# Privacy

## What happens to an image you check

1. The image arrives as a `content://` URI (via the Android share sheet or the
   system Photo Picker) with a temporary, scoped read grant — Genned never
   requests broad storage permission.
2. It is copied into the app's **private** cache (`ImageLoader`), never a shared or
   external location.
3. All detection — the on-device classifier, EXIF/metadata inspection, provenance
   checks — runs **on-device**. No network call is made as part of analysis, and
   the app requests no network-related permission for this path.
4. After analysis, the original and normalized working copies are deleted
   (`AnalyzingViewModel.cleanUp`). Only a small (≤256px) thumbnail and the
   aggregated result are kept, in the app's private storage, for your local
   history — never the full-resolution image.
5. Nothing is uploaded anywhere. There is no backend in this app (see README).

## What's stored, and where

- **Room database** (`genned.db`, app-private): analysis id, timestamp, AI
  likelihood, classification, the signal list, and limitations text — all as
  plain local rows, never synced anywhere.
- **Thumbnails** (`filesDir/thumbnails/`, app-private): small downscaled JPEGs,
  one per saved analysis.
- **Shared cache** (`cacheDir/shared/`, app-private, cleared aggressively): working
  copies during analysis, and generated result-card PNGs for the share sheet.
  Exposed to other apps *only* via short-lived `FileProvider` grants when you
  explicitly tap "Share Result" — never any other file in the app's storage.

`android:allowBackup="false"` and explicit `dataExtractionRules`/`fullBackupContent`
exclusions mean none of this is included in Android's automatic cloud backup.

## What's deliberately excluded from the shareable result card

The result card you can share (`ResultCardRenderer`) is rendered entirely from the
aggregated score/classification/signal summary — **never your original image** —
specifically to avoid handing your (or someone else's) image content to whatever
platform you share the card to, and to avoid copyright complications from
redistributing an image you didn't create.

## Permissions

Genned requests **no permissions** beyond what its `<intent-filter>` declarations
imply. Specifically, it does not request:
- broad storage access (`READ_EXTERNAL_STORAGE`/`READ_MEDIA_IMAGES`) — the modern
  Photo Picker and scoped `content://` URIs make this unnecessary;
- network access for the analysis path (there is no analysis-time network
  permission in the manifest at all);
- camera, location, contacts, or any other sensitive permission group.

## Logging

The app does not log image bytes, extracted metadata content, or filenames to
persistent logs. Standard Android crash/ANR reporting (if a developer enables one
in a future build) is out of scope for this document and must be disclosed
separately if added — see `internal-docs/ROADMAP.md` for what is *not* in this build.

Settings -> Experimental -> "Copy debug log" is a manual, user-initiated exception
worth calling out specifically: tapping it reads this app's own recent on-device
log (via `LogcatCapture`) and copies it to your clipboard, so you can hand it to
a developer if something's misbehaving (e.g. the overlay bubble). It only ever
contains what the overlay code above already logs — app package names,
timestamps, and show/hide booleans — never image bytes or screen content, and
nothing is sent anywhere automatically; the log only leaves your device if you
paste and send it yourself. Android's own log isolation means this can only ever
read log lines this app produced itself, never another app's or the system's.

## Screen overlay (experimental, off by default)

Settings -> Experimental -> "Enable overlay bubble" is a separate, opt-in feature
covered by its own privacy rules, since it works differently from the rest of the
app. See `internal-docs/ARCHITECTURE.md` "Screen overlay (experimental)" for the technical
design; this section is what it means for your data specifically.

- **Off unless you turn it on.** Nothing here runs, and none of the permissions
  below are requested, unless you explicitly enable it in Settings.
- **What it can see:** while active, and only while Instagram or WhatsApp is the
  app currently in front, a small floating bubble appears, and the system begins
  mirroring the screen into this app's process for as long as you stay in that
  app (the same class of access a screen-recording app uses) — this is what lets
  repeat taps in one visit work without re-prompting you for permission each
  time. Tapping the bubble reads a single frame from that mirror at *that
  moment* and runs it through the same on-device analysis as any other check in
  this app; every other frame the system produces while you're scrolling is
  never read, saved, or looked at by this app at all — the mirror exists only so
  a tap has something to read from, not to continuously observe your screen.
  Nothing is captured before the feature is enabled or after you leave
  Instagram/WhatsApp (bubble hidden), and the mirror itself is torn down the
  moment you do. Android requires you to grant this via a system "screen
  capture" consent dialog every time the overlay service is (re)started; this
  is not something the app can request silently, and the consent does not
  persist across a service restart.
- **What it does not see:** it never reads Instagram/WhatsApp's own data, network
  traffic, message content, or accessibility tree — it has no access to those at
  all. The two special permissions it requests are `SYSTEM_ALERT_WINDOW` (needed
  to draw the floating bubble) and usage access (needed only to know *which app
  package* is currently in front, so the bubble can hide itself everywhere except
  Instagram/WhatsApp — this reveals a package name only, never any content).
- **What happens to a captured frame:** it is written to the app's private cache,
  analyzed on-device exactly like a shared image, and then handled the same way as
  any other analysis — a thumbnail + result are kept in local history, the
  full-frame capture itself is deleted once analysis completes.
- **Foreground notification:** while the overlay is active, Android requires a
  persistent, visible notification ("Genned overlay is active") with a Stop
  action — this cannot be hidden or suppressed, by design, so you always know the
  feature is running.
- **Turning it off:** flipping the Settings switch off, or tapping "Stop" on the
  notification, immediately stops the foreground service, removes the bubble, and
  releases the screen-capture session. Nothing continues running in the
  background afterward.

## If C2PA support is added later

Per `internal-docs/ARCHITECTURE.md`'s "C2PA integration path", reading a Content
Credentials manifest is still a fully local, on-device parse of the image's own
embedded data — it does not require a network call or change anything in this
document.
