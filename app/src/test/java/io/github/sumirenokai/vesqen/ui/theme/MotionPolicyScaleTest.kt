package io.github.sumirenokai.vesqen.ui.theme

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MotionPolicyScaleTest {
    @Test
    fun `the same composition reads an updated animator scale`() = runBlocking {
        val scale = object : MotionDurationScale {
            override var scaleFactor by mutableFloatStateOf(1f)
        }
        val recomposer = Recomposer(coroutineContext + scale)
        val composition = Composition(NoopApplier(), recomposer)
        val states = mutableListOf<Boolean>()
        fun render(pass: Int) {
            composition.setContent {
                val policy = rememberVesqenMotionPolicy()
                SideEffect { states.add(pass, policy.reduceMotion) }
            }
        }
        try {
            render(0)
            scale.scaleFactor = 0f
            render(1)
            scale.scaleFactor = 1f
            render(2)
            assertEquals(listOf(false, true, false), states)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
