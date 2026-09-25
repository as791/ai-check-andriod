#!/usr/bin/env python3
"""Can each candidate detector ship on-device as ONNX? Export, verify, size and time it.

For every candidate in tools/eval_candidates.py (plus the bundled model for
reference) this:
  1. exports the PyTorch model to ONNX (opset 17), which is the only format the app runs;
  2. checks parity: ONNX Runtime vs PyTorch logit gap on real dataset images, using
     the candidate's native preprocessing (max abs difference);
  3. dynamically quantizes the weights to int8 and repeats the parity check;
  4. reports file size and ONNX Runtime CPU latency (4 threads as a rough stand-in
     for a phone's big cores; a phone is typically 2-4x slower than a CI runner,
     so read these as relative costs, not absolute phone latency).

The app budget this is judged against: total model files well under ~150 MB, and a
full check (all ensemble members, both views where used) in a few seconds on a
mid-range phone. The exported .onnx files stay on the runner; only numbers are
uploaded.

Usage:
    python tools/onnx_feasibility.py --dataset eval-data/defactify \\
        --candidates commfor-384,commfor-224,ateeqq-siglip,dima806-vit \\
        --out-dir onnx-export --json eval-results/onnx_feasibility.json > eval-results/onnx_feasibility.md
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_candidates import CANDIDATES  # noqa: E402
from evaluate import (  # noqa: E402
    INPUT_NAME,
    app_normalize,
    collect_images,
    logit_difference,
    to_model_input,
    to_tensor,
)


def ort_session(path: Path, threads: int = 4):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def latency_ms(session, feed: dict, runs: int = 10) -> float:
    session.run(None, feed)  # warm-up
    times = []
    for _ in range(runs):
        start = time.perf_counter()
        session.run(None, feed)
        times.append((time.perf_counter() - start) * 1000)
    return statistics.median(times)


def quantize(src: Path, dst: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True, help="A fetched dataset folder (for parity images)")
    parser.add_argument("--candidates", default=",".join(CANDIDATES))
    parser.add_argument("--bundled", type=Path, default=Path("app/src/main/assets/models/ai-image-detector.onnx"))
    parser.add_argument("--out-dir", type=Path, default=Path("onnx-export"))
    parser.add_argument("--parity-images", type=int, default=8)
    parser.add_argument("--json", type=Path, default=None)
    args = parser.parse_args()

    import torch

    args.out_dir.mkdir(parents=True, exist_ok=True)
    paths = collect_images(args.dataset / "ai")[: args.parity_images // 2] + \
        collect_images(args.dataset / "real")[: args.parity_images // 2]
    images = [app_normalize(Image.open(p).convert("RGB")) for p in paths]
    rows = []

    # Reference: the bundled model as shipped (two views per check).
    if args.bundled.exists():
        session = ort_session(args.bundled)
        feed = {INPUT_NAME: to_tensor(to_model_input(images[0], "squash"))}
        int8_path = args.out_dir / "bundled.int8.onnx"
        int8 = {}
        try:
            quantize(args.bundled, int8_path)
            q_session = ort_session(int8_path)
            diffs = [abs(logit_difference(session.run(None, {INPUT_NAME: to_tensor(to_model_input(im, "squash"))})[0])
                         - logit_difference(q_session.run(None, {INPUT_NAME: to_tensor(to_model_input(im, "squash"))})[0]))
                     for im in images]
            int8 = {"int8_mb": int8_path.stat().st_size / 1e6, "int8_ms": latency_ms(q_session, feed),
                    "int8_max_diff": max(diffs)}
        except Exception as e:  # noqa: BLE001
            print(f"::warning::bundled int8 quantization failed: {e}", file=sys.stderr)
        rows.append({"model": "dafilab (bundled)", "exported": True, "parity_max_diff": 0.0,
                     "fp32_mb": args.bundled.stat().st_size / 1e6, "fp32_ms": latency_ms(session, feed),
                     "inferences_per_check": 2, **int8})

    for name in [n for n in args.candidates.split(",") if n]:
        candidate = CANDIDATES[name]
        row = {"model": name, "hub_id": candidate.hub_id, "license": candidate.license, "exported": False,
               "inferences_per_check": 1}
        try:
            candidate.load()
            module, example = candidate.export_module()
            module.eval()
            path = args.out_dir / f"{name}.onnx"
            torch.onnx.export(module, (example,), str(path), input_names=["pixel_values"], output_names=["logits"],
                              opset_version=17, dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}})
            row["exported"] = True
            session = ort_session(path)
            input_name = session.get_inputs()[0].name
            arrays = [candidate.preprocess_array(im) for im in images]
            torch_diffs = [candidate.logit_diff(im) for im in images]
            onnx_diffs = [candidate.logit_diff_from_output(session.run(None, {input_name: a})[0]) for a in arrays]
            row["parity_max_diff"] = max(abs(a - b) for a, b in zip(torch_diffs, onnx_diffs))
            row["fp32_mb"] = sum(f.stat().st_size for f in args.out_dir.glob(f"{name}.onnx*")) / 1e6
            row["fp32_ms"] = latency_ms(session, {input_name: arrays[0]})
            try:
                int8_path = args.out_dir / f"{name}.int8.onnx"
                quantize(path, int8_path)
                q_session = ort_session(int8_path)
                q_diffs = [candidate.logit_diff_from_output(q_session.run(None, {input_name: a})[0]) for a in arrays]
                row["int8_mb"] = int8_path.stat().st_size / 1e6
                row["int8_ms"] = latency_ms(q_session, {input_name: arrays[0]})
                row["int8_max_diff"] = max(abs(a - b) for a, b in zip(onnx_diffs, q_diffs))
            except Exception as e:  # noqa: BLE001
                row["int8_error"] = str(e)[:200]
        except (Exception, SystemExit) as e:  # noqa: BLE001 - one failed export must not hide the others
            row["error"] = repr(e)[:300]
            print(f"::warning::{name} ONNX export/verify failed: {e!r}", file=sys.stderr)
        rows.append(row)
        if args.json:  # write as we go so a later crash can't lose finished rows
            args.json.write_text(json.dumps(rows, indent=2))

    def fmt(value, spec=".1f"):
        return "–" if value is None else format(value, spec)

    print("## ONNX feasibility (on-device format)\n")
    print("Latency is ONNX Runtime CPU, 4 threads, on the CI runner: compare models against each other; "
          "expect a phone to be 2–4× slower. Parity = max |logit gap| difference vs PyTorch (fp32) or vs "
          "fp32 ONNX (int8); under ~0.1 is harmless after calibration.\n")
    print("| Model | License | Exports | Parity | fp32 MB | fp32 ms | int8 MB | int8 ms | int8 parity | Inferences/check |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['model']} | {r.get('license', 'Apache-2.0')} | {'yes' if r['exported'] else 'NO: ' + r.get('error', '')[:60]} "
              f"| {fmt(r.get('parity_max_diff'), '.4f')} | {fmt(r.get('fp32_mb'))} | {fmt(r.get('fp32_ms'))} "
              f"| {fmt(r.get('int8_mb'))} | {fmt(r.get('int8_ms'))} | {fmt(r.get('int8_max_diff'), '.3f')} "
              f"| {r['inferences_per_check']} |")
    print()
    if args.json:
        args.json.write_text(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
