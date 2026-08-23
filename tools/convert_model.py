#!/usr/bin/env python3
"""Export the AI-image classifier to ONNX for bundling into the Android app.

This project targets `Dafilab/ai-image-detector` on Hugging Face (Apache-2.0,
EfficientNet-B4 via timm) as documented in docs/MODEL.md — see that file first for
why this model was chosen and its known limitations, including that the repo is
gated (you must request/accept access and `huggingface-cli login` before this
script's download will succeed — a plain 401 GatedRepoError otherwise).

Steps this script performs:
  1. Download `pytorch_model.pth` (a raw PyTorch training checkpoint, not a
     timm-hub-formatted repo — confirmed by actually running this against the
     real repo) and load its weights into a bare `efficientnet_b4` architecture.
  2. Export to ONNX with a fixed input size.
  3. Run a smoke inference on a synthetic input to sanity-check the export.

You still MUST, by hand, after running this:
  - Inspect the exported graph's real input/output tensor names (e.g. with
    https://netron.app) and update INPUT_NAME in
    app/src/main/kotlin/com/aicheck/app/data/detection/classifier/ModelConfig.kt
    if it differs from "pixel_values".
  - Confirm the output label order still matches ModelConfig.interpretOutput's
    `[ai, human]` assumption (confirmed from this repo's config.json
    `label_mapping`; re-check if you point this script at a different model).
  - Run tools/evaluate.py against a labeled dataset and sanity-check the numbers
    before shipping.
  - Confirm you accept the model's license (Apache-2.0) and are comfortable with
    its documented training data / limitations in docs/MODEL.md.

Usage:
    pip install -r tools/requirements.txt
    huggingface-cli login   # after requesting access on the model page — see docs/MODEL.md
    python tools/convert_model.py --output app/src/main/assets/models/ai-image-detector.onnx

    # If hf_hub_download fails locally (e.g. a NameResolutionError on
    # us.aws.cdn.hf.co — a known issue on some networks/DNS resolvers), download
    # pytorch_model.pth manually from the model's "Files and versions" page in a
    # browser instead, then point this script at it directly:
    python tools/convert_model.py --checkpoint-path ~/Downloads/pytorch_model.pth \\
        --output app/src/main/assets/models/ai-image-detector.onnx
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

MODEL_ID = "Dafilab/ai-image-detector"
CHECKPOINT_FILENAME = "pytorch_model.pth"
INPUT_SIZE = 380


def _load_state_dict(checkpoint_path: Path) -> dict:
    """Unwrap whatever was actually pickled into pytorch_model.pth.

    Training-loop checkpoints (this one was renamed from
    model_epoch_8_acc_0.9859.pth, a telltale training-script naming pattern) are
    saved in several common shapes: a bare state_dict, a dict wrapping it under a
    "state_dict"/"model_state_dict" key alongside optimizer/epoch metadata, or
    occasionally the full nn.Module itself. Handle all three rather than guessing
    one and failing opaquely on the others.
    """
    import torch

    # weights_only=False: this checkpoint is a full pickle (possibly containing
    # optimizer state or the module object itself, not just tensors), so PyTorch's
    # safer weights_only loader would reject it. Only pass a checkpoint here from a
    # repo whose contents you've deliberately chosen to trust, as documented in
    # docs/MODEL.md.
    raw = torch.load(checkpoint_path, map_location="cpu", weights_only=False)

    if isinstance(raw, dict):
        for key in ("state_dict", "model_state_dict"):
            if key in raw:
                raw = raw[key]
                break
    else:
        raw = raw.state_dict()

    # Strip a "module." prefix left over from DataParallel/DistributedDataParallel
    # training, if present — otherwise every key mismatches the bare model's.
    return {k.removeprefix("module."): v for k, v in raw.items()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-id", default=MODEL_ID, help="Hugging Face model repo id")
    parser.add_argument("--checkpoint-filename", default=CHECKPOINT_FILENAME, help="Checkpoint file within the repo")
    parser.add_argument(
        "--checkpoint-path",
        type=Path,
        default=None,
        help=(
            "Path to an already-downloaded checkpoint file, skipping hf_hub_download "
            "entirely. Useful if huggingface_hub's CDN download fails locally (e.g. "
            "a NameResolutionError on us.aws.cdn.hf.co) - download the file manually "
            "from the model's 'Files and versions' page in a browser instead, then "
            "point this at wherever you saved it."
        ),
    )
    parser.add_argument("--output", type=Path, required=True, help="Output .onnx path")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset version")
    args = parser.parse_args()

    try:
        import torch
        import timm
    except ImportError:
        print(
            "Missing dependencies. Run: pip install -r tools/requirements.txt",
            file=sys.stderr,
        )
        sys.exit(1)

    if args.checkpoint_path is not None:
        checkpoint_path = args.checkpoint_path
        if not checkpoint_path.is_file():
            print(f"--checkpoint-path {checkpoint_path} does not exist.", file=sys.stderr)
            sys.exit(1)
        print(f"Using local checkpoint at {checkpoint_path} ...")
    else:
        try:
            from huggingface_hub import hf_hub_download
        except ImportError:
            print(
                "Missing dependencies. Run: pip install -r tools/requirements.txt",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"Downloading {args.checkpoint_filename} from {args.model_id} ...")
        checkpoint_path = Path(hf_hub_download(repo_id=args.model_id, filename=args.checkpoint_filename))

    print("Building bare efficientnet_b4 (num_classes=2) and loading its weights ...")
    model = timm.create_model("efficientnet_b4", pretrained=False, num_classes=2)
    state_dict = _load_state_dict(checkpoint_path)
    missing, unexpected = model.load_state_dict(state_dict, strict=False)
    if missing or unexpected:
        print(f"WARNING: missing keys={missing}")
        print(f"WARNING: unexpected keys={unexpected}")
        print(
            "A non-empty list above means the checkpoint didn't fully match the "
            "efficientnet_b4 architecture - inspect these before trusting the "
            "export, since a partial load can silently produce garbage predictions."
        )
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
