#!/usr/bin/env python3
"""Fit a probability calibration and HIGH/LOW thresholds from tools/evaluate.py output.

The bundled classifier's raw scores are far too extreme (see internal-docs/MODEL.md
"Measured accuracy"): it says >99% or <1% for most images while being wrong much
more often than that implies. This script fits Platt scaling

    P(ai) = sigmoid(slope * (ai_logit - human_logit) + intercept)

- the transform ModelConfig.interpretOutput applies on-device - and picks the app's
classification thresholds from the calibrated scores.

Input: the CSV written by `evaluate.py --scores-csv` (one row per image x condition x
preprocess mode). Output: a markdown report on stdout, plus `--json` with the
recommended values.

What it reports, per preprocess mode:
- AUC per dataset (calibration is monotonic, so it can't change AUC - this is the
  number used to decide between preprocess modes);
- cross-dataset check: fit on each dataset alone, ECE on the other, to show the
  calibration generalizes rather than memorizing one dataset;
- the shipped fit: all datasets and conditions pooled;
- a threshold table and a recommendation:
    HIGH = lowest threshold where at most --max-real-high of real images score HIGH
           in EVERY dataset x condition (worst case);
    LOW  = highest threshold where at most --max-ai-low of AI images score LOW in
           every dataset x condition, kept at least --min-gap below HIGH.
  If no threshold meets a target, the least-bad one is recommended and flagged.

Usage:
    python tools/calibrate.py scores.csv [--preprocess squash] [--json out.json]
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HIGH_GRID = [round(0.50 + 0.05 * i, 2) for i in range(10)]  # 0.50 .. 0.95
LOW_GRID = [round(0.05 + 0.05 * i, 2) for i in range(9)]  # 0.05 .. 0.45


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 0.5 * (1.0 + np.tanh(0.5 * x))  # numerically stable for any x


def fit_platt(d: np.ndarray, y: np.ndarray, iterations: int = 100) -> tuple[float, float]:
    """Newton's method on log-loss, with Platt's smoothed targets to avoid over-confidence."""
    positives = float(y.sum())
    negatives = float(len(y) - positives)
    targets = np.where(y == 1, (positives + 1) / (positives + 2), 1 / (negatives + 2))
    x = np.stack([d, np.ones_like(d)], axis=1)

    def loss(w: np.ndarray) -> float:
        z = x @ w
        # log(1 + e^z) - t*z, written to stay finite for large |z|
        return float(np.mean(np.logaddexp(0.0, z) - targets * z))

    # Start from a slope that keeps scores unsaturated; raw logit gaps here are often +-10.
    w = np.array([1.0 / max(1.0, float(np.std(d))), 0.0])
    for _ in range(iterations):
        p = sigmoid(x @ w)
        gradient = x.T @ (p - targets) / len(d)
        hessian = (x * (p * (1 - p))[:, None]).T @ x / len(d) + 1e-9 * np.eye(2)
        step = np.linalg.solve(hessian, gradient)
        # Backtracking line search: a full Newton step can overshoot when scores saturate.
        current = loss(w)
        scale = 1.0
        while scale > 1e-8 and loss(w - scale * step) > current - 1e-4 * scale * float(gradient @ step):
            scale *= 0.5
        w = w - scale * step
        if np.abs(scale * step).max() < 1e-10:
            break
    return float(w[0]), float(w[1])


def auc(y: np.ndarray, s: np.ndarray) -> float:
    order = np.argsort(s, kind="mergesort")
    ranks = np.empty(len(s))
    sorted_s = s[order]
    i = 0
    while i < len(s):
        j = i
        while j + 1 < len(s) and sorted_s[j + 1] == sorted_s[i]:
            j += 1
        ranks[order[i : j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    positives = y.sum()
    negatives = len(y) - positives
    return float((ranks[y == 1].sum() - positives * (positives + 1) / 2) / (positives * negatives))


def ece(y: np.ndarray, p: np.ndarray, bins: int = 10) -> float:
    edges = np.linspace(0, 1, bins + 1)
    total = 0.0
    for b in range(bins):
        mask = (p >= edges[b]) & ((p < edges[b + 1]) if b < bins - 1 else (p <= 1.0))
        if mask.any():
            total += mask.sum() / len(p) * abs(p[mask].mean() - y[mask].mean())
    return float(total)


def extreme_share(p: np.ndarray) -> float:
    return float(((p > 0.99) | (p < 0.01)).mean())


def load(path: Path, model: str | None = None) -> dict[str, list[dict]]:
    by_mode: dict[str, list[dict]] = defaultdict(list)
    with path.open() as f:
        for row in csv.DictReader(f):
            if model is not None and row.get("model") != model:
                continue
            row["is_ai"] = int(row["is_ai"])
            row["logit_diff"] = float(row["logit_diff"])
            by_mode[row["preprocess"]].append(row)
    return by_mode


def arrays(rows: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    return (
        np.array([r["logit_diff"] for r in rows], dtype=np.float64),
        np.array([r["is_ai"] for r in rows], dtype=np.int64),
    )


def group(rows: list[dict], *keys: str) -> dict[tuple, list[dict]]:
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        groups[tuple(r[k] for k in keys)].append(r)
    return dict(sorted(groups.items()))


def worst_case(rows: list[dict], slope: float, intercept: float, high: float, low: float) -> tuple[float, float]:
    """Max share of real images >= high and of AI images < low over every dataset x condition."""
    worst_real_high = worst_ai_low = 0.0
    for _, subset in group(rows, "dataset", "condition").items():
        d, y = arrays(subset)
        p = sigmoid(slope * d + intercept)
        if (y == 0).any():
            worst_real_high = max(worst_real_high, float((p[y == 0] >= high).mean()))
        if (y == 1).any():
            worst_ai_low = max(worst_ai_low, float((p[y == 1] < low).mean()))
    return worst_real_high, worst_ai_low


def analyze_mode(mode: str, rows: list[dict], args: argparse.Namespace, out: list[str]) -> dict:
    out.append(f"## Preprocess: `{mode}`\n")
    datasets = group(rows, "dataset")

    out.append("| Dataset | n | AUC | ECE raw | ECE fit-on-other-dataset | ECE pooled fit | >99%/<1% raw | >99%/<1% calibrated |")
    out.append("|---|---|---|---|---|---|---|---|")
    d_all, y_all = arrays(rows)
    slope, intercept = fit_platt(d_all, y_all)
    aucs = {}
    for (name,), subset in datasets.items():
        d, y = arrays(subset)
        others = [r for r in rows if r["dataset"] != name]
        if others:
            od, oy = arrays(others)
            cross = ece(y, sigmoid(np.polyval(fit_platt(od, oy), d)))
            cross_text = f"{cross:.3f}"
        else:
            cross_text = "n/a"
        calibrated = sigmoid(slope * d + intercept)
        aucs[name] = auc(y, d)
        out.append(
            f"| {name} | {len(subset)} | {aucs[name]:.3f} | {ece(y, sigmoid(d)):.3f} | {cross_text} "
            f"| {ece(y, calibrated):.3f} | {extreme_share(sigmoid(d)):.1%} | {extreme_share(calibrated):.1%} |"
        )
    out.append(f"\nPooled fit: **slope = {slope:.4f}, intercept = {intercept:.4f}**  "
               f"(P(ai) = sigmoid(slope · (ai − human) + intercept))\n")

    out.append("Worst case over every dataset × condition, calibrated scores:\n")
    out.append("| Threshold | Real images ≥ threshold (would show HIGH) | AI images < threshold (would show LOW) |")
    out.append("|---|---|---|")
    for t in sorted(set(HIGH_GRID + LOW_GRID)):
        real_high, ai_low = worst_case(rows, slope, intercept, t, t)
        out.append(f"| {t:.2f} | {real_high:.1%} | {ai_low:.1%} |")

    high = next((t for t in HIGH_GRID if worst_case(rows, slope, intercept, t, 0)[0] <= args.max_real_high), None)
    high_met = high is not None
    if high is None:
        high = HIGH_GRID[-1]
    low_candidates = [t for t in LOW_GRID if t <= high - args.min_gap]
    low = next((t for t in reversed(low_candidates)
                if worst_case(rows, slope, intercept, 1.1, t)[1] <= args.max_ai_low), None)
    low_met = low is not None
    if low is None:
        low = low_candidates[0] if low_candidates else LOW_GRID[0]
    real_high, _ = worst_case(rows, slope, intercept, high, 0)
    _, ai_low = worst_case(rows, slope, intercept, 1.1, low)

    out.append(
        f"\nRecommendation: **HIGH ≥ {high:.2f}**, **LOW < {low:.2f}** → worst case "
        f"{real_high:.1%} of real images shown HIGH (target ≤{args.max_real_high:.0%}"
        f"{'' if high_met else ' - NOT MET, least-bad threshold'}), "
        f"{ai_low:.1%} of AI images shown LOW (target ≤{args.max_ai_low:.0%}"
        f"{'' if low_met else ' - NOT MET, least-bad threshold'}).\n"
    )
    return {
        "preprocess": mode,
        "slope": slope,
        "intercept": intercept,
        "auc": aucs,
        "high_threshold": high,
        "low_threshold": low,
        "high_target_met": high_met,
        "low_target_met": low_met,
        "worst_real_high": real_high,
        "worst_ai_low": ai_low,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scores_csv", type=Path)
    parser.add_argument("--preprocess", default=None, help="Only analyze this mode (default: every mode present)")
    parser.add_argument("--model", default=None, help="Only rows for this model (CSV 'model' column)")
    parser.add_argument("--max-real-high", type=float, default=0.05)
    parser.add_argument("--max-ai-low", type=float, default=0.10)
    parser.add_argument("--min-gap", type=float, default=0.20, help="Minimum width of the UNCERTAIN band")
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    by_mode = load(args.scores_csv, args.model)
    if args.preprocess:
        by_mode = {args.preprocess: by_mode.get(args.preprocess, [])}
    if not any(by_mode.values()):
        print("No rows to calibrate.", file=sys.stderr)
        sys.exit(1)

    out = ["# Calibration\n",
           "Platt scaling fit on per-image logit differences (all datasets and conditions pooled). "
           "The cross-dataset column fits on the *other* dataset only - if it's close to the pooled "
           "one, the calibration generalizes.\n"]
    results = [analyze_mode(mode, rows, args, out) for mode, rows in sorted(by_mode.items()) if rows]

    if len(results) > 1:
        out.append("## Preprocess comparison (AUC)\n")
        names = sorted({n for r in results for n in r["auc"]})
        out.append("| Preprocess | " + " | ".join(names) + " |")
        out.append("|---|" + "---|" * len(names))
        for r in results:
            out.append(f"| {r['preprocess']} | " + " | ".join(f"{r['auc'].get(n, float('nan')):.3f}" for n in names) + " |")
        out.append("")

    print("\n".join(out))
    if args.json:
        args.json.write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
