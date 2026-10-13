package me.rerere.rikkahub.shizuku

/**
 * What the live Shizuku (or Sui) broker runs as — i.e. the privilege our shell tools actually
 * execute with, not a cosmetic label.
 *
 * The Shizuku server can be started with ADB (UID 2000, the same level `adb shell` gets) or with
 * root (UID 0). Sui — the Magisk module by the same author — provides the same API with a root
 * UID and no Shizuku app at all. `Shizuku.getUid()` reports the *server's* UID, and the user
 * service we bind via `Shizuku.bindUserService` is spawned by that server and inherits its UID.
 *
 * Verified on device (2026-10-13, ADB-started broker): `shizuku_server` and this app's
 * `<app>:shizuku` service process both run as `uid=2000(shell)` under `u:r:shell:s0`, and
 * `shizuku_exec` → `id -u` returns `2000`. So the mapping below is the real privilege, and a
 * root-started broker would make the same path run as `uid=0`.
 *
 * Deliberately pure (no Shizuku SDK, no Android) so the UID → level mapping is unit-testable
 * without a live binder; [ShizukuManager] owns the fallible acquisition and the SDK guard.
 */
enum class PrivilegeLevel {
    /** No live broker, or a UID this app does not model (neither root nor shell). */
    UNKNOWN,

    /** UID 2000 — the shell / ADB level. */
    SHELL,

    /** UID 0 — root. */
    ROOT,
    ;

    companion object {
        /** Android's `shell` UID: what `adb shell` and an ADB-started Shizuku server run as. */
        const val SHELL_UID: Int = 2000

        /** `root`. */
        const val ROOT_UID: Int = 0

        /**
         * Map a broker UID to a level.
         *
         * Anything we do not explicitly recognise — a null UID (no binder, or the read threw) or
         * an unexpected value — degrades to [UNKNOWN]. We never *guess* root: only a literal
         * [ROOT_UID] is reported as [ROOT].
         */
        fun fromUid(uid: Int?): PrivilegeLevel = when (uid) {
            null -> UNKNOWN
            ROOT_UID -> ROOT
            SHELL_UID -> SHELL
            else -> UNKNOWN
        }
    }
}
