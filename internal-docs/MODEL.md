# Model documentation

## Status in this repository

**The app ships an ensemble of two bundled models** (see "Ensemble (shipped)"):
`ai-image-detector.onnx` (35.2 MB) and `commfor-224.onnx` (Community Forensics ViT-S 224,
MIT, 43.5 MB), both with fp16-stored weights and fp32 math. If the second file is missing
or fails to load, the first runs alone with its own calibration.

**The primary classifier model is bundled.** `app/src/main/assets/models/ai-image-detector.onnx`
is committed to the repository: the `Dafilab/ai-image-detector` model
below, exported to ONNX (export steps in "Replacing the model file"). It loads and runs on real
devices — debug logs show output like
`Classifier raw output=[[-3.787038, 2.1529553]] -> P(ai)=0.0026`, i.e. the graph
accepts the `pixel_values` input and emits two raw logits. If the file is ever
missing from a build, `AIImageClassifierProvider` honestly reports the
`AI_CLASSIFIER` signal as unavailable rather than fabricating a score.

Accuracy has been measured, and scores are calibrated and bands set from data (see
"Measured accuracy"). `ModelConfig.BASE_CONFIDENCE`, which weighs the classifier
against the other signals, is still a fixed value.

**`Dafilab/ai-image-detector` is a gated repo on Hugging Face** — confirmed by
actually running the conversion: a plain download gets a `401 GatedRepoError`, even
though the model itself is public and Apache-2.0 licensed. You must be logged in
*and* have clicked through the access request on the model page before downloading
works (see step 1 in "Replacing the model file"). Its `config.json` is also not in
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
`app/src/main/kotlin/com/genned/app/data/detection/classifier/ModelConfig.kt`, and
are duplicated in `tools/evaluate.py` for the offline evaluation harness — keep both
in sync if you change them.

### Output interpretation

The ONNX export emits the classifier head's **raw logits** (the output tensor is
named `logits`; timm's `efficientnet_b4` has no softmax layer), so
`ModelConfig.interpretOutput` applies the softmax itself:
- a 2-class output `[ai_logit, human_logit]` → `P(ai) = sigmoid(ai_logit - human_logit)`, or
- a single raw logit → `P(ai) = sigmoid(logit)`.

An earlier version "normalized" with `ai / (ai + human)`, which is not a softmax:
whenever both logits were negative — an entirely ordinary output — a `total <= 0`
guard forced exactly `0.5`, so confident predictions were displayed as a flat 50%.
Found on-device with the real bundled model; `ModelConfigTest` now pins the exact
failing input.

