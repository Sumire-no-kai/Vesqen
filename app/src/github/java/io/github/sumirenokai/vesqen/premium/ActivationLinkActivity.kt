package io.github.sumirenokai.vesqen.premium

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.VesqenApplication

/**
 * Opens `https://vesqen-api.sumirenokai.com/activate#<code>` from the website's purchase page (#120).
 * The code sits in the fragment, which browsers never send to the server. This activity checks it,
 * then hands over to the app on its Pro features page, which shows the result; it has no UI of its own.
 */
class ActivationLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data
        val code = link?.takeIf { it.scheme == "https" && it.host == HOST && it.path?.trimEnd('/') == PATH }?.fragment
        val activationCodes = (application as VesqenApplication).activationCodes
        val checked = code != null && activationCodes != null
        if (checked) activationCodes?.activate(code.orEmpty())
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PREMIUM, checked),
        )
        finish()
    }

    private companion object {
        const val HOST = "vesqen-api.sumirenokai.com"
        const val PATH = "/activate"
    }
}
