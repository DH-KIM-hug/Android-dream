package com.androiddream.callrelay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 녹음 1건의 처리 상태. 채널별로 성공 여부를 기록해 중복 전송을 막는다. */
data class RecordState(
    val key: String,
    val sent: MutableSet<String> = mutableSetOf(),
    var attempts: Int = 0,
    var done: Boolean = false,
    var lastError: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sent", JSONArray(sent.toList()))
        .put("attempts", attempts)
        .put("done", done)
        .put("err", lastError)
        .put("at", updatedAt)

    companion object {
        fun fromJson(key: String, o: JSONObject): RecordState {
            val arr = o.optJSONArray("sent") ?: JSONArray()
            val sent = (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
            return RecordState(
                key, sent, o.optInt("attempts"), o.optBoolean("done"),
                o.optString("err"), o.optLong("at"),
            )
        }
    }
}

data class LogEntry(val time: Long, val title: String, val detail: String, val ok: Boolean)

/** 처리 상태와 화면에 보여줄 전송 기록 */
class History(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("callrelay_history", Context.MODE_PRIVATE)

    @Synchronized
    private fun states(): JSONObject = runCatching { JSONObject(prefs.getString("states", "{}")!!) }.getOrElse { JSONObject() }

    @Synchronized
    fun get(key: String): RecordState? = states().optJSONObject(key)?.let { RecordState.fromJson(key, it) }

    @Synchronized
    fun put(state: RecordState) {
        state.updatedAt = System.currentTimeMillis()
        val all = states()
        all.put(state.key, state.toJson())
        // 오래된 항목 정리 (최대 1000건)
        if (all.length() > 1000) {
            val keys = all.keys().asSequence().toList()
                .sortedBy { all.optJSONObject(it)?.optLong("at") ?: 0L }
            keys.take(all.length() - 1000).forEach { all.remove(it) }
        }
        prefs.edit().putString("states", all.toString()).apply()
    }

    @Synchronized
    fun clearStates() = prefs.edit().remove("states").apply()

    @Synchronized
    fun log(title: String, detail: String, ok: Boolean) {
        val arr = runCatching { JSONArray(prefs.getString("log", "[]")!!) }.getOrElse { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().put("t", System.currentTimeMillis()).put("title", title).put("d", detail).put("ok", ok))
        for (i in 0 until minOf(arr.length(), 199)) next.put(arr.get(i))
        prefs.edit().putString("log", next.toString()).apply()
    }

    @Synchronized
    fun logs(): List<LogEntry> {
        val arr = runCatching { JSONArray(prefs.getString("log", "[]")!!) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.optLong("t"), o.optString("title"), o.optString("d"), o.optBoolean("ok"))
        }
    }

    @Synchronized
    fun clearLogs() = prefs.edit().remove("log").apply()
}
