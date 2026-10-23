package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.time.TimeSource

/**
 * Says what the narrator decided should be said, out loud. The chained voice chat speaks it with the gateway's
 * voice, the one the reply is read in; a test has its own, so the cadence is driven without a gateway.
 */
fun interface NarrationSpeaker {
    suspend fun say(line: String)
}

/**
 * What Hermes is at, as the narrator has it: the tool it's running (and what for), a status update,
 * thinking/ruminating, or wrapping up. Two of these are one activity when every part of them is,
 * which is how a line already said is told from news.
 */
data class NarrationActivity(
    val tool: String? = null,
    /** What the tool is on, when the gateway said it in words rather than arguments. */
    val detail: String? = null,
    /** The turn's latest `status.update` line. */
    val status: String? = null,
    /** When the bot is thinking or ruminating before or between actions. */
    val thinking: String? = null,
    /** When tools have run in this turn and finished, and the agent is preparing the final reply. */
    val wrappingUp: Boolean = false,
) {
    /** Nothing to go on yet: no tool started, no status, and not running. */
    val quiet: Boolean get() = tool == null && status == null && thinking == null && !wrappingUp

    /** What makes two of these the same thought activity. */
    val key: String get() = when {
        wrappingUp -> "wrapping_up"
        tool != null -> "tool:$tool:${detail.orEmpty()}"
        status != null -> "status:$status"
        thinking != null -> "thinking:${thinkingCategory(thinking)}"
        else -> ""
    }
}

/**
 * Milliseconds from a fixed point that never goes back. The narrator measures gaps on this, not the time of
 * day, so a clock that jumps on a network or daylight-saving change can't make it wait or fire twice.
 */
fun monotonicMs(): Long = MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds

private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()

/**
 * Thought-driven progress narration: announces what Hermes is doing whenever its thoughts change (thinking,
 * running commands, wrapping up), throttled so rapid transient thoughts (faster than [minGapMs]) are skipped.
 *
 * It stays peaceful while a long thought continues without repetitive clock ticking, and speaks the moment
 * the bot enters a new thought phase.
 */
class Narration(
    private val clock: () -> Long = { monotonicMs() },
    val minGapMs: Long = MIN_GAP_MS,
) {
    /** The key of the activity currently being tracked, and when it started. */
    private var currentKey: String? = null
    private var currentStartedAt: Long = 0L

    /** The key of the activity that was last spoken. */
    var saidKey: String? = null
        private set
    /** The last line said. */
    private var said: String? = null

    private fun track(activity: NarrationActivity): Long? {
        if (activity.quiet) {
            currentKey = null
            return null
        }
        val key = activity.key
        if (key.isEmpty() || key == saidKey) return null
        val now = clock()
        if (key != currentKey) {
            currentKey = key
            currentStartedAt = now
        }
        return (currentStartedAt + minGapMs - now).coerceAtLeast(0L)
    }

    /**
     * How long until the current thought qualifies to be spoken (has persisted for [minGapMs]),
     * or null if there is no pending thought or the current thought was already spoken.
     */
    fun waitMs(activity: NarrationActivity): Long? = track(activity)

    /**
     * Returns the line for [activity] if it represents an active thought that has persisted for at
     * least [minGapMs], skipping thoughts that change faster than [minGapMs].
     */
    fun line(activity: NarrationActivity): String? {
        val remaining = track(activity) ?: return null
        if (remaining > 0L) return null
        val base = narrationLine(activity) ?: return null
        val line = if (base == said) narrationLine(activity, other = true) ?: base else base
        said = line
        saidKey = activity.key
        return line
    }

    companion object {
        /** Minimum delay before a thought transition is announced; faster thoughts are skipped. */
        const val MIN_GAP_MS = 3_000L
    }
}

/**
 * What the turn running in [chat] is at, its reply starting at message [from]:
 * 1. Running tool (e.g. terminal, web search).
 * 2. Wrapping up (tools have completed and the final reply is being generated).
 * 3. Gateway status update line.
 * 4. Thinking/ruminating before or between actions.
 */
fun narrationActivity(chat: ChatState, from: Int): NarrationActivity {
    if (!chat.running) return NarrationActivity()

    val assistantMessages = chat.messages.drop(from).filterIsInstance<ChatMessage.Assistant>()
    val allTools = assistantMessages.flatMap { it.tools }
    val runningTool = allTools.lastOrNull { it.running }
    if (runningTool != null) return NarrationActivity(tool = runningTool.name, detail = detailOf(runningTool))
    if (allTools.isNotEmpty() && allTools.none { it.running }) return NarrationActivity(wrappingUp = true)
    if (chat.status?.isNotBlank() == true) return NarrationActivity(status = chat.status)
    val thinking = chat.thinkingFrame?.takeIf { it.isNotBlank() } ?: "thinking"
    return NarrationActivity(thinking = thinking)
}

/**
 * What [tool] is on, in words worth hearing: what the gateway calls it, else what it was given when that reads
 * as words (a query, a path) rather than as the arguments of a call.
 */
private fun detailOf(tool: ToolActivity): String? =
    tool.detail?.takeIf { it.isNotBlank() }
        ?: tool.input?.takeUnless { it.trimStart().let { text -> text.startsWith("{") || text.startsWith("[") } }

/**
 * A line for [activity] the way the voice chat says things: a sentence about what Hermes is at, not a label.
 * [other] is the alternative line to avoid repeating the exact same words in a row.
 */
