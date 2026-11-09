package com.example.dougasort

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-video previews for the grid, built to stay cheap with thousands of videos:
 *  - [thumb]: the system's own cached thumbnail, shown immediately (no decoding of ours);
 *  - [get]: a handful of evenly spaced frames (RGB_565 to halve memory), cached in memory and on
 *    disk. Disk use is capped at [DISK_CAP] and least recently used files go first;
 *  - prefetch requests run on their own single-permit gate so they never delay visible cells.
 */
class FrameCache(private val context: Context) {
    private val mem = object : LruCache<Long, List<Bitmap>>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: List<Bitmap>) = value.sumOf { it.byteCount }
    }
    private val thumbs = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val visibleGate = Semaphore(3)
    private val prefetchGate = Semaphore(1)
    private val thumbGate = Semaphore(4)
    private val dir = File(context.cacheDir, "frames").apply { mkdirs() }
    private val writes = AtomicInteger()

    suspend fun thumb(item: VideoItem): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val bmp = thumbGate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(item.uri, Size(MAX_W, MAX_W * 16 / 9), null) }
                    .getOrNull()
            }
        }
        if (bmp != null) thumbs.put(item.id, bmp)
        return bmp
    }

    /** [prefetch] = true for look-ahead work, which yields to cells that are on screen. */
    suspend fun get(item: VideoItem, prefetch: Boolean = false): List<Bitmap> {
        mem.get(item.id)?.let { return it }
        val gate = if (prefetch) prefetchGate else visibleGate
        val frames = gate.withPermit { withContext(Dispatchers.IO) { load(item) } }
        if (frames.isNotEmpty()) mem.put(item.id, frames)
        return frames
    }

    fun frameCount(item: VideoItem) = if (item.durationMs < 15_000) 5 else 10

    /** Drops cached files of videos that no longer exist. */
    fun prune(validIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('_').toLongOrNull()
            if (id == null || id !in validIds) f.delete()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.load(item: VideoItem): List<Bitmap> {
        val n = frameCount(item)
        fun file(i: Int) = File(dir, "${item.id}_${item.size}_${n}_$i.jpg")

        if ((0 until n).all { file(it).exists() }) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val cached = (0 until n).mapNotNull { BitmapFactory.decodeFile(file(it).path, opts) }
            if (cached.size == n) {
                val now = System.currentTimeMillis()
                (0 until n).forEach { file(it).setLastModified(now) } // keeps it fresh for LRU trimming
                return cached
            }
        }

        val out = ArrayList<Bitmap>(n)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, item.uri)
            for (i in 0 until n) {
                ensureActive()
                val timeUs = item.durationMs * (2L * i + 1) / (2L * n) * 1000
                val raw = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val w = minOf(raw.width, MAX_W)
                val scaled = Bitmap.createScaledBitmap(raw, w, raw.height * w / raw.width, true)
                val bmp = scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
                if (scaled !== bmp) scaled.recycle()
                if (raw !== scaled && raw !== bmp) raw.recycle()
                runCatching {
                    file(i).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 65, it) }
                }
                out += bmp
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            r.release()
        }
        if (writes.incrementAndGet() % 100 == 0) trimDisk()
        return out
    }

    /** Keeps the on-disk cache under [DISK_CAP]: oldest files go first, down to 80% of the cap. */
    private fun trimDisk() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val MAX_W = 240
        const val DISK_CAP = 500L * 1024 * 1024
    }
}
