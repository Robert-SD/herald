package dev.hermeskotlin.core.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The spectral half of the voice gate: what a frame of a voice, a hiss, a bass line and a clatter measure. */
class VoiceFrameTest {

    private val features = VoiceFrameFeatures(SAMPLE_RATE)

    /** 20 ms of a voice-like sound: a 120 Hz pitch under formants, wobbling at the syllable rate. */
    private fun voice(ms: Int, amp: Float = 0.2f): VoiceFrame {
        val count = ms * SAMPLE_RATE / 1000
        val raw = DoubleArray(count)
        for (i in raw.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val syllable = 0.6 + 0.4 * sin(2 * PI * 4 * t)
            var sample = 0.0
            for (harmonic in 1..28) {
                val hz = 120.0 * harmonic
                // Two formants, as a voice has: they are what puts its energy in the speech band.
                val shape = 1.0 / (1.0 + ((hz - 700) / 400).let { it * it }) +
                    0.7 / (1.0 + ((hz - 1_800) / 600).let { it * it })
                sample += shape * sin(2 * PI * hz * t)
            }
            raw[i] = sample * syllable
        }
        // Scaled to the wanted level instead of clipped: a squared-off frame is distortion, not a voice.
        val peak = raw.maxOf { kotlin.math.abs(it) }
        val scale = if (peak > 0) amp * 32_767 * 0.9 / peak else 0.0
        val samples = ShortArray(count) { (raw[it] * scale).toInt().toShort() }
        return features.of(samples, samples.size, amp)
    }

    /** 20 ms of white noise: a hiss, a fan, or a cymbal, with no pitch to speak of. */
    private fun hiss(ms: Int, amp: Float = 0.2f, seed: Int = 7): VoiceFrame {
        val random = Random(seed)
        val samples = ShortArray(ms * SAMPLE_RATE / 1000) {
            ((random.nextFloat() * 2 - 1) * amp * 32_767).toInt().toShort()
        }
        return features.of(samples, samples.size, amp)
    }

    /** 20 ms of a bass line: a phone speaker's music, down where a voice has nothing. */
    private fun bass(ms: Int, amp: Float = 0.2f): VoiceFrame {
        val samples = ShortArray(ms * SAMPLE_RATE / 1000) {
            (sin(2 * PI * 85 * it / SAMPLE_RATE) * amp * 32_767).toInt().toShort()
        }
        return features.of(samples, samples.size, amp)
    }

    @Test
    fun aVoiceKeepsItsEnergyInTheSpeechBand() {
        val frame = voice(20)
        assertTrue(frame.speechBand!! > 0.35f, "band ${frame.speechBand}")
        assertTrue(frame.flatness!! < 0.55f, "flatness ${frame.flatness}")
    }

    @Test
    fun aHissIsTooFlatToBeAVoice() {
        val frame = hiss(20)
        assertTrue(frame.flatness!! > 0.55f, "flatness ${frame.flatness}")
    }

    @Test
    fun aBassLineIsBelowTheSpeechBand() {
        val frame = bass(20)
        assertTrue(frame.speechBand!! < 0.35f, "band ${frame.speechBand}")
    }

    @Test
    fun silenceMeasuresAsItselfAndNeverAsAVoice() {
        val frame = features.of(ShortArray(320), 320, 0f)
        assertEquals(0f, frame.level)
        assertEquals(0f, frame.speechBand)
        assertEquals(0f, frame.flatness)
    }

    /** Feeds [levels] as 20 ms frames of one face; the time the recording would end, or null if it wouldn't. */
    private fun endsAt(frames: List<VoiceFrame>, activity: VoiceActivity = VoiceActivity()): Long? {
        val detector = EndOfSpeech(activity)
        frames.forEachIndexed { i, frame -> if (detector.onFrame(frame, (i + 1) * 20L)) return (i + 1) * 20L }
        return null
    }

    private fun voiceFrames(ms: Long, amp: Float = 0.6f) = List((ms / 20).toInt()) { voice(20, amp) }
    private fun hissFrames(ms: Long, amp: Float = 0.12f) = List((ms / 20).toInt()) { hiss(20, amp, it + 1) }
    private fun bassFrames(ms: Long, amp: Float = 0.12f) = List((ms / 20).toInt()) { bass(20, amp) }

    @Test
    fun aSoundtrackOfHissAndBassNeverStartsATurn() {
        val detector = EndOfSpeech(VoiceActivity(idleMs = 4_000))
        var ended = 0L
        for (i in 1..300) {
            val frame = when (i % 2) {
                0 -> hiss(20, 0.3f, i)
                else -> bass(20, 0.3f)
            }
            if (detector.onFrame(frame, i * 20L)) {
                ended = i * 20L
                break
            }
        }
        assertEquals(4_000L, ended)
        assertFalse(detector.heardSpeech)
    }

    @Test
    fun aVoiceOverTheSameSoundtrackStartsAndEndsATurn() {
        val frames = hissFrames(2_000) + voiceFrames(1_500) + hissFrames(3_000)
        val end = endsAt(frames)
        // The voice holds 1.5 s from 2 s; the pause after it ends the recording at 4.77 s.
        assertTrue(end != null && end in 4_700L..4_900L, "ended at $end")
    }

    /** Confirming a word rather than a frame: one syllable of a film is over before the bar is met. */
    @Test
    fun aSingleSyllableDoesNotCountAsSomeoneTalking() {
        val detector = EndOfSpeech(VoiceActivity())
        val clatter = hissFrames(1_000) + voiceFrames(100) + hissFrames(1_000)
        clatter.forEachIndexed { i, frame -> detector.onFrame(frame, (i + 1) * 20L) }
        assertFalse(detector.heardSpeech)
    }

    /** A room that is loud from the first second must not pass for its own silence. */
    @Test
    fun aLoudRoomFromTheFirstSecondIsNotItsOwnSilence() {
        val detector = EndOfSpeech(VoiceActivity(idleMs = 4_000))
        var ended = 0L
        for (i in 1..250) {
            // A steady, loud room: no voice in it, but well above the fixed speech level.
            if (detector.onFrame(hiss(20, 0.45f, i), i * 20L)) {
                ended = i * 20L
                break
            }
        }
        assertEquals(4_000L, ended)
        assertFalse(detector.heardSpeech)
    }

    /** A sentence longer than the noise window must not raise the bar over the speaker's own head. */
    @Test
    fun asLongAsSomeoneTalksTheRoomIsStillTheRoom() {
        val detector = EndOfSpeech(VoiceActivity(idleMs = 0))
        var ended = 0L
        // Six seconds of continuous speech, then a quiet room: far longer than the 3 s window.
        val frames = hissFrames(1_000) + voiceFrames(6_000) + hissFrames(3_000)
        for ((i, frame) in frames.withIndex()) {
            if (detector.onFrame(frame, (i + 1) * 20L)) {
                ended = (i + 1) * 20L
                break
            }
        }
        assertTrue(detector.heardSpeech)
        assertTrue(ended in 8_200L..8_400L, "ended at $ended")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
