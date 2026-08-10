package com.sam.openspoof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sam.openspoof.ui.MainScreen
import com.sam.openspoof.ui.theme.OpenSpoofTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The map is drawn to the very edges of the display; the controls layered over it
        // apply their own safe-drawing insets.
        enableEdgeToEdge()
        setContent {
            OpenSpoofTheme {
                MainScreen()
            }
        }
    }
}
