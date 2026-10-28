package me.rerere.rikkahub.runtime

/**
 * Graded escalation for repeated identical tool calls.
 *
 * [LoopGuard] answers one question — "has this exact call already run N times this turn" — and its
 * answer is binary: run it, or refuse it with a `loop_detected` envelope. That is a cost safety net,
 * not a way out of the loop: a model that receives a bare refusal usually rephrases the same call
 * (one extra field, different whitespace) or simply retries it, because nothing told it what to do
 * instead. The turn keeps burning tokens while the transcript fills with refusals.
 *
 * This object adds the missing middle. The ladder:
 *
 * | streak | grade | execution | what the model is told |
 * |---|---|---|---|
 * | 3 | NUDGE | runs | "state what new information you expect from the next one" |
 * | 5 | ESCALATE | refused | pick one of three concrete exits |
 * | 8 | HARD_STOP | refused | this is a blocker; write it up, text only |
 *
 * Thresholds follow Kimi Code CLI's repeat breaker (3 / 5 / 8, `toolDedupeService.ts`), which is the
 * only implementation we found that treats "the model is circling" as a first-class state rather
 * than as a quota to enforce. Allowing the third call through and asking it to justify the fourth is
 * the point: the cheapest fix for a loop is the model noticing it is in one.
 *
 * Deliberately does NOT force-end the turn at [RepeatGrade.HARD_STOP]. Two reasons, both structural:
 * the loop's history must keep every assistant `tool_call` paired with a `tool_result`, and cutting
 * the turn there throws away the model's closing text — the only thing the user actually reads. Turn
 * termination stays with `MAX_LOOP_GUARD_TRIPS_PER_TURN`; this object only decides whether *this*
 * call runs and what the model hears about it.
 *
 * Pure: no Android, no I/O, no clocks. The caller supplies the streak (which already carries
 * LoopGuard's act-observe reset and freshness-TTL bypass) so both rules stay independently testable.
 */
internal enum class RepeatGrade { NONE, NUDGE, ESCALATE, HARD_STOP }

/** One repeat-breaker verdict for a single proposed tool call. */
internal data class RepeatDecision(
    /** How many times this exact signature would have run in a row, this call included. */
    val streak: Int,
    val grade: RepeatGrade,
    /**
     * LoopGuard's freshness-TTL bypass fired: this is a deliberate re-read of a real-time value,
     * not a loop. Every grade is suppressed, and the call runs.
     */
    val refreshed: Boolean = false,
    /** Model-facing `<system-reminder>` text, emitted only on the exact threshold that set [grade]. */
    val reminder: String? = null,
) {
    /** Whether the call must be refused instead of executed. */
    val blocked: Boolean get() = grade == RepeatGrade.ESCALATE || grade == RepeatGrade.HARD_STOP
}

/** The refusal envelope's two fields, returned instead of a hand-built JSON object. */
internal data class RepeatBlock(val code: String, val recovery: String)

internal object RepeatBreaker {

    /** Third identical call in a row: it still runs, but the model must justify the next one. */
    const val NUDGE_AT = 3

    /** Fifth: refused, with three concrete exits to choose from. */
    const val ESCALATE_AT = 5

    /** Eighth: refused, and stated as a blocker to report rather than a problem to solve. */
    const val HARD_STOP_AT = 8

    /**
     * Grade one proposed call.
     *
     * @param priorOccurrences how many times this exact signature already ran this turn — for
     *   observation tools, only since the last intervening action (see [LoopGuard]); this is why the
     *   streak is not simply a call counter.
     * @param refreshed LoopGuard's freshness-TTL bypass verdict for this call.
     */
    fun decide(
        priorOccurrences: Int,
        refreshed: Boolean = false,
        nudgeAt: Int = NUDGE_AT,
        escalateAt: Int = ESCALATE_AT,
        hardStopAt: Int = HARD_STOP_AT,
    ): RepeatDecision {
        require(nudgeAt in 1..escalateAt && escalateAt <= hardStopAt) {
            "repeat thresholds must be ordered: 1 <= nudge <= escalate <= hard-stop"
        }
        val streak = priorOccurrences + 1
        if (refreshed) return RepeatDecision(streak = streak, grade = RepeatGrade.NONE, refreshed = true)

        val grade = when {
            streak >= hardStopAt -> RepeatGrade.HARD_STOP
            // Once escalated, every further repeat is refused — not just the one that crossed the
            // line. Re-opening the door one call later would let a looping model keep spending
            // tokens on a refusal it never reads.
            streak >= escalateAt -> RepeatGrade.ESCALATE
            streak == nudgeAt -> RepeatGrade.NUDGE
            else -> RepeatGrade.NONE
        }
        // Reminders fire once per rung. Past the hard stop every repeat is still refused, but
        // repeating the text warning step after step only spends tokens on a model that is past
        // reading it.
        val reminder = when (streak) {
            nudgeAt -> nudgeReminder(streak)
            escalateAt -> escalateReminder(streak)
            hardStopAt -> hardStopReminder(streak)
            else -> null
        }
        return RepeatDecision(streak = streak, grade = grade, reminder = reminder)
    }

