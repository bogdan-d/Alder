package dev.alder

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lowerSourceHeight(height: Int): Int? = listOf(1080, 720, 480).firstOrNull { it < height }
internal fun upNextWindow(durationMs: Long) = minOf(25_000L, maxOf(15_000L, durationMs / 60))
internal fun plausibleEpisode(durationMs: Long) = durationMs >= 2 * 60_000L

// Fall-asleep protection: episodes that end in a row with no remote key pressed are counted, and the next one waits
// behind "Still watching?" instead of streaming all night (debrid traffic, and Stremio's watched ticks).
internal const val QUIET_EPISODES = 3
internal const val SLEEP_TIMER = 60 * 60_000L
/** [endedQuietly] episodes in a row, this one included, ended with no key pressed: ask before the next one. */
internal fun askStillWatching(endedQuietly: Int) = endedQuietly >= QUIET_EPISODES
/** The Sleep row's value in Audio & subtitles: "Off", "After this episode" or "In 42 min". The screen passes its words. */
internal fun sleepLabel(stopAfter: Boolean, sleepAt: Long, now: Long,
                        afterEpisode: String = "After this episode", inMinutes: String = "In %1\$d min", off: String = "Off") = when {
    stopAfter -> afterEpisode
    sleepAt > now -> inMinutes.format(((sleepAt - now + 59_999) / 60_000).toInt())
    else -> off
}

internal fun formatTiming(delay: Long, onTime: String = "On time",
                          earlier: String = "%1\$+.2f s  earlier", later: String = "%1\$+.2f s  later") = when {
    delay == 0L -> onTime
    delay < 0L -> earlier.format(delay / 1000f)
    else -> later.format(delay / 1000f)
}

/** Holds track data for display and smart pinning in the Audio & Subtitles overlay. */
internal data class TrackChoice(
    val id: String,
    val name: String,
    val tag: String = "",
    val langCode: String? = null,
    val isSelected: Boolean = false,
    val isOff: Boolean = false
)

/**
 * Pinned vs more tracks for fast TV D-pad access:
 * 1. Off (if present)
 * 2. Active / selected track
 * 3. Device / preferred language track (if found and not already pinned)
 * 4. Recent language tracks (in order of recency, if found and not already pinned)
 * 5. All remaining tracks stay in `more` in their original order.
 */
internal fun partitionPinnedTracks(
    tracks: List<TrackChoice>,
    preferredLang: String?,
    recentLangs: List<String>
): Pair<List<TrackChoice>, List<TrackChoice>> {
    val pinned = mutableListOf<TrackChoice>()
    val seen = mutableSetOf<String>()

    fun pin(track: TrackChoice) {
        if (seen.add(track.id)) {
            pinned.add(track)
        }
    }

    // 1. Off track first
    tracks.firstOrNull { it.isOff }?.let { pin(it) }

    // 2. Currently selected track (if not Off)
    tracks.firstOrNull { it.isSelected && !it.isOff }?.let { pin(it) }

    // 3. Preferred language track
    if (!preferredLang.isNullOrBlank()) {
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, preferredLang) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 4. Recently used language tracks
    for (recent in recentLangs) {
        if (recent.isBlank()) continue
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, recent) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 5. Remaining tracks
    val more = tracks.filter { it.id !in seen }
    return pinned to more
}

/** Updates recent languages with [newLang] at the front, deduplicated and capped at [maxCount]. */
internal fun updateRecentLanguages(recents: List<String>, newLang: String, maxCount: Int = 3): List<String> {
    val code = Languages.code(newLang) ?: newLang.trim().lowercase()
    if (code.isBlank() || code == "und") return recents
    val list = recents.filterNot { Languages.same(it, code) || it.equals(code, ignoreCase = true) }.toMutableList()
    list.add(0, code)
    return list.take(maxCount)
}

/** Chunks a list of tracks into rows for a multi-column grid. */
internal fun <T> chunkGrid(tracks: List<T>, columns: Int = 3): List<List<T>> {
    require(columns > 0)
    return tracks.chunked(columns)
}

internal fun playerPauseFacts(subtitle: String, endClock: String?, isTrailer: Boolean, isLive: Boolean,
                              endsAt: String = "Ends %1\$s"): String =
    listOfNotNull(
        subtitle.takeIf { it.isNotBlank() },
        endClock?.takeIf { it.isNotBlank() && !isTrailer && !isLive }?.let { endsAt.format(it) },
    ).joinToString(Screen.DOT)

internal enum class BadgeBrand { NONE, DOLBY, DTS, SUBTITLES, DEBRID }

internal data class PlayerBadge(val brand: BadgeBrand, val label: String, val code: String? = null, val accent: Boolean = false)

internal fun playerBadges(
    stream: Stream?,
    videoHeight: Int = 0,
    audioLanguage: String? = null,
    audioMime: String? = null,
    audioLabel: String? = null,
    audioChannels: Int = 0,
    subtitleLanguage: String? = null,
): List<PlayerBadge> {
    if (stream == null && videoHeight <= 0 && audioMime == null && audioLanguage == null && subtitleLanguage == null) return emptyList()
    val out = mutableListOf<PlayerBadge>()
    val height = videoHeight.takeIf { it > 0 } ?: stream?.height ?: 0
    when {
        height >= 2160 -> out += PlayerBadge(BadgeBrand.NONE, "4K", accent = true)
        height >= 1440 -> out += PlayerBadge(BadgeBrand.NONE, "1440p")
        height >= 1080 -> out += PlayerBadge(BadgeBrand.NONE, "1080p")
        height >= 720 -> out += PlayerBadge(BadgeBrand.NONE, "720p")
        height >= 480 -> out += PlayerBadge(BadgeBrand.NONE, "${height}p")
    }
    if (stream?.dolbyVision == true) out += PlayerBadge(BadgeBrand.DOLBY, "VISION", accent = true)
    if (stream?.hdr == true && !stream.dolbyVision) out += PlayerBadge(BadgeBrand.NONE, "HDR", accent = true)

    val words = "${stream?.filename.orEmpty()} ${stream?.title.orEmpty()} ${stream?.description.orEmpty()} ${audioLabel.orEmpty()}".lowercase()
    val atmos = audioMime == MimeTypes.AUDIO_E_AC3_JOC || "atmos" in words
    val trueHd = audioMime == MimeTypes.AUDIO_TRUEHD || "truehd" in words
    val dolbyDigital = audioMime == MimeTypes.AUDIO_E_AC3 || audioMime == MimeTypes.AUDIO_AC3 ||
        Regex("\\b(ddp?|e-?ac-?3|dolby.?digital)(\\b|\\d)").containsMatchIn(words)
    val dtsX = Regex("dts[-_. ]?x\\b").containsMatchIn(words)
    val dtsHd = audioMime == MimeTypes.AUDIO_DTS_HD || Regex("dts[-_. ]?hd").containsMatchIn(words)
    val dts = audioMime == MimeTypes.AUDIO_DTS || Regex("\\bdts\\b").containsMatchIn(words)

    when {
        atmos -> out += PlayerBadge(BadgeBrand.DOLBY, "ATMOS", accent = true)
        trueHd -> out += PlayerBadge(BadgeBrand.DOLBY, "TRUEHD")
        dtsX -> out += PlayerBadge(BadgeBrand.DTS, "X", accent = true)
        dtsHd -> out += PlayerBadge(BadgeBrand.DTS, "HD")
        dts -> out += PlayerBadge(BadgeBrand.DTS, "")
        dolbyDigital -> out += PlayerBadge(BadgeBrand.DOLBY, "AUDIO")
    }

    val langName = audioLanguage?.let { Languages.code(it)?.let(Languages::name) ?: it }
        ?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
    val channels = when {
        audioChannels == 8 -> "7.1"
        audioChannels == 6 -> "5.1"
        audioChannels == 2 -> "2.0"
        audioChannels == 1 -> "1.0"
        audioChannels > 0 -> null
        Regex("(?<!\\d)7[ ._-]?1\\b").containsMatchIn(words) -> "7.1"
        Regex("(?<!\\d)5[ ._-]?1\\b").containsMatchIn(words) -> "5.1"
        else -> null
    }
    val audioTag = listOfNotNull(langName, channels.takeIf { langName != null || (!atmos && !dtsX) })
        .joinToString(" ").ifEmpty { null }
    if (audioTag != null) out += PlayerBadge(BadgeBrand.NONE, audioTag)

    val subName = subtitleLanguage?.let { Languages.code(it)?.let(Languages::name) ?: it }
        ?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
    if (subName != null) out += PlayerBadge(BadgeBrand.SUBTITLES, subName)

    if (stream?.remux == true) out += PlayerBadge(BadgeBrand.NONE, "REMUX")
    stream?.debrid?.let { d ->
        out += PlayerBadge(BadgeBrand.DEBRID, d.short, code = d.tag, accent = true)
    }
    return out
}

