package io.github.sumirenokai.vesqen.updates

import org.junit.Assert.*
import org.junit.Test

class FakeAppUpdaterTest {
    private val release = UpdateRelease("1.0.0-beta.2", 11, 26,
        listOf("https://github.com/Sumire-no-kai/Vesqen/releases/download/example/app.apk"),
        "a".repeat(64), mapOf(UpdateLanguage.ENGLISH to "Example notes"))

    @Test fun userCommandsPreserveSettingsAndNeverSkipSystemPermission() {
        val updater = FakeAppUpdater()
        updater.setAutomaticChecksEnabled(false)
        updater.checkNow()
        assertEquals(UpdateState.Checking, updater.snapshot.value.state)
        updater.emit(UpdateState.Available(release))
        updater.skipVersion(11)
        assertTrue((updater.snapshot.value.state as UpdateState.Available).skipped)
        updater.downloadUpdate()
        assertEquals(UpdateState.Downloading(release, 0, null), updater.snapshot.value.state)
        updater.emit(UpdateState.ReadyToInstall(release, true))
        updater.installUpdate()
        assertEquals(UpdateState.ReadyToInstall(release, true), updater.snapshot.value.state)
        updater.emit(UpdateState.ReadyToInstall(release, false))
        updater.installUpdate()
        assertEquals(UpdateState.Installing(release), updater.snapshot.value.state)
        assertFalse(updater.snapshot.value.automaticChecksEnabled)
    }

    @Test fun invalidPhaseCommandsDoNotCreateDownloadsOrInstallations() {
        val updater = FakeAppUpdater()
        updater.downloadUpdate()
        updater.installUpdate()
        assertEquals(UpdateState.Idle, updater.snapshot.value.state)
    }
}
