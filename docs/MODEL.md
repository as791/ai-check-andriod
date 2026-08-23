# Model documentation

## Status in this repository

**No classifier model file is bundled in this build.** `AIImageClassifierProvider`
looks for `app/src/main/assets/models/ai-image-detector.onnx` and, when it isn't
there, honestly reports the `AI_CLASSIFIER` signal as unavailable rather than
fabricating a score. Everything below documents the model this project is *built
for* and exactly how to add it — see "Adding the model file".

Why no model ships out of the box: producing it requires downloading ~100MB+ of
weights from Hugging Face and running a PyTorch → ONNX export, which needs
`huggingface.co` network access and a `torch`/`timm`/`onnx` toolchain. Neither was
available in the sandbox this project was scaffolded in (see README "Known
limitations"). This is the exact contingency the project brief asked for: ship the
provider abstraction and precise instructions rather than a fabricated or
unlicensed model file.

**`Dafilab/ai-image-detector` is a gated repo on Hugging Face** — confirmed by
actually running the conversion: a plain download gets a `401 GatedRepoError`, even
though the model itself is public and Apache-2.0 licensed. You must be logged in
*and* have clicked through the access request on the model page before downloading
works (see step 1 in "Adding the model file"). Its `config.json` is also not in
`timm`'s hub-config format (`timm.create_model("hf_hub:...")` fails with
`KeyError: 'architecture'`) — the repo instead publishes a raw training checkpoint,
`pytorch_model.pth` (71MB, renamed at some point from `model_epoch_8_acc_0.9859.pth`
— that "acc_0.9859" is the model author's own training/validation accuracy, not an
independently verified number; still worth running `tools/evaluate.py` yourself).
`tools/convert_model.py` downloads that checkpoint directly, builds a bare
`efficientnet_b4` architecture, and loads the checkpoint's weights into it, handling
the couple of common checkpoint-dict shapes a training script might have saved
(bare state dict, or wrapped under a `state_dict`/`model_state_dict` key).

## The target model

| Field | Value |
|---|---|
| Name | `Dafilab/ai-image-detector` |
| Source | https://huggingface.co/Dafilab/ai-image-detector |
| Architecture | EfficientNet-B4 (via `timm`) |
| License | Apache-2.0 (permits commercial use) |
| Task | Binary classification: human-made vs. AI-generated image |
| Native input size | 380×380 RGB |

### Why this model

The project brief requires a classifier that (a) permits commercial use, (b) is
mobile-viable or convertible to ONNX/TFLite, (c) has a clearly documented license,
and (d) doesn't require a hosted inference service. Two candidates were evaluated:

- **`Organika/sdxl-detector`** / **`umm-maybe/AI-image-detector`** — well-known,
  reasonably accurate community detectors, but licensed **CC-BY-NC** (non-commercial).
  Disqualified outright by the commercial-use requirement.
- **`Dafilab/ai-image-detector`** (chosen) — Apache-2.0, EfficientNet-B4. Meaningfully
  smaller and faster than transformer-based alternatives (e.g. a SwinV2-Base export
  of `haywoodsloan/ai-image-detector-deploy`, also Apache-2.0 but ~4× the parameter
  count and slower on-device), while still being a real, general-purpose AI-image
  classifier rather than a narrow single-generator detector.

No independent accuracy benchmark of this specific model was run as part of this
project (see "Known limitations" below and `tools/evaluate.py`).

### Expected input

- 380×380 RGB, resized (not center-cropped) from the normalized image.
- Pixel values scaled to `[0, 1]`, then normalized with the standard ImageNet
  statistics `timm` models default to:
  - mean = `[0.485, 0.456, 0.406]`
  - std = `[0.229, 0.224, 0.225]`
- Layout: `NCHW`, i.e. tensor shape `[1, 3, 380, 380]`.

