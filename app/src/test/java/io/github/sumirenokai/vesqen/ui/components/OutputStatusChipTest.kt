package io.github.sumirenokai.vesqen.ui.components

import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputStatusChipTest {
    @Test
    fun `every output declaration has a distinct evidence cue`() {
        assertEquals(OutputStatusCue.ROUTE, outputStatusVisualSpec(OutputDeclaration.SYSTEM_MIXED).cue)
        assertEquals(
            OutputStatusCue.AVAILABILITY,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_AVAILABLE).cue,
        )
        assertEquals(
            OutputStatusCue.REQUESTED,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_REQUESTED).cue,
        )
        assertEquals(OutputStatusCue.ACTIVITY, outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_ACTIVE).cue)
        assertEquals(OutputStatusCue.VERIFIED, outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_VERIFIED).cue)
        assertEquals(OutputStatusCue.FAILURE, outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_FAILED).cue)
    }

    @Test
    fun `semantic evidence states cannot accept a neutral surface override`() {
        assertTrue(outputStatusVisualSpec(OutputDeclaration.SYSTEM_MIXED).acceptsNeutralColorOverride)
        assertTrue(outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_REQUESTED).acceptsNeutralColorOverride)
        assertFalse(outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_AVAILABLE).acceptsNeutralColorOverride)
        assertFalse(outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_ACTIVE).acceptsNeutralColorOverride)
        assertFalse(outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_VERIFIED).acceptsNeutralColorOverride)
        assertFalse(outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_FAILED).acceptsNeutralColorOverride)
    }

    @Test
    fun `available active verified and failed use the required treatments`() {
        assertEquals(
            OutputStatusTreatment.SIGNAL_OUTLINE,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_AVAILABLE).treatment,
        )
        assertEquals(
            OutputStatusTreatment.SIGNAL_FILL,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_ACTIVE).treatment,
        )
        assertEquals(
            OutputStatusTreatment.SIGNAL_TONAL,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_VERIFIED).treatment,
        )
        assertEquals(
            OutputStatusTreatment.ERROR,
            outputStatusVisualSpec(OutputDeclaration.BIT_PERFECT_FAILED).treatment,
        )
    }
}
