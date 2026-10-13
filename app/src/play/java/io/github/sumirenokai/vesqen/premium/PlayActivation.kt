package io.github.sumirenokai.vesqen.premium

import android.content.Context

/** Google Play sells this build's Pro features (#46); it never accepts website activation codes. */
@Suppress("UNUSED_PARAMETER")
internal fun createActivationCodes(context: Context, onChanged: () -> Unit): ActivationCodes? = null
