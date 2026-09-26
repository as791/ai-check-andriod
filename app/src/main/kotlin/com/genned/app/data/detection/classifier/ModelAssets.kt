package com.genned.app.data.detection.classifier

import android.content.Context
import java.io.IOException

/**
 * The classifier models are bundled at build time as app assets, never downloaded at
 * runtime (see internal-docs/MODEL.md "Never download a model at runtime"). The app ships
 * with `models/ai-image-detector.onnx` (primary) and `models/commfor-224.onnx` (ensemble
 * partner); [openModelBytes] returns null only if an asset is missing from a build, which
 * callers treat as "classifier unavailable" (primary) or "run the primary alone" (partner).
 */
object ModelAssets {
    const val ASSET_PATH = "models/ai-image-detector.onnx"
    const val COMMUNITY_FORENSICS_ASSET_PATH = "models/commfor-224.onnx"

    fun openModelBytes(context: Context, assetPath: String = ASSET_PATH): ByteArray? = try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    /**
     * Cheap existence check for UI (e.g. Settings) that avoids reading the whole file.
     *
     * Deliberately uses [android.content.res.AssetManager.open] (a stream), not
     * [android.content.res.AssetManager.openFd] - openFd only succeeds for assets
     * stored *uncompressed* in the APK, and Android's build tooling compresses
     * `.onnx` by default (it isn't on the recognized no-compress extension list).
     * openFd here would report "not bundled" even when the model is genuinely
     * present and openModelBytes() (used for real inference) loads it fine -
     * confirmed by actually bundling a real model and hitting exactly this.
     */
    fun isBundled(context: Context, assetPath: String = ASSET_PATH): Boolean = try {
        context.assets.open(assetPath).close()
        true
    } catch (e: IOException) {
        false
    }
}
