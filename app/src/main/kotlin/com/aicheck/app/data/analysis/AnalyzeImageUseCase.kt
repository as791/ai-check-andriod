package com.aicheck.app.data.analysis

import com.aicheck.app.data.storage.HistoryRepository
import com.aicheck.domain.evidence.EvidenceEngine
import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.provider.DetectionProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Orchestrates the evidence-aggregation pipeline described in docs/ARCHITECTURE.md:
 * providers run grouped by [AnalysisStage] (each group concurrently within itself),
 * their signals feed [EvidenceEngine], and the result is persisted. [onStage] is
 * invoked as each group actually begins — real progress, not a simulated animation.
 *
 * A provider throwing is never allowed to abort the whole analysis: it is caught and
 * turned into an [DetectionSignal.error] signal for that provider only.
 */
class AnalyzeImageUseCase(
    private val provenanceProviders: List<DetectionProvider>,
    private val metadataProviders: List<DetectionProvider>,
    private val visualProviders: List<DetectionProvider>,
    private val evidenceEngine: EvidenceEngine,
    private val historyRepository: HistoryRepository,
) {
    suspend fun run(
        input: AnalysisInput,
        thumbnailSourceFile: File,
        onStage: suspend (AnalysisStage) -> Unit,
    ): Pair<Long, AnalysisResult> {
        onStage(AnalysisStage.PROVENANCE)
        val provenanceSignals = runGroup(provenanceProviders, input)

        onStage(AnalysisStage.METADATA)
        val metadataSignals = runGroup(metadataProviders, input)

        onStage(AnalysisStage.VISUAL)
        val visualSignals = runGroup(visualProviders, input)

        val result = evidenceEngine.aggregate(provenanceSignals + metadataSignals + visualSignals)
        val analysisId = historyRepository.save(result, thumbnailSourceFile)
        return analysisId to result
    }

    private suspend fun runGroup(
        providers: List<DetectionProvider>,
        input: AnalysisInput,
    ): List<DetectionSignal> = coroutineScope {
        providers.map { provider ->
            async {
                runCatching { provider.analyze(input) }.getOrElse {
                    DetectionSignal.error(provider.signalType, "This detector failed unexpectedly.")
                }
            }
        }.awaitAll()
    }
}
