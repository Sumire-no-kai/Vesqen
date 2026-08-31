package io.github.sumirenokai.vesqen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.ui.VesqenApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VesqenTheme {
                VesqenApp()
            }
        }
    }
}
