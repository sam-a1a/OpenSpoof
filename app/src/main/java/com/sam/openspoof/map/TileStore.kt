package com.sam.openspoof.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Packs z/x/y into one Long so the caches can key on a primitive. */
fun tileKey(z: Int, x: Int, y: Int): Long =
    (z.toLong() shl 44) or (x.toLong() shl 22) or y.toLong()

/**
 * The OpenStreetMap tile usage policy requires a User-Agent that identifies the specific
 * application and offers a contact; generic library defaults such as "okhttp" get blocked.
 */
private const val USER_AGENT =
    "OpenSpoof/1.0 (Android; +https://github.com/sam-a1a/OpenSpoof)"

private const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"

/**
 * The policy asks that tiles be cached per their HTTP headers, or for at least 7 days where
 * headers cannot be read. This is the floor applied to whatever max-age comes back.
 */
private const val MIN_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000

private const val MAX_DISK_BYTES = 48L * 1024 * 1024
private const val MAX_PARALLEL_FETCHES = 4

/**
 * Loads OSM raster tiles, backed by an in-memory LRU and a disk cache.
 *
 * Tiles are only ever fetched when the renderer actually asks for one that is on screen. The
 * usage policy forbids pre-emptively fetching tiles the user is not looking at, so there is
 * deliberately no prefetch-around-the-viewport or seed-an-area path here.
 */
class TileStore(context: Context, private val scope: CoroutineScope) {

    private val dir = File(context.cacheDir, "tiles")
    private val fetchLimit = Semaphore(MAX_PARALLEL_FETCHES)
    private val inFlight = HashSet<Long>()

    /**
     * Bumped whenever a tile becomes available. The map Canvas reads this so a newly decoded
     * tile triggers exactly one recomposition instead of the map polling for arrivals.
     */
    var generation by mutableIntStateOf(0)
        private set

    private val memory = object : LruCache<Long, ImageBitmap>(memoryBudgetBytes()) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }

    init {
        scope.launch(Dispatchers.IO) { trimDiskCache() }
    }

    /** Synchronous, memory-only lookup. Safe to call from a draw pass. */
    fun peek(key: Long): ImageBitmap? = memory.get(key)

    /**
     * Ensures the tile is being loaded. Returns immediately; the caller learns about the result
     * by observing [generation]. Repeated calls for an in-flight tile are cheap no-ops.
     */
    fun request(z: Int, x: Int, y: Int) {
        val key = tileKey(z, x, y)
        if (memory.get(key) != null) return
        synchronized(inFlight) { if (!inFlight.add(key)) return }

        scope.launch(Dispatchers.IO) {
            val bitmap = readFromDisk(z, x, y) ?: fetchLimit.withPermit { download(z, x, y) }
            synchronized(inFlight) { inFlight.remove(key) }
            if (bitmap != null) {
                memory.put(key, bitmap)
                // Touching Compose state must happen off the IO thread.
                scope.launch { generation++ }
            }
        }
    }

    private fun tileFile(z: Int, x: Int, y: Int) = File(dir, "$z/${x}_$y.png")

    /**
     * Expiry is encoded in the file's last-modified stamp rather than a sidecar file, so a
     * freshness check is a single stat and the cache stays a plain directory of PNGs.
     */
    private fun readFromDisk(z: Int, x: Int, y: Int): ImageBitmap? {
        val file = tileFile(z, x, y)
        if (!file.exists()) return null
        if (file.lastModified() < System.currentTimeMillis()) {
            file.delete()
            return null
        }
        return runCatching {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }.getOrNull()
    }

    private fun download(z: Int, x: Int, y: Int): ImageBitmap? = runCatching {
        val conn = (URL(TILE_URL.format(z, x, y)).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            writeToDisk(z, x, y, bytes, conn.getHeaderField("Cache-Control"))
            bitmap.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun writeToDisk(z: Int, x: Int, y: Int, bytes: ByteArray, cacheControl: String?) {
        runCatching {
            val file = tileFile(z, x, y)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.setLastModified(System.currentTimeMillis() + cacheLifetime(cacheControl))
        }
    }

    /** Honours the server's max-age when present, never dropping below the 7-day floor. */
    private fun cacheLifetime(cacheControl: String?): Long {
        val maxAge = cacheControl
            ?.substringAfter("max-age=", "")
            ?.takeWhile { it.isDigit() }
            ?.toLongOrNull()
            ?.times(1000)
            ?: 0L
        return maxOf(maxAge, MIN_CACHE_MILLIS)
    }

    /** Drops expired tiles, then the oldest survivors, until the cache is back under budget. */
    private fun trimDiskCache() {
        val files = dir.walkTopDown().filter { it.isFile }.toMutableList()
        val now = System.currentTimeMillis()
        files.removeAll { file ->
            (file.lastModified() < now).also { expired -> if (expired) file.delete() }
        }
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= MAX_DISK_BYTES * 8 / 10) break
            total -= file.length()
            file.delete()
        }
    }
}

/** A quarter of the heap, bounded so a large-heap device does not hoard tiles. */
private fun memoryBudgetBytes(): Int {
    val quarterHeap = Runtime.getRuntime().maxMemory() / 4
    return quarterHeap.coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
}
