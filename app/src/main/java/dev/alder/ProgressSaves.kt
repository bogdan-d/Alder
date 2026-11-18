package dev.alder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/** Last unsynced checkpoint per title, scoped to the same Stremio sign-in as the library cache. */
internal object ProgressSaves {
    private var prefs: SharedPreferences? = null
    private val syncFailures = mutableMapOf<String, Failure>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("pending_progress", Context.MODE_PRIVATE)
    }

    private fun prefix(authKey: String) = MessageDigest.getInstance("SHA-256").digest(authKey.toByteArray())
        .joinToString("") { "%02x".format(it) } + "/"

    @Synchronized
    fun keep(authKey: String, item: JSONObject, provisional: Boolean) {
        val store = prefs ?: return
        val key = prefix(authKey) + item.getString("_id")
        val old = store.getString(key, null)?.let(::JSONObject)
        if (old == null || item.optString("_mtime") >= old.optString("_mtime")) {
            if (provisional) item.put("alderProvisional", true)
            store.edit().putString(key, item.toString()).apply()
        }
    }

    @Synchronized
    fun pending(authKey: String): Map<String, String> {
        val prefix = prefix(authKey)
        return prefs?.all.orEmpty().filterKeys { it.startsWith(prefix) }.mapValues { it.value as String }
    }

    @Synchronized
    fun complete(sent: Map<String, String>) {
        val store = prefs ?: return
        val edit = store.edit()
        sent.forEach { (key, value) -> if (store.getString(key, null) == value) edit.remove(key) }
        edit.apply()
    }

    @Synchronized
    fun forget(authKey: String) {
        complete(pending(authKey))
        syncFailures.remove(authKey)
    }

    @Synchronized
    fun syncFailure(authKey: String, failure: Failure?): Failure? =
        if (failure == null) syncFailures.remove(authKey) else syncFailures.put(authKey, failure)
}

internal fun newerProgress(local: JSONObject, remote: JSONObject?) =
    remote == null || local.optString("_mtime") > remote.optString("_mtime")

/** If playback started without a library item, apply its progress to the real item when it becomes available. */
internal fun applyProgress(checkpoint: JSONObject, remote: JSONObject?): JSONObject {
    val local = JSONObject(checkpoint.toString())
    if (local.remove("alderProvisional") != true || remote == null) return local
    val merged = JSONObject(remote.toString())
    val state = merged.state
    val progress = local.state
    val sameVideo = Library.savedVideoId(local) == Library.savedVideoId(remote)
    val played = progress.optLong("overallTimeWatched")
    for (field in listOf("video_id", "timeOffset", "duration", "lastWatched")) state.put(field, progress.opt(field))
    state.put("timeWatched", (if (sameVideo) state.optLong("timeWatched") else 0L) + progress.optLong("timeWatched"))
    state.put("overallTimeWatched", state.optLong("overallTimeWatched") + played)
    val watched = progress.optInt("flaggedWatched")
    val alreadyWatched = sameVideo && state.optInt("flaggedWatched") != 0
    if (!alreadyWatched) state.put("flaggedWatched", watched)
    if (!alreadyWatched && watched != 0) state.put("timesWatched", state.optInt("timesWatched") + 1)
    return merged.put("_mtime", local.getString("_mtime"))
}
