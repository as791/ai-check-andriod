# Roadmap

This file documents intentionally deferred scope. Nothing here is implemented, and
none of it should be inferred from the codebase — it's recorded so future work has
context on what was considered and deliberately excluded from the MVP.

## V1 (this repository)

Android only. On-device classifier (when a model is bundled — see
`docs/MODEL.md`) + metadata inspection + honest provenance/watermark stubs. Local
history via Room. No accounts, no backend, no payments, no ads.

## V2

- iOS.
- An improved/ensembled classifier once real evaluation data
  (`tools/evaluate.py`) exists to justify the choice.
- Batch image scanning (multiple images in one session).
- Additional provenance standards beyond C2PA if relevant ones emerge.
- Multi-model ensemble (combine more than one classifier's output, weighted by
  measured per-model reliability).

## V3

- Video analysis (deepfake detection), likely via sampled-frame analysis reusing
  the existing image pipeline per frame rather than a bespoke video model.

## V4

- Optional cloud high-accuracy analysis, opt-in and clearly disclosed, for cases
  where on-device accuracy isn't sufficient — this would be the first time the app
  ever sends image data off-device, and would need its own explicit consent flow
  and privacy documentation before any implementation work.
- A browser extension.
- A public API.

## Experimental — explicitly not planned for MVP

Android screen/overlay integration for social-media feeds (e.g. an in-feed AI
badge on Instagram/X). **Not implemented, and not started**, because it would
require Accessibility Service access to read another app's screen content, which
raises real policy (Play Store Accessibility API misuse review), privacy (reading
another app's UI), battery, and cross-app compatibility concerns disproportionate
to an MVP. If ever pursued, it needs its own dedicated design and review — not an
incremental add to this codebase.

## Monetization (structure exists, not implemented)

`domain/model/Entitlement.kt` defines the seam (see `docs/ARCHITECTURE.md`
"Monetization-ready seams"). No paywall, purchase flow, or feature gating exists in
this build. A future Pro tier might offer unlimited analysis, a better classifier,
batch analysis, and (once built) video/deepfake detection, potentially as a
one-time purchase in the ₹299–₹699 range — pricing here is a placeholder for
product discussion, not a decision.
