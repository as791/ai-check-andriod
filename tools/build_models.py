#!/usr/bin/env python3
"""Build the ONNX model files the app bundles, and verify every step numerically.

1. Export Community Forensics ViT-S 224 (OwensLab/commfor-model-224, MIT) to ONNX.
2. Parity: PyTorch model on the authors' own preprocessing vs the ONNX file on the
   app's mirrored preprocessing (evaluate.commfor_input). This checks the export and
   the app's preprocessing copy together.
3. Store both models' weights as fp16 (tools/fp16_weights.py). This halves the files;
   the math stays fp32.
4. Parity: fp32 vs fp16-weight logit gaps on real dataset images, for both models.

The output directory holds exactly the files to drop into app/src/main/assets/models/.
Fails (exit 1) if any parity check exceeds --max-gap-diff.

Adversarially fine-tuned weights (tools/adv_finetune.py, #15) replace either model with
--commfor-checkpoint / --bundled-checkpoint: a local .pt file or "hf-repo:filename"
(private repos use HF_TOKEN). The architecture, export and every check stay the same.

Usage:
    python tools/build_models.py --dataset eval-data/defactify --out model-assets \\
        --bundled app/src/main/assets/models/ai-image-detector.onnx --report parity.md
    python tools/build_models.py --dataset eval-data/defactify --out model-assets \\
        --bundled-checkpoint you/genned-robust:bundled-robust.pt \\
        --commfor-checkpoint you/genned-robust:commfor-robust.pt
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate import (  # noqa: E402
    INPUT_NAME,
    CommforOnnx,
    commfor_input,
    app_normalize,
    collect_images,
    logit_difference,
    to_model_input,
    to_tensor,
)
from fp16_weights import convert  # noqa: E402

BUNDLED_NAME = "ai-image-detector.onnx"
COMMFOR_NAME = "commfor-224.onnx"
# The app bundles onnxruntime-android 1.19.x (gradle/libs.versions.toml): IR <= 10, opset <= 21.
MAX_IR_VERSION = 10
MAX_OPSET = 21


def resolve_checkpoint(spec: str) -> Path:
    """A local file, or "repo_id:filename" on the Hugging Face Hub (HF_TOKEN for private repos)."""
    if Path(spec).is_file():
        return Path(spec)
    repo_id, _, filename = spec.rpartition(":")
    if not repo_id or not filename:
        sys.exit(f"Checkpoint {spec!r} is neither a file nor 'repo_id:filename'")
    from huggingface_hub import hf_hub_download

    return Path(hf_hub_download(repo_id=repo_id, filename=filename))


def load_checkpoint(module, spec: str) -> None:
    import torch

    state = torch.load(resolve_checkpoint(spec), map_location="cpu", weights_only=True)
    module.load_state_dict(state, strict=True)
    print(f"Loaded fine-tuned weights from {spec}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True, help="Fetched dataset folder for parity images")
    parser.add_argument("--bundled", type=Path, default=None, help="Current bundled model (.onnx)")
    parser.add_argument("--bundled-checkpoint", default=None,
                        help="Fine-tuned efficientnet_b4 state_dict (file or repo:filename); replaces --bundled")
    parser.add_argument("--commfor-checkpoint", default=None,
                        help="Fine-tuned Community Forensics state_dict (file or repo:filename)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--images", type=int, default=40)
    parser.add_argument("--max-gap-diff", type=float, default=0.05)
    parser.add_argument("--report", type=Path, default=None)
    args = parser.parse_args()

    import onnxruntime as ort
    import torch

    from eval_candidates import CommunityForensics

    if (args.bundled is None) == (args.bundled_checkpoint is None):
        parser.error("pass exactly one of --bundled or --bundled-checkpoint")
    args.out.mkdir(parents=True, exist_ok=True)
    work = args.out / "_work"
    work.mkdir(exist_ok=True)
    if args.bundled_checkpoint:
        import timm

        from convert_model import export_bundled

        net = timm.create_model("efficientnet_b4", pretrained=False, num_classes=2)
        load_checkpoint(net, args.bundled_checkpoint)
        args.bundled = work / f"fp32-{BUNDLED_NAME}"
        export_bundled(net, args.bundled)
    paths = (collect_images(args.dataset / "ai")[: args.images // 2]
             + collect_images(args.dataset / "real")[: args.images // 2])
    images = [app_normalize(Image.open(p).convert("RGB")) for p in paths]
    rows = []
    failed = False

    # 1-2. Export Community Forensics 224 and check it against PyTorch.
    candidate = CommunityForensics(224)
    candidate.load()
    if args.commfor_checkpoint:
        load_checkpoint(candidate.model, args.commfor_checkpoint)
    module, example = candidate.export_module()
    fp32_commfor = work / COMMFOR_NAME
    export_kwargs = dict(input_names=["pixel_values"], output_names=["logits"], opset_version=17,
                         dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}})
    try:  # the classic exporter gives a plain opset-17 graph the app's ORT 1.19 can load
        torch.onnx.export(module.eval(), (example,), str(fp32_commfor), dynamo=False, **export_kwargs)
    except TypeError:  # older torch without the `dynamo` switch
        torch.onnx.export(module.eval(), (example,), str(fp32_commfor), **export_kwargs)
    onnx_commfor = CommforOnnx(fp32_commfor)
    # (a) The export alone: identical input tensor through PyTorch and ONNX Runtime.
    with torch.no_grad():
        diffs = [abs(float(candidate.model(torch.from_numpy(commfor_input(im))).reshape(-1)[0]) - onnx_commfor.gap(im))
                 for im in images]
    rows.append(("commfor-224: PyTorch vs ONNX export, same input", max(diffs), np.mean(diffs)))
    # (b) End to end: authors' preprocessing + PyTorch vs the app's preprocessing + ONNX.
    diffs = [abs(candidate.logit_diff(im) - onnx_commfor.gap(im)) for im in images]
    rows.append(("commfor-224: authors' preprocessing + PyTorch vs app preprocessing + ONNX", max(diffs), np.mean(diffs)))

    # 3. fp16 weights for both models.
    fp16_bundled = args.out / BUNDLED_NAME
    fp16_commfor = args.out / COMMFOR_NAME
    convert(args.bundled, fp16_bundled)
    convert(fp32_commfor, fp16_commfor)

    # 4. fp32 vs fp16-weight parity on the app's exact inputs.
    a = ort.InferenceSession(str(args.bundled), providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(str(fp16_bundled), providers=["CPUExecutionProvider"])
    diffs = []
    for im in images:
        for mode in ("squash", "center_crop"):
            t = to_tensor(to_model_input(im, mode))
            diffs.append(abs(logit_difference(a.run(None, {INPUT_NAME: t})[0])
                             - logit_difference(b.run(None, {INPUT_NAME: t})[0])))
    rows.append(("bundled: fp32 vs fp16 weights", max(diffs), np.mean(diffs)))
    half_commfor = CommforOnnx(fp16_commfor)
    diffs = [abs(onnx_commfor.gap(im) - half_commfor.gap(im)) for im in images]
    rows.append(("commfor-224: fp32 vs fp16 weights", max(diffs), np.mean(diffs)))

    out = ["## Model build parity\n",
           f"{len(images)} dataset images, app normalization. A logit-gap difference below ~0.05 is negligible "
           "after calibration (slopes are ~0.2).\n",
           "| Check | Max \\|gap diff\\| | Mean | OK |", "|---|---|---|---|"]
    for name, worst, mean in rows:
        ok = worst <= args.max_gap_diff
        failed |= not ok
        out.append(f"| {name} | {worst:.4f} | {mean:.4f} | {'yes' if ok else '**NO**'} |")
    import onnx

    for path in (fp16_bundled, fp16_commfor):
        model = onnx.load(str(path), load_external_data=False)
        opset = max(o.version for o in model.opset_import if o.domain in ("", "ai.onnx"))
        ok = model.ir_version <= MAX_IR_VERSION and opset <= MAX_OPSET
        failed |= not ok
        out.append(f"| {path.name}: loadable by the app's ONNX Runtime 1.19 (IR {model.ir_version} ≤ {MAX_IR_VERSION}, "
                   f"opset {opset} ≤ {MAX_OPSET}) | – | – | {'yes' if ok else '**NO**'} |")
    out.append("")
    out.append("| File | fp32 MB | Shipped (fp16 weights) MB |")
    out.append("|---|---|---|")
    out.append(f"| {BUNDLED_NAME} | {args.bundled.stat().st_size / 1e6:.1f} | {fp16_bundled.stat().st_size / 1e6:.1f} |")
    out.append(f"| {COMMFOR_NAME} | {fp32_commfor.stat().st_size / 1e6:.1f} | {fp16_commfor.stat().st_size / 1e6:.1f} |")
    report = "\n".join(out)
    print(report)
    if args.report:
        args.report.write_text(report + "\n")

    for f in work.iterdir():
        f.unlink()
    work.rmdir()
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
