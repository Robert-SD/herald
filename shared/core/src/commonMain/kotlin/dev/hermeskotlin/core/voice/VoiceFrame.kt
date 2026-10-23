package dev.hermeskotlin.core.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What one frame of the microphone holds beside how loud it is: enough of its spectrum to tell a voice from the
 * room around it. A film's soundtrack, a fan, a road's hiss and a passing lorry are all as loud as someone
 * talking, so the level alone cannot say which of them is the person.
 */
data class VoiceFrame(
    /** RMS of the frame, 0..1, scaled so 1.0 is loud speech, as the recorders already take it. */
    val level: Float,
    /** The share of the frame's energy sitting in 300–3400 Hz; null when the caller has no spectrum to offer. */
    val speechBand: Float? = null,
    /** How flat the frame's spectrum is, 0 for a pure tone to 1 for white noise; null without a spectrum. */
    val flatness: Float? = null,
)

/**
 * Measures [VoiceFrame]s from 16 kHz mono PCM: the level, and two cheap spectral tests that a voice passes where a
 * soundtrack mostly doesn't. Speech carries its energy in 300–3400 Hz, and its harmonics leave the spectrum
 * uneven; a hiss spreads flat across every bin, and music leans on its bass.
 *
 * The transform is a plain 512-point FFT over a Hann window, which is a fraction of a millisecond on any phone
 * that runs this app. Frames shorter than that are zero-padded, which the window keeps harmless.
 */
class VoiceFrameFeatures(sampleRate: Int = 16_000) {

    private val rate = sampleRate
    private val hann = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / FFT_SIZE) }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val lowBin = (SPEECH_LOW_HZ * FFT_SIZE / rate).toInt().coerceAtLeast(1)
    private val highBin = (SPEECH_HIGH_HZ * FFT_SIZE / rate).toInt().coerceAtMost(FFT_SIZE / 2 - 1)

    /** The features of [pcm] up to [read] samples, whose level the caller has already worked out. */
    fun of(pcm: ShortArray, read: Int, level: Float): VoiceFrame {
        val n = minOf(read, FFT_SIZE)
        if (n < 2) return VoiceFrame(level)
        re.fill(0f)
        im.fill(0f)
        for (i in 0 until n) re[i] = pcm[i] / 32_768f * hann[i]
        transform(re, im)

        var total = 0.0
        var band = 0.0
        var magnitude = 0.0
        var logarithm = 0.0
        val bins = FFT_SIZE / 2
        for (b in 1 until bins) {
            val mag = sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())
            val energy = mag * mag
            total += energy
            if (b in lowBin..highBin) band += energy
            magnitude += mag
            logarithm += ln(mag + FLOOR)
        }
        if (total <= 0.0 || magnitude <= 0.0) return VoiceFrame(level, 0f, 0f)
        val flat = exp(logarithm / (bins - 1)) / (magnitude / (bins - 1))
        return VoiceFrame(level, (band / total).toFloat(), flat.toFloat().coerceIn(0f, 1f))
    }

    /** In-place radix-2 FFT of [re] and [im], which are both [FFT_SIZE] long. */
    private fun transform(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val step = -2.0 * PI / size
            for (start in 0 until FFT_SIZE step size) {
                for (k in 0 until size / 2) {
                    val angle = step * k
                    val wr = cos(angle).toFloat()
                    val wi = sin(angle).toFloat()
                    val a = start + k
                    val b = a + size / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            size = size shl 1
        }
    }

    private companion object {
        const val FFT_SIZE = 512
        const val SPEECH_LOW_HZ = 300.0
        const val SPEECH_HIGH_HZ = 3_400.0
        const val FLOOR = 1e-9
    }
}
