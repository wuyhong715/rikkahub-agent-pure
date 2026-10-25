package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec

/**
 * 批 B — the neutral thing a trigger family schedules and fires.
 *
 * The trigger infrastructure originally spoke [me.rerere.rikkahub.workflow.model.WorkflowDefinition]
 * end to end, which tied it to the (now retired) workflow engine. Batch B moves the same
 * receivers / WorkManager backend under the mission scheduler, so families only need the
 * three fields they ever used: a stable id, the spec to match, and the row's update stamp
 * (used by the time family to notice a rescheduled trigger).
 *
 * Workflow triggers still adapt their definitions to this type so the old code keeps
 * compiling while it is being retired.
 */
data class TriggerBinding(
    /** Mission id or workflow id — whatever owns the trigger. */
    val id: String,
    val trigger: TriggerSpec,
    /** Last update of the owning row; a change forces the time family to reschedule. */
    val updatedAtMs: Long = 0L,
    /**
     * The next fire already persisted for this binding (`missions.next_run_at`), when there is
     * one. The time-cron family uses it as the initial delay instead of recomputing from "now":
     * a cold start re-registers every work request, and recomputing would restart the countdown
     * each time the app is opened.
     */
    val nextRunAtMs: Long? = null,
)