package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsTest {
    @Test
    fun `current OVOS intent-unmatched event is a failure`() {
        assertTrue("ovos.intent.unmatched" in ThalovantEvents.FAILURE_EVENTS)
        assertTrue(ThalovantEvent(name = "ovos.intent.unmatched").isFailure)
    }

    @Test
    fun `legacy Mycroft complete_intent_failure event is still a failure`() {
        assertTrue("complete_intent_failure" in ThalovantEvents.FAILURE_EVENTS)
        assertTrue(ThalovantEvent(name = "complete_intent_failure").isFailure)
    }

    @Test
    fun `matched utterance events are not failures`() {
        assertFalse(ThalovantEvent(name = ThalovantEvents.UTTERANCE_HANDLED).isFailure)
        assertFalse(ThalovantEvent(name = ThalovantEvents.SPEAK).isFailure)
    }
}
