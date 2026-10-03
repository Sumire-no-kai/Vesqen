package io.github.sumirenokai.vesqen.ui.components

import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import org.junit.Assert.assertEquals
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
    fun `treatments follow the Paper and Sound evidence states`() {
        val treatments = OutputDeclaration.entries.associateWith { outputStatusVisualSpec(it).treatment }
        assertEquals(OutputStatusTreatment.NEUTRAL, treatments[OutputDeclaration.SYSTEM_MIXED])
        assertEquals(OutputStatusTreatment.SIGNAL_OUTLINE, treatments[OutputDeclaration.BIT_PERFECT_AVAILABLE])
        assertEquals(OutputStatusTreatment.SIGNAL_OUTLINE, treatments[OutputDeclaration.BIT_PERFECT_REQUESTED])
        assertEquals(OutputStatusTreatment.SIGNAL_FILL, treatments[OutputDeclaration.BIT_PERFECT_ACTIVE])
        assertEquals(OutputStatusTreatment.SIGNAL_OUTLINE, treatments[OutputDeclaration.BIT_PERFECT_VERIFIED])
        assertEquals(OutputStatusTreatment.WARNING, treatments[OutputDeclaration.BIT_PERFECT_FAILED])
    }

    @Test
    fun `only ACTIVE is filled so no other state can pass for it`() {
        val filled = OutputDeclaration.entries.filter {
            outputStatusVisualSpec(it).treatment == OutputStatusTreatment.SIGNAL_FILL
        }
        assertEquals(listOf(OutputDeclaration.BIT_PERFECT_ACTIVE), filled)
    }
}
