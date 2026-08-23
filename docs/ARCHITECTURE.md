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

## Video (Reels/Shorts)

Handled by a parallel pipeline, `AnalyzeVideoUseCase` (`app/data/analysis`), not
by extending `AnalyzeImageUseCase`:

```
video Uri
   |
   v
VideoFrameSampler (MediaMetadataRetriever, N evenly-spaced frames -> JPEGs)
   |
   v
AIImageClassifierProvider.analyze() per frame (same provider as photos)
   |
   v
VideoSignalAggregator.aggregateFrameSignals() -> one AI_CLASSIFIER DetectionSignal
   |
   v
EvidenceEngine.aggregate() (same engine, same thresholds, plus explicit
UNAVAILABLE stubs for metadata/provenance and a video-specific limitation string)
```

This reuses the *same* on-device image classifier per frame rather than a
video-specific model — there isn't one bundled, and none is implied. It is
explicitly frame-sampled still-image classification, not motion, temporal, or
audio analysis; every video result's limitations say this in plain language
(`AnalyzeVideoUseCase.videoLimitation`). `VideoSignalAggregator` is pure Kotlin
(only depends on domain models), so the frame-averaging logic is unit-tested
without needing a real video file or Robolectric.

Metadata and provenance inspection (EXIF, generator signatures, C2PA) are not
implemented for video in this version — rather than silently omitting those
signal cards, `AnalyzeVideoUseCase` includes explicit `UNAVAILABLE`
`DetectionSignal`s for them so the Result screen states the gap honestly instead
of looking incomplete.

### Share-sheet routing for image vs. video

`ShareIntentParser.extractSharedMedia` resolves both `ACTION_SEND` and
`ACTION_SEND_MULTIPLE` (some share panels — notably Samsung's Gallery "Share via"
sheet — dispatch `SEND_MULTIPLE` even for a single selected item, so a
SEND-only filter simply never appears as a target from those sources) to a
`SharedMedia(uri, kind)`, where `kind` is derived from the intent's MIME type
(`image/*` vs `video/*`). `MainActivity` passes `kind` through as a nav argument
so `AnalyzingViewModel` can route to `AnalyzeImageUseCase` or
`AnalyzeVideoUseCase` accordingly.

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

## Screen overlay (experimental)

Off by default, opt-in only, toggled from Settings -> Experimental. Exists to reach
two cases nothing else in this app can: content Instagram/WhatsApp only let you
forward *within* the app itself (never through Android's share sheet, so
`ShareIntentParser` never sees it), and checking a Reel/photo while scrolling
without manually sharing each one out.

```
Settings toggle
   |  (chained permission requests, one system dialog at a time)
   v
1. Settings.canDrawOverlays()          -- "draw over other apps"
2. AppOpsManager usage-access check    -- "usage access"
3. MediaProjectionManager consent      -- one-time per service start
   |
   v
OverlayCaptureService (foreground service, type=mediaProjection)
   |
   +-- ForegroundAppWatcher: polls UsageStatsManager every 1.5s for the
   |   current foreground package; bubble is shown only over
   |   TARGET_PACKAGES (Instagram, WhatsApp) and hidden everywhere else
   |
   +-- BubbleView: a draggable floating window (plain View/Canvas, not
       Compose -- see its class doc for why). Tap it:
          |
          v
       captureFrame() -- grabs the current frame from the MediaProjection's
       ImageReader (the *rendered pixels* of whatever is on screen),
       encodes to JPEG in cacheDir
          |
          v
       AnalyzeImageUseCase.run() -- the exact same pipeline as a normal
       share-in check (classifier + metadata + provenance signals)
          |
          v
       bubble shows the resulting AI-likelihood percentage; tapping again
       opens the full Result screen (MainActivity via
       Routes.EXTRA_OPEN_ANALYSIS_ID)
```

### Why MediaProjection, not Accessibility Service

The obvious alternative — Accessibility Service — was deliberately rejected, even
though it's the more common approach for "overlay on top of another app" tools.
Accessibility Service would let (and would be flagged by Play Store review as
being able to) read Instagram/WhatsApp's actual view hierarchy and text content,
which is exactly the kind of automated in-app content scraping this project's own
constraints rule out. MediaProjection instead captures *rendered pixels only* —
the same class of access screen recorders and screenshot tools use, sanctioned by
Google for this purpose, requiring an explicit, per-session, revocable user
consent dialog every time (Android will not let this be silently
re-granted). `ForegroundAppWatcher` uses `UsageStatsManager`, not Accessibility
Service either — it only ever learns a foreground package *name*, never any
content within it.

### Why tap-to-capture, not continuous analysis

The bubble analyzes only on an explicit tap. Continuously analyzing every frame
while scrolling was considered and rejected for V1: it would run the classifier
several times a second indefinitely, with real battery cost, and would mean the
app is analyzing content the user never asked it to look at — a materially
different privacy posture than "capture what's on screen right now, once, because
I tapped a button." Tap-to-capture keeps the same consent-per-action model as
the rest of the app (you always initiate an analysis).

### What this does not do

- Does not read Instagram/WhatsApp's UI, database, or network traffic — only a
  screen-pixel capture, on tap, of whatever is currently rendered.
- Does not run unless explicitly turned on in Settings, and stops the moment the
  toggle is turned off or the persistent notification's "Stop" action is tapped —
  a foreground-service notification is mandatory and always visible while active
  (Android requirement, not a choice this app makes).
- Does not persist the captured screen frame beyond the single analysis it was
  captured for (deleted after; see `docs/PRIVACY.md`).
- Is not published to any Play Store listing claim as a "silent" or "background"
  feature — see `docs/PLAY_STORE_CHECKLIST.md` for the disclosure this requires
  before any store submission.

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
