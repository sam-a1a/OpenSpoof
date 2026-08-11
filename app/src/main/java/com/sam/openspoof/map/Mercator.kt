package com.sam.openspoof.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Edge length of an OpenStreetMap raster tile, in pixels. */
const val TILE_SIZE = 256

/**
 * The latitude where the Web Mercator projection is truncated to keep the world square.
 * Beyond this the projection runs off to infinity.
 */
const val MAX_LATITUDE = 85.05112877980659

/** A WGS84 coordinate. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Web Mercator, expressed in normalised world coordinates: the whole world is the unit square,
 * with (0,0) at the north-west corner. This keeps the projection independent of zoom, so the
 * camera can hold a fractional zoom and the renderer multiplies by the world size on demand.
 */
fun lonToWorldX(lon: Double): Double = (lon + 180.0) / 360.0

fun latToWorldY(lat: Double): Double {
    val rad = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

fun worldXToLon(x: Double): Double = x * 360.0 - 180.0

fun worldYToLat(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

/** World size in pixels at [zoom]; fractional zoom is supported. */
fun worldPx(zoom: Float): Double = TILE_SIZE.toDouble() * Math.pow(2.0, zoom.toDouble())

/** Longitudes wrap, so normalise into [0,1) before converting to a tile column. */
fun wrapWorldX(x: Double): Double = x - Math.floor(x)

/**
 * Formats a coordinate the way the rest of the UI shows it. Six decimals is
 * roughly 0.1 m, which is well past what any consumer GPS reports.
 */
fun formatLatLon(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return "%.6f°%s  %.6f°%s".format(abs(lat), ns, abs(lon), ew)
}
