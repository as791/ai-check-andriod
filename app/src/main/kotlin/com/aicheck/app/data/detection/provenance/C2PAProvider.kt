package com.aicheck.app.data.detection.provenance

import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalType
import com.aicheck.domain.provider.DetectionProvider

/**
 * Content Credentials (C2PA) verification — cryptographically checking an embedded
 * manifest for provenance, declared tools, and generative-AI usage. This is the one
 * signal [com.aicheck.domain.evidence.EvidenceEngine] treats as authoritative rather
 * than probabilistic when available (see its class doc).
 *
 * **Not implemented in this build.** `contentauth/c2pa-android` (Apache-2.0/MIT,
 * github.com/contentauth/c2pa-android) is a real, maintained Kotlin/JNI wrapper over
 * the C2PA Rust SDK that can read and cryptographically validate manifests — but its
 * only documented distribution channel is GitHub Packages, which requires an
 * authenticated `GITHUB_TOKEN`. Depending on it here would make this project fail to
 * build for anyone who clones it without personal GitHub credentials, which conflicts
 * with keeping the project buildable and low-maintenance out of the box.
 *
 * To enable in a future version (see docs/ARCHITECTURE.md "C2PA integration path"):
 * 1. Add the GitHub Packages Maven repo + credentials to `settings.gradle.kts`.
 * 2. Add `implementation("org.contentauth:c2pa:<version>")` to `app/build.gradle.kts`.
 * 3. Replace this class's body with a call to `c2pa.Reader`/manifest validation on
 *    `image.originalFilePath` (never the re-encoded [AnalysisInput.normalizedFilePath]
 *    — re-encoding strips any embedded manifest).
 * 4. Map a validated manifest to a score: e.g. `0.95f` if any action/assertion
 *    declares generative-AI tool use, a low score if it only declares camera capture,
 *    and keep returning UNAVAILABLE for a present-but-invalid/unparseable manifest
 *    (that is a broken signature, not evidence either way).
 */
class C2PAProvider : DetectionProvider {
    override val signalType: SignalType = SignalType.CONTENT_CREDENTIALS

    override suspend fun analyze(image: AnalysisInput): DetectionSignal =
        DetectionSignal.unavailable(
            signalType,
            "Content Credentials checking is not enabled in this build. See " +
                "docs/ARCHITECTURE.md for the integration path.",
        )
}
