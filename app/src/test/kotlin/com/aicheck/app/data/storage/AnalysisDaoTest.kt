package com.aicheck.app.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalysisDaoTest {

    private lateinit var database: AnalysisDatabase
    private lateinit var dao: AnalysisDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnalysisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.analysisDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(timestamp: Long, classification: String = "HIGH") = AnalysisEntity(
        timestampMillis = timestamp,
        thumbnailPath = null,
        aiLikelihood = 0.9f,
        classification = classification,
        signalsJson = "[]",
        limitationsJson = "[]",
    )

    @Test
    fun `inserted analyses are returned newest first`() = runTest {
        dao.insert(entity(timestamp = 1_000))
        dao.insert(entity(timestamp = 3_000))
        dao.insert(entity(timestamp = 2_000))

        val all = dao.observeAll().first()

        assertThat(all.map { it.timestampMillis }).containsExactly(3_000L, 2_000L, 1_000L).inOrder()
    }

    @Test
    fun `observeRecent limits results`() = runTest {
        repeat(5) { dao.insert(entity(timestamp = it.toLong())) }

        val recent = dao.observeRecent(2).first()

        assertThat(recent).hasSize(2)
    }

    @Test
    fun `deleteById removes only that row`() = runTest {
        val keepId = dao.insert(entity(timestamp = 1))
        val removeId = dao.insert(entity(timestamp = 2))

        dao.deleteById(removeId)

        val remaining = dao.observeAll().first()
        assertThat(remaining.map { it.id }).containsExactly(keepId)
    }

    @Test
    fun `clearAll empties the table`() = runTest {
        dao.insert(entity(timestamp = 1))
        dao.insert(entity(timestamp = 2))

        dao.clearAll()

        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test
    fun `getById returns null for a missing row`() = runTest {
        assertThat(dao.getById(999)).isNull()
    }
}
