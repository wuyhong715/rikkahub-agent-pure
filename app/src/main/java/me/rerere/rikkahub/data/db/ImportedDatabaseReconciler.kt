package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reconciles a database file that was just restored from a backup so Room can open it.
 *
 * The fork added several tables (scheduled jobs, workflows, ssh hosts, telegram chats,
 * the agent-run ledger) on top of upstream RikkaHub, and it renumbered its Room schema so
 * the same version number no longer means the same thing in the two apps. A backup exported
 * from *upstream* RikkaHub therefore breaks a fresh restore in two ways:
 *  - it is missing the fork-only tables, so Room fails its integrity check or hits "no such
 *    table: scheduled_jobs" at first query (issue #8); and
 *  - it is stamped with upstream's user_version (24 for 2.4.x), which is LOWER than the
 *    fork's schema-equivalent version (27), so Room replays the fork's 24->25 / 25->26 /
 *    26->27 auto-migrations and re-ADDs columns the file already carries, crashing with
 *    "duplicate column name: custom_system_prompt" (issues #10, #11).
 * Either way the app crashes on the very first launch after the import.
 *
 * This step runs once, right after the restore writes `rikka_hub.db`, on the raw file before
 * Room touches it:
 *  - It creates any of the fork-only tables that are missing, empty, with the exact schema
 *    Room expects (copied from the exported schema's createSql), so the file looks like a
 *    clean agent install for those tables.
 *  - It backfills the indices and columns that Room would normally add via the auto-migrations
 *    between the restored version and [EXPECTED_VERSION] (see [BACKFILL_INDEX_DDL] and the
 *    `chat_model_id` column below). Those auto-migrations never run on the "already current"
 *    path, since it jumps straight to [EXPECTED_VERSION] instead of walking the chain, so
 *    anything they would have added has to be recreated here too (issue #60).
 *  - If the file is already at the fork's current schema (stamped at the matching version, or
 *    an upstream file whose shared tables already carry every modern column), it stamps Room's
 *    user_version and identity row to the fork's current values so Room opens the file with no
 *    migration. Without this Room either replays colliding migrations or rejects the foreign
 *    hash, even though every table is now present. The shared tables match column-for-column
 *    because the fork tracks upstream's schema, so trusting the hash is sound.
 *
 * If the backup is at an older version that is not yet schema-complete, Room runs its normal
 * migrations up to current and sets the identity itself. Nothing is pre-created on that path:
 * a migration chain creates each table at the version that introduced it, whereas the fork-only
 * DDL always writes the *current* shape — pre-creating `missions` there made the following
 * 34->35 / 35->36 auto-migrations ADD columns that already existed and crashed every restore of
 * a backup older than v32. Backups newer than the app are left untouched (Room reports the
 * downgrade).
 *
 * Best-effort: any failure here is logged and swallowed so a restore never half-breaks. The
 * worst case is the same pre-existing crash on next open, never data loss: there is no
 * destructive-migration fallback configured, so the restored rows always survive on disk.
 */
object ImportedDatabaseReconciler {

    private const val TAG = "DbReconciler"
    private const val DB_NAME = "rikka_hub"

    /**
     * Room's schema version and identity hash for [AppDatabase]. Both are copied verbatim
     * from app/schemas/me.rerere.rikkahub.data.db.AppDatabase/38.json (the identity hash also
     * appears in the generated AppDatabase_Impl RoomOpenDelegate). When the schema version is
     * bumped, update BOTH constants (and the table DDL below if the fork-only tables changed,
     * BACKFILL_INDEX_DDL if any entity gained/lost an index, and MODERN_COLUMN_SENTINELS if
     * newer *shared* conversation columns were added) or this reconciliation will silently stop
     * matching. `internal` so a JVM test can assert these stay in sync with the schema export.
     */
    internal const val EXPECTED_VERSION = 39
    internal const val EXPECTED_IDENTITY_HASH = "20763c65de6b15d789126bb0f05cde41"

    /**
     * Columns that a restored file must already have for its shared schema to be considered
     * byte-for-byte equal to the fork's current schema. They are exactly the columns the
     * fork's 24->25 / 25->26 / 26->27 auto-migrations add (custom_system_prompt at 25,
     * workspace_cwd at 26, folder_id at 27), so a file carrying all three would collide on
     * every one of those replays. Upstream 2.4.x carries all three; a genuine fork file below
     * v27 carries only a prefix, so it still migrates normally.
     */
    private val MODERN_COLUMN_SENTINELS = listOf("custom_system_prompt", "workspace_cwd", "folder_id")

    private const val CONTEXT_COMPACTION_DDL =
        "CREATE TABLE IF NOT EXISTS `conversation_compaction` (`conversation_id` TEXT NOT NULL, `summary` TEXT NOT NULL, `tail_start_node_id` TEXT, `source_end_node_id` TEXT NOT NULL, `summary_model_id` TEXT NOT NULL, `is_auto` INTEGER NOT NULL, `source_token_estimate` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /**
     * v31 adds this column to `workspaces` via [me.rerere.rikkahub.data.db.migrations.Migration_30_31].
     * That migration never runs on the "already current" path below, so it has to be backfilled
     * here too (issue #105), same reasoning as `chat_model_id` further down. DDL byte-identical
     * to the migration's.
     */
    private const val SHELL_COMPATIBILITY_MODE_DDL =
        "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"

    /**
     * v35 (批 A) adds six runtime columns to `missions` through the 34->35 auto-migration. A
     * v32..v34 moxw backup carries the table without them, and the "already current" stamp
     * below skips the auto-migration (same reasoning as [SHELL_COMPATIBILITY_MODE_DDL]), so
     * each missing column is added here. The DDL mirrors what Room's auto-migration emits; a
     * column guard keeps re-runs a no-op (bare ALTER ... ADD COLUMN is not idempotent).
     */
    private val MISSION_V35_COLUMN_DDL: List<Pair<String, String>> = listOf(
        "workspace_id" to "ALTER TABLE `missions` ADD COLUMN `workspace_id` TEXT",
        "approval_mode" to "ALTER TABLE `missions` ADD COLUMN `approval_mode` TEXT",
        "limits_json" to "ALTER TABLE `missions` ADD COLUMN `limits_json` TEXT",
        "resume_count" to "ALTER TABLE `missions` ADD COLUMN `resume_count` INTEGER NOT NULL DEFAULT 0",
        "last_run_id" to "ALTER TABLE `missions` ADD COLUMN `last_run_id` TEXT",
        "execution_state" to "ALTER TABLE `missions` ADD COLUMN `execution_state` TEXT NOT NULL DEFAULT 'idle'",
    )

    /**
     * v36 (批 B) adds the trigger / schedule columns. Same backup-restore backfill treatment:
     * a restored v35 file's `missions` table predates them.
     */
    private val MISSION_V36_COLUMN_DDL: List<Pair<String, String>> = listOf(
        "trigger_json" to "ALTER TABLE `missions` ADD COLUMN `trigger_json` TEXT",
        "trigger_enabled" to "ALTER TABLE `missions` ADD COLUMN `trigger_enabled` INTEGER NOT NULL DEFAULT 1",
        "cooldown_seconds" to "ALTER TABLE `missions` ADD COLUMN `cooldown_seconds` INTEGER NOT NULL DEFAULT 0",
        "max_runs_per_day" to "ALTER TABLE `missions` ADD COLUMN `max_runs_per_day` INTEGER",
        "last_fired_at" to "ALTER TABLE `missions` ADD COLUMN `last_fired_at` INTEGER",
        "fires_today" to "ALTER TABLE `missions` ADD COLUMN `fires_today` INTEGER NOT NULL DEFAULT 0",
        "fires_day" to "ALTER TABLE `missions` ADD COLUMN `fires_day` TEXT",
        "next_run_at" to "ALTER TABLE `missions` ADD COLUMN `next_run_at` INTEGER",
    )

    /**
     * v37 (批 C) adds `missions.workflow_path` — the workspace-relative .md playbook the
     * mission follows. Nullable, so a restored v36 file only needs the column added.
     */
    private val MISSION_V37_COLUMN_DDL: List<Pair<String, String>> = listOf(
        "workflow_path" to "ALTER TABLE `missions` ADD COLUMN `workflow_path` TEXT",
    )

    /**
     * v38 (上下文压缩 C 批, memory write gate) adds five columns to `MemoryEntity`: the record
     * kind, its creation time, an optional validity end, and the vector + model id the gate
     * needs to recognise a reworded duplicate.
     *
     * Backfilled here for the same reason as the mission columns: a restored v37 file carries
     * the old three-column shape, and the "already current" path below stamps straight to
     * [EXPECTED_VERSION] without walking the auto-migrations. `MemoryEntity` is an *upstream*
     * table, so it is never created by [FORK_ONLY_DDL] — hence the table-exists guard at the
     * call site, mirroring `workspaces`.
     */
    private val MEMORY_V38_COLUMN_DDL: List<Pair<String, String>> = listOf(
        "kind" to "ALTER TABLE `MemoryEntity` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'note'",
        "created_at" to "ALTER TABLE `MemoryEntity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0",
        "valid_to" to "ALTER TABLE `MemoryEntity` ADD COLUMN `valid_to` INTEGER",
        "embedding" to "ALTER TABLE `MemoryEntity` ADD COLUMN `embedding` BLOB",
        "embedding_model" to "ALTER TABLE `MemoryEntity` ADD COLUMN `embedding_model` TEXT",
    )

    /**
     * Fork-only tables absent from an upstream backup, with their exact v25 create + index
     * statements. Every statement is IF NOT EXISTS so running it against a genuine agent
     * backup (where the tables already exist) is a no-op.
     */
    internal val FORK_ONLY_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once', `description` TEXT, `tags` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `mode` TEXT NOT NULL, `scheduledAtMs` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `outcome` TEXT NOT NULL, `conversationId` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ssh_hosts` (`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `password` TEXT, `privateKey` TEXT, `passphrase` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `telegram_chats` (`chatId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastMessageAtMs` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1, `definitionJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `lastRunStatus` TEXT, `lastRunError` TEXT, `runsTodayCount` INTEGER NOT NULL DEFAULT 0, `runsTodayDate` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `workflow_runs` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` TEXT NOT NULL, `firedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `domain_id` TEXT NOT NULL, `parent_run_id` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `finished_at_ms` INTEGER, `last_error` TEXT, `metadata_json` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)",
        // v32 workbench tables (create statement refreshed to the v35 column set — a restored
        // pre-v35 file that already has the table is covered by the ALTER backfill below; a
        // file with no missions table at all gets the full current shape here).
        "CREATE TABLE IF NOT EXISTS `missions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `acceptance_json` TEXT NOT NULL, `acceptance_checked_json` TEXT NOT NULL, `conversation_id` TEXT, `assistant_id` TEXT, `artifact_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `completed_at` INTEGER, `workspace_id` TEXT, `approval_mode` TEXT, `limits_json` TEXT, `resume_count` INTEGER NOT NULL DEFAULT 0, `last_run_id` TEXT, `execution_state` TEXT NOT NULL DEFAULT 'idle', `trigger_json` TEXT, `trigger_enabled` INTEGER NOT NULL DEFAULT 1, `cooldown_seconds` INTEGER NOT NULL DEFAULT 0, `max_runs_per_day` INTEGER, `last_fired_at` INTEGER, `fires_today` INTEGER NOT NULL DEFAULT 0, `fires_day` TEXT, `next_run_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_missions_status` ON `missions` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_missions_conversation_id` ON `missions` (`conversation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_missions_updated_at` ON `missions` (`updated_at`)",
        // v33: mission_events is generalised into the unified run_events journal. The new
        // table is fork-only (absent from upstream backups), so it is pre-created here the
        // same way missions is; a v32 moxw backup that already carries mission_events is
        // dropped so the v32->33 auto-migration (skipped on the "already current" stamp path)
        // does not leave a stale, orphaned table behind. Every statement is idempotent.
        "CREATE TABLE IF NOT EXISTS `run_events` (`id` TEXT NOT NULL, `mission_id` TEXT, `conversation_id` TEXT, `run_id` TEXT, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT, `payload_json` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mission_id`) REFERENCES `missions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_run_events_mission_id_created_at` ON `run_events` (`mission_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_run_events_conversation_id_created_at` ON `run_events` (`conversation_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_run_events_run_id_created_at` ON `run_events` (`run_id`, `created_at`)",
        "DROP TABLE IF EXISTS `mission_events`",
        // v34 (P5): durable run breakpoints. Fork-only, so a restored backup needs it
        // pre-created for the same reason missions/run_events are.
        "CREATE TABLE IF NOT EXISTS `run_checkpoints` (`run_id` TEXT NOT NULL, `conversation_id` TEXT, `mission_id` TEXT, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `step_index` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `pending_tools` TEXT, `attempts` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`run_id`))",
        "CREATE INDEX IF NOT EXISTS `index_run_checkpoints_conversation_id` ON `run_checkpoints` (`conversation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_run_checkpoints_updated_at_ms` ON `run_checkpoints` (`updated_at_ms`)",
        // v39 (上下文压缩 B 批): durable facts extracted from conversation summaries. Fork-only
        // like missions/run_events, so a restored backup needs it pre-created; every statement is
        // idempotent. Index names and column order mirror what Room generates for the entity.
        "CREATE TABLE IF NOT EXISTS `memory_fact` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `subject` TEXT NOT NULL, `predicate` TEXT NOT NULL, `object_value` TEXT NOT NULL, `confidence` REAL NOT NULL, `source_conversation_id` TEXT, `valid_from` INTEGER NOT NULL, `valid_to` INTEGER, `created_at` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_memory_fact_assistant_id_valid_to` ON `memory_fact` (`assistant_id`, `valid_to`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_fact_assistant_id_subject_predicate` ON `memory_fact` (`assistant_id`, `subject`, `predicate`)",
    )

    /**
     * Indices the fork's 27->28 auto-migration adds on tables that already exist before that
     * step (`ConversationEntity`, `MemoryEntity`) or that [FORK_ONLY_DDL] just created fresh
     * (`scheduled_jobs`, `scheduled_job_runs`). That migration is a pure schema diff compiled
     * from app/schemas/.../27.json and 28.json, so it never runs on the "already current" path
     * below, which stamps the file straight to [EXPECTED_VERSION]: the indices would otherwise
     * be silently missing (issue #60, `Found: indices = {}` on `ConversationEntity`). Every
     * statement is `IF NOT EXISTS`, so running this against a database that already has the
     * indices - including a genuine backup already on v30 - is a safe no-op that touches no data.
     */
    internal val BACKFILL_INDEX_DDL: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_is_pinned_update_at` ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)",
        "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` ON `ConversationEntity` (`is_pinned`, `update_at`)",
        "CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id` ON `MemoryEntity` (`assistant_id`)",
        "CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_enabled` ON `scheduled_jobs` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_scheduled_job_runs_jobId_startedAtMs` ON `scheduled_job_runs` (`jobId`, `startedAtMs`)",
        "CREATE INDEX IF NOT EXISTS `index_scheduled_job_runs_jobId_outcome` ON `scheduled_job_runs` (`jobId`, `outcome`)",
    )

    /**
     * Call after a restore has written the database file, and only when the restore actually
     * included the database. Safe to call when the file is a genuine agent backup (every
     * statement is idempotent) or when the file does not exist (no-op).
     */
    fun reconcile(context: Context) {
        reconcileDatabaseFile(context.getDatabasePath(DB_NAME))
    }

    /**
     * Testable core of [reconcile]: operate on the raw db file at [dbFile] directly, so a test
     * can exercise it against a temp file instead of the app's live `rikka_hub` database.
     */
    internal fun reconcileDatabaseFile(dbFile: File) {
        if (!dbFile.exists()) {
            Log.i(TAG, "reconcile: no database file at ${dbFile.absolutePath}, skipping")
            return
        }
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                val version = db.version // PRAGMA user_version
                if (version > EXPECTED_VERSION) {
                    Log.w(TAG, "reconcile: backup db version $version is newer than $EXPECTED_VERSION; leaving untouched")
                    return
                }

                // A file whose shared schema already matches the fork's current schema must not
                // be migrated: Room would replay the fork's 24->27 auto-migrations and re-ADD
                // columns the file already has, crashing with "duplicate column name" (an
                // upstream 2.4.x backup stamps user_version 24 but carries every modern column;
                // see issues #10, #11). Detect that case by the sentinel columns those very
                // migrations add.
                val alreadyCurrent = MODERN_COLUMN_SENTINELS.all {
                    hasColumn(db, "ConversationEntity", it)
                }

                db.beginTransaction()
                try {
                    if (version == EXPECTED_VERSION || alreadyCurrent) {
                        // Fork-only tables and the indices that backfill older files. Deliberately
                        // NOT run for a file Room is still going to migrate: these statements write
                        // the *current* shape of `missions` (every v35 / v36 column), and the
                        // 34->35 / 35->36 / 36->37 auto-migrations that follow would then try to
                        // ADD columns that already exist and abort the whole open with "duplicate
                        // column name". Restoring any backup older than v32 hit exactly that.
                        // Room's own migrations create each of these tables at the version that
                        // introduced it, i.e. with the shape the next step expects.
                        FORK_ONLY_DDL.forEach(db::execSQL)
                        BACKFILL_INDEX_DDL.forEach(db::execSQL)
                        // v29 adds this table and v30 adds the chat_model_id column below,
                        // both through Room auto-migrations. Imported upstream databases and
                        // genuine v27/v28 fork databases are stamped straight to
                        // EXPECTED_VERSION here, so create/add them before installing the
                        // identity for EXPECTED_VERSION.
                        db.execSQL(CONTEXT_COMPACTION_DDL)
                        if (!hasColumn(db, "ConversationEntity", "chat_model_id")) {
                            db.execSQL(
                                "ALTER TABLE `ConversationEntity` ADD COLUMN `chat_model_id` TEXT NOT NULL DEFAULT ''"
                            )
                        }
                        // A restored file without a `workspaces` table (pre-2.3 upstream) has
                        // nothing to add this column to; running the ALTER would fail and roll
                        // back the whole transaction, so only attempt it when the table exists.
                        if (tableExists(db, "workspaces") &&
                            !hasColumn(db, "workspaces", "shell_compatibility_mode")
                        ) {
                            db.execSQL(SHELL_COMPATIBILITY_MODE_DDL)
                        }
                        // v35 — backfill the mission runtime columns for a restored v32..v34 file
                        // whose `missions` table predates them. (When the table did not exist at
                        // all, FORK_ONLY_DDL above already created it with the full v35 shape.)
                        if (tableExists(db, "missions")) {
                            MISSION_V35_COLUMN_DDL.forEach { (column, ddl) ->
                                if (!hasColumn(db, "missions", column)) {
                                    db.execSQL(ddl)
                                }
                            }
                            // v36 — trigger / schedule columns (批 B).
                            MISSION_V36_COLUMN_DDL.forEach { (column, ddl) ->
                                if (!hasColumn(db, "missions", column)) {
                                    db.execSQL(ddl)
                                }
                            }
                            // v37 — workflow document path (批 C).
                            MISSION_V37_COLUMN_DDL.forEach { (column, ddl) ->
                                if (!hasColumn(db, "missions", column)) {
                                    db.execSQL(ddl)
                                }
                            }
                        }
                        // v38 — the memory write gate's columns. `MemoryEntity` is an upstream
                        // table: a pre-2.x backup may not carry it at all, so guard first.
                        if (tableExists(db, "MemoryEntity")) {
                            MEMORY_V38_COLUMN_DDL.forEach { (column, ddl) ->
                                if (!hasColumn(db, "MemoryEntity", column)) {
                                    db.execSQL(ddl)
                                }
                            }
                        }
                        // No migration should run: the file is either already stamped at the
                        // fork's version, or it is an upstream file whose shared schema already
                        // matches it. Point Room's identity row and user_version at the fork so
                        // the integrity check passes now that every fork-only table is present.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(EXPECTED_IDENTITY_HASH),
                        )
                        if (version != EXPECTED_VERSION) {
                            // PRAGMA user_version is transactional (SQLiteOpenHelper sets it the
                            // same way inside its own upgrade transaction), so this commits or
                            // rolls back atomically with the DDL and identity row above.
                            db.version = EXPECTED_VERSION
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.i(TAG, "reconcile: reconciled imported db (version=$version, alreadyCurrent=$alreadyCurrent)")
            }
        } catch (t: Throwable) {
            // Never let reconciliation break the restore. Worst case is the pre-existing
            // behaviour (a crash on next open); the user's rows are still on disk.
            Log.w(TAG, "reconcile: failed to reconcile imported db", t)
        }
    }

    /** True if [table] exists and has a column named [column]. Best-effort; false on error. */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                if (nameIndex >= 0) {
                    while (!found && cursor.moveToNext()) {
                        found = cursor.getString(nameIndex) == column
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hasColumn: failed to inspect $table.$column", t)
            false
        }
    }

    /** True if [table] exists. Best-effort; false on error. */
    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor -> cursor.moveToFirst() }
        } catch (t: Throwable) {
            Log.w(TAG, "tableExists: failed to check $table", t)
            false
        }
    }
}
