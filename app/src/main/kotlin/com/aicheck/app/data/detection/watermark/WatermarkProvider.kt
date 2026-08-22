package com.aicheck.app.data.detection.watermark

import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalType
import com.aicheck.domain.provider.DetectionProvider

/**
 * Invisible generative-image watermark detection (e.g. Google DeepMind's SynthID).
 *
 * **Not implemented, and not fabricated.** As of this writing there is no open,
 * on-device detector for these watermarks: SynthID detection is only exposed through
 * Google's own (server-side, access-gated) verification tools, and no equivalent
 * exists for other vendors' schemes. Rather than approximate this with an unrelated
 * heuristic — which would misrepresent a made-up signal as watermark evidence — this
 * provider always reports [com.aicheck.domain.model.SignalAvailability.UNAVAILABLE].
 *
 * Revisit if a vendor ships an on-device or licensable verification SDK; wire it in
 * here behind this same interface so nothing else in the app needs to change.
 */
class WatermarkProvider : DetectionProvider {
    override val signalType: SignalType = SignalType.WATERMARK

    override suspend fun analyze(image: AnalysisInput): DetectionSignal =
        DetectionSignal.unavailable(
            signalType,
            "Known-watermark checking is not available on-device for this build.",
        )
}
