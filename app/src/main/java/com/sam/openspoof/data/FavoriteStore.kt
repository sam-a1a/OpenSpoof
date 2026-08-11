package com.sam.openspoof.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A place the user has saved. */
data class Favorite(
    val name: String,
    val lat: Double,
    val lon: Double,
    val savedAt: Long,
)

/**
 * How close two coordinates must be to count as the same saved place, in degrees.
 * Roughly a metre, which is finer than the pin can be placed at any usable zoom.
 */
private const val MATCH_TOLERANCE = 1e-5

private const val PREFS = "favorites"
private const val KEY = "items"

/**
 * Saved locations, persisted with SharedPreferences and a JSON blob.
 *
 * DataStore or Room would be the reflexive choices, and both are wrong here: this is a short
 * list of four-field records written only when the user taps save, and either would add a
 * dependency measured in hundreds of kilobytes to an app whose whole APK is about two megabytes.
 * SharedPreferences and org.json are already in the framework.
 *
 * The list is a snapshot state list, so the UI recomposes on change without a separate flow.
 */
@Stable
class FavoriteStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = mutableStateListOf<Favorite>()

    /** Newest first, which is the order the sheet shows them in. */
    val items: List<Favorite> get() = _items

    init {
        _items.addAll(read())
    }

    fun add(name: String, lat: Double, lon: Double) {
        val label = name.trim().ifEmpty { return }
        // Re-saving the same spot replaces it rather than stacking duplicates.
        _items.removeAll { it.matches(lat, lon) }
        _items.add(0, Favorite(label, lat, lon, System.currentTimeMillis()))
        write()
    }

    fun remove(favorite: Favorite) {
        _items.remove(favorite)
        write()
    }

    /** The saved place at these coordinates, if there is one. */
    fun at(lat: Double, lon: Double): Favorite? = _items.firstOrNull { it.matches(lat, lon) }

    private fun Favorite.matches(lat: Double, lon: Double) =
        abs(this.lat - lat) < MATCH_TOLERANCE && abs(this.lon - lon) < MATCH_TOLERANCE

    private fun read(): List<Favorite> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    Favorite(
                        name = item.optString("name").ifEmpty { continue },
                        lat = item.getDouble("lat"),
                        lon = item.getDouble("lon"),
                        savedAt = item.optLong("savedAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val array = JSONArray()
        for (favorite in _items) {
            array.put(
                JSONObject()
                    .put("name", favorite.name)
                    .put("lat", favorite.lat)
                    .put("lon", favorite.lon)
                    .put("savedAt", favorite.savedAt),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
