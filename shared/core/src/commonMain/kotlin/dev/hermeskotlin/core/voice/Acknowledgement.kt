package dev.hermeskotlin.core.voice

import kotlin.random.Random

/**
 * What the voice says the moment a sentence is taken, before the wait for the reply is narrated: a short word
 * that it heard you. Several, so the same one isn't said turn after turn.
 */
val voiceAcknowledgements: List<String> = listOf(
    "Got it.",
    "Okay.",
    "Right.",
    "Understood.",
    "Sure.",
    "Noted.",
    "Alright.",
    "On it.",
    "Got you.",
    "Mm-hmm.",
)

/** One of [voiceAcknowledgements], at random; [random] lets a test drive the choice. */
fun acknowledgement(random: Random = Random.Default): String =
    voiceAcknowledgements[random.nextInt(voiceAcknowledgements.size)]
