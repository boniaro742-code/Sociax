package com.sociax.app.data

import android.content.Context
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Step 44 (plan section 9, "Data / accessibility"): what Sociax keeps ON THE PHONE only as a temporary copy, and a way to remove it.
 * Only two things are ever touched: Coil's picture cache (photos are downloaded again when needed) and leftover temporary video
 * files this app made itself in its cache folder (names below) that are more than one hour old, so an upload in progress is never
 * disturbed. Nothing on the server, no account data, no saved posts, no settings, no App Lock and no sign-in is touched.
 */
object CacheTools {
    private const val MIN_AGE_MS = 60 * 60 * 1000L
    private val TEMP_PREFIXES = listOf("probe_", "short_src_", "short_out_")

    private fun oldTempFiles(context: Context): List<File> {
        val now = System.currentTimeMillis()
        val dir = context.cacheDir
        val own = dir.listFiles()?.filter { f -> f.isFile && TEMP_PREFIXES.any { f.name.startsWith(it) } } ?: emptyList()
        val camera = File(dir, "camera").listFiles()?.filter { it.isFile } ?: emptyList()
        return (own + camera).filter { now - it.lastModified() >= MIN_AGE_MS }
    }

    /** Bytes that [clear] would free right now. */
    suspend fun clearableBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val images = context.imageLoader.diskCache?.size ?: 0L
        images + oldTempFiles(context).sumOf { it.length() }
    }

    /** Removes the temporary copies and returns how many bytes were counted before removing. */
    suspend fun clear(context: Context): Long = withContext(Dispatchers.IO) {
        val before = clearableBytes(context)
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        oldTempFiles(context).forEach { runCatching { it.delete() } }
        before
    }

    /** "1.4 MB" / "320 KB" / "0 KB" (1 MB = 1024 x 1024 bytes, like the server). */
    fun format(bytes: Long): String =
        if (bytes >= 1024L * 1024L) String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else String.format(java.util.Locale.US, "%d KB", bytes / 1024L)
}
