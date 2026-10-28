package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.compositionLocalOf

/**
 * 批 4 — 「以此开始任务」的入口。
 *
 * 用 CompositionLocal 而不是给 `ChatMessage` 再加一个参数：消息渲染链是
 * ChatList -> ChatMessage -> ChatMessageActionsSheet，这条链上已经有二十来个回调，为了一个
 * 只在会话页存在、且与消息本身无关的动作再穿三层会把签名彻底淹掉。
 *
 * 值为 null = 当前不在支持「从对话开始任务」的界面里（例如预览 / 分享渲染），入口自动隐藏，
 * 而不是给一个点了没反应的按钮。
 *
 * 参数是消息正文（纯文本）；调用方负责预填到创建表里。
 */
val LocalMissionStarter = compositionLocalOf<((text: String) -> Unit)?> { null }
