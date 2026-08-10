package com.sam.openspoof.geo

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A geocoding hit. [label] is the full address, [name] the short leading part of it. */
data class Place(val name: String, val label: String, val lat: Double, val lon: Double)

private const val USER_AGENT =
    "OpenSpoof/1.0 (Android; +https://github.com/sam-a1a/OpenSpoof)"

private const val ENDPOINT = "https://nominatim.openstreetmap.org/search"

/** The usage policy caps this service at one request per second, absolutely. */
private const val MIN_INTERVAL_MS = 1100L

private const val MAX_RESULTS = 6

/**
 * Place search against OpenStreetMap's Nominatim.
 *
 * The usage policy constrains the design more than the API does:
 *
 * - Autocomplete is expressly forbidden, so this must never be wired to a text watcher. The
 *   caller searches on submit only.
 * - Hard ceiling of one request per second, enforced here rather than trusted to the UI.
 * - Repeated identical queries are grounds for being blocked, hence the result cache.
 *
 * Sources: https://operations.osmfoundation.org/policies/nominatim/
 */
object Nominatim {

    private val gate = Mutex()
    private var lastRequestAt = 0L
    private val cache = LruCache<String, List<Place>>(32)

    /**
     * Looks up [query]. Returns an empty list when nothing matches, and throws [IOException]
     * when the network is unreachable, so the UI can tell "no results" from "offline".
     */
    suspend fun search(query: String): List<Place> {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        cache.get(key)?.let { return it }

        val results = gate.withLock {
            // Serialised behind the same lock as the request itself, so concurrent callers
            // queue rather than all seeing a stale timestamp and firing at once.
            val since = System.currentTimeMillis() - lastRequestAt
            if (since < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - since)
            lastRequestAt = System.currentTimeMillis()
            fetch(key)
        }

        cache.put(key, results)
        return results
    }

    private suspend fun fetch(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ENDPOINT)
            append("?format=jsonv2&limit=").append(MAX_RESULTS)
            append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()
            parse(conn.inputStream.use { it.readBytes() }.decodeToString())
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Place> = runCatching {
        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val lat = item.optString("lat").toDoubleOrNull() ?: continue
                val lon = item.optString("lon").toDoubleOrNull() ?: continue
                val label = item.optString("display_name").ifEmpty { continue }
                val name = item.optString("name").ifEmpty { label.substringBefore(',') }
                add(Place(name = name, label = label, lat = lat, lon = lon))
            }
        }
    }.getOrDefault(emptyList())
}
