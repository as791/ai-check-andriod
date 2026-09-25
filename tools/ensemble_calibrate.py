#!/usr/bin/env python3
"""Fit the app's two-model ensemble: standardization, photo and video calibration.

The app combines the bundled detector (two views, logit gap d) and Community Forensics
224 (logit c) as the mean of their standardized logits:

    s = ((d - mean_d) / std_d + (c - mean_c) / std_c) / 2

then calibrates s separately for photos and for video (a video's s uses the per-video
mean of each model's frame logits, which is exact because s is linear):

    P(ai) = sigmoid(slope * s + intercept)

Standardization is fit on the pooled photo scores only (label-free), so the same
constants serve both paths. Inputs are the per-item scores of the exact files the app
bundles (evaluate.py / eval_video.py --commfor-onnx). Reports, per dataset: AUC and AI
caught at <=5% false alarms for each model alone vs the ensemble, the calibration fit
(cross-dataset check), and the worst case at the app's HIGH/LOW bands.

Usage:
    python tools/ensemble_calibrate.py --image-scores image-scores.csv \\
        --video-scores video-scores.csv --json ensemble-params.json > ensemble.md
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path
from types import SimpleNamespace

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from calibrate import analyze_mode, auc, fit_platt, sigmoid, worst_case  # noqa: E402
from ensemble import tpr_at_fpr  # noqa: E402
from evaluate import COMMFOR_MODEL_NAME, HIGH_THRESHOLD, LOW_THRESHOLD  # noqa: E402

BUNDLED_MODEL_NAME = "app as shipped"


def load_pairs(path: Path, bundled_preprocess: str, commfor_preprocess: str) -> list[dict]:
    """Rows where both models scored the same item: {dataset, condition, is_ai, d, c}."""
    table: dict[tuple, dict] = defaultdict(dict)
    with path.open() as f:
        for row in csv.DictReader(f):
            key = (row["dataset"], row["condition"], row["image"])
            if row["model"] == BUNDLED_MODEL_NAME and row["preprocess"] == bundled_preprocess:
                table[key]["d"] = float(row["logit_diff"])
            elif row["model"] == COMMFOR_MODEL_NAME and row["preprocess"] == commfor_preprocess:
                table[key]["c"] = float(row["logit_diff"])
            else:
                continue
            table[key].update(dataset=key[0], condition=key[1], is_ai=int(row["is_ai"]))
    return [v for v in table.values() if "d" in v and "c" in v]


def summarize(name: str, rows: list[dict], stats: dict, out: list[str]) -> None:
    out.append(f"### {name}: each model alone vs the ensemble\n")
    datasets = sorted({r["dataset"] for r in rows})
    out.append("| Score | " + " | ".join(f"AUC {d}" for d in datasets) + " | "
               + " | ".join(f"AI caught @5% FA {d}" for d in datasets) + " |")
    out.append("|---|" + "---|" * (2 * len(datasets)))
    for label, key in (("Bundled (two views)", "d"), ("Community Forensics 224", "c"), ("**Ensemble**", "s")):
        aucs, tprs = [], []
        for d in datasets:
            subset = [r for r in rows if r["dataset"] == d]
            y = np.array([r["is_ai"] for r in subset])
            x = np.array([r[key] for r in subset])
            aucs.append(f"{auc(y, x):.3f}")
            tprs.append(f"{tpr_at_fpr(y, x):.1%}")
            stats.setdefault(name, {}).setdefault(key, {})[d] = {"auc": float(aucs[-1]), "tpr5": tprs[-1]}
        out.append(f"| {label} | " + " | ".join(aucs) + " | " + " | ".join(tprs) + " |")
    out.append("")


def calibrate_rows(name: str, rows: list[dict], out: list[str]) -> dict:
    calib_rows = [{"dataset": r["dataset"], "condition": r["condition"], "is_ai": r["is_ai"],
                   "logit_diff": r["s"]} for r in rows]
    settings = SimpleNamespace(max_real_high=0.05, max_ai_low=0.10, min_gap=0.20)
    result = analyze_mode(name, calib_rows, settings, out)
    if result["slope"] <= 0:
        # A non-positive slope would mean "more AI-like evidence -> lower P(ai)": a broken
        # ensemble (e.g. a flipped label), never something to ship.
        sys.exit(f"{name}: calibration slope {result['slope']:.4f} <= 0 - refusing to produce app constants")
    real_high, _ = worst_case(calib_rows, result["slope"], result["intercept"], HIGH_THRESHOLD, 0)
    _, ai_low = worst_case(calib_rows, result["slope"], result["intercept"], 1.1, LOW_THRESHOLD)
    ai_high = min(
        float((sigmoid(result["slope"] * np.array([r["logit_diff"] for r in group]) + result["intercept"])
               >= HIGH_THRESHOLD).mean())
        for group in [[r for r in calib_rows if r["is_ai"] == 1 and r["dataset"] == d and r["condition"] == c]
                      for d, c in {(r["dataset"], r["condition"]) for r in calib_rows}]
        if group
    )
    out.append(f"At the app's bands (HIGH ≥ {HIGH_THRESHOLD:.2f}, LOW < {LOW_THRESHOLD:.2f}), worst case: "
               f"**{real_high:.1%} of real shown HIGH**, {ai_low:.1%} of AI shown LOW, "
               f"and at least {ai_high:.1%} of AI shown HIGH.\n")
    result.update(real_high_at_app_bands=real_high, ai_low_at_app_bands=ai_low, min_ai_high_at_app_bands=ai_high)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image-scores", type=Path, required=True)
    parser.add_argument("--video-scores", type=Path, default=None)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    images = load_pairs(args.image_scores, "avg", "native")
    if not images:
        sys.exit("No image items scored by both models - check the model names in the CSV.")
    d_all = np.array([r["d"] for r in images])
    c_all = np.array([r["c"] for r in images])
    std = {"mean_d": float(d_all.mean()), "std_d": float(d_all.std()),
           "mean_c": float(c_all.mean()), "std_c": float(c_all.std())}

    def combine(rows: list[dict]) -> None:
        for r in rows:
            r["s"] = ((r["d"] - std["mean_d"]) / std["std_d"] + (r["c"] - std["mean_c"]) / std["std_c"]) / 2.0

    combine(images)
    out = ["# App ensemble: bundled + Community Forensics 224\n",
           "Scores come from the exact ONNX files the app bundles (fp16 weights). The ensemble score is the "
           "mean of both models' standardized logits; standardization is fit on the photo scores and "
           f"shared with video: mean_d={std['mean_d']:.4f}, std_d={std['std_d']:.4f}, "
           f"mean_c={std['mean_c']:.4f}, std_c={std['std_c']:.4f}.\n"]
    stats: dict = {}
    summarize("Photos", images, stats, out)
    photo = calibrate_rows("ensemble (photos)", images, out)

    video = None
    if args.video_scores and args.video_scores.exists():
        videos = load_pairs(args.video_scores, "video5", "video5")
        if videos:
            combine(videos)
            summarize("Video", videos, stats, out)
            video = calibrate_rows("ensemble (video)", videos, out)

    print("\n".join(out))
    if args.json:
        args.json.write_text(json.dumps({"standardization": std, "photo": photo, "video": video,
                                         "comparison": stats}, indent=2, default=float))


if __name__ == "__main__":
    main()
