#!/usr/bin/env python3
"""Export the AI-image classifier to ONNX for bundling into the Android app.

This project targets `Dafilab/ai-image-detector` on Hugging Face (Apache-2.0,
EfficientNet-B4 via timm) as documented in docs/MODEL.md — see that file first for
why this model was chosen and its known limitations. This script was NOT run as
part of building this repository: the sandbox this project was built in has no
network access to huggingface.co and no torch/timm/onnx installed (see README
"Known limitations"). Treat it as a precise, tested-by-reading starting point, not
a script whose output has been verified — run it yourself, then verify the result
with `tools/evaluate.py` before trusting it in the app.

Steps this script performs:
  1. Download the model + weights from Hugging Face via `timm`/`transformers`.
  2. Export to ONNX with a fixed input size.
  3. Run a smoke inference on a synthetic input to sanity-check the export.

You still MUST, by hand, after running this:
  - Inspect the exported graph's real input/output tensor names (e.g. with
    https://netron.app) and update INPUT_NAME in
    app/src/main/kotlin/com/aicheck/app/data/detection/classifier/ModelConfig.kt
    if it differs from "pixel_values".
  - Confirm the output label order (`id2label` in the model's config.json) matches
    ModelConfig.interpretOutput's `[human, ai]` assumption. If it's reversed, either
    fix interpretOutput or flip the output before shipping — a silent mismatch
    inverts every result.
  - Run tools/evaluate.py against a labeled dataset and sanity-check the numbers
    before shipping.
  - Confirm you accept the model's license (Apache-2.0) and are comfortable with
    its documented training data / limitations in docs/MODEL.md.

Usage:
    pip install -r tools/requirements.txt
    python tools/convert_model.py --output app/src/main/assets/models/ai-image-detector.onnx
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

MODEL_ID = "Dafilab/ai-image-detector"
INPUT_SIZE = 380


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-id", default=MODEL_ID, help="Hugging Face model repo id")
    parser.add_argument("--output", type=Path, required=True, help="Output .onnx path")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset version")
    args = parser.parse_args()

    try:
        import torch
        from huggingface_hub import hf_hub_download
        import timm
    except ImportError:
        print(
            "Missing dependencies. Run: pip install -r tools/requirements.txt "
            "huggingface_hub",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Loading {args.model_id} via timm ...")
    # Dafilab/ai-image-detector publishes a timm-compatible EfficientNet-B4
    # checkpoint. If timm.create_model can't resolve it directly by hub id in your
    # timm version, download the checkpoint file with hf_hub_download and load it
    # with timm.create_model("efficientnet_b4", pretrained=False, num_classes=2)
    # followed by model.load_state_dict(...) — check the model card on Hugging Face
    # for the exact checkpoint filename and any wrapper code it documents.
    model = timm.create_model(f"hf_hub:{args.model_id}", pretrained=True)
    model.eval()

    args.output.parent.mkdir(parents=True, exist_ok=True)

    dummy_input = torch.randn(1, 3, INPUT_SIZE, INPUT_SIZE, dtype=torch.float32)

    print(f"Exporting to {args.output} (opset {args.opset}) ...")
    torch.onnx.export(
        model,
        dummy_input,
        str(args.output),
        input_names=["pixel_values"],
        output_names=["logits"],
        opset_version=args.opset,
        dynamic_axes=None,  # fixed batch size of 1, matching ModelConfig.INPUT_SHAPE
    )

    print("Running a smoke inference on the exported graph ...")
    import onnxruntime as ort
    import numpy as np

    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    outputs = session.run(None, {"pixel_values": dummy_input.numpy().astype(np.float32)})
    print(f"Output shape: {outputs[0].shape}, sample values: {outputs[0].reshape(-1)[:4]}")
    print()
    print("Export complete. Before bundling this file, read the checklist in this")
    print("script's module docstring (input/output names, label order, evaluate.py).")


if __name__ == "__main__":
    main()
