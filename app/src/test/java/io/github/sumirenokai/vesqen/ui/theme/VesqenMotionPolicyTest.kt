package io.github.sumirenokai.vesqen.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class VesqenMotionPolicyTest {
    @Test
    fun focused_player_handoff_delays_stay_inside_the_full_transition_budgets() {
        val motion = VesqenMotionPolicy(reduceMotion = false)

        assertEquals(24, motion.playerHandoffDelayMillis)
        assertEquals(72, motion.playerReturnRevealDelayMillis)
        assertEquals(216, motion.playerExpandMillis - motion.playerHandoffDelayMillis)
        assertEquals(108, motion.playerCollapseMillis - motion.playerReturnRevealDelayMillis)
    }

    @Test
    fun reduced_motion_removes_the_handoff_delay() {
        val motion = VesqenMotionPolicy(reduceMotion = true)

        assertEquals(0, motion.playerHandoffDelayMillis)
        assertEquals(0, motion.playerReturnRevealDelayMillis)
        assertEquals(VesqenMotion.ReducedMotionMillis, motion.playerExpandMillis)
        assertEquals(VesqenMotion.ReducedMotionMillis, motion.playerCollapseMillis)
    }
}
