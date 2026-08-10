package com.sam.openspoof.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sam.openspoof.R

/**
 * Explains why spoofing is not working yet and sends the user to Developer options.
 *
 * This dialog is not optional politeness. Selecting a mock location app is a manual step with no
 * permission prompt and no intent that deep-links to the picker, so an app that skips the
 * explanation simply appears broken: buttons do nothing and the failure surfaces as a
 * SecurityException the user never sees.
 */
@Composable
fun MockLocationDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Expressive spatial springs are underdamped, so the icon settles with a slight overshoot
    // rather than easing in. Scale starts below 1 to give the spring somewhere to travel.
    val scale = remember { Animatable(0.5f) }
    val entrance = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { scale.animateTo(1f, entrance) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_developer_mode),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
            )
        },
        title = { Text(stringResource(R.string.enable_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.enable_body),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // The one instruction that matters gets its own surface so it survives being
                // skim-read, which is how anyone reads a dialog standing between them and a map.
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.enable_step),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.enable_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.enable_later))
            }
        },
    )
}
