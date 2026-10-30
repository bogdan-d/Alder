package dev.alder

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit

/**
 * One installed addon, spoken to with the Stremio addon protocol: plain GETs for catalogs, metadata and streams.
 * [descriptor] is the account's entry for it as Stremio stores it, kept whole so it can be copied to another profile.
 */
class Addon(val descriptor: JSONObject) {
    private val manifest: JSONObject = descriptor.getJSONObject("manifest")
    val id: String = manifest.optString("id")
    val name: String = manifest.optString("name")
    val transportUrl: String = descriptor.getString("transportUrl")
    /** Stremio's built-ins (Cinemeta, Local Files), which every account already has. */
    val official = descriptor.optJSONObject("flags")?.optBoolean("official") == true
    /** Stremio's own that can't be removed (Cinemeta, Local Files); they can still be moved. */
    val locked = descriptor.optJSONObject("flags")?.optBoolean("protected") == true
    val providesStreams: Boolean = manifest.optJSONArray("resources").items().any {
        (it as? String ?: (it as? JSONObject)?.optString("name")) == "stream"
    }
    /** Reads one person's history or lists (MyTrakt also declares `stream`): never shared or copied by default. */
    val personal = listOf("watchly", "trakt", "simkl", "syncribullet", "aiolists").any { name.contains(it, ignoreCase = true) }
    /** What a profile lends when it shares its streaming sources: its own stream add-ons, not Stremio's or personal ones. */
    val shareable get() = !official && providesStreams && !personal
    private val base = transportUrl.removeSuffix("/manifest.json")
    /**
     * Its settings page, when it has one (`behaviorHints.configurable`), as a fresh setup: the install's own address
     * carries its settings and often a debrid key, which a code on the screen mustn't show.
     */
    val configureUrl: String? = base.takeIf { manifest.optJSONObject("behaviorHints")?.optBoolean("configurable") == true }
        ?.let(::freshConfigureUrl)

    /**
     * [pages]: the catalog takes Stremio's `skip` extra, so a row can ask for the titles after the first page.
     * [required]: extras it can't be asked without, with the value Stremio would pick (`genre` → "None").
     */
    class Catalog(val addon: Addon, val type: String, val id: String, val name: String, val pages: Boolean, val genres: List<String>,
                  val required: Map<String, String> = emptyMap())

    /**
     * Catalogs Home can show. One with a required extra is asked with that extra's default or first option, as
     * Stremio's Discover does: AIOMetadata marks the genre of every catalog not shown on Stremio's board as required
     * (its first option "None"), and skipping those left a Shows page with no rows. A required extra with no options
     * (a search query) still leaves the catalog out.
     */
    val catalogs: List<Catalog> = manifest.optJSONArray("catalogs").objects().mapNotNull { c ->
        val extra = c.optJSONArray("extra").objects()
        val extras = c.optJSONArray("extraSupported").strings() + extra.map { it.optString("name") }
        // The genres it can filter by: the protocol's `genre` extra options, or the older `genres` list.
        val options = extra.firstOrNull { it.optString("name") == "genre" }?.optJSONArray("options").strings()
            .ifEmpty { c.optJSONArray("genres").strings() }
        val requiredNames = c.optJSONArray("extraRequired").strings() + extra.filter { it.optBoolean("isRequired") }.map { it.optString("name") }
        val required = requiredNames.distinct().associateWith { name ->
            val own = extra.firstOrNull { it.optString("name") == name }
            val choices = own?.optJSONArray("options").strings().ifEmpty { if (name == "genre") options else emptyList() }
            own?.text("default")?.takeIf { it in choices } ?: choices.firstOrNull() ?: return@mapNotNull null
        }
        Catalog(this, c.getString("type"), c.getString("id"), c.optString("name").ifEmpty { c.getString("id") }, "skip" in extras,
            options.filterNot { it.equals("None", ignoreCase = true) }, required) // "None" is no filter, not a genre
    }

