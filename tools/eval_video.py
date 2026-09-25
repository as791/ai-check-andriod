#!/usr/bin/env python3
"""Benchmark the bundled classifier on real vs AI-generated VIDEO, the way the app sees it.

The app has no video model: VideoFrameSampler takes FRAME_COUNT (5) frames at
evenly spaced positions (i+1)/(FRAME_COUNT+1) of the duration, caps each at 1024 px
and re-encodes it as JPEG q90; each frame then goes through the normal image path
(ImageLoader normalization + classifier), and VideoSignalAggregator averages the
per-frame P(ai). This script reproduces exactly that, per video, so the numbers
answer "how well does Genned do on a Reel?" - not how a hypothetical video model
would do. (Android seeks to the closest sync frame; OpenCV seeks to the exact frame -
a small, documented difference.)

Two subcommands:

  fetch   pick a balanced, seeded sample of video files from a Hugging Face dataset
          repo (by path patterns) and download only those. Gated datasets need
          HF_TOKEN in the environment, after accepting the dataset's terms.
  eval    score every video under <dir>/real/** and <dir>/ai/<generator>/** and write
          the same JSON/CSV as tools/evaluate.py (preprocess "video5").

Usage:
    python tools/eval_video.py fetch --repo DF26/DF26 --out eval-video/df26 \\
        --real-regex '^real/' --ai-regex '^fake/' \\
        --generator-regex '^fake/[^/]+/([^/]+)/' --per-class 60
    python tools/eval_video.py eval --model app/src/main/assets/models/ai-image-detector.onnx \\
        --dataset eval-video/df26 --json-dir results/ --scores-csv results/video-scores.csv

Only numbers leave the runner; videos and frames are never uploaded.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import random
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate import (  # noqa: E402 - share the image pipeline + metrics with evaluate.py
    Prediction,
    _sigmoid,
    app_normalize,
    compute_metrics,
    print_report,
    run_inference,
    to_model_input,
    to_tensor,
)

VIDEO_EXTENSIONS = {".mp4", ".mov", ".webm", ".mkv", ".avi", ".m4v"}
FRAME_COUNT = 5  # VideoFrameSampler.FRAME_COUNT
MAX_FRAME_DIMENSION = 1024  # VideoFrameSampler.MAX_FRAME_DIMENSION
FRAME_JPEG_QUALITY = 90  # VideoFrameSampler compress quality


def fetch(args: argparse.Namespace) -> None:
    from huggingface_hub import HfApi

    token = os.environ.get("HF_TOKEN") or None
    files = HfApi(token=token).list_repo_files(args.repo, repo_type="dataset")
    videos = [f for f in files if Path(f).suffix.lower() in VIDEO_EXTENSIONS]
    real = [f for f in videos if re.search(args.real_regex, f)]
    ai = [f for f in videos if re.search(args.ai_regex, f)]
    print(f"{args.repo}: {len(files)} files, {len(real)} real videos, {len(ai)} AI videos")
    if not real or not ai:
        sample = "\n  ".join(videos[:20])
        raise SystemExit(f"Patterns matched no real or no AI videos. First video paths:\n  {sample}")

    rng = random.Random(args.seed)
    by_generator: dict[str, list[str]] = defaultdict(list)
    for f in ai:
        match = re.search(args.generator_regex, f) if args.generator_regex else None
        by_generator[match.group(1) if match else "ai"].append(f)
    quota = max(1, -(-args.per_class // len(by_generator)))  # ceil
    chosen_ai = []
    for generator, paths in sorted(by_generator.items()):
        rng.shuffle(paths)
        chosen_ai += [(generator, p) for p in paths[:quota]]
    rng.shuffle(chosen_ai)
    chosen_ai = chosen_ai[: args.per_class]
    rng.shuffle(real)
    chosen_real = real[: args.per_class]
    print(f"Downloading {len(chosen_real)} real + {len(chosen_ai)} AI "
          f"({', '.join(f'{g}={sum(1 for x, _ in chosen_ai if x == g)}' for g in sorted(by_generator))})")

    for index, path in enumerate(chosen_real):
        _download(args, path, args.out / "real" / f"{index:04d}{Path(path).suffix.lower()}", token)
    for index, (generator, path) in enumerate(chosen_ai):
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", generator)
        _download(args, path, args.out / "ai" / safe / f"{index:04d}{Path(path).suffix.lower()}", token)


def _download(args: argparse.Namespace, repo_path: str, target: Path, token: str | None) -> None:
    from huggingface_hub import hf_hub_download

    target.parent.mkdir(parents=True, exist_ok=True)
    local = Path(hf_hub_download(args.repo, repo_path, repo_type="dataset", token=token))
    target.write_bytes(local.resolve().read_bytes())  # cache entries are symlinks into the blob store


def sample_frames(path: Path) -> list[Image.Image]:
    """VideoFrameSampler: FRAME_COUNT frames at (i+1)/(FRAME_COUNT+1), <=1024 px, JPEG q90."""
    import cv2

    capture = cv2.VideoCapture(str(path))
    try:
        total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        if total <= 0:
            return []
        frames = []
        for i in range(FRAME_COUNT):
            index = min(total - 1, int(total * (i + 1) / (FRAME_COUNT + 1)))
            capture.set(cv2.CAP_PROP_POS_FRAMES, index)
            ok, bgr = capture.read()
            if not ok:
                continue
            image = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
            if max(image.size) > MAX_FRAME_DIMENSION:
                scale = MAX_FRAME_DIMENSION / max(image.size)
                image = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))),
                                     Image.BILINEAR)
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=FRAME_JPEG_QUALITY)
            buffer.seek(0)
            frames.append(Image.open(buffer).convert("RGB"))
        return frames
    finally:
        capture.release()


def evaluate_videos(args: argparse.Namespace) -> None:
    import onnxruntime as ort

    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    ai_dir, real_dir = args.dataset / "ai", args.dataset / "real"

    def collect(directory: Path) -> list[Path]:
        return sorted(p for p in directory.rglob("*") if p.suffix.lower() in VIDEO_EXTENSIONS) if directory.is_dir() else []

    labeled = [(p, True, p.relative_to(ai_dir).parts[0] if len(p.relative_to(ai_dir).parts) > 1 else "ai")
               for p in collect(ai_dir)]
    labeled += [(p, False, "real") for p in collect(real_dir)]
    if not labeled:
        raise SystemExit(f"No videos under {ai_dir} or {real_dir}")

    predictions: list[Prediction] = []
    frame_counts = []
    for index, (path, is_ai, generator) in enumerate(labeled, start=1):
        frames = sample_frames(path)
        if not frames:
            print(f"Skipping undecodable video {path}", file=sys.stderr)
            continue
        diffs = [run_inference(session, to_tensor(to_model_input(app_normalize(f), "squash"))) for f in frames]
        # VideoSignalAggregator averages per-frame probabilities (not logits).
        probability = float(np.mean([_sigmoid(d) for d in diffs]))
        mean_diff = float(np.log(max(probability, 1e-12) / max(1 - probability, 1e-12)))
        predictions.append(Prediction(path, is_ai, probability, generator, mean_diff))
        frame_counts.append(len(frames))
        if index % 20 == 0:
            print(f"  {index}/{len(labeled)} videos", file=sys.stderr, flush=True)

    name = args.dataset_name or args.dataset.resolve().name
    print(f"=== {name} | video (5 frames, app pipeline) | {len(predictions)} videos, "
          f"mean {np.mean(frame_counts):.1f} frames decoded ===")
    print_report(predictions, 0.5)
    if args.json_dir:
        args.json_dir.mkdir(parents=True, exist_ok=True)
        payload = {"dataset": name, "condition": "video", "preprocess": "video5", "calibration": None,
                   "metrics": compute_metrics(predictions, 0.5)}
        (args.json_dir / f"{name}__video__video5.json").write_text(json.dumps(payload, indent=2))
    if args.scores_csv:
        new_file = not args.scores_csv.exists()
        with args.scores_csv.open("a", newline="") as f:
            writer = csv.writer(f)
            if new_file:
                writer.writerow(["dataset", "condition", "preprocess", "generator", "is_ai", "logit_diff"])
            for p in predictions:
                writer.writerow([name, "video", "video5", p.generator, int(p.ground_truth_is_ai), f"{p.logit_diff:.6f}"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    f = sub.add_parser("fetch")
    f.add_argument("--repo", required=True)
    f.add_argument("--out", type=Path, required=True)
    f.add_argument("--real-regex", required=True)
    f.add_argument("--ai-regex", required=True)
    f.add_argument("--generator-regex", default=None, help="Regex with one group capturing the generator name")
    f.add_argument("--per-class", type=int, default=60)
    f.add_argument("--seed", type=int, default=1234)

    e = sub.add_parser("eval")
    e.add_argument("--model", type=Path, required=True)
    e.add_argument("--dataset", type=Path, required=True)
    e.add_argument("--dataset-name", default=None)
    e.add_argument("--json-dir", type=Path, default=None)
    e.add_argument("--scores-csv", type=Path, default=None)

    args = parser.parse_args()
    if args.command == "fetch":
        fetch(args)
    else:
        evaluate_videos(args)


if __name__ == "__main__":
    main()