These exact values live in one place in the code:
`app/src/main/kotlin/com/aicheck/app/data/detection/classifier/ModelConfig.kt`, and
are duplicated in `tools/evaluate.py` for the offline evaluation harness — keep both
in sync if you change them.

### Output interpretation

`ModelConfig.interpretOutput` expects either:
- a 2-class output `[ai_probability, human_probability]` (softmax-style), or
- a single sigmoid output = `P(ai)`.

**This label order is now confirmed**, not guessed: the real `config.json` on
`Dafilab/ai-image-detector` publishes `"label_mapping": {"0": "ai", "1": "human"}`
directly (fetched while working through "Adding the model file" below), i.e. output
index 0 = P(ai). An earlier version of this file/code assumed the reverse
(`[human, ai]`), which would have silently inverted every result — caught before a
model was ever bundled, precisely because this doc insisted on confirming it rather
than trusting the initial guess. Still worth a final sanity check once you have the
real `.onnx` export:
1. Open it in [Netron](https://netron.app) and confirm the real input tensor name
   (`ModelConfig.INPUT_NAME` currently assumes `"pixel_values"`) and output shape.
2. Update `ModelConfig.kt` if the input name differs, or if a future model version's
   `config.json` changes `label_mapping`.

### Model size

Not measured directly (no export was run in this environment). An EfficientNet-B4
ONNX export is typically in the tens of megabytes at full precision; int8
quantization (via `onnxruntime.quantization` or `torch.quantization`) can shrink
this further for a smaller APK, at some accuracy cost that should be re-verified
with `tools/evaluate.py` if you do this.

### Known limitations

- **Not independently benchmarked in this project.** The Apache-2.0 license and
  architecture are verified; accuracy against a real, diverse, up-to-date dataset of
  AI-generated and real images is not. Run `tools/evaluate.py` against a labeled
  dataset before treating its output as more than a rough signal, and before
  calibrating `EvidenceWeights`/classification thresholds in the domain module.
- **Training data cutoff.** Like all AI-image detectors, this model's training data
  has a cutoff; it will be systematically weaker against generators released after
  that point (a fundamental limitation of every classifier-based approach, not
  specific to this model).
- **Vulnerable to adversarial pressure.** Compression, screenshotting, resizing, and
  intentional adversarial perturbation can all degrade classifier accuracy in either
  direction (false positive or false negative).
- **Binary, single-signal.** It does not identify *which* generator produced an
  image, and (per this app's design) its output is never treated as proof on its
  own — see `docs/ARCHITECTURE.md`'s evidence-aggregation model.

## Adding the model file

1. Request access to the gated repo: visit
   https://huggingface.co/Dafilab/ai-image-detector while logged into a (free)
   Hugging Face account and click through to agree/request access. Then create a
   read-scoped token at https://huggingface.co/settings/tokens and run
   `huggingface-cli login` locally with it — a plain download otherwise fails with
   `401 GatedRepoError` (see "Why no model ships out of the box" above).
2. `pip install -r tools/requirements.txt`
3. `python tools/convert_model.py --output app/src/main/assets/models/ai-image-detector.onnx`
   — read that script's docstring first; it explains the verification steps you
   must do by hand (input/output names, label order) before trusting the export.
4. Run `python tools/evaluate.py --model app/src/main/assets/models/ai-image-detector.onnx --dataset <labeled dataset>`
   and sanity-check accuracy/precision/recall before shipping.
5. Rebuild the app. `SettingsScreen` and `AIImageClassifierProvider` will
   automatically detect the bundled file — no other code change is required.
6. Never commit a model file you have not personally verified the license and
   provenance of.

## Never download a model at runtime

Per the project's engineering rules, `ModelAssets` only ever reads from the app's
own compiled assets (`app/src/main/assets/models/`). Nothing in this codebase
fetches a model from the network at install- or run-time — a model change is a
build-time decision made by a maintainer who has read this document, not something
that happens silently on a user's device.
