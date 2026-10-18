package me.rerere.rikkahub.runtime

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.runtime.util.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<LoopMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

/**
 * The "Recent Chats" block. The query itself is :app's job (LoopRecentChatsProvider);
 * this function only formats the rows — the date is pre-formatted by :app because its
 * util layer owns the localisation.
 */
internal fun buildRecentChatsPrompt(entries: List<RecentChatEntry>): String {
    if (entries.isEmpty()) return ""
    return buildString {
        appendLine()
        append("**Recent Chats**")
        appendLine()
        append("These are some of the user's recent conversations. You can use them to understand user preferences:")
        appendLine()
        val json = buildJsonArray {
            entries.forEach { entry ->
                add(buildJsonObject {
                    put("title", entry.title)
                    put("last_chat", entry.lastChatDate)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
}
