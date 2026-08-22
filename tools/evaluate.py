#!/usr/bin/env python3
"""Evaluate the bundled AI-image classifier against a labeled dataset.

This is the tool referred to throughout docs/MODEL.md and docs/ARCHITECTURE.md as
the intended source of truth for detector quality — AI Check's evidence weights and
classification thresholds should eventually be calibrated from real numbers this
script produces, not guessed. It has NOT been run against a real dataset as part of
building this repository: no labeled AI/real image corpus and no ML runtime were
available in the environment this project was built in (see README "Known
limitations"). Running it is a maintainer task before shipping the classifier
signal as a serious product claim.

Usage:
    python tools/evaluate.py \\
        --model app/src/main/assets/models/ai-image-detector.onnx \\
        --dataset /path/to/dataset \\
        --threshold 0.5

Expected dataset layout (binary classification, folder name = ground truth):

    dataset/
      ai/            # known AI-generated images
        img001.jpg
        img002.png
        ...
      real/          # known non-AI (camera/human-made) images
        img001.jpg
        ...

Prints accuracy, precision, recall, F1, a confusion matrix, and the false-positive /
false-negative rates ("positive" = AI-generated), matching what a product decision
about classification thresholds (see EvidenceWeights.HIGH_THRESHOLD /
LOW_THRESHOLD in the domain module) should be based on.

Preprocessing here MUST match app/src/main/kotlin/.../classifier/ModelConfig.kt
exactly (input size, channel order, normalization) or these numbers will not
reflect what the app actually does on-device.
"""

from __future__ import annotations

import argparse
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

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


@dataclass
class Prediction:
    path: Path
    ground_truth_is_ai: bool
    ai_probability: float


def preprocess(image_path: Path) -> np.ndarray:
    image = Image.open(image_path).convert("RGB").resize((INPUT_SIZE, INPUT_SIZE))
    array = np.asarray(image, dtype=np.float32) / 255.0
    array = (array - MEAN) / STD
    chw = np.transpose(array, (2, 0, 1))  # HWC -> CHW
    return np.expand_dims(chw, axis=0).astype(np.float32)


def interpret_output(raw_output: np.ndarray) -> float:
    """Mirrors ModelConfig.interpretOutput in the Kotlin code."""
    flat = raw_output.reshape(-1)
    if flat.size == 2:
        human, ai = float(flat[0]), float(flat[1])
        total = human + ai
        return ai / total if total > 0 else 0.5
    if flat.size == 1:
        return float(np.clip(flat[0], 0.0, 1.0))
    return 0.5


def run_inference(session: "ort.InferenceSession", image_path: Path) -> float:
    tensor = preprocess(image_path)
    outputs = session.run(None, {INPUT_NAME: tensor})
    return interpret_output(outputs[0])


def collect_images(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted(p for p in directory.iterdir() if p.suffix.lower() in IMAGE_EXTENSIONS)


def evaluate(model_path: Path, dataset_dir: Path, threshold: float) -> list[Prediction]:
    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])

    ai_images = collect_images(dataset_dir / "ai")
    real_images = collect_images(dataset_dir / "real")

    if not ai_images and not real_images:
        print(
            f"No images found under {dataset_dir}/ai or {dataset_dir}/real. "
            "See this script's docstring for the expected layout.",
            file=sys.stderr,
        )
        sys.exit(1)

    predictions: list[Prediction] = []
    for path in ai_images:
        predictions.append(Prediction(path, True, run_inference(session, path)))
    for path in real_images:
        predictions.append(Prediction(path, False, run_inference(session, path)))

    return predictions


def print_report(predictions: list[Prediction], threshold: float) -> None:
    true_positive = false_positive = true_negative = false_negative = 0

    for prediction in predictions:
        predicted_ai = prediction.ai_probability >= threshold
        if prediction.ground_truth_is_ai and predicted_ai:
            true_positive += 1
        elif prediction.ground_truth_is_ai and not predicted_ai:
            false_negative += 1
        elif not prediction.ground_truth_is_ai and predicted_ai:
            false_positive += 1
        else:
            true_negative += 1

    total = len(predictions)
    accuracy = (true_positive + true_negative) / total if total else 0.0
    precision = true_positive / (true_positive + false_positive) if (true_positive + false_positive) else 0.0
    recall = true_positive / (true_positive + false_negative) if (true_positive + false_negative) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    fpr = false_positive / (false_positive + true_negative) if (false_positive + true_negative) else 0.0
    fnr = false_negative / (false_negative + true_positive) if (false_negative + true_positive) else 0.0

    print(f"Images evaluated: {total} (threshold={threshold})")
    print()
    print("Confusion matrix (rows = actual, columns = predicted):")
    print(f"{'':>14}{'Predicted AI':>14}{'Predicted Real':>16}")
    print(f"{'Actual AI':>14}{true_positive:>14}{false_negative:>16}")
    print(f"{'Actual Real':>14}{false_positive:>14}{true_negative:>16}")
    print()
    print(f"Accuracy:              {accuracy:.3f}")
    print(f"Precision (AI class):  {precision:.3f}")
    print(f"Recall (AI class):     {recall:.3f}")
    print(f"F1 (AI class):         {f1:.3f}")
    print(f"False positive rate:   {fpr:.3f}  (real images flagged as AI)")
    print(f"False negative rate:   {fnr:.3f}  (AI images missed)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", type=Path, required=True, help="Path to the .onnx classifier")
    parser.add_argument("--dataset", type=Path, required=True, help="Path to the dataset/ai, dataset/real folder")
    parser.add_argument("--threshold", type=float, default=0.5, help="AI-probability threshold for a positive prediction")
    args = parser.parse_args()

    if not args.model.exists():
        print(f"Model file not found: {args.model}", file=sys.stderr)
        sys.exit(1)

    predictions = evaluate(args.model, args.dataset, args.threshold)
    print_report(predictions, args.threshold)


if __name__ == "__main__":
    main()
