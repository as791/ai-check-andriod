package com.aicheck.app.ui.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val ANALYZING_PATTERN = "analyzing/{uri}/{isVideo}"
    const val RESULT_PATTERN = "result/{analysisId}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    /**
     * Intent extra key used to open a specific saved analysis directly (e.g. from
     * the experimental overlay bubble/notification), bypassing the normal
     * share-intent parsing in [com.aicheck.app.MainActivity].
     */
    const val EXTRA_OPEN_ANALYSIS_ID = "com.aicheck.app.OPEN_ANALYSIS_ID"

    fun analyzing(uri: String, isVideo: Boolean): String = "analyzing/${Uri.encode(uri)}/$isVideo"
    fun result(analysisId: Long): String = "result/$analysisId"
}
