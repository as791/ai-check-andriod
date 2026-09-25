#!/usr/bin/env python3
"""Download a balanced, labeled evaluation sample from a Hugging Face dataset.

Writes the folder layout tools/evaluate.py expects:

    <out>/real/<n>.<ext>
    <out>/ai/<generator>/<n>.<ext>

Images are written with their ORIGINAL bytes (never re-encoded), so any
compression difference between the dataset's real and AI images is preserved
exactly as published - evaluate.py's jpeg75/social conditions exist to measure
how much the detector relies on that.

Labels are never guessed: the label column's meaning is taken from its
ClassLabel names, from string values like "real"/"fake", or from explicit
--ai-values/--real-values. Anything ambiguous fails loudly and prints the
dataset's features so the mapping can be fixed. This is only ever run in the
`model-eval` CI workflow or by a maintainer; nothing here ships in the app.

Usage:
    python tools/fetch_eval_data.py --dataset <hf id> --out eval-data/<name> \\
        [--per-class 250] [--split test] [--label-col ...] [--generator-col ...] \\
        [--generator-names real,sd21,...] [--ai-values 1 --real-values 0]
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from collections import Counter
from pathlib import Path

REAL_WORDS = ("real", "human", "authentic", "natural", "photo", "genuine")
AI_WORDS = ("ai", "fake", "generated", "synthetic", "gen", "artificial")
LABEL_COLUMN_NAMES = ("label", "labels", "label_a", "is_ai", "is_fake", "fake", "class", "target")
IMAGE_COLUMN_NAMES = ("image", "img", "picture", "file")


def fail(message: str, features=None) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    if features is not None:
        print(f"Dataset features: {features}", file=sys.stderr)
    sys.exit(2)


def classify_word(value: str) -> bool | None:
    """True = AI, False = real, None = can't tell. Whole-token match, so "ai" != "chair"."""
    tokens = {t for t in "".join(c if c.isalnum() else " " for c in value.lower()).split()}
    is_real = any(w in tokens for w in REAL_WORDS)
    is_ai = any(w in tokens for w in AI_WORDS)
    if is_real == is_ai:
        return None
    return is_ai