internal class OffsettableTextRenderer(
    private val delegate: TextRenderer
) : Renderer by delegate, RendererCapabilities by delegate {
    @Volatile
    var offsetUs: Long = 0L

    override fun getName(): String = delegate.name
    override fun getTrackType(): Int = delegate.trackType
    override fun getCapabilities(): RendererCapabilities = this

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(maxOf(0L, positionUs - offsetUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, join: Boolean) {
        delegate.resetPosition(maxOf(0L, positionUs - offsetUs), join)
    }
}

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
class PlayerActivity : Screen() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }
    private val isTrailer by lazy { intent.getBooleanExtra("isTrailer", false) }

    // The title's library item. Null when it couldn't be loaded: then nothing is saved, rather than
    // overwriting real history with a blank item.
    private var item: JSONObject? = null
    private var lastPosition = 0L
    private var ticks = 0

    private var sources: List<Stream> = emptyList()
    private var resolvingStreams: Cancellable? = null
    private lateinit var backdropArt: ImageView
    private lateinit var backdropShade: FrameLayout
    private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    private var startMessage: String? = null
    private var watched = false // any source showed a picture this time: only then is progress saved
    private var startAt = 0L
    private var prepared = false
    private lateinit var status: android.widget.TextView
    private lateinit var loading: FrameLayout
    private lateinit var stallPanel: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var pausedShade: View
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var refreshOptionsSubtitles: (() -> Unit)? = null
    private var chosenSub: Subtitle? = null
    private var selectAddonSub = false
    // The languages to start with: what was picked in Audio & subtitles for this title before (the next episode, a
    // resume), else the profile's Settings. Picks in the menu change them, are kept for the title, and hold when the
    // player moves to another source.
    private var wantAudio = ""
    private var wantSub = ""
    private var subsOn = true
    private var viewerAddonSub = false // the viewer chose the addon file, over the file's own in the same language
    private val dialogueProcessor = DialogueAudioProcessor()
    private var passthrough = false
    private var pickedSub: String? = null // the exact subtitle picked for this video before: "addon:<url>" or "file:<track id>"
    // Timing for subtitles (in the file or from add-ons): offset is applied directly to the TextRenderer live.
    private var subDelay = 0L
    private var textRenderer: OffsettableTextRenderer? = null
    private var subPref = "on"
    private var rememberedSubtitles: String? = null

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private var tickedWatched = false
    // "Up next": offered during credits; the current episode keeps playing until the viewer chooses the next one.
    private lateinit var upNextPanel: FrameLayout
    private var upNextDismissed = false
    // "Still watching?": episodes before this one that ended with no key pressed (carried in the intent from one
    // episode to the next, and to another source), and the sleep choices: stop when this one ends, or pause at sleepAt.
    private lateinit var stillPanel: FrameLayout
    private var quiet = 0
    private var stopAfter = false
    private var sleepAt = 0L
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    private val loadingTask = object : Runnable {
        override fun run() {
            if (loading.visibility != View.VISIBLE || player.playbackState != Player.STATE_BUFFERING) return
            showLoading(if (started) getString(R.string.player_loading) else startMessage
                ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
        }
    }
    private val startTimeout = Runnable {
        if (started) return@Runnable
        android.util.Log.w("Alder", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState}")
        next()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val sourcesJson = intent.getStringExtra("sources")
        if (!sourcesJson.isNullOrBlank()) {
            sources = JSONArray(sourcesJson).objects().map(Stream::read)
        }
        if (sources.isEmpty() && isTrailer) return finish()
        // Audio is decoded to PCM here unless Settings says a receiver takes Dolby and DTS: TVs report passthrough
        // (DTS-HD, TrueHD) they then can't open, and playback dies. A track that fails is swapped (onPlayerError).
        passthrough = Settings.passthrough(this)
        dialogueProcessor.enabled = !passthrough && Settings.clearDialogue(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                if (passthrough) DefaultAudioSink.Builder(context).build() else
                object : ForwardingAudioSink(DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(dialogueProcessor)).build()) {
                    override fun getFormatSupport(format: Format): Int =
                        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<Renderer>
            ) {
                val list = java.util.ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, list)
                val wrapped = list.map { r ->
                    if (r is TextRenderer) {
                        OffsettableTextRenderer(r).also { textRenderer = it }
                    } else r
                }
                out.addAll(wrapped)
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
        player = ExoPlayer.Builder(this, renderers).build()
        val remembered = Settings.titleChoice(this, metaId)
        wantAudio = remembered?.audio ?: Settings.audioLanguage(this)
        subPref = Settings.subPref(this)
        rememberedSubtitles = remembered?.subtitles
        subsOn = remembered?.subtitles?.isNotEmpty() ?: (subPref != "off")
        wantSub = remembered?.subtitles?.ifEmpty { null } ?: Settings.subtitleLanguage(this)
        pickedSub = remembered?.takeIf { it.subtitleVideo == videoId }?.subtitle
        if (pickedSub != null) { subsOn = true; viewerAddonSub = pickedSub!!.startsWith("addon:") }
        // Release files carry many dubs; start in the chosen language (the TV's unless Settings says) instead of the first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(wantAudio).setPreferredTextLanguage(wantSub).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Settings' size, color and background, with a shadow so text holds up on bright scenes either way.
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(context) / 100f)
            subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(context),
                if (Settings.subtitleBackground(context)) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            setBackgroundColor(Color.BLACK)
        }
        status = text("", BODY).onArt().apply {
            gravity = Gravity.CENTER
            maxWidth = dp(440)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pausedShade = View(this).apply { setBackgroundColor(0x4D000000); visibility = View.GONE }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        stillPanel = FrameLayout(this).apply { visibility = View.GONE }
        quiet = intent.getIntExtra("quiet", 0)
        sleepAt = intent.getLongExtra("sleepAt", 0L)
        stopAfter = intent.getBooleanExtra("stopAfter", false) // another source of this video; the next episode gets a fresh intent
        controls = buildControls()
        loading = buildLoading()
        stallPanel = FrameLayout(this).apply { visibility = View.GONE }
        skip = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = glass(10)
            visibility = View.GONE
        }
        if (!isTrailer) fetchSegments()
        options = FrameLayout(this).apply { visibility = View.GONE }
        val (bArt, bShade) = buildBackdrop()
        backdropArt = bArt
        backdropShade = bShade
        screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
            addView(backdropShade, FrameLayout.LayoutParams(-1, -1))
            addView(pausedShade, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
            addView(stallPanel, FrameLayout.LayoutParams(-1, -1))
            addView(upNextPanel, FrameLayout.LayoutParams(-1, -1))
            addView(stillPanel, FrameLayout.LayoutParams(-1, -1))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
        }
        setContentView(screen)
        background({
            Stremio.addons(key).let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            val episode = if (isTrailer) null else m.videos.firstOrNull { it.id == videoId }
            pauseTitle.text = episode?.title?.takeIf { it.isNotBlank() } ?: m.name
            subtitle.text = if (isTrailer) getString(R.string.action_trailer) else episode?.let { "S${it.season} E${it.episode}" } ?: m.year.orEmpty()
            Images.load(m.logo ?: Images.logoFor(m.id), playerLogo, dp(300), urgent = true, failed = {
                playerLogo.visibility = View.GONE; topName.visibility = View.VISIBLE
            }) {
                playerLogo.visibility = View.VISIBLE; topName.visibility = View.GONE
            }
            nextCell.visibility = if (isTrailer || nextVideo() == null) View.GONE else View.VISIBLE
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // A bad audio track (ffmpeg rejects a DTS-HD packet in The Raid around 5:14) shouldn't end the movie:
                // drop that track and carry on from the same second on the next best one.
                val failed = (e as? ExoPlaybackException)?.rendererFormat
                if (failed?.sampleMimeType?.let(MimeTypes::isAudio) == true && failedAudio.add(failed.id ?: "")) {
                    val next = bestAudio(audioGroups)
                    if (next != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, (0 until next.length).toList())).build()
                        player.prepare()
                        android.util.Log.i("Alder", "Audio track changed after decoder error")
                        return
                    }
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                if (retryable(e) && retries < RETRIES) {
                    retries++
                    // ExoPlayer's own retries already took about 6 s: each of ours gets a fresh START_WAIT, or the
                    // start timeout would move on to the next source before the second retry ran.
                    main.removeCallbacks(startTimeout)
                    main.postDelayed(startTimeout, RETRY_WAIT + START_WAIT)
                    main.postDelayed({ if (!isFinishing) player.prepare() }, RETRY_WAIT)
                    return
                }
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                next(e.errorCodeName)
            }

            override fun onRenderedFirstFrame() {
                if (started) return
                started = true
                watched = true
                retries = 0
                hideLoading()
                dismissBackdrop()
                main.removeCallbacks(startTimeout)
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                refreshBadges()
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                if (!isTrailer && sources.isNotEmpty()) {
                    val s = sources[at]
                    getSharedPreferences("sources", MODE_PRIVATE).edit()
                        .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
                }
                main.postDelayed({ if (player.playWhenReady) hideControls() }, 2000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // At the real end the next episode starts, unless the viewer chose to watch the credits or to stop after
                // this one, or this is the third episode in a row with no key pressed: then "Still watching?" waits.
                if (state == Player.STATE_ENDED) {
                    if (isTrailer) {
                        finish()
                        return
                    }
                    val upcoming = nextVideo()
                    // shortcut: sub-two-minute series streams are treated as addon placeholders; revisit for short-form shows.
                    if (type == "series" && !plausibleEpisode(player.duration)) {
                        started = false // retry from the original resume point, not the placeholder's short ending
                        next("source ended as a short placeholder")
                    } else if (upcoming == null || upNextDismissed || stopAfter) finish()
                    else if (askStillWatching(quiet + 1)) showStillWatching(upcoming)
                    else playNext(quiet + 1)
                }
                if (state == Player.STATE_BUFFERING) showLoading(if (started) getString(R.string.player_loading) else startMessage
                    ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
                if (state == Player.STATE_READY) {
                    if (started) {
                        hideLoading()
                        updateMarkers()
                    }
                }
                // A seek buffers too; only stalls in plain playback count.
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) stalled()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playPause.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 25, BASE)
                buttonLabels[playPause]?.text = getString(if (playWhenReady) R.string.player_pause else R.string.action_play)
                pausedShade.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                pauseKicker.visibility = if (isTrailer || !playWhenReady) View.VISIBLE else View.GONE
                if (playWhenReady) { if (shown) showControls(null) }
                else if (options.visibility != View.VISIBLE && upNextPanel.visibility != View.VISIBLE &&
                    stallPanel.visibility != View.VISIBLE && stillPanel.visibility != View.VISIBLE) showControls(playPause)
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    skipped.clear() // back into an intro: offer it again
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackStatus.text = trackStatus(tracks)
                refreshBadges(tracks)
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                    refreshOptionsSubtitles?.invoke()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                val best = bestAudio(audio)
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the profile's subtitle language, whatever the sound, else
                // none. The file's own come first, then the addon file attached at the start, then the file's own from
                // another country (Portugal's for Brazil's). Once per video, like audio.
                if (!subsPicked && best != null) {
                    subsPicked = true
                    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
                    fun inFile(score: Int) = texts.filterNot(::isAddonSub).filter { Languages.match(it.getTrackFormat(0).language, wantSub) == score }
                        .maxByOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0 } // full, not forced-only
                    val addon = texts.firstOrNull { isAddonSub(it) && Languages.same(chosenSub?.lang, wantSub) }
                    // The same track of the file as last time, while its language still matches (another source's file
                    // numbers its tracks its own way).
                    val exact = pickedSub?.takeIf { it.startsWith("file:") }?.removePrefix("file:")?.let { id ->
                        texts.firstOrNull { g -> !isAddonSub(g) && g.getTrackFormat(0).let { it.id == id &&
                            (it.language == null || Languages.same(it.language, wantSub)) } }
                    }
                    val audioLang = best.getTrackFormat(0).language
                    val foreignAudio = !Languages.same(audioLang, wantSub)
                    val forcedTrack = texts.filterNot(::isAddonSub).firstOrNull {
                        Languages.match(it.getTrackFormat(0).language, wantSub) >= 1 &&
                            (it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                    }
                    val pick = when {
                        !subsOn -> null
                        exact != null -> exact
                        subPref == "foreign" && !foreignAudio && rememberedSubtitles == null -> forcedTrack
                        else -> (if (viewerAddonSub) addon else null) ?: inFile(2) ?: addon ?: inFile(1)
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        if (pick == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        else setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
                    }.build()
                }
                if (audioPicked || best == null) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }
        })

        // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
        val known = if (isTrailer || intent.getBooleanExtra("startOver", false)) 0L else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 }
        // The addon's subtitles are waited for (SUB_WAIT at most) so a file in the profile's language is attached from
        // the start: attaching one later means loading the source again. The latest resume point asked for wins.
        var subsAnswered = isTrailer || !subsOn
        var waiting: Long? = null
        var pendingResume: Long? = null
        fun begin(resume: Long) {
            if (prepared) return
            if (sources.isEmpty()) {
                pendingResume = resume
                return
            }
            if (!subsAnswered) { waiting = resume; return }
            load(resume)
            lastPosition = resume
            prepared = true
            if (!isTrailer) {
                main.postDelayed(tickTask, TICK)
                main.post(skipTask)
            }
        }
        if (isTrailer) {
            begin(0L)
            return
        }
        if (sources.isEmpty()) {
            pauseFacts.text = getString(R.string.finding_sources)
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
            resolvingStreams = StreamsResolver.resolve(
                context = this,
                type = type,
                videoId = videoId,
                metaId = metaId,
                authKey = key,
                onReady = { best ->
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    sources = best
                    refreshBadges()
                    pauseFacts.text = getString(R.string.player_starting_stream)
                    val resumeToStart = pendingResume ?: known ?: 0L
                    begin(resumeToStart)
                },
                onFallback = {
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent))
                    finish()
                }
            )
        }
        val waitMs = if (pickedSub?.startsWith("addon:") == true) 3_500L else SUB_WAIT
        val stopWaiting = Runnable { subsAnswered = true; waiting?.let(::begin) }
        main.postDelayed(stopWaiting, waitMs)
        background({
            // Every language: the profile's subtitle language and English first, three files each in the addon's order,
            // then one file for each other language by name, so the list stays short enough for a remote.
            val all = Stremio.addons(key).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId) }
                    .onFailure { android.util.Log.w("Alder", "subtitles from ${a.name} failed", it) }.getOrDefault(emptyList()) }
            all.groupBy { Languages.code(it.lang) ?: it.lang }.entries
                .sortedWith(compareBy({ it.key != wantSub }, { it.key != "en" }, { Languages.name(it.key) }))
                .flatMap { (code, files) -> files.take(if (code == wantSub || code == "en") 3 else 1) }
        }) { r ->
            addonSubs = r.getOrDefault(emptyList())
            android.util.Log.i("Alder", "addon subtitles: ${addonSubs.size}", r.exceptionOrNull())
            if (options.visibility == View.VISIBLE) {
                refreshOptionsSubtitles?.invoke()
            }
            val exact = addonSubs.firstOrNull { "addon:" + it.url == pickedSub }
            if (subsAnswered) { // too late for the start: offered in the list, and the file picked last time attached now
                if (exact != null && chosenSub == null) {
                    chosenSub = exact
                    if (prepared) {
                        selectAddonSub = true
                        val playing = player.playWhenReady
                        load(position())
                        player.playWhenReady = playing
                    }
                }
                return@background
            }
            main.removeCallbacks(stopWaiting)
            if (chosenSub == null) chosenSub = exact ?: addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } // attached, picked above
            stopWaiting.run()
        }
        // The stream starts at once from what's known here (the intent, or the library saved on the TV) instead of
        // waiting a round trip to Stremio for the resume point; Stremio's answer corrects it if it moved elsewhere.
        if (known != null) begin(known)
        else background({ Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { r ->
            r.getOrNull()?.let { begin(Library.resumeAt(it, videoId)) }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            item = result.fold(
                { it ?: Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster")) },
                { null },
            )
            val resume = known ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            if (!prepared) begin(resume)
            // Watched further on the phone since the TV saved its copy: go there, unless you've already moved on.
            else if (known == null && abs(resume - startAt) > RESUME_SLACK && position() < startAt + RESUME_SLACK) {
                startAt = resume
                player.seekTo(resume)
                lastPosition = resume
            }
        }
    }

    private fun buildBackdrop(): Pair<ImageView, FrameLayout> {
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val bg = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        if (!bg.isNullOrBlank()) {
            Images.load(bg, art, resources.displayMetrics.widthPixels)
        }
        val shade = FrameLayout(this).apply {
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xEB0E0C0A.toInt(), 0x990E0C0A.toInt(), 0x330E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xF70E0C0A.toInt(), 0xB30E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
        }
        return art to shade
    }

    private fun dismissBackdrop() {
        if (::backdropArt.isInitialized && backdropArt.visibility == View.VISIBLE) {
            backdropArt.animate().alpha(0f).setDuration(350).withEndAction { backdropArt.visibility = View.GONE }
            backdropShade.animate().alpha(0f).setDuration(350).withEndAction { backdropShade.visibility = View.GONE }
        }
    }

    private fun buildLoading() = FrameLayout(this).apply {
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(0x800E0C0A.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        val center = column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun showLoading(message: String) {
        if (started) {
            status.text = if (message == getString(R.string.player_loading) && player.bufferedPercentage in 1..99)
                getString(R.string.player_loading_percent, player.bufferedPercentage) else message
            loading.visibility = View.VISIBLE
            main.removeCallbacks(loadingTask)
            if (player.playbackState == Player.STATE_BUFFERING) main.postDelayed(loadingTask, 500)
        } else {
            pauseFacts.text = message
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
        }
    }

    private fun hideLoading() {
        main.removeCallbacks(loadingTask)
        loading.visibility = View.GONE
        status.text = ""
        bar.isIndeterminate = false
    }

    /** A subtitle pick in the menu: the language ("" for Off) for this title's next episode and resume, and for the
     *  next source if this one fails, and [pick] itself for this video. A track with no language turns subtitles on
     *  without naming one. */
    private fun remember(language: String?, addonFile: Boolean, pick: String) {
        subsOn = language != ""
        viewerAddonSub = addonFile
        pickedSub = pick.ifEmpty { null }
        val code = if (language == "") "" else Languages.code(language)
        rememberedSubtitles = code
        if (!code.isNullOrEmpty()) wantSub = code
        Settings.pickedInPlayer(this, metaId, subtitles = code, video = videoId, subtitle = pick)
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long, message: String? = null) {
        if (sources.isEmpty()) return
        val s = sources[at]
        startAt = position
        started = false
        toldUnseekable = false
        startMessage = message ?: getString(R.string.player_starting, s.label)
        bar.isIndeterminate = true
        showLoading(startMessage!!)
        subDelay = 0L
        textRenderer?.offsetUs = 0L
        mkvChapters = emptyList()
        setChapterSnapMode(false)
        updateMarkers()
        refreshBadges()
        showControls(null)
        val sourceUrl = s.url
        val sourceHeaders = s.headers
        background({
            MkvChapters.fetch(sourceUrl, sourceHeaders)
        }) { r ->
            val chaps = r.getOrNull()
            if (!chaps.isNullOrEmpty() && sources.getOrNull(at)?.url == sourceUrl) {
                mkvChapters = chaps
                reconcileSegments()
            }
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
        val item = MediaItem.Builder().setUri(s.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url)).setMimeType(it.mimeType)
                    .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
            })).build()
        // Files with no index (.ts, raw audio, some .avi) seek by their bitrate instead of not at all: ExoPlayer turns
        // every seek in a file it can't seek into a jump to 0:00, so scrubbing restarted the movie.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors).createMediaSource(item), position) // files too: shifted subtitles
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, START_WAIT)
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: String = "didn't start") {
        main.removeCallbacks(startTimeout)
        val position = position()
        if (at + 1 < sources.size) {
            at++
            stalls.clear()
            retries = 0
            asHls = false
            audioPicked = false
            subsPicked = false
            failedAudio.clear()
            android.util.Log.w("Alder", "Source failed: $reason; trying another")
            return load(position, getString(R.string.player_source_failed_next))
        }
        if (isTrailer) {
            intent.getStringExtra("trailerYtId")?.takeIf { it.isNotBlank() }?.let { ytId ->
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$ytId")))
                }.isSuccess
                if (!opened) runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$ytId")))
                }
            }
            finish()
            return
        }
        if (intent.getBooleanExtra("picked", false)) {
            showSourceError()
            player.stop()
            showLoading(getString(R.string.player_source_failed_choose))
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", "${sources.size} sources didn't play"))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<String>() // Format ids of audio tracks that failed to decode

    /**
     * The start track: never a commentary (releases only say so in the track name, which ExoPlayer ignores), then the
     * TV's language, then the release's default track, then the most channels. ExoPlayer alone would favour whatever
     * the TV decodes in hardware, which on The Raid was a stereo commentary.
     */
    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val language = wantAudio
        return groups
            .filter { it.isTrackSupported(0) && (it.getTrackFormat(0).id ?: "") !in failedAudio }
            .filter { it.getTrackFormat(0).label?.contains("commentary", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Tracks.Group>(
                { Languages.match(it.getTrackFormat(0).language, language) },
                { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
                { it.getTrackFormat(0).channelCount },
            ))
    }

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        if (sleepAt in 1..System.currentTimeMillis() && stillPanel.visibility != View.VISIBLE) {
            setSleepAt(0)
            showStillWatching(null)
        }
        record()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    override fun onStart() {
        super.onStart()
        if (prepared) {
            lastPosition = player.currentPosition
            if (!isTrailer) {
                main.postDelayed(tickTask, TICK)
                main.post(skipTask)
            }
            if (loading.visibility == View.VISIBLE && player.playbackState == Player.STATE_BUFFERING)
                main.post(loadingTask)
        }
    }

    private fun record() {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        // Live TV has no progress to keep, nor does a channel or event whose stream doesn't say it's live.
        if (isTrailer || !started || player.isCurrentMediaItemLive || isLiveType(type)) return
        if (type == "series" && !plausibleEpisode(player.duration)) return
        val item = item ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Keep the current episode full screen; offer the next one only near its actual end.
        // If credits timing (outroAt) is known, wait until outroAt before showing Up next.
        val shouldShowUpNext = if (outroAt > 0) position >= outroAt else duration - position < upNextWindow(duration)
        if (!upNextDismissed && upNextPanel.visibility != View.VISIBLE && shouldShowUpNext) showUpNext()
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(onlyAired: Boolean = true): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        val next = videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
        return if (onlyAired && next != null && !isEpisodeAired(next.released)) null else next
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        val height = sources[at].height
        if (stalls.size != 3 || offeringLower || upNextPanel.visibility == View.VISIBLE || lowerSourceHeight(height) == null) return
        showStall(height)
    }

    private fun showStall(height: Int) {
        val lower = lowerSourceHeight(height) ?: return
        offeringLower = true
        stallPanel.removeAllViews()
        val card = column(Gravity.START).apply {
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = videoGlass(14)
            addView(caps(R.string.player_keeps_buffering, TUNGSTEN))
            addView(text(getString(R.string.player_switch_prompt, lower), SERIF_TITLE, serif = true),
                wrap().apply { topMargin = dp(4) })
            addView(text(getString(R.string.player_switch_desc, height, clock(position())),
                SMALL, color = TEXT_2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val actions = LinearLayout(this@PlayerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val lowerButton = pill(getString(R.string.player_switch_to, lower)).apply { setOnClickListener { lowerQuality() } }
            actions.addView(lowerButton, wrap())
            actions.addView(chip(getString(R.string.player_keep, height)).apply { setOnClickListener { dismissStall() } },
                wrap().apply { marginStart = dp(6) })
            actions.addView(chip(R.string.player_other_sources).apply { setOnClickListener { changeStream() } },
                wrap().apply { marginStart = dp(6) })
            addView(actions, wrap().apply { topMargin = dp(12) })
            lowerButton.post { lowerButton.requestFocus() }
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(460), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
    }

    private fun dismissStall() {
        offeringLower = false
        stallPanel.visibility = View.GONE
        stallPanel.removeAllViews()
    }

    private fun showSourceError() {
        stallPanel.removeAllViews()
        val card = column(Gravity.START,
            caps(R.string.player_source_failed, TUNGSTEN),
            text(R.string.player_try_another, SERIF_TITLE, serif = true).marginTop(6),
            pill(R.string.player_other_sources).apply { setOnClickListener { changeStream() } }.marginTop(12)
        ).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = videoGlass(14)
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(310), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
        card.getChildAt(2).requestFocus()
    }

    /** Continue at the same second with one quality step lower. */
    private fun lowerQuality() {
        val height = sources[at].height
        val lower = lowerSourceHeight(height) ?: return
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("pick")
        }.putExtra("maxHeight", lower).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (type == "series" && !plausibleEpisode(player.duration)) return
        if (stopAfter || stillPanel.visibility == View.VISIBLE) return // Sleep: After this episode
        val next = nextVideo() ?: return
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        hideLoading()
        upNextPanel.removeAllViews()
        val playNext = pill(R.string.player_next_episode, R.drawable.ic_play).apply { setOnClickListener { playNext() } }
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            // Video can't be frosted: a dark base under the glass keeps it readable over white end cards.
            background = videoGlass(14)
            addView(caps(R.string.player_up_next, TUNGSTEN).onArt())
            addView(text(next.title.ifBlank { getString(R.string.episode_number, next.episode) }, SERIF_TITLE, serif = true).onArt(),
                wrap().apply { topMargin = dp(6) })
            addView(text("S${next.season} E${next.episode}", SMALL, color = TEXT_2).onArt(), wrap().apply { topMargin = dp(5) })
            addView(playNext, wrap().apply { topMargin = dp(16) })
        }
        upNextPanel.addView(content, FrameLayout.LayoutParams(dp(360), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        upNextPanel.visibility = View.VISIBLE
        playNext.requestFocus()
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        upNextPanel.visibility = View.GONE
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here.
     *  [quietly]: episodes in a row that ended with no key pressed (0 when the viewer chose it). */
    private fun playNext(quietly: Int = 0) {
        val next = nextVideo() ?: return finish()
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}")
            .putExtra("logo", meta?.logo).putExtra("still", next.thumbnail).putExtra("runtime", meta?.runtime)
            .putExtra("episode", "S${next.season} E${next.episode}  ·  ${next.title}")
            .putExtra("quiet", quietly).putExtra("sleepAt", sleepAt))
        finish()
    }

    /**
     * "Still watching?", in the Up next place: before the next episode once [QUIET_EPISODES] ended with no key pressed
     * ([next]), or when the sleep timer runs out (null: Resume). Playback stops, and the screen may sleep.
     */
    private fun showStillWatching(next: Video?) {
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        upNextPanel.visibility = View.GONE
        hideLoading()
        stillPanel.removeAllViews()
        stillPanel.visibility = View.VISIBLE
        player.pause()
        pausedShade.visibility = View.VISIBLE
        view.keepScreenOn = false // nobody may be watching: let the TV's own screen saver and sleep take over
        val play = pill(if (next != null) getString(R.string.player_play_episode, next.season, next.episode)
            else getString(R.string.action_resume), R.drawable.ic_play).apply {
            setOnClickListener { if (next != null) playNext() else leaveStillWatching() }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(play, wrap())
        actions.addView(chip(R.string.player_exit_home).apply {
            setOnClickListener {
                startActivity(Intent(this@PlayerActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }, wrap().apply { marginStart = dp(8) })
        val title = next?.let { it.title.ifBlank { getString(R.string.episode_number, it.episode) } } ?: pauseTitle.text.toString()
        val why = if (next != null) getString(R.string.player_quiet_episodes, QUIET_EPISODES) else getString(R.string.player_sleep_paused)
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = videoGlass(14)
            addView(caps(R.string.player_still_watching, TUNGSTEN).onArt())
            addView(text(title, SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(why, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(actions, wrap().apply { topMargin = dp(16) })
        }
        stillPanel.addView(content, FrameLayout.LayoutParams(dp(500), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        play.requestFocus()
    }

    /** Back or Resume on "Still watching?": at the end the player closes; mid-episode it stays paused with the controls. */
    private fun leaveStillWatching(resume: Boolean = true) {
        if (player.playbackState == Player.STATE_ENDED) return finish()
        stillPanel.visibility = View.GONE
        stillPanel.removeAllViews()
        view.keepScreenOn = true
        if (resume) player.play()
        showControls(playPause)
    }

    private fun setSleepAt(at: Long) {
        sleepAt = at
        intent.putExtra("sleepAt", at) // so another source of this video keeps the timer
    }

    /** OK on the Sleep row: Off → After this episode (series) → In 60 min → Off. */
    private fun cycleSleep() {
        when {
            stopAfter -> { stopAfter = false; setSleepAt(System.currentTimeMillis() + SLEEP_TIMER) }
            sleepAt > 0 -> setSleepAt(0)
            type == "series" -> stopAfter = true
            else -> setSleepAt(System.currentTimeMillis() + SLEEP_TIMER)
        }
        intent.putExtra("stopAfter", stopAfter)
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun push() {
        if (isTrailer || !watched || player.isCurrentMediaItemLive || isLiveType(type)) return
        val key = authKey ?: return
        val snapshot = item?.let { JSONObject(it.toString()) } ?: return
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Someone is awake: the count of episodes with no key pressed starts again (also for another source of this one).
        if (event.action == KeyEvent.ACTION_DOWN && quiet != 0) { quiet = 0; intent.removeExtra("quiet") }
        if (stillPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) leaveStillWatching(resume = false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (upNextPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    wakeKey = KeyEvent.KEYCODE_BACK
                    dismissUpNext()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (stallPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissStall()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!started) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    resolvingStreams?.cancel()
                    resolvingStreams = null
                    finish()
                }
                return true
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        if (options.visibility == View.VISIBLE) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (!down) closeOptions()
            return true
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            (skip.tag as? IntroSegment)?.takeIf { canSeek() }?.let { player.seekTo(it.endMs) }
            skip.visibility = View.GONE
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        // Back with the controls up closes them; the next Back leaves the player.
        if (shown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (chapterSnapMode) {
                if (!down) setChapterSnapMode(false)
                return true
            }
            if (!down) hideControls(force = true)
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wakeKey = event.keyCode
                if (player.playWhenReady) player.pause() else player.play()
                showControls(playPause)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK
    // jumps to its end. The credits start, when known, is when Up next appears, rather than the last 4% guess.
    // ponytail: series with IMDb ids only; anime (AniSkip by MAL id) and films' credits aren't asked for.
    private lateinit var skip: TextView
    private var segments = emptyList<IntroSegment>()
    private var introDbSegments = emptyList<IntroSegment>()
    private var outroAt = 0L
    private var introDbOutroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<IntroSegment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private fun fetchSegments() {
        val parts = videoId.split(":")
        if (type != "series" || !metaId.startsWith("tt") || parts.size < 3) return
        background({
            val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=${parts[0]}&season=${parts[1]}&episode=${parts[2]}"))
            fun part(name: String) = json.optJSONObject(name)?.let { seg ->
                val startSec = seg.optDouble("start_sec", -1.0)
                val endSec = seg.optDouble("end_sec", -1.0)
                val confidence = seg.optDouble("confidence", 1.0)
                // Filter out low confidence segments (must be >= 0.8)
                if (confidence < 0.8 || startSec < 0 || endSec <= startSec) null
                else (startSec * 1000).toLong() to (endSec * 1000).toLong()
            }
            Triple(part("intro"), part("recap"), part("outro"))
        }) { r ->
            val (intro, recap, outro) = r.getOrNull() ?: return@background
            introDbSegments = listOfNotNull(
                recap?.let { IntroSegment("Skip recap", it.first, it.second) },
                intro?.let { IntroSegment("Skip intro", it.first, it.second) }
            )
            introDbOutroAt = outro?.first ?: 0L
            reconcileSegments()
        }
    }

    private fun reconcileSegments() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val mkvIntros = MkvChapters.findIntroSegments(mkvChapters, duration)
        if (mkvIntros.isNotEmpty()) {
            segments = mkvIntros.map { IntroSegment(it.label, it.startMs, it.endMs) }
            outroAt = introDbOutroAt
        } else {
            // Reject zero-second starts from IntroDB if file has chapters indicating a later start or cold open
            val hasColdOpenChapter = mkvChapters.any { it.startMs in 1L..120_000L }
            segments = introDbSegments.filterNot { seg ->
                seg.startMs == 0L && hasColdOpenChapter
            }
            outroAt = introDbOutroAt
        }
        updateMarkers()
    }

    private fun updateMarkers() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        markers = TimelineMarkers.merge(mkvChapters, segments, outroAt, duration)
        bar.setMarkers(markers)
        if (shown) updateProgress()
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.startMs && position < it.endMs - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            // The label names the kind ("Skip recap"); the words come from the viewer's language.
            skip.text = getString(if (now.label.contains("recap", ignoreCase = true)) R.string.skip_recap else R.string.skip_intro) + "   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && player.duration - position < 60_000 &&
            !upNextDismissed && upNextPanel.visibility != View.VISIBLE && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- player controls ----------
    private lateinit var controls: FrameLayout
    private lateinit var bar: SeekBar
    private lateinit var chapterPill: TextView
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private lateinit var audioButton: TextView
    private lateinit var trackStatus: TextView // "English  ·  Spanish subtitles", under the bar whenever the controls are up
    private lateinit var nextCell: FrameLayout
    private lateinit var playerLogo: ImageView
    private lateinit var topName: TextView
    private lateinit var pauseKicker: TextView
    private lateinit var pauseTitle: TextView
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseFacts: TextView
    private lateinit var pauseBadges: LinearLayout
    private lateinit var subtitle: TextView // the year or S5 E2
    private val buttonLabels = mutableMapOf<View, TextView>()
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private var chapterSnapMode = false
    private var markers = emptyList<Marker>()
    private var mkvChapters = emptyList<MkvChapter>()
    private var shownBadges = emptyList<PlayerBadge>()
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 } }

    private fun pillBackground(snapMode: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (snapMode) {
            setColor(0xE60E0C0A.toInt())
            setStroke(dp(1), Screen.TUNGSTEN_RIM)
        } else {
            setColor(0xCC0E0C0A.toInt())
        }
    }

    private fun setChapterSnapMode(enabled: Boolean) {
        if (chapterSnapMode == enabled) return
        chapterSnapMode = enabled
        chapterPill.background = pillBackground(enabled)
        chapterPill.setTextColor(if (enabled) Screen.TUNGSTEN else Screen.TEXT_2)
        bar.invalidate()
        if (shown) updateProgress()
    }

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name").orEmpty()
        playerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        topName = text(name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val logoUrl = intent.getStringExtra("logo") ?: Images.logoFor(metaId)
        Images.load(logoUrl, playerLogo, dp(300), urgent = true, failed = {
            playerLogo.visibility = View.GONE
            topName.visibility = View.VISIBLE
        }) {
            playerLogo.visibility = View.VISIBLE
            topName.visibility = View.GONE
        }
        pauseTitle = text(intent.getStringExtra("episode")?.substringAfter("  ·  ", "")?.takeIf { it.isNotBlank() }
            ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text(if (isTrailer) getString(R.string.action_trailer) else intent.getStringExtra("episode")?.substringBefore("  ·  ").orEmpty(), SMALL, color = TEXT_2).onArt()
        pauseFacts = text(if (sources.isEmpty() && !isTrailer) getString(R.string.finding_sources) else "", SMALL, color = TEXT_2).onArt()
        pauseBadges = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val pauseMetaRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pauseFacts, wrap().apply { marginEnd = dp(10) })
            addView(pauseBadges, wrap())
        }
        pauseKicker = caps(if (isTrailer) R.string.action_trailer else R.string.player_paused, TUNGSTEN).onArt().apply {
            visibility = if (isTrailer) View.VISIBLE else View.GONE
        }
        pauseInfo = column(Gravity.START).apply {
            addView(pauseKicker)
            addView(pauseTitle, LinearLayout.LayoutParams(dp(640), -2).apply { topMargin = dp(6) })
            addView(pauseMetaRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        refreshBadges()

        trackStatus = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        chapterPill = text("", SMALL, color = TEXT_2).onArt().apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = pillBackground(false)
            visibility = View.INVISIBLE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        elapsed = text("", SMALL).onArt()
        length = text("", SMALL).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            contentDescription = getString(R.string.player_seek)
            setOnFocusChangeListener { _, focused ->
                if (!focused) setChapterSnapMode(false)
                invalidate()
            }
            setOnClickListener {
                if (chapterSnapMode) {
                    if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                    setChapterSnapMode(false)
                } else {
                    if (player.playWhenReady) player.pause() else player.play()
                }
            }
            setOnKeyListener { _, code, event ->
                val current = if (scrubTo >= 0) scrubTo else player.currentPosition
                if (chapterSnapMode) {
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val next = TimelineMarkers.findNext(markers, current)
                            if (next != null) {
                                scrubTo = next.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val prev = TimelineMarkers.findPrev(markers, current)
                            if (prev != null) {
                                scrubTo = prev.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            setChapterSnapMode(false)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                            setChapterSnapMode(false)
                            true
                        }
                        else -> false
                    }
                } else {
                    val by = when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.action == KeyEvent.ACTION_DOWN && markers.size > 1) {
                                setChapterSnapMode(true)
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (scrubTo >= 0) {
                                scrubTo = -1
                                updateProgress()
                            }
                            return@setOnKeyListener false
                        }
                        else -> return@setOnKeyListener false
                    }
                    if (event.action == KeyEvent.ACTION_DOWN)
                        scrub(by * when { event.repeatCount < 10 -> SKIP; event.repeatCount < 30 -> 30_000L; else -> 60_000L })
                    true
                }
            }
        }

        audioButton = button(getString(R.string.player_audio_subtitles), R.drawable.ic_subtitles) { openOptions() }
        val streamButton = button(getString(R.string.player_change_stream), R.drawable.ic_layers) { changeStream() }
        playPause = button(getString(R.string.player_pause), R.drawable.ic_pause, primary = true) {
            if (player.playWhenReady) player.pause() else player.play()
        }
        val nextButton = button(getString(R.string.player_next_episode), R.drawable.ic_next_episode) { playNext() }
        val infoButton = button(getString(R.string.player_info), R.drawable.ic_info) {
            startActivity(Intent(this, DetailsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("type", type).putExtra("id", metaId)
                .putExtra("name", intent.getStringExtra("name"))
                .putExtra("poster", intent.getStringExtra("poster"))
                .putExtra("background", intent.getStringExtra("background")))
            finish()
        }
        nextCell = buttonCell(nextButton).apply { visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            clipChildren = false // a focused button's label is wider than its cell
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            if (!isTrailer) {
                addView(buttonCell(streamButton))
                addView(buttonCell(audioButton))
                addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                    LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginEnd = dp(10) })
            }
            addView(buttonCell(playPause, true))
            if (!isTrailer) {
                addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                    LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginStart = dp(10) })
                addView(nextCell)
                addView(buttonCell(infoButton))
            }
        }
        for (button in listOf(streamButton, audioButton, playPause, nextButton, infoButton))
            button.nextFocusUpId = bar.id
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id

        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x990E0C0A.toInt(), 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xBF0E0C0A.toInt(), 0x590E0C0A, 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM))
            addView(playerLogo, FrameLayout.LayoutParams(dp(150), dp(50)).apply {
                leftMargin = dp(PAD); topMargin = dp(26)
            })
            addView(topName, FrameLayout.LayoutParams(dp(250), -2).apply {
                leftMargin = dp(PAD); topMargin = dp(31)
            })
            addView(TextClock(this@PlayerActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2); typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(34); rightMargin = dp(PAD)
            })
            addView(pauseInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(168)
            })
            addView(chapterPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(137)
            })
            addView(bar, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM).apply {
                leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(111)
            })
            addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(91)
            })
            addView(length, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(91)
            })
            addView(buttons, FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM).apply {
                bottomMargin = dp(11)
            })
            addView(column(Gravity.END, keyHint("OK", getString(R.string.key_play_pause)), keyHint("◀ ▶", getString(R.string.key_seek))),
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(35)
            })
        }
    }

    private fun refreshBadges(tracks: Tracks = player.currentTracks) {
        if (!::pauseBadges.isInitialized) return
        if (isTrailer) {
            pauseBadges.visibility = View.GONE
            return
        }
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)
        val textGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subLang = if (textGroup == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) null
        else (if (isAddonSub(textGroup)) chosenSub?.lang else textGroup.getTrackFormat(0).language)
            ?.let(Languages::code)?.let(Languages::name) ?: getString(R.string.state_on)
        val videoHeight = player.videoFormat?.height?.takeIf { it > 0 }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.height ?: 0
        val badges = playerBadges(
            stream = sources.getOrNull(at),
            videoHeight = videoHeight,
            audioLanguage = audio?.language,
            audioMime = audio?.sampleMimeType,
            audioLabel = audio?.label,
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
            subtitleLanguage = subLang,
        )
        if (badges == shownBadges && pauseBadges.childCount > 0) return
        shownBadges = badges
        pauseBadges.removeAllViews()
        pauseBadges.visibility = if (badges.isEmpty()) View.GONE else View.VISIBLE
        badges.forEachIndexed { index, badge ->
            pauseBadges.addView(badgeView(badge), LinearLayout.LayoutParams(-2, dp(20)).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
    }

    /** Monochrome House Lights brand badge: custom vector marks for Dolby / DTS / Subtitles / Debrid, or crisp typographic lockups. */
    private fun badgeView(b: PlayerBadge): View {
        val rim = if (b.accent) 0x80F3D9B1.toInt() else 0x66FFF0DC
        val frame = GradientDrawable().apply {
            setColor(VIDEO_CONTROL_BASE)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), rim)
        }
        return when (b.brand) {
            BadgeBrand.DOLBY -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dolbyIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(13), dp(9)).apply { marginEnd = dp(5) })
                addView(text("DOLBY ${b.label}", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.DTS -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dtsIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(4) })
                val suffix = when (b.label) { "HD" -> "-HD"; "X" -> ":X"; else -> "" }
                addView(text("DTS$suffix", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.SUBTITLES -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(5), 0, dp(6), 0)
                addView(ImageView(context).apply {
                    setImageDrawable(getDrawable(R.drawable.ic_subtitles)?.mutate()?.apply { setTint(TEXT_2) })
                }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) })
                addView(text(b.label, 9.5f, bold = true, color = TEXT_2).apply {
                    includeFontPadding = false
                    letterSpacing = 0.05f
                }, wrap())
            }
            BadgeBrand.DEBRID -> if (b.label == "TB") {
                FrameLayout(this).apply {
                    background = frame
                    setPadding(dp(5), 0, dp(5), 0)
                    addView(torboxIcon(TUNGSTEN), FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
                }
            } else {
                text(b.code ?: b.label, 9.5f, bold = true, color = TUNGSTEN).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    letterSpacing = 0.04f
                    background = frame
                    setPadding(dp(6), 0, dp(6), 0)
                }
            }
            BadgeBrand.NONE -> text(b.label, 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT_2).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = 0.05f
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
    }

    /** TorBox isometric 3D cube mark, drawn in [color]. */
    private fun torboxIcon(color: Int) = object : View(this) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.25f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = android.graphics.Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val topY = h * 0.08f
            val midY = h * 0.32f
            val botMidY = h * 0.68f
            val botY = h * 0.92f
            val leftX = w * 0.10f
            val rightX = w * 0.90f
            path.reset()
            // Outer hexagon of the cube
            path.moveTo(cx, topY)
            path.lineTo(rightX, midY)
            path.lineTo(rightX, botMidY)
            path.lineTo(cx, botY)
            path.lineTo(leftX, botMidY)
            path.lineTo(leftX, midY)
            path.close()
            // Inner Y edges of the cube
            path.moveTo(leftX, midY)
            path.lineTo(cx, h * 0.52f)
            path.lineTo(rightX, midY)
            path.moveTo(cx, h * 0.52f)
            path.lineTo(cx, botY)
            canvas.drawPath(path, stroke)
        }
    }

    /** The Dolby double-D mark (two opposing D shapes), drawn in [color]. */
    private fun dolbyIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            path.reset()
            rect.set(0f, 0f, w * 0.44f, h)
            path.addRoundRect(rect, floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), android.graphics.Path.Direction.CW)
            rect.set(w * 0.56f, 0f, w, h)
            path.addRoundRect(rect, floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    /** A stylized three-bar DTS acoustic slash mark, drawn in [color]. */
    private fun dtsIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val barW = w * 0.22f
            val r = barW / 2f
            canvas.drawRoundRect(0f, h * 0.30f, barW, h * 0.70f, r, r, paint)
            canvas.drawRoundRect(w * 0.39f, h * 0.14f, w * 0.39f + barW, h * 0.86f, r, r, paint)
            canvas.drawRoundRect(w - barW, 0f, w, h, r, r, paint)
        }
    }

    /** An icon of [size] dp in the middle of whatever it's drawn over (a button's circle). */
    private fun centred(res: Int, size: Int, tint: Int) = getDrawable(res)?.mutate()?.let { d ->
        d.setTint(tint)
        android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
            setLayerSize(0, dp(size), dp(size)); setLayerGravity(0, Gravity.CENTER)
        }
    }

    private fun button(label: String, res: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        val words = text(label, SMALL, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER; visibility = View.INVISIBLE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val radius = if (primary) 25 else 20
        val control = text("", BODY).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            isFocusable = true
            foreground = centred(res, if (primary) 25 else 20, if (primary) BASE else TEXT)
            if (primary) {
                fun face(focused: Boolean) = GradientDrawable().apply {
                    setColor(TEXT); cornerRadius = dp(radius).toFloat()
                }
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    view.grow(focused).start()
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            } else {
                fun face(focused: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
                    rounded(VIDEO_CONTROL_BASE, radius), glass(radius, focused)))
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            }
            setOnClickListener { onClick() }
        }
        buttonLabels[control] = words
        return control
    }

    private fun buttonCell(control: TextView, primary: Boolean = false) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(control, FrameLayout.LayoutParams(dp(if (primary) 50 else 40), dp(if (primary) 50 else 40),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(buttonLabels.getValue(control), FrameLayout.LayoutParams(dp(160), dp(18),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (primary) 53 else 48) })
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 70 else 62), dp(76))
    }

    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.28f) // keep captions above the seek bar and buttons
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            updateProgress()
            (focus ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady) main.postDelayed(hideTask, HIDE)
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && !player.playWhenReady) return
        setChapterSnapMode(false)
        chapterPill.visibility = View.INVISIBLE
        shown = false
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.animate().alpha(0f).setDuration(250).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown && options.visibility != View.VISIBLE) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        if (shown) {
            if (!bar.isIndeterminate) {
                bar.set(position, player.bufferedPosition, if (live) 0L else duration)
                elapsed.text = if (live) getString(R.string.player_live) else clock(position)
                length.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
            }
            val active = if (markers.size > 1) TimelineMarkers.findCurrent(markers, position) else null
            if ((bar.isFocused || scrubTo >= 0 || chapterSnapMode) && active != null && !live) {
                chapterPill.text = when {
                    chapterSnapMode -> "‹  ${active.title}  ›"
                    bar.isFocused && scrubTo < 0 -> getString(R.string.player_chapters, active.title)
                    else -> active.title
                }
                chapterPill.visibility = View.VISIBLE
                val knob = dp(if (bar.isFocused) 9 else 6).toFloat()
                val barWidth = bar.width.takeIf { it > 0 } ?: (controls.width - 2 * dp(PAD))
                val knobX = knob + (barWidth - 2 * knob) * (if (duration > 0) position.coerceIn(0, duration) / duration.toFloat() else 0f)
                chapterPill.post {
                    val pillWidth = chapterPill.width
                    val targetX = (knobX - pillWidth / 2f).coerceIn(0f, (barWidth - pillWidth).toFloat().coerceAtLeast(0f))
                    chapterPill.translationX = targetX
                }
            } else {
                chapterPill.visibility = View.INVISIBLE
            }
        }
        if (options.visibility == View.VISIBLE) {
            optionsBar.set(position, player.bufferedPosition, if (live) 0L else duration)
            optionsElapsed.text = if (live) getString(R.string.player_live) else clock(position)
            optionsLeft.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        val remaining = (duration - position).coerceAtLeast(0)
        val end = if (duration > 0) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + remaining)) else null
        if (started) {
            pauseFacts.text = playerPauseFacts(subtitle.text.toString(), end, isTrailer, live, getString(R.string.player_ends_at))
            pauseFacts.visibility = if (pauseFacts.text.isEmpty()) View.GONE else View.VISIBLE
        }
        main.postDelayed(progressTask, 500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive || !canSeek()) return
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** Whether this file can jump to another second. A seek in one that can't (no index, even by bitrate) lands on
     *  0:00, so it's refused with a word instead; once per source. */
    private fun canSeek(): Boolean {
        if (player.isCurrentMediaItemSeekable) return true
        if (!toldUnseekable) {
            toldUnseekable = true
            android.widget.Toast.makeText(this, getString(R.string.player_cannot_seek), android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }
    private var toldUnseekable = false

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    private fun changeStream() {
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", player.currentPosition)
        })
        finish()
    }

    // ---------- playback options, after design/player-options-cinematic-minimal-glass.png ----------
    // A panel down the left over a darkening: the audio tracks, the subtitles (Off first), and Change stream with the
    // source playing, then the Sleep row. The chosen track has a tick; OK on another switches to it there and then. Back closes it.
    private lateinit var options: FrameLayout
    private lateinit var optionsBar: SeekBar
    private lateinit var optionsElapsed: TextView
    private lateinit var optionsLeft: TextView

    private fun openOptions() {
        hideControls(force = true)
        controls.visibility = View.GONE
        options.removeAllViews()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            clipToPadding = true
            clipChildren = true
            background = videoGlass(14)
        }
        val sheetWidth = dp(460)
        options.addView(sheet, FrameLayout.LayoutParams(sheetWidth, resources.displayMetrics.heightPixels - dp(54)).apply {
            leftMargin = dp(27); topMargin = dp(27)
        })

        sheet.addView(text(R.string.player_audio_subtitles, SERIF_TITLE, serif = true), wrap())
        sheet.addView(text(listOfNotNull(pauseTitle.text.toString().takeIf { it.isNotBlank() },
            subtitle.text.toString().takeIf { it.isNotBlank() }).joinToString(DOT),
            SMALL, color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, wrap().apply { topMargin = dp(4) })

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = glass(18)
        }
        sheet.addView(tabsRow, LinearLayout.LayoutParams(-1, dp(36)).apply {
            topMargin = dp(14); bottomMargin = dp(12)
        })

        val tabPills = mutableListOf<TextView>()
        val tabNames = listOf(R.string.tab_subtitles, R.string.tab_audio, R.string.tab_sync_style).map(::getString)
        var currentTab = 0

        val panelsContainer = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            setPadding(0, 0, 0, dp(48))
        }
        val contentScroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
                if (childCount == 0) return 0
                val screenTop = scrollY
                val screenBottom = screenTop + height
                val margin = dp(44)
                return when {
                    rect.top < screenTop + margin -> rect.top - (screenTop + margin)
                    rect.bottom > screenBottom - margin -> rect.bottom - (screenBottom - margin)
                    else -> 0
                }
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = true
            clipChildren = true
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(panelsContainer, FrameLayout.LayoutParams(-1, -2))
        }
        sheet.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val subtitlesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val audioPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val syncPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }

        fun styleTabPill(index: Int) {
            val pill = tabPills[index]
            val isSelected = index == currentTab
            val isFocused = pill.isFocused
            pill.background = when {
                isFocused -> GradientDrawable().apply {
                    setColor(ROW_FOCUSED)
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), TUNGSTEN_RIM)
                }
                isSelected -> GradientDrawable().apply {
                    setColor(0x3DF3D9B1.toInt())
                    cornerRadius = dp(13).toFloat()
                }
                else -> GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(13).toFloat()
                }
            }
            pill.setTextColor(when {
                isFocused -> TEXT
                isSelected -> TUNGSTEN
                else -> TEXT_2
            })
        }

        fun switchTab(newTab: Int) {
            if (currentTab == newTab) return
            currentTab = newTab
            tabPills.indices.forEach { styleTabPill(it) }
            subtitlesPanel.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
            audioPanel.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
            syncPanel.visibility = if (currentTab == 2) View.VISIBLE else View.GONE
            contentScroll.scrollTo(0, 0)
        }

        tabNames.forEachIndexed { i, title ->
            val pill = text(title, SMALL, bold = true).apply {
                id = View.generateViewId()
                isFocusable = true
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        switchTab(i)
                        contentScroll.scrollTo(0, 0)
                    }
                    styleTabPill(i)
                }
                setOnClickListener {
                    switchTab(i)
                }
            }
            tabPills.add(pill)
            tabsRow.addView(pill, LinearLayout.LayoutParams(0, -1, 1f))
        }

        tabPills[0].nextFocusRightId = tabPills[1].id
        tabPills[0].nextFocusLeftId = tabPills[0].id
        tabPills[1].nextFocusLeftId = tabPills[0].id
        tabPills[1].nextFocusRightId = tabPills[2].id
        tabPills[2].nextFocusLeftId = tabPills[1].id
        tabPills[2].nextFocusRightId = tabPills[2].id

        tabPills.forEachIndexed { index, pill ->
            pill.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val activePanel = when (index) {
                        0 -> subtitlesPanel
                        1 -> audioPanel
                        else -> syncPanel
                    }
                    val target = findFirstFocusable(activePanel)
                    if (target != null) {
                        target.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        var syncTimingHeader: TextView? = null
        var quickTimingValue: TextView? = null
        val timingResetPills = mutableListOf<TextView>()

        fun updateAllTimingHeaders() {
            val label = timing(subDelay)
            syncTimingHeader?.text = getString(R.string.subtitle_timing_heading, label).uppercase()
            quickTimingValue?.text = label
            timingResetPills.forEach { it.setTextColor(if (subDelay != 0L) TUNGSTEN else TEXT_3) }
        }

        fun adjustTiming(delta: Long) {
            setSubtitleDelay((subDelay + delta).coerceIn(-10_000L, 10_000L))
            updateAllTimingHeaders()
        }

        fun resetTiming() {
            setSubtitleDelay(0L)
            updateAllTimingHeaders()
        }

        var currentBg = Settings.subtitleBackground(this)
        fun applySubtitleStyle() {
            view.subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(this@PlayerActivity),
                if (currentBg) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
        }

        val colors = listOf(
            "white" to getString(R.string.color_white),
            "yellow" to getString(R.string.color_yellow),
            "cyan" to getString(R.string.color_cyan),
            "cream" to getString(R.string.color_cream),
            "warm_white" to getString(R.string.color_warm_white)
        )
        var currentSubColor = Settings.subtitleColorName(this)
        val colorPillUpdaters = mutableMapOf<String, () -> Unit>()

        fun selectColor(colorValue: String) {
            currentSubColor = colorValue
            Settings.setSubtitleColor(this@PlayerActivity, colorValue)
            applySubtitleStyle()
            colorPillUpdaters.values.forEach { it() }
        }

        fun createColorRow(slot: String): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pills = mutableListOf<TextView>()
            colors.forEachIndexed { idx, (colorValue, colorLabel) ->
                val isCur = currentSubColor == colorValue
                val p = text(colorLabel, SMALL, bold = true, color = if (isCur) TUNGSTEN else TEXT_2).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    background = if (isCur) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                    rowFocus(8, rest = { if (currentSubColor == colorValue) rounded(0x3DF3D9B1.toInt(), 8) else glass(8) }) { has ->
                        setTextColor(if (has || currentSubColor == colorValue) TEXT else TEXT_2)
                    }
                    setOnClickListener { selectColor(colorValue) }
                }
                pills.add(p)
                row.addView(p, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (idx < colors.lastIndex) marginEnd = dp(5)
                })
            }
            colorPillUpdaters[slot] = {
                pills.forEachIndexed { i, pill ->
                    val active = colors[i].first == currentSubColor
                    pill.background = if (active) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                    pill.setTextColor(if (active) TUNGSTEN else TEXT_2)
                }
            }
            return row
        }

        fun timingPill(label: String, isReset: Boolean = false, onClick: () -> Unit) = text(label, SMALL, bold = true,
            color = if (isReset && subDelay == 0L) TEXT_3 else TEXT_2).apply {
            if (isReset) timingResetPills.add(this)
            gravity = Gravity.CENTER
            maxLines = 1
            background = glass(8)
            rowFocus(8, rest = { glass(8) }) { has ->
                setTextColor(when {
                    has -> TEXT
                    isReset && subDelay != 0L -> TUNGSTEN
                    isReset -> TEXT_3
                    else -> TEXT_2
                })
            }
            setOnClickListener { onClick() }
        }

        fun trackRowRest(selected: Boolean) = if (selected) GradientDrawable().apply {
            setColor(0x24F3D9B1.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x4DF3D9B1.toInt())
        } else null

        class TrackRowHolder(
            val track: TrackChoice,
            val row: LinearLayout,
            val check: View,
            val name: TextView,
            val tagText: TextView,
            val badge: TextView
        )

        fun createTrackRow(
            track: TrackChoice,
            isSelected: Boolean,
            isPreferred: Boolean,
            isRecent: Boolean,
            onUpToTabs: (() -> Unit)? = null,
            onClick: () -> Unit
        ): Pair<LinearLayout, TrackRowHolder> {
            val check = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TUNGSTEN) }
                visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            }
            val name = text(track.name, BODY, color = if (isSelected) TEXT else TEXT_2).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val tagText = text(track.tag, SMALL, color = TEXT_3).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                visibility = if (track.tag.isNotEmpty()) View.VISIBLE else View.GONE
            }
            val badgeText = when {
                isSelected && !track.isOff -> getString(R.string.track_active)
                isPreferred -> getString(R.string.track_preferred)
                isRecent -> getString(R.string.track_recent)
                else -> ""
            }
            val badge = text(badgeText, 10f, bold = true, color = if (isSelected) TUNGSTEN else TEXT_2).apply {
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = rounded(if (isSelected) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                visibility = if (badgeText.isNotEmpty()) View.VISIBLE else View.GONE
            }

            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(10), 0, dp(10), 0)
                background = trackRowRest(isSelected)

                addView(check, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(9) })
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                addView(badge, wrap().apply { marginStart = dp(6) })
                addView(tagText, wrap().apply { marginStart = dp(6) })

                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) GradientDrawable().apply {
                        setColor(ROW_FOCUSED)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), TUNGSTEN_RIM)
                    } else trackRowRest(isSelected)
                    name.setTextColor(if (hasFocus || isSelected) TEXT else TEXT_2)
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && onUpToTabs != null) {
                            onUpToTabs()
                            return@setOnKeyListener true
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            return@setOnKeyListener true
                        }
                    }
                    false
                }

                setOnClickListener { onClick() }
            }

            return row to TrackRowHolder(track, row, check, name, tagText, badge)
        }

        // Subtitles tab setup
        val subHolders = mutableListOf<TrackRowHolder>()
        var quickTimingCard: LinearLayout? = null
        var quickTimingDivider: View? = null

        fun populateSubtitles() {
            val focusedTrackId = subHolders.firstOrNull { it.row.isFocused }?.track?.id
            val wasTimingFocused = quickTimingCard?.hasFocus() == true
            subtitlesPanel.removeAllViews()
            subHolders.clear()

            val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
            val subs = texts.filterNot(::isAddonSub)
            val loaded = texts.firstOrNull(::isAddonSub)
            var chosenSubIndex = when {
                C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> 0
                loaded?.isSelected == true -> 1 + subs.size + addonSubs.indexOf(chosenSub)
                else -> subs.indexOfFirst { it.isSelected } + 1
            }

            val subChoices = mutableListOf<TrackChoice>()
            subChoices.add(TrackChoice("sub:0", getString(R.string.state_off), isSelected = chosenSubIndex == 0, isOff = true))
            subs.forEachIndexed { i, group ->
                val format = group.getTrackFormat(0)
                subChoices.add(TrackChoice("sub:${1 + i}", trackName(format, false), getString(R.string.track_in_file),
                    Languages.code(format.language), isSelected = chosenSubIndex == 1 + i))
            }
            addonSubs.forEachIndexed { i, sub ->
                subChoices.add(TrackChoice("sub:${1 + subs.size + i}", sub.language, sub.addon,
                    Languages.code(sub.lang), isSelected = chosenSubIndex == 1 + subs.size + i))
            }

            val (pinnedSubs, moreSubs) = partitionPinnedTracks(subChoices, Settings.subtitleLanguage(this), Settings.recentSubtitles(this))
            val initialChosenSub = subChoices.getOrNull(chosenSubIndex)
            var currentSelectedSubId = initialChosenSub?.id ?: "sub:0"

            val subActiveHeader = caps(getString(R.string.subtitles_heading,
                initialChosenSub?.takeIf { currentSelectedSubId != "sub:0" }?.name ?: getString(R.string.state_off)), TUNGSTEN)
            subtitlesPanel.addView(subActiveHeader, wrap().apply { topMargin = dp(4); bottomMargin = dp(6) })

            val timingCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = glass(8)
                visibility = if (currentSelectedSubId == "sub:0") View.GONE else View.VISIBLE
            }
            quickTimingCard = timingCard
            timingCard.addView(caps(R.string.quick_timing, TEXT_3), wrap().apply { bottomMargin = dp(6) })
            val timingCardButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val minus = timingPill("− 0.25s") { adjustTiming(-250L) }
                val reset = timingPill(timing(subDelay), isReset = true) { resetTiming() }.also { quickTimingValue = it }
                val plus = timingPill("+ 0.25s") { adjustTiming(+250L) }
                val onUpToLastTrack = View.OnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        subHolders.lastOrNull()?.row?.requestFocus()
                        return@OnKeyListener true
                    }
                    false
                }
                minus.setOnKeyListener(onUpToLastTrack)
                reset.setOnKeyListener(onUpToLastTrack)
                plus.setOnKeyListener(onUpToLastTrack)
                addView(minus, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(6) })
                addView(reset, LinearLayout.LayoutParams(0, dp(30), 1.2f).apply { marginEnd = dp(6) })
                addView(plus, LinearLayout.LayoutParams(0, dp(30), 1f))
            }
            timingCard.addView(timingCardButtons, LinearLayout.LayoutParams(-1, dp(30)))
            timingCard.addView(caps(R.string.subtitle_color, TEXT_3), wrap().apply { topMargin = dp(8); bottomMargin = dp(4) })
            timingCard.addView(createColorRow("subtitles"), LinearLayout.LayoutParams(-1, dp(30)))

            val timingDivider = divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }
                visibility = if (currentSelectedSubId == "sub:0") View.GONE else View.VISIBLE
            }
            quickTimingDivider = timingDivider

            fun updateSubSelection(selectedId: String) {
                val selChoice = subChoices.firstOrNull { it.id == selectedId }
                subActiveHeader.text = getString(R.string.subtitles_heading,
                    selChoice?.takeIf { !it.isOff }?.name ?: getString(R.string.state_off)).uppercase()
                val timingVisible = selChoice?.isOff == false
                timingCard.visibility = if (timingVisible) View.VISIBLE else View.GONE
                timingDivider.visibility = if (timingVisible) View.VISIBLE else View.GONE

                subHolders.forEach { holder ->
                    val track = holder.track
                    val isSel = track.id == selectedId
                    holder.check.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                    holder.name.setTextColor(if (isSel || holder.row.isFocused) TEXT else TEXT_2)

                    val isPref = !track.isOff && Languages.match(track.langCode, Settings.subtitleLanguage(this@PlayerActivity)) > 0
                    val isRec = !track.isOff && Settings.recentSubtitles(this@PlayerActivity).any { Languages.match(track.langCode, it) > 0 }

                    val badgeLabel = when {
                        isSel && !track.isOff -> getString(R.string.track_active)
                        isPref -> getString(R.string.track_preferred)
                        isRec -> getString(R.string.track_recent)
                        else -> ""
                    }
                    holder.badge.text = badgeLabel
                    holder.badge.setTextColor(if (isSel) TUNGSTEN else TEXT_2)
                    holder.badge.background = rounded(if (isSel) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                    holder.badge.visibility = if (badgeLabel.isNotEmpty()) View.VISIBLE else View.GONE

                    if (!holder.row.isFocused) {
                        holder.row.background = trackRowRest(isSel)
                    }
                }
            }

            fun selectSubtitleTrack(track: TrackChoice) {
                val i = subChoices.indexOfFirst { it.id == track.id }
                if (i < 0) return
                val p = player.trackSelectionParameters.buildUpon()
                val sub = addonSubs.getOrNull(i - 1 - subs.size)
                val lang = if (i == 0) "" else sub?.lang ?: subs[i - 1].getTrackFormat(0).language
                remember(lang, addonFile = sub != null,
                    pick = when { i == 0 -> ""; sub != null -> "addon:" + sub.url; else -> "file:" + subs[i - 1].getTrackFormat(0).id.orEmpty() })
                if (i != 0) {
                    track.langCode?.let { Settings.addRecentSubtitle(this@PlayerActivity, it) }
                }
                player.trackSelectionParameters = when {
                    i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    sub == null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(subs[i - 1].mediaTrackGroup, 0)).build()
                    sub == chosenSub && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
                    else -> { chosenSub = sub; subDelay = 0L; textRenderer?.offsetUs = 0L; selectAddonSub = true; load(position()); return }
                }
                chosenSubIndex = i
                currentSelectedSubId = track.id
                updateSubSelection(track.id)
            }

            if (pinnedSubs.isNotEmpty()) {
                subtitlesPanel.addView(caps(R.string.tracks_pinned, TEXT_3), wrap().apply {
                    topMargin = dp(6); bottomMargin = dp(4)
                })

                pinnedSubs.forEachIndexed { idx, track ->
                    val (row, holder) = createTrackRow(
                        track = track,
                        isSelected = track.id == currentSelectedSubId,
                        isPreferred = !track.isOff && Languages.match(track.langCode, Settings.subtitleLanguage(this)) > 0,
                        isRecent = !track.isOff && Settings.recentSubtitles(this).any { Languages.match(track.langCode, it) > 0 },
                        onUpToTabs = if (idx == 0) { { tabPills[0].requestFocus() } } else null,
                        onClick = { selectSubtitleTrack(track) }
                    )
                    subHolders.add(holder)
                    subtitlesPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
                }
            }

            if (moreSubs.isNotEmpty()) {
                subtitlesPanel.addView(divider().apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(10), 0, dp(8)) }
                })
                subtitlesPanel.addView(caps(getString(R.string.all_languages, moreSubs.size), TEXT_3), wrap().apply {
                    bottomMargin = dp(4)
                })
                moreSubs.forEachIndexed { idx, track ->
                    val (row, holder) = createTrackRow(
                        track = track,
                        isSelected = track.id == currentSelectedSubId,
                        isPreferred = Languages.match(track.langCode, Settings.subtitleLanguage(this)) > 0,
                        isRecent = Settings.recentSubtitles(this).any { Languages.match(track.langCode, it) > 0 },
                        onUpToTabs = if (pinnedSubs.isEmpty() && idx == 0) { { tabPills[0].requestFocus() } } else null,
                        onClick = { selectSubtitleTrack(track) }
                    )
                    subHolders.add(holder)
                    subtitlesPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
                }
            }

            subtitlesPanel.addView(timingDivider)
            subtitlesPanel.addView(timingCard, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(4)
            })

            if (focusedTrackId != null) {
                subHolders.firstOrNull { it.track.id == focusedTrackId }?.row?.requestFocus()
            } else if (wasTimingFocused) {
                timingCardButtons.getChildAt(1)?.requestFocus()
            }
        }

        populateSubtitles()
        refreshOptionsSubtitles = {
            if (options.visibility == View.VISIBLE && currentTab == 0) {
                populateSubtitles()
            }
        }

        // Audio tab setup
        val audio = audioGroups.filter { it.isTrackSupported(0) }
        var chosenAudioIndex = audio.indexOfFirst { it.isSelected }

        val audioChoices = audio.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            TrackChoice(
                id = "audio:$i",
                name = trackName(format, true),
                langCode = Languages.code(format.language),
                isSelected = i == chosenAudioIndex
            )
        }
        val (pinnedAudio, moreAudio) = partitionPinnedTracks(audioChoices, Settings.audioLanguage(this), Settings.recentAudio(this))
        val initialChosenAudio = audioChoices.getOrNull(chosenAudioIndex)
        var currentSelectedAudioId = initialChosenAudio?.id.orEmpty()

        val audioActiveHeader = caps(if (initialChosenAudio != null) getString(R.string.audio_heading, initialChosenAudio.name)
            else getString(R.string.tab_audio), TUNGSTEN)
        audioPanel.addView(audioActiveHeader, wrap().apply { topMargin = dp(4); bottomMargin = dp(6) })

        val audioHolders = mutableListOf<TrackRowHolder>()

        fun updateAudioSelection(selectedId: String) {
            val selChoice = audioChoices.firstOrNull { it.id == selectedId }
            audioActiveHeader.text = (if (selChoice != null) getString(R.string.audio_heading, selChoice.name)
                else getString(R.string.tab_audio)).uppercase()

            audioHolders.forEach { holder ->
                val track = holder.track
                val isSel = track.id == selectedId
                holder.check.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                holder.name.setTextColor(if (isSel || holder.row.isFocused) TEXT else TEXT_2)

                val isPref = Languages.match(track.langCode, Settings.audioLanguage(this@PlayerActivity)) > 0
                val isRec = Settings.recentAudio(this@PlayerActivity).any { Languages.match(track.langCode, it) > 0 }

                val badgeLabel = when {
                    isSel -> getString(R.string.track_active)
                    isPref -> getString(R.string.track_preferred)
                    isRec -> getString(R.string.track_recent)
                    else -> ""
                }
                holder.badge.text = badgeLabel
                holder.badge.setTextColor(if (isSel) TUNGSTEN else TEXT_2)
                holder.badge.background = rounded(if (isSel) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                holder.badge.visibility = if (badgeLabel.isNotEmpty()) View.VISIBLE else View.GONE

                if (!holder.row.isFocused) {
                    holder.row.background = trackRowRest(isSel)
                }
            }
        }

        fun selectAudioTrack(track: TrackChoice) {
            val i = audioChoices.indexOfFirst { it.id == track.id }
            if (i >= 0) {
                audioPicked = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(audio[i].mediaTrackGroup, (0 until audio[i].length).toList())).build()
                track.langCode?.let {
                    wantAudio = it
                    Settings.pickedInPlayer(this@PlayerActivity, metaId, audio = it)
                    Settings.addRecentAudio(this@PlayerActivity, it)
                }
                chosenAudioIndex = i
                currentSelectedAudioId = track.id
                updateAudioSelection(track.id)
            }
        }

        audioPanel.addView(caps(R.string.tracks_pinned, TEXT_3), wrap().apply {
            topMargin = dp(6); bottomMargin = dp(4)
        })

        pinnedAudio.forEachIndexed { idx, track ->
            val (row, holder) = createTrackRow(
                track = track,
                isSelected = track.id == currentSelectedAudioId,
                isPreferred = Languages.match(track.langCode, Settings.audioLanguage(this)) > 0,
                isRecent = Settings.recentAudio(this).any { Languages.match(track.langCode, it) > 0 },
                onUpToTabs = if (idx == 0) { { tabPills[1].requestFocus() } } else null,
                onClick = { selectAudioTrack(track) }
            )
            audioHolders.add(holder)
            audioPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
        }

        if (moreAudio.isNotEmpty()) {
            audioPanel.addView(divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(10), 0, dp(8)) }
            })
            audioPanel.addView(caps(getString(R.string.all_audio_tracks, moreAudio.size), TEXT_3), wrap().apply {
                bottomMargin = dp(4)
            })
            moreAudio.forEach { track ->
                val (row, holder) = createTrackRow(
                    track = track,
                    isSelected = track.id == currentSelectedAudioId,
                    isPreferred = Languages.match(track.langCode, Settings.audioLanguage(this)) > 0,
                    isRecent = Settings.recentAudio(this).any { Languages.match(track.langCode, it) > 0 },
                    onClick = { selectAudioTrack(track) }
                )
                audioHolders.add(holder)
                audioPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
            }
        }

        if (!passthrough) {
            audioPanel.addView(divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }
            })
            audioPanel.addView(caps(R.string.audio_enhancement, TEXT_3), wrap().apply { bottomMargin = dp(4) })
            var clearOn = dialogueProcessor.enabled
            val clearStatus = text(if (clearOn) R.string.state_on else R.string.state_off, 10f, bold = true, color = if (clearOn) TUNGSTEN else TEXT_3).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = rounded(if (clearOn) 0x3DF3D9B1.toInt() else 0x1AFFFFFF.toInt(), 4)
            }
            val clearRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(10), 0, dp(10), 0)
                val label = text(R.string.clear_dialogue, BODY, color = TEXT_2).apply { maxLines = 1 }
                val sub = text(R.string.clear_dialogue_hint, SMALL, color = TEXT_3).apply { maxLines = 1 }
                val left = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label)
                    addView(sub)
                }
                addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                addView(clearStatus, wrap())
                rowFocus(8)
                setOnClickListener {
                    clearOn = !dialogueProcessor.enabled
                    dialogueProcessor.enabled = clearOn
                    Settings.setClearDialogue(this@PlayerActivity, clearOn)
                    clearStatus.text = getString(if (clearOn) R.string.state_on else R.string.state_off)
                    clearStatus.setTextColor(if (clearOn) TUNGSTEN else TEXT_3)
                    clearStatus.background = rounded(if (clearOn) 0x3DF3D9B1.toInt() else 0x1AFFFFFF.toInt(), 4)
                }
            }
            audioPanel.addView(clearRow, LinearLayout.LayoutParams(-1, dp(42)))
        }

        // Sync & Style tab setup
        fun timingHeading(delay: Long) = getString(R.string.subtitle_timing_heading, timing(delay))
        val timingHeader = caps(timingHeading(subDelay), TUNGSTEN)
        syncTimingHeader = timingHeader
        syncPanel.addView(timingHeader, wrap().apply { topMargin = dp(4); bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.timing_earlier_heading, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val earlierRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pills = listOf(
                timingPill("− 0.5s") { adjustTiming(-500L) },
                timingPill("− 0.25s") { adjustTiming(-250L) },
                timingPill("− 0.1s") { adjustTiming(-100L) }
            )
            pills.forEachIndexed { idx, pill ->
                pill.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        tabPills[2].requestFocus()
                        return@setOnKeyListener true
                    }
                    false
                }
                addView(pill, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < pills.lastIndex) marginEnd = dp(6)
                })
            }
        }
        syncPanel.addView(earlierRow, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.timing_later_heading, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val laterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pills = listOf(
                timingPill("+ 0.1s") { adjustTiming(+100L) },
                timingPill("+ 0.25s") { adjustTiming(+250L) },
                timingPill("+ 0.5s") { adjustTiming(+500L) }
            )
            pills.forEachIndexed { idx, pill ->
                addView(pill, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < pills.lastIndex) marginEnd = dp(6)
                })
            }
        }
        syncPanel.addView(laterRow, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(8) })

        val resetPill = timingPill(getString(R.string.timing_reset), isReset = true) { resetTiming() }
        syncPanel.addView(resetPill, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(14) })

        syncPanel.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(10)) }
        })
        syncPanel.addView(caps(R.string.subtitle_appearance, TUNGSTEN), wrap().apply { bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.subtitle_size, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val sizes = listOf(80 to R.string.size_small, 100 to R.string.size_medium, 130 to R.string.size_large,
            160 to R.string.size_extra_large).map { (size, words) -> size to getString(words) }
        var currentSubSize = Settings.subtitleSize(this)
        val sizePills = mutableListOf<TextView>()
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizes.forEachIndexed { idx, (sizeValue, sizeLabel) ->
            val isCur = currentSubSize == sizeValue
            val p = text(sizeLabel, SMALL, bold = true, color = if (isCur) TUNGSTEN else TEXT_2).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = if (isCur) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                rowFocus(8, rest = { if (currentSubSize == sizeValue) rounded(0x3DF3D9B1.toInt(), 8) else glass(8) }) { has ->
                    setTextColor(if (has || currentSubSize == sizeValue) TEXT else TEXT_2)
                }
                setOnClickListener {
                    currentSubSize = sizeValue
                    Settings.setSubtitleSize(this@PlayerActivity, sizeValue)
                    view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizeValue / 100f)
                    sizePills.forEachIndexed { i, pill ->
                        val active = sizes[i].first == currentSubSize
                        pill.background = if (active) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                        pill.setTextColor(if (active) TUNGSTEN else TEXT_2)
                    }
                }
            }
            sizePills.add(p)
            sizeRow.addView(p, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                if (idx < sizes.lastIndex) marginEnd = dp(5)
            })
        }
        syncPanel.addView(sizeRow, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(10) })

        syncPanel.addView(caps(R.string.color, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        syncPanel.addView(createColorRow("sync"), LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(10) })

        syncPanel.addView(caps(R.string.background_box, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val bgOptions = listOf(true to getString(R.string.state_on), false to getString(R.string.state_off))
        val bgPills = mutableListOf<TextView>()
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bgOptions.forEachIndexed { idx, (bgValue, bgLabel) ->
            val isCur = currentBg == bgValue
            val p = text(bgLabel, SMALL, bold = true, color = if (isCur) TUNGSTEN else TEXT_2).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = if (isCur) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                rowFocus(8, rest = { if (currentBg == bgValue) rounded(0x3DF3D9B1.toInt(), 8) else glass(8) }) { has ->
                    setTextColor(if (has || currentBg == bgValue) TEXT else TEXT_2)
                }
                setOnClickListener {
                    currentBg = bgValue
                    Settings.setSubtitleBackground(this@PlayerActivity, bgValue)
                    applySubtitleStyle()
                    bgPills.forEachIndexed { i, pill ->
                        val active = bgOptions[i].first == currentBg
                        pill.background = if (active) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                        pill.setTextColor(if (active) TUNGSTEN else TEXT_2)
                    }
                }
            }
            bgPills.add(p)
            bgRow.addView(p, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                if (idx < bgOptions.lastIndex) marginEnd = dp(6)
            })
        }
        syncPanel.addView(bgRow, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(14) })

        syncPanel.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(10)) }
        })
        syncPanel.addView(caps(R.string.playback_sleep, TUNGSTEN), wrap().apply { bottomMargin = dp(6) })

        val sleepValue = text(sleep(), SMALL, color = TEXT_3)
        val sleepRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(text(R.string.sleep_timer, BODY, color = TEXT_2), wrap())
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sleepValue, wrap())
            rowFocus(8)
            setOnClickListener {
                cycleSleep()
                sleepValue.text = sleep()
            }
        }
        syncPanel.addView(sleepRow, LinearLayout.LayoutParams(-1, dp(36)))

        val changeStreamRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val head = text(R.string.player_change_stream_arrow, BODY, bold = true).apply { setPadding(dp(10), dp(5), dp(10), dp(3)) }
            addView(head, wrap())
            val label = sources[at].label.split(DOT.trim()).map { it.trim() }.filter { it.isNotEmpty() }
            addView(text((label.drop(1) + label.take(1)).joinToString(DOT), SMALL, color = TEXT_3).apply {
                setPadding(dp(10), 0, dp(10), 0)
            }, wrap())
            setPadding(0, 0, 0, dp(4))
            rowFocus(8)
            setOnClickListener { changeStream() }
        }
        syncPanel.addView(changeStreamRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        audioPanel.visibility = View.GONE
        syncPanel.visibility = View.GONE
        panelsContainer.addView(subtitlesPanel, FrameLayout.LayoutParams(-1, -2))
        panelsContainer.addView(audioPanel, FrameLayout.LayoutParams(-1, -2))
        panelsContainer.addView(syncPanel, FrameLayout.LayoutParams(-1, -2))

        optionsBar = SeekBar(this).apply { isFocusable = false }
        optionsElapsed = text("", SMALL, color = TEXT_2).onArt()
        optionsLeft = text("", SMALL, color = TEXT_2).onArt()
        options.addView(optionsBar, FrameLayout.LayoutParams(dp(280), dp(12), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        options.addView(optionsElapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD + 246); bottomMargin = dp(27)
        })
        options.addView(optionsLeft, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(27)
        })
        options.visibility = View.VISIBLE
        updateProgress()
        tabPills[0].requestFocus()
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view.isFocusable) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstFocusable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun closeOptions() {
        refreshOptionsSubtitles = null
        options.visibility = View.GONE
        showControls(audioButton)
    }

    /** What's playing, in a few words: "English  ·  Spanish subtitles", or "English  ·  Subtitles off". */
    private fun trackStatus(tracks: Tracks): String {
        fun name(language: String?) = Languages.code(language)?.let(Languages::name)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
        val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subtitles = when {
            text == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> getString(R.string.subtitles_off)
            else -> name(if (isAddonSub(text)) chosenSub?.lang else text.getTrackFormat(0).language)
                ?.let { getString(R.string.subtitles_in, it) } ?: getString(R.string.subtitles_on)
        }
        return listOfNotNull(name(audio), subtitles).joinToString(DOT)
    }

    /** "English · Dolby Digital 5.1": the language, then the release's own name for the track or, for audio, its format. */
    /** Sets subtitle delay in milliseconds live on the renderer without reloading playback. */
    private fun setSubtitleDelay(newDelay: Long) {
        val oldDelay = subDelay
        subDelay = newDelay
        textRenderer?.offsetUs = subDelay * 1000L
        if (subDelay > oldDelay) {
            player.seekTo(player.currentPosition)
        }
    }

    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    private fun trackName(f: Format, audio: Boolean): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.ifBlank { null }
        val format = if (!audio) null else listOfNotNull(when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }, when (f.channelCount) {
            1 -> getString(R.string.audio_mono); 2 -> getString(R.string.audio_stereo); 6 -> "5.1"; 8 -> "7.1"
            Format.NO_VALUE -> null; else -> getString(R.string.audio_channels, f.channelCount)
        })
            .joinToString(" ").ifEmpty { null }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        return listOfNotNull(language, label ?: format).joinToString("  ·  ").ifEmpty { getString(R.string.track_number, f.id.orEmpty()).trim() }
    }

    private fun timing(delay: Long) = formatTiming(delay, getString(R.string.timing_on_time),
        getString(R.string.timing_earlier), getString(R.string.timing_later))

    private fun sleep() = sleepLabel(stopAfter, sleepAt, System.currentTimeMillis(), getString(R.string.sleep_after_episode),
        getString(R.string.sleep_in_minutes), getString(R.string.state_off))

    override fun onStop() {
        super.onStop()
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(loadingTask)
        player.pause()
        if (isTrailer || !watched || player.isCurrentMediaItemLive || isLiveType(type) || type == "series" && !plausibleEpisode(player.duration)) return
        record()
        // The player's own episode list first: Home's Resume passes no next episode when the title's details weren't in
        // yet, and a finished episode with no next one drops the title out of Continue Watching.
        item?.let { Library.stopped(it, nextVideo(onlyAired = false)?.id ?: intent.getStringExtra("nextVideoId")) }
        push()
    }

    override fun onDestroy() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(loadingTask)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICK = 10_000L
        const val RESUME_SLACK = 10_000L // how far apart the saved and fresh resume points may be before it jumps
        const val SKIP = 10_000L // ms a Left/Right press on the bar moves
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val SUB_WAIT = 2_000L // ms the start waits for the addon's subtitles
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
    }
}

