#!/usr/bin/env python3
"""Fit the consistency check's abstain threshold on the app's real pipeline (issue #15).

The consistency check (feature squeezing, Xu et al. 2018) scores each model view twice:
as is, and "squeezed" (JPEG q75 re-encode, then a 3x3 per-channel median with edge
pixels replicated). Adversarial noise tends to be fragile, so a large disagreement between
the two ensemble scores means the image was likely manipulated; the app then abstains
(UNCERTAIN) instead of showing a score.

tools/adv_eval.py measures how well that stops attacks on 380x380 crops. This script fits
the threshold the *app* uses, on the app's exact pipeline: app normalization, both
primary views, the Community Forensics view, fp16 ONNX files, and the shipped ensemble
(APP_ENSEMBLE). It also reports how often clean content would abstain per dataset x
condition, so re-compressed (Instagram-like) images don't abstain far more often than
originals.

Usage:
    python tools/consistency_calibrate.py --datasets eval-data/defactify eval-data/mj-dalle-sd-nbp \\
        --json consistency.json > consistency.md
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate import (  # noqa: E402
    APP_ENSEMBLE,
    CONDITIONS,
    INPUT_NAME,
    CommforOnnx,
    app_normalize,
    collect_images,
    commfor_view,
    degrade,
    ensemble_score,
    load_ensemble_params,
    logit_difference,
    roc_auc,
    to_model_input,
    to_tensor,
)

import onnxruntime as ort  # noqa: E402

QUANTILE = 0.95


def median3(image: Image.Image) -> Image.Image:
    """3x3 per-channel median, edge pixels replicated. Mirrors the app's ImageSqueezer."""
    a = np.asarray(image, dtype=np.uint8)
    padded = np.pad(a, ((1, 1), (1, 1), (0, 0)), mode="edge")
    h, w = a.shape[:2]
    stack = np.stack([padded[dy:dy + h, dx:dx + w] for dy in range(3) for dx in range(3)], axis=0)
    return Image.fromarray(np.median(stack, axis=0).astype(np.uint8))


def squeeze(view: Image.Image) -> Image.Image:
    """JPEG q75 re-encode, then 3x3 median. Mirrors the app's ImageSqueezer."""
    buf = io.BytesIO()
    view.save(buf, format="JPEG", quality=75)
    buf.seek(0)
    return median3(Image.open(buf).convert("RGB"))


class AppEnsemble:
    def __init__(self, bundled: Path, commfor: Path, params: dict):
        self.params = params
        self.primary = ort.InferenceSession(str(bundled), providers=["CPUExecutionProvider"])
        self.cf = CommforOnnx(commfor)

    def _primary_gap(self, view: Image.Image) -> float:
        return logit_difference(self.primary.run(None, {INPUT_NAME: to_tensor(view)})[0])

    def _cf_logit(self, view: Image.Image) -> float:
        return float(self.cf.session.run(None, {self.cf.input_name: to_tensor(view)})[0].reshape(-1)[0])

    def scores(self, image: Image.Image) -> tuple[float, float]:
        """(ensemble score, ensemble score on the squeezed views) for an app-normalized image."""
        primary_views = [to_model_input(image, "squash"), to_model_input(image, "center_crop")]
        cf = commfor_view(image)
        raw = ensemble_score(np.mean([self._primary_gap(v) for v in primary_views]), self._cf_logit(cf),
                             self.params)
        sq = ensemble_score(np.mean([self._primary_gap(squeeze(v)) for v in primary_views]),
                            self._cf_logit(squeeze(cf)), self.params)
        return raw, sq


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets", type=Path, nargs="+", required=True)
    parser.add_argument("--bundled", type=Path, default=Path("app/src/main/assets/models/ai-image-detector.onnx"))
    parser.add_argument("--commfor-onnx", type=Path, default=Path("app/src/main/assets/models/commfor-224.onnx"))
    parser.add_argument("--per-class", type=int, default=150, help="Images per class per dataset")
    parser.add_argument("--ensemble-params", type=Path, default=None,
                        help="ensemble-params.json for a candidate model set (default: the app's APP_ENSEMBLE)")
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    params = load_ensemble_params(args.ensemble_params) if args.ensemble_params else APP_ENSEMBLE
    model = AppEnsemble(args.bundled, args.commfor_onnx, params)
    rows = []  # (dataset, condition, is_ai, raw, squeezed)
    for d in args.datasets:
        for is_ai, sub in ((1, "ai"), (0, "real")):
            paths = collect_images(d / sub)
            rng = np.random.default_rng(11)
            rng.shuffle(paths)
            for path in paths[: args.per_class]:
                image = Image.open(path).convert("RGB")
                for condition in CONDITIONS:
                    raw, sq = model.scores(app_normalize(degrade(image, condition)))
                    rows.append((d.name, condition, is_ai, raw, sq))
        print(f"{d.name}: done", file=sys.stderr, flush=True)

    dis = np.array([abs(r[3] - r[4]) for r in rows])
    tau = float(np.quantile(dis, QUANTILE))
    slope, intercept = params["photo"]
    out = ["## Consistency check: abstain threshold on the app pipeline\n",
           f"{len(rows)} image × condition scores ({len(args.datasets)} datasets × {', '.join(CONDITIONS)}), shipped ensemble. "
           f"Disagreement = |ensemble score − ensemble score on squeezed views| (JPEG q75 + 3×3 median).\n",
           f"**τ = {tau:.4f}** ({QUANTILE:.0%} quantile of clean disagreement; "
           f"median {np.median(dis):.4f}, 99th pct {np.quantile(dis, 0.99):.4f}).\n",
           "| Dataset | Condition | Real abstained | AI abstained | AUC (raw) | AUC (squeezed) |",
           "|---|---|---|---|---|---|"]
    per_cell = {}
    for ds in sorted({r[0] for r in rows}):
        for cond in CONDITIONS:
            cell = [r for r in rows if r[0] == ds and r[1] == cond]
            y = np.array([r[2] for r in cell])
            d = np.array([abs(r[3] - r[4]) for r in cell])
            raw_p = 1 / (1 + np.exp(-(slope * np.array([r[3] for r in cell]) + intercept)))
            sq_p = 1 / (1 + np.exp(-(slope * np.array([r[4] for r in cell]) + intercept)))
            real_ab, ai_ab = float((d[y == 0] > tau).mean()), float((d[y == 1] > tau).mean())
            per_cell[f"{ds}/{cond}"] = {"real_abstained": real_ab, "ai_abstained": ai_ab}
            out.append(f"| {ds} | {cond} | {real_ab:.1%} | {ai_ab:.1%} | {roc_auc(y, raw_p):.3f} | "
                       f"{roc_auc(y, sq_p):.3f} |")
    worst_real = max(v["real_abstained"] for v in per_cell.values())
    out.append(f"\nWorst clean real abstain rate: **{worst_real:.1%}** (target ≤ 10%).")
    print("\n".join(out))
    if args.json:
        args.json.write_text(json.dumps({"tau": tau, "quantile": QUANTILE, "n": len(rows),
                                         "worst_real_abstained": worst_real, "cells": per_cell}, indent=2))


if __name__ == "__main__":
    main()
