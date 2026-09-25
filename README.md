# Genned

A private, on-device Android app that estimates whether an image — or a shared
video like a Reel or Short — is likely AI-generated. Share it in from
Instagram, X, Reddit, WhatsApp, a browser, or your Gallery, and get an
evidence-based likelihood estimate in seconds, along with the reasoning behind
it — never a claim of certainty.

> **This is an estimate, not proof.** AI-content detection can produce false
> positives and false negatives. See "Known accuracy limitations" below.

## What it does

1. You share an image into Genned (or pick one directly from the app).
2. It's analyzed entirely on your device: a visual AI-likelihood classifier,
   metadata inspection (EXIF, PNG generator signatures), and provenance checks.
3. You see an AI-likelihood percentage and a classification —
   **HIGH** / **UNCERTAIN** / **LOW** (deliberately never "REAL"/"FAKE"/"definitely"
   anything, unless cryptographically verified provenance actually supports it),
   plus a "Why?" breakdown of every signal that contributed.
4. The result — and only the result, never your original image — can be saved
   to local history or shared as a card via the normal Android share sheet.

No accounts, no backend, no cloud sync, no ads, no subscriptions.

## User flow

```
Home ──choose image──▶ Analyzing ──▶ Result ──▶ History
  ▲                                     │           │
  └───────── "Check Another" ───────────┘           │
  ▲                                                  │
  └──────────────── tap a saved row ─────────────────┘

Any app's Share sheet ──▶ Genned ──▶ Analyzing ──▶ Result
```

Screens: **Home** (choose image / share hint / recent checks), **Analyzing**
(preview + real pipeline-stage progress, never a fake timer), **Result** (score,
classification, per-signal "Why?" cards, disclaimer, share/check-another),
**History** (thumbnail rows, per-item delete, clear-all), **Settings** (privacy
statement, bundled-model status, version).

## How detection works

Genned never relies on a single classifier. Multiple independent evidence
sources each report a score — or explicitly "unavailable," never a fabricated
value — and those are combined transparently into one likelihood estimate:

- **Visual AI classifier** — on-device ONNX Runtime inference with the bundled
  `Dafilab/ai-image-detector` model (EfficientNet-B4, exported to ONNX).
- **Generator metadata** — scans EXIF and PNG text chunks for known
  generative-tool signatures (Stable Diffusion/ComfyUI "parameters" chunks,
  Midjourney/DALL·E/Firefly software strings, etc.). A match is real evidence;
  no match is never treated as evidence the image is authentic (this metadata
  is trivially stripped by re-saving or sharing).
- **EXIF camera metadata** — presence of camera capture fields (make/model/
  exposure) is weak evidence *for* a real photo; its absence is treated as
  near-zero evidence, since screenshots and social re-uploads strip EXIF
  constantly too.
- **Content Credentials (C2PA)** — interface implemented, honestly reports
  "unavailable" in this build. If ever enabled, a cryptographically valid
  manifest *overrides* the probabilistic blend rather than being averaged
  into it.
- **Known watermark detection** — interface implemented, honestly reports
  "unavailable": no open, on-device detector exists for generative watermarks
  like SynthID as of this writing.

**Video (Reels/Shorts):** shared video is handled by sampling a handful of
evenly-spaced still frames and running the *same* image classifier on each
one, then averaging the scores — this is frame-sampled still-image
classification, not motion/temporal or audio analysis, and every video result
says so explicitly.

**Screen overlay (experimental, off by default):** a floating bubble, enabled
from Settings → Experimental, that lets you check whatever is currently on
screen inside Instagram or WhatsApp — including content those apps only let
you forward internally, which no share-sheet integration can reach. It
captures on-screen frames via Android's `MediaProjection` API (the same
sanctioned mechanism screen recorders use) only while the bubble is visible,
and analyzes only when you tap it — never automatically, and never reading
the other app's actual content.

## Build

Requires JDK 17+ and an Android SDK (via Android Studio, or `sdkmanager`) with
the `compileSdk 35` platform + build tools installed.

```bash
./gradlew assembleDebug
```

Install to a connected device/emulator:

```bash
./gradlew installDebug
```

Run tests:

```bash
./gradlew testDebugUnitTest :domain:test
```

## How to replace/update the ML model

The bundled model lives at `app/src/main/assets/models/ai-image-detector.onnx`.
To replace it, drop a verified `.onnx` file at that path — nothing else needs to
change; the classifier and Settings screen pick it up automatically.
`tools/convert_model.py` documents exporting the current model
(`Dafilab/ai-image-detector`, Apache-2.0) from Hugging Face, and
`tools/evaluate.py` measures accuracy/precision/recall/F1/confusion matrix
against a labeled dataset. The model is **never** downloaded at runtime —
only ever bundled at build time.

## Licenses

- App source code: [Apache-2.0](LICENSE).
- Kotlin, Jetpack Compose, AndroidX libraries (Room, Navigation, ExifInterface,
  Activity/Lifecycle): Apache-2.0.
- ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`): MIT.
- Bundled classifier model (`Dafilab/ai-image-detector`): Apache-2.0.
- `contentauth/c2pa-android` (referenced, not bundled): dual MIT/Apache-2.0.

## Known accuracy limitations

- **The visual classifier ships with the app** — the ~70 MB
  `Dafilab/ai-image-detector` ONNX export is bundled at
  `app/src/main/assets/models/ai-image-detector.onnx` and runs on-device. If
  that file is ever missing from a build, the classifier signal is reported as
  unavailable (and the Settings screen says so) rather than faked.
- **No independent accuracy benchmark has been run** against the bundled
  classifier model in this project. `tools/evaluate.py` exists specifically to
  produce that number once a labeled dataset is available — do not treat this
  app's percentages as validated accuracy until that's been done.
- Like every AI-image detector, the classifier's training data has a cutoff
  and will be weaker against newer generators; compression, screenshotting,
  and intentional adversarial editing can all shift results in either
  direction.
- The evidence weights and HIGH/UNCERTAIN/LOW thresholds
  (`domain/evidence/EvidenceWeights.kt`) are a documented, transparent
  starting point — not a statistically calibrated model.

## Documentation

Deeper technical notes for contributors — architecture, the model card, the
privacy design, the roadmap, and a pre-release checklist — live in
[`internal-docs/`](internal-docs/), kept separate from this README so it stays
focused on what the app does and how to build it.
