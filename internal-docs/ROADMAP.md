# Roadmap

This file documents intentionally deferred scope. Nothing here is implemented, and
none of it should be inferred from the codebase — it's recorded so future work has
context on what was considered and deliberately excluded from the MVP.

## V1 (this repository)

Android only. On-device classifier (bundled `Dafilab/ai-image-detector` ONNX
model — see `internal-docs/MODEL.md`) + metadata inspection + honest provenance/watermark stubs. Local
history via Room. No accounts, no backend, no payments, no ads.

**Video (Reels/Shorts) is supported on a frame-sampled basis**: `AnalyzeVideoUseCase`
extracts a handful of evenly-spaced still frames (`VideoFrameSampler`, via
`MediaMetadataRetriever`) from a shared video and classifies each one with the
*same* image classifier used for photos, then averages the per-frame scores
(`VideoSignalAggregator`). This is explicitly still-image classification applied
per frame — not motion, temporal, or audio analysis, and no video-specific model
exists or is implied. Every video result's limitations say so. Metadata/provenance
inspection (EXIF, generator signatures, C2PA) is not implemented for video in this
version — see internal-docs/ARCHITECTURE.md.

## Detection quality phases (current focus)

**Phase 1: measured, honest detection on images, Reels and shared content.**
Tracked in [#14](https://github.com/as791/genned/issues/14).
- Benchmark the bundled model (`Model eval`). Done: baseline in
  `internal-docs/MODEL.md`.
- Calibrate scores and set HIGH/LOW bands from data. Done: two-view averaging,
  Platt calibration, HIGH ≥ 90% / LOW < 25% (LOW < 15% since the ensemble).
- Compare candidate detectors (`Model compare`) and switch only for a clear win.
- **Multi-model ensemble feasibility, ONNX only** (pulled forward from V2).
  Using only the benchmarked models: export each to ONNX, verify parity with
  PyTorch, measure int8/fp32 size and latency (`tools/onnx_feasibility.py`),
  then test whether any combination beats the best single model, weights fit on
  one dataset and scored on the other (`tools/ensemble.py`). Ship an ensemble
  only if it clears the on-device budget and beats the best affordable single
  model by ≥3 points of AI caught at 5% false alarms.
  **Done:** bundled model + Community Forensics 224, fp16 weights, 78.7 MB total.
  Worst case over photos and video: 30% of AI caught at 5% false alarms, vs 21% for
  the single model (Ensemble build run 36196623887). LOW band moved to < 15%.
- Benchmark the Reels path on real vs AI video (`Video eval`, which needs the
  `HF_TOKEN` secret for its gated datasets).

**Phase 2: adversarial robustness.** Tracked in
[#15](https://github.com/as791/genned/issues/15). Genned is open source, so an
attacker can compute gradients against the exact on-device model. White-box
attacks are therefore the realistic threat, not a theoretical one. Phase 2
benchmarks mobile-feasible defenses against **adaptive** white-box attacks
(PGD/APGD with EOT/BPDA) and black-box attacks (transfer, query-based, the RAID
transferable-adversarial set, and social-media laundering). It then integrates
the defense with the best worst-case robustness that stays within the
clean-accuracy and on-device cost budget. It starts once Phase 1 has fixed the
model, because a defense is evaluated on a specific detector.

Order: Phase 1 (including the ensemble decision) → Phase 2 → V2. The goal is a
working Android app with good, well-calibrated results before any other platform.

## V2

- An improved/ensembled classifier: **moved into Phase 1** (ensemble
  feasibility above), since the evaluation data now exists.
- Batch image/video scanning (multiple items in one session — the app currently
  analyzes only the first item of a multi-select share).
- Additional provenance standards beyond C2PA if relevant ones emerge.
- Multi-model ensemble: **moved into Phase 1** (see above). V2 keeps only
  follow-ups, such as re-fitting the ensemble weights as new models and datasets
  arrive.

## V3

- Real video-native analysis: motion/temporal consistency checks and audio
  analysis, going beyond the frame-sampled still-image classification shipped in
  V1 — genuine deepfake detection needs more than N independent frame scores.
- A dedicated video/deepfake classifier model, once one meeting the same
  license/on-device requirements as the image classifier (see internal-docs/MODEL.md) is
  identified and evaluated.

## V4

- Optional cloud high-accuracy analysis, opt-in and clearly disclosed, for cases
  where on-device accuracy isn't sufficient — this would be the first time the app
  ever sends image data off-device, and would need its own explicit consent flow
  and privacy documentation before any implementation work.
- A browser extension.
- A public API.

## Later

- iOS. Only after the Android app ships with good, calibrated results and
  Phases 1–2 are done.

## Experimental — screen overlay (implemented, opt-in, off by default)

Unlike the rest of V1, this one *is* implemented, specifically to reach content
Instagram/WhatsApp only let you forward internally (never through Android's share
sheet) and to let you check a Reel/photo while scrolling. See
`internal-docs/ARCHITECTURE.md` "Screen overlay (experimental)" and `internal-docs/PRIVACY.md`
"Screen overlay" for the full design and exactly what it does and does not access.

In short: a draggable bubble, shown only while Instagram/WhatsApp is in front
(detected via `UsageStatsManager` package-name polling, not Accessibility
Service), that captures a single on-screen frame via `MediaProjection` — the same
sanctioned mechanism screen recorders use — only when tapped, and runs it through
the same on-device pipeline as any other check. Never continuous/automatic
analysis, never a read of the other app's actual content, and always carries a
mandatory persistent notification while active. This was a deliberate, informed
scope decision (not a silent addition) given real Play Store policy and privacy
tradeoffs — see `internal-docs/PLAY_STORE_CHECKLIST.md` before any store submission with
this feature enabled.

Not yet done for this feature: an interval-based "auto-capture" mode was
considered and intentionally left out of this version — the toggle does not exist
in Settings, only tap-to-capture. If added later, it needs its own privacy-review
pass (continuous analysis is a materially different consent model than "analyze
once because I tapped a button").

## Monetization (structure exists, not implemented)

`domain/model/Entitlement.kt` defines the seam (see `internal-docs/ARCHITECTURE.md`
"Monetization-ready seams"). No paywall, purchase flow, or feature gating exists in
this build. A future Pro tier might offer unlimited analysis, a better classifier,
batch analysis, and (once built) video/deepfake detection, potentially as a
one-time purchase in the ₹299–₹699 range — pricing here is a placeholder for
product discussion, not a decision.
