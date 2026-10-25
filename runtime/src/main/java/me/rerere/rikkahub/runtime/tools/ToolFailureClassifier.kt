package me.rerere.rikkahub.runtime.tools

/**
 * 批 I — 工具失败归因。
 *
 * 任务跑八小时，真正决定「下一轮该干什么」的不是「调了几次工具」，而是「失败的是哪一类」：
 * 网络抖动重试就好，权限被拒得换路径，参数写错要改调用，环境缺依赖得先装东西。
 * 此前这些信息全埋在工具输出的前 300 字符里，模型下一轮只能靠读片段猜，督导也统计不出来。
 *
 * 这一层只做纯文本判定：**不抛异常、不读 IO、不认识具体工具**。输入是工具的输出
 * （通常是一个 JSON envelope），输出是一个分类；拿不准就 [ToolFailureKind.UNKNOWN]。
 * 宁可归到 UNKNOWN，也不要因为猜错把「网络问题」当成「参数错误」去让模型改代码。
 */
enum class ToolFailureKind {
    /** 连不上、DNS、证书、断流。 */
    NETWORK,
    /** 401/403、key 无效、额度耗尽。 */
    AUTH,
    /** 文件/系统权限被拒（EACCES、SAF 未授权）。 */
    PERMISSION,
    /** 路径不存在、命令不存在之外的一般 404。 */
    NOT_FOUND,
    /** 超时（工具自身或 shell）。 */
    TIMEOUT,
    /** 参数缺失/非法/类型不对。 */
    PARAMETER,
    /** 依赖没装、环境不支持（如没有 root、没有无障碍服务）。 */
    ENVIRONMENT,
    /** 用户/系统取消。 */
    CANCELLED,
    /** 没有足够信息，或不是失败。 */
    UNKNOWN,
}

data class ToolFailure(
    val kind: ToolFailureKind,
    /** 给日志/交接用的一小段原文（已截断）。 */
    val detail: String = "",
)

object ToolFailureClassifier {

    /** 详情最多留多少字符：它会被塞进 run event 的 payload，不是给模型读全文的。 */
    const val MAX_DETAIL_CHARS: Int = 240

    /**
     * 从一次工具输出里认出失败；不是失败（或认不出来）返回 null。
     *
     * 认三种形态：
     * 1. 结构化 envelope：`{"error": "code", "detail": "..."}`（本项目的工具普遍这么报错）；
     * 2. `{"success": false, ...}`；
     * 3. 纯文本以 `Error:` / `错误` / `Exception` 开头（外部工具、脚本输出）。
     */
    fun detect(output: String): ToolFailure? {
        val text = output.trim()
        if (text.isEmpty()) return null
        val fromJson = detectEnvelope(text)
        if (fromJson != null) return fromJson
        val lower = text.take(200).lowercase()
        // 只认 `error:` / `error ` 前缀：`Errors: 0 warnings: 2` 这种统计行不是失败。
        val looksLikeError = lower.startsWith("error:") ||
            lower.startsWith("error ") ||
            lower.startsWith("exception") ||
            lower.startsWith("错误") ||
            lower.startsWith("失败")
        if (!looksLikeError) return null
        return ToolFailure(kind = classifyText(text), detail = clip(text))
    }

    private fun detectEnvelope(text: String): ToolFailure? {
        // 只处理「看起来像对象」的输入：不做完整 JSON 解析，工具输出可能是数组或纯文本，
        // 这里要的是零依赖、不会因为半截 JSON 抛异常。
        if (!text.startsWith("{")) return null
        val errorCode = stringField(text, "error")
        val success = booleanField(text, "success")
        val detail = stringField(text, "detail").orEmpty()
        if (errorCode.isNullOrBlank() && success != false) return null
        val haystack = listOfNotNull(errorCode, detail).joinToString(" ")
        return ToolFailure(kind = classifyText(haystack), detail = clip(haystack.ifBlank { text }))
    }

