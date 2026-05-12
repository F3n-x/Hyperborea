package com.nettarion.hyperborea.platform

import android.content.Context
import android.os.Environment
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.ui.admin.ExportResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FitExporter(
    private val context: Context,
    private val logger: AppLogger,
) {

    fun exportToFile(fitBytes: ByteArray, startedAtMs: Long): ExportResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
        val filename = "hyperborea_$timestamp.fit"

        // App-specific external dir: no storage permission needed on any API level and unaffected
        // by scoped storage. Retrieve with: adb pull /sdcard/Android/data/<pkg>/files/Download/<file>
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return try {
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeBytes(fitBytes)
            logger.i(TAG, "FIT exported to ${file.absolutePath}")
            ExportResult(file.absolutePath)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write FIT file", e)
            ExportResult(null, error = "Failed to save: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "FitExporter"
    }
}
