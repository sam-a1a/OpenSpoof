package com.sam.openspoof.map

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** How long a freshly decoded tile takes to fade in. */
private const val TILE_FADE_MS = 180L

/** How many zoom levels to walk up looking for a stand-in while a tile loads. */
private const val MAX_PARENT_LOOKUP = 5

/**
 * An OpenStreetMap raster map drawn straight onto a Compose [Canvas].
 *
 * This exists instead of osmdroid for two reasons: osmdroid is an archived, View-based library
 * that would need AndroidView interop and cannot participate in Compose animation, and it would
 * add roughly 1.5 MB to the APK. Drawing tiles is a few hundred lines, so the app does it here
 * and gets frame-accurate control over the motion in exchange.
 */
@Composable
fun OsmMap(
    camera: MapCameraState,
    store: TileStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val zoomSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val background = MaterialTheme.colorScheme.surfaceContainerLow

    // Tracks the frame clock only while tiles are fading, so an idle map does no work.
    var frameMillis by remember { mutableLongStateOf(0L) }
    val firstDrawn = remember { HashMap<Long, Long>() }

    LaunchedEffect(store.generation) {
        val deadline = withFrameMillis { it } + TILE_FADE_MS
        while (withFrameMillis { frameMillis = it; it } < deadline) {
            // Body intentionally empty; the condition drives the frames.
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        scope.launch {
                            camera.animateZoomTo(
                                target = floor(camera.zoom) + 1f,
                                focus = position,
                                viewportCenter = center(),
                                spec = zoomSpec,
                            )
                        }
                    },
                )
            }
            .pointerInput(Unit) { mapGestures(camera, decay, scope) },
    ) {
        drawRect(background)
        // Reading these two makes the draw pass observe them, so a tile arrival or a
        // fade frame invalidates the canvas.
        store.generation
        val now = frameMillis
        drawTiles(camera, store, firstDrawn, now)
    }
}

private fun DrawScope.center() = Offset(size.width / 2f, size.height / 2f)

private fun PointerInputScope.center() = Offset(size.width / 2f, size.height / 2f)

/**
 * One gesture loop handling drag, pinch and fling together.
 *
 * detectTransformGestures would cover pan and zoom, but it does not surface pointer velocity,
 * and without velocity a map cannot fling. Running the loop directly gives access to both.
 */
