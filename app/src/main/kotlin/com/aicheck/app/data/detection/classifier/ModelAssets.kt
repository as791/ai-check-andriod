package com.aicheck.app.data.detection.classifier

import android.content.Context
import java.io.IOException

/**
 * The classifier model is bundled at build time as an app asset, never downloaded at
 * runtime (see docs/MODEL.md "Never silently download a model"). This build ships
 * without one — [openModelBytes] returning null is the expected, honest state until a
 * maintainer follows docs/MODEL.md to add `app/src/main/assets/models/ai-image-detector.onnx`.
 */
object ModelAssets {
    const val ASSET_PATH = "models/ai-image-detector.onnx"

    fun openModelBytes(context: Context): ByteArray? = try {
        context.assets.open(ASSET_PATH).use { it.readBytes() }
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
    fun isBundled(context: Context): Boolean = try {
        context.assets.open(ASSET_PATH).close()
        true
    } catch (e: IOException) {
        false
    }
}
