#!/usr/bin/env python3
"""Halve an ONNX model's size by storing its weights as float16, computing in float32.

Every float32 initializer with at least --min-elements values is stored as float16 and
followed by a Cast back to float32 under the original name. The graph's math is
unchanged (fp32 everywhere), so it runs on any ONNX Runtime execution provider -
unlike a full fp16 conversion, which needs fp16 kernels that ORT's CPU provider
largely lacks. The only numerical change is the one-time rounding of each weight to
fp16 (about 3 significant digits); tools/ensemble_build.py measures its effect on
the logit gap and on AUC before anything ships. ORT constant-folds the Casts when the
session is created, so inference speed and RAM use match the fp32 model; only the
file (and APK) shrinks.

Usage:
    python tools/fp16_weights.py in.onnx out.onnx
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def convert(src: Path, dst: Path, min_elements: int = 1024) -> tuple[int, int]:
    model = onnx.load(str(src))
    graph = model.graph
    new_initializers = []
    cast_nodes = []
    converted = 0
    for init in graph.initializer:
        if init.data_type != TensorProto.FLOAT or np.prod(init.dims, dtype=np.int64) < min_elements:
            new_initializers.append(init)
            continue
        values = numpy_helper.to_array(init).astype(np.float16)
        half_name = f"{init.name}__fp16"
        new_initializers.append(numpy_helper.from_array(values, half_name))
        cast_nodes.append(helper.make_node("Cast", [half_name], [init.name], to=TensorProto.FLOAT,
                                           name=f"{init.name}__cast"))
        converted += 1
    del graph.initializer[:]
    graph.initializer.extend(new_initializers)
    # Casts first so every consumer sees its (restored float32) input already defined.
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(cast_nodes + nodes)
    onnx.checker.check_model(model)
    onnx.save(model, str(dst))
    return converted, len(new_initializers)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("src", type=Path)
    parser.add_argument("dst", type=Path)
    parser.add_argument("--min-elements", type=int, default=1024)
    args = parser.parse_args()
    converted, total = convert(args.src, args.dst, args.min_elements)
    print(f"{args.src.name}: {converted}/{total} initializers stored as fp16; "
          f"{args.src.stat().st_size / 1e6:.1f} MB -> {args.dst.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
