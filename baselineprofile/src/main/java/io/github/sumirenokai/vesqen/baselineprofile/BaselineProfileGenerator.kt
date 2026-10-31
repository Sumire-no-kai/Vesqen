package io.github.sumirenokai.vesqen.baselineprofile

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the journeys from #50 where the owner saw jank: flinging a large library and moving
 * between Settings pages, plus Now and Chain. Each test adds its rules to the merged profile; only
 * startup goes into the startup profile. The device needs a large library in MediaStore (README.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupToLibrary() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        startAtLibrary()
    }

    @Test
    fun libraryFlings() = rule.collect(PACKAGE) {
        startAtLibrary()
        val tracks = scrollable("vesqen.library.tracks")
        repeat(FLINGS) { tracks.fling(Direction.DOWN) }
        repeat(FLINGS) { tracks.fling(Direction.UP) }
        click("vesqen.library.mode.albums")
        // The album list has no tag of its own: it is the scrollable that holds the album rows.
        scrollable(By.scrollable(true).hasDescendant(By.res(ALBUM_ROW)), "album list").fling(Direction.DOWN)
        click("vesqen.library.mode.songs")
        waitFor("vesqen.library.tracks")
    }

    @Test
    fun albumNowAndChain() = rule.collect(PACKAGE) {
        startAtLibrary()
        click("vesqen.library.mode.albums")
        // Albums sort by title, so the first one is a demo FLAC album with a cover.
        click(ALBUM_ROW)
        click("vesqen.library.album.play")
        click("vesqen.mini-player.open-now")
        // #169: the lyrics in the cover's place, then the cover again, so later runs start from it.
        // The first demo track embeds synced lyrics (tools/demo_library); any other shows the message.
        click("vesqen.now.lyrics-toggle")
        waitFor(By.res(LYRICS_SHOWN), LYRICS_SHOWN.pattern(), UI_TIMEOUT_MS)
        click("vesqen.now.lyrics-toggle")
        waitFor("vesqen.now.artwork-stage")
        click("vesqen.now.notes-toggle")
        click("vesqen.now.open-chain")
        waitFor("vesqen.chain")
        device.pressBack()
        click("vesqen.now.play-pause")
        // Now hides the navigation bar; Back returns to the album.
        device.pressBack()
        click("vesqen.nav.chain")
        scrollable("vesqen.chain").fling(Direction.DOWN)
    }

    @Test
    fun settingsPages() = rule.collect(PACKAGE) {
        startAtLibrary()
        click("vesqen.nav.settings")
        val settings = scrollable("vesqen.settings")
        settings.fling(Direction.DOWN)
        settings.fling(Direction.UP)
        click("vesqen.settings.playback-chain")
        waitFor("vesqen.chain")
        device.pressBack()
        openFromSettings("vesqen.settings.about", "vesqen.about")
        device.pressBack()
        openFromSettings("vesqen.settings.licenses", "vesqen.licenses")
        // The list loads off the main thread; Back leaves an open entry before the list.
        click("vesqen.licenses.entry.0")
        waitFor("vesqen.licenses.detail")
        device.pressBack()
        scrollable("vesqen.licenses").fling(Direction.DOWN)
        device.pressBack()
        waitFor("vesqen.settings")
    }

    private fun MacrobenchmarkScope.startAtLibrary() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissions.forEach { device.executeShellCommand("pm grant $PACKAGE $it") }
        pressHome()
        startActivityAndWait()
        // A first launch scans the whole library; later launches show the stored catalog.
        waitFor(By.res(TRACK_ROW), TRACK_ROW.pattern(), SCAN_TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.openFromSettings(rowTag: String, pageTag: String) {
        val settings = scrollable("vesqen.settings")
        checkNotNull(settings.scrollUntil(Direction.DOWN, Until.findObject(By.res(rowTag)))) {
            "Settings has no $rowTag"
        }
        click(rowTag)
        waitFor(pageTag)
    }

    private fun MacrobenchmarkScope.scrollable(tag: String): UiObject2 = scrollable(By.res(tag), tag)

    private fun MacrobenchmarkScope.scrollable(selector: BySelector, description: String): UiObject2 =
        waitFor(selector, description, UI_TIMEOUT_MS).apply {
            // Keep the gestures clear of the system back and navigation gesture areas.
            setGestureMargin(device.displayWidth / 5)
        }

    private fun MacrobenchmarkScope.click(tag: String) = click(By.res(tag), tag)

    private fun MacrobenchmarkScope.click(pattern: Pattern) = click(By.res(pattern), pattern.pattern())

    /** Taps once the target stops moving, so enter and expand animations cannot shift it away. */
    private fun MacrobenchmarkScope.click(selector: BySelector, description: String) {
        var target = waitFor(selector, description, UI_TIMEOUT_MS)
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val bounds = target.visibleBounds
            SystemClock.sleep(SETTLE_MS)
            target = waitFor(selector, description, UI_TIMEOUT_MS)
            if (target.visibleBounds == bounds) break
            check(SystemClock.uptimeMillis() < deadline) { "$description kept moving" }
        }
        target.click()
    }

    private fun MacrobenchmarkScope.waitFor(tag: String): UiObject2 = waitFor(By.res(tag), tag, UI_TIMEOUT_MS)

    private fun MacrobenchmarkScope.waitFor(selector: BySelector, description: String, timeoutMs: Long): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "$description did not appear within $timeoutMs ms"
        }

    private companion object {
        // The nonMinifiedRelease target keeps the release application ID.
        const val PACKAGE = "io.github.sumirenokai.vesqen"
        const val FLINGS = 6
        const val SETTLE_MS = 250L
        const val UI_TIMEOUT_MS = 10_000L
        const val SCAN_TIMEOUT_MS = 180_000L
        val TRACK_ROW: Pattern = Pattern.compile("""vesqen\.library\.track\.-?\d+""")
        val ALBUM_ROW: Pattern = Pattern.compile("""vesqen\.library\.collection\..+""")
        val LYRICS_SHOWN: Pattern = Pattern.compile("""vesqen\.now\.lyrics\.(line\.0|message)""")
    }
}
