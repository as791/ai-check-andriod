package com.aicheck.app.data.storage

import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.Classification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A lightweight row for list screens — avoids deserializing the full signal list. */
data class HistoryEntry(
    val id: Long,
    val timestampMillis: Long,
    val thumbnailPath: String?,
    val aiLikelihood: Float,
    val classification: Classification,
)

/** Full detail for the Result screen: the frozen result plus its saved thumbnail. */
data class SavedAnalysis(
    val id: Long,
    val timestampMillis: Long,
    val thumbnailPath: String?,
    val result: AnalysisResult,
)

class HistoryRepository(
    private val dao: AnalysisDao,
    private val thumbnailStore: ThumbnailStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
        dao.observeRecent(limit).map { entities -> entities.map(::toHistoryEntry) }

    fun observeAll(): Flow<List<HistoryEntry>> =
        dao.observeAll().map { entities -> entities.map(::toHistoryEntry) }

    fun observeResult(id: Long): Flow<AnalysisResult?> =
        dao.observeById(id).map { entity -> entity?.let(::toAnalysisResult) }

    fun observeSavedAnalysis(id: Long): Flow<SavedAnalysis?> =
        dao.observeById(id).map { entity ->
            entity?.let {
                SavedAnalysis(
                    id = it.id,
                    timestampMillis = it.timestampMillis,
                    thumbnailPath = it.thumbnailPath,
                    result = toAnalysisResult(it),
                )
            }
        }

    suspend fun save(result: AnalysisResult, normalizedPreviewFile: File): Long {
        val thumbnailPath = thumbnailStore.save(normalizedPreviewFile)
        val entity = AnalysisEntity(
            timestampMillis = System.currentTimeMillis(),
            thumbnailPath = thumbnailPath,
            aiLikelihood = result.aiLikelihood,
            classification = result.classification.name,
            signalsJson = json.encodeToString(result.signals.map { it.toDto() }),
            limitationsJson = json.encodeToString(result.limitations),
        )
        return dao.insert(entity)
    }

    suspend fun delete(id: Long) {
        dao.getById(id)?.thumbnailPath?.let { thumbnailStore.delete(it) }
        dao.deleteById(id)
    }

    suspend fun clearAll() {
        thumbnailStore.deleteAll()
        dao.clearAll()
    }

    private fun toHistoryEntry(entity: AnalysisEntity): HistoryEntry = HistoryEntry(
        id = entity.id,
        timestampMillis = entity.timestampMillis,
        thumbnailPath = entity.thumbnailPath,
        aiLikelihood = entity.aiLikelihood,
        classification = Classification.valueOf(entity.classification),
    )

    private fun toAnalysisResult(entity: AnalysisEntity): AnalysisResult {
        val signals = runCatching {
            json.decodeFromString<List<SignalDto>>(entity.signalsJson).map { it.toDomain() }
        }.getOrDefault(emptyList())
        val limitations = runCatching {
            json.decodeFromString<List<String>>(entity.limitationsJson)
        }.getOrDefault(emptyList())

        return AnalysisResult(
            aiLikelihood = entity.aiLikelihood,
            classification = Classification.valueOf(entity.classification),
            signals = signals,
            limitations = limitations,
        )
    }
}
