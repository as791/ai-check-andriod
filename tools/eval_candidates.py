#!/usr/bin/env python3
"""Benchmark candidate replacement detectors on the same data as tools/evaluate.py.

Runs each candidate under the same degradation conditions (original / jpeg75 /
social), after the same app normalization (long edge <=2048, JPEG q92), and emits
the same JSON metrics and per-image scores CSV, so tools/eval_report.py and
tools/calibrate.py can compare them directly against the bundled model. Each
candidate uses its OWN native preprocessing (reported as preprocess "native") -
that's what it was trained with, and what an on-device port would replicate.

This is a maintainer/CI tool (it needs torch, timm and transformers); nothing here
ships in the app. A candidate is only worth porting if it clearly beats the bundled
model on every dataset - see internal-docs/MODEL.md "Measured accuracy".

Usage:
    python tools/eval_candidates.py --dataset eval-data/defactify --dataset-name defactify \\
        --candidates commfor-384,ateeqq-siglip --json-dir results/ --scores-csv scores.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate import (  # noqa: E402 - shared conditions/metrics keep results comparable
    CONDITIONS,
    Prediction,
    app_normalize,
    calibrated_probability,
    collect_images,
    compute_metrics,
    degrade,
    generator_of,
    print_report,
)

AI_WORDS = ("ai", "fake", "artificial", "generated", "synthetic")
REAL_WORDS = ("real", "human", "authentic", "natural")


class Candidate:
    """name -> how to load it, preprocess one PIL image, and turn output into ai-vs-real logit gap."""

    name: str
    hub_id: str
    license: str

    def load(self) -> None: ...

    def logit_diff(self, image: Image.Image) -> float: ...


class CommunityForensics(Candidate):
    """OwensLab Community Forensics ViT-S (CVPR 2025), trained on ~4.8k generators. MIT.

    Mirrors the authors' test transform (github.com/JeongsooP/Community-Forensics,
    dataloader.get_transform): resize short side to 440 (or 256), center-crop 384
    (or 224), ImageNet normalization; one output logit where sigmoid = P(fake).
    """

    license = "MIT"

    def __init__(self, input_size: int):
        self.input_size = input_size
        self.name = f"commfor-{input_size}"
        self.hub_id = f"OwensLab/commfor-model-{input_size}"

    def load(self) -> None:
        import timm
        import torch
        import torch.nn as nn
        from huggingface_hub import PyTorchModelHubMixin
        from torchvision import transforms

        input_size = self.input_size

        # Same module structure as the authors' models.ViTClassifier so the published
        # state dict ("vit.*") loads; backbone weights come from the checkpoint, so
        # timm doesn't need to download ImageNet weights.
        class ViTClassifier(nn.Module, PyTorchModelHubMixin):
            def __init__(self, model_size="small", input_size=384, patch_size=16,
                         freeze_backbone=False, device="cpu", dtype=torch.float32, **_):
                super().__init__()
                width = {"small": 384, "tiny": 192}[model_size]
                self.vit = timm.create_model(
                    f"vit_{model_size}_patch{patch_size}_{input_size}.augreg_in21k_ft_in1k", pretrained=False
                )
                self.vit.head = nn.Linear(width, 1)

            def forward(self, x):
                return self.vit(x)

        self.torch = torch
        self.model = ViTClassifier.from_pretrained(self.hub_id, device="cpu").eval()
        resize = 440 if input_size == 384 else 256
        self.transform = transforms.Compose([
            transforms.Resize(resize),
            transforms.CenterCrop(input_size),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ])

    def logit_diff(self, image: Image.Image) -> float:
        with self.torch.no_grad():
            out = self.model(self.transform(image).unsqueeze(0))
        return float(out.reshape(-1)[0])


class HFClassifier(Candidate):
    """Any 2-class transformers image classifier; AI/real labels read from config.id2label."""

    def __init__(self, name: str, hub_id: str, license: str):
        self.name, self.hub_id, self.license = name, hub_id, license

    def load(self) -> None:
        import torch
        from transformers import AutoImageProcessor, AutoModelForImageClassification

        self.torch = torch
        self.processor = AutoImageProcessor.from_pretrained(self.hub_id)
        self.model = AutoModelForImageClassification.from_pretrained(self.hub_id).eval()
        labels = {int(k): v for k, v in self.model.config.id2label.items()}
        print(f"{self.name}: labels {labels}")
        if len(labels) != 2:
            raise SystemExit(f"{self.name}: expected 2 labels, got {labels}")

        def kind(label: str) -> str | None:
            tokens = set("".join(c if c.isalnum() else " " for c in label.lower()).split())
            is_ai, is_real = bool(tokens & set(AI_WORDS)), bool(tokens & set(REAL_WORDS))
            return None if is_ai == is_real else ("ai" if is_ai else "real")

        kinds = {i: kind(l) for i, l in labels.items()}
        if sorted(k for k in kinds.values() if k) != ["ai", "real"]:
            raise SystemExit(f"{self.name}: can't tell which label is AI from {labels}")
        self.ai_index = next(i for i, k in kinds.items() if k == "ai")
        self.real_index = next(i for i, k in kinds.items() if k == "real")

    def logit_diff(self, image: Image.Image) -> float:
        with self.torch.no_grad():
            logits = self.model(**self.processor(images=image, return_tensors="pt")).logits[0]
        return float(logits[self.ai_index] - logits[self.real_index])


CANDIDATES: dict[str, Candidate] = {
    c.name: c
    for c in [
        CommunityForensics(384),
        CommunityForensics(224),
        HFClassifier("ateeqq-siglip", "Ateeqq/ai-vs-human-image-detector", "Apache-2.0"),
        HFClassifier("dima806-vit", "dima806/ai_vs_real_image_detection", "check model card"),
    ]
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--dataset-name", default=None)
    parser.add_argument("--candidates", default=",".join(CANDIDATES), help=f"Subset of {','.join(CANDIDATES)}")
    parser.add_argument("--conditions", default=",".join(CONDITIONS))
    parser.add_argument("--json-dir", type=Path, required=True)
    parser.add_argument("--scores-csv", type=Path, default=None)
    args = parser.parse_args()

    dataset_name = args.dataset_name or args.dataset.resolve().name
    conditions = [c for c in args.conditions.split(",") if c]
    ai_dir = args.dataset / "ai"
    labeled = [(p, True, generator_of(p, ai_dir)) for p in collect_images(ai_dir)]
    labeled += [(p, False, "real") for p in collect_images(args.dataset / "real")]
    if not labeled:
        raise SystemExit(f"No images under {args.dataset}")

    # Decode + degrade once, shared by every candidate.
    inputs: list[tuple[Path, bool, str, dict[str, Image.Image]]] = []
    for path, is_ai, generator in labeled:
        try:
            image = Image.open(path).convert("RGB")
        except Exception as e:  # noqa: BLE001
            print(f"Skipping unreadable image {path}: {e}", file=sys.stderr)
            continue
        inputs.append((path, is_ai, generator, {c: app_normalize(degrade(image, c)) for c in conditions}))

    args.json_dir.mkdir(parents=True, exist_ok=True)
    for name in [n for n in args.candidates.split(",") if n]:
        candidate = CANDIDATES[name]
        print(f"=== {name} ({candidate.hub_id}, license: {candidate.license}) on {dataset_name} ===", flush=True)
        try:
            candidate.load()
        except Exception as e:  # noqa: BLE001 - one broken candidate must not sink the comparison
            print(f"::warning::{name} failed to load: {e}")
            continue

        results: dict[str, list[Prediction]] = {c: [] for c in conditions}
        for index, (path, is_ai, generator, degraded) in enumerate(inputs, start=1):
            for condition, image in degraded.items():
                d = candidate.logit_diff(image)
                results[condition].append(Prediction(path, is_ai, calibrated_probability(d), generator, d))
            if index % 100 == 0:
                print(f"  {index}/{len(inputs)}", file=sys.stderr, flush=True)

        for condition, predictions in results.items():
            print(f"--- {name} | {dataset_name} | {condition} ---")
            print_report(predictions, 0.5)
            payload = {
                "model": name,
                "hub_id": candidate.hub_id,
                "license": candidate.license,
                "dataset": dataset_name,
                "condition": condition,
                "preprocess": "native",
                "calibration": None,
                "metrics": compute_metrics(predictions, 0.5),
            }
            (args.json_dir / f"{name}__{dataset_name}__{condition}.json").write_text(json.dumps(payload, indent=2))

        if args.scores_csv:
            new_file = not args.scores_csv.exists()
            with args.scores_csv.open("a", newline="") as f:
                writer = csv.writer(f)
                if new_file:
                    writer.writerow(["model", "dataset", "condition", "preprocess", "generator", "is_ai", "logit_diff"])
                for condition, predictions in results.items():
                    for p in predictions:
                        writer.writerow([name, dataset_name, condition, "native", p.generator,
                                         int(p.ground_truth_is_ai), f"{p.logit_diff:.6f}"])


if __name__ == "__main__":
    main()
