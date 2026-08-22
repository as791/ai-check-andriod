package com.aicheck.app.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.Classification
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {

    private lateinit var database: AnalysisDatabase
    private lateinit var repository: HistoryRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AnalysisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HistoryRepository(database.analysisDao(), ThumbnailStore(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sampleResult(): AnalysisResult = AnalysisResult(
        aiLikelihood = 0.87f,
        classification = Classification.HIGH,
        signals = listOf(
            DetectionSignal(
                type = SignalType.AI_CLASSIFIER,
                availability = SignalAvailability.AVAILABLE,
                score = 0.91f,
                confidence = 0.75f,
                description = "91% AI probability",
                evidence = "Dafilab/ai-image-detector",
            ),
            DetectionSignal.unavailable(SignalType.CONTENT_CREDENTIALS, "Not enabled in this build"),
        ),
        limitations = listOf("AI detection is probabilistic.", "Content Credentials unavailable."),
    )

    @Test
    fun `save then observeSavedAnalysis round-trips the full result`() = runTest {
        val previewFile = File.createTempFile("preview", ".jpg").apply {
            writeBytes(ByteArray(16) { 1 }) // content irrelevant; ThumbnailStore handles decode failure gracefully
        }

        val id = repository.save(sampleResult(), previewFile)
        val saved = repository.observeSavedAnalysis(id).first()

        assertThat(saved).isNotNull()
        assertThat(saved!!.result.aiLikelihood).isWithin(0.001f).of(0.87f)
        assertThat(saved.result.classification).isEqualTo(Classification.HIGH)
        assertThat(saved.result.signals).hasSize(2)
        assertThat(saved.result.signals[0].type).isEqualTo(SignalType.AI_CLASSIFIER)
        assertThat(saved.result.signals[0].score).isWithin(0.001f).of(0.91f)
        assertThat(saved.result.signals[1].availability).isEqualTo(SignalAvailability.UNAVAILABLE)
        assertThat(saved.result.limitations).containsExactly(
            "AI detection is probabilistic.",
            "Content Credentials unavailable.",
        ).inOrder()
    }

    @Test
    fun `delete removes the entry from history`() = runTest {
        val previewFile = File.createTempFile("preview", ".jpg").apply { writeBytes(ByteArray(16) { 1 }) }
        val id = repository.save(sampleResult(), previewFile)

        repository.delete(id)

        assertThat(repository.observeAll().first()).isEmpty()
    }

    @Test
    fun `clearAll empties history`() = runTest {
        val previewFile = File.createTempFile("preview", ".jpg").apply { writeBytes(ByteArray(16) { 1 }) }
        repository.save(sampleResult(), previewFile)
        repository.save(sampleResult(), previewFile)

        repository.clearAll()

        assertThat(repository.observeAll().first()).isEmpty()
    }
}
