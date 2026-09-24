package com.genned.domain.provider

import com.genned.domain.model.AnalysisInput
import com.genned.domain.model.DetectionSignal
import com.genned.domain.model.SignalType

/**
 * One evidence source in the aggregation pipeline (see internal-docs/ARCHITECTURE.md).
 * Implementations live in the `app` module (they need Android APIs — EXIF, bitmaps,
 * ONNX Runtime); this interface is what lets new detectors (a better classifier, a
 * real C2PA check, a watermark model) be added later without touching
 * [com.genned.domain.evidence.EvidenceEngine] or the UI.
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
