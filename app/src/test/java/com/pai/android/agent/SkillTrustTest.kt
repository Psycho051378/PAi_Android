package com.pai.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTrustTest {

    // sha256("abc")
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcBytes = "abc".toByteArray()

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(abc, SkillTrust.sha256(abcBytes))
    }

    @Test
    fun verifyAcceptsCorrectHashCaseInsensitiveAndTrimmed() {
        assertTrue(SkillTrust.verify(abcBytes, abc))
        assertTrue(SkillTrust.verify(abcBytes, abc.uppercase()))
        assertTrue(SkillTrust.verify(abcBytes, "  $abc  "))
    }

    @Test
    fun verifyRejectsWrongOrMissingHash() {
        assertFalse(SkillTrust.verify(abcBytes, "deadbeef"))
        assertFalse(SkillTrust.verify(abcBytes, null))
        assertFalse(SkillTrust.verify(abcBytes, ""))
        assertFalse(SkillTrust.verify(abcBytes, "   "))
    }
}
