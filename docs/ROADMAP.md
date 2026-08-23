# Roadmap

This file documents intentionally deferred scope. Nothing here is implemented, and
none of it should be inferred from the codebase — it's recorded so future work has
context on what was considered and deliberately excluded from the MVP.

## V1 (this repository)

Android only. On-device classifier (when a model is bundled — see
`docs/MODEL.md`) + metadata inspection + honest provenance/watermark stubs. Local
history via Room. No accounts, no backend, no payments, no ads.

**Video (Reels/Shorts) is supported on a frame-sampled basis**: `AnalyzeVideoUseCase`
extracts a handful of evenly-spaced still frames (`VideoFrameSampler`, via
`MediaMetadataRetriever`) from a shared video and classifies each one with the
*same* image classifier used for photos, then averages the per-frame scores
(`VideoSignalAggregator`). This is explicitly still-image classification applied
per frame — not motion, temporal, or audio analysis, and no video-specific model
exists or is implied. Every video result's limitations say so. Metadata/provenance
inspection (EXIF, generator signatures, C2PA) is not implemented for video in this
version — see docs/ARCHITECTURE.md.

## V2

- iOS.
- An improved/ensembled classifier once real evaluation data
  (`tools/evaluate.py`) exists to justify the choice.
- Batch image/video scanning (multiple items in one session — the app currently
  analyzes only the first item of a multi-select share).
- Additional provenance standards beyond C2PA if relevant ones emerge.
- Multi-model ensemble (combine more than one classifier's output, weighted by
  measured per-model reliability).

## V3

- Real video-native analysis: motion/temporal consistency checks and audio
  analysis, going beyond the frame-sampled still-image classification shipped in
  V1 — genuine deepfake detection needs more than N independent frame scores.
- A dedicated video/deepfake classifier model, once one meeting the same
  license/on-device requirements as the image classifier (see docs/MODEL.md) is
  identified and evaluated.

## V4

- Optional cloud high-accuracy analysis, opt-in and clearly disclosed, for cases
  where on-device accuracy isn't sufficient — this would be the first time the app
  ever sends image data off-device, and would need its own explicit consent flow
  and privacy documentation before any implementation work.
- A browser extension.
- A public API.

## Experimental — screen overlay (implemented, opt-in, off by default)

Unlike the rest of V1, this one *is* implemented, specifically to reach content
Instagram/WhatsApp only let you forward internally (never through Android's share
sheet) and to let you check a Reel/photo while scrolling. See
`docs/ARCHITECTURE.md` "Screen overlay (experimental)" and `docs/PRIVACY.md`
"Screen overlay" for the full design and exactly what it does and does not access.

In short: a draggable bubble, shown only while Instagram/WhatsApp is in front
(detected via `UsageStatsManager` package-name polling, not Accessibility
Service), that captures a single on-screen frame via `MediaProjection` — the same
sanctioned mechanism screen recorders use — only when tapped, and runs it through
the same on-device pipeline as any other check. Never continuous/automatic
analysis, never a read of the other app's actual content, and always carries a
mandatory persistent notification while active. This was a deliberate, informed
scope decision (not a silent addition) given real Play Store policy and privacy
tradeoffs — see `docs/PLAY_STORE_CHECKLIST.md` before any store submission with
this feature enabled.

Not yet done for this feature: an interval-based "auto-capture" mode was
considered and intentionally left out of this version — the toggle does not exist
in Settings, only tap-to-capture. If added later, it needs its own privacy-review
pass (continuous analysis is a materially different consent model than "analyze
once because I tapped a button").

## Monetization (structure exists, not implemented)

`domain/model/Entitlement.kt` defines the seam (see `docs/ARCHITECTURE.md`
"Monetization-ready seams"). No paywall, purchase flow, or feature gating exists in
this build. A future Pro tier might offer unlimited analysis, a better classifier,
batch analysis, and (once built) video/deepfake detection, potentially as a
one-time purchase in the ₹299–₹699 range — pricing here is a placeholder for
product discussion, not a decision.
