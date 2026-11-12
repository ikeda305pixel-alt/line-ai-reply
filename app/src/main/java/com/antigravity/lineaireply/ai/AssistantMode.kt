package com.antigravity.lineaireply.ai

/**
 * 4 Quick Action Modes for LINE AI Reply Assistant:
 * - REPLY: 💬 返信提案 (Straightforward, empathetic, engaging, alternative replies)
 * - SCHEDULE: 📅 日程調整 (Smart meetups/invites strictly in Kanagawa; Tokyo forbidden)
 * - TOPIC: 💡 話題提案 (Questions/topics to keep conversations flowing)
 * - MOOD: 🔍 ムード分析 (Partner emotion/affection analysis & strategic response)
 */
enum class AssistantMode(val title: String) {
    REPLY("返信提案"),
    SCHEDULE("日程調整"),
    TOPIC("話題提案"),
    MOOD("ムード分析")
}
