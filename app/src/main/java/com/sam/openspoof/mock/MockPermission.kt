package com.sam.openspoof.mock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Whether this app is the one currently chosen under
 * Developer options > Select mock location app.
 *
 * There is no permission to request for this: ACCESS_MOCK_LOCATION is declared in the manifest
 * but the user has to pick the app by hand, and the choice surfaces as the MOCK_LOCATION app-op
 * rather than as a granted permission. Checking the op up front is what lets the app explain
 * the situation instead of letting addTestProvider throw SecurityException.
 */
fun Context.isMockLocationEnabled(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    // checkOpNoThrow, not unsafeCheckOpNoThrow: the pair was renamed to the "unsafe" spelling
    // and then renamed back, leaving the unsafe variant deprecated on current platforms.
    val mode = runCatching {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(),
            packageName,
        )
    }.getOrDefault(AppOpsManager.MODE_ERRORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Opens Developer options, which is where the mock location app is chosen. There is no intent
 * that deep-links to the picker itself, so the dialog that launches this has to say what to
 * tap once the screen opens.
 */
fun Context.openDeveloperOptions(): Boolean = runCatching {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrElse {
    // Developer options are hidden until unlocked, and the screen is absent on some builds.
    runCatching {
        startActivity(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
