package dev.alder

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Enriches movie and series cast metadata with full cast lists, directors, character names, and headshot photos from TMDB.
 */
object Tmdb {
    private const val KEY = "844dba0bfd8f3a4f3799f6130ef9e335"
    private const val BASE = "https://api.themoviedb.org/3"
    private val memoryCache = ConcurrentHashMap<String, List<CastMember>>()
    private val creditsCache = ConcurrentHashMap<String, Set<String>>()

    fun cast(imdbId: String, type: String): List<CastMember> {
        if (!imdbId.startsWith("tt")) return emptyList()
        memoryCache[imdbId]?.let { return it }
        return runCatching {
            val findUrl = "$BASE/find/$imdbId?api_key=$KEY&external_source=imdb_id"
            val findJson = JSONObject(Http.get(findUrl, keep = true))
            val isMovie = type != "series"
            val primaryKey = if (isMovie) "movie_results" else "tv_results"
            val fallbackKey = if (isMovie) "tv_results" else "movie_results"
            val results = findJson.optJSONArray(primaryKey) ?: findJson.optJSONArray(fallbackKey)
            val target = results?.optJSONObject(0) ?: return emptyList()
            val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return emptyList()
            val mediaType = if (target.has("title")) "movie" else "tv"
            val creditsUrl = "$BASE/$mediaType/$tmdbId/credits?api_key=$KEY"
            val creditsJson = JSONObject(Http.get(creditsUrl, keep = true))

            val directors = mutableListOf<CastMember>()
            val crewArray = creditsJson.optJSONArray("crew")
            if (crewArray != null) {
                for (i in 0 until crewArray.length()) {
                    val item = crewArray.optJSONObject(i) ?: continue
                    val job = item.optString("job").trim()
                    val isDir = if (isMovie) {
                        job.equals("Director", ignoreCase = true)
                    } else {
                        job.equals("Director", ignoreCase = true) ||
                            job.equals("Series Director", ignoreCase = true) ||
                            job.equals("Creator", ignoreCase = true)
                    }
                    if (isDir) {
                        val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                        val profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() && it != "null" }
                        val photoUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                        val id = item.optInt("id").takeIf { it > 0 }
                        directors.add(CastMember(name, "Director", photoUrl, role = "director", tmdbId = id))
                    }
                }
            }

            val castArray = creditsJson.optJSONArray("cast") ?: org.json.JSONArray()
            val actors = mutableListOf<CastMember>()
            for (i in 0 until castArray.length()) {
                val item = castArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                val character = item.optString("character").trim().takeIf { it.isNotBlank() && it != "null" }
                val profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() && it != "null" }
                val photoUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                val id = item.optInt("id").takeIf { it > 0 }
                actors.add(CastMember(name, character, photoUrl, role = "actor", tmdbId = id))
            }

            val list = (directors.distinctBy { it.name } + actors.distinctBy { it.name })
            list.also { memoryCache[imdbId] = it }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolves credited movie and TV titles for a person from TMDB to filter out search false-positives.
     */
    fun personCredits(personId: Int?, personName: String, isDirector: Boolean): Set<String> {
        val cacheKey = "${personId ?: personName}_$isDirector".lowercase()
        creditsCache[cacheKey]?.let { return it }

        return runCatching {
            var targetId = personId
            if (targetId == null || targetId <= 0) {
                val searchUrl = "$BASE/search/person?api_key=$KEY&query=${android.net.Uri.encode(personName)}"
                val searchJson = JSONObject(Http.get(searchUrl, keep = true))
                val first = searchJson.optJSONArray("results")?.optJSONObject(0)
                targetId = first?.optInt("id")?.takeIf { it > 0 }
            }
            if (targetId == null || targetId <= 0) return emptySet()

            val combinedUrl = "$BASE/person/$targetId/combined_credits?api_key=$KEY"
            val combinedJson = JSONObject(Http.get(combinedUrl, keep = true))
            val set = mutableSetOf<String>()

            if (isDirector) {
                val crew = combinedJson.optJSONArray("crew")
                if (crew != null) {
                    for (i in 0 until crew.length()) {
                        val item = crew.optJSONObject(i) ?: continue
                        val job = item.optString("job")
                        val dept = item.optString("department")
                        if (job.equals("Director", ignoreCase = true) || dept.equals("Directing", ignoreCase = true)) {
                            val title = item.optString("title").ifEmpty { item.optString("name") }.trim().lowercase()
                            if (title.isNotBlank()) set.add(title)
                        }
                    }
                }
            } else {
                val cast = combinedJson.optJSONArray("cast")
                if (cast != null) {
                    for (i in 0 until cast.length()) {
                        val item = cast.optJSONObject(i) ?: continue
                        val title = item.optString("title").ifEmpty { item.optString("name") }.trim().lowercase()
                        if (title.isNotBlank()) set.add(title)
                    }
                }
            }
            set.also { creditsCache[cacheKey] = it }
        }.getOrDefault(emptySet())
    }
}
