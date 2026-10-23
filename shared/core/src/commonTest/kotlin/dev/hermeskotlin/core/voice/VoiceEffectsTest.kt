package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a phone's answer about its audio effects says, in words a bug report can carry. */
class VoiceEffectsTest {

    @Test
    fun aPhoneThatCancelsTheEchoSaysSo() {
        val effects = VoiceEffects(true, true, true, true)
        assertTrue(effects.cancelsEcho)
        assertEquals("aec offered=yes on=yes · ns offered=yes on=yes", effects.toString())
    }

    @Test
    fun offeredButRefusedIsNotCancelling() {
        val effects = VoiceEffects(echoCancelAvailable = true, echoCancelEnabled = false)
        assertFalse(effects.cancelsEcho)
        assertEquals("aec offered=yes on=no · ns offered=no on=no", effects.toString())
    }

    @Test
    fun nothingOfferedIsPlainRecording() {
        val effects = VoiceEffects()
        assertFalse(effects.cancelsEcho)
        assertEquals("aec offered=no on=no · ns offered=no on=no", effects.toString())
    }
}