fun narrationLine(activity: NarrationActivity, other: Boolean = false): String? {
    if (activity.wrappingUp) return if (other) "Finishing up now." else "Wrapping up now."
    if (other) return "Working on it now."
    activity.tool?.let { tool ->
        val subject = subject(activity.detail)
        val doing = doing(tool, subject)
        val tail = if (doing.tail.isEmpty()) "" else " ${doing.tail}"
        return "${doing.gerund.replaceFirstChar { it.uppercase() }}$tail."
    }
    activity.status?.let { status ->
        return spokenStatus(status)
    }
    activity.thinking?.let { thinking ->
        return spokenThinking(thinking)
    }
    return null
}

/** Translates thinking/ruminating frames into plain spoken words. */
private fun spokenThinking(thinking: String): String {
    val cleaned = thinking.replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""[^\w\s]"""), " ")
        .trim()
        .lowercase()
    return when {
        "ruminat" in cleaned -> "Ruminating now."
        "musing" in cleaned -> "Musing now."
        "formulat" in cleaned -> "Formulating now."
        "prepar" in cleaned -> "Preparing now."
        else -> "Thinking now."
    }
}

/** Category key for thinking frames so animated spinner variations don't re-trigger speech. */
private fun thinkingCategory(thinking: String): String {
    val cleaned = thinking.replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""[^\w\s]"""), " ")
        .trim()
        .lowercase()
    return when {
        "ruminat" in cleaned -> "ruminating"
        "musing" in cleaned -> "musing"
        "formulat" in cleaned -> "formulating"
        "prepar" in cleaned -> "preparing"
        else -> "thinking"
    }
}

/** A tool as it sounds while it runs: [gerund] after "Still", and the words that close the line when it's news. */
private class Doing(val gerund: String, val tail: String)

/**
 * How a tool sounds, from its name: the verb a person would use for it, not the tool's own. A name an MCP
 * server gave is read past the server's prefix (`mcp__gateway__ah_search_products` asks about products), and
 * one nothing here knows is "working on" whatever it is on, which is the honest thing to say.
 */
private fun doing(tool: String, subject: String?): Doing {
    val name = tool.substringAfterLast("__").lowercase()
    val words = name.split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
    fun any(vararg keys: String) = keys.any { it in words }
    return when {
        // A search of the web says what it's looking for; one of the phone's own files has nothing to add.
        any("search", "find", "lookup") ->
            if (any("file", "files", "session")) Doing("looking through the files", "now")
            else Doing("searching the web", if (subject != null) "for $subject" else "for that")
        any("extract", "browse", "browser", "fetch", "crawl", "scrape") -> Doing("looking that page up", "now")
        any("test", "tests", "pytest") -> Doing("running the tests", "now")
        any("execute", "code", "python", "script") -> Doing("running some code", "now")
        any("terminal", "shell", "bash", "exec", "command", "run") -> Doing("running a command", "now")
        any("write", "edit", "patch", "apply", "save", "replace") -> Doing("editing the code", "now")
        any("read", "view", "open", "load", "list", "file") -> Doing("reading through the files", "now")
        any("git") -> Doing("sorting out the repository", "now")
        any("delegate", "subagent", "agent", "task") -> Doing("handing that to a subagent", "")
        any("image", "picture", "draw", "render", "camera") -> Doing("making the picture", "now")
        any("todo", "plan", "goal", "cron") -> Doing("planning the steps", "now")
        any("memory", "note", "notes") -> Doing("checking its notes", "now")
        else -> Doing("working on ${subject ?: "it"}", "now")
    }
}

/**
 * [detail] as words a line can carry: one short, plain phrase. An address, a set of arguments or a wall of text
 * is nothing to hear, and a path is only its file's name.
 */
private fun subject(detail: String?): String? {
    val line = detail?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    if (line.any { it in SUBJECT_UNSAFE } || "://" in line) return null
    val words = line.substringAfterLast('/').replace(WHITESPACE, " ").trim().trimEnd('.', ',', ';', ':')
    if (words.isEmpty() || words.none { it.isLetterOrDigit() }) return null
    if (words.length <= SUBJECT_CHARS) return words
    return words.take(SUBJECT_CHARS).substringBeforeLast(' ').trimEnd().ifEmpty { words.take(SUBJECT_CHARS) } + "…"
}

/**
 * A `status.update` line as it should sound. The gateway writes these for the screen: markup and addresses go,
 * it's cut to a clause, and it ends like the sentence it is spoken as.
 */
private fun spokenStatus(status: String): String? {
    val text = speakableText(status).trim().trimEnd(':').trim()
    if (text.isEmpty()) return null
    val cut = if (text.length <= STATUS_CHARS) text else text.take(STATUS_CHARS).substringBeforeLast(' ').trimEnd() + "…"
    val sentence = cut.replaceFirstChar { it.uppercase() }
    return if (sentence.last() in SENTENCE_ENDS) sentence else "$sentence."
}

/** Long enough for a query or a file name, short enough to hear inside one breath. */
private const val SUBJECT_CHARS = 48
private const val STATUS_CHARS = 120
private const val SENTENCE_ENDS = ".!?…"
private const val SUBJECT_UNSAFE = "{}[]\"`<>|\\"
private val WHITESPACE = Regex("""\s+""")
