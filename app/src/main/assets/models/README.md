# Bundled classifier model

`ai-image-detector.onnx` (about 70 MB) is the on-device AI-image classifier that
`AIImageClassifierProvider` loads.

- **What it is:** the Hugging Face model
  [`Dafilab/ai-image-detector`](https://huggingface.co/Dafilab/ai-image-detector)
  (EfficientNet-B4), exported to ONNX. Input `pixel_values` `[1, 3, 380, 380]`;
  output is two raw logits in `[ai, human]` order.
- **License:** Apache-2.0.
- **Delivery:** bundled into the APK at build time as an app asset. It is never
  downloaded at runtime.
- **Accuracy:** not independently benchmarked yet. See `tools/evaluate.py`.

To replace or re-export it, see `internal-docs/MODEL.md` in the repository root.
Do not commit a model file whose license and provenance you have not verified.
