package com.aicheck.app.data.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved analysis. Deliberately stores only a small thumbnail path (see
 * ThumbnailStore), never the full-resolution original image, and freezes the
 * aggregated result (score/classification/signals/limitations) as it was actually
 * shown to the user at analysis time rather than recomputing it later — so history
 * doesn't silently change if EvidenceEngine's weights are recalibrated in a future
 * update.
 */
@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val thumbnailPath: String?,
    val aiLikelihood: Float,
    val classification: String,
    val signalsJson: String,
    val limitationsJson: String,
)
