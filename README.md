# AI Check

A private, on-device Android app that estimates whether an image — or a shared
video like a Reel or Short — is likely AI-generated. Share it in from
Instagram, X, Reddit, WhatsApp, a browser, or your Gallery, and get an
evidence-based likelihood estimate in seconds, with the reasoning behind it,
never a claim of certainty.

> **This is an estimate, not proof.** AI-content detection can produce false
> positives and false negatives. See "Known accuracy limitations" below.

## What it does

1. You share an image into AI Check (or pick one directly from the app).
2. It's analyzed entirely on your device: a visual AI-likelihood classifier (when
   bundled — see [Known accuracy limitations](#known-accuracy-limitations)),
   metadata inspection (EXIF, PNG generator signatures), and provenance checks.
3. You see an AI-likelihood percentage and a classification —
   **HIGH** / **UNCERTAIN** / **LOW** (deliberately never "REAL"/"FAKE"/"definitely"
   anything, unless cryptographically verified provenance actually supports it),
   plus a "Why?" breakdown of every signal that contributed.
4. The result — and only the result, never your original image — can be saved to
   local history or shared as a card via the normal Android share sheet.

No accounts, no backend, no cloud sync, no ads, no subscriptions.

## User flow

```
Home ──choose image──▶ Analyzing ──▶ Result ──▶ History
  ▲                                     │           │
  └───────── "Check Another" ───────────┘           │
  ▲                                                  │
  └──────────────── tap a saved row ─────────────────┘

Any app's Share sheet ──▶ AI Check ──▶ Analyzing ──▶ Result
```

Screens: **Home** (choose image / share hint / recent checks), **Analyzing**
(preview + real pipeline-stage progress, never a fake timer), **Result** (score,
classification, per-signal "Why?" cards, disclaimer, share/check-another),
**History** (thumbnail rows, per-item delete, clear-all), **Settings** (privacy
statement, bundled-model status, version).

## Architecture

```
domain/   Pure Kotlin — models, DetectionProvider interface, EvidenceEngine.
          No Android dependency; compiles and tests as a plain JVM module.
app/
  ui/                 Compose screens + navigation + ViewModels
  data/detection/      One package per evidence source (classifier/metadata/
                        provenance/watermark), each implementing DetectionProvider
  data/storage/        Room (local history only)
  data/sharing/        Result-card rendering + share intent
  data/image/          content:// URI → normalized, orientation-corrected image
  data/analysis/        Orchestrates providers → EvidenceEngine → persistence
```

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the full data-flow
diagram, the evidence-weighting design (including a real bug it caught — see that
doc's "Why corroborating signals scale their weight by their own score"), and the
C2PA integration path that was investigated but not wired into V1.

## How detection works

AI Check never relies on a single classifier. Multiple independent
`DetectionProvider`s each report a `DetectionSignal` (score, or explicitly
"unavailable" — never a fabricated value), and `EvidenceEngine` combines them
transparently:

- **Visual AI classifier** — on-device ONNX Runtime inference. See
  [docs/MODEL.md](docs/MODEL.md) for the exact model, its Apache-2.0 license, and
  why it isn't bundled in this build by default.
- **Generator metadata** — scans EXIF and PNG text chunks for known
  generative-tool signatures (Stable Diffusion/ComfyUI "parameters" chunks,
  Midjourney/DALL·E/Firefly software strings, etc.). A match is real evidence; no
  match is never treated as evidence the image is authentic (this metadata is
  trivially stripped by re-saving or sharing).
- **EXIF camera metadata** — presence of camera capture fields (make/model/
  exposure) is weak evidence *for* a real photo; its absence is treated as
  near-zero evidence, since screenshots and social re-uploads strip EXIF
  constantly too.
- **Content Credentials (C2PA)** — interface implemented, honestly reports
  "unavailable" in this build (see docs/ARCHITECTURE.md for exactly why and how to
  enable it). If ever enabled, a cryptographically valid manifest *overrides* the
  probabilistic blend rather than being averaged into it.
- **Known watermark detection** — interface implemented, honestly reports
  "unavailable": no open, on-device detector exists for generative watermarks like
  SynthID as of this writing.

**Video (Reels/Shorts):** shared video is handled by sampling a handful of
evenly-spaced still frames and running the *same* image classifier on each one,
then averaging the scores — this is frame-sampled still-image classification,
not motion/temporal or audio analysis, and every video result says so
explicitly. See `docs/ARCHITECTURE.md` "Video (Reels/Shorts)".

## Build

Requires JDK 17+ and an Android SDK (via Android Studio, or `sdkmanager`) with
`compileSdk 35` platform + build tools installed.

```bash
./gradlew assembleDebug
```

Install to a connected device/emulator:

```bash
./gradlew installDebug
```

> **Note on this repository's own build verification:** this project was built in
> a sandboxed environment whose network egress policy blocks
> `dl.google.com`/`maven.google.com` — the hosts that serve the Android Gradle
> Plugin and all AndroidX/Compose/Room artifacts. That means the Android app
> module (`:app`) could not actually be compiled in that sandbox. What *was*
> verified there: the `:domain` module (pure Kotlin, only needs Maven Central)
> built and its 13 unit tests passed locally, and `.github/workflows/android-ci.yml`
> runs the full `:app` build + lint + tests on GitHub's own runners (which have
> normal internet access) on every push — check that workflow's status for the
> real build signal. If you're building locally with normal internet access, the
> commands above should just work; if they don't, please file an issue with the
> error.

## Run tests

```bash
./gradlew testDebugUnitTest :domain:test
```

- `domain/src/test` — `EvidenceEngineTest`: classification thresholds, the
  content-credentials override path, and the "absence of evidence isn't evidence"
  weighting behavior.
- `app/src/test` — metadata parsing (`GeneratorSignaturesTest`,
  `PngChunkReaderTest`), malformed-image/URI/share-intent handling
  (`ImageLoaderTest`, `ShareIntentParserTest`), video frame-score averaging
  (`VideoSignalAggregatorTest`), and Room history persistence (`AnalysisDaoTest`,
  `HistoryRepositoryTest`) — the Android-dependent ones via Robolectric so they
  run as fast JVM tests without an emulator.

Detector *quality* (as opposed to code correctness) is evaluated separately —
see `tools/evaluate.py` and [Known accuracy limitations](#known-accuracy-limitations).

## How to replace/update the ML model

See **[docs/MODEL.md](docs/MODEL.md)** in full. Short version: drop a verified
`.onnx` file at `app/src/main/assets/models/ai-image-detector.onnx` — nothing else
needs to change; `AIImageClassifierProvider` and the Settings screen pick it up
automatically. `tools/convert_model.py` documents exporting the target model
(`Dafilab/ai-image-detector`, Apache-2.0) from Hugging Face, and
`tools/evaluate.py` measures accuracy/precision/recall/F1/confusion
matrix/false-positive/false-negative rate against a labeled dataset. The model is
**never** downloaded at runtime — only ever bundled at build time by a maintainer
who has read that doc.

## Licenses

- App source code: [Apache-2.0](LICENSE).
- Kotlin, Jetpack Compose, AndroidX libraries (Room, Navigation, ExifInterface,
  Activity/Lifecycle): Apache-2.0.
- ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`): MIT.
- Target classifier model (`Dafilab/ai-image-detector`): Apache-2.0 — see
  docs/MODEL.md for why this was chosen over CC-BY-NC alternatives that don't
  permit commercial use.
- `contentauth/c2pa-android` (referenced, not bundled — see
  docs/ARCHITECTURE.md "C2PA integration path"): dual MIT/Apache-2.0.

A full third-party notice list for whatever ends up in a release build belongs in
an in-app licenses screen before a store submission — see
[docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md).

## Known accuracy limitations

- **No classifier is bundled by default in this build** (see docs/MODEL.md) — out
  of the box, AI Check's likelihood is based on metadata signals only, which is
  meaningfully weaker than with the visual classifier running. The Settings screen
  states this honestly rather than implying full functionality.
- **No independent accuracy benchmark has been run** against the target
  classifier model in this project. `tools/evaluate.py` exists specifically to
  produce that number once a model and a labeled dataset are available — do not
  treat this app's percentages as validated accuracy until that's been done.
- Like every AI-image detector, the classifier's training data has a cutoff and
  will be weaker against newer generators; compression, screenshotting, and
  intentional adversarial editing can all shift results in either direction.
- The evidence weights and HIGH/UNCERTAIN/LOW thresholds
  (`domain/evidence/EvidenceWeights.kt`) are a documented, transparent starting
  point — not a statistically calibrated model. See docs/ARCHITECTURE.md
  "Recalibrating the evidence weights."
- This app's build/test verification itself was constrained by its development
  sandbox's network policy — see "Build" above and
  [docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md) "smoke-test on a
  real device" for what still needs manual verification before any release.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) for what's intentionally out of scope for
this MVP (iOS, video/deepfake detection, cloud analysis, and — explicitly — any
Accessibility-Service-based social-feed overlay, which was considered and
rejected for the reasons documented there).