def sniff_extension(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG"):
        return "png"
    if data.startswith(b"\xff\xd8"):
        return "jpg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data.startswith(b"BM"):
        return "bmp"
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, help="Hugging Face dataset id")
    parser.add_argument("--config", default=None, help="Dataset config name, if it has several")
    parser.add_argument("--split", default="test", help="Preferred split (falls back to validation, then train)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--per-class", type=int, default=250, help="Images per class (AI is spread across generators)")
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--max-scan", type=int, default=8000, help="Stop after scanning this many rows")
    parser.add_argument("--image-col", default=None)
    parser.add_argument("--label-col", default=None)
    parser.add_argument("--generator-col", default=None)
    parser.add_argument("--generator-names", default=None,
                        help="Comma-separated names for integer generator ids; the name 'real' marks real images")
    parser.add_argument("--ai-values", default=None, help="Comma-separated raw label values meaning AI")
    parser.add_argument("--real-values", default=None, help="Comma-separated raw label values meaning real")
    args = parser.parse_args()

    from datasets import Image as HFImage
    from datasets import get_dataset_split_names, load_dataset

    splits = get_dataset_split_names(args.dataset, args.config)
    split = next((s for s in (args.split, "test", "validation", "train") if s in splits), splits[0])
    print(f"{args.dataset}: splits={splits}, using '{split}'")

    ds = load_dataset(args.dataset, args.config, split=split, streaming=True)
    features = ds.features
    if features is None:
        first = next(iter(ds))
        fail("Streaming dataset exposes no features; pass --image-col/--label-col explicitly. "
             f"First row keys: {list(first.keys())}")
    print(f"Features: {features}")

    columns = list(features.keys())
    lower = {c.lower(): c for c in columns}

    image_col = args.image_col or next(
        (c for c in columns if isinstance(features[c], HFImage)), None
    ) or next((lower[n] for n in IMAGE_COLUMN_NAMES if n in lower), None)
    if image_col is None or image_col not in features:
        fail("Could not find the image column; pass --image-col.", features)

    label_col = args.label_col or next((lower[n] for n in LABEL_COLUMN_NAMES if n in lower), None)
    if label_col is None or label_col not in features:
        fail("Could not find a label column; pass --label-col.", features)

    generator_col = args.generator_col
    if generator_col is not None and generator_col not in features:
        fail(f"Generator column {generator_col!r} not found.", features)

    # Label value -> is_ai.
    ai_values = set(args.ai_values.split(",")) if args.ai_values else None
    real_values = set(args.real_values.split(",")) if args.real_values else None
    label_names = getattr(features[label_col], "names", None)

    def is_ai(raw) -> bool:
        key = str(raw)
        if ai_values is not None or real_values is not None:
            if ai_values and key in ai_values:
                return True
            if real_values and key in real_values:
                return False
            fail(f"Label value {raw!r} is in neither --ai-values nor --real-values.", features)
        if label_names is not None and isinstance(raw, int):
            verdict = classify_word(label_names[raw])
            if verdict is None:
                fail(f"Can't tell whether ClassLabel name {label_names[raw]!r} means AI or real; "
                     "pass --ai-values/--real-values.", features)
            return verdict
        if isinstance(raw, bool):
            fail(f"Boolean label column {label_col!r} needs explicit --ai-values/--real-values.", features)
        if isinstance(raw, str):
            verdict = classify_word(raw)
            if verdict is None:
                fail(f"Can't tell whether label {raw!r} means AI or real; pass --ai-values/--real-values.", features)
            return verdict
        fail(f"Numeric label {raw!r} without ClassLabel names; pass --ai-values/--real-values.", features)
        raise AssertionError  # unreachable

    generator_names = args.generator_names.split(",") if args.generator_names else None
    if generator_names is None and generator_col is not None:
        generator_names = getattr(features[generator_col], "names", None)

    def generator_of(row) -> str:
        if generator_col is None:
            return "ai"
        raw = row[generator_col]
        if generator_names is not None and isinstance(raw, int):
            return generator_names[raw]
        return str(raw)

    ai_generators = [g for g in (generator_names or []) if g.lower() != "real"]
    per_generator_quota = (
        math.ceil(args.per_class / len(ai_generators)) if ai_generators else args.per_class
    )

    ds = ds.cast_column(image_col, HFImage(decode=False)).shuffle(seed=args.seed, buffer_size=300)

    (args.out / "real").mkdir(parents=True, exist_ok=True)
    counts: Counter[str] = Counter()
    ai_total = 0
    scanned = skipped = 0

    for row in ds:
        scanned += 1
        if scanned > args.max_scan:
            break
        label_is_ai = is_ai(row[label_col])
        generator = generator_of(row) if label_is_ai else "real"
        if generator_col is not None and generator_of(row).lower() == "real" and label_is_ai:
            fail(f"Inconsistent labels: {label_col}={row[label_col]!r} says AI but "
                 f"{generator_col} says real. The label mapping is wrong.", features)

        if label_is_ai:
            if ai_total >= args.per_class or counts[generator] >= per_generator_quota:
                continue
        elif counts["real"] >= args.per_class:
            continue

        image = row[image_col] or {}
        data = image.get("bytes")
        extension = sniff_extension(data) if data else None
        if not data or extension is None:
            skipped += 1
            continue

        folder = args.out / "real" if not label_is_ai else args.out / "ai" / generator.replace("/", "_")
        folder.mkdir(parents=True, exist_ok=True)
        (folder / f"{counts[generator]:05d}.{extension}").write_bytes(data)
        counts[generator] += 1
        if label_is_ai:
            ai_total += 1

        if counts["real"] >= args.per_class and ai_total >= args.per_class:
            break

    print(f"Scanned {scanned} rows, skipped {skipped} without usable image bytes")
    print(f"Wrote: real={counts['real']}, ai={ai_total} "
          f"({', '.join(f'{g}={n}' for g, n in sorted(counts.items()) if g != 'real')})")
    if counts["real"] == 0 or ai_total == 0:
        fail("Need at least one real and one AI image.", features)


if __name__ == "__main__":
    main()
    # Everything is on disk. Exit without interpreter finalization: the `datasets`
    # streaming worker threads can abort there ("PyGILState_Release ... must be
    # current"), turning a successful fetch into a core dump and a spurious
    # "fetch failed" warning in CI.
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