private suspend fun PointerInputScope.mapGestures(
    camera: MapCameraState,
    decay: DecayAnimationSpec<Float>,
    scope: CoroutineScope,
) {
    var flingJob: Job? = null
    awaitEachGesture {
        // A new touch takes over from any momentum still running.
        flingJob?.cancel()
        val tracker = VelocityTracker()
        awaitFirstDown(requireUnconsumed = false)

        var pressed: Boolean
        do {
            val event = awaitPointerEvent()
            val active = event.changes.count { it.pressed }
            pressed = active > 0

            val zoomChange = event.calculateZoom()
            if (zoomChange != 1f) {
                camera.zoomBy(zoomChange, event.calculateCentroid(useCurrent = true), center())
            }
            val pan = event.calculatePan()
            if (pan != Offset.Zero) camera.panBy(pan.x, pan.y)

            // Velocity is only tracked for one-finger drags. During a pinch the centroid
            // jumps as fingers lift, which would fling the map in a random direction.
            if (active == 1) {
                event.changes.firstOrNull { it.pressed }?.let {
                    tracker.addPosition(it.uptimeMillis, it.position)
                }
            } else {
                tracker.resetTracking()
            }

            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (pressed)

        val velocity = tracker.calculateVelocity()
        flingJob = scope.launch { camera.fling(Offset(velocity.x, velocity.y), decay) }
    }
}

/**
 * Draws the visible tiles for the current camera.
 *
 * Tiles come from the integer zoom level at or below the camera's fractional zoom, scaled up by
 * the remainder, so pinching scales the current level smoothly and only swaps levels on
 * crossing an integer boundary.
 */
private fun DrawScope.drawTiles(
    camera: MapCameraState,
    store: TileStore,
    firstDrawn: HashMap<Long, Long>,
    now: Long,
) {
    val zoomInt = floor(camera.zoom).toInt().coerceIn(0, MAX_ZOOM.toInt())
    val tileSpan = TILE_SIZE * Math.pow(2.0, (camera.zoom - zoomInt).toDouble())
    val tilesPerAxis = 1 shl zoomInt

    val centerTileX = camera.centerX * tilesPerAxis
    val centerTileY = camera.centerY * tilesPerAxis
    val viewCenter = center()

    val firstX = floor(centerTileX - viewCenter.x / tileSpan).toInt()
    val lastX = ceil(centerTileX + viewCenter.x / tileSpan).toInt()
    val firstY = floor(centerTileY - viewCenter.y / tileSpan).toInt().coerceAtLeast(0)
    val lastY = ceil(centerTileY + viewCenter.y / tileSpan).toInt()
        .coerceAtMost(tilesPerAxis - 1)

    for (ty in firstY..lastY) {
        for (tx in firstX..lastX) {
            // Longitude wraps, so a column off the left edge is a real tile on the right.
            val wrappedX = Math.floorMod(tx, tilesPerAxis)

            // Snapping both edges to whole pixels, rather than rounding the origin and the
            // size independently, guarantees neighbouring tiles share an exact edge and no
            // hairline seams show through.
            val left = viewCenter.x + ((tx - centerTileX) * tileSpan).toFloat()
            val top = viewCenter.y + ((ty - centerTileY) * tileSpan).toFloat()
            val x0 = left.roundToInt()
            val y0 = top.roundToInt()
            val x1 = (left + tileSpan).roundToInt()
            val y1 = (top + tileSpan).roundToInt()
            val dstOffset = IntOffset(x0, y0)
            val dstSize = IntSize(x1 - x0, y1 - y0)

            store.request(zoomInt, wrappedX, ty)
            val key = tileKey(zoomInt, wrappedX, ty)
            val tile = store.peek(key)

            if (tile != null) {
                val since = firstDrawn.getOrPut(key) { now }
                val alpha = if (now <= since) 0f
                else ((now - since).toFloat() / TILE_FADE_MS).coerceAtMost(1f)

                // Until the tile is fully opaque, keep a blurry ancestor underneath so the
                // fade reveals detail rather than fading up from empty background.
                if (alpha < 1f) {
                    drawParentTile(store, zoomInt, wrappedX, ty, dstOffset, dstSize, 1f)
                }
                drawImage(
                    image = tile,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            } else {
                drawParentTile(store, zoomInt, wrappedX, ty, dstOffset, dstSize, 1f)
            }
        }
    }
}

/**
 * Substitutes the matching region of an already-cached lower-zoom tile.
 *
 * This is what stops the map flashing empty while panning or zooming: the ancestor is already
 * in memory from the zoom level the user just left, so there is always something to show.
 */
private fun DrawScope.drawParentTile(
    store: TileStore,
    zoom: Int,
    x: Int,
    y: Int,
    dstOffset: IntOffset,
    dstSize: IntSize,
    alpha: Float,
) {
    for (levelsUp in 1..MAX_PARENT_LOOKUP) {
        val parentZoom = zoom - levelsUp
        if (parentZoom < 0) return
        val parent: ImageBitmap = store.peek(
            tileKey(parentZoom, x shr levelsUp, y shr levelsUp),
        ) ?: continue

        // The tile occupies one cell of a 2^levelsUp grid within its ancestor.
        val divisions = 1 shl levelsUp
        val srcSize = TILE_SIZE / divisions
        drawImage(
            image = parent,
            srcOffset = IntOffset((x % divisions) * srcSize, (y % divisions) * srcSize),
            srcSize = IntSize(srcSize, srcSize),
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
        return
    }
}
