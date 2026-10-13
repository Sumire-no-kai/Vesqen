package io.github.sumirenokai.vesqen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.ui.VesqenApp

class MainActivity : ComponentActivity() {
    /** Counts requests to show the Pro features page, such as after an activation link (#120). */
    private val premiumRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takePremiumRequest(intent)
        setContent {
            VesqenTheme {
                VesqenApp(openPremiumRequest = premiumRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePremiumRequest(intent)
    }

    private fun takePremiumRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PREMIUM, false) == true) premiumRequests.intValue += 1
    }

    companion object {
        const val EXTRA_OPEN_PREMIUM = "io.github.sumirenokai.vesqen.OPEN_PREMIUM"
    }
}
