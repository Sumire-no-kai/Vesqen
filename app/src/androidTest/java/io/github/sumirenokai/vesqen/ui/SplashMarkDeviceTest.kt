package io.github.sumirenokai.vesqen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import io.github.sumirenokai.vesqen.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android 12+ splash icon inflates, finishes well inside the splash's 700 ms budget plus
 * margin, and ends with the whole mark drawn. It runs in a real view: an animated vector only
 * animates once a hardware canvas draws it, as the system splash does.
 */
class SplashMarkDeviceTest {
    @Test fun splashMarkFinishesQuicklyWithTheWholeMarkDrawn() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val ended = CountDownLatch(1)
            lateinit var view: ImageView
            scenario.onActivity { activity ->
                val mark = activity.getDrawable(R.drawable.avd_splash_mark) as AnimatedVectorDrawable
                mark.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable) = ended.countDown()
                })
                view = ImageView(activity).apply { setImageDrawable(mark) }
                activity.setContentView(view, ViewGroup.LayoutParams(Size, Size))
                view.post { mark.start() }
            }
            assertTrue("the splash mark must finish within 1.5 s", ended.await(1_500, TimeUnit.MILLISECONDS))
            var inked = 0
            scenario.onActivity {
                val bitmap = Bitmap.createBitmap(Size, Size, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(Size * Size)
                bitmap.getPixels(pixels, 0, Size, 0, 0, Size, Size)
                inked = pixels.count { (it ushr 24) > 0 }
            }
            assertTrue("both paths must be drawn at the end; inked=$inked", inked > Size * Size / 20)
        }
    }

    private companion object {
        const val Size = 216
    }
}