    /**
     * 分类。顺序是刻意的，从「最具体的证据」到「最泛的词」：
     * - 「command not found」必须在「not found」之前，否则缺依赖会被误判成路径错误；
     * - 「连接失败」必须在「找不到」之前，否则 DNS 失败会被误判成文件不存在；
     * - 参数类永远最后，因为 `invalid` / `missing` 这种词在别的错误里也常出现。
     */
    fun classifyText(raw: String?): ToolFailureKind {
        val text = raw?.lowercase().orEmpty()
        if (text.isBlank()) return ToolFailureKind.UNKNOWN
        return when {
            containsAny(text, "timeout", "timed out", "超时", "deadline exceeded") ->
                ToolFailureKind.TIMEOUT
            containsAny(text, "cancelled", "canceled", "aborted", "已取消", "中断") ->
                ToolFailureKind.CANCELLED
            containsAny(text, "unauthorized", "invalid api key", "invalid_api_key", "401", "认证失败", "密钥无效", "额度") ->
                ToolFailureKind.AUTH
            containsAny(text, "permission", "eacces", "denied", "not granted", "拒绝访问", "权限") ->
                ToolFailureKind.PERMISSION
            containsAny(text, "command not found", "not installed", "enotfound", "未安装") ->
                ToolFailureKind.ENVIRONMENT
            containsAny(
                text,
                "network",
                "connect",
                "unreachable",
                "socket",
                "ssl",
                "dns",
                "resolve host",
                "could not resolve",
                "网络",
                "连接",
            ) ->
                ToolFailureKind.NETWORK
            containsAny(text, "no such file", "not found", "404", "不存在", "找不到") ->
                ToolFailureKind.NOT_FOUND
            containsAny(text, "unsupported", "not supported", "不支持", "不可用") ->
                ToolFailureKind.ENVIRONMENT
            containsAny(text, "invalid", "required", "missing", "parameter", "malformed", "参数", "非法", "为空") ->
                ToolFailureKind.PARAMETER
            else -> ToolFailureKind.UNKNOWN
        }
    }

    /** 一句话统计（交接提示词 / 运行报告用）：`网络×2、权限×1`。 */
    fun summarize(kinds: List<ToolFailureKind>): String {
        val counted = kinds.filter { it != ToolFailureKind.UNKNOWN }.groupingBy { it }.eachCount()
        if (counted.isEmpty()) return ""
        return counted.entries
            .sortedByDescending { it.value }
            .joinToString("、") { (kind, count) -> "${label(kind)}×$count" }
    }

    fun label(kind: ToolFailureKind): String = when (kind) {
        ToolFailureKind.NETWORK -> "网络"
        ToolFailureKind.AUTH -> "认证"
        ToolFailureKind.PERMISSION -> "权限"
        ToolFailureKind.NOT_FOUND -> "找不到"
        ToolFailureKind.TIMEOUT -> "超时"
        ToolFailureKind.PARAMETER -> "参数"
        ToolFailureKind.ENVIRONMENT -> "环境"
        ToolFailureKind.CANCELLED -> "取消"
        ToolFailureKind.UNKNOWN -> "未知"
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { needle -> text.contains(needle) }

    private fun clip(text: String): String =
        text.replace('\n', ' ').trim().take(MAX_DETAIL_CHARS)

    /** 极简字段读取：只认 `"key": "value"` 与 `"key": true/false`，避免引入 JSON 依赖。 */
    private fun stringField(text: String, key: String): String? {
        val marker = "\"$key\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val colon = text.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var index = colon + 1
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length || text[index] != '"') return null
        var cursor = index + 1
        val builder = StringBuilder()
        while (cursor < text.length) {
            val ch = text[cursor]
            when (ch) {
                '\\' -> {
                    cursor++
                    if (cursor < text.length) builder.append(text[cursor])
                }
                '"' -> return builder.toString()
                else -> builder.append(ch)
            }
            cursor++
        }
        return null
    }

    private fun booleanField(text: String, key: String): Boolean? {
        val marker = "\"$key\""
        val at = text.indexOf(marker)
        if (at < 0) return null
        val colon = text.indexOf(':', at + marker.length)
        if (colon < 0) return null
        val rest = text.substring(colon + 1).trimStart()
        return when {
            rest.startsWith("false") -> false
            rest.startsWith("true") -> true
            else -> null
        }
    }
}
