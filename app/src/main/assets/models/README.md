# Model directory (empty by design)

This build does not ship an on-device AI-image classifier model. `AIImageClassifierProvider`
looks for a file at this exact path — `app/src/main/assets/models/ai-image-detector.onnx`
— and honestly reports the classifier signal as **unavailable** when it's missing,
rather than fabricating a score.

See `docs/MODEL.md` in the repository root for:
- the specific model this app is built for (name, source, license),
- exactly how to export/convert it to ONNX,
- how to verify the input/output tensor names and label order before trusting it,
- and where to drop the resulting `.onnx` file (right here).

Do not commit a model file you have not personally verified the license of.
