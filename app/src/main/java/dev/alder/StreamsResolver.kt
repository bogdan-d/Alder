package dev.alder

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

interface Cancellable {
    fun cancel()
}

object StreamsResolver {
    const val TRY = 3

    fun rank(
        context: Context,
        streams: List<Stream>,
        videoId: String,
        metaId: String?,
        maxHeight: Int = 0
    ): List<Stream> {
        val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
        val file = prefs.getString("file:$videoId", null)
        val binge = metaId?.let { prefs.getString("binge:$it", null) }
        val tv = Device.of(context)
        val prefer = preference(context)
        val lower = maxHeight > 0
        return streams.sortedWith(compareByDescending<Stream> { if (lower) it.height else 0 }
            .thenByDescending { file != null && it.filename == file }
            .thenByDescending { binge != null && it.bingeGroup == binge }
            .thenBy { it.awkward }
            .thenBy { it.dolbyVisionOnly && !tv.dolbyVision }
            .thenByDescending { it.fit(tv) }
            .thenByDescending(prefer))
    }

    fun preference(context: Context): (Stream) -> Int {
        val release = Settings.release(context)
        val hdr = Settings.hdrPref(context)
        val dub = Settings.dub(context)
        val language = Settings.audioLanguage(context)
        val receiver = Settings.passthrough(context)
        return { s ->
            when (release) {
                "remux" -> if (s.remux) 3 else if (s.bluray) 1 else 0
                "encode" -> if (s.remux) -2 else if (s.bluray || s.web) 2 else 0
                "web" -> if (s.web && !s.remux) 2 else if (s.remux) -2 else 0
                else -> 0
            } + when (hdr) {
                "dv" -> if (s.dolbyVision) 2 else if (s.hdr) 1 else 0
                "hdr10" -> if (s.hdr) 2 else 0
                else -> 0
            } + when (dub) {
                "original" -> if (s.dubbed) -2 else 0
                "mine" -> if (s.mentions(language)) 2 else if (s.multiAudio) 1 else 0
                else -> 0
            } + if (receiver && s.bigAudio) 1 else 0
        }
    }

    fun wanted(context: Context, s: Stream, maxHeight: Int = 0): Boolean {
        val limitHeight = maxHeight.takeIf { it > 0 } ?: Settings.maxHeight(context)
        val maxBytes = Settings.maxSizeGb(context) * 1_073_741_824L
        return (limitHeight == 0 || s.height == 0 || s.height <= limitHeight) &&
            (maxBytes == 0L || s.size == 0L || s.size <= maxBytes) &&
            (Settings.hdr(context) || !s.isHdr) && !s.cam
    }

    fun resolve(
        context: Context,
        type: String,
        videoId: String,
        metaId: String,
        authKey: String?,
        maxHeight: Int = 0,
        executor: Executor = Screen.pool,
        mainHandler: Handler = Handler(Looper.getMainLooper()),
        onReady: (List<Stream>) -> Unit,
        onFallback: () -> Unit
    ): Cancellable {
        val key = authKey ?: run {
            mainHandler.post(onFallback)
            return object : Cancellable { override fun cancel() {} }
        }

        var canceled = false
        var started = false
        var waitingForEarlier = false
        val tv = Device.of(context)

        val cancellable = object : Cancellable {
            override fun cancel() {
                canceled = true
            }
        }

        val lenders = Profiles.streamLenders(context)

        executor.execute {
            if (canceled) return@execute
            val res = runCatching {
                val own = Stremio.addons(key).filter { it.providesStreams }
                val lent = lenders.flatMap { lender ->
                    runCatching {
                        (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable }
                    }.getOrDefault(emptyList())
                }
                val household = lent.filter { it.serves("stream", type, videoId) }.distinctBy { it.id }
                household + own.filter { it.serves("stream", type, videoId) }.filterNot { mine -> household.any { it.id == mine.id } }
            }

            val addons = res.getOrNull().orEmpty()
            if (addons.isEmpty()) {
                mainHandler.post { if (!canceled) onFallback() }
                return@execute
            }

            val answers = arrayOfNulls<List<Stream>>(addons.size)

            fun tryStart(allowGaps: Boolean = false) {
                if (canceled || started) return
                val ready = readyStreams(answers, allowGaps)
                val best = rank(context, ready.filter { it.isVideo && it.playableOn(tv) && wanted(context, it, maxHeight) }, videoId, metaId, maxHeight)
                if (best.isEmpty() && answers.any { it == null }) return
                if (best.isEmpty()) {
                    started = true
                    onFallback()
                    return
                }
                started = true
                onReady(best.take(TRY))
            }

            addons.forEachIndexed { i, addon ->
                executor.execute {
                    if (canceled) return@execute
                    val streamsResult = runCatching { addon.streams(type, videoId) }
                    mainHandler.post {
                        if (canceled) return@post
                        answers[i] = streamsResult.getOrNull()?.playable.orEmpty()
                        if (answers.all { it != null }) {
                            tryStart(allowGaps = true)
                        } else {
                            tryStart(allowGaps = false)
                            if (!started && !waitingForEarlier && readyStreams(answers, true).any { it.isVideo && it.playableOn(tv) && wanted(context, it, maxHeight) }) {
                                waitingForEarlier = true
                                mainHandler.postDelayed({ tryStart(allowGaps = true) }, 800)
                            }
                        }
                    }
                }
            }
        }

        return cancellable
    }
}
