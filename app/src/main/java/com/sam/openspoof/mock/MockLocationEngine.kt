package com.sam.openspoof.mock

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock

/**
 * Providers the app impersonates.
 *
 * Mocking only "gps" is the usual mistake: most apps read location through the fused provider,
 * and some fall back to "network" indoors, so a spoof that covers just GPS looks inconsistent
 * or is ignored outright. FUSED_PROVIDER has existed as a mockable provider since API 31.
 */
private val PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.FUSED_PROVIDER,
)

/** Reported horizontal accuracy, in metres. A plausible good-conditions GPS fix. */
private const val ACCURACY_METERS = 3.0f

private const val ALTITUDE_METERS = 12.0

/**
 * Installs test providers and feeds them a fixed position.
 *
 * All of this requires the app to be selected under Developer options; see [isMockLocationEnabled].
 */
class MockLocationEngine(context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)

    /** Providers successfully registered, so teardown only removes what was added. */
    private var installed = emptyList<String>()

    /**
     * Registers the test providers. Returns false if none could be installed, which in practice
     * means the app is not the selected mock location app.
     */
    fun start(): Boolean {
        val manager = locationManager ?: return false
        val properties = ProviderProperties.Builder()
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()

        installed = PROVIDERS.filter { provider ->
            // Devices vary in which providers they will let you mock, so each is attempted
            // independently and a refusal on one does not sink the rest.
            runCatching {
                // Removing first clears any provider left behind by a previous crash;
                // addTestProvider throws if the name is already registered.
                runCatching { manager.removeTestProvider(provider) }
                manager.addTestProvider(provider, properties)
                manager.setTestProviderEnabled(provider, true)
            }.isSuccess
        }
        return installed.isNotEmpty()
    }

    /** Publishes [lat]/[lon] to every installed provider. */
    fun push(lat: Double, lon: Double) {
        val manager = locationManager ?: return
        for (provider in installed) {
            runCatching {
                manager.setTestProviderLocation(provider, buildLocation(provider, lat, lon))
            }
        }
    }

    fun stop() {
        val manager = locationManager ?: return
        for (provider in installed) {
            runCatching {
                manager.setTestProviderEnabled(provider, false)
                manager.removeTestProvider(provider)
            }
        }
        installed = emptyList()
    }

    private fun buildLocation(provider: String, lat: Double, lon: Double) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = ALTITUDE_METERS
            accuracy = ACCURACY_METERS
            bearing = 0f
            speed = 0f
            time = System.currentTimeMillis()

            // Mandatory: setTestProviderLocation rejects a Location whose monotonic timestamp
            // is unset or in the future, so this cannot be left at its default.
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            // Consumers increasingly discard fixes that omit these uncertainty fields, so a
            // spoof without them is silently ignored by some apps.
            bearingAccuracyDegrees = 0.5f
            speedAccuracyMetersPerSecond = 0.1f
            verticalAccuracyMeters = 2.0f
        }
}
