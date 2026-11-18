package dev.alder

import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

const val LETTERBOXD = "Letterboxd"

/**
 * Letterboxd's average for a film, "3.9" out of 5, from MDBList's ratings. `score` is 0–100 on every MDBList endpoint;
 * `value` is Letterboxd's own 0–5 on the single-title one but 0–10 on the batch one, so it's used only when it agrees.
 */
internal fun parseLetterboxd(json: JSONObject): String? {
    val r = json.optJSONArray("ratings").objects().firstOrNull { it.optString("source") == "letterboxd" } ?: return null
    val score = r.optDouble("score").takeIf { !it.isNaN() && it > 0 } ?: return null
    val stars = r.optDouble("value").takeIf { it in 0.0..5.0 && Math.abs(it * 20 - score) <= 2 } ?: (score / 20)
    return String.format(Locale.US, "%.1f", stars)
}

/** The ratings to show, in order, for Settings → Ratings ("imdb", "letterboxd", "both"); IMDb stands in for a missing Letterboxd. */
internal fun shownRatings(pick: String, imdb: String?, letterboxd: String?): List<Pair<String, String>> {
    val i = imdb?.takeIf { it.isNotBlank() }?.let { "IMDb" to it }
    val l = letterboxd?.let { LETTERBOXD to it }
    return when (pick) {
        "letterboxd" -> listOfNotNull(l ?: i)
        "both" -> listOfNotNull(i, l)
        else -> listOfNotNull(i)
    }
}

// "" is a film MDBList has no Letterboxd score for, or a request that failed (asked again next start).
private val letterboxdCache = ConcurrentHashMap<String, String>()

/** What's known without the network: this session's answer, or null when it has to be asked. */
internal fun knownLetterboxd(imdbId: String): String? = letterboxdCache[imdbId]?.ifEmpty { null }

internal fun forgetLetterboxd() = letterboxdCache.clear()

/**
 * A film's Letterboxd score through MDBList, with the viewer's own API key (free, 1,000 requests a day). Answers are
 * kept on disk, so Home asks once per film; [fresh] (the title page) asks again to keep them current.
 */
internal fun letterboxd(imdbId: String, apiKey: String, fresh: Boolean = false): String? {
    if (!imdbId.startsWith("tt") || apiKey.isBlank()) return null
    if (!fresh) letterboxdCache[imdbId]?.let { return it.ifEmpty { null } }
    val url = "https://api.mdblist.com/imdb/movie/$imdbId?apikey=${android.net.Uri.encode(apiKey)}"
    val saved = Http.savedAnswer(url)
    val raw = if (saved != null && !fresh) saved else runCatching { Http.get(url, keep = true, timeoutMs = 5_000) }
        .onFailure { android.util.Log.w("Alder", "Letterboxd rating for $imdbId failed: ${it.javaClass.simpleName}") }
        .getOrNull() ?: saved
    val score = raw?.let { runCatching { parseLetterboxd(JSONObject(it)) }.getOrNull() }
    letterboxdCache[imdbId] = score.orEmpty()
    return score
}
