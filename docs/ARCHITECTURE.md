# Architecture

## Data flow

```
content:// URI (share sheet / photo picker)
        |
        v
ImageLoader.normalize()
  - copies original bytes verbatim (EXIF/PNG chunks/C2PA stay intact)
  - decodes a bounded, orientation-corrected preview/classifier JPEG
        |
        v
AnalysisInput (domain model: two file paths + basic dimensions, no Android types)
        |
        +---------------------+---------------------+
        |                     |                     |
        v                     v                     v
  provenance group      metadata group         visual group
  (C2PAProvider)   (Exif/KnownGeneratorMetadata)  (AIImageClassifier, Watermark)
        |                     |                     |
        +---------------------+---------------------+
                              |
                              v
                       EvidenceEngine.aggregate()
                              |
                              v
                   AnalysisResult (score, classification, signals, limitations)
                              |
                    +---------+---------+
                    v                   v
              Room (history)      Result screen
```

`AnalyzeImageUseCase` (`app/data/analysis`) runs the three provider groups in that
order, reporting each group's start as a real `AnalysisStage` — the Analyzing
screen's stage text reflects actual pipeline progress, never a simulated timer.

## Modules

- **`domain`** — pure Kotlin, no Android dependency, compiles and tests as a plain
  JVM module. Holds `DetectionProvider`, `DetectionSignal`, `AnalysisResult`,
  `Classification`, and `EvidenceEngine`. Deliberately isolated so the
  highest-risk logic (evidence aggregation) can be tested without an Android SDK
  and recalibrated later without touching UI or provider code.
- **`app`** — everything Android: Compose UI (`ui/`), provider implementations
  (`data/detection/*`), Room persistence (`data/storage`), image normalization
  (`data/image`), and share-card rendering (`data/sharing`).

A single Gradle module was deliberately *not* used for everything: keeping
`domain` Android-free is what makes `EvidenceEngineTest` runnable without an
Android SDK/emulator — a real constraint during this project's own development
sandbox, and a genuine ongoing benefit for fast local iteration.

## Detection providers

Every detector implements the same interface:

```kotlin
interface DetectionProvider {
    val signalType: SignalType
    suspend fun analyze(image: AnalysisInput): DetectionSignal
}
```

Implemented in V1:
- `ExifMetadataProvider` — presence of camera-capture EXIF fields (make/model/
  exposure). Weak, one-directional signal (see EvidenceWeights below).
- `KnownGeneratorMetadataProvider` — scans EXIF and PNG tEXt/iTXt chunks for known
  generator signatures (`GeneratorSignatures.kt`). A match is real evidence; no
  match is not evidence of anything (metadata is trivially stripped).
- `AIImageClassifierProvider` — ONNX Runtime Mobile inference. See `docs/MODEL.md`.

Implemented as honest stubs (`SignalAvailability.UNAVAILABLE`, not fabricated):
- `C2PAProvider` — see "C2PA integration path" below.
- `WatermarkProvider` — no open on-device detector exists for generative
  watermarks (e.g. SynthID); see its class doc.

Adding a new detector (a better classifier, a real C2PA check, a watermark model)
means writing one new `DetectionProvider` and adding it to one of the three
provider-group lists in `AppContainer` — nothing else changes.

## Evidence engine

`EvidenceEngine.aggregate()` has two paths:

1. **Verified provenance available** (a `CONTENT_CREDENTIALS` signal is
   `AVAILABLE`): its score *is* the result, not blended with probabilistic
   signals — matching "cryptographically verified provenance should be presented
   separately from probabilistic ML detection." Unreachable in V1 (C2PAProvider
   always reports UNAVAILABLE) but implemented and unit-tested now.
2. **Probabilistic blend** (the normal V1 path): a confidence-weighted average
   over every `AVAILABLE` signal, using fixed weights in `EvidenceWeights.kt`.

### Why corroborating signals scale their weight by their own score

The classifier is bidirectional by design (trained to discriminate both ways), so
its weight is flat regardless of the score it returns. Metadata/watermark signals
are one-directional: *finding* a generator signature is real evidence, but *not*
finding one is merely an absence of evidence. An earlier version of this engine
gave these signals a flat weight regardless of outcome, which meant a confirmed
"no generator metadata found" diluted the classifier's contribution and quietly
pulled every result toward "human" — caught by
`EvidenceEngineTest."absent generator metadata never pulls likelihood toward AI or
toward human"`. The fix: a corroborating signal's weight is scaled by its own
score, so "not found" (score 0) contributes zero weight rather than diluting
anything.

### Recalibrating the evidence weights

`EvidenceWeights.kt` is a documented starting point, not a validated calibration.
Once `tools/evaluate.py` has been run against a real labeled dataset with the
actual bundled classifier, use those precision/recall numbers to inform:
- `CLASSIFIER_WEIGHT` relative to the metadata weights,
- `HIGH_THRESHOLD` / `LOW_THRESHOLD` (currently 0.70 / 0.30, chosen to leave a
  deliberately wide UNCERTAIN band rather than force a confident-sounding verdict
  from an unvalidated classifier).

This is isolated in one object specifically so it can change without touching
providers, the use case, or the UI.

## C2PA integration path

`contentauth/c2pa-android` (Apache-2.0/MIT, github.com/contentauth/c2pa-android)
is a real, maintained Kotlin/JNI wrapper over the C2PA Rust SDK that can read and
cryptographically validate embedded manifests. It was investigated and is not
wired into V1 because its only documented distribution channel is GitHub
Packages, which requires an authenticated `GITHUB_TOKEN` to resolve as a Gradle
dependency — depending on it would break `./gradlew assembleDebug` for anyone
who clones this repo without personal GitHub credentials.

To enable it in a future version:
1. Add the GitHub Packages Maven repo + credentials to `settings.gradle.kts`.
2. `implementation("org.contentauth:c2pa:<version>")` in `app/build.gradle.kts`.
3. Replace `C2PAProvider`'s body with manifest reading/validation against
   `image.originalFilePath` (never `normalizedFilePath` — re-encoding strips any
   embedded manifest).
4. Map a validated manifest to a score (e.g. ~0.95 if any assertion declares
   generative-AI tool use, low if it only declares camera capture); keep returning
   `UNAVAILABLE` for a present-but-invalid manifest, since a broken signature is
   not evidence either way.

No other file needs to change — `EvidenceEngine`'s verified-provenance branch and
its tests already exist for this.

## Why no DI framework

The object graph (`AppContainer`) is small, static for the process lifetime, and
fits on one screen. Hilt/Koin would add a build-time dependency, annotation
processing, and a layer of generated code for a graph a single engineer can read
end-to-end faster than tracing DI-generated code. Revisit only if the graph grows
enough that manual wiring becomes the harder-to-read option.

## Monetization-ready seams

`domain/model/Entitlement.kt` defines `Entitlement { FREE, PRO }` and an
`EntitlementProvider` interface with a single `AlwaysFreeEntitlementProvider`
implementation wired in V1. No feature actually checks entitlement yet — this
exists so a future Pro tier (unlimited analysis, advanced models, batch analysis)
can gate behind one new `EntitlementProvider` implementation instead of scattered
`if` checks. See `docs/ROADMAP.md`.
