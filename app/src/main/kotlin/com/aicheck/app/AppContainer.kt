package com.aicheck.app

import android.content.Context
import com.aicheck.app.data.analysis.AnalyzeImageUseCase
import com.aicheck.app.data.analysis.AnalyzeVideoUseCase
import com.aicheck.app.data.detection.classifier.AIImageClassifierProvider
import com.aicheck.app.data.detection.metadata.ExifMetadataProvider
import com.aicheck.app.data.detection.metadata.KnownGeneratorMetadataProvider
import com.aicheck.app.data.detection.provenance.C2PAProvider
import com.aicheck.app.data.detection.watermark.WatermarkProvider
import com.aicheck.app.data.image.ImageLoader
import com.aicheck.app.data.sharing.ResultCardRenderer
import com.aicheck.app.data.storage.AnalysisDatabase
import com.aicheck.app.data.storage.HistoryRepository
import com.aicheck.app.data.storage.ThumbnailStore
import com.aicheck.app.data.video.VideoFrameSampler
import com.aicheck.domain.evidence.EvidenceEngine

/**
 * Hand-written composition root. No DI framework: the object graph here is small and
 * static for the lifetime of the process, and one engineer can read this file top to
 * bottom faster than tracing generated Hilt/Koin wiring — see docs/ARCHITECTURE.md
 * "Why no DI framework".
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy { AnalysisDatabase.getInstance(appContext) }
    private val thumbnailStore by lazy { ThumbnailStore(appContext) }

    val imageLoader by lazy { ImageLoader(appContext) }
    private val videoFrameSampler by lazy { VideoFrameSampler(appContext) }
    val historyRepository by lazy { HistoryRepository(database.analysisDao(), thumbnailStore) }
    val resultCardRenderer by lazy { ResultCardRenderer(appContext) }

    private val c2paProvider by lazy { C2PAProvider() }
    private val exifMetadataProvider by lazy { ExifMetadataProvider() }
    private val generatorMetadataProvider by lazy { KnownGeneratorMetadataProvider() }
    private val classifierProvider by lazy { AIImageClassifierProvider(appContext) }
    private val watermarkProvider by lazy { WatermarkProvider() }

    private val evidenceEngine by lazy { EvidenceEngine() }

    val analyzeImageUseCase by lazy {
        AnalyzeImageUseCase(
            provenanceProviders = listOf(c2paProvider),
            metadataProviders = listOf(exifMetadataProvider, generatorMetadataProvider),
            visualProviders = listOf(classifierProvider, watermarkProvider),
            evidenceEngine = evidenceEngine,
            historyRepository = historyRepository,
        )
    }

    val analyzeVideoUseCase by lazy {
        AnalyzeVideoUseCase(
            frameSampler = videoFrameSampler,
            classifierProvider = classifierProvider,
            watermarkProvider = watermarkProvider,
            evidenceEngine = evidenceEngine,
            historyRepository = historyRepository,
        )
    }
}
