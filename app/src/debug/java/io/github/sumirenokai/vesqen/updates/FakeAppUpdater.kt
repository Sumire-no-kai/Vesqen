package io.github.sumirenokai.vesqen.updates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic preview/test implementation: no network, files, installer or background work. */
class FakeAppUpdater(initial: UpdateSnapshot = UpdateSnapshot()) : AppUpdater {
    private val mutableSnapshot = MutableStateFlow(initial)
    override val snapshot = mutableSnapshot.asStateFlow()

    /** The UI harness advances checks/downloads explicitly, including failures and permission states. */
    fun emit(state: UpdateState) {
        mutableSnapshot.value = mutableSnapshot.value.copy(state = state)
    }

    override fun checkNow() = emit(UpdateState.Checking)

    override fun skipVersion(versionCode: Long) {
        val current = mutableSnapshot.value
        val available = current.state as? UpdateState.Available
        mutableSnapshot.value = current.copy(
            skippedVersionCode = versionCode,
            state = if (available?.release?.versionCode == versionCode) available.copy(skipped = true) else current.state,
        )
    }

    override fun downloadUpdate() {
        val release = (mutableSnapshot.value.state as? UpdateState.Available)?.release ?: return
        emit(UpdateState.Downloading(release, 0, null))
    }

    override fun installUpdate() {
        val ready = mutableSnapshot.value.state as? UpdateState.ReadyToInstall ?: return
        if (!ready.requiresInstallPermission) emit(UpdateState.Installing(ready.release))
    }

    override fun setAutomaticChecksEnabled(enabled: Boolean) {
        mutableSnapshot.value = mutableSnapshot.value.copy(automaticChecksEnabled = enabled)
    }
}
