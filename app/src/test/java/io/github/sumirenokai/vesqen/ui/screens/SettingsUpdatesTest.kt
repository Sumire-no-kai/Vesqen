package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.licenses.LicenseText
import io.github.sumirenokai.vesqen.licenses.ThirdPartyLicense
import io.github.sumirenokai.vesqen.updates.UpdateFailure
import io.github.sumirenokai.vesqen.updates.UpdateInstallationSource
import io.github.sumirenokai.vesqen.updates.UpdateLanguage
import io.github.sumirenokai.vesqen.updates.UpdateRelease
import io.github.sumirenokai.vesqen.updates.UpdateState
import io.github.sumirenokai.vesqen.updates.classifyInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUpdatesTest {
    private val release = UpdateRelease(
        versionName = "1.0.0-beta.3",
        versionCode = 12,
        minimumAndroidApi = 26,
        apkUrls = listOf("https://github.com/Sumire-no-kai/Vesqen/releases/download/v1.0.0-beta.3/Vesqen.apk"),
        sha256 = "0".repeat(64),
        releaseNotes = mapOf(UpdateLanguage.ENGLISH to "Fixes", UpdateLanguage.SIMPLIFIED_CHINESE to "修复"),
    )

    @Test fun `every update failure has its own message`() {
        val labels = UpdateFailure.entries.map(::updateFailureLabel)
        assertEquals(UpdateFailure.entries.size, labels.toSet().size)
    }

    @Test fun `updaters that own updates get a readable name and other installers none`() {
        listOf(
            "com.android.vending", "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify",
            "com.machiav3lli.fdroid", "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid", "com.aurora.store",
        ).forEach { installer ->
            // The names follow #78's classification, so every named installer really owns updates.
            assertNotEquals(installer, UpdateInstallationSource.DIRECT, classifyInstaller(installer))
            assertNotNull(installer, updaterName(installer))
        }
        assertNull(updaterName("com.android.packageinstaller"))
        assertNull(updaterName(null))
    }

    @Test fun `a release card lasts from the offer until installation ends`() {
        listOf(
            UpdateState.Available(release),
            UpdateState.Downloading(release, 0, null),
            UpdateState.Verifying(release),
            UpdateState.ReadyToInstall(release, requiresInstallPermission = true),
            UpdateState.Installing(release),
            UpdateState.Failed(UpdateFailure.SIGNATURE_MISMATCH, release),
        ).forEach { assertEquals(it.toString(), release, updateRelease(it)) }
        listOf(
            UpdateState.Idle,
            UpdateState.Checking,
            UpdateState.UpToDate,
            UpdateState.Failed(UpdateFailure.NETWORK_UNAVAILABLE),
            UpdateState.ManagedExternally(UpdateInstallationSource.GOOGLE_PLAY, "com.android.vending"),
        ).forEach { assertNull(it.toString(), updateRelease(it)) }
    }

    @Test fun `checks are blocked only while an operation runs`() {
        assertTrue(UpdateState.Checking.isUpdateBusy())
        assertTrue(UpdateState.Downloading(release, 0, null).isUpdateBusy())
        assertTrue(UpdateState.Verifying(release).isUpdateBusy())
        assertTrue(UpdateState.Installing(release).isUpdateBusy())
        assertFalse(UpdateState.Available(release).isUpdateBusy())
        assertFalse(UpdateState.ReadyToInstall(release, requiresInstallPermission = false).isUpdateBusy())
        assertFalse(UpdateState.Failed(UpdateFailure.DOWNLOAD_FAILED, release).isUpdateBusy())
    }

    @Test fun `license rows show the version and each license once`() {
        val entry = ThirdPartyLicense(
            id = "example:library:1.0",
            name = "Library",
            version = "1.0",
            licenses = listOf(
                LicenseText("Apache-2.0", "text", "LICENSE"),
                LicenseText("Apache-2.0", "text", "NOTICE-LICENSE"),
                LicenseText("MIT", "text", "MIT"),
            ),
            notices = emptyList(),
        )
        assertEquals("1.0 · Apache-2.0 · MIT", licenseSummary(entry))
        assertEquals("OFL-1.1", licenseSummary(entry.copy(version = "", licenses = listOf(LicenseText("OFL-1.1", "text", "OFL")))))
    }
}
