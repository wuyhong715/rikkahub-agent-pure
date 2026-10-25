package me.rerere.rikkahub.data.mission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.runtime.util.JsonInstant
import java.io.File
import java.nio.file.Files

/**
 * 批 F — 一轮开始前对任务工作区做的文件快照。
 *
 * 存在的理由：任务跑偏了要能退回去。任务运行时的 RunCheckpoint 只解决「进程死了续上」，
 * 解决不了「五小时前就不该往这个方向走」。所以每轮开跑前把工作区的 FILES 区整份拷一份到应用私有
 * 目录，回退时整份换回去。
 *
 * 三条硬规矩：
 * 1. **不碰 rootfs/LINUX 区**：只快照 FILES 区（模型产出的地方），rootfs 太大也快照不动。
 * 2. **有上限**：文件数/总字节超过阈值就整体跳过并记录原因，绝不半份快照（半份恢复比不恢复更糟）。
 * 3. **回退前先给自己拍一张**：恢复会用快照覆盖当前内容，覆盖前先把当前状态存成 `pre_restore`
 *    快照，所以「回退」本身也是可回退的。
 *
 * 元数据存 `index.json`（每任务一份，原子替换），不放数据库 —— 这样整套功能不需要 schema 迁移。
 */
class MissionSnapshotStore(
    private val baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTotalBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val keep: Int = DEFAULT_KEEP,
) {

    private val lock = Mutex()

    /** 一次快照的结果。 */
    sealed interface CaptureResult {
        /** 存下来了。 */
        data class Saved(val snapshot: MissionSnapshot) : CaptureResult

        /** 工作区是空的（首轮之前就是这种），没有可存的东西 —— 不是错误。 */
        object Empty : CaptureResult

        /** 跳过了，[reason] 是给用户看的中文原因。 */
        data class Skipped(val reason: String) : CaptureResult
    }

    /** 一次恢复的结果。 */
    sealed interface RestoreResult {
        /** 恢复成功，[fileCount] 是换回去的文件数，[safetySnapshotId] 是覆盖前存下的那份。 */
        data class Restored(
            val fileCount: Int,
            val safetySnapshotId: String?,
        ) : RestoreResult

        data class Failed(val reason: String) : RestoreResult
    }

    /** 该任务的全部快照，最新在前。 */
    suspend fun list(missionId: String): List<MissionSnapshot> = withContext(Dispatchers.IO) {
        lock.withLock { readIndex(missionId).sortedByDescending { it.createdAt } }
    }

    /**
     * 把 [filesDir] 当前内容整份存成快照。轮次号自动取「已有最大轮次 + 1」。
     *
     * [note] 用于区分来源：正常轮次写 `round_start`，恢复前那张写 `pre_restore`。
     */
    suspend fun capture(
        filesDir: File,
        missionId: String,
        note: String = "round_start",
    ): CaptureResult = withContext(Dispatchers.IO) {
        lock.withLock { captureLocked(filesDir, missionId, note) }
    }

    /**
     * 用 [snapshot] 覆盖 [filesDir]。
     *
     * 先给当前状态拍一张 `pre_restore` 快照（失败了也继续恢复 —— 用户的意图明确），再清空目标
     * 目录、把快照内容拷回去。整个过程在工作区互斥锁里，避免两次恢复打架。
     */
    suspend fun restore(
        filesDir: File,
        snapshot: MissionSnapshot,
    ): RestoreResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = File(snapshot.dir)
            if (!source.isDirectory) {
                return@withLock RestoreResult.Failed("快照目录已不存在：${snapshot.dir}")
            }
            val safety = when (val captured = captureLocked(filesDir, snapshot.missionId, "pre_restore")) {
                is CaptureResult.Saved -> captured.snapshot.id
                else -> null
            }
            runCatching {
                filesDir.listFiles()?.forEach { child -> deleteTreeSafely(child) }
                filesDir.mkdirs()
                copyTree(source, filesDir)
            }.fold(
                onSuccess = { RestoreResult.Restored(fileCount = snapshot.fileCount, safetySnapshotId = safety) },
                onFailure = { RestoreResult.Failed("恢复失败：${it.message ?: it::class.simpleName}") },
            )
        }
    }

    /** 任务删除时清掉它的全部快照目录。 */
    suspend fun purge(missionId: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { deleteTreeSafely(File(baseDir, missionId)); true }
    }

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    private fun captureLocked(filesDir: File, missionId: String, note: String): CaptureResult {
        if (!filesDir.isDirectory) return CaptureResult.Empty

        // Symlinks are neither counted nor followed: a link to a directory would be walked (a
        // self-referential one loops forever) and a link to a file would be counted as workspace
        // data it is not. The workspace is a shell sandbox — venv / npm leave links everywhere.
        val files = filesDir.walkTopDown()
            .onEnter { !isSymlink(it) }
            .filter { !isSymlink(it) && it.isFile }
            .toList()
        if (files.isEmpty()) return CaptureResult.Empty

        if (files.size > maxFiles) {
            return CaptureResult.Skipped("文件太多（${files.size} > $maxFiles），本轮跳过快照")
        }
        val totalBytes = files.sumOf { it.length() }
        if (totalBytes > maxTotalBytes) {
            return CaptureResult.Skipped(
                "工作区过大（${totalBytes / 1024 / 1024}MB > ${maxTotalBytes / 1024 / 1024}MB），本轮跳过快照"
            )
        }

        val index = readIndex(missionId).toMutableList()
        val round = (index.maxOfOrNull { it.round } ?: 0) + 1
        val createdAt = clock()
        val id = "r$round-$createdAt"
        val dir = File(File(baseDir, missionId), id)
        runCatching {
            deleteTreeSafely(dir)
            copyTree(filesDir, dir)
        }.onFailure {
            deleteTreeSafely(dir)
            return CaptureResult.Skipped("快照写入失败：${it.message ?: it::class.simpleName}")
        }

        val snapshot = MissionSnapshot(
            id = id,
            missionId = missionId,
            round = round,
            dir = dir.absolutePath,
            fileCount = files.size,
            totalBytes = totalBytes,
            createdAt = createdAt,
            note = note,
        )
        index += snapshot
        val pruned = prune(index)
        writeIndex(missionId, pruned)
        return CaptureResult.Saved(snapshot)
    }

    /** 只保留最新 [keep] 张（含刚存的那张），把更老的目录一并删掉。 */
    private fun prune(index: List<MissionSnapshot>): List<MissionSnapshot> {
        if (index.size <= keep) return index
        val sorted = index.sortedByDescending { it.createdAt }
        val survivors = sorted.take(keep)
        sorted.drop(keep).forEach { old -> runCatching { deleteTreeSafely(File(old.dir)) } }
        return survivors.sortedBy { it.createdAt }
    }

    private fun readIndex(missionId: String): List<MissionSnapshot> {
        val file = indexFile(missionId)
        if (!file.isFile) return emptyList()
        return runCatching {
            JsonInstant.decodeFromString<List<MissionSnapshot>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(missionId: String, snapshots: List<MissionSnapshot>) {
        val file = indexFile(missionId)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(JsonInstant.encodeToString(snapshots))
        if (!temp.renameTo(file)) {
            // 某些文件系统上 rename 到已存在目标会失败：删掉再改一次名。
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun indexFile(missionId: String): File = File(File(baseDir, missionId), INDEX_NAME)

    /** 递归拷贝；符号链接直接跳过（不跟随、不复制，避免把工作区外的东西拖进快照）。 */
    private fun copyTree(source: File, target: File) {
        // The check has to be explicit: `File.isFile` / `isDirectory` FOLLOW the link, so without
        // this a symlink to an outside file was copied (content and all) and a symlink to a
        // directory was descended into.
        if (isSymlink(source)) return
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyTree(child, File(target, child.name)) }
        } else if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    /** True when [file] is a symlink. Never follows it. */
    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Deletes [file] without ever following a symlink.
     *
     * `File.deleteRecursively()` treats a symlink-to-directory as a directory and deletes the
     * *target's* contents, so one `ln -s ~/Documents loop` in a workspace turned "restore this
     * mission's files" into "wipe an unrelated directory". A link is removed as a link.
     */
    private fun deleteTreeSafely(file: File) {
        val path = file.toPath()
        if (isSymlink(file)) {
            runCatching { Files.deleteIfExists(path) }
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteTreeSafely(child) }
        }
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        /** 单个任务最多留这么多份快照（越多越占空间，回退通常只需要最近几轮）。 */
        const val DEFAULT_KEEP: Int = 12

        /** 单份快照的总字节上限。超过就跳过 —— 半份快照会让回退「看起来成功其实丢了文件」。 */
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024

        /** 单份快照的文件数上限。 */
        const val DEFAULT_MAX_FILES: Int = 5_000

        const val INDEX_NAME: String = "index.json"
    }
}

/**
 * 一份快照的元数据。`dir` 存绝对路径：应用私有目录重启不变，比存相对路径少一层解析。
 */
@Serializable
data class MissionSnapshot(
    val id: String,
    val missionId: String,
    /** 第几轮开始前拍的（1 起；`pre_restore` 那张沿用当时的最大轮次 + 1）。 */
    val round: Int,
    val dir: String,
    val fileCount: Int,
    val totalBytes: Long,
    val createdAt: Long,
    /** `round_start` / `pre_restore`。 */
    val note: String = "round_start",
) {
    val isSafetyCopy: Boolean get() = note == "pre_restore"
}
