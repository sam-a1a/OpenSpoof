package com.sam.openspoof.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sam.openspoof.R
import com.sam.openspoof.geo.Nominatim
import com.sam.openspoof.geo.Place
import com.sam.openspoof.map.GeoPoint
import com.sam.openspoof.map.MapCameraState
import com.sam.openspoof.map.OsmMap
import com.sam.openspoof.map.TileStore
import com.sam.openspoof.map.formatLatLon
import com.sam.openspoof.mock.SpoofService
import com.sam.openspoof.mock.SpoofState
import com.sam.openspoof.mock.isMockLocationEnabled
import com.sam.openspoof.mock.openDeveloperOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Permissions the foreground service needs before it can start. Location is not used to read a
 * position; Android 14+ simply refuses to start a location-typed foreground service without it.
 */
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val camera = rememberSaveable(saver = MapCameraState.Saver) {
        MapCameraState(lat = 48.8584, lon = 2.2945, zoom = 13f)
    }
    // Scoped to the composition, so its loaders are cancelled when the screen goes away.
    val store = remember { TileStore(context, scope) }

    val spoofState by SpoofService.state.collectAsState()
    val active = spoofState as? SpoofState.Active

    var showEnableDialog by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }

    val flySpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val zoomSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    fun beginSpoof() {
        SpoofService.start(context, GeoPoint(camera.latitude, camera.longitude))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Notifications being denied is survivable, but the service cannot start at all
        // without location, so that one decides whether to proceed.
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            beginSpoof()
        } else {
            scope.launch { snackbars.showSnackbar(context.getString(R.string.permission_needed)) }
        }
    }

    fun requestSpoof() {
        if (!context.isMockLocationEnabled()) {
            showEnableDialog = true
            return
        }
        val missing = REQUIRED_PERMISSIONS.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) beginSpoof() else permissionLauncher.launch(missing.toTypedArray())
    }

    // The service reports back if the providers could not be installed, which in practice means
    // the mock location selection was revoked while running.
    LaunchedEffect(spoofState) {
        if (spoofState is SpoofState.Failed) {
            SpoofService.acknowledgeFailure()
            showEnableDialog = true
            snackbars.showSnackbar(context.getString(R.string.spoof_failed))
        }
    }

    // There is no broadcast for "the user picked a mock location app", so while the dialog is up
    // the app-op is polled and the dialog closes itself when the user comes back having done it.
    LaunchedEffect(showEnableDialog) {
        while (showEnableDialog) {
            delay(600)
            if (context.isMockLocationEnabled()) showEnableDialog = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OsmMap(camera = camera, store = store)

        CenterPin(
            // Lifted for any movement, dragged or flown, so the pin touching down always
            // means the coordinate below it has stopped changing.
            lifted = camera.isInteracting || camera.isFlying,
            active = active != null,
        )

        Attribution(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 12.dp, bottom = 6.dp),
        )

        PlaceSearchBar(
            query = query,
            onQueryChange = {
                query = it
                if (it.isEmpty()) {
                    results = emptyList()
                    searchMessage = null
                }
            },
            onSubmit = {
                if (query.isBlank()) return@PlaceSearchBar
                searching = true
                searchMessage = null
                scope.launch {
                    runCatching { Nominatim.search(query) }
                        .onSuccess { found ->
                            results = found
                            searchMessage = if (found.isEmpty()) {
                                context.getString(R.string.no_results)
                            } else {
                                null
                            }
                        }
                        .onFailure {
                            results = emptyList()
                            searchMessage = context.getString(R.string.offline)
                        }
                    searching = false
                }
            },
            onPick = { place ->
                results = emptyList()
                searchMessage = null
                query = place.name
                scope.launch { camera.animateTo(place.lat, place.lon, 16f, flySpec) }
            },
            searching = searching,
            results = results,
            message = searchMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        ZoomControls(
            onZoomIn = {
                scope.launch {
                    camera.animateZoomTo(floor(camera.zoom) + 1f, Offset.Zero, Offset.Zero, zoomSpec)
                }
            },
            onZoomOut = {
                scope.launch {
                    camera.animateZoomTo(
                        target = kotlin.math.ceil(camera.zoom) - 1f,
                        focus = Offset.Zero,
                        viewportCenter = Offset.Zero,
                        spec = zoomSpec,
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 12.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SnackbarHost(hostState = snackbars) { Snackbar(it) }

            CoordinateReadout(
                // While spoofing, the readout describes the position actually being broadcast,
                // not wherever the user has since panned the map to.
                point = active?.point ?: GeoPoint(camera.latitude, camera.longitude),
            )

            PrimaryAction(
                active = active != null,
                onClick = { if (active != null) SpoofService.stop(context) else requestSpoof() },
            )
        }

        if (showEnableDialog) {
            MockLocationDialog(
                onOpenSettings = {
                    if (!context.openDeveloperOptions()) {
                        scope.launch {
                            snackbars.showSnackbar(context.getString(R.string.enable_hint))
                        }
                    }
                },
                onDismiss = { showEnableDialog = false },
            )
        }
    }
}

@Composable
private fun PrimaryAction(active: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "actionContainer",
    )
    val content by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "actionContent",
    )

    // transitionSpec is not a composable scope, so the spec is read from the theme out here
    // and captured, rather than looked up inside the lambda.
    val swap = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val crossfade = remember(swap) {
        ContentTransform(
            targetContentEnter = fadeIn(swap),
            initialContentExit = fadeOut(swap),
        )
    }

    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = container,
        contentColor = content,
        text = {
            AnimatedContent(
                targetState = active,
                transitionSpec = { crossfade },
                label = "actionLabel",
            ) { isActive ->
                Text(
                    stringResource(
                        if (isActive) R.string.stop_spoof else R.string.start_spoof,
                    ),
                )
            }
        },
        icon = {
            AnimatedContent(
                targetState = active,
                transitionSpec = { crossfade },
                label = "actionIcon",
            ) { isActive ->
                Icon(
                    painter = painterResource(
                        if (isActive) R.drawable.ic_stop else R.drawable.ic_play,
                    ),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun CoordinateReadout(point: GeoPoint) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = formatLatLon(point.lat, point.lon),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            IconButton(onClick = onZoomIn) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.zoom_in),
                )
            }
            IconButton(onClick = onZoomOut) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove),
                    contentDescription = stringResource(R.string.zoom_out),
                )
            }
        }
    }
}

/** ODbL requires visible credit wherever OpenStreetMap tiles are shown. */
@Composable
private fun Attribution(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Text(
            text = stringResource(R.string.attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
