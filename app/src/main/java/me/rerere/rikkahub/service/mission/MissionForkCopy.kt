package me.rerere.rikkahub.service.mission

import java.io.File

/**
 * 批 I — 任务分叉时的「工作区快照拷贝」。
 *
 * 分叉要的是「从这一刻的产物出发，走另一条路」，所以新任务必须拿到一份**独立的副本**：
 * 两个任务写同一个目录会互相覆盖，分叉也就失去意义。
 *
 * 只拷 FILES 区（用户/模型的工作产出），不拷 rootfs：rootfs 是工具链与运行时（几百 MB，
 * 且各任务共用同一套解释器更合理），拷它会让「分叉」变成一次几十分钟的磁盘操作。
 *
 * 三条保护：
 * - 不跟随符号链接（防止把工作区外的目录整棵拷进来，也防止环）；
 * - 文件数 / 总字节超上限就中止并报错——半份副本比不拷更糟（模型会以为文件丢了）；
 * - 目标目录必须是空的（新建的工作区），避免覆盖用户已有内容。
 */
object MissionForkCopy {

    const val MAX_FILES: Int = 5_000
    const val MAX_BYTES: Long = 200L * 1024L * 1024L

    data class Stats(val files: Int, val bytes: Long)

    /** 目标非空/上限超出时抛 [IllegalStateException]，调用方负责把任务建成「未启动」状态。 */
    fun copyTree(source: File, target: File): Stats {
        require(source.isDirectory) { "source workspace is not a directory: ${source.path}" }
        target.mkdirs()
        val existing = target.list()?.toList().orEmpty()
        check(existing.isEmpty()) { "target workspace is not empty (${existing.size} entries)" }

        var files = 0
        var bytes = 0L
        val queue = ArrayDeque<Pair<File, File>>()
        queue.addLast(source to target)
        while (queue.isNotEmpty()) {
            val (from, to) = queue.removeFirst()
            val children = from.listFiles().orEmpty()
            for (child in children) {
                // 符号链接直接跳过：它不是「产物」，而是指向别处的指针。
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                val destination = File(to, child.name)
                if (child.isDirectory) {
                    destination.mkdirs()
                    queue.addLast(child to destination)
                    continue
                }
                if (!child.isFile) continue
                files++
                bytes += child.length()
                check(files <= MAX_FILES) { "workspace has more than $MAX_FILES files; fork aborted" }
                check(bytes <= MAX_BYTES) { "workspace is larger than ${MAX_BYTES / 1024 / 1024}MB; fork aborted" }
                child.copyTo(destination, overwrite = false)
            }
        }
        return Stats(files = files, bytes = bytes)
    }
}