**This label order is now confirmed**, not guessed: the real `config.json` on
`Dafilab/ai-image-detector` publishes `"label_mapping": {"0": "ai", "1": "human"}`
directly, i.e. output index 0 = P(ai). An earlier version of this file/code assumed
the reverse (`[human, ai]`), which would have silently inverted every result — caught
before the model was bundled, precisely because this doc insisted on confirming it
rather than trusting the initial guess. The bundled export runs with
`ModelConfig.INPUT_NAME = "pixel_values"` and a 2-logit output. If you replace the
model:
1. Open the new export in [Netron](https://netron.app) and confirm the input tensor
   name and output shape.
2. Update `ModelConfig.kt` if the input name differs, or if a future model version's
   `config.json` changes `label_mapping`.

### Model size

The full-precision export is 70.1 MB. The app ships it with fp16-stored weights
(`tools/fp16_weights.py`: weights stored as fp16 and cast back to fp32 at load, so
all math stays fp32), which is 35.2 MB. The logit gap moves by at most 0.022
(mean 0.007) on real dataset images, which is negligible after calibration
(slope 0.23). Dynamic int8 quantization broke this model (logit gaps off by up to
35), so it is not used.

### Known limitations

- **Moderate accuracy, overconfident raw scores.** See "Measured accuracy": AUC
  0.91 / 0.80 on two public datasets, with about a third of real images in the
  second one scored as AI. Its raw probabilities are far too extreme to show
  as-is.
- **Training data cutoff.** Like all AI-image detectors, this model's training data
  has a cutoff; it will be systematically weaker against generators released after
  that point (a fundamental limitation of every classifier-based approach, not
  specific to this model).
- **Vulnerable to adversarial pressure.** Compression, screenshotting, resizing, and
  intentional adversarial perturbation can all degrade classifier accuracy in either
  direction (false positive or false negative).
- **Binary, single-signal.** It does not identify *which* generator produced an
  image, and (per this app's design) its output is never treated as proof on its
  own — see `internal-docs/ARCHITECTURE.md`'s evidence-aggregation model.

## Measured accuracy

The `Model eval` GitHub Actions workflow (`.github/workflows/model-eval.yml`,
manual trigger only: Actions → Model eval → Run workflow) benchmarks the bundled
`.onnx` against public labeled datasets. It runs on GitHub's runners because they
have the open internet access (Hugging Face) that a sandboxed dev environment may not.

- `tools/fetch_eval_data.py` streams a seeded, balanced sample (default 250 real +
  250 AI per dataset, AI spread across generators) and writes the original file
  bytes into `real/` and `ai/<generator>/`. Labels are never guessed: ambiguous
  label columns fail loudly with the dataset's features printed.
- `tools/evaluate.py` scores every image under 3 conditions × 2 preprocessing
  modes. Conditions: `original`; `jpeg75`; and `social`, which caps the long edge at
  1080 and applies JPEG q75, like an Instagram re-upload. Every condition then gets
  the app's own normalization: long edge ≤2048 and JPEG q92, same as `ImageLoader`.
  Preprocessing modes: `squash`, the app's current straight resize to 380×380, and
  `center_crop`.
  It reports AUC, accuracy/FPR/FNR at 0.5, the share of real/AI images landing
  in each app band (LOW/UNCERTAIN/HIGH), calibration (ECE + reliability table) and
  per-generator results.
- `tools/eval_report.py` merges the per-run JSON into the job summary and the
  `model-eval-results` artifact. Only numbers leave the runner, never images.

Datasets: `Rajarshi-Roy-research/Defactify_Image_Dataset` (MS-COCO real photos;
SD 2.1, SDXL, SD3, DALL-E 3, Midjourney v6) and
`julienlucas/midjourney-dalle-sd-nanobananapro-dataset`. Caveats:
- If the model was trained on either dataset, its numbers there are inflated.
- Defactify's `Label_B` id → generator-name order follows the dataset card's
  listing order and isn't independently verified. Only `0 = real` is checked,
  against `Label_A`.
- Neither dataset covers what the overlay actually captures, which is a
  phone-screen crop of an Instagram post. `social` only approximates it.

### Baseline: first run (2026-09-25, [run 36171690452](https://github.com/as791/genned/actions/runs/36171690452))

250 real + 250 AI images per dataset, raw (uncalibrated) scores, app preprocessing
(`squash`). "Real shown HIGH" / "AI shown LOW" use the app's bands at the time
(LOW <30%, HIGH ≥70%).

| Dataset | Condition | AUC | Accuracy @0.5 | Real→AI (FPR) | AI missed (FNR) | Real shown HIGH | AI shown LOW | ECE | Scores >99% or <1% |
|---|---|---|---|---|---|---|---|---|---|
| Defactify | original | 0.914 | 83.0% | 10.0% | 24.0% | 8.0% | 22.0% | 0.132 | 64.0% |
| Defactify | social | 0.915 | 82.8% | 10.0% | 24.4% | 7.6% | 21.6% | 0.127 | 64.6% |
| MJ/DALL·E/SD/NBP | original | 0.795 | 72.0% | 36.4% | 19.6% | 33.6% | 15.6% | 0.222 | 60.0% |
| MJ/DALL·E/SD/NBP | social | 0.770 | 69.8% | 40.8% | 19.6% | 38.8% | 16.8% | 0.247 | 58.4% |

Findings:
- **Overconfident.** 60–74% of scores are above 99% or below 1%. Scores above 90%
  are actually AI 93% (Defactify) / 72% (other set) of the time, and scores below
  10% are still AI 18% of the time.
- **Real photos are often flagged.** On the second set about a third of real
  images land in HIGH.
- **Compression barely matters.** `jpeg75`/`social` are within about 3 AUC
  points of `original`. The Instagram re-upload isn't the main problem.
- **Center crop is mixed.** Defactify AUC goes 0.914 → 0.964 and FPR 10% → 5.2%.
  The other set is unchanged or slightly worse (0.795 → 0.794 original,
  0.748 → 0.722 jpeg75).
- **Per generator** (detected at 0.5, original): Midjourney v6 86%, SDXL 82%,
  SD 2.1 80%, SD3 76%, **DALL·E 3 56%**.

### Calibration and preprocessing (shipped, [run 36185656205](https://github.com/as791/genned/actions/runs/36185656205))

- **Preprocessing: two views averaged.** The app runs the model on the whole
  image squashed to 380×380 *and* on an aspect-preserving center crop, then
  averages the two logit gaps. AUC: Defactify 0.952 vs 0.915 (squash only), the
  harder set 0.773 vs 0.771. The adoption rule was "at least as good as squash on
  both datasets". Cost: 2 inferences per image.
- **Calibration:** `P(ai) = sigmoid(0.2252 · (ai − human) + 0.0494)` (Platt scaling
  on 3,000 per-image scores, all datasets and conditions pooled). Scores above 99%
  or below 1% drop from 58–71% of images to about 1%. ECE drops from 0.125/0.247 to
  0.102/0.081 (pooled fit). A fit on one dataset alone transfers only partly to the
  other (ECE 0.161/0.205), so treat the percentages as approximate.
- **Bands** (`EvidenceWeights`): **HIGH ≥ 90%, LOW < 25%**, UNCERTAIN in between
  (LOW later moved to < 15% for the ensemble, see "Ensemble (shipped)").
  Worst case over every dataset × condition: **2.8% of real images shown HIGH**
  (was up to 38.8% with the old 70% band) and **8.0% of AI images shown LOW**.
  Accepted cost: many images now read UNCERTAIN. That's the honest answer for a
  detector with AUC 0.77 on the harder set, and only a better model (Phase 1 model
  comparison) can shrink the UNCERTAIN band.
- Example: the on-device output `[[4.50, -3.50]]` that used to read 99.97% now reads
  86% (UNCERTAIN).
- `tools/evaluate.py --preprocess avg --calibration app` reproduces the app as
  shipped. The `Model eval` report includes it as the "app as shipped" rows.

**Verified** ([run 36187349202](https://github.com/as791/genned/actions/runs/36187349202),
app exactly as shipped):

| Dataset | AUC | Real shown HIGH | AI shown LOW | ECE | Scores >99% or <1% |
|---|---|---|---|---|---|
| Defactify (original / jpeg75 / social) | 0.951–0.952 | 0.0% | 6.4% | 0.102 | 1.0% |
| MJ/DALL·E/SD/NBP (original / jpeg75 / social) | 0.744–0.803 | 2.4–2.8% | 3.6–8.0% | 0.088–0.093 | 0.0% |

AUC is identical to the uncalibrated two-view scores, as it must be, since
calibration only rescales scores and doesn't reorder them. The false-HIGH target
(≤5%) holds in every condition.

### Candidate detectors: first comparison ([run 36186295441](https://github.com/as791/genned/actions/runs/36186295441))

Same images and conditions. Each candidate uses its own preprocessing, and its
scores are raw.

| Model | Defactify AUC | MJ/DALL·E/SD/NBP AUC | Real passed @0.5 | AI caught @0.5 (harder set) |
|---|---|---|---|---|
| Bundled (as shipped) | 0.951 | 0.744–0.803 | 94% / 56–60% | 74–84% |
| Community Forensics ViT-S 224 (MIT) | 0.959–0.960 | 0.624–0.708 | 99% | 10–15% |
| Community Forensics ViT-S 384 (MIT) | 0.941 | 0.520–0.589 | 99.6% | 9–14% |

Community Forensics almost never flags a real image, but it misses most AI
images in the harder set. It's not a replacement on its own. Its errors do
complement the bundled model's (for example, it catches 76% of DALL·E 3 vs 56%),
which is what the ensemble analysis tests. The SigLIP and dima806 candidates
didn't run in this pass because of a tooling bug that has since been fixed (a
`'hum'` label crashed the script).

### Video: the Reels path ([run 36187968201](https://github.com/as791/genned/actions/runs/36187968201))

This is the app's exact pipeline: 5 frames per video, two views per frame,
calibrated, and the per-frame probabilities averaged. 60 real + 60 AI videos per
dataset.

| Dataset | AUC | Real shown HIGH | AI shown LOW | Mean score, real videos | AI caught @0.5 |
|---|---|---|---|---|---|
| [DF26](https://huggingface.co/datasets/DF26/DF26) (2026 talking heads: Veo 3.1, Kling 3.0, Wan 2.6, Grok Imagine, HunyuanVideo 1.5, LTX 2.3…) | **0.646** | **28.3%** | 0% | 0.83 | 100% |
| [DeepAction](https://huggingface.co/datasets/faridlab/deepaction_v1) (actions: Veo, RunwayML, CogVideoX, AnimateDiff, VideoPoet, SD) | 0.797 | 3.3% | 0% | 0.65 | 98% |

**The image model reads real video frames as AI-like.** Compression, motion blur
and talking-head framing all push real videos up. The image calibration doesn't
carry over to video, so about 1 in 4 real talking-head clips showed HIGH.

### Video: all models + video calibration ([run 36190406439](https://github.com/as791/genned/actions/runs/36190406439))

100 real + 100 AI videos per dataset, the same 5 sampled frames for every model.
Candidates use raw scores.

| Model | AUC DeepAction | AUC DF26 | Real videos shown HIGH (DA / DF26) | AI caught at ≤5% false alarms (worst dataset) |
|---|---|---|---|---|
| Bundled (image-calibrated, before this fix) | 0.789 | 0.651 | 2% / 26% | 21% |
| Community Forensics ViT-S 224 (MIT) | **0.970** | **0.743** | 0% / 0% | 33% |
| Community Forensics ViT-S 384 (MIT) | 0.944 | 0.730 | 0% / 2% | 19% |
| Ateeqq SigLIP | 0.645 | 0.691 | 43% / 99% | 18% |
| dima806 ViT | 0.496 | 0.543 | 33% / 27% | 5% |

The best video ensemble is commfor-224 + commfor-384 (mean): 38% of AI caught at
≤5% false alarms, and 39% with SigLIP added.

**Shipped video calibration.** `VideoSignalAggregator` now averages the frames'
logit gaps (inverting the image calibration) and applies
`P(ai) = sigmoid(0.2071 · meanGap − 1.3977)`. This was fit on the bundled
model's per-video mean frame gap. The calibration error (ECE) goes from
0.36 / 0.49 raw to 0.14 / 0.20 when fit on the other dataset only, and to
0.09 pooled. At the global bands (HIGH ≥ 90%, LOW < 25%), the worst case is
**0% of real videos shown HIGH** and 3% of AI videos shown LOW. The honest
consequence is that with this model a video almost never reads HIGH. That
takes near-certain frames (mean image score about 0.98+), and no benchmark
video, real or AI, got there. Example: a real talking-head clip whose frames
average 0.83 now reads about 50% (UNCERTAIN) instead of HIGH. Actually catching
AI video needs a better video model; see the comparison above.

### Ensemble (shipped, [Ensemble build run 36196623887](https://github.com/as791/genned/actions/runs/36196623887))

The app now combines the bundled model with **Community Forensics ViT-S 224**
([OwensLab/commfor-model-224](https://huggingface.co/OwensLab/commfor-model-224), MIT,
CVPR 2025). The two models make different mistakes: the bundled model catches more
AI photos, while Community Forensics is much stronger on video and rarely flags real
content. Every number below comes from the exact ONNX files the app bundles.

`EnsembleConfig`:
`s = ((gap − μd)/σd + (cf − μc)/σc) / 2` is the mean of both models' standardized
logits, with standardization fit on photo scores. Photos use
`P = sigmoid(3.1935·s + 0.1634)`. Video uses `P = sigmoid(3.4921·mean(s) − 1.8588)`
over the 5 sampled frames. Python mirror: `tools/evaluate.py` `APP_ENSEMBLE`.

| | Bundled alone | Community Forensics alone | **Ensemble** |
|---|---|---|---|
| Photos AUC (Defactify / MJ-DALL·E-SD-NBP) | 0.952 / 0.773 | 0.959 / 0.654 | **0.991 / 0.778** |
| Photos: AI caught at 5% false alarms | 73.2% / 29.7% | 83.3% / 22.8% | **94.8% / 33.9%** |
| Video AUC (DeepAction / DF26) | 0.793 / 0.651 | 0.970 / 0.743 | 0.958 / **0.746** |
| Video: AI caught at 5% false alarms | 41% / 21% | 70% / 33% | **82%** / 30% |
| **Worst case over photos and video** | 21% | 22.8% | **30%** |

- **Bands.** HIGH stays at ≥ 90%. In the worst case, 2.8% of real photos and 2.0%
  of real videos show HIGH (target ≤ 5%). LOW moved from < 25% to **< 15%**: at
  25%, 17.6% of AI photos and 17% of AI videos read LOW. At 15% that is 10.0% and
  9.0% (target ≤ 10%). That value is the calibration's own recommendation for
  both photos and video.
- **Parity** (40 images).
  - Community Forensics, authors' PyTorch pipeline vs the app's preprocessing plus
    ONNX: max logit difference 0.0001. That needed torchvision's exact geometry:
    Resize truncates the long side, CenterCrop rounds half-to-even.
  - fp16 vs fp32 weights: 0.022 for the bundled model, 0.014 for Community
    Forensics.
  - Both files load in the app's ONNX Runtime 1.19 (IR 8, opset 17).
- **Cost.** 3 model runs per check: 2 views for the bundled model, 1 for Community
  Forensics. The models grow from 70.1 MB to 78.7 MB in total (+8.6 MB).
- **Still limited on talking-head video.** DF26 AUC is 0.746, and some generators
  (LTX, Hunyuan) fool both models.

### Adversarial robustness (Phase 2, [run 36196534662](https://github.com/as791/genned/actions/runs/36196534662), #15)

`tools/adv_eval.py` attacks a differentiable copy of the app pipeline (onnx2torch,
with the app's JPEG q92 inside the attack via a straight-through estimator):
- white-box: FGSM, PGD with EOT, and a PGD adapted to each defense;
- transfer from a surrogate model;
- black-box Square Attack (200 queries).

*Evasion* means an AI image shown LOW. *Framing* means a real image shown HIGH.
*Laundered* means the result is re-scored after JPEG q75. Worst case is taken over
all attacks at 8/255.

| Defense | Model runs per check | Clean AUC | Worst evasion (direct / laundered) | Worst framing | Square (black box) | Eligible |
|---|---|---|---|---|---|---|
| none | 2 | 0.848 | 100% / 100% | 100% | 83% | – |
| **consistency** (JPEG75 + median-3 disagreement → abstain) | 4 | 0.848 | 91.7% / 100% | **20.8%** | 92% | ✅ selected by the rule |
| transform (4 random resize/JPEG views) | 4 | 0.899 | 100% / 100% | 100% | **33%** | ✅ |
| ensemble | 3 | 0.913 | 100% / 100% | 100% | 58% | ✅ |
| smoothing (8 noisy copies, σ = 0.03) | 8 | 0.648 | 100% / 83% | 100% | 0% | ❌ loses clean accuracy, over budget |

Reading:
- **No inference-time defense that is cheap enough for a phone stops an adaptive
  white-box attacker.** This matches the literature. Only training-time defenses,
  such as adversarial training, move this number, and those need GPU training.
- The consistency check is the only defense that meaningfully protects real
  people. Real images pushed to HIGH drop from 100% to 21%, because the attacked
  image is caught and the app abstains.
- Random transforms make black-box queries much harder.

Caveats:
- The sample is small: 24 AI and 24 real images (12 for Square).
- The run used the single bundled model at the old LOW < 25% band, before the
  ensemble shipped. Re-run it on the ensemble before integrating any defense.
- Not yet benchmarked: TRIM and RAID (#15).

Caveats: MS-COCO (Defactify's real photos) is a very common training source, so
Defactify numbers may be optimistic. What the second dataset's "real" class
contains (photos only, or also artwork) hasn't been verified.

## Replacing the model file

Follow these steps to re-export the model with `tools/convert_model.py` or replace
the bundled file with a different one.

1. Request access to the gated repo: visit
   https://huggingface.co/Dafilab/ai-image-detector while logged into a (free)
   Hugging Face account and click through to agree/request access. Then create a
   read-scoped token at https://huggingface.co/settings/tokens and run
   `huggingface-cli login` locally with it — a plain download otherwise fails with
   `401 GatedRepoError` (see the gated-repo note under "Status in this repository").
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
