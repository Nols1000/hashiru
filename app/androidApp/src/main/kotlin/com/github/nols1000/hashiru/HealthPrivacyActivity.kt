package com.github.nols1000.hashiru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Opened by Health Connect from its permission screen to explain what hashiru does with health data. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HealthPrivacyScreen(onClose = ::finish) }
    }
}
