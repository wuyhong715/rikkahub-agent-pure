package me.rerere.rikkahub.runtime

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many generation turns are running in this process right now.
 *
 * The automation state ([AgentTurnTracker], [AutomationRecorder], the overlay, the temporary
 * screenshots) is process-wide, but turns are not: a `subagent_dispatch` child, a mission round, a
 * cron job and the user's own chat can all be generating at the same time — and the child usually
 * runs *inside* the parent's turn.
 *
 * Only the first turn may reset that state and only the last may tear it down. Without this split a
 * child turn wiped the parent's navigation history mid-automation, and on completion hid the
 * overlay the parent was using, force-returned to the app and deleted the screenshots the parent's
 * next request still needed.
 */
object ActiveTurns {

    private val active = AtomicInteger(0)

    /** @return true when this is the first active turn, i.e. the one that owns the reset. */
    fun begin(): Boolean = active.incrementAndGet() == 1

    /** @return true when this was the last active turn, i.e. the one that owns the teardown. */
    fun end(): Boolean = active.decrementAndGet() <= 0

    /** Active turn count, for tests and diagnostics. */
    fun count(): Int = active.get()

    /** Test hook: forget any leaked counts. */
    internal fun reset() {
        active.set(0)
    }
}
