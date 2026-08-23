package com.aicheck.app.ui.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val ANALYZING_PATTERN = "analyzing/{uri}/{isVideo}"
    const val RESULT_PATTERN = "result/{analysisId}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun analyzing(uri: String, isVideo: Boolean): String = "analyzing/${Uri.encode(uri)}/$isVideo"
    fun result(analysisId: Long): String = "result/$analysisId"
}
