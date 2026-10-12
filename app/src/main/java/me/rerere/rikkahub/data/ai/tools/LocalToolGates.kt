package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.agentdef.LocalToolGroups

/**
 * Moxw — which local-tool groups the user may still switch off, and which are simply always on.
 *
 * ## Why the switch is gone for most groups
 *
 * This product line surfaces tools through the embedding model: a turn receives `tool_search`, and
 * the tools it actually needs are retrieved by meaning (see `ToolRankFusion` / `ToolVectorIndex`).
 * Every group is in that catalogue either way, so leaving one out of `Assistant.localTools` never
 * hid anything from the model — it only made the catalogue smaller, and a smaller catalogue is not
 * a benefit when the ranking is what chooses. The switch was therefore answering a question nobody
 * was asking, while costing the user ~50 taps-away-from-default rows to answer it.
 *
 * So `Assistant.localTools` is redefined here: it now records **only** the [gated] groups. Every
 * other group is unconditionally part of the surface, whatever a stored payload says — that is what
 * "always on and cannot be turned off" means, and it is enforced in exactly one place,
 * [ToolSurfaceResolver.resolve], the single assembly point the app already routes every path
 * through (chat, rerun, fast path, cron, workflow, sub-agents).
 *
 * ## What still gets a switch
 *
 * A group whose tools cannot do anything unless the user first provides something the app cannot
 * provide for itself: an external app (Termux, Shizuku, the agent keyboard, a Telegram bot token),
 * a system-level service the user must enable by hand (the accessibility service, notification
 * access), or hardware (an NFC chip). Those are `gated`: if the prerequisite is absent, the group
 * is pure noise in the catalogue and in the vector index, so the user may say so.
 *
 * Everything that merely needs an ordinary runtime permission is **not** gated. Those permissions
 * are one system dialog each, they are already collected in Settings → Permissions, and a tool
 * that is called without its permission fails with a readable error rather than silently — which
 * is strictly better than a switch that hides the capability from the model forever.
 *
 * ## One consequence worth stating plainly
 *
 * The union is applied where the surface is assembled, so it cannot tell a stored assistant from a
 * sub-agent's derived one — a child built from an expert definition gets the same unconditioned
 * groups its parent does. An expert can therefore no longer narrow its *catalogue* by group; it
 * narrows by `Assistant.disabledLocalTools` (still honoured, still per exact tool name), which was
 * always the finer control anyway. That is consistent with the reason the group switch went away in
 * the first place, but it is a real behaviour change for the expert editor's group list and is
 * called out as such rather than left to be discovered.
 *
 * Pure: no Android, no `R`, no coroutines. `LocalToolGatesTest` runs it on a bare JVM.
 */
object LocalToolGates {

    /**
     * The groups a stored `localTools` list still controls, in the catalogue's reading order.
     *
     * Deliberately a small, explicit set rather than "everything with a `PermissionedSwitch`": the
     * page must be able to say *why* each of these is special, and a rule that silently grew with
     * every new tool group would be worse than no rule.
     */
    val gated: List<LocalToolOption> by lazy {
        LocalToolGroups.order(
            listOf(
                // Needs the accessibility service switched on by hand in system settings.
                LocalToolOption.ScreenAutomation,
                // Needs notification access granted by hand in system settings.
                LocalToolOption.NotificationListener,
                // Needs the Shizuku app running and its consent dialog accepted.
                LocalToolOption.Shizuku,
                // Needs the Termux app installed and allow-external-apps set inside it.
                LocalToolOption.Termux,
                // Needs the co-signed agent keyboard installed AND set as the active IME.
                LocalToolOption.KeyboardControl,
                // Needs an NFC chip — a device without one can never run these tools.
                LocalToolOption.Nfc,
                // Needs a bot token, which only the user can obtain from BotFather.
                LocalToolOption.TelegramBot,
            )
        )
    }

    private val gatedSet: Set<LocalToolOption> by lazy { gated.toSet() }

    /** Whether [option] keeps its enable switch (see the class doc for the criterion). */
    fun isGated(option: LocalToolOption): Boolean = option in gatedSet

    /** Every group that is part of the surface no matter what is stored, in catalogue order. */
    val alwaysOn: List<LocalToolOption> by lazy {
        LocalToolGroups.all.filterNot { it in gatedSet }
    }

    /**
     * The groups a turn's surface is actually assembled from: every non-gated group, plus the
     * gated ones the stored list still names. Deduplicated and catalogue-ordered, so two callers
     * holding the same stored list build the same surface.
     */
    fun effective(stored: Collection<LocalToolOption>): List<LocalToolOption> =
        LocalToolGroups.order(alwaysOn + stored)

    /**
     * The stored list reduced to the choices that still mean something — the gated groups. Used by
     * the tool page so a payload full of pre-redefinition, non-gated entries reads as "nothing to
     * switch" rather than as fifty stale switches that appear on.
     */
    fun gatedChoices(stored: Collection<LocalToolOption>): List<LocalToolOption> =
        LocalToolGroups.order(stored.filter { it in gatedSet })
}
