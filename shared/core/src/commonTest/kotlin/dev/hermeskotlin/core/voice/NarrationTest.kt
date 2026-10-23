package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The narrator's cadence and its words, driven off a clock the test moves: no device, no gateway and no waiting.
 * The thought-driven contract: announces when thoughts change, throttled to at most once every 3 seconds so
 * rapid transient thoughts (< 3s) are skipped, and stays quiet while a long thought continues.
 */
class NarrationTest {

    private var now = 0L

    private fun narration() = Narration({ now })

    /** The loop, for [span]: what it said, and when. */
    private fun Narration.run(span: Long, stepMs: Long = 100L, activity: () -> NarrationActivity): List<Pair<Long, String>> {
        val said = mutableListOf<Pair<Long, String>>()
        while (now <= span) {
            val act = activity()
            line(act)?.let { said += now to it }
            val wait = waitMs(act)
            val step = if (wait != null && wait in 1..stepMs) wait else stepMs
            now += step
        }
        return said
    }

    @Test
    fun aThoughtPersistingThreeSecondsIsAnnouncedAtThreeSeconds() {
        val narration = narration()
        val tool = NarrationActivity(tool = "web_search", detail = "Chicago weather")

        // Before 3 seconds, the thought is still qualifying.
        assertEquals(3_000L, narration.waitMs(tool))
        now = 2_999
        assertNull(narration.line(tool))
        now = 3_000
        assertEquals("Searching the web for Chicago weather.", narration.line(tool))
        // Once spoken, no wait until the thought changes.
        assertNull(narration.waitMs(tool))
    }

    @Test
    fun aThoughtShorterThanThreeSecondsIsSkipped() {
        val narration = narration()
        val first = NarrationActivity(tool = "terminal")
        val second = NarrationActivity(tool = "web_search")

        // First tool runs for only 2 seconds (too fast, skipped), second runs for 4 seconds.
        val spoken = narration.run(7_000) { if (now < 2_000) first else second }
        assertEquals(
            listOf(5_000L to "Searching the web for that."),
            spoken,
        )
    }

    @Test
    fun aLongThoughtRemainsQuietWithoutTimerRepetition() {
        val narration = narration()
        val tool = NarrationActivity(tool = "terminal")

        // A command running for a full minute is announced once at 3s and never repeats on a clock.
        assertEquals(
            listOf(3_000L to "Running a command now."),
            narration.run(60_000) { tool },
        )
    }

    @Test
    fun rapidThoughtsAreSkippedUntilOnePersists() {
        val narration = narration()
        val thinking = NarrationActivity(thinking = "thinking")
        val ruminating = NarrationActivity(thinking = "ruminating")
        val formulating = NarrationActivity(thinking = "formulating")
        val command = NarrationActivity(tool = "terminal")

        // Intermediate thoughts last only 800ms each, then command persists from 2400ms onward.
        val spoken = narration.run(7_000) {
            when {
                now < 800 -> thinking
                now < 1600 -> ruminating
                now < 2400 -> formulating
                else -> command
            }
        }

        assertEquals(
            listOf(5_400L to "Running a command now."),
            spoken,
        )
    }

    @Test
    fun subsequentThoughtIsAnnouncedAfterThreeSeconds() {
        val narration = narration()
        val think = NarrationActivity(thinking = "thinking")
        val tool = NarrationActivity(tool = "terminal")
        val wrap = NarrationActivity(wrappingUp = true)

        // Thinking from 0..5s (spoken at 3s), tool from 5..12s (spoken at 8s), wrapping up from 12..18s (spoken at 15s).
        val spoken = narration.run(18_000) {
            when {
                now < 5_000 -> think
                now < 12_000 -> tool
                else -> wrap
            }
        }

        assertEquals(
            listOf(
                3_000L to "Thinking now.",
                8_000L to "Running a command now.",
                15_000L to "Wrapping up now.",
            ),
            spoken,
        )
    }

    @Test
    fun twoConsecutiveThoughtsOfTheSameKindDoNotRepeatTheExactSameWords() {
        val narration = narration()
        val first = NarrationActivity(tool = "terminal", detail = "ls -la")
        val second = NarrationActivity(tool = "terminal", detail = "pwd")

        assertEquals(
            listOf(3_000L to "Running a command now.", 8_000L to "Working on it now."),
            narration.run(10_000) { if (now < 5_000) first else second },
        )
    }

    @Test
    fun aTurnWithNothingToGoOnSaysNothing() {
        val narration = narration()
        assertTrue(narration.run(120_000) { NarrationActivity() }.isEmpty())
    }

    @Test
    fun aToolIsSaidTheWayItWouldBeSpoken() {
        assertEquals("Searching the web for that.", narrationLine(NarrationActivity(tool = "web_search")))
        assertEquals("Running the tests now.", narrationLine(NarrationActivity(tool = "run_tests")))
        assertEquals("Running a command now.", narrationLine(NarrationActivity(tool = "terminal")))
        assertEquals("Editing the code now.", narrationLine(NarrationActivity(tool = "patch")))
        assertEquals("Reading through the files now.", narrationLine(NarrationActivity(tool = "read_file")))
        assertEquals("Handing that to a subagent.", narrationLine(NarrationActivity(tool = "delegate_task")))
        // An MCP tool is read past its server's name.
        assertEquals(
            "Searching the web for melk.",
            narrationLine(NarrationActivity(tool = "mcp__gateway__ah_search_products", detail = "melk")),
        )
        // A tool this doesn't know is working on whatever it was given, in words.
        assertEquals("Working on the build now.", narrationLine(NarrationActivity(tool = "bake_the_build", detail = "the build")))
    }

    @Test
    fun thinkingAndWrappingUpPhrasing() {
        assertEquals("Thinking now.", narrationLine(NarrationActivity(thinking = "thinking")))
        assertEquals("Ruminating now.", narrationLine(NarrationActivity(thinking = "ruminating (1/3)")))
        assertEquals("Musing now.", narrationLine(NarrationActivity(thinking = "musing")))
        assertEquals("Formulating now.", narrationLine(NarrationActivity(thinking = "formulating")))
        assertEquals("Preparing now.", narrationLine(NarrationActivity(thinking = "preparing")))
        assertEquals("Wrapping up now.", narrationLine(NarrationActivity(wrappingUp = true)))
        assertEquals("Finishing up now.", narrationLine(NarrationActivity(wrappingUp = true), other = true))
    }

    @Test
    fun aStatusLineIsSaidAsTheSentenceItIs() {
        assertEquals("Compacting the conversation.", narrationLine(NarrationActivity(status = "compacting the conversation")))
        // Markup and arguments aren't words: what's left of them is, or nothing is.
        assertEquals(
            "Searching the web for that.",
            narrationLine(NarrationActivity(tool = "web_search", detail = """{"query":"x","limit":3}""")),
        )
        assertNull(narrationLine(NarrationActivity()))
    }
}
