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

    /** Cheap existence check for UI (e.g. Settings) that avoids reading the whole file. */
    fun isBundled(context: Context): Boolean = try {
        context.assets.openFd(ASSET_PATH).close()
        true
    } catch (e: IOException) {
        false
    }
}
