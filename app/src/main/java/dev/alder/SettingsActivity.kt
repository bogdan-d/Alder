package dev.alder

import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Choices kept on this TV. Most are shared by every profile (they're about the TV and the sound system); taste (release
 * type, dubs and audio language) is kept per profile under "key@profileId".
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** The language Alder shows ("es", "pt-BR"), for the whole TV; empty follows the TV's own language. */
    fun appLanguage(c: Context): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) // the system keeps it, and its own per-app language page can change it
            c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
        else prefs(c).getString("appLanguage", "").orEmpty()
    fun setAppLanguage(c: Context, tag: String) {
        prefs(c).edit().putString("appLanguage", tag).apply()
        // Android 13 and later keep the choice themselves, so their own per-app language page shows it too.
        if (android.os.Build.VERSION.SDK_INT >= 33) c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
    }
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun displayHeight(c: Context) = prefs(c).getInt("displayHeight", 0) // 0: auto
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    /** "": as the addon orders them, "dv" or "hdr10": that first, "off": plain releases only. */
    fun hdrPref(c: Context): String {
        val p = prefs(c)
        if (!p.contains("hdrPref")) p.edit().putString("hdrPref", if (p.getBoolean("hdr", true)) "" else "off").remove("hdr").apply() // was On/Off
        return p.getString("hdrPref", "").orEmpty()
    }
    fun hdr(c: Context) = hdrPref(c) != "off"
    /** "": no preference, "remux", "encode" (smaller BluRay and web encodes) or "web". */
    fun release(c: Context) = prefs(c).getString(profileKey(c, "release"), "").orEmpty()
    /** "": no preference, "original": dubs last, "mine": the audio language first. */
    fun dub(c: Context) = prefs(c).getString(profileKey(c, "dub"), "").orEmpty()
    fun profileKey(c: Context, key: String) = "$key@${Profiles.active(c)?.id}"
    /**
     * Titles this profile hid from Continue Watching on this TV: id to {name, timeOffset}, so Settings can put them
     * back. ponytail: this TV only; titles hidden in Stremio elsewhere aren't listed.
     */
    fun hidden(c: Context) = runCatching { org.json.JSONObject(prefs(c).getString(profileKey(c, "hidden"), null)!!) }
        .getOrDefault(org.json.JSONObject())
    fun setHidden(c: Context, all: org.json.JSONObject) = prefs(c).edit().putString(profileKey(c, "hidden"), all.toString()).apply()
    fun blurThumbnails(c: Context) = prefs(c).getBoolean(profileKey(c, "blurThumbnails"), prefs(c).getBoolean("blurThumbnails", false))
    fun hiddenRows(c: Context): Set<String> = prefs(c).getStringSet(profileKey(c, "hiddenRows"), emptySet()) ?: emptySet()
    fun hideRow(c: Context, rowTitle: String) {
        val current = hiddenRows(c).toMutableSet()
        current.add(rowTitle)
        prefs(c).edit().putStringSet(profileKey(c, "hiddenRows"), current).apply()
    }
    fun unhideRow(c: Context, rowTitle: String) {
        val current = hiddenRows(c).toMutableSet()
        current.remove(rowTitle)
        prefs(c).edit().putStringSet(profileKey(c, "hiddenRows"), current).apply()
    }
    fun startTab(c: Context) = prefs(c).getString(profileKey(c, "startTab"), "home").orEmpty()
    /** Whether a tab after Home shows in the top bar, by its id in [tabsFor]; hiding an add-on's tab keeps the add-on. */
    fun tabShown(c: Context, id: String) = prefs(c).getBoolean(profileKey(c, tabKey(id)), true)
    fun setTabShown(c: Context, id: String, shown: Boolean) = prefs(c).edit().putBoolean(profileKey(c, tabKey(id)), shown).apply()
    // Movies, Shows and Live keep the keys of the switches they had before Settings › Tabs, so those choices carry over.
    private fun tabKey(id: String) = when (id) {
        "movies" -> "showMovies"
        "shows" -> "showShows"
        "live" -> "showLive"
        "library" -> "showLibrary"
        else -> "showTab:" + id.removePrefix("type:")
    }
    /** The tabs after Home in this profile's order ([orderTabs] fills in the ones it never placed). */
    fun tabOrder(c: Context): List<String> =
        runCatching { org.json.JSONArray(prefs(c).getString(profileKey(c, "tabOrder"), "[]")).strings() }.getOrDefault(emptyList())
    fun setTabOrder(c: Context, ids: List<String>) = prefs(c).edit().putString(profileKey(c, "tabOrder"), org.json.JSONArray(ids).toString()).apply()
    fun featuredHome(c: Context) = prefs(c).getBoolean(profileKey(c, "featuredHome"), false)
    /** "imdb", "letterboxd" or "both"; Letterboxd needs [mdblistKey] and covers films only. */
    fun ratings(c: Context) = prefs(c).getString("ratings", "imdb").orEmpty().ifEmpty { "imdb" }
    fun mdblistKey(c: Context) = prefs(c).getString("mdblistKey", "").orEmpty()
    fun setMdblistKey(c: Context, key: String) = prefs(c).edit().putString("mdblistKey", key.trim()).apply()
    /** Letterboxd is asked for only when it can show: a film, a key, and a pick that includes it. */
    fun wantsLetterboxd(c: Context, type: String, id: String) =
        type == "movie" && id.startsWith("tt") && ratings(c) != "imdb" && mdblistKey(c).isNotEmpty()
    /** A removed profile's taste goes with it, so a new profile in its place starts with none. */
    fun forget(c: Context, profileId: String) = prefs(c).edit().apply {
        listOf("release", "dub", "audioLang", "subLang", "hidden", "blurThumbnails", "hiddenRows", "startTab", "showMovies", "showShows", "showLive", "showLibrary", "tabOrder", "featuredHome", "recentSubLangs", "recentAudioLangs").forEach { remove("$it@$profileId") }
        prefs(c).all.keys.filter { (it.startsWith("title:") || it.startsWith("showTab:")) && it.endsWith("@$profileId") }.forEach(::remove)
    }.apply()

    /**
     * What the viewer picked in the player's Audio & subtitles for one title (a series' episodes share it), so the next
     * episode and a resume start the same way. Language codes; subtitles "" is Off; null where nothing was picked.
     * [subtitle]: the exact subtitle picked for video [subtitleVideo] ("addon:<url>" or "file:<track id>"), as Stremio
     * keeps it per video.
     */
    class TitleChoice(val audio: String?, val subtitles: String?, val subtitleVideo: String? = null, val subtitle: String? = null)

    fun titleChoice(c: Context, metaId: String): TitleChoice? = prefs(c).getString(profileKey(c, "title:$metaId"), null)
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?.let { TitleChoice(it.text("audio"), if (it.has("subtitles")) it.optString("subtitles") else null,
            it.text("subtitleVideo"), it.text("subtitle")) }

    fun pickedInPlayer(c: Context, metaId: String, audio: String? = null, subtitles: String? = null,
                       video: String? = null, subtitle: String? = null) {
        val key = profileKey(c, "title:$metaId")
        val json = prefs(c).getString(key, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()
        audio?.let { json.put("audio", it) }
        subtitles?.let { json.put("subtitles", it) }
        video?.let { json.put("subtitleVideo", it).put("subtitle", subtitle.orEmpty()) }
        prefs(c).edit().putString(key, json.toString()).apply()
    }
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    /** Settings › Source list: true shows each source as its add-on wrote it, in a wider list. */
    fun addonText(c: Context) = prefs(c).getBoolean("addonText", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun decodeAudio(c: Context) = prefs(c).getBoolean("decodeAudio", false)
    /** Settings › Text and poster size, for this TV: 1 is the House Lights size, more makes every screen bigger. */
    fun uiScale(c: Context) = prefs(c).getInt("uiScale", 100).coerceIn(100, 115) / 100f
    /** Settings › Video player: null plays in Alder, else the package of the video app each source goes to. */
    fun externalPlayer(c: Context) = prefs(c).getString("videoPlayer", "").orEmpty().ifEmpty { null }
    fun clearDialogue(c: Context) = prefs(c).getBoolean("clearDialogue", false)
    fun setClearDialogue(c: Context, on: Boolean) = prefs(c).edit().putBoolean("clearDialogue", on).apply()
    /** "on", "foreign" (only when audio differs or forced), "off". Migrates old Boolean "subtitles". */
    fun subPref(c: Context): String {
        val p = prefs(c)
        val key = profileKey(c, "subPref")
        val oldKey = "subtitles"
        if (!p.contains(key)) {
            val fallback = if (p.contains(oldKey)) {
                val oldBool = runCatching { p.getBoolean(oldKey, true) }.getOrDefault(true)
                if (oldBool) "on" else "off"
            } else "on"
            p.edit().putString(key, fallback).remove(oldKey).apply()
        }
        return p.getString(key, "on").orEmpty().ifEmpty { "on" }
    }
    fun subtitles(c: Context) = subPref(c) != "off"
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString(profileKey(c, "audioLang"), prefs(c).getString("audioLang", ""))
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    /** Two-letter code of the subtitles to start with: the TV's language unless one is picked. */
    fun subtitleLanguage(c: Context): String = prefs(c).getString(profileKey(c, "subLang"), "")
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleColorName(c: Context): String = prefs(c).getString("subColor", "white").orEmpty().ifEmpty { "white" }
    fun subtitleColor(c: Context) = subtitleColorValue(subtitleColorName(c))
    /** The player's picture: "fit" (whole picture, bars), "zoom" (fills the screen, crops) or "stretch". */
    fun pictureFit(c: Context) = prefs(c).getString("pictureFit", "fit").orEmpty().ifEmpty { "fit" }
    fun setPictureFit(c: Context, fit: String) = prefs(c).edit().putString("pictureFit", fit).apply()
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
    fun setSubtitleSize(c: Context, size: Int) = prefs(c).edit().putInt("subSize", size).apply()
    fun setSubtitleColor(c: Context, color: String) = prefs(c).edit().putString("subColor", color).apply()
    fun setSubtitleBackground(c: Context, on: Boolean) = prefs(c).edit().putBoolean("subBackground", on).apply()

    fun recentSubtitles(c: Context): List<String> = runCatching {
        val raw = prefs(c).getString(profileKey(c, "recentSubLangs"), null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecentSubtitle(c: Context, langCode: String) {
        val updated = updateRecentLanguages(recentSubtitles(c), langCode, 3)
        prefs(c).edit().putString(profileKey(c, "recentSubLangs"), org.json.JSONArray(updated).toString()).apply()
    }

    fun recentAudio(c: Context): List<String> = runCatching {
        val raw = prefs(c).getString(profileKey(c, "recentAudioLangs"), null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecentAudio(c: Context, langCode: String) {
        val updated = updateRecentLanguages(recentAudio(c), langCode, 3)
        prefs(c).edit().putString(profileKey(c, "recentAudioLangs"), org.json.JSONArray(updated).toString()).apply()
    }
}

/** ARGB color for subtitle text from a preference name. */
fun subtitleColorValue(name: String): Int = when (name.trim().lowercase()) {
    "gray" -> 0xFFBFBFBF.toInt()
    "yellow" -> 0xFFFFE600.toInt()
    "cyan" -> 0xFF00FFFF.toInt()
    "cream" -> 0xFFFFF5C0.toInt()
    "warm_white", "warm white", "warmwhite" -> 0xFFF3EEE6.toInt()
    else -> 0xFFFFFFFF.toInt()
}

/**
 * Every source writes languages its own way: the TV "es", a file's track "spa" or "es-MX", OpenSubtitles "spa", "ger"
 * or "pob", some add-ons "Spanish". [code] turns any of them into one code, so all comparisons go through it: two
 * letters, except Brazilian Portuguese ("pt-BR"), which OpenSubtitles keeps apart from Portugal's.
 */
object Languages {
    const val BRAZIL = "pt-BR"
    // ISO 639-2/B codes (OpenSubtitles sends these) where they differ from 639-2/T, OpenSubtitles' own, and Java's old ones.
    private val aliases = mapOf("alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "spl" to "es", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy", "pom" to "pt",
        "scc" to "sr", "scr" to "hr", "ze" to "zh", "zht" to "zh", "zhe" to "zh", "iw" to "he", "in" to "id", "ji" to "yi")
    private val brazilian = setOf("pob", "pb", "ptbr", "brazilian", "brazilian portuguese", "portuguese (brazil)",
        "portuguese (brazilian)", "portuguese (br)")
    private val iso by lazy { Locale.getISOLanguages().map { aliases[it] ?: it }.distinct() }
    private val threeLetter by lazy { iso.associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) } }
    private val englishNames by lazy { iso.associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() } }
    private val common = listOf("en", "es", "fr", "de", "pt", BRAZIL, "it", "hi", "ar", "ja", "ko", "zh", "ru")

    /** The code ("es", "pt-BR"), or null for none or unknown ("und"). */
    fun code(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('_', '-')?.ifEmpty { null } ?: return null
        if (s in brazilian) return BRAZIL
        val parts = s.split('-')
        val base = parts[0].let { aliases[it] ?: when (it.length) {
            2 -> it.takeIf { it in iso }
            3 -> threeLetter[it]
            else -> englishNames[it]
        } } ?: return null
        return if (base == "pt" && parts.getOrNull(1) == "br") BRAZIL else base
    }
    /** "pt-BR" -> "pt". */
    fun base(code: String) = code.substringBefore('-')
    fun same(a: String?, b: String?) = code(a).let { it != null && it == code(b) }
    /** 2 for the same language, 1 for the same language from another country (Portugal's for Brazil's), else 0. */
    fun match(a: String?, b: String?): Int {
        val x = code(a) ?: return 0
        val y = code(b) ?: return 0
        return if (x == y) 2 else if (base(x) == base(y)) 1 else 0
    }
    /** "Spanish", "Portuguese (Brazil)", in the TV's language. */
    fun name(raw: String): String = (code(raw) ?: raw).let { c ->
        val l = Locale.forLanguageTag(c)
        (if (l.country.isEmpty()) l.getDisplayLanguage(Locale.getDefault()) else l.getDisplayName(Locale.getDefault()))
            .ifBlank { c }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    /** Every language, the common ones first, the rest by name. */
    val all: List<String> by lazy { common + iso.filter { it !in common && name(it) != it }.sortedBy(::name) }
}

internal fun nextSettingIndex(current: Int, size: Int, direction: Int): Int {
    require(size > 0 && direction in -1..1 && direction != 0)
    return if (current < 0) if (direction < 0) size - 1 else 0 else (current + direction + size) % size
}

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>, val perProfile: Boolean = false) {
        init { require(values.isNotEmpty() && values.all { it.first.javaClass == values[0].first.javaClass }) }
    }
    private enum class Group(val words: Int, val icon: Int = 0, val glyph: String = "") {
        STREAMS(R.string.group_streams, R.drawable.ic_play),
        PICTURE(R.string.group_picture, R.drawable.ic_tv),
        SUBTITLES(R.string.group_subtitles, R.drawable.ic_subtitles),
        ADDONS(R.string.group_addons, R.drawable.ic_layers),
        PROFILE(R.string.group_profile, glyph = "♙"),
        LANGUAGE(R.string.group_language, glyph = "◎"),
        STORAGE(R.string.group_storage, glyph = "▤"),
        ABOUT(R.string.group_about, R.drawable.ic_info),
    }

    // Built on first use: the words come from resources, which an Activity has only once it's attached.
    private val on get() = getString(R.string.state_on)
    private val off get() = getString(R.string.state_off)
    private fun onOff() = listOf(true to on, false to off)

    private val streaming by lazy { listOf(
        Choice("alwaysList", getString(R.string.play_press), getString(R.string.play_press_hint),
            listOf(false to getString(R.string.play_best_source), true to getString(R.string.play_show_sources))),
        Choice("addonText", getString(R.string.source_list), getString(R.string.source_list_hint),
            listOf(false to getString(R.string.source_list_clean), true to getString(R.string.source_list_addon))),
        // Alder, then each video app on this TV by its own name.
        Choice("videoPlayer", getString(R.string.video_player), getString(R.string.video_player_hint),
            listOf("" to getString(R.string.video_player_alder)) + ExternalPlayer.installed(this)),
        Choice("maxHeight", getString(R.string.max_quality), getString(R.string.max_quality_hint),
            listOf(0 to getString(R.string.no_limit), 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", getString(R.string.max_size), getString(R.string.max_size_hint),
            listOf(0 to getString(R.string.no_limit), 40 to "40 GB", 30 to "30 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
        Choice("release", getString(R.string.release_type), getString(R.string.release_type_hint),
            listOf("" to getString(R.string.no_preference), "remux" to getString(R.string.release_remux),
                "encode" to getString(R.string.release_encode), "web" to getString(R.string.release_web)), true),
    ) }
    private val pictureAndSound by lazy { listOf(
        Choice("displayHeight", getString(R.string.display_resolution), getString(R.string.display_resolution_hint),
            listOf(0 to getString(R.string.streams_auto), 1080 to "1080p", 2160 to "4K")),
        Choice("uiScale", getString(R.string.ui_size), getString(R.string.ui_size_hint),
            // ponytail: two steps; 130% pushed Home's title logo into the top bar on a 1080p TV.
            listOf(100 to getString(R.string.ui_size_normal), 115 to getString(R.string.size_large))),
        Choice("hdrPref", getString(R.string.hdr_choice), getString(R.string.hdr_choice_hint),
            listOf("" to on, "dv" to getString(R.string.hdr_dv_first), "hdr10" to getString(R.string.hdr_hdr10_first), "off" to off)),
        Choice("matchFrameRate", getString(R.string.frame_rate), getString(R.string.frame_rate_hint), onOff()),
        Choice("decodeAudio", getString(R.string.audio_output), getString(R.string.audio_output_hint),
            listOf(false to getString(R.string.streams_auto), true to "PCM")),
        Choice("clearDialogue", getString(R.string.clear_dialogue), getString(R.string.clear_dialogue_setting_hint),
            listOf(false to off, true to on)),
    ) }
    private val dubbed by lazy { listOf(
        Choice("dub", getString(R.string.dubbed), getString(R.string.dubbed_hint),
            listOf("" to getString(R.string.no_preference), "original" to getString(R.string.dubbed_original),
                "mine" to getString(R.string.dubbed_mine)), true),
    ) }
    private val subtitles by lazy { listOf(
        Choice("subPref", getString(R.string.subs_default), getString(R.string.subs_default_hint),
            listOf("on" to getString(R.string.subs_always), "foreign" to getString(R.string.subs_foreign), "off" to off), true),
    ) }
    private val subtitleLooks by lazy { listOf(
        Choice("subSize", getString(R.string.subtitle_size), getString(R.string.subtitle_size_hint),
            listOf(100 to getString(R.string.size_medium), 130 to getString(R.string.size_large),
                160 to getString(R.string.size_extra_large), 80 to getString(R.string.size_small))),
        Choice("subColor", getString(R.string.subtitle_color), getString(R.string.subtitle_color_hint),
            listOf("white" to getString(R.string.color_white), "gray" to getString(R.string.color_gray),
                "yellow" to getString(R.string.color_yellow),
                "cyan" to getString(R.string.color_cyan), "cream" to getString(R.string.color_cream),
                "warm_white" to getString(R.string.color_warm_white))),
        Choice("subBackground", getString(R.string.subtitle_background), getString(R.string.subtitle_background_hint), onOff()),
    ) }

    private lateinit var groups: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var detailTitle: TextView
    private lateinit var hint: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private val groupViews = mutableMapOf<Group, View>()
    private val groupDots = mutableMapOf<Group, TextView>()
    private val groupLabels = mutableMapOf<Group, TextView>()
    private val groupBadges = mutableMapOf<Group, View>()
    private val choiceRows = mutableSetOf<View>()
    private val refresh = mutableListOf<() -> Unit>()
    private var selectedGroup: Group? = null
    private var reopenGroup: Group? = null
    private var firstDetail: View? = null
    private var addonValue: TextView? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.hdrPref(this) // migrate the old HDR switch before any row reads it
        Settings.subPref(this) // migrate the old subtitles switch before any row reads it
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false; clipToPadding = false }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = text(Profiles.active(this)?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(text(R.string.settings_title, SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        profileName = text("", SMALL, color = TEXT_3).apply { setPadding(dp(14), dp(8), 0, 0) }
        header.addView(profileName)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(android.widget.TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        })
        root.addView(header, FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP).apply {
            leftMargin = dp(48); rightMargin = dp(48); topMargin = dp(24)
        })

        groups = column(Gravity.START).apply {
            setPadding(dp(7), dp(9), dp(7), dp(9))
            background = glassOver(this, 14)
        }
        Group.entries.forEach { group ->
            if (group == Group.STORAGE) groups.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            addGroup(group)
        }
        root.addView(groups, FrameLayout.LayoutParams(dp(200), dp((height - 115).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48); topMargin = dp(88)
        })

        detailTitle = text("", BODY, bold = true).apply { setPadding(dp(12), 0, 0, 0) }
        detailRows = column(Gravity.START)
        // A long group (Profile) scrolls in its own space: drawn past it, rows ran over the group's title and pushed the
        // help off the screen. The padding keeps room for the focused row's glow inside that space.
        val rowsMax = dp((height - 210).toInt())
        rowsScroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(rowsMax, MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(12))
            // Its parent lets children draw past it (the glow), so the frame clips the rows itself.
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(detailRows)
        }
        hint = text("", SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), 0, dp(20), 0) }
        // The help follows the rows, so a long group (Streams has six) never runs under it.
        val detail = column(Gravity.START).apply {
            clipChildren = false
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(rowsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); marginStart = -dp(12); marginEnd = -dp(12) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(detail, FrameLayout.LayoutParams(dp((width - 328).toInt()), dp((height - 110).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(280); topMargin = dp(88)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        profileName.text = Profiles.active(this)?.name?.let { getString(R.string.settings_profile_name, it) } ?: ""
        // Rebuilt for a new Text and poster size: back on the same page, on the row that changed it.
        val reopened = savedInstanceState?.getString("group")?.let { name -> Group.entries.firstOrNull { it.name == name } }
        val start = reopened ?: Group.STREAMS
        showGroup(start)
        // Now, not posted: otherwise Android gives first focus to the Streams button, and focus on a page button opens it.
        groupViews[start]?.requestFocus()
        if (reopened != null) detailRows.post { firstDetail?.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The page the size was changed on: focus moving during the rebuild can select another one first.
        (reopenGroup ?: selectedGroup)?.let { outState.putString("group", it.name) }
    }

    override fun onResume() {
        super.onResume()
        groupBadges[Group.ABOUT]?.visibility = if (Updates.hasUpdate(this)) View.VISIBLE else View.GONE
        if (::profileName.isInitialized) {
            val name = Profiles.active(this)?.name
            profileName.text = name?.let { getString(R.string.settings_profile_name, it) } ?: ""
            avatar.text = name?.take(1)?.uppercase() ?: "A"
            refresh.forEach { it() }
        }
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { result ->
            addonValue?.text = installed(result.getOrNull(), R.string.addons_unavailable)
        }
    }

    private fun installed(count: Int?, otherwise: Int) =
        (count?.let { getString(R.string.addons_installed, it) } ?: getString(otherwise)) + "  ›"

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                groupViews.values.any { it.hasFocus() }) {
                if (firstDetail?.requestFocus() == true) return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                currentFocus?.let { isInside(it, detailRows) && it !in choiceRows } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
            // The rows end where they end: Down past the last one stays put instead of falling into the group list.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && detailRows.childCount > 0 &&
                currentFocus === detailRows.getChildAt(detailRows.childCount - 1)) return true
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun addGroup(group: Group) {
        val dot = text("•", SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        val icon = if (group.icon != 0) ImageView(this).apply {
            setImageResource(group.icon)
            imageTintList = ColorStateList.valueOf(TEXT_3)
        } else text(group.glyph, BODY, color = TEXT_3).apply { gravity = Gravity.CENTER }
        val label = text(group.words, BODY, bold = true, color = TEXT_2)
        val badge = View(this).apply {
            background = rounded(TUNGSTEN, 3)
            visibility = if (group == Group.ABOUT && Updates.hasUpdate(this@SettingsActivity)) View.VISIBLE else View.GONE
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), -1))
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(4) })
            addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(badge, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
            setPadding(dp(2), 0, dp(4), 0)
            rowFocus(8, rest = { if (selectedGroup == group) tile(8) else null }) { has ->
                if (has) showGroup(group)
            }
            setOnClickListener { showGroup(group); firstDetail?.requestFocus() }
        }
        groupViews[group] = row
        groupDots[group] = dot
        groupLabels[group] = label
        groupBadges[group] = badge
        groups.addView(row, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(1) })
    }

    private fun showGroup(group: Group) {
        if (selectedGroup == group && detailRows.childCount > 0) return
        selectedGroup = group
        groupViews.forEach { (item, view) ->
            groupDots[item]?.visibility = if (item == group) View.VISIBLE else View.INVISIBLE
            groupLabels[item]?.setTextColor(if (item == group) TEXT else if (item == Group.STORAGE || item == Group.ABOUT) TEXT_3 else TEXT_2)
            if (!view.hasFocus()) view.background = if (item == group) tile(8) else null
        }
        detailTitle.text = getString(group.words)
        detailRows.removeAllViews()
        rowsScroll.scrollTo(0, 0)
        choiceRows.clear()
        refresh.clear()
        firstDetail = null
        addonValue = null
        hint.text = getString(when (group) {
            Group.STREAMS -> R.string.hint_streams
            Group.PICTURE -> R.string.hint_picture
            Group.SUBTITLES -> R.string.hint_subtitles
            Group.ADDONS -> R.string.hint_addons
            Group.PROFILE -> R.string.hint_profile
            Group.LANGUAGE -> R.string.hint_language
            Group.STORAGE -> R.string.hint_storage
            Group.ABOUT -> R.string.hint_about
        })
        when (group) {
            Group.STREAMS -> {
                presetRow()
                streaming.forEach(::choiceRow)
                Profiles.active(this)?.let { me ->
                    lateinit var value: TextView
                    value = actionRow(getString(R.string.share_streams), if (Profiles.sharesStreams(this, me.id)) on else off,
                        getString(R.string.share_streams_hint)) {
                        val shares = !Profiles.sharesStreams(this, me.id)
                        Profiles.setSharesStreams(this, me.id, shares)
                        value.text = (if (shares) on else off) + "  ›"
                    }
                }
                Profiles.streamLenders(this).forEach { lender ->
                    val value = actionRow(getString(R.string.shared_by, lender.name), "…",
                        getString(R.string.shared_by_hint, lender.name)) {}
                    background({ (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable } }) { result ->
                        value.text = result.getOrNull()?.map { addonShortName(it.name) }?.ifEmpty { null }?.joinToString(", ")
                            ?: getString(if (result.isSuccess) R.string.no_stream_addons else R.string.load_failed)
                    }
                }
                actionRow(getString(R.string.more_filters), "", getString(R.string.more_filters_hint)) {
                    choose(getString(R.string.more_filters), listOf(getString(R.string.action_ok)), getString(R.string.more_filters_desc)) {}
                }
            }
            Group.PICTURE -> {
                pictureAndSound.forEach(::choiceRow)
                languageRow("audioLang", getString(R.string.audio_language), getString(R.string.audio_language_hint))
                dubbed.forEach(::choiceRow)
            }
            Group.SUBTITLES -> {
                subtitles.forEach(::choiceRow)
                languageRow("subLang", getString(R.string.subtitle_language), getString(R.string.subtitle_language_hint))
                subtitleLooks.forEach(::choiceRow)
            }
            Group.ADDONS -> {
                actionRow(getString(R.string.my_addons), "…", getString(R.string.my_addons_hint)) {
                    if (authKey != null) startActivity(Intent(this, AddonsActivity::class.java))
                }.also { addonValue = it }
                actionRow(getString(R.string.reload_addons), "", getString(R.string.reload_addons_hint)) {
                    val key = authKey ?: return@actionRow
                    addonValue?.text = getString(R.string.reloading)
                    background({ Stremio.addons(key, refresh = true).size }) { result ->
                        addonValue?.text = installed(result.getOrNull(), R.string.reload_failed)
                    }
                }
                val key = authKey
                if (key != null) background({ Stremio.addons(key).size }) { result ->
                    addonValue?.text = installed(result.getOrNull(), R.string.addons_unavailable)
                }
            }
            Group.PROFILE -> {
                actionRow(getString(R.string.menu_switch_profile), "", getString(R.string.switch_profile_hint)) {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                actionRow(getString(R.string.manage_profiles), "", getString(R.string.manage_profiles_hint)) {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                var hiddenValue: TextView? = null
                fun count() { hiddenValue?.text = "${Settings.hidden(this).length()}  ›" }
                hiddenValue = actionRow(getString(R.string.hidden_continue), "", getString(R.string.hidden_continue_hint)) {
                    unhideMenu(::count)
                }
                count()
                var hiddenRowsValue: TextView? = null
                fun countRows() { hiddenRowsValue?.text = "${Settings.hiddenRows(this).size}  ›" }
                hiddenRowsValue = actionRow(getString(R.string.hidden_rows), "", getString(R.string.hidden_rows_hint)) {
                    unhideRowsMenu(::countRows)
                }
                countRows()
                choiceRow(Choice("startTab", getString(R.string.default_tab), getString(R.string.default_tab_hint),
                    listOf("home" to getString(R.string.tab_home), "movies" to getString(R.string.tab_movies),
                        "shows" to getString(R.string.tab_shows), "library" to getString(R.string.tab_my_list)), perProfile = true))
                lateinit var tabsValue: TextView
                tabsValue = actionRow(getString(R.string.tabs), "…", getString(R.string.tabs_hint), perProfile = true) {
                    tabsMenu { tabs -> tabsValue.text = tabsSummary(tabs) + "  ›" }
                }
                withTabs { tabs -> tabsValue.text = tabsSummary(tabs) + "  ›" }
                choiceRow(Choice("featuredHome", getString(R.string.featured_home), getString(R.string.featured_home_hint),
                    listOf(false to off, true to on), perProfile = true))
                choiceRow(Choice("blurThumbnails", getString(R.string.blur_thumbnails), getString(R.string.blur_thumbnails_hint),
                    listOf(false to off, true to on), perProfile = true))
                choiceRow(Choice("ratings", getString(R.string.ratings_source), getString(R.string.ratings_source_hint),
                    listOf("imdb" to "IMDb", "letterboxd" to LETTERBOXD, "both" to getString(R.string.ratings_both))))
                lateinit var keyValue: TextView
                fun keyState() = getString(if (Settings.mdblistKey(this).isEmpty()) R.string.key_not_set else R.string.key_set)
                keyValue = actionRow(getString(R.string.mdblist_key), keyState(), getString(R.string.mdblist_key_hint)) {
                    askOnPhone(R.string.mdblist_key, initial = Settings.mdblistKey(this)) { typed ->
                        Settings.setMdblistKey(this, typed)
                        forgetLetterboxd()
                        keyValue.text = keyState() + "  ›"
                    }
                }
            }
            Group.LANGUAGE -> appLanguageRow()
            Group.STORAGE -> actionRow(getString(R.string.clear_storage), "", getString(R.string.clear_storage_hint)) {
                choose(getString(R.string.clear_storage_title), listOf(getString(R.string.action_cancel), getString(R.string.action_clear)),
                    getString(R.string.clear_storage_desc)) { choice ->
                    if (choice == 1) (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }
            }
            Group.ABOUT -> {
                detailRows.addView(brand(SERIF_TITLE), LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(12); bottomMargin = dp(14)
                })
                actionRow("Alder", getString(R.string.version, Updates.mine(this)), getString(R.string.about_hint)) {
                    choose("Alder", listOf(getString(R.string.action_ok)), getString(R.string.about_desc)) {}
                }
                actionRow(getString(R.string.whats_new), getString(R.string.version, Updates.mine(this)), getString(R.string.whats_new_hint)) { whatsNew() }
                // Only while a crash waits to be sent: answered "No" on Home, or the send failed.
                if (Crashes.sendable(this)) actionRow(getString(R.string.report_send), "", getString(R.string.report_send_hint)) {
                    sendReport { showGroup(Group.ABOUT) }
                }
                var found: Updates.Release? = null
                val value = actionRow(getString(R.string.updates), getString(R.string.updates_checking), getString(R.string.updates_hint)) {
                    found?.let(::installUpdate) ?: showGroup(Group.ABOUT)
                }
                background({ Updates.latest(applicationContext) }) { r ->
                    val release = r.getOrNull()
                    found = release?.takeIf { newerVersion(it.version, Updates.mine(this)) }
                    groupBadges[Group.ABOUT]?.visibility = if (found != null) View.VISIBLE else View.GONE
                    value.text = when {
                        found != null -> getString(R.string.updates_install, found!!.version)
                        release == null -> getString(R.string.updates_check_failed)
                        else -> getString(R.string.updates_up_to_date)
                    }
                }
            }
        }
    }

    private fun settingRow(label: String, value: View, help: String, perProfile: Boolean = false,
                           focus: (Boolean) -> Unit = {}, click: () -> Unit): View {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, BODY, bold = true), LinearLayout.LayoutParams(-2, -2))
            if (perProfile) addView(text(getString(R.string.this_profile).uppercase(), 8f, color = TUNGSTEN).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = glass(6)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val row = FrameLayout(this).apply {
            addView(line, FrameLayout.LayoutParams(-1, -1))
            rowFocus(9) { has ->
                if (has) hint.text = help
                elevation = if (has) dp(8).toFloat() else 0f
                if (has && android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
                focus(has)
            }
            setOnClickListener { click() }
        }
        detailRows.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        if (firstDetail == null) firstDetail = row
        return row
    }

    /** The titles hidden from Continue Watching; OK on one puts it back with its resume point, here and in Stremio. */
    private fun unhideMenu(done: () -> Unit) {
        val all = Settings.hidden(this)
        val ids = all.keys().asSequence().toList()
        if (ids.isEmpty()) return choose(getString(R.string.nothing_hidden), listOf(getString(R.string.action_ok)), getString(R.string.nothing_hidden_desc)) {}
        val key = authKey ?: return
        choose(getString(R.string.hidden_continue), ids.map { all.getJSONObject(it).optString("name", it) }, getString(R.string.unhide_title_hint)) { i ->
            val id = ids[i]
            background({
                val item = Stremio.library(key, listOf(id)).firstOrNull() ?: error("not in the library")
                Library.unhide(item, all.getJSONObject(id).optLong("timeOffset"))
                Stremio.saveLibrary(key, listOf(item))
            }) { result ->
                result.fold({
                    all.remove(id)
                    Settings.setHidden(this, all)
                    done()
                }) { choose(getString(R.string.unhide_failed), listOf(getString(R.string.action_ok)), getString(R.string.check_connection)) {} }
            }
        }
    }

    /** This profile's tabs after Home, in its order; its add-ons say whether Live and their own tabs (Trakt…) exist. */
    private fun withTabs(then: (List<String>) -> Unit) {
        val key = authKey ?: return
        background({
            val addons = runCatching { Stremio.addons(key) }.getOrElse { Stremio.savedAddons(key).orEmpty() }
            tabsFor(addons.flatMap { a -> a.catalogs.map { it.type } })
        }) { result ->
            then(orderTabs(Settings.tabOrder(this), result.getOrElse { tabsFor(emptyList()) }))
        }
    }

    private fun tabsSummary(tabs: List<String>) = getString(R.string.tabs_count, tabs.count { Settings.tabShown(this, it) }, tabs.size)

    private fun tabName(id: String) = when (id) {
        "shows" -> getString(R.string.tab_shows)
        "movies" -> getString(R.string.tab_movies)
        "live" -> getString(R.string.tab_live)
        "library" -> getString(R.string.tab_my_list)
        else -> typeTabName(this, id.removePrefix("type:"))
    }

    /**
     * Settings › Tabs: the tabs after Home, left to right as in the top bar. OK on one shows or hides it, or moves it a
     * place; the list then opens again on that tab, so moving it several places is OK, OK, OK.
     */
    private fun tabsMenu(focus: Int = 0, done: (List<String>) -> Unit): Unit = withTabs { tabs ->
        choose(getString(R.string.tabs), tabs.map { "${tabName(it)}  ·  ${if (Settings.tabShown(this, it)) on else off}" },
            getString(R.string.tabs_desc), focus = focus.coerceIn(0, tabs.lastIndex)) { i ->
            val id = tabs[i]
            val shown = Settings.tabShown(this, id)
            fun move(to: Int) {
                val reordered = tabs.toMutableList().apply { add(to, removeAt(i)) }
                Settings.setTabOrder(this, saveTabOrder(Settings.tabOrder(this), reordered))
            }
            val actions = listOfNotNull(
                getString(if (shown) R.string.tab_hide else R.string.tab_show) to { Settings.setTabShown(this, id, !shown); i },
                if (i > 0) getString(R.string.tab_move_left) to { move(i - 1); i - 1 } else null,
                if (i < tabs.lastIndex) getString(R.string.tab_move_right) to { move(i + 1); i + 1 } else null,
            )
            choose(tabName(id), actions.map { it.first }) { a ->
                val at = actions[a].second()
                withTabs(done)
                tabsMenu(at, done)
            }
        }
    }

    /** The catalog rows hidden from home, movies or shows; OK on one puts it back. */
    private fun unhideRowsMenu(done: () -> Unit) {
        val all = Settings.hiddenRows(this).toList().sorted()
        if (all.isEmpty()) return choose(getString(R.string.no_hidden_rows), listOf(getString(R.string.action_ok)), getString(R.string.no_hidden_rows_desc)) {}
        choose(getString(R.string.hidden_rows), all, getString(R.string.unhide_row_hint)) { i ->
            Settings.unhideRow(this, all[i])
            done()
        }
    }

    private fun actionRow(label: String, value: String, help: String, perProfile: Boolean = false, click: () -> Unit): TextView {
        val display = text(if (value.isEmpty()) "›" else "$value  ›", BODY, color = TEXT_2)
        settingRow(label, display, help, perProfile, focus = { display.setTextColor(if (it) TUNGSTEN else TEXT_2) }, click = click)
        return display
    }

    private fun key(c: Choice) = if (c.perProfile) Settings.profileKey(this, c.key) else c.key
    private fun stored(c: Choice): Any = when (val default = c.values[0].first) {
        is Boolean -> prefs.getBoolean(key(c), default)
        is String -> prefs.getString(key(c), default).orEmpty()
        else -> prefs.getInt(key(c), default as Int)
    }
    private fun store(c: Choice, value: Any) = prefs.edit().apply {
        when (value) {
            is Boolean -> putBoolean(key(c), value)
            is String -> putString(key(c), value)
            else -> putInt(key(c), value as Int)
        }
    }.apply().also { if (c.key == "uiScale") { reopenGroup = selectedGroup; main.post { recreate() } } } // the new size shows at once
    private fun choice(key: String) = (streaming + pictureAndSound).first { it.key == key }

    private fun choiceRow(c: Choice) {
        fun current() = c.values.indexOfFirst { it.first == stored(c) }.coerceAtLeast(0)
        // A switch only where the choice is On or Off; "Best source / Show sources" is a choice with words.
        val isSwitch = c.values.map { it.second }.toSet() == setOf(on, off)
        val words = text("", BODY, color = TEXT_2)
        val track = FrameLayout(this).apply { background = rounded(GLASS_EDGE, 9) }
        val knob = View(this)
        if (isSwitch) track.addView(knob)
        val value = if (isSwitch) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(track, LinearLayout.LayoutParams(dp(32), dp(17)))
        } else words
        var focused = false
        fun draw() {
            val index = current()
            val on = isSwitch && (c.values[index].first == true || c.values[index].second == this@SettingsActivity.on)
            words.setTextColor(if (focused) TEXT else TEXT_2)
            words.text = if (isSwitch) c.values[index].second
                else if (focused) "‹  " + c.values[index].second + "  ›" else c.values[index].second
            if (isSwitch) {
                track.background = rounded(if (on) TUNGSTEN else GLASS_EDGE, 9)
                knob.background = rounded(if (on) BASE else TEXT_2, 7)
                knob.layoutParams = FrameLayout.LayoutParams(dp(13), dp(13),
                    Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                }
            }
        }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), c.values.size, by)
            store(c, c.values[next].first)
            refresh.forEach { it() }
        }
        val row = settingRow(c.label, value, c.hint, c.perProfile, focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    /** A language for this profile, picked from a list (too many to step through with Left and Right). */
    private fun languageRow(key: String, label: String, help: String) {
        val codes = listOf("") + Languages.all
        // Audio language was once shared by every profile: that pick stands until this profile makes its own.
        fun current() = prefs.getString(Settings.profileKey(this, key), if (key == "audioLang") prefs.getString(key, "") else "").orEmpty()
        fun name(code: String) = if (code.isEmpty()) getString(R.string.tv_language) else Languages.name(code)
        val value = text("", BODY, color = TEXT_2)
        fun draw() { value.text = name(current()) + "  ›" }
        settingRow(label, value, help, perProfile = true, focus = { value.setTextColor(if (it) TUNGSTEN else TEXT_2) }) {
            choose(label, codes.map(::name), checked = codes.indexOf(current()).coerceAtLeast(0)) { i ->
                prefs.edit().putString(Settings.profileKey(this, key), codes[i]).apply()
                refresh.forEach { it() }
            }
        }
        refresh += ::draw
        draw()
    }

    private fun presetRow() {
        val value = text("", BODY, color = TEXT_2)
        fun current() = PRESETS.indexOfFirst { (_, rows) -> rows.all { (key, v) -> stored(choice(key)) == v } }
        var focused = false
        fun draw() { value.text = (if (focused) "‹  " else "") + getString(PRESETS.getOrNull(current())?.first ?: R.string.custom) + (if (focused) "  ›" else "") }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), PRESETS.size, by)
            PRESETS[next].second.forEach { (key, v) -> store(choice(key), v) }
            refresh.forEach { it() }
        }
        val row = settingRow(getString(R.string.source_choice), value, getString(R.string.source_choice_hint),
            focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    /**
     * The language Alder shows, picked here so TVs without Android 13's per-app language page (Fire OS, Android 8–12)
     * can change it too. Each language is named in its own words, so anyone can find theirs.
     */
    private fun appLanguageRow() {
        val current = Settings.appLanguage(this)
        val system = android.content.res.Resources.getSystem().configuration
        @Suppress("DEPRECATION") val tv = if (android.os.Build.VERSION.SDK_INT >= 24) system.locales[0] else system.locale
        val names = listOf("" to getString(R.string.tv_language_named, tv.getDisplayLanguage(tv).replaceFirstChar { it.titlecase(tv) })) +
            APP_LANGUAGES.map { tag -> tag to Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } } }
        // "es-MX" picked on Android's own page is Spanish here.
        val at = names.indexOfFirst { it.first == current }.takeIf { it >= 0 }
            ?: names.indexOfFirst { it.first.isNotEmpty() && it.first.substringBefore('-') == current.substringBefore('-') }.coerceAtLeast(0)
        actionRow(getString(R.string.app_language), names[at].second, getString(R.string.app_language_hint)) {
            choose(getString(R.string.app_language), names.map { it.second }, checked = at) { i ->
                val picked = names[i].first
                if (picked == current) return@choose
                Settings.setAppLanguage(this, picked)
                // Android 13 and later redraw every screen in the new language by themselves; before that, start over.
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    finishAffinity()
                    packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                }
            }
        }
    }

    private companion object {
        /** The languages Alder has words for, besides English; res/xml/locales_config.xml lists the same. */
        val APP_LANGUAGES = listOf("en", "es", "pt-BR", "fr", "de", "it", "hi", "ar")
        val PRESETS = listOf(
            R.string.preset_addon_order to mapOf("release" to "", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 0),
            R.string.preset_best_picture to mapOf("release" to "remux", "hdrPref" to "dv", "maxHeight" to 0, "maxSizeGb" to 0),
            R.string.preset_balanced to mapOf("release" to "encode", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 20),
            R.string.preset_fast_start to mapOf("release" to "web", "hdrPref" to "", "maxHeight" to 1080, "maxSizeGb" to 10),
        )
    }
}
