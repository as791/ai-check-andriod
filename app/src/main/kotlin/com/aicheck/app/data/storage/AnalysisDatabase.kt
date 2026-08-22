package com.aicheck.app.data.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AnalysisEntity::class], version = 1, exportSchema = true)
abstract class AnalysisDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao

    companion object {
        @Volatile private var instance: AnalysisDatabase? = null

        fun getInstance(context: Context): AnalysisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnalysisDatabase::class.java,
                    "ai-check.db",
                ).build().also { instance = it }
            }
    }
}