    /**
     * The refusal a blocked call returns as its tool result.
     *
     * ESCALATE keeps the route hints the old unconditional `loop_detected` envelope carried (they
     * were earned from real failures — fighting a browser URL bar, typing into Termux); HARD_STOP
     * drops them, because by the eighth identical call the problem is no longer "wrong tool for the
     * job", it is "the premise is wrong and the model will not let go of it".
     */
    fun blockFor(grade: RepeatGrade, toolName: String, streak: Int): RepeatBlock = when (grade) {
        RepeatGrade.ESCALATE -> RepeatBlock(
            code = "repeat_blocked",
            recovery = "You have called `$toolName` with these exact arguments $streak times in this turn " +
                "without producing new information, so it was not run again. Pick exactly one, and say " +
                "which before acting: (1) Falsification check — run the cheapest test that could " +
                "conclusively disprove your current approach. (2) Missing input — tell the user " +
                "precisely what information or decision you need, and ask for it. (3) Conclude — give " +
                "your best answer from the evidence already gathered, listing what remains uncertain. " +
                "If the call itself is the problem, change the route rather than the wording: for " +
                "`search X in chrome` use open_url(\"https://www.google.com/search?q=X\") instead of " +
                "driving the URL bar with set_text; for terminal work use termux_run_command instead of " +
                "typing into Termux; for a stale screen read, take a screenshot first.",
        )
        RepeatGrade.HARD_STOP -> RepeatBlock(
            code = "repeat_hard_stop",
            recovery = "This is the ${streak}th identical `$toolName` call in this turn. The approach is " +
                "not working and repeating it cannot fix that. Stop calling tools and write your final " +
                "response now: what you were trying to do, what you tried and what each attempt " +
                "established, where you are blocked, and the specific information or decision you need " +
                "from the user. Text only.",
        )
        // Not reachable through [decide]; kept total so a future caller passing a stale grade cannot
        // silently produce a success-shaped envelope.
        RepeatGrade.NUDGE, RepeatGrade.NONE -> RepeatBlock(
            code = "repeat_noted",
            recovery = "This call has already run $streak times in this turn.",
        )
    }

    private fun nudgeReminder(streak: Int): String = reminder(
        "You have now issued the same tool call $streak times in a row with identical arguments. " +
            "Before your next call, write one sentence stating what new information you expect it to " +
            "produce. Then act on that sentence: if it names something the last result does not " +
            "already give you, take the action that provides it; otherwise continue with the evidence " +
            "you already have. If the call you just made did return something new, ignore this and " +
            "carry on.",
    )

    private fun escalateReminder(streak: Int): String = reminder(
        "The same tool call has now been issued $streak times in a row, and the last one was not run. " +
            "Choose exactly one and state your choice before acting: (1) Falsification check — the " +
            "cheapest test that could disprove your current approach. (2) Missing input — the precise " +
            "information or decision you need from the user. (3) Conclude — your best result from the " +
            "evidence already gathered, with the uncertainties named.",
    )

    private fun hardStopReminder(streak: Int): String = reminder(
        "The same tool call has been issued $streak times in a row. Further identical calls will not " +
            "run. Write your final response now, without any further tool calls: the blocker, every " +
            "approach you tried and what it established, and the specific information or decision you " +
            "need from the user to unblock progress. Text only.",
    )

    /**
     * Wrap a harness directive so the model cannot mistake it for the user speaking — the same
     * framing Moxw's context-budget reminder uses, and the one the system prompt is told to treat as
     * authoritative.
     */
    private fun reminder(body: String): String =
        "<system-reminder>\n$body\n</system-reminder>"
}
