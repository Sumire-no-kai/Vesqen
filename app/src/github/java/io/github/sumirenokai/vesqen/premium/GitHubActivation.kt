package io.github.sumirenokai.vesqen.premium

import android.content.Context
import androidx.core.content.edit
import io.github.sumirenokai.vesqen.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The betas stay free, so their release builds take no codes: entry and links work only where the
 * build charges, or in debug builds that test the flow with the test key (#120).
 */
internal fun createActivationCodes(context: Context, onChanged: () -> Unit): ActivationCodes? {
    if (!BuildConfig.DEBUG && !BuildConfig.PREMIUM_ENFORCED) return null
    val revoked = context.assets.open("activation/revoked.txt").bufferedReader().useLines {
        ActivationCodeVerifier.parseRevoked(it)
    }
    val verifier = ActivationCodeVerifier(ActivationKeys.trusted(includeTestKey = BuildConfig.DEBUG), revoked)
    return ActivationCodeBook(AndroidKeptCodeStore(context), verifier, onChanged)
}

internal object ActivationKeys {
    const val TEST_KEY_ID = 0x81

    /**
     * Production keys, by key id. The owner generates each one offline (docs/ACTIVATION_CODES.md);
     * none exists before 1.0. Old keys stay so codes already sold keep working.
     */
    private val production = mapOf<Int, String>()

    /** Debug builds only. Its private key never signs codes that are sold. */
    private const val TEST_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEF0tfhspwYJxvQ1R7vSJGx5J8YVNMJSfDYzkJlZVLLRtV1N9akqVnhp8UCUes/0UL7coS3zrN2Gb7Tg1UNKihTg=="

    fun trusted(includeTestKey: Boolean) = buildMap {
        production.forEach { (id, key) -> put(id, ActivationCodeVerifier.publicKey(key)) }
        if (includeTestKey) put(TEST_KEY_ID, ActivationCodeVerifier.publicKey(TEST_KEY))
    }
}

internal interface KeptCodeStore {
    fun read(): String?
    fun write(compact: String)
}

/** The kept code, checked again at every start so a revocation in a later version takes effect. */
internal class ActivationCodeBook(
    private val store: KeptCodeStore,
    private val verifier: ActivationCodeVerifier,
    private val onChanged: () -> Unit,
) : ActivationCodes {
    private val mutableState = MutableStateFlow(stateOf(store.read()))
    private val mutableResult = MutableStateFlow<ActivationResult?>(null)
    override val state: StateFlow<ActivationState> = mutableState.asStateFlow()
    override val lastResult: StateFlow<ActivationResult?> = mutableResult.asStateFlow()

    @Synchronized
    override fun activate(text: String): ActivationResult {
        val result = when (val outcome = verifier.check(text)) {
            is ActivationCodeVerifier.Outcome.Genuine -> {
                store.write(outcome.compact)
                mutableState.value = ActivationState.Active(ActivationCodeVerifier.display(outcome.compact))
                ActivationResult.ACTIVATED
            }
            // A revoked code never replaces a working one.
            is ActivationCodeVerifier.Outcome.Revoked -> ActivationResult.REVOKED
            ActivationCodeVerifier.Outcome.Malformed -> ActivationResult.MALFORMED
            ActivationCodeVerifier.Outcome.NotGenuine -> ActivationResult.NOT_GENUINE
        }
        mutableResult.value = result
        if (result == ActivationResult.ACTIVATED) onChanged()
        return result
    }

    private fun stateOf(kept: String?): ActivationState = when (val outcome = kept?.let(verifier::check)) {
        is ActivationCodeVerifier.Outcome.Genuine -> ActivationState.Active(ActivationCodeVerifier.display(outcome.compact))
        is ActivationCodeVerifier.Outcome.Revoked -> ActivationState.Revoked(ActivationCodeVerifier.display(outcome.compact))
        // No code, or one this build no longer trusts (a debug test code in a release build).
        else -> ActivationState.None
    }
}

/**
 * Any app or page can open an activation link, with a code that may leak and be revoked later, so a
 * link never replaces a working code; the Pro page then shows the kept one, and typing a code there
 * still replaces it (#194 S3). Null when the link was not used.
 */
internal fun ActivationCodes.activateFromLink(text: String): ActivationResult? =
    if (state.value is ActivationState.Active) null else activate(text)

private class AndroidKeptCodeStore(context: Context) : KeptCodeStore {
    private val preferences = context.getSharedPreferences("activation", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString(KEY, null)
    override fun write(compact: String) = preferences.edit { putString(KEY, compact) }

    private companion object {
        const val KEY = "code"
    }
}