/** Tungsten playback progress over a dark track, with a warm-white knob on focus. */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var markers = emptyList<Marker>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    var isIndeterminate = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else stopPulse()
            invalidate()
        }
    private var pulseFraction = 0.5f
    private var pulseAnimator: android.animation.ValueAnimator? = null

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0.15f, 0.85f).apply {
            duration = 1100
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 0.5f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    fun setMarkers(markers: List<Marker>) {
        this.markers = markers
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val knob = (if (isFocused) 9 else 6) * d
        val line = (if (isFocused) 4 else 3) * d
        val y = height / 2f
        fun x(t: Long) = knob + (width - 2 * knob) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(knob, y - line / 2, to, y + line / 2, line / 2, line / 2, paint)
        }
        if (isIndeterminate) {
            stretch(width - knob, 0x33F3EEE6)
            val trackW = width - 2 * knob
            val centerX = knob + trackW * pulseFraction
            val halfSpan = trackW * 0.22f
            paint.color = Screen.TUNGSTEN
            paint.setShadowLayer(6 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
            canvas.drawRoundRect((centerX - halfSpan).coerceAtLeast(knob), y - line / 2,
                (centerX + halfSpan).coerceAtMost(width - knob), y + line / 2, line / 2, line / 2, paint)
            paint.clearShadowLayer()
            return
        }
        // A light track: the dark one vanished over a dark picture.
        stretch(width - knob, 0x40F3EEE6)

        // Tint spans for Intro / Recap if present
        if (duration > 0) {
            paint.color = 0x33F3D9B1.toInt()
            for (m in markers) {
                if (m.endMs != null && m.endMs > m.timeMs) {
                    val x1 = x(m.timeMs)
                    val x2 = x(m.endMs)
                    if (x2 > x1) canvas.drawRect(x1, y - line / 2, x2, y + line / 2, paint)
                }
            }
        }

        stretch(x(buffered), 0x73F3EEE6)
        paint.setShadowLayer(4 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
        stretch(x(position), Screen.TUNGSTEN)
        paint.clearShadowLayer()

        // Marker ticks along the bar
        if (duration > 0 && markers.size > 1) {
            val gapHalf = 1.5f * d
            val tickHalf = 0.8f * d
            val tickExtra = if (isFocused) 2.5f * d else 1f * d
            for (m in markers) {
                if (m.timeMs > 0 && m.timeMs < duration) {
                    val mx = x(m.timeMs)
                    paint.color = 0xFF0E0C0A.toInt()
                    canvas.drawRect(mx - gapHalf, y - line / 2 - 1f, mx + gapHalf, y + line / 2 + 1f, paint)
                    paint.color = if (m.type != MarkerType.CHAPTER) Screen.TUNGSTEN else 0xCCF3EEE6.toInt()
                    canvas.drawRect(mx - tickHalf, y - line / 2 - tickExtra, mx + tickHalf, y + line / 2 + tickExtra, paint)
                }
            }
        }

        paint.color = Screen.TEXT
        if (duration > 0) canvas.drawCircle(x(position), y, knob, paint)
    }
}
