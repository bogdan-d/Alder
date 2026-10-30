package dev.alder

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import org.json.JSONArray
import java.util.Locale

// Finds the sources for one video. Normally it picks one and hands the player a short list to fall back on, so Play
// is one click; with "pick" (held OK on Play, or after three sources failed) it lists them all.
internal fun readyStreams(answers: Array<List<Stream>?>, allowGaps: Boolean): List<Stream> =
    (if (allowGaps) answers.asList() else answers.takeWhile { it != null }).flatMap { it.orEmpty() }

class StreamsActivity : Screen() {
    private val pick by lazy { intent.getBooleanExtra("pick", false) || Settings.alwaysList(this) }

    private lateinit var art: ImageView
    override val backdrop get() = art
    // Audio language chips above the list: All, then each language the sources name. A pick lasts while the page is open.
    private lateinit var languageStrip: HorizontalScrollView
    private var audio: String? = null
    private var audioOpened = false // the first list opens on the profile's language (Dubbed releases: My language first)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val type = intent.getStringExtra("type") ?: "movie"
        val videoId = intent.getStringExtra("videoId")!!
        val name = intent.getStringExtra("name") ?: ""
        val episode = intent.getStringExtra("episode") ?: intent.getStringExtra("label")?.removePrefix(name)?.trim(' ', '·')
        val where = episode?.split("·")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        val fallback = text(name, SERIF_NAME, serif = true).onArt().apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val brand = FrameLayout(this).apply {
            addView(fallback, FrameLayout.LayoutParams(-1, -1))
            addView(logo, FrameLayout.LayoutParams(-1, -1))
        }
        Images.load(intent.getStringExtra("logo") ?: Images.logoFor(intent.getStringExtra("id").orEmpty()),
            logo, dp(200), failed = { logo.visibility = View.GONE }) { fallback.visibility = View.GONE }
        val episodeInfo = column(Gravity.START).apply {
            addView(text(listOfNotNull(where.firstOrNull(), intent.getStringExtra("runtime"))
                .joinToString(" · "), SMALL, bold = true, color = TEXT_2).onArt())
            // The mock's 23 sp: the screen title's size crowded the count.
            addView(text(where.drop(1).joinToString(" · ").ifBlank { name }, NAME_SIZE, serif = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, wrap().apply { topMargin = dp(2) })
        }
        val count = text("", SMALL, color = TEXT_3).onArt().apply { gravity = Gravity.END }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brand, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(16) })
            addView(episodeInfo, LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(dp(90), -2))
        }
        val cols = LinearLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(caps(R.string.streams_quality, TEXT_3).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(caps(R.string.streams_release, TEXT_3), LinearLayout.LayoutParams(0, -2, 1f))
            addView(caps(R.string.streams_size, TEXT_3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(72), -2))
        }
        languageStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; visibility = View.GONE
        }
        val rows = column(Gravity.START)
        val status = text(intent.getStringExtra("message") ?: "Finding sources…", BODY, color = TEXT_2).onArt()
        rows.addView(status, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); topMargin = dp(12) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // The row past the panel's foot fades out above the help line instead of being cut by it.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(rows)
        }
        val help = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("OK", 9f, bold = true, color = BASE).apply {
                background = rounded(TUNGSTEN, 3); includeFontPadding = false; setPadding(dp(4), dp(1), dp(4), dp(1))
            })
            val preset = presetName()
            val line = getString(R.string.streams_help, preset)
            addView(text("", SMALL, color = TEXT_2).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                // The preset in full-strength bold, as the mock reads it.
                text = android.text.SpannableString(line).apply {
                    val at = line.lastIndexOf(preset)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + preset.length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + preset.length, 0)
                }
            })
        }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val still = intent.getStringExtra("still")
        val backArt = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        val blurStill = intent.getBooleanExtra("blurStill", false)
        Images.load(still ?: backArt, art, resources.displayMetrics.widthPixels, orElse = backArt.takeIf { still != null },
            look = if (blurStill && still != null) Images.blurred else null) {
            art.animate().alpha(1f).setDuration(250).start()
            refrost()
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xB30E0C0A.toInt(), 0x730E0C0A, 0x590E0C0A))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = glassOver(this, 14, 0.18f)
                addView(header, LinearLayout.LayoutParams(-1, dp(58)))
                addView(View(context).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) })
                addView(languageStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                addView(cols, LinearLayout.LayoutParams(-1, -2))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(help, LinearLayout.LayoutParams(-1, dp(34)))
            }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 62 / 100, -1).apply {
                setMargins(dp(PAD), dp(27), 0, dp(27))
            })
            addView(TextClock(this@StreamsActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
        })
        val tv = Device.of(this)

        val lenders = Profiles.streamLenders(this)
        // Asks every add-on again (a fixed connection, or an add-on set up again on the phone); a source found plays.
        fun tryAgain(focus: Boolean = true) {
            val again = pill(R.string.action_try_again).apply { setOnClickListener { recreate() } }
            rows.addView(again, wrap().apply { marginStart = dp(12); topMargin = dp(16); bottomMargin = dp(12) })
            if (focus) again.requestFocus()
        }
        background({
            val own = Stremio.addons(key).filter { it.providesStreams }
            val lent = lenders.flatMap { lender ->
                runCatching {
                    (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable }
                }.getOrDefault(emptyList())
            }
            val household = lent.filter { it.serves("stream", type, videoId) }.distinctBy { it.id }
            // Whether any stream add-on is installed besides Stremio's own (Local Files has a stream resource): if so, none
            // of them knows this kind of title.
            (household + own.filter { it.serves("stream", type, videoId) }.filterNot { mine -> household.any { it.id == mine.id } }) to
                (own.any { !it.official } || lent.isNotEmpty())
        }) { result ->
            val (addons, anyStreams) = result.getOrElse {
                status.text = getString(R.string.streams_load_failed)
                return@background tryAgain()
            }
            if (addons.isEmpty()) {
                status.text = getString(if (anyStreams) R.string.streams_none_for_kind else R.string.streams_no_addons)
                return@background tryAgain()
            }
            val answers = arrayOfNulls<List<Stream>>(addons.size)
            // Why an add-on gave nothing to play, so an empty list can say so instead of blaming the TV.
            val failures = arrayOfNulls<Throwable>(addons.size)
            val torrents = IntArray(addons.size)
            val links = IntArray(addons.size)
            var started = false
            var listed = false
            var waitingForEarlier = false
            var providers = emptyMap<Stream, String>()
            var eligible = emptyList<Stream>()
            var ordered = emptyList<Stream>()
            /** The list in the chosen audio language; Best match is the first within Settings' limits that's in it. */
            fun drawRows(focusList: Boolean) {
                val shown = audio?.let { lang -> ordered.filter { speaks(it, lang) } } ?: ordered
                count.text = plural(R.plurals.streams_count, shown.size)
                rows.removeAllViews()
                cols.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
                if (shown.isEmpty()) {
                    rows.addView(text(R.string.streams_none_playable, BODY, color = TEXT_2).onArt().apply {
                        setPadding(dp(12), dp(12), 0, 0)
                    })
                    val unplayable = answers.sumOf { a -> a.orEmpty().count { it.isVideo && !it.playableOn(tv) } }
                    noSourceReasons(addons.map { it.name }, failures, torrents, links, unplayable, sourceWords).forEach { line ->
                        rows.addView(text(line, SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), dp(8), dp(12), 0) },
                            LinearLayout.LayoutParams(-1, -2))
                    }
                    // The first add-on a phone can fix: it found only torrents (no debrid service yet) or refused its key.
                    addons.indices.firstOrNull { i ->
                        addons[i].configureUrl != null && (failures[i]?.let(::refusedKey) ?: (torrents[i] > 0))
                    }?.let { i -> setupCode(addons[i], torrentsOnly = failures[i] == null)?.let { rows.addView(it, LinearLayout.LayoutParams(-1, -2)) } }
                    tryAgain(focusList)
                } else {
                    val best = eligible.firstOrNull { it in shown }
                    shown.forEach { stream -> rows.addView(streamRow(stream, providers[stream].orEmpty(), stream === best),
                        LinearLayout.LayoutParams(-1, dp(ROW_H)).apply { bottomMargin = dp(2) }) }
                    if (focusList) rows.getChildAt(0).requestFocus()
                }
            }
            fun showList() {
                listed = true
                val available = addons.flatMapIndexed { i, addon -> answers[i].orEmpty()
                    .filter { it.isVideo && it.playableOn(tv) }.map { it to addon.name } }
                providers = available.toMap()
                val ranked = rank(available.map { it.first }, videoId)
                eligible = ranked.filter(::wanted)
                ordered = eligible + ranked.filterNot(::wanted)
                showLanguages(ordered) { drawRows(focusList = false) }
                drawRows(focusList = true)
            }

            // Keep add-on order when earlier ones answer promptly; after 800 ms, a playable later answer can start.
            fun tryStart(allowGaps: Boolean = false) {
                if (started || pick || listed) return
                val ready = readyStreams(answers, allowGaps)
                val best = rank(ready.filter { it.isVideo && it.playableOn(tv) && wanted(it) }, videoId)
                if (best.isEmpty() && answers.any { it == null }) return
                if (best.isEmpty()) return showList()
                started = true
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(best.take(TRY).map { it.toJson() }).toString()))
                finish()
            }

            addons.forEachIndexed { i, addon ->
                background({ addon.streams(type, videoId) }) { streams ->
                    streams.exceptionOrNull()?.let { e ->
                        failures[i] = e
                        android.util.Log.w("Alder", "streams from ${addon.name} failed", e)
                    }
                    torrents[i] = streams.getOrNull()?.torrents ?: 0
                    links[i] = streams.getOrNull()?.links ?: 0
                    answers[i] = streams.getOrNull()?.playable.orEmpty()
                    if (answers.all { it != null }) {
                        if (pick || listed) showList() else tryStart()
                    } else if (!pick) {
                        tryStart()
                        if (!started && !waitingForEarlier && readyStreams(answers, true).any { it.isVideo && it.playableOn(tv) && wanted(it) }) {
                            waitingForEarlier = true
                            main.postDelayed({ tryStart(allowGaps = true) }, 800)
                        }
                    }
                }
            }
        }
    }

    /**
     * All, then each audio language the sources name, the profile's audio language and English first; hidden when the
     * sources name only one. Sources that name no language count as English, as most English releases name none.
     */
    private fun showLanguages(streams: List<Stream>, redraw: () -> Unit) {
        val unnamed = streams.any { it.audioLanguages.isEmpty() && !it.multiAudio }
        val mine = Settings.audioLanguage(this)
        val codes = (streams.flatMap { it.audioLanguages } + listOfNotNull("en".takeIf { unnamed })).distinct()
            .sortedWith(compareBy({ it != mine }, { it != "en" }, ::languageName))
        if (!audioOpened) {
            audioOpened = true
            if (Settings.dub(this) == "mine" && mine in codes) audio = mine
        }
        if (audio !in codes) audio = null
        languageStrip.removeAllViews()
        if (codes.size < 2) { languageStrip.visibility = View.GONE; audio = null; return }
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(2), dp(2), dp(2), dp(2)) }
        val chips = mutableMapOf<String?, android.widget.TextView>()
        fun label(code: String?) = code?.let(::languageName) ?: getString(R.string.streams_all_languages)
        (listOf<String?>(null) + codes).forEach { code ->
            val control = chip(label(code), code == audio).apply {
                setOnClickListener {
                    audio = code
                    chips.forEach { (value, chip) -> chip.select(label(value), value == code) }
                    redraw()
                }
            }
            chips[code] = control
            line.addView(control, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(6) })
        }
        languageStrip.addView(line)
        languageStrip.visibility = View.VISIBLE
    }

    /** In [language] by its names: it names that language, it's multi or dual audio, or (English) it names none. */
    private fun speaks(s: Stream, language: String) =
        language in s.audioLanguages || s.multiAudio || (language == "en" && s.audioLanguages.isEmpty())

    private fun languageName(code: String) =
        Locale(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /**
     * A QR code for [addon]'s settings page, to set it up on a phone: a debrid service when it found [torrentsOnly], a new
     * key when it refused this one. It opens a fresh setup (see [Addon.configureUrl]); null if the address is too long
     * for a code.
     */
    private fun setupCode(addon: Addon, torrentsOnly: Boolean): View? {
        val page = addon.configureUrl ?: return null
        val code = runCatching { qrBitmap(page, dp(112)) }.getOrNull() ?: return null
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), 0)
            addView(ImageView(context).apply {
                setImageBitmap(code); background = rounded(TEXT, 8); setPadding(dp(4), dp(4), dp(4), dp(4))
            }, wrap())
            addView(column(Gravity.START,
                text(getString(R.string.streams_scan, addon.name), BODY, bold = true).onArt(),
                text(if (torrentsOnly) R.string.streams_scan_debrid else R.string.streams_scan_key,
                    SMALL, color = TEXT_2).onArt().marginTop(6),
            ), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        }
    }

    /**
     * The order to try sources in: when the player asked for a lower quality, the tallest that's left first; then the
     * file played last time for this video (debrid links expire, so the link is fresh but the release is the same), then
     * the release the previous episode used (AIOStreams' bingeGroup), then the addon's own order, with releases above the
     * screen's resolution or HDR on a screen without it moved down, 3D and hard-coded subtitles last, and then Settings'
     * stream preferences. The sort is stable, so ties keep the addon's order.
     */
    private fun rank(streams: List<Stream>, videoId: String): List<Stream> =
        StreamsResolver.rank(this, streams, videoId, intent.getStringExtra("id"), intent.getIntExtra("maxHeight", 0))

    private fun preference(): (Stream) -> Int = StreamsResolver.preference(this)

    private fun wanted(s: Stream): Boolean =
        StreamsResolver.wanted(this, s, intent.getIntExtra("maxHeight", 0))

    private fun streamRow(s: Stream, addon: String, best: Boolean): View {
        val quality = text(if (s.height > 0) "${s.height}p" else getString(R.string.streams_auto), BODY, bold = true).onArt()
        val detail = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (best) detail.addView(text(getString(R.string.streams_best_match).uppercase(), 9f, bold = true, color = BASE).apply {
            letterSpacing = 0.04f
            background = rounded(TUNGSTEN, 3)
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }, wrap().apply { marginEnd = dp(7) })
        detail.addView(text(sourceSummary(s), BODY, bold = true, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(if (best) 145 else 170)
        }, wrap().apply { marginEnd = dp(6) })
        // The debrid service first ("RD ⚡": cached on Real-Debrid, starts at once), then the release's own tags.
        (listOfNotNull(s.debrid?.tag) + s.tags.filter { it !in listOf("BluRay", "WEB") }.take(if (best) 1 else 2)).forEach { tag ->
            val words = when (tag) {
                "Multi audio" -> getString(R.string.streams_tag_multi_audio)
                "Dub" -> getString(R.string.streams_tag_dub)
                "Hard subs" -> getString(R.string.streams_tag_hard_subs)
                else -> tag
            }
            detail.addView(text(words.uppercase(), 9f, bold = true, color = TUNGSTEN).apply {
                letterSpacing = 0.03f
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(3).toFloat(); setStroke(dp(1), 0x80F3D9B1.toInt())
                }
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }, wrap().apply { marginEnd = dp(5) })
        }
        val provider = s.title.lineSequence().firstOrNull().orEmpty().substringBefore('(')
            .replace(Regex("^\\[[^]]+]\\s*"), "").trim().takeIf { it.isNotBlank() && !it.matches(Regex("\\d+p")) } ?: addon
        detail.addView(text(provider, SMALL, color = TEXT_3).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(quality, LinearLayout.LayoutParams(dp(QUALITY_W), -2))
            addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(sourceSize(s.size), BODY, bold = true).onArt().apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(72), -2))
            rowFocus(8) { focused ->
                alpha = if (!focused && s.height in 1..719) 0.48f else 1f
                elevation = if (focused) dp(5).toFloat() else 0f
            }
            alpha = if (s.height in 1..719) 0.48f else 1f
            setOnClickListener {
                startActivity(Intent(this@StreamsActivity, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(listOf(s.toJson())).toString()).putExtra("picked", true))
            }
        }
    }

    private fun presetName(): String {
        val release = Settings.release(this)
        val hdr = Settings.hdrPref(this)
        val height = Settings.maxHeight(this)
        val size = Settings.maxSizeGb(this)
        return when {
            release == "" && hdr == "" && height == 0 && size == 0 -> getString(R.string.preset_addon_order)
            release == "remux" && hdr == "dv" && height == 0 && size == 0 -> getString(R.string.preset_best_picture)
            release == "encode" && hdr == "" && height == 0 && size == 20 -> getString(R.string.preset_balanced)
            release == "web" && hdr == "" && height == 1080 && size == 10 -> getString(R.string.preset_fast_start)
            else -> getString(R.string.custom)
        }
    }

    /** [noSourceReasons] in the viewer's language. */
    private val sourceWords by lazy { object : SourceWords() {
        override fun failed(names: String) = getString(R.string.no_sources_failed, names)
        override fun torrentsOnly(names: String) = getString(R.string.no_sources_torrents, names)
        override fun linksOnly(names: String) = getString(R.string.no_sources_links, names)
        override fun unplayable(count: Int) = plural(R.plurals.no_sources_unplayable, count)
        override fun none() = getString(R.string.no_sources_none)
        override fun reason(e: Throwable) = when (val english = failureReason(e)) {
            "key or sign-in refused" -> getString(R.string.failure_refused)
            "too many requests" -> getString(R.string.failure_too_many)
            "having problems" -> getString(R.string.failure_server)
            "timed out" -> getString(R.string.failure_timeout)
            "address not found" -> getString(R.string.failure_no_address)
            "connection refused" -> getString(R.string.failure_connection)
            "secure connection failed" -> getString(R.string.failure_secure)
            "not a source list" -> getString(R.string.failure_not_sources)
            "error" -> getString(R.string.failure_error)
            else -> english // the add-on's own words ("HTTP 400")
        }
    } }

    private companion object {
        const val TRY = 3
        const val NAME_SIZE = 23f // sp, the title or episode name in the header
        const val QUALITY_W = 76 // dp, wide enough for "QUALITY" in spaced capitals
        const val ROW_H = 38 // dp a source row: eight fit above the help line, as on the mock
    }
}

