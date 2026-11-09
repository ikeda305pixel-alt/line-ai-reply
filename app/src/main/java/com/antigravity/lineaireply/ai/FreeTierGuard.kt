package com.antigravity.lineaireply.ai

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FreeTierGuard(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("line_free_tier", Context.MODE_PRIVATE)

    companion object {
        const val MAX_RPM = 10
        const val MAX_RPD = 1500
        private const val KEY_DAILY_COUNT = "daily_count"
        private const val KEY_DATE = "date"
        private const val KEY_MINUTE_TIMESTAMPS = "minute_timestamps"
    }

    private fun getToday(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    @Synchronized
    fun getDailyUsage(): Int {
        val today = getToday()
        val savedDate = prefs.getString(KEY_DATE, "")
        return if (savedDate == today) prefs.getInt(KEY_DAILY_COUNT, 0) else 0
    }

    @Synchronized
    fun canRequest(): Boolean {
        val today = getToday()
        val savedDate = prefs.getString(KEY_DATE, "")
        val count = if (savedDate == today) prefs.getInt(KEY_DAILY_COUNT, 0) else 0
        if (count >= MAX_RPD) return false

        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_MINUTE_TIMESTAMPS, "") ?: ""
        val recent = raw.split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < 60_000 }
        return recent.size < MAX_RPM
    }

    @Synchronized
    fun recordRequest() {
        val today = getToday()
        val savedDate = prefs.getString(KEY_DATE, "")
        val count = if (savedDate == today) prefs.getInt(KEY_DAILY_COUNT, 0) + 1 else 1

        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_MINUTE_TIMESTAMPS, "") ?: ""
        val recent = (raw.split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < 60_000 } + now)

        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_DAILY_COUNT, count)
            .putString(KEY_MINUTE_TIMESTAMPS, recent.joinToString(","))
            .apply()
    }
}
