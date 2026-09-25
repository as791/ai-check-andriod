#!/usr/bin/env python3
"""Does combining detectors beat the best single one? Ensemble analysis from per-image scores.

Joins the per-image logit gaps that tools/evaluate.py (bundled model) and
tools/eval_candidates.py (candidates) write with --scores-csv, on (dataset,
condition, image). It then scores every single model and every combination of 2+
models two ways:

  mean   average of per-model standardized logit gaps (no label fitting at all)
  stack  logistic regression on the per-model logit gaps (learned weights)

To catch overfitting, the stack is scored **cross-dataset**: weights fit on one
dataset and evaluated on the other. Only that transfer number counts. The headline
metric is the worst dataset's **AI detection rate at a 5% false-alarm rate** (share
of AI images that can be shown HIGH while at most 5% of real images are), which is
exactly the trade-off behind the app's HIGH band.

With --feasibility (tools/onnx_feasibility.py JSON) every combination also gets its
on-device cost: total int8 (else fp32) ONNX size and summed latency. The
recommendation is the best worst-case detection rate within --max-mb / --max-ms, and
an ensemble is recommended only if it beats the best affordable single model by
--min-gain points; otherwise the extra size and latency aren't worth it.

Usage:
    python tools/ensemble.py bundled-scores.csv candidate-scores.csv \\
        --feasibility onnx_feasibility.json --json ensemble.json > ensemble.md
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from calibrate import auc  # noqa: E402

# Which preprocessing represents each model as it would run in the app.
SHIPPED_PREPROCESS = {"avg", "native", "video5"}


def load(paths: list[Path]) -> tuple[list[str], dict[str, dict]]:
    """-> models, {dataset: {"X": [n, models], "y": [n], "condition": [n]}} for images all models scored."""
    table: dict[tuple, dict[str, float]] = defaultdict(dict)
    labels: dict[tuple, int] = {}
    models: set[str] = set()
    for path in paths:
        with path.open() as f:
            for row in csv.DictReader(f):
                if row["preprocess"] not in SHIPPED_PREPROCESS:
                    continue
                key = (row["dataset"], row["condition"], row["image"])
                table[key][row["model"]] = float(row["logit_diff"])
                labels[key] = int(row["is_ai"])
                models.add(row["model"])
    ordered = sorted(models, key=lambda m: (not m.startswith("dafilab"), m))
    data: dict[str, dict] = defaultdict(lambda: {"X": [], "y": [], "condition": []})
    for key, scores in table.items():
        if len(scores) != len(ordered):
            continue  # a model failed on this image; compare only on images every model scored
        data[key[0]]["X"].append([scores[m] for m in ordered])
        data[key[0]]["y"].append(labels[key])
        data[key[0]]["condition"].append(key[1])
    return ordered, {d: {k: np.array(v) for k, v in parts.items()} for d, parts in data.items()}


def fit_logistic(X: np.ndarray, y: np.ndarray, l2: float = 1e-2, iterations: int = 100) -> np.ndarray:
    """IRLS / Newton with a small L2 penalty; returns weights with the bias last."""
    Xb = np.hstack([X, np.ones((len(X), 1))])
    w = np.zeros(Xb.shape[1])
    penalty = l2 * np.eye(Xb.shape[1])
    penalty[-1, -1] = 0.0
    for _ in range(iterations):
        p = 0.5 * (1 + np.tanh(0.5 * (Xb @ w)))
        gradient = Xb.T @ (p - y) / len(y) + penalty @ w
        hessian = (Xb * (p * (1 - p))[:, None]).T @ Xb / len(y) + penalty + 1e-9 * np.eye(len(w))
        step = np.linalg.solve(hessian, gradient)
        w -= step
        if np.abs(step).max() < 1e-9:
            break
    return w


def tpr_at_fpr(y: np.ndarray, score: np.ndarray, max_fpr: float = 0.05) -> float:
    real = np.sort(score[y == 0])
    if len(real) == 0 or (y == 1).sum() == 0:
        return float("nan")
    # Threshold = the score at or above which at most max_fpr of real images fall.
    index = int(np.ceil((1 - max_fpr) * len(real))) - 1
    threshold = real[min(max(index, 0), len(real) - 1)]
    return float((score[y == 1] > threshold).mean())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scores", type=Path, nargs="+")
    parser.add_argument("--feasibility", type=Path, default=None)
    parser.add_argument("--max-mb", type=float, default=150.0, help="Budget for total model size on device")
    parser.add_argument("--max-ms", type=float, default=1500.0, help="Budget for summed CI-runner latency per check")
    parser.add_argument("--min-gain", type=float, default=0.03, help="Required gain over best single model")
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    models, data = load(args.scores)
    datasets = sorted(data)
    if len(models) < 2 or not datasets:
        print("Need scores from at least two models on shared images.")
        return

    cost: dict[str, dict] = {}
    if args.feasibility and args.feasibility.exists():
        for row in json.loads(args.feasibility.read_text()):
            if not row.get("exported"):
                cost[row["model"]] = {"ok": False}
                continue
            use_int8 = row.get("int8_mb") is not None and (row.get("int8_max_diff") or 0) < 0.25
            per_check = row.get("inferences_per_check", 1)
            cost[row["model"]] = {
                "ok": True,
                "mb": row["int8_mb"] if use_int8 else row["fp32_mb"],
                "ms": (row["int8_ms"] if use_int8 else row["fp32_ms"]) * per_check,
                "int8": use_int8,
            }

    # Standardize each model's logit gap on all data pooled (label-free).
    pooled = np.vstack([data[d]["X"] for d in datasets])
    mean, std = pooled.mean(axis=0), pooled.std(axis=0) + 1e-9

    results = []
    for size in range(1, len(models) + 1):
        for combo in itertools.combinations(range(len(models)), size):
            names = [models[i] for i in combo]
            entry = {"models": names, "per_dataset": {}}
            for d in datasets:
                X = (data[d]["X"][:, combo] - mean[list(combo)]) / std[list(combo)]
                y = data[d]["y"]
                mean_score = X.mean(axis=1)
                stats = {"mean_auc": auc(y, mean_score), "mean_tpr5": tpr_at_fpr(y, mean_score)}
                if size > 1 and len(datasets) > 1:
                    others = [o for o in datasets if o != d]
                    Xo = np.vstack([(data[o]["X"][:, combo] - mean[list(combo)]) / std[list(combo)] for o in others])
                    yo = np.concatenate([data[o]["y"] for o in others])
                    w = fit_logistic(Xo, yo)
                    stack_score = X @ w[:-1] + w[-1]
                    stats.update({"stack_auc": auc(y, stack_score), "stack_tpr5": tpr_at_fpr(y, stack_score)})
                entry["per_dataset"][d] = stats
            for method in ("mean", "stack"):
                values = [entry["per_dataset"][d].get(f"{method}_tpr5") for d in datasets]
                if all(v is not None for v in values):
                    entry[f"{method}_worst_tpr5"] = min(values)
                    entry[f"{method}_worst_auc"] = min(entry["per_dataset"][d][f"{method}_auc"] for d in datasets)
            if cost:
                members = [cost.get(n, {"ok": False}) for n in names]
                entry["deployable"] = all(m["ok"] for m in members)
                if entry["deployable"]:
                    entry["mb"] = sum(m["mb"] for m in members)
                    entry["ms"] = sum(m["ms"] for m in members)
                    entry["within_budget"] = entry["mb"] <= args.max_mb and entry["ms"] <= args.max_ms
            if size > 1:
                w = fit_logistic((pooled[:, combo] - mean[list(combo)]) / std[list(combo)],
                                 np.concatenate([data[d]["y"] for d in datasets]))
                entry["shipping_weights"] = {"weights": dict(zip(names, map(float, w[:-1]))), "bias": float(w[-1]),
                                             "standardize_mean": dict(zip(names, map(float, mean[list(combo)]))),
                                             "standardize_std": dict(zip(names, map(float, std[list(combo)])))}
            entry["best_method"] = max(("mean", "stack") if size > 1 else ("mean",),
                                       key=lambda m: entry.get(f"{m}_worst_tpr5", -1))
            entry["best_worst_tpr5"] = entry.get(f"{entry['best_method']}_worst_tpr5", float("nan"))
            results.append(entry)

    results.sort(key=lambda e: -e["best_worst_tpr5"])

    def label(e):
        return " + ".join(e["models"])

    out = ["## Ensemble analysis\n",
           "**AI caught @5% false alarms** = share of AI images that can be shown HIGH while at most 5% of real "
           "images are, on the *worst* dataset. The stack is fit on the other dataset (cross-dataset), so "
           "it can't just memorize. Single models use the mean column (their own standardized score). "
           "Images: " + ", ".join(f"{d} n={len(data[d]['y'])}" for d in datasets) + ".\n",
           "| Models | Method | AI caught @5% FA (worst) | " + " | ".join(f"AUC {d}" for d in datasets)
           + " | Size MB | Latency ms (CI) | Fits budget |",
           "|---|---|---|" + "---|" * len(datasets) + "---|---|---|"]
    for e in results:
        m = e["best_method"]
        aucs = " | ".join(f"{e['per_dataset'][d][f'{m}_auc']:.3f}" for d in datasets)
        budget = ("yes" if e.get("within_budget") else "no") if e.get("deployable") else ("n/a" if not cost else "no export")
        out.append(f"| {label(e)} | {m} | {e['best_worst_tpr5']:.1%} | {aucs} | {e.get('mb', float('nan')):.0f} "
                   f"| {e.get('ms', float('nan')):.0f} | {budget} |")

    affordable = [e for e in results if not cost or e.get("within_budget")]
    singles = [e for e in affordable if len(e["models"]) == 1]
    ensembles = [e for e in affordable if len(e["models"]) > 1]
    recommendation = None
    if singles:
        best_single = singles[0]
        best_ensemble = ensembles[0] if ensembles else None
        if best_ensemble and best_ensemble["best_worst_tpr5"] >= best_single["best_worst_tpr5"] + args.min_gain:
            recommendation = best_ensemble
            verdict = (f"**Ensemble recommended:** {label(best_ensemble)} ({best_ensemble['best_method']}) catches "
                       f"{best_ensemble['best_worst_tpr5']:.1%} of AI images at 5% false alarms (worst dataset), vs "
                       f"{best_single['best_worst_tpr5']:.1%} for the best single model ({label(best_single)}): "
                       f"+{(best_ensemble['best_worst_tpr5'] - best_single['best_worst_tpr5']) * 100:.1f} points.")
        else:
            recommendation = best_single
            gain = (best_ensemble["best_worst_tpr5"] - best_single["best_worst_tpr5"]) * 100 if best_ensemble else 0
            verdict = (f"**Single model recommended:** {label(best_single)} "
                       f"({best_single['best_worst_tpr5']:.1%} AI caught @5% FA, worst dataset). The best affordable "
                       f"ensemble adds {gain:+.1f} points, below the {args.min_gain * 100:.0f}-point bar that would "
                       f"justify its extra size and latency.")
    else:
        verdict = "**No combination fits the on-device budget.**"
    out.append("\n" + verdict + "\n")
    print("\n".join(out))
    if args.json:
        args.json.write_text(json.dumps({"results": results, "recommendation": recommendation, "verdict": verdict},
                                        indent=2, default=float))


if __name__ == "__main__":
    main()