internal fun sourceSize(size: Long): String = when {
    size <= 0 -> "—"
    size >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", size / 1_000_000_000.0)
    else -> "${(size / 1_000_000.0).toInt()} MB"
}

internal fun sourceSummary(s: Stream): String {
    val words = "${s.filename} ${s.title} ${s.description}".lowercase()
    val release = when {
        s.remux || s.bluray -> "BluRay"
        s.web -> "WEB"
        "hdtv" in words -> "HDTV"
        "sdtv" in words -> "SDTV"
        else -> "Source"
    }
    val codec = when {
        Regex("\\bav1\\b").containsMatchIn(words) -> "AV1"
        Regex("hevc|[xh].?265").containsMatchIn(words) -> "HEVC"
        Regex("avc|[xh].?264").containsMatchIn(words) -> "AVC"
        else -> null
    }
    val audio = when {
        "atmos" in words -> "Atmos"
        Regex("\\b(dd|ac3|eac3|e-ac3)[ ._-]?5[ ._-]?1\\b").containsMatchIn(words) -> "DD 5.1"
        Regex("\\b5[ ._-]?1\\b").containsMatchIn(words) -> "5.1"
        Regex("\\b2[ ._-]?0\\b|stereo").containsMatchIn(words) -> "2.0"
        else -> null
    }
    return listOfNotNull(release, codec, audio).joinToString(" · ")
}

