package com.genned.app.data.storage

import java.io.File

/**
 * Deletes stale files from the shared cache: working copies left behind by failed
 * or cancelled checks, and result cards rendered for past shares.
 */
object SharedCacheSweeper {

    fun sweep(dir: File, nowMillis: Long, maxAgeMillis: Long): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            file.isFile && nowMillis - file.lastModified() > maxAgeMillis && file.delete()
        }
    }
}
