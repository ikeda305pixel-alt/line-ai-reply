package com.antigravity.lineaireply.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for messages captured from notifications (LINE, Bumble, etc.).
 */
data class CachedMessage(
    val roomName: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isPartner: Boolean = true
) {
    fun isPhoto(): Boolean {
        val lower = text.lowercase().trim()
        return lower == "写真" || lower == "[写真]" || lower == "photo" ||
                lower.contains("写真を送信しました") || lower.contains("画像を送信しました") ||
                lower.contains("sent a photo")
    }
}

/**
 * Thread-safe persistent cache for incoming chat notifications.
 * Eliminates the need for MediaProjection or AccessibilityService by recording
 * recent incoming messages per chat room / sender in the background.
 */
object NotificationChatCacheManager {
    private const val TAG = "NotificationChatCache"
    private const val PREFS_NAME = "notification_chat_cache_prefs"
    private const val KEY_CACHE_DATA = "cache_json_data"
    private const val KEY_RECENT_ROOMS = "recent_rooms_list"
    private const val MAX_MESSAGES_PER_ROOM = 25
    private const val MAX_ROOMS = 15

    private val lock = Any()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Records a new incoming message from a notification.
     */
    fun addMessage(
        context: Context,
        roomName: String,
        sender: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        isPartner: Boolean = true
    ) {
        val cleanRoom = roomName.trim().ifEmpty { "トーク相手" }
        val cleanSender = sender.trim().ifEmpty { cleanRoom }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        synchronized(lock) {
            val allData = loadAllData(context)
            val roomList = allData.getOrPut(cleanRoom) { mutableListOf() }

            // Deduplication: skip if identical message in same room within 3 seconds
            val lastMsg = roomList.lastOrNull()
            if (lastMsg != null && lastMsg.text == cleanText && (timestamp - lastMsg.timestamp) < 3000L) {
                return
            }

            roomList.add(
                CachedMessage(
                    roomName = cleanRoom,
                    sender = cleanSender,
                    text = cleanText,
                    timestamp = timestamp,
                    isPartner = isPartner
                )
            )

            // Cap messages per room
            while (roomList.size > MAX_MESSAGES_PER_ROOM) {
                roomList.removeAt(0)
            }

            // Update recent rooms order
            val recentRooms = loadRecentRooms(context).toMutableList()
            recentRooms.remove(cleanRoom)
            recentRooms.add(0, cleanRoom)
            while (recentRooms.size > MAX_ROOMS) {
                val removedRoom = recentRooms.removeAt(recentRooms.lastIndex)
                allData.remove(removedRoom)
            }

            saveAllData(context, allData, recentRooms)
            Log.d(TAG, "Cached notification for [$cleanRoom]: $cleanSender -> $cleanText (Total: ${roomList.size})")
        }
    }

    /**
     * Returns list of cached messages for the given room (ordered chronologically, oldest to newest).
     */
    fun getMessagesForRoom(context: Context, roomName: String): List<CachedMessage> {
        synchronized(lock) {
            val allData = loadAllData(context)
            return allData[roomName]?.toList() ?: emptyList()
        }
    }

    /**
     * Returns list of active room names, sorted by recency (most recent first).
     */
    fun getRecentRooms(context: Context): List<String> {
        synchronized(lock) {
            return loadRecentRooms(context)
        }
    }

    /**
     * Returns the name of the most recently active room.
     */
    fun getMostRecentRoom(context: Context): String? {
        synchronized(lock) {
            return loadRecentRooms(context).firstOrNull()
        }
    }

    /**
     * Formats the cached conversation history into a clean multiline script for AI prompt injection.
     */
    fun getFormattedContextForRoom(context: Context, roomName: String): String {
        val messages = getMessagesForRoom(context, roomName)
        if (messages.isEmpty()) {
            return "（直近の通知メッセージはありません）"
        }
        val sb = StringBuilder()
        for (m in messages) {
            val role = if (m.isPartner) m.sender else "自分"
            sb.append("$role: ${m.text}\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Clears all cached notification history.
     */
    fun clearCache(context: Context) {
        synchronized(lock) {
            getPrefs(context).edit().clear().apply()
        }
    }

    private fun loadAllData(context: Context): MutableMap<String, MutableList<CachedMessage>> {
        val rawJson = getPrefs(context).getString(KEY_CACHE_DATA, null) ?: return mutableMapOf()
        val result = mutableMapOf<String, MutableList<CachedMessage>>()
        try {
            val rootObj = JSONObject(rawJson)
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val room = keys.next()
                val array = rootObj.getJSONArray(room)
                val list = mutableListOf<CachedMessage>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list.add(
                        CachedMessage(
                            roomName = item.optString("roomName", room),
                            sender = item.optString("sender", room),
                            text = item.optString("text", ""),
                            timestamp = item.optLong("timestamp", 0L),
                            isPartner = item.optBoolean("isPartner", true)
                        )
                    )
                }
                result[room] = list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing cached notification JSON: ${e.message}")
        }
        return result
    }

    private fun loadRecentRooms(context: Context): List<String> {
        val rawJson = getPrefs(context).getString(KEY_RECENT_ROOMS, null) ?: return emptyList()
        val result = mutableListOf<String>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                result.add(array.getString(i))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing recent rooms: ${e.message}")
        }
        return result
    }

    private fun saveAllData(
        context: Context,
        data: Map<String, List<CachedMessage>>,
        recentRooms: List<String>
    ) {
        try {
            val rootObj = JSONObject()
            for ((room, list) in data) {
                val array = JSONArray()
                for (msg in list) {
                    val item = JSONObject().apply {
                        put("roomName", msg.roomName)
                        put("sender", msg.sender)
                        put("text", msg.text)
                        put("timestamp", msg.timestamp)
                        put("isPartner", msg.isPartner)
                    }
                    array.put(item)
                }
                rootObj.put(room, array)
            }

            val roomsArray = JSONArray()
            for (r in recentRooms) {
                roomsArray.put(r)
            }

            getPrefs(context).edit()
                .putString(KEY_CACHE_DATA, rootObj.toString())
                .putString(KEY_RECENT_ROOMS, roomsArray.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving notification cache: ${e.message}")
        }
    }
}
