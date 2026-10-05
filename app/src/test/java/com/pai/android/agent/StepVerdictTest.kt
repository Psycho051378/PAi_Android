package com.pai.android.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class StepVerdictTest {

    private val noFiles = emptyMap<String, Long>()
    private val someFiles = mapOf("a.txt" to 1L)

    @Test
    fun firstStepSucceedsEvenWithoutChanges() {
        assertEquals(
            StepVerdict.Outcome.DONE,
            StepVerdict.decide(true, 0, noFiles, noFiles, 1, 2)
        )
    }

    @Test
    fun laterStepNeedsFileChanges() {
        assertEquals(
            StepVerdict.Outcome.DONE,
            StepVerdict.decide(true, 1, noFiles, someFiles, 1, 2)
        )
    }

    @Test
    fun laterStepWithoutChangesRetriesThenFails() {
        assertEquals(
            StepVerdict.Outcome.RETRY,
            StepVerdict.decide(true, 1, noFiles, noFiles, 1, 2)
        )
        assertEquals(
            StepVerdict.Outcome.FAILED,
            StepVerdict.decide(true, 1, noFiles, noFiles, 2, 2)
        )
    }

    @Test
    fun failureRetriesThenFails() {
        assertEquals(
            StepVerdict.Outcome.RETRY,
            StepVerdict.decide(false, 0, noFiles, noFiles, 1, 2)
        )
        assertEquals(
            StepVerdict.Outcome.FAILED,
            StepVerdict.decide(false, 0, noFiles, noFiles, 2, 2)
        )
    }

    @Test
    fun detectsModificationByTimestamp() {
        val before = mapOf("a.txt" to 1L)
        val after = mapOf("a.txt" to 2L)
        assertEquals(
            StepVerdict.Outcome.DONE,
            StepVerdict.decide(true, 1, before, after, 1, 2)
        )
    }
}
