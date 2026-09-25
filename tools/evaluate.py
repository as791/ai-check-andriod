#!/usr/bin/env python3
"""Evaluate the bundled AI-image classifier against a labeled dataset.

This is the tool referred to throughout internal-docs/MODEL.md and internal-docs/ARCHITECTURE.md as
the intended source of truth for detector quality — Genned's evidence weights and
classification thresholds should be calibrated from the numbers this script
produces, not guessed. The `model-eval` GitHub Actions workflow
(.github/workflows/model-eval.yml) runs it against public labeled datasets; see
internal-docs/MODEL.md "Measured accuracy".

Usage:
    python tools/evaluate.py \\
        --model app/src/main/assets/models/ai-image-detector.onnx \\
        --dataset /path/to/dataset \\
        --threshold 0.5

    # Several degradation conditions / preprocessing modes in one pass, with a
    # JSON result per combination (consumed by tools/eval_report.py):
    python tools/evaluate.py --model ... --dataset ... \\
        --conditions original,jpeg75,social --preprocess squash,center_crop \\
        --json-dir results/

Expected dataset layout (binary classification, folder name = ground truth):

    dataset/
      ai/                # known AI-generated images, optionally grouped
        sdxl/img001.png  # by generator (subfolder name = generator)
        img002.png       # (or directly in ai/ -> generator "ai")
      real/              # known non-AI (camera/human-made) images
        img001.jpg

Conditions (applied to each image before preprocessing):
    original  the file as-is
    jpeg75    re-encoded once as JPEG quality 75
    social    what a post seen through Instagram goes through: long edge
              downscaled to <=1080, then JPEG q75 (platform re-upload)

Every condition then goes through the app's own normalization, exactly as
ImageLoader does before the classifier ever sees the image: long edge capped at
2048, re-encoded as JPEG quality 92.

Preprocessing modes (image -> 380x380 model input):
    squash       resize straight to 380x380, ignoring aspect ratio - what the app
                 does today (AIImageClassifierProvider.preprocess)
    center_crop  resize the short side to 380, then center-crop 380x380
    avg          mean of the squash and center_crop logit differences (2 inferences)

Scores are raw model probabilities unless --calibration SLOPE,INTERCEPT is given,
in which case P(ai) = sigmoid(SLOPE * (ai_logit - human_logit) + INTERCEPT) - the
same transform ModelConfig.interpretOutput applies on-device. --scores-csv writes
one row per image and combination (model, dataset, condition, preprocess, image, generator,
is_ai, logit_diff) for tools/calibrate.py and tools/ensemble.py. `image` is the
sample's path inside the fetched dataset folder (e.g. real/00012.jpg), used only to
join models per image; no pixels or source file names.

Resizing uses bilinear filtering to match Android's
Bitmap.createScaledBitmap(..., filter = true). Normalization/channel order MUST
match app/src/main/kotlin/.../classifier/ModelConfig.kt or these numbers will not
reflect what the app actually does on-device.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

try:
    import onnxruntime as ort
except ImportError:  # pragma: no cover - dependency check, not test-covered
    print("onnxruntime is required: pip install -r tools/requirements.txt", file=sys.stderr)
    raise

# Keep these in sync with ModelConfig.kt.
INPUT_SIZE = 380
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
INPUT_NAME = "pixel_values"

# Keep these in sync with EvidenceWeights.kt (domain module).
LOW_THRESHOLD = 0.25
HIGH_THRESHOLD = 0.90

# Keep these in sync with ModelConfig.CALIBRATION_SLOPE / CALIBRATION_INTERCEPT and the
# app's two-view ("avg") preprocessing. Pass --calibration app to evaluate the app as shipped.
APP_CALIBRATION = (0.2252, 0.0494)
# Video path (VideoSignalAggregator): mean per-frame logit gap -> this calibration.
# Keep in sync with ModelConfig.VIDEO_CALIBRATION_*; None until fitted from Video eval.
APP_VIDEO_CALIBRATION = None

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
CONDITIONS = ("original", "jpeg75", "social")
PREPROCESS_MODES = ("squash", "center_crop", "avg")
BASE_MODES = ("squash", "center_crop")


@dataclass
class Prediction:
    path: Path
    ground_truth_is_ai: bool
    ai_probability: float
    generator: str = "real"
    logit_diff: float = 0.0


def _jpeg(image: Image.Image, quality: int) -> Image.Image:
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality)
    buffer.seek(0)
    return Image.open(buffer).convert("RGB")


def degrade(image: Image.Image, condition: str) -> Image.Image:
    if condition == "original":
        return image
    if condition == "jpeg75":
        return _jpeg(image, 75)
    if condition == "social":
        long_edge = max(image.size)
        if long_edge > 1080:
            scale = 1080 / long_edge
            image = image.resize(
                (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
                Image.BILINEAR,
            )
        return _jpeg(image, 75)
    raise ValueError(f"Unknown condition: {condition}")


def app_normalize(image: Image.Image) -> Image.Image:
    """Mirrors ImageLoader: long edge capped at MAX_DIMENSION_PX (2048), JPEG quality 92."""
    long_edge = max(image.size)
    if long_edge > 2048:
        scale = 2048 / long_edge
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.BILINEAR,
        )
    return _jpeg(image, 92)


def to_model_input(image: Image.Image, mode: str) -> Image.Image:
    if mode == "squash":
        return image.resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
    if mode == "center_crop":
        scale = INPUT_SIZE / min(image.size)
        width = max(INPUT_SIZE, round(image.width * scale))
        height = max(INPUT_SIZE, round(image.height * scale))
        resized = image.resize((width, height), Image.BILINEAR)
        left = (width - INPUT_SIZE) // 2
        top = (height - INPUT_SIZE) // 2
        return resized.crop((left, top, left + INPUT_SIZE, top + INPUT_SIZE))
    raise ValueError(f"Unknown preprocess mode: {mode}")


def to_tensor(image: Image.Image) -> np.ndarray:
    array = np.asarray(image, dtype=np.float32) / 255.0
    array = (array - MEAN) / STD
    chw = np.transpose(array, (2, 0, 1))  # HWC -> CHW
    return np.expand_dims(chw, axis=0).astype(np.float32)


def preprocess(image_path: Path, condition: str = "original", mode: str = "squash") -> np.ndarray:
    image = Image.open(image_path).convert("RGB")
    return to_tensor(to_model_input(app_normalize(degrade(image, condition)), mode))


def _sigmoid(x: float) -> float:
    # Written to saturate cleanly for extreme logits instead of overflowing exp().
    if x >= 0:
        return 1.0 / (1.0 + np.exp(-x))
    e = np.exp(x)
    return float(e / (1.0 + e))


def interpret_output(raw_output: np.ndarray) -> float:
    """Mirrors ModelConfig.interpretOutput in the Kotlin code - keep them in sync.

    The export emits raw logits (output tensor "logits"; no softmax layer in the
    graph), ordered [ai, human] per the model's config.json label_mapping
    ({"0": "ai", "1": "human"}). P(ai) is therefore the 2-class softmax, i.e.
    sigmoid(ai_logit - human_logit).

    An earlier version of this function had the label order inverted AND used
    ai / (ai + human) - not a softmax, and it collapsed to 0.5 whenever both logits
    were negative - so evaluation numbers from it were meaningless.
    """
    flat = raw_output.reshape(-1)
    if np.isnan(flat).any():
        return 0.5
    if flat.size == 2:
        ai, human = float(flat[0]), float(flat[1])
        return float(_sigmoid(ai - human))
    if flat.size == 1:
        return float(_sigmoid(float(flat[0])))
    return 0.5


def logit_difference(raw_output: np.ndarray) -> float:
    """ai_logit - human_logit (or the single AI logit): the input interpret_output squashes."""
    flat = raw_output.reshape(-1)
    if np.isnan(flat).any():
        return 0.0
    if flat.size == 2:
        return float(flat[0]) - float(flat[1])
    if flat.size == 1:
        return float(flat[0])
    return 0.0


def calibrated_probability(logit_diff: float, slope: float = 1.0, intercept: float = 0.0) -> float:
    """sigmoid(slope * logit_diff + intercept); slope=1, intercept=0 is the raw model output."""
    return float(_sigmoid(slope * logit_diff + intercept))


def run_inference(session: "ort.InferenceSession", tensor: np.ndarray) -> float:
    """Returns the logit difference; see calibrated_probability for P(ai)."""
    outputs = session.run(None, {INPUT_NAME: tensor})
    return logit_difference(outputs[0])


def collect_images(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted(p for p in directory.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS)


def generator_of(path: Path, ai_dir: Path) -> str:
    relative = path.relative_to(ai_dir)
    return relative.parts[0] if len(relative.parts) > 1 else "ai"


def evaluate_all(
    model_path: Path,
    dataset_dir: Path,
    conditions: list[str],
    modes: list[str],
    slope: float = 1.0,
    intercept: float = 0.0,
) -> dict[tuple[str, str], list[Prediction]]:
    """Runs every (condition, preprocess) combination, decoding each image once."""
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])

    ai_dir = dataset_dir / "ai"
    labeled = [(p, True, generator_of(p, ai_dir)) for p in collect_images(ai_dir)]
    labeled += [(p, False, "real") for p in collect_images(dataset_dir / "real")]

    if not labeled:
        print(
            f"No images found under {dataset_dir}/ai or {dataset_dir}/real. "
            "See this script's docstring for the expected layout.",
            file=sys.stderr,
        )
        sys.exit(1)

    results: dict[tuple[str, str], list[Prediction]] = {(c, m): [] for c in conditions for m in modes}
    skipped = 0
    for index, (path, is_ai, generator) in enumerate(labeled, start=1):
        try:
            image = Image.open(path).convert("RGB")
        except Exception as e:  # noqa: BLE001 - one unreadable file must not abort the run
            print(f"Skipping unreadable image {path}: {e}", file=sys.stderr)
            skipped += 1
            continue
        for condition in conditions:
            degraded = app_normalize(degrade(image, condition))
            needed = {m for m in modes if m in BASE_MODES} | (set(BASE_MODES) if "avg" in modes else set())
            diffs = {m: run_inference(session, to_tensor(to_model_input(degraded, m))) for m in needed}
            if "avg" in modes:
                diffs["avg"] = (diffs["squash"] + diffs["center_crop"]) / 2.0
            for mode in modes:
                d = diffs[mode]
                results[(condition, mode)].append(
                    Prediction(path, is_ai, calibrated_probability(d, slope, intercept), generator, d)
                )
        if index % 100 == 0:
            print(f"  {index}/{len(labeled)} images", file=sys.stderr)
    if skipped:
        print(f"Skipped {skipped} unreadable images", file=sys.stderr)
    return results


def evaluate(model_path: Path, dataset_dir: Path, threshold: float) -> list[Prediction]:
    """Original single-configuration entry point (app behavior: original file, squash)."""
    return evaluate_all(model_path, dataset_dir, ["original"], ["squash"])[("original", "squash")]


def roc_auc(labels: np.ndarray, scores: np.ndarray) -> float | None:
    """Mann-Whitney AUC with average ranks for ties (no sklearn dependency)."""
    positives = int(labels.sum())
    negatives = len(labels) - positives
    if positives == 0 or negatives == 0:
        return None
    order = np.argsort(scores, kind="mergesort")
    sorted_scores = scores[order]
    ranks = np.empty(len(scores), dtype=np.float64)
    i = 0
    while i < len(scores):
        j = i
        while j + 1 < len(scores) and sorted_scores[j + 1] == sorted_scores[i]:
            j += 1
        ranks[order[i : j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    positive_rank_sum = ranks[labels == 1].sum()
    return float((positive_rank_sum - positives * (positives + 1) / 2.0) / (positives * negatives))


def reliability(labels: np.ndarray, scores: np.ndarray, bins: int = 10) -> tuple[float, list[dict]]:
    """Expected calibration error of P(ai) plus the per-bin table behind it."""
    edges = np.linspace(0.0, 1.0, bins + 1)
    table = []
    ece = 0.0
    for b in range(bins):
        low, high = edges[b], edges[b + 1]
        mask = (scores >= low) & ((scores < high) if b < bins - 1 else (scores <= high))
        count = int(mask.sum())
        if count == 0:
            table.append({"bin": f"{low:.1f}-{high:.1f}", "n": 0, "mean_score": None, "frac_ai": None})
            continue
        mean_score = float(scores[mask].mean())
        frac_ai = float(labels[mask].mean())
        ece += count / len(scores) * abs(mean_score - frac_ai)
        table.append({"bin": f"{low:.1f}-{high:.1f}", "n": count, "mean_score": mean_score, "frac_ai": frac_ai})
    return float(ece), table


def _bands(scores: np.ndarray) -> dict[str, float]:
    if len(scores) == 0:
        return {"low": 0.0, "uncertain": 0.0, "high": 0.0}
    return {
        "low": float((scores < LOW_THRESHOLD).mean()),
        "uncertain": float(((scores >= LOW_THRESHOLD) & (scores < HIGH_THRESHOLD)).mean()),
        "high": float((scores >= HIGH_THRESHOLD).mean()),
    }


def compute_metrics(predictions: list[Prediction], threshold: float) -> dict:
    labels = np.array([1 if p.ground_truth_is_ai else 0 for p in predictions], dtype=np.int64)
    scores = np.array([p.ai_probability for p in predictions], dtype=np.float64)
    predicted = scores >= threshold

    tp = int((predicted & (labels == 1)).sum())
    fn = int((~predicted & (labels == 1)).sum())
    fp = int((predicted & (labels == 0)).sum())
    tn = int((~predicted & (labels == 0)).sum())
    total = len(predictions)
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    ece, reliability_table = reliability(labels, scores)

    per_generator = {}
    for generator in sorted({p.generator for p in predictions}):
        generator_scores = np.array([p.ai_probability for p in predictions if p.generator == generator])
        is_real = generator == "real"
        per_generator[generator] = {
            "n": int(len(generator_scores)),
            # For AI generators: share detected; for real: share correctly passed.
            "correct_at_threshold": float(
                ((generator_scores < threshold) if is_real else (generator_scores >= threshold)).mean()
            ),
            "mean_score": float(generator_scores.mean()),
            "bands": _bands(generator_scores),
        }

    return {
        "n": total,
        "n_ai": int(labels.sum()),
        "n_real": int(total - labels.sum()),
        "threshold": threshold,
        "confusion": {"tp": tp, "fn": fn, "fp": fp, "tn": tn},
        "accuracy": (tp + tn) / total if total else 0.0,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0,
        "fpr": fp / (fp + tn) if (fp + tn) else 0.0,
        "fnr": fn / (fn + tp) if (fn + tp) else 0.0,
        "auc": roc_auc(labels, scores),
        "ece": ece,
        "reliability": reliability_table,
        "bands_real": _bands(scores[labels == 0]),
        "bands_ai": _bands(scores[labels == 1]),
        "extreme_share": float(((scores > 0.99) | (scores < 0.01)).mean()) if total else 0.0,
        "per_generator": per_generator,
    }


def print_report(predictions: list[Prediction], threshold: float) -> None:
    m = compute_metrics(predictions, threshold)
    c = m["confusion"]
    print(f"Images evaluated: {m['n']} (threshold={threshold})")
    print()
    print("Confusion matrix (rows = actual, columns = predicted):")
    print(f"{'':>14}{'Predicted AI':>14}{'Predicted Real':>16}")
    print(f"{'Actual AI':>14}{c['tp']:>14}{c['fn']:>16}")
    print(f"{'Actual Real':>14}{c['fp']:>14}{c['tn']:>16}")
    print()
    print(f"Accuracy:              {m['accuracy']:.3f}")
    print(f"Precision (AI class):  {m['precision']:.3f}")
    print(f"Recall (AI class):     {m['recall']:.3f}")
    print(f"F1 (AI class):         {m['f1']:.3f}")
    print(f"False positive rate:   {m['fpr']:.3f}  (real images flagged as AI)")
    print(f"False negative rate:   {m['fnr']:.3f}  (AI images missed)")
    auc = m["auc"]
    print(f"ROC-AUC:               {'n/a' if auc is None else f'{auc:.3f}'}")
    print(f"ECE (calibration):     {m['ece']:.3f}  (0 = scores mean what they say)")
    print(f"Scores >99% or <1%:    {m['extreme_share']:.1%}")
    print()
    print(f"App bands (LOW <{LOW_THRESHOLD:.2f} / UNCERTAIN / HIGH >={HIGH_THRESHOLD:.2f}):")
    for name in ("real", "ai"):
        b = m[f"bands_{name}"]
        print(f"  {name:>4}: LOW {b['low']:.1%}  UNCERTAIN {b['uncertain']:.1%}  HIGH {b['high']:.1%}")
    print()
    print("Per generator (share classified correctly at threshold, mean P(ai)):")
    for generator, g in m["per_generator"].items():
        print(f"  {generator:>16}: n={g['n']:<5} correct={g['correct_at_threshold']:.1%}  mean={g['mean_score']:.3f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", type=Path, required=True, help="Path to the .onnx classifier")
    parser.add_argument("--dataset", type=Path, required=True, help="Path to the dataset/ai, dataset/real folder")
    parser.add_argument("--threshold", type=float, default=0.5, help="AI-probability threshold for a positive prediction")
    parser.add_argument("--conditions", default="original", help=f"Comma-separated subset of {','.join(CONDITIONS)}")
    parser.add_argument("--preprocess", default="squash", help=f"Comma-separated subset of {','.join(PREPROCESS_MODES)}")
    parser.add_argument("--dataset-name", default=None, help="Name used in JSON output (default: dataset folder name)")
    parser.add_argument("--json-dir", type=Path, default=None, help="Write one JSON result per combination here")
    parser.add_argument("--model-name", default=None, help="Label for this configuration in reports")
    parser.add_argument("--scores-csv", type=Path, default=None, help="Append per-image logit differences here")
    parser.add_argument("--calibration", default=None, help="SLOPE,INTERCEPT as in ModelConfig.interpretOutput, or 'app' for the shipped values")
    args = parser.parse_args()

    slope, intercept = 1.0, 0.0
    if args.calibration:
        try:
            slope, intercept = (APP_CALIBRATION if args.calibration == "app"
                                else (float(v) for v in args.calibration.split(",")))
        except ValueError:
            parser.error("--calibration must be SLOPE,INTERCEPT, e.g. 0.4,-0.1")

    conditions = [c.strip() for c in args.conditions.split(",") if c.strip()]
    modes = [m.strip() for m in args.preprocess.split(",") if m.strip()]
    for c in conditions:
        if c not in CONDITIONS:
            parser.error(f"unknown condition {c!r}; choose from {CONDITIONS}")
    for m in modes:
        if m not in PREPROCESS_MODES:
            parser.error(f"unknown preprocess mode {m!r}; choose from {PREPROCESS_MODES}")

    if not args.model.exists():
        print(f"Model file not found: {args.model}", file=sys.stderr)
        sys.exit(1)

    dataset_name = args.dataset_name or args.dataset.resolve().name
    results = evaluate_all(args.model, args.dataset, conditions, modes, slope, intercept)
    if args.json_dir:
        args.json_dir.mkdir(parents=True, exist_ok=True)

    for (condition, mode), predictions in results.items():
        print()
        print(f"=== {dataset_name} | condition={condition} | preprocess={mode} ===")
        print_report(predictions, args.threshold)
        if args.json_dir:
            payload = {
                **({"model": args.model_name} if args.model_name else {}),
                "dataset": dataset_name,
                "condition": condition,
                "preprocess": mode,
                "calibration": {"slope": slope, "intercept": intercept} if args.calibration else None,
                "metrics": compute_metrics(predictions, args.threshold),
            }
            prefix = f"{args.model_name.replace(' ', '_').replace('/', '_')}__" if args.model_name else ""
            out = args.json_dir / f"{prefix}{dataset_name}__{condition}__{mode}.json"
            out.write_text(json.dumps(payload, indent=2))

    if args.scores_csv:
        new_file = not args.scores_csv.exists()
        with args.scores_csv.open("a", newline="") as f:
            writer = csv.writer(f)
            if new_file:
                writer.writerow(["model", "dataset", "condition", "preprocess", "image", "generator", "is_ai",
                                 "logit_diff"])
            for (condition, mode), predictions in results.items():
                for p in predictions:
                    writer.writerow([args.model_name or "dafilab (bundled)", dataset_name, condition, mode,
                                     p.path.relative_to(args.dataset).as_posix(), p.generator,
                                     int(p.ground_truth_is_ai), f"{p.logit_diff:.6f}"])


if __name__ == "__main__":
    main()
