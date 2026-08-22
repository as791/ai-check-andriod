# Play Store release checklist

Not done as part of this repository — a punch list for whoever takes this to an
actual store listing.

## Before any release build

- [ ] Bundle a real classifier model and verify it (see `docs/MODEL.md`) — the app
      is fully functional without one (metadata-only analysis), but a store listing
      implying "AI detection" should ship with the classifier working.
- [ ] Run `tools/evaluate.py` against a real labeled dataset and record the
      accuracy/precision/recall/F1 numbers somewhere durable (e.g. this file, or a
      release note) — do not publish accuracy claims that weren't measured.
- [ ] Bump `compileSdk`/`targetSdk` in `app/build.gradle.kts` to whatever Google
      Play currently requires for new submissions (as of this writing, Play
      requires new app submissions to target a recent Android API level on a
      rolling basis — check
      https://developer.android.com/google/play/requirements/target-sdk for the
      current number, since it changes yearly).
- [ ] Replace the placeholder launcher icon
      (`app/src/main/res/drawable/ic_launcher_foreground.xml`) with real, designed
      app icon assets (adaptive icon foreground/background, plus a Play Store
      512×512 icon and feature graphic).
- [ ] Smoke-test on a real device/emulator — this project's build sandbox had no
      Android SDK or emulator access (see README "Known limitations"), so the full
      user flow (share-sheet entry from real apps, photo picker, analysis,
      history, share-card) has not been manually verified end-to-end. Do this
      before any release.
- [ ] Verify the share-sheet entry actually appears and works from Instagram, X,
      Reddit, WhatsApp, and a browser/Gallery specifically — share-sheet behavior
      can vary by source app.

## Store listing content

- [ ] Screenshots of Home, Analyzing, Result (HIGH/UNCERTAIN/LOW examples),
      History, and Settings.
- [ ] A short description that uses the app's own careful language: "estimate,"
      "likelihood," never "detects fake images with certainty."
- [ ] Privacy policy URL — publish `docs/PRIVACY.md` (or a version of it) at a
      public URL; Play Console requires one even for an app with no backend.
- [ ] Data safety form: answer honestly based on `docs/PRIVACY.md` — no data
      collected/shared/uploaded, given the on-device-only analysis path. Re-check
      this section if C2PA or any network-touching feature is later added.
- [ ] Content rating questionnaire.
- [ ] Target audience / ads declaration: no ads in this build.

## Technical / policy

- [ ] Signing: generate a real release keystore (never commit it — see
      `.gitignore`), configure `app/build.gradle.kts` `signingConfigs` for
      `release`, and store the keystore + Play App Signing enrollment per Google's
      current guidance.
- [ ] `isMinifyEnabled`/`isShrinkResources` are already on for `release` builds —
      after bundling a real ONNX model, verify R8 doesn't strip anything ONNX
      Runtime's JNI bridge needs (see `app/proguard-rules.pro`; re-test a release
      build specifically, since debug builds skip minification).
- [ ] Accessibility Service policy: this app does not use Accessibility Service
      (see `docs/ROADMAP.md` "Experimental") — nothing to declare here, but keep it
      that way unless a full policy review accompanies that feature.
- [ ] Confirm the final `applicationId` (`com.aicheck.app`) is the one you intend
      to publish under — it cannot be changed after the first release.
- [ ] Review all third-party licenses actually bundled in the shipped APK
      (ONNX Runtime, AndroidX libraries, Kotlin coroutines/serialization, and the
      classifier model itself) and include them in an in-app licenses screen or
      `NOTICE` file — `SettingsScreen` currently shows model info but a full
      OSS-license list is not yet wired up.

## Post-launch

- [ ] Decide on and configure crash/ANR reporting if desired — none is included in
      this build; adding one needs its own privacy-doc update first (see
      `docs/PRIVACY.md` "Logging").
- [ ] Set up a real support/contact channel (issue tracker, email) before the
      listing goes live.
