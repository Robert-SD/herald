package dev.hermeskotlin.core.voice

/**
 * What the phone actually gave the microphone a barge-in listens on: whether echo cancelling and noise
 * suppression were offered, and whether they switched on. Both are best-effort — a device whose audio HAL
 * doesn't expose them records plainly and says nothing — so this is what the trace prints, and what a
 * diagnostics screen would show, instead of a listener having to guess why a cut-in never trips.
 */
data class VoiceEffects(
    val echoCancelAvailable: Boolean = false,
    val echoCancelEnabled: Boolean = false,
    val noiseSuppressAvailable: Boolean = false,
    val noiseSuppressEnabled: Boolean = false,
) {
    /** True when the phone cancels the voice coming out of the speaker, so a cut-in beats the person, not an echo. */
    val cancelsEcho: Boolean get() = echoCancelAvailable && echoCancelEnabled

    /** "aec offered=yes on=yes · ns offered=yes on=no" — one line, for logcat and bug reports. */
    override fun toString(): String {
        fun mark(value: Boolean) = if (value) "yes" else "no"
        return "aec offered=${mark(echoCancelAvailable)} on=${mark(echoCancelEnabled)} · " +
            "ns offered=${mark(noiseSuppressAvailable)} on=${mark(noiseSuppressEnabled)}"
    }
}
