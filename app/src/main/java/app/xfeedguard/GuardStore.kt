package app.xfeedguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded structural samples. Never stores text or content descriptions. */
class GuardStore(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val file = java.io.File(context.filesDir, "samples.json")
    private val traceFile = java.io.File(context.filesDir, "transitions.json")
    val traceCount: Int get() = prefs.getInt("traceCount", 0)
    val sampleRevision: Long get() = prefs.getLong("sampleRevision", 0)
    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("backgroundEnabled", blocking)
        set(value) { prefs.edit().putBoolean("backgroundEnabled", value).apply() }
    var backgroundError: String
        get() = prefs.getString("backgroundError", "") ?: ""
        set(value) { prefs.edit().putString("backgroundError", value).apply() }
    val blockAttempts: Int get() = prefs.getInt("blockAttempts", 0)
    fun recordBlockAttempt(accepted: Boolean) {
        prefs.edit().putInt("blockAttempts", blockAttempts + 1)
            .putLong("lastBlockAt", System.currentTimeMillis())
            .putBoolean("lastBackAccepted", accepted)
            .putString("blockStatus", if (accepted) "Back requested after a video swipe" else "Android declined Back; no retry")
            .apply()
    }
    fun setProtection(enabled: Boolean) {
        val change = prefs.edit().putBoolean("observing", enabled).putBoolean("blocking", enabled)
        if (enabled) change.putBoolean("backgroundEnabled", true)
        change.apply()
    }
    var blocking: Boolean
        get() = prefs.getBoolean("blocking", false)
        set(value) { prefs.edit().putBoolean("blocking", value).apply() }
    var blockStatus: String
        get() = prefs.getString("blockStatus", "No exit attempted yet") ?: "No exit attempted yet"
        set(value) { prefs.edit().putString("blockStatus", value).apply() }
    var observing: Boolean
        get() = prefs.getBoolean("observing", false)
        set(value) { prefs.edit().putBoolean("observing", value).apply() }
    var result: String
        get() = prefs.getString("result", "No samples yet") ?: "No samples yet"
        set(value) { prefs.edit().putString("result", value).apply() }

    fun samples(): JSONArray = try {
        val atomic = android.util.AtomicFile(file)
        if (file.exists() || java.io.File(file.path + ".bak").exists()) {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
        } else JSONArray()
    } catch (_: Exception) { JSONArray() }

    fun save(sample: JSONObject) {
        val all = samples()
        val label = sample.getString("label")
        val sameLabel = (0 until all.length()).filter { all.getJSONObject(it).optString("label") == label }
        val remove = if (sameLabel.size >= 5) sameLabel.first() else -1
        val updated = JSONArray()
        for (i in 0 until all.length()) if (i != remove) updated.put(all.getJSONObject(i))
        updated.put(sample)
        // Atomic replacement prevents a partial sample after process interruption.
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(updated.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            prefs.edit().putLong("sampleRevision", sampleRevision + 1).apply()
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        android.util.AtomicFile(file).delete()
        android.util.AtomicFile(traceFile).delete()
        prefs.edit().putInt("traceCount", 0).putLong("sampleRevision", sampleRevision + 1).apply()
        result = "Samples deleted"
    }

    fun traces(): JSONArray = try {
        android.util.AtomicFile(traceFile).openRead().bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
    } catch (_: Exception) { JSONArray() }

    fun saveTrace(trace: JSONObject) {
        val previous = traces()
        val next = JSONArray()
        for (i in (previous.length() - 2).coerceAtLeast(0) until previous.length()) next.put(previous.getJSONObject(i))
        next.put(trace)
        val atomic = android.util.AtomicFile(traceFile)
        val output = atomic.startWrite()
        try {
            output.write(next.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            prefs.edit().putInt("traceCount", next.length()).apply()
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
