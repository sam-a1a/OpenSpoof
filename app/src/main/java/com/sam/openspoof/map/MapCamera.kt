package com.sam.openspoof.map

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln

const val MIN_ZOOM = 2f

/** OpenStreetMap's raster tiles stop at z19; asking for more just yields 404s. */
const val MAX_ZOOM = 19f

/**
 * Camera over the Web Mercator plane.
 *
 * The centre is kept in normalised world coordinates as [Double]. That precision is not
 * optional: at z19 the world is 256 * 2^19 = ~134 million pixels across, and a Float carries
 * only ~7 significant digits, which would quantise the centre to roughly 13-pixel steps and
 * make panning visibly stair-step. Animation *deltas* stay in Float screen pixels, where
 * Float precision is ample, and are integrated into the Double centre.
 */
@Stable
class MapCameraState(lat: Double, lon: Double, zoom: Float) {

    var centerX by mutableDoubleStateOf(lonToWorldX(lon))
        private set
    var centerY by mutableDoubleStateOf(latToWorldY(lat))
        private set
    var zoom by mutableFloatStateOf(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        private set

    /**
     * True from the moment a finger lands until any resulting momentum has died out. The centre
     * pin reads this to lift off the map while it is moving, so "settled" is a state the user
     * can see: the pin only touches down once the coordinate under it has stopped changing.
     */
    var isInteracting by mutableStateOf(false)
        internal set

    /** True while [animateTo] is flying the camera to a new place. */
    var isFlying by mutableStateOf(false)
        private set

    val latitude: Double get() = worldYToLat(centerY)
    val longitude: Double get() = worldXToLon(wrapWorldX(centerX))

    /** Moves the camera by a screen-space drag, in pixels. */
    fun panBy(dxPx: Float, dyPx: Float) {
        val scale = worldPx(zoom)
        centerX = wrapWorldX(centerX - dxPx / scale)
        // Latitude does not wrap, so clamp instead. The bound is the Mercator cutoff
        // expressed in world coordinates.
        val limit = latToWorldY(MAX_LATITUDE)
        centerY = (centerY - dyPx / scale).coerceIn(limit, 1.0 - limit)
    }

    /**
     * Applies a zoom multiplier while holding the geographic point under [focus] still, which is
     * what makes a pinch feel anchored to the fingers rather than to the screen centre.
     */
    fun zoomBy(factor: Float, focus: Offset, viewportCenter: Offset) {
        val target = (zoom + log2(factor)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomTo(target, focus, viewportCenter)
    }

    fun zoomTo(target: Float, focus: Offset, viewportCenter: Offset) {
        val clamped = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val offsetX = (focus.x - viewportCenter.x).toDouble()
        val offsetY = (focus.y - viewportCenter.y).toDouble()

        val before = worldPx(zoom)
        val anchorX = centerX + offsetX / before
        val anchorY = centerY + offsetY / before

        zoom = clamped

        val after = worldPx(clamped)
        centerX = wrapWorldX(anchorX - offsetX / after)
        val limit = latToWorldY(MAX_LATITUDE)
        centerY = (anchorY - offsetY / after).coerceIn(limit, 1.0 - limit)
    }

    /**
     * Springs the zoom to [target] while keeping [focus] pinned. Re-anchoring on every frame is
     * what lets a double-tap zoom feel like it grows out of the tapped point, and it means an
     * overshooting spring stays anchored through the overshoot.
     */
    suspend fun animateZoomTo(
        target: Float,
        focus: Offset,
        viewportCenter: Offset,
        spec: AnimationSpec<Float>,
    ) {
        val from = zoom
        val to = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (from == to) return
        AnimationState(initialValue = from).animateTo(to, spec) {
            zoomTo(value, focus, viewportCenter)
        }
    }

    /**
     * Continues a drag under its own momentum. Each axis integrates a decay curve and feeds the
     * frame-to-frame delta back through [panBy], so the fling obeys the same clamping as a drag.
     */
    suspend fun fling(velocity: Offset, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch {
            var previous = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity.x)
                .animateDecay(decay) {
                    panBy(value - previous, 0f)
                    previous = value
                }
        }
        launch {
            var previous = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity.y)
                .animateDecay(decay) {
                    panBy(0f, value - previous)
                    previous = value
                }
        }
    }

    /**
     * Flies to a target, driving a single 0..1 progress value with [spec] and interpolating the
     * centre in Double. Running one spring rather than three keeps the pan and the zoom
     * perfectly in step, so a spring that overshoots does so coherently.
     */
    suspend fun animateTo(
        lat: Double,
        lon: Double,
        targetZoom: Float,
        spec: AnimationSpec<Float>,
    ) {
        val fromX = centerX
        val fromY = centerY
        val fromZoom = zoom

        var toX = lonToWorldX(lon)
        val toY = latToWorldY(lat)
        val toZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

        // Cross the antimeridian if that is the short way round.
        if (toX - fromX > 0.5) toX -= 1.0
        if (fromX - toX > 0.5) toX += 1.0

        // Long journeys arc out and back in rather than crossing at the destination zoom.
        // Flying Paris to Tokyo at street level would drag the viewport over thousands of
        // distinct tiles, which is slow and is exactly the wide-area fetching the OSM tile
        // policy warns against. Pulling back to a zoom where the whole trip roughly fits
        // means the flight crosses a handful of tiles instead, and it reads better too.
        val span = maxOf(abs(toX - fromX), abs(toY - fromY))
        val fitZoom = if (span > 1e-9) {
            (ln(1.0 / span) / ln(2.0)).toFloat()
        } else {
            MAX_ZOOM
        }
        val dip = (minOf(fromZoom, toZoom) - fitZoom).coerceAtLeast(0f)

        isFlying = true
        try {
            AnimationState(initialValue = 0f).animateTo(1f, spec) {
                val t = value.toDouble()
                centerX = wrapWorldX(fromX + (toX - fromX) * t)
                centerY = fromY + (toY - fromY) * t

                // Parabolic dip, deepest at the midpoint and zero at both ends. Clamped so a
                // spring overshooting past 1 does not invert it into a zoom-in spike.
                val arc = value.coerceIn(0f, 1f)
                zoom = fromZoom + (toZoom - fromZoom) * value - dip * 4f * arc * (1f - arc)
            }
        } finally {
            // Unlike the fling flag, this must clear on cancellation too, or a interrupted
            // flight would leave the renderer permanently refusing to fetch detail tiles.
            isFlying = false
        }
    }

    companion object {
        val Saver: Saver<MapCameraState, List<Any>> = Saver(
            save = { listOf(it.latitude, it.longitude, it.zoom) },
            restore = {
                MapCameraState(it[0] as Double, it[1] as Double, it[2] as Float)
            },
        )
    }
}

private fun log2(value: Float): Float =
    (Math.log(value.toDouble()) / Math.log(2.0)).toFloat()