/**
 * What went wrong when nothing is playable, one plain line each: add-ons that failed (and how), add-ons that only found
 * torrents (they need a debrid service) or only links for a browser or YouTube, and sources in a format this TV can't
 * decode.
 */
internal fun noSourceReasons(names: List<String>, failures: Array<Throwable?>, torrents: IntArray, links: IntArray, unplayable: Int,
                             words: SourceWords = SourceWords()): List<String> {
    val failed = names.indices.filter { failures[it] != null }.map { "${names[it]} (${words.reason(failures[it]!!)})" }
    val torrentOnly = names.indices.filter { failures[it] == null && torrents[it] > 0 }.map { names[it] }
    val linkOnly = names.indices.filter { failures[it] == null && torrents[it] == 0 && links[it] > 0 }.map { names[it] }
    return listOfNotNull(
        failed.takeIf { it.isNotEmpty() }?.let { words.failed(it.joinToString(", ")) },
        torrentOnly.takeIf { it.isNotEmpty() }?.let { words.torrentsOnly(it.joinToString(", ")) },
        linkOnly.takeIf { it.isNotEmpty() }?.let { words.linksOnly(it.joinToString(", ")) },
        unplayable.takeIf { it > 0 }?.let(words::unplayable),
    ).ifEmpty { listOf(words.none()) }
}

