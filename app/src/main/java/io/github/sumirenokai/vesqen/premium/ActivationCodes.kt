package io.github.sumirenokai.vesqen.premium

import kotlinx.coroutines.flow.StateFlow

/**
 * #120: activation codes bought on the website unlock the GitHub build. The Play build sells
 * through Google Play and has none (its factory returns null). Codes are checked on the device
 * only; see docs/ACTIVATION_CODES.md.
 */
interface ActivationCodes {
    val state: StateFlow<ActivationState>

    /** The outcome of the latest [activate], from the Pro features page or an activation link. */
    val lastResult: StateFlow<ActivationResult?>

    /** Checks [text] on this device and, when it is genuine and not revoked, keeps it. */
    fun activate(text: String): ActivationResult
}

sealed interface ActivationState {
    data object None : ActivationState

    /** [code] in display groups, kept so the person can copy it for a reinstall or a new phone. */
    data class Active(val code: String) : ActivationState

    /** A kept code that a later version revoked after a refund, a chargeback or a leak. */
    data class Revoked(val code: String) : ActivationState
}

enum class ActivationResult { ACTIVATED, MALFORMED, NOT_GENUINE, REVOKED }
