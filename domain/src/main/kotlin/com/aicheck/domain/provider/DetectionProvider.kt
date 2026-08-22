package com.aicheck.domain.provider

import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalType

/**
 * One evidence source in the aggregation pipeline (see docs/ARCHITECTURE.md).
 * Implementations live in the `app` module (they need Android APIs — EXIF, bitmaps,
 * ONNX Runtime); this interface is what lets new detectors (a better classifier, a
 * real C2PA check, a watermark model) be added later without touching
 * [com.aicheck.domain.evidence.EvidenceEngine] or the UI.
 *
 * [analyze] must never throw for expected failure modes (corrupt file, unsupported
 * format, missing model) — catch internally and return [DetectionSignal.unavailable]
 * or [DetectionSignal.error] instead, so one failing provider never aborts the whole
 * analysis.
 */
interface DetectionProvider {
    val signalType: SignalType

    suspend fun analyze(image: AnalysisInput): DetectionSignal
}
