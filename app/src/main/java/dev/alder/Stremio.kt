package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin client for Stremio's own services (request shapes from stremio-core). Every call blocks,
 * so run them off the main thread.
 */
object Stremio {
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"

    class Link(val code: String, val url: String)
    class ApiException(val code: Int, message: String) : IOException(message)

    /** Error code for a session that no longer exists (signed out elsewhere). */
    const val NO_SESSION = 1

    private val addonsCache = java.util.concurrent.ConcurrentHashMap<String, List<Addon>>() // per profile's auth key

    /**
     * TV sign-in, the same flow as Stremio's own TV app: show [Link.url] or the code, the person approves
     * it on their phone, and [readLink] then returns the account's auth key. The app never sees a password.
     */
    fun createLink(): Link = result(Http.get(LINK + "create?type=Create"))
        .let { Link(it.getString("code"), it.getString("link")) }

    /** Auth key once the code is approved, null until then. Stremio answers 101 for both "waiting" and "expired". */
    fun readLink(code: String): String? = try {
        result(Http.get(LINK + "read?type=Read&code=" + URLEncoder.encode(code, "UTF-8"))).getString("authKey")
    } catch (e: ApiException) {
        if (e.code == 101) null else throw e
    }

    /** The addons installed on the account, in the account's order. Kept in memory until [refresh]. */
    fun addons(authKey: String, refresh: Boolean = false): List<Addon> {
        if (!refresh) addonsCache[authKey]?.let { return it }
        val body = addonsBody(authKey)
        val answer = Http.post(API + "addonCollectionGet", body)
        return result(answer).getJSONArray("addons").objects().map(::Addon)
            .also { addonsCache[authKey] = it; Http.keep(API + "addonCollectionGet", body, answer) }
    }

    /** The addon list as it was last time, from disk (null if never fetched), so Home can start before the network answers. */
    fun savedAddons(authKey: String): List<Addon>? = Http.savedAnswer(API + "addonCollectionGet", addonsBody(authKey))
        ?.let { runCatching { result(it).getJSONArray("addons").objects().map(::Addon) }.getOrNull() }
        ?.also { addonsCache.putIfAbsent(authKey, it) } // until the fresh list arrives, details are asked of these

    private fun addonsBody(authKey: String) =
        JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true).toString()

    /** Replaces the account's addon list (and order) with [addons]. */
    fun setAddons(authKey: String, addons: List<Addon>) {
        val body = JSONObject().put("type", "AddonCollectionSet").put("authKey", authKey)
            .put("addons", JSONArray(addons.map { it.descriptor }))
        result(post("addonCollectionSet", body))
        addonsCache[authKey] = addons
    }

    /** Library items (watch history included): the given ids, or every item when [ids] is empty. */
    fun library(authKey: String, ids: List<String> = emptyList()): List<JSONObject> {
        return newest(authKey, ids, readLibrary(authKey, ids))
    }

    private fun readLibrary(authKey: String, ids: List<String>): List<JSONObject> {
        val body = libraryBody(authKey, ids)
        val answer = Http.post(API + "datastoreGet", body)
        return libraryItems(answer).also { if (ids.isEmpty()) Http.keep(API + "datastoreGet", body, answer) }
    }

    // Items saved here whose save may not have reached Stremio yet: the player saves as it closes, and the page behind
    // it asks Stremio at the same moment, so without this it showed the progress from before.
    private val saving = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    private fun newest(authKey: String, ids: List<String>, items: List<JSONObject>): List<JSONObject> {
        val pending = ProgressSaves.pending(authKey).values.map(::JSONObject)
        val latest = items.associateByTo(linkedMapOf()) { it.optString("_id") }
        pending.filter { ids.isEmpty() || it.optString("_id") in ids }.forEach { item ->
            val id = item.optString("_id")
            if (newerProgress(item, latest[id])) latest[id] = applyProgress(item, latest[id])
        }
        return latest.map { (id, item) -> saving["$authKey/$id"]?.takeIf { newerProgress(it, item) } ?: item }
    }

    /** The whole library as it was last time, from disk, or null. */
    fun savedLibrary(authKey: String): List<JSONObject>? {
        val cached = Http.savedAnswer(API + "datastoreGet", libraryBody(authKey, emptyList()))
            ?.let { runCatching { libraryItems(it) }.getOrNull() }
        return newest(authKey, emptyList(), cached.orEmpty()).takeIf { cached != null || it.isNotEmpty() }
    }

    private fun libraryBody(authKey: String, ids: List<String>) = JSONObject().put("authKey", authKey)
        .put("collection", "libraryItem").put("ids", JSONArray(ids)).put("all", ids.isEmpty()).toString()

    private fun libraryItems(answer: String): List<JSONObject> {
        val json = JSONObject(answer)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        val list = json.getJSONArray("result")
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    @Synchronized
    fun saveLibrary(authKey: String, items: List<JSONObject>) {
        items.forEach { saving["$authKey/${it.optString("_id")}"] = it }
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", JSONArray(items))
        try { result(post("datastorePut", body)) }
        catch (error: Throwable) {
            items.forEach { saving.remove("$authKey/${it.optString("_id")}", it) }
            throw error
        }
    }

    @Synchronized
    internal fun editLibrary(authKey: String, id: String, fallback: JSONObject, change: (JSONObject) -> Unit): JSONObject {
        val entry = library(authKey, listOf(id)).firstOrNull()?.let { JSONObject(it.toString()) } ?: fallback
        change(entry)
        saveLibrary(authKey, listOf(entry))
        return entry
    }

    @Synchronized
    internal fun syncProgress(authKey: String) {
        val pending = ProgressSaves.pending(authKey)
        if (pending.isEmpty()) return
        val items = pending.values.map(::JSONObject)
        val remote = readLibrary(authKey, items.map { it.getString("_id") }).associateBy { it.getString("_id") }
        val changes = items.mapNotNull { item ->
            val id = item.getString("_id")
            val saved = remote[id]
            if (newerProgress(item, saved) && newerProgress(item, saving["$authKey/$id"])) applyProgress(item, saved) else null
        }
        if (changes.isNotEmpty()) saveLibrary(authKey, changes)
        val body = libraryBody(authKey, emptyList())
        val cached = Http.savedAnswer(API + "datastoreGet", body)?.let { runCatching { libraryItems(it) }.getOrNull() }
        if (cached != null) {
            val latest = cached.associateByTo(linkedMapOf()) { it.getString("_id") }
            (remote.values + changes).forEach { item ->
                val id = item.getString("_id")
                if (newerProgress(item, latest[id])) latest[id] = item
            }
            Http.keep(API + "datastoreGet", body, JSONObject().put("result", JSONArray(latest.values)).toString())
        }
        ProgressSaves.complete(pending)
    }

    /** The account behind a sign-in: each sign-in has its own auth key, so two keys can be the same account. */
    fun userId(authKey: String): String =
        result(post("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))).getString("_id")

    fun logout(authKey: String) {
        addonsCache.remove(authKey)
        post("logout", JSONObject().put("type", "Logout").put("authKey", authKey))
    }

    // Stremio wraps every answer as {"result": …} or {"error": {"code", "message"}}.
    private fun result(body: String): JSONObject {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun post(method: String, body: JSONObject) = Http.post(API + method, body.toString())
}
