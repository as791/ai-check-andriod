# Privacy

## What happens to an image you check

1. The image arrives as a `content://` URI (via the Android share sheet or the
   system Photo Picker) with a temporary, scoped read grant — AI Check never
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

- **Room database** (`ai-check.db`, app-private): analysis id, timestamp, AI
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

AI Check requests **no permissions** beyond what its `<intent-filter>` declarations
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
separately if added — see `docs/ROADMAP.md` for what is *not* in this build.

## If C2PA support is added later

Per `docs/ARCHITECTURE.md`'s "C2PA integration path", reading a Content
Credentials manifest is still a fully local, on-device parse of the image's own
embedded data — it does not require a network call or change anything in this
document.