/** The lines of [noSourceReasons]: English here, the viewer's language from StreamsActivity. */
internal open class SourceWords {
    open fun failed(names: String) = "Couldn't get sources from $names. Try again, or check the add-on in Stremio."
    open fun torrentsOnly(names: String) =
        "$names found only torrents. A TV plays torrents through a debrid service, such as Real-Debrid, connected in the add-on's settings."
    open fun linksOnly(names: String) = "$names only has links that open in a browser or on YouTube, which Alder can't play."
    open fun unplayable(count: Int) = "$count ${if (count == 1) "source needs" else "sources need"} a video format this TV can't decode."
    open fun none() = "Your add-ons have no sources for this title."
    open fun reason(e: Throwable) = failureReason(e)
}

/** An add-on refused the request: its key or account expired, or was never set up. */
internal fun refusedKey(e: Throwable) = e is HttpError && (e.code == 401 || e.code == 403)

internal fun failureReason(e: Throwable): String = when {
    refusedKey(e) -> "key or sign-in refused"
    e is HttpError && e.code == 429 -> "too many requests"
    e is HttpError && e.code >= 500 -> "having problems"
    e is java.net.SocketTimeoutException -> "timed out"
    e is java.net.UnknownHostException -> "address not found"
    e is java.net.ConnectException -> "connection refused"
    e is javax.net.ssl.SSLException -> "secure connection failed"
    e is org.json.JSONException -> "not a source list"
    else -> e.message?.takeIf { it.length <= 40 } ?: "error"
}