    /** Whether this addon answers [resource] ("meta", "stream", …) for this type and id. */
    fun serves(resource: String, type: String, id: String): Boolean {
        val entry = manifest.optJSONArray("resources").items()
            .firstOrNull { (it as? String ?: (it as? JSONObject)?.optString("name")) == resource } ?: return false
        val own = entry as? JSONObject
        val types = (own?.optJSONArray("types") ?: manifest.optJSONArray("types")).strings()
        val prefixes = (own?.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))?.strings()
        return type in types && (prefixes == null || prefixes.any { id.startsWith(it) })
    }

    // Catalogs (one [genre] of them with Stremio's `genre` extra) and details are kept on disk as they arrive; the saved*
    // versions read last time's answer, no network.
    fun catalog(c: Catalog, genre: String? = null, timeoutMs: Int = 20_000): List<Meta> =
        JSONObject(Http.get(catalogUrl(c, genre), true, timeoutMs)).optJSONArray("metas").objects().map(::Meta)

    /** The titles after the first [skip] (Stremio's paging extra); not kept on disk. */
    fun catalog(c: Catalog, skip: Int): List<Meta> =
        JSONObject(Http.get(catalogUrl(c, null, skip))).optJSONArray("metas").objects().map(::Meta)

    fun savedCatalog(c: Catalog, genre: String? = null): List<Meta>? =
        Http.savedAnswer(catalogUrl(c, genre))?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONArray("metas")?.objects()?.map(::Meta)

    /** /catalog/type/id/genre=Drama&skip=20.json: the catalog's required extras, the [genre] picked, and the page. */
    private fun catalogUrl(c: Catalog, genre: String?, skip: Int = 0): String {
        val extras = c.required + listOfNotNull(genre?.let { "genre" to it }, ("skip" to "$skip").takeIf { skip > 0 })
        val path = url(arrayOf("catalog", c.type, c.id))
        if (extras.isEmpty()) return path
        return path.removeSuffix(".json") + "/" + extras.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" } + ".json"
    }

    /** Every search catalog the addon advertises, as Stremio's catalog protocol specifies. */
    fun search(query: String): List<Meta> {
        require(query.isNotBlank())
        val catalogs = manifest.optJSONArray("catalogs").objects()
            .filter { c ->
                c.optString("type") in listOf("movie", "series") &&
                    "search" in (c.optJSONArray("extraRequired").strings() + c.optJSONArray("extraSupported").strings() +
                        c.optJSONArray("extra").objects().map { it.optString("name") }) &&
                    c.optJSONArray("extraRequired").strings().all { it == "search" } &&
                    c.optJSONArray("extra").objects().none { it.optBoolean("isRequired") && it.optString("name") != "search" }
            }
            .distinctBy { it.optString("type") to it.optString("id") }
        fun fetch(c: JSONObject): List<Meta> {
            val path = url(arrayOf("catalog", c.getString("type"), c.getString("id"))).removeSuffix(".json")
            val searchUrl = "$path/search=${Uri.encode(query)}.json"
            val answer = JSONObject(Http.get(searchUrl, timeoutMs = 5_000))
            return (answer.optJSONArray("metas") ?: throw IOException("Search catalog failed")).objects().map(::Meta)
        }
        val completion = ExecutorCompletionService<Pair<Int, Result<List<Meta>>>>(catalogSearchPool)
        val pending = catalogs.mapIndexed { index, catalog -> completion.submit { index to runCatching { fetch(catalog) } } }
        val deadline = System.currentTimeMillis() + 5_000
        val answers = mutableListOf<Pair<Int, List<Meta>>>()
        var succeeded = false
        try {
            repeat(catalogs.size) {
                val finished = completion.poll((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    ?: return@repeat
                val (index, result) = finished.get()
                result.getOrNull()?.let { succeeded = true; answers += index to it }
            }
        } finally { pending.forEach { it.cancel(true) } }
        if (catalogs.isNotEmpty() && !succeeded) throw IOException("Search catalog unavailable")
        return answers.sortedBy { it.first }.flatMap { it.second }
    }

    fun meta(type: String, id: String): Meta? = get(true, "meta", type, id).optJSONObject("meta")?.let { meta(it, id) }

    fun savedMeta(type: String, id: String): Meta? = saved("meta", type, id)?.optJSONObject("meta")?.let { meta(it, id) }

    /** Some add-ons leave the title's own id out of its details: it's the one asked for. */
    private fun meta(json: JSONObject, id: String) = Meta(if (json.text("id") == null) json.put("id", id) else json)

    /** A title-specific catalog, when an installed addon advertises the protocol's required `similar` extra. */
    fun similar(type: String, id: String): List<Meta>? {
        val catalog = manifest.optJSONArray("catalogs").objects().firstOrNull { c ->
            c.optString("type") == type && c.optJSONArray("extra").objects().any {
                it.optString("name") == "similar" && it.optBoolean("isRequired")
            } && c.optJSONArray("idPrefixes").strings().let { it.isEmpty() || it.any(id::startsWith) }
        } ?: return null
        val path = url(arrayOf("catalog", type, catalog.getString("id"))).removeSuffix(".json")
        return JSONObject(Http.get("$path/similar=${Uri.encode(id, ":")}.json", true))
            .optJSONArray("metas").objects().map(::Meta)
    }

    /**
     * Playable streams only: torrent-only results (no url) need a streaming server, which this app doesn't have. An error
     * answer throws [HttpError], so a refused key reads as that rather than as no sources; a 404 is just none.
     */
    fun streams(type: String, videoId: String): Streams {
        val all = try {
            get(false, "stream", type, videoId, strict = true).optJSONArray("streams").objects()
        } catch (e: HttpError) {
            if (e.code == 404) emptyList<JSONObject>() else throw e
        }
        return Streams(all.mapNotNull { Stream.from(it) },
            all.count { it.text("url") == null && it.text("infoHash") != null },
            all.count { it.text("url") == null && it.text("infoHash") == null && (it.text("externalUrl") ?: it.text("ytId")) != null })
    }

    /**
     * What Alder can't play, counted to say why: [torrents], a torrent with no debrid link (an infoHash, no url), and
     * [links], a page for a browser or a YouTube video (externalUrl, ytId).
     */
    class Streams(val playable: List<Stream>, val torrents: Int, val links: Int)

    /** Subtitle files for one video (OpenSubtitles and co. through Stremio's `subtitles` resource). */
    fun subtitles(type: String, videoId: String): List<Subtitle> =
        get(false, "subtitles", type, videoId).optJSONArray("subtitles").objects().mapNotNull { s ->
            Subtitle(s.text("url") ?: return@mapNotNull null, s.text("lang") ?: "und", name)
        }

    // Ids such as "tt0903747:1:2" keep their colons, like Stremio's own requests.
    private fun url(path: Array<out String>) = base + "/" + path.joinToString("/") { Uri.encode(it, ":=") } + ".json"

    private fun get(keep: Boolean, vararg path: String, strict: Boolean = false) = JSONObject(Http.get(url(path), keep, strict = strict))

    private fun saved(vararg path: String) = Http.savedAnswer(url(path))?.let { runCatching { JSONObject(it) }.getOrNull() }

    private companion object { val catalogSearchPool = Images.newestFirst(8) }
}

/**
 * A title's details from the first add-on, in the account's order, that has them, as Stremio's apps do: one that fails
 * or doesn't know the title (a metadata add-on without its API key, a catalog add-on that claims details) passes to the
 * next, instead of "Couldn't load this title's details" on every title. [together] asks them all at once, for a screen
 * that waits on the answer: a dead add-on then costs no more than its own wait. Otherwise one at a time, so Home's
 * cards don't ask every add-on for every title.
 */
fun List<Addon>.meta(type: String, id: String, together: Boolean = false): Meta? {
    val serving = filter { it.serves("meta", type, id) }
    fun ask(a: Addon) = runCatching { a.meta(type, id) }
        .onFailure { android.util.Log.w("Alder", "details from ${a.name} failed", it) }.getOrNull()
        .also { if (it == null) android.util.Log.i("Alder", "no details for $type $id from ${a.name}") }
    if (!together || serving.size < 2) return serving.firstNotNullOfOrNull(::ask)
    return serving.map { a -> metaPool.submit<Meta?> { ask(a) } }.firstNotNullOfOrNull { it.get() }
}

/** Last time's details from disk, from the first add-on in the account's order that kept some; no network. */
fun List<Addon>.savedMeta(type: String, id: String): Meta? =
    filter { it.serves("meta", type, id) }.firstNotNullOfOrNull { runCatching { it.savedMeta(type, id) }.getOrNull() }

private val metaPool = java.util.concurrent.Executors.newCachedThreadPool()

class Meta(json: JSONObject) {
    val id: String = json.getString("id")
    val type: String = json.optString("type")
    val name: String = json.optString("name")
    val poster = json.text("poster")
    val background = json.text("background")
    /** The title as artwork (AIOMetadata and Cinemeta provide it), shown instead of plain text when present. */
    val logo = json.text("logo")
    /** "poster" (the default), "square" or "landscape": live channels send their logo as a square or wide picture. */
    val posterShape = json.text("posterShape")
    val description = json.text("description")
    val year = json.text("releaseInfo")?.trimEnd('–', '-', ' ') // "2026–" (still running) reads as 2026 everywhere
    val runtime = json.text("runtime")?.let(::runtime)
    val rating = json.text("imdbRating")
    /** Content rating (PG-13, TV-MA) when the meta add-on sends one; otherwise IMDb fills it in on the title page. */
    val certification = json.text("certification") ?: json.text("contentRating")
    val genres = json.optJSONArray("genres").strings().take(3)
    val directors: List<String> = (json.optJSONArray("director") ?: json.optJSONArray("directors"))?.strings()
        ?: json.text("director")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList()
    val castMembers: List<CastMember> = (json.optJSONArray("cast") ?: json.optJSONArray("actors"))?.items()?.mapNotNull { item ->
        when (item) {
            is String -> item.trim().takeIf { it.isNotBlank() }?.let { CastMember(it) }
            is JSONObject -> {
                val name = item.text("name") ?: item.text("actor") ?: return@mapNotNull null
                val character = item.text("character") ?: item.text("role")
                val photo = item.text("photo") ?: item.text("image") ?: item.text("profile") ?: item.text("thumbnail")
                CastMember(name.trim(), character?.trim(), photo?.trim())
            }
            else -> null
        }
    } ?: emptyList()
    val cast: List<String> = castMembers.map { it.name }
    val info = listOfNotNull(year, runtime, genres.joinToString(" · ").ifEmpty { null }).joinToString("   ")
    val trailerStreams: List<Stream> = parseTrailerStreams(json)
    val trailerYtId: String? = parseTrailerYtId(json)

    /** Episodes in play order: by season then episode, specials (season 0) last. */
    val videos: List<Video> = json.optJSONArray("videos").objects()
        .filter { it.text("id") != null } // one episode without an id shouldn't lose the whole title
        .map {
            Video(it.getString("id"), it.text("title") ?: it.text("name") ?: "", it.optInt("season"), it.optInt("episode"),
                it.text("thumbnail"), it.text("overview") ?: it.text("description"),
                it.text("released") ?: it.text("available"))
        }
        .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
}

/** [lang] is what the addon sends: ISO 639-2 ("eng") from OpenSubtitles, sometimes a two-letter code. */
class Subtitle(val url: String, val lang: String, val addon: String) {
    val language: String get() = Languages.name(lang)
    val mimeType: String get() = url.substringBefore('?').lowercase().let {
        when {
            it.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            it.endsWith(".ass") || it.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

internal fun parseCastMember(name: String?, character: String? = null, photo: String? = null, role: String = "actor", tmdbId: Int? = null): CastMember? {
    val cleanName = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return CastMember(cleanName, character?.trim()?.takeIf { it.isNotBlank() }, photo?.trim()?.takeIf { it.isNotBlank() })
}

class CastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    val role: String = "actor",
    val tmdbId: Int? = null,
) {
    val isDirector: Boolean get() = role == "director"
    val initials: String get() {
        val parts = name.trim().split(Regex("""\s+""")).filter {
            it.isNotBlank() && it.lowercase().trimEnd('.') !in setOf("jr", "sr", "ii", "iii", "iv")
        }.ifEmpty { name.trim().split(Regex("""\s+""")).filter { it.isNotBlank() } }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
        }
    }
}

class Video(val id: String, val title: String, val season: Int, val episode: Int, val thumbnail: String?,
            val overview: String?, val released: String?)

class Stream(
    val title: String, val description: String, val url: String, val headers: Map<String, String>,
    val size: Long, val filename: String?, val bingeGroup: String?,
) {
    /**
     * False for addon "actions" dressed up as streams, such as MyTrakt's "Mark Watched" (a 27 KB placeholder file).
     * They're left out of the list and never picked. Unknown size counts as a video.
     */
    val isVideo get() = size == 0L || size >= 50L * 1024 * 1024

    /**
     * The addon's name for it on one line: "Torrentio (2160p)" becomes "Torrentio  ·  2160p". A debrid tag ("[RD+]")
     * becomes the service's name at the end, as the player shows it: "Torrentio  ·  2160p  ·  Real-Debrid".
     */
    val label get() = title.lines().joinToString(" ") { it.trim() }.replace(debridTag, "").trim()
        .replace(Regex("\\s*\\((.+?)\\)\\s*$"), "  ·  $1") + (debrid?.let { "  ·  ${it.name}" } ?: "")

    /** The debrid service it comes through, null for a direct link. */
    val debrid: Debrid? = debridOf(title, url)

    // What the release is, read from its file name and the addon's labels (AIOStreams writes "2160p", "DV", "HEVC"
    // into them; the file name says it for every addon). ponytail: name matching, not the file's real tracks.
    private val words = "$filename $title $description".lowercase()
    val height = when {
        Regex("2160|\\b4k\\b|\\buhd\\b").containsMatchIn(words) -> 2160
        "1080" in words -> 1080
        "720" in words -> 720
        "576" in words -> 576
        "480" in words -> 480
        else -> 0
    }
    val isHdr get() = hdr || dolbyVision
    /** Dolby Vision with no HDR10 base (profile 5, common on web releases): only a profile 5 decoder shows it right. */
    val dolbyVisionOnly get() = dolbyVision && !hdr
    val dolbyVision = Regex("\\b(dv|dovi|dolby.?vision)\\b").containsMatchIn(words)
    val hdr = Regex("\\bhdr|\\bp8\\b|hybrid").containsMatchIn(words) // P8 and hybrid DV carry an HDR10 base
    private val hevc = Regex("hevc|[xh].?265").containsMatchIn(words)
    private val av1 = Regex("\\bav1\\b").containsMatchIn(words)

    // The release type and extras, for Settings' stream preferences.
    val remux = Regex("\\bremux\\b").containsMatchIn(words)
    val bluray = !remux && Regex("blu.?ray|\\bbd.?rip|\\bbr.?rip").containsMatchIn(words)
    val web = Regex("web.?(dl|rip)|\\bweb\\b").containsMatchIn(words)
    /** Filmed in a cinema or a screener: never picked. */
    val cam = Regex("\\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine|scr|screener|dvdscr)\\b").containsMatchIn(words)
    private val threeD = Regex("\\b3d\\b").containsMatchIn(words)
    private val hardSubs = Regex("\\bhc\\b|hardcoded|hardsub").containsMatchIn(words)
    /** 3D or subtitles burnt into the picture: picked last. */
    val awkward get() = threeD || hardSubs
    val bigAudio = Regex("atmos|truehd|dts.?hd|dts.?x\\b").containsMatchIn(words) // what a receiver gains from
    val multiAudio = Regex("\\b(multi|dual)\\b").containsMatchIn(words)
    val dubbed = Regex("\\bdub(bed)?\\b").containsMatchIn(words)
    /** The audio languages the names give, as two-letter codes; empty when they give none (see [audioLanguagesIn]). */
    val audioLanguages = audioLanguagesIn(words)
    /** Whether the names mention this language ("hindi" for "hi"). */
    fun mentions(language: String) = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH).lowercase().let { it.length > 2 && it in words }

    /** Short tags for the source list: "REMUX · DV · Atmos". */
    val tags get() = listOfNotNull(
        "REMUX".takeIf { remux }, "BluRay".takeIf { bluray }, "WEB".takeIf { web && !remux && !bluray },
        "DV".takeIf { dolbyVision }, "HDR".takeIf { hdr }, "Atmos".takeIf { "atmos" in words },
        "TrueHD".takeIf { "truehd" in words }, "DTS-HD".takeIf { Regex("dts.?hd").containsMatchIn(words) },
        "Multi audio".takeIf { multiAudio }, "Dub".takeIf { dubbed }, "3D".takeIf { threeD }, "Hard subs".takeIf { hardSubs },
    )

    /**
     * Whether this TV can decode it at all. Dolby Vision without an HDR10 base isn't left out here: many TVs report
     * their Dolby Vision decoders in ways the check misses, so it's listed last instead (StreamsActivity.rank).
     */
    fun playableOn(tv: Device): Boolean = (!hevc || tv.hevc) && (!av1 || tv.av1)

    /** How well it suits this TV, higher first: nothing above the screen's resolution, HDR only on an HDR screen. */
    fun fit(tv: Device): Int = (if (height > tv.height) 0 else 2) + (if (hdr || dolbyVision) (if (tv.hdr) 0 else -1) else 0)

    fun toJson(): JSONObject = JSONObject().put("name", title).put("description", description).put("url", url)
        .put("headers", JSONObject(headers)).put("size", size).put("filename", filename).put("binge", bingeGroup)

    companion object {
        fun from(json: JSONObject): Stream? {
            val url = json.text("url") ?: return null
            val hints = json.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            return Stream(
                json.text("name") ?: "",
                json.text("description") ?: json.text("title") ?: "",
                url,
                headers?.keys()?.asSequence()?.associateWith { headers.getString(it) } ?: emptyMap(),
                hints?.optLong("videoSize") ?: 0L,
                hints?.text("filename"),
                hints?.text("bingeGroup"),
            )
        }

        /** Back from [toJson], for the player's list of sources to try. */
        fun read(json: JSONObject): Stream {
            val headers = json.optJSONObject("headers") ?: JSONObject()
            return Stream(json.optString("name"), json.optString("description"), json.getString("url"),
                headers.keys().asSequence().associateWith { headers.getString(it) }, json.optLong("size"),
                json.text("filename"), json.text("binge"))
        }
    }
}

fun JSONArray?.items(): List<Any> = if (this == null) emptyList() else (0 until length()).map { get(it) }

fun JSONArray?.objects(): List<JSONObject> = items().filterIsInstance<JSONObject>()

fun JSONArray?.strings(): List<String> = items().filterIsInstance<String>()

/** The string at [key], or null when it's missing, JSON null or blank (optString would return "null" for JSON null). */
/** "147 min" reads "2h 27m" as on the mock; under an hour, and anything not in minutes, stays as the addon wrote it. */
/**
 * Audio languages, as two-letter codes, from a stream's names: Torrentio's flags (🇮🇹), AIOStreams' "English | Hindi",
 * and file-name tags (ITA, HINDI, TRUEFRENCH). ponytail: names only, not the file's real tracks; most English releases
 * name no language at all, so an empty set usually means English.
 */
internal fun audioLanguagesIn(text: String): Set<String> {
    val found = mutableSetOf<String>()
    // Regional indicators: two in a row make a country's flag, read in pairs so 🇺🇸🇪🇸 isn't also "SE".
    val letters = text.codePoints().toArray().map { it - 0x1F1E6 }
    var i = 0
    while (i < letters.size - 1) {
        if (letters[i] in 0..25 && letters[i + 1] in 0..25) {
            flagLanguages["${'a' + letters[i]}${'a' + letters[i + 1]}"]?.let(found::add)
            i += 2
        } else i++
    }
    Regex("[a-z]+").findAll(text.lowercase()).forEach { m -> languageWords[m.value]?.let(found::add) }
    return found
}

private val flagLanguages = mapOf("gb" to "en", "us" to "en", "au" to "en", "it" to "it", "in" to "hi", "es" to "es",
    "mx" to "es", "fr" to "fr", "de" to "de", "pt" to "pt", "br" to "pt", "ru" to "ru", "jp" to "ja", "kr" to "ko",
    "cn" to "zh", "tw" to "zh", "hk" to "zh", "nl" to "nl", "pl" to "pl", "tr" to "tr", "se" to "sv", "dk" to "da",
    "no" to "no", "fi" to "fi", "gr" to "el", "il" to "he", "sa" to "ar", "ae" to "ar", "eg" to "ar", "th" to "th",
    "vn" to "vi", "id" to "id", "ua" to "uk", "cz" to "cs", "hu" to "hu", "ro" to "ro", "ir" to "fa", "pk" to "ur", "bd" to "bn")

// English names and the release tags that stand for a language. Short tags that are also common words ("por", "mal")
// are left out.
private val languageWords = mapOf("english" to "en", "eng" to "en", "hindi" to "hi", "hin" to "hi", "italian" to "it",
    "ita" to "it", "spanish" to "es", "spa" to "es", "esp" to "es", "latino" to "es", "castellano" to "es",
    "french" to "fr", "fre" to "fr", "fra" to "fr", "vff" to "fr", "truefrench" to "fr", "german" to "de", "ger" to "de",
    "deu" to "de", "portuguese" to "pt", "russian" to "ru", "rus" to "ru", "japanese" to "ja", "jpn" to "ja",
    "korean" to "ko", "kor" to "ko", "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "dutch" to "nl",
    "polish" to "pl", "turkish" to "tr", "swedish" to "sv", "danish" to "da", "norwegian" to "no", "finnish" to "fi",
    "greek" to "el", "hebrew" to "he", "arabic" to "ar", "thai" to "th", "vietnamese" to "vi", "indonesian" to "id",
    "ukrainian" to "uk", "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "persian" to "fa", "urdu" to "ur",
    "bengali" to "bn", "tamil" to "ta", "tam" to "ta", "telugu" to "te", "tel" to "te", "malayalam" to "ml",
    "kannada" to "kn", "marathi" to "mr")

/** A debrid service ([short] as add-ons tag it, "RD"); [cached]: ready to start at once, null when the add-on doesn't say. */
class Debrid(val name: String, val short: String, val cached: Boolean?) {
    /** The source list's tag: "RD ⚡" when cached. */
    val tag get() = if (cached == true) "$short ⚡" else short
}

private val debridServices = listOf(
    Triple("RD", "Real-Debrid", listOf("real-debrid", "realdebrid", "rdeb.io")),
    Triple("AD", "AllDebrid", listOf("alldebrid", "debrid.it")),
    Triple("PM", "Premiumize", listOf("premiumize", "energycdn")),
    Triple("TB", "TorBox", listOf("torbox", "tb-cdn")),
    Triple("DL", "Debrid-Link", listOf("debrid-link", "debridlink")),
    Triple("ED", "EasyDebrid", listOf("easydebrid")),
    Triple("OC", "Offcloud", listOf("offcloud")),
    Triple("PIO", "Put.io", listOf("put.io", "putio")),
)

// Add-ons' tags: Torrentio "[RD+]" (cached) and "[RD download]", AIOStreams and Comet "[RD⚡]" and "[RD⏳]",
// MediaFusion "⚡RD". The code must stand alone, so "[RDX]" or "HDR" isn't one.
private val debridCodes = debridServices.joinToString("|") { it.first }
private val debridTag = Regex("\\[($debridCodes)\\s*(\\+|⚡️?|⏳|download)?]\\s*", RegexOption.IGNORE_CASE)
private val debridFlash = Regex("(⚡️?)\\s*($debridCodes)\\b")

/**
 * The debrid service from a source's name, else from its link (Torrentio's /resolve/realdebrid/…, or the service's
 * own download host). Null for a direct link or a service it doesn't know.
 */
internal fun debridOf(name: String, url: String): Debrid? {
    fun service(code: String) = debridServices.first { it.first.equals(code, ignoreCase = true) }
    debridTag.find(name)?.let { m ->
        val mark = m.groupValues[2].lowercase()
        val cached = when { mark == "+" || mark.startsWith("⚡") -> true; mark == "⏳" || mark == "download" -> false; else -> null }
        return service(m.groupValues[1]).let { Debrid(it.second, it.first, cached) }
    }
    debridFlash.find(name)?.let { m -> return service(m.groupValues[2]).let { Debrid(it.second, it.first, true) } }
    val link = url.lowercase()
    return debridServices.firstOrNull { (_, _, hosts) -> hosts.any { it in link } }?.let { Debrid(it.second, it.first, null) }
}

internal fun runtime(text: String): String {
    // "147 min", or "2h35min" / "2h 35m" as some addons write it.
    val hm = Regex("""^(\d+)\s*h\s*(\d+)\s*m(in)?$""").find(text.trim())?.groupValues
    val minutes = hm?.let { it[1].toInt() * 60 + it[2].toInt() }
        ?: Regex("""^(\d+)\s*min""").find(text.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return text
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h %02dm".format(minutes % 60)
}

fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * An add-on's `/configure` page with its settings left out of the address: the path parts that hold them (Torrentio's
 * "providers=…|realdebrid=KEY", AIOStreams' long encoded blob) go, and the rest of the path stays. ponytail: a guess by
 * shape (`=`, `|`, `%`, `,` or 24+ characters); an add-on with a short plain key in its path would still show it.
 */
internal fun freshConfigureUrl(base: String): String {
    val start = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val slash = base.indexOf('/', start).let { if (it < 0) base.length else it }
    val path = base.substring(slash).split('/').filter { seg -> seg.isNotEmpty() && seg.length < 24 && seg.none { it in "=|%," } }
    return base.substring(0, slash) + path.joinToString("") { "/$it" } + "/configure"
}

/** Direct playable trailer URLs from a metadata add-on's `trailerStreams`. */
internal fun parseTrailerStreams(json: JSONObject): List<Stream> =
    json.optJSONArray("trailerStreams")?.objects()?.mapNotNull { item ->
        val url = item.text("url") ?: return@mapNotNull null
        val title = item.text("title")?.takeIf { it.isNotBlank() } ?: "Trailer"
        Stream(title, "", url, emptyMap(), 0L, null, null)
    }.orEmpty()

/** The official YouTube trailer ID from `trailerStreams` or `trailers` (Cinemeta and AIOMetadata). */
internal fun parseTrailerYtId(json: JSONObject): String? =
    json.optJSONArray("trailerStreams")?.objects()?.firstNotNullOfOrNull { it.text("ytId") }
        ?: json.optJSONArray("trailers")?.objects()
            ?.sortedBy { if (it.text("type").equals("Trailer", ignoreCase = true)) 0 else 1 }
            ?.firstNotNullOfOrNull { it.text("source") ?: it.text("ytId") }

internal fun trailerNodePriority(type: String, name: String): Int = when {
    type.equals("Trailer", ignoreCase = true) -> 0
    name.contains("trailer", ignoreCase = true) -> 1
    else -> 2
}

internal fun orderTrailerStreams(videoTitle: String, playbackUrls: List<Pair<String, String>>): List<Stream> {
    val title = videoTitle.ifBlank { "Trailer" }
    val rankOrder = listOf("1080p", "auto", "720p", "480p", "sd")
    return playbackUrls
        .filter { it.second.isNotBlank() }
        .sortedBy { (quality, _) ->
            rankOrder.indexOf(quality.lowercase()).let { if (it >= 0) it else rankOrder.size }
        }
        .distinctBy { it.second }
        .map { (quality, url) ->
            val label = if (quality.isNotBlank() && !quality.equals("AUTO", ignoreCase = true)) "$title ($quality)" else title
            Stream(label, quality, url, emptyMap(), 0L, null, null)
        }
}

/** The US content rating ("R", "TV-MA") from IMDb's `certificate` response, or null when IMDb has none. */
internal fun parseImdbCertificate(json: JSONObject): String? =
    json.optJSONObject("data")?.optJSONObject("title")?.optJSONObject("certificate")?.text("rating")
        ?.trim()?.takeIf { it.isNotBlank() }

private val imdbCertificateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

internal fun imdbCertificate(imdbId: String): String? {
    if (!imdbId.startsWith("tt")) return null
    imdbCertificateCache[imdbId]?.let { return it }
    return runCatching {
        val query = JSONObject().put("query", "{title(id:\"$imdbId\"){certificate{rating}}}").toString()
        val raw = Http.post(
            "https://api.graphql.imdb.com/",
            query,
            timeoutMs = 5_000,
            headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized")
        )
        parseImdbCertificate(JSONObject(raw))?.also { imdbCertificateCache[imdbId] = it }
    }.getOrNull()
}

/**
 * Direct MP4 and HLS trailer streams from IMDb's `primaryVideos` response, preferring official trailers over clips and
 * ordering stream qualities from 1080p and adaptive HLS down to SD so ExoPlayer can fall back if one fails.
 */
internal fun parseImdbTrailerStreams(json: JSONObject): List<Stream> {
    val edges = json.optJSONObject("data")?.optJSONObject("title")
        ?.optJSONObject("primaryVideos")?.optJSONArray("edges")?.objects().orEmpty()
    val nodes = edges.mapNotNull { it.optJSONObject("node") }.filter { node ->
        node.optJSONArray("playbackURLs")?.objects()?.any { it.text("url") != null } == true
    }
    val bestNode = nodes.minByOrNull { node ->
        val type = node.optJSONObject("contentType")?.optJSONObject("displayName")?.text("value").orEmpty()
        val name = node.optJSONObject("name")?.text("value").orEmpty()
        trailerNodePriority(type, name)
    } ?: return emptyList()
    val videoTitle = bestNode.optJSONObject("name")?.text("value") ?: "Trailer"
    val urls = bestNode.optJSONArray("playbackURLs")?.objects().orEmpty()
        .mapNotNull { u ->
            val url = u.text("url") ?: return@mapNotNull null
            val quality = u.optJSONObject("displayName")?.text("value")
                ?: u.text("videoMimeType") ?: ""
            quality to url
        }
    return orderTrailerStreams(videoTitle, urls)
}

private val imdbTrailerCache = java.util.concurrent.ConcurrentHashMap<String, List<Stream>>()

internal fun imdbTrailerStreams(imdbId: String): List<Stream> {
    if (!imdbId.startsWith("tt")) return emptyList()
    imdbTrailerCache[imdbId]?.let { return it }
    return runCatching {
        val query = JSONObject().put(
            "query",
            "{title(id:\"$imdbId\"){primaryVideos(first:5){edges{node{id name{value}contentType{displayName{value}}playbackURLs{displayName{value}url videoMimeType}}}}}}"
        ).toString()
        val raw = Http.post(
            "https://api.graphql.imdb.com/",
            query,
            timeoutMs = 5_000,
            headers = mapOf("x-imdb-client-name" to "imdb-web-next-localized")
        )
        parseImdbTrailerStreams(JSONObject(raw)).also { imdbTrailerCache[imdbId] = it }
    }.getOrDefault(emptyList())
}

