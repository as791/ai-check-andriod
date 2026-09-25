#!/usr/bin/env python3
"""Merge tools/evaluate.py --json-dir results into one markdown report.

Usage:
    python tools/eval_report.py results/ > report.md
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

CONDITION_ORDER = {"original": 0, "jpeg75": 1, "social": 2, "video": 3}
PREPROCESS_ORDER = {"squash": 0, "center_crop": 1, "avg": 2, "native": 3, "video5": 4}
BUNDLED = "dafilab (bundled)"


def pct(value: float | None) -> str:
    return "n/a" if value is None else f"{value * 100:.1f}%"


def num(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    results = [json.loads(p.read_text()) for p in sorted(Path(sys.argv[1]).glob("*.json"))]
    results = [r for r in results if "metrics" in r]  # skip e.g. calibration.json
    for r in results:
        r.setdefault("model", BUNDLED)
    if not results:
        print("No results found - every dataset fetch or evaluation failed; see the job log.")
        return
    results.sort(key=lambda r: (r["dataset"], r["model"] != BUNDLED, r["model"],
                                CONDITION_ORDER.get(r["condition"], 9), PREPROCESS_ORDER.get(r["preprocess"], 9)))

    out = []
    out.append("# Genned detector accuracy benchmark\n")
    out.append("Model: bundled `Dafilab/ai-image-detector` ONNX export. `squash` = what the app does today; "
               "`social` = long edge ≤1080 + JPEG q75 (Instagram re-upload). Every condition then gets the app's own normalization (long edge ≤2048, JPEG q92), as on-device. "
               "Threshold 0.5 for accuracy/FPR/FNR; bands use the app's LOW <30% / HIGH ≥70%.\n")
    out.append("> If the model was trained on one of these datasets, its numbers there are inflated. "
               "Treat a dataset that scores far better than the others with suspicion.\n")

    out.append("## Overall\n")
    out.append("| Dataset | Model | Condition | Preprocess | n (AI/real) | AUC | Accuracy | FPR (real→AI) "
               "| FNR (AI missed) | Real shown HIGH | AI shown LOW | ECE | Scores >99% or <1% |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        m = r["metrics"]
        out.append(
            f"| {r['dataset']} | {r['model']} | {r['condition']} | {r['preprocess']} | {m['n_ai']}/{m['n_real']} "
            f"| {num(m['auc'])} | {pct(m['accuracy'])} | {pct(m['fpr'])} | {pct(m['fnr'])} "
            f"| {pct(m['bands_real']['high'])} | {pct(m['bands_ai']['low'])} | {num(m['ece'])} "
            f"| {pct(m['extreme_share'])} |"
        )

    out.append("\n## Per generator (bundled: squash; candidates: native)\n")
    out.append("Share classified correctly at 0.5 (for `real`: share *not* flagged as AI).\n")
    for dataset, model in sorted({(r["dataset"], r["model"]) for r in results}, key=lambda k: (k[0], k[1] != BUNDLED, k[1])):
        rows = [r for r in results if r["dataset"] == dataset and r["model"] == model
                and r["preprocess"] in ("squash", "native", "video5")]
        if not rows:
            continue
        generators = sorted({g for r in rows for g in r["metrics"]["per_generator"]},
                            key=lambda g: (g != "real", g))
        out.append(f"### {dataset} · {model}\n")
        out.append("| Generator | " + " | ".join(r["condition"] for r in rows) + " |")
        out.append("|---|" + "---|" * len(rows))
        for g in generators:
            cells = []
            for r in rows:
                stats = r["metrics"]["per_generator"].get(g)
                cells.append("–" if stats is None else f"{pct(stats['correct_at_threshold'])} (n={stats['n']})")
            out.append(f"| {g} | " + " | ".join(cells) + " |")
        out.append("")

    out.append("## Calibration (original, squash)\n")
    out.append("When the model says X%, how often is the image actually AI?\n")
    for r in results:
        if r["condition"] not in ("original", "video") or r["preprocess"] not in ("squash", "native", "video5"):
            continue
        out.append(f"### {r['dataset']} · {r['model']}\n")
        out.append("| Score bin | n | Mean score | Actually AI |")
        out.append("|---|---|---|---|")
        for b in r["metrics"]["reliability"]:
            out.append(f"| {b['bin']} | {b['n']} | {pct(b['mean_score'])} | {pct(b['frac_ai'])} |")
        out.append("")

    print("\n".join(out))


if __name__ == "__main__":
    main()
