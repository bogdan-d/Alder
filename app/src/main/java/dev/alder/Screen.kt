package dev.alder

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A title's art, sharp, fading out into the glass on its left (under the text) and towards the bottom, from
 * [bottomFrom] to [bottomTo] of its height. A mask on the picture itself, not a colour painted over it, so there's no
 * box where the fade meets the blurred scene behind.
 */
class FadedArt(context: Context, private val bottomFrom: Float = 0.45f, private val bottomTo: Float = 1f) : ImageView(context) {
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var left: Shader? = null
    private var bottom: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Under the text (the left 60%) only a hint of the picture stays, so bright art can't fight the words.
        left = LinearGradient(0f, 0f, w * 0.8f, 0f, intArrayOf(0x1F000000, 0x40000000, Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bottom = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, intArrayOf(Color.BLACK, 0x59000000, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        for (s in listOf(left, bottom)) {
            mask.shader = s
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        }
        canvas.restoreToCount(layer)
    }
}

/** Keeps text readable straight on art: a soft dark shadow instead of a panel over the picture. */
fun TextView.onArt() = apply { setShadowLayer(resources.displayMetrics.density * 8, 0f, resources.displayMetrics.density, 0xCC000000.toInt()) }

internal fun filmGlow(pixels: IntArray): Int {
    require(pixels.isNotEmpty())
    val red = pixels.sumOf { it ushr 16 and 255 } / pixels.size
    val green = pixels.sumOf { it ushr 8 and 255 } / pixels.size
    val blue = pixels.sumOf { it and 255 } / pixels.size
    val lightness = (maxOf(red, green, blue) + minOf(red, green, blue)) / 2f
    val scale = if (lightness > 178.5f) 178.5f / lightness else 1f
    return (0x8C shl 24) or ((red * scale).toInt() shl 16) or ((green * scale).toInt() shl 8) or (blue * scale).toInt()
}

/**
 * A [w] x [h] copy of [src]'s pixels, or null. Decoded art is often a hardware bitmap, which can't be read: scaling
 * keeps it hardware, so the small copy is moved to software before reading. Every pixel sample goes through here.
 */
internal fun samplePixels(src: Bitmap, w: Int, h: Int): IntArray? = runCatching {
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val soft = if (small.config == Bitmap.Config.HARDWARE) small.copy(Bitmap.Config.ARGB_8888, false) else small
    IntArray(w * h).also { soft.getPixels(it, 0, w, 0, 0, w, h) }
        .also { if (small !== src) small.recycle(); if (soft !== small) soft.recycle() }
}.getOrNull()

internal fun brightLeft(pixels: IntArray, width: Int): Boolean {
    require(width > 0 && pixels.isNotEmpty() && pixels.size % width == 0)
    val left = maxOf(1, width / 2)
    val luminance = pixels.indices.filter { it % width < left }.sumOf {
        val color = pixels[it]
        (color ushr 16 and 255) * 0.2126 + (color ushr 8 and 255) * 0.7152 + (color and 255) * 0.0722
    }
    return luminance / (left * (pixels.size / width) * 255) > 0.5
}

/** What every screen shares: the signed-in account, background work, and the few view helpers the plain UI uses. */
abstract class Screen : Activity() {
    protected val main = Handler(Looper.getMainLooper())
    /** The Stremio account of the profile in use. */
    protected val authKey: String? get() = Profiles.active(this)?.authKey

    /** Settings › Language › App language, for TVs without Android 13's per-app language page (it applies it itself). */
    override fun attachBaseContext(newBase: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) return super.attachBaseContext(newBase)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        val lang = Settings.appLanguage(newBase)
        @Suppress("DEPRECATION") val locale = if (lang.isEmpty()) config.locale else java.util.Locale.forLanguageTag(lang)
        java.util.Locale.setDefault(locale) // language names and dates follow it too
        if (lang.isEmpty()) return super.attachBaseContext(newBase)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Http.saved != null) return
        // Pictures load many at a time from a few hosts: keep enough connections open to reuse (Android keeps 5).
        System.setProperty("http.maxConnections", "16")
        // Disk cache for pictures (and anything else whose server allows caching), and last time's answers.
        android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 250L * 1024 * 1024)
        Http.saved = java.io.File(cacheDir, "answers")
        Http.userAgent = "Alder/${Updates.mine(this)} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Downloads [release] and hands it to Android's installer. Alder closes to be replaced and doesn't open again by
     * itself, so the viewer is told first. Without permission to install apps, Android's page for it opens, and the
     * install goes on when the viewer comes back with it allowed; a TV without that page gets the steps instead.
     */
    protected fun installUpdate(release: Updates.Release) {
        if (!Updates.canInstall(this)) {
            pendingInstall = release
            if (!Updates.askToInstall(this)) {
                pendingInstall = null
                choose(getString(R.string.updates_allow_title), listOf(getString(R.string.action_ok)),
                    getString(R.string.updates_allow_steps)) {}
            }
            return
        }
        android.widget.Toast.makeText(this, getString(R.string.updates_downloading, release.version),
            android.widget.Toast.LENGTH_LONG).show()
        background({ Updates.install(applicationContext, release) }) { r ->
            if (r.isFailure) choose(getString(R.string.updates_download_failed), listOf(getString(R.string.action_ok)),
                getString(R.string.updates_download_failed_desc)) {}
        }
    }

    // An update waiting on the TV's permission to install apps: it goes on when the viewer comes back with it allowed.
    private var pendingInstall: Updates.Release? = null

    override fun onResume() {
        super.onResume()
        val release = pendingInstall ?: return
        pendingInstall = null
        if (Updates.canInstall(this)) installUpdate(release)
    }

    /** Runs [work] off the main thread and hands its result back on it, unless the screen is gone. */
    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed) done(result) }
        }
    }

    // Read once for the whole app, not per screen: Inter alone is 870 KB, read twice, on the main thread.
    private val fonts get() = loadedFonts ?: Fonts(applicationContext.assets).also { loadedFonts = it }

    /** Shared House Lights typography. */
    protected fun text(value: String, sp: Float, bold: Boolean = false, color: Int = TEXT, serif: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            typeface = if (serif) fonts.serif else if (bold) fonts.semiBold else fonts.medium
            LEADING[sp]?.let { lead ->
                val want = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, lead, resources.displayMetrics)
                setLineSpacing(want - paint.getFontMetricsInt(null), 1f)
            }
        }

    protected fun text(words: Int, sp: Float, bold: Boolean = false, color: Int = TEXT, serif: Boolean = false) =
        text(getString(words), sp, bold, color, serif)

    protected fun caps(value: String, color: Int = TEXT_2) = text(value.uppercase(), CAPS, bold = true, color = color).apply {
        letterSpacing = 0.12f
    }

    protected fun caps(words: Int, color: Int = TEXT_2) = caps(getString(words), color)

    /** [count] in the plural form the viewer's language needs ("1 season", "5 seasons"). */
    protected fun plural(words: Int, count: Int, vararg more: Any): String =
        resources.getQuantityString(words, count, count, *more)

    protected fun column(gravity: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // focused pills and pictures grow past their own bounds
        this.gravity = gravity
        // Wrap-content by default: a vertical LinearLayout would otherwise stretch every child to full width.
        views.forEach { addView(it, it.layoutParams ?: wrap()) }
    }

    protected fun <V : View> V.marginTop(top: Int) = apply { layoutParams = wrap().apply { topMargin = dp(top) } }

    protected fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var windowShade: View? = null
    private var windowArt: ImageView? = null
    private var shadeSource: Bitmap? = null

    /** The left shade is for text over bare art; a screen that puts its text on glass can lift it off the art. */
    protected fun leftShade(on: Boolean) {
        windowShade?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(260)?.start()
    }

    private fun updateWindowShade() {
        val art = (windowArt?.drawable as? BitmapDrawable)?.bitmap ?: return
        if (art === shadeSource) return
        shadeSource = art
        // Sampled off the main thread: reading a hardware bitmap back from the GPU stalls it for several ms.
        background({ samplePixels(art, 16, 24)?.let { brightLeft(it, 16) } }) { r ->
            val bright = r.getOrNull() ?: return@background
            if (art !== shadeSource) return@background
            // A bright film keeps the shade dark across the whole text column, not just at the screen's edge.
            windowShade?.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (bright) intArrayOf(0xF70E0C0A.toInt(), 0xE00E0C0A.toInt(), 0x800E0C0A.toInt(), 0x000E0C0A)
                else intArrayOf(0xEB0E0C0A.toInt(), 0x000E0C0A, 0x000E0C0A))
        }
    }

    /** Sharp film art fills the screen; shade stays behind text and the bottom row. */
    protected fun setWindowed(content: View, backdrop: View = View(this).apply { background = mood() }) {
        windowArt = backdrop as? ImageView
        shadeSource = null
        window.setBackgroundDrawable(null) // the scene paints the whole screen
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            windowShade = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xEB0E0C0A.toInt(), 0x000E0C0A, 0x000E0C0A))
            }.also { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF70E0C0A.toInt(), 0x000E0C0A, 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A, 0x000E0C0A, 0x000E0C0A, 0x000E0C0A, 0x000E0C0A, 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, -1))
            for (edge in listOf(GradientDrawable.Orientation.LEFT_RIGHT, GradientDrawable.Orientation.RIGHT_LEFT,
                GradientDrawable.Orientation.TOP_BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP)) {
                addView(View(context).apply {
                    background = GradientDrawable(edge,
                        intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(content, FrameLayout.LayoutParams(-1, -1))
        })
        updateWindowShade()
    }

    /** A quiet tinted row on the pane (episodes, sources): no edge, no glass of its own. */
    protected fun tile(radiusDp: Int = TILE_RADIUS) = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Solid fallback until a title's art is available. */
    private fun mood() = rounded(BASE, 0)

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    protected fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(TEXT)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = BASE }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    protected fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    /** House Lights' single glass recipe; focus brightens the face (no rim: dots are the only indicator). */
    protected fun glass(radiusDp: Int, focused: Boolean = false): Drawable {
        val face = GradientDrawable().apply {
            setColor(if (focused) GLASS_FOCUSED else GLASS)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), GLASS_EDGE)
        }
        return LayerDrawable(arrayOf(face, Highlight(dp(radiusDp).toFloat())))
    }

    /** A stable reading surface over moving video, which cannot use the artwork frost. */
    protected fun videoGlass(radiusDp: Int): Drawable =
        LayerDrawable(arrayOf(rounded(VIDEO_GLASS_BASE, radiusDp), glass(radiusDp)))

    /** The glass's top highlight, like CSS `inset 0 1px 0`: a 1 dp sliver that follows the corners and thins out down
     *  their curve, rather than a flat bar that stops short of them and reads as a stray line. */
    private inner class Highlight(private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        override fun draw(canvas: Canvas) = canvas.drawPath(highlight(RectF(bounds), radius), paint)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun highlight(box: RectF, radius: Float): Path {
        val r = minOf(radius, box.height() / 2, box.width() / 2) // as GradientDrawable clamps a pill's radius
        val shape = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        val lower = Path().apply { addRoundRect(RectF(box).apply { offset(0f, dp(1).toFloat()) }, r, r, Path.Direction.CW) }
        return shape.apply { op(lower, Path.Op.DIFFERENCE) }
    }

    protected fun pill(words: Int, icon: Int = 0): TextView = pill(getString(words), icon)

    /** The warm-white primary action; focus lifts and grows it with a centered tungsten glow. */
    protected fun pill(label: String, icon: Int = 0): TextView = text(label, SMALL, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(BUTTON_H)
        setPadding(dp(if (icon != 0) 14 else 16), 0, dp(16), 0)
        compoundDrawablePadding = dp(6)
        if (icon != 0) setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
        fun style(focused: Boolean) {
            val r = dp(BUTTON_H / 2).toFloat()
            background = object : Drawable() {
                private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TEXT
                    if (focused) setShadowLayer(dp(12).toFloat(), 0f, 0f, TUNGSTEN_GLOW)
                }
                private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
                private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dp(1).toFloat()
                    color = TUNGSTEN
                }
                override fun draw(canvas: Canvas) {
                    val box = RectF(bounds)
                    if (focused) canvas.drawRoundRect(box, r, r, glow)
                    canvas.drawRoundRect(box, r, r, face)
                    if (focused) canvas.drawRoundRect(box, r, r, rim)
                }
                override fun setAlpha(alpha: Int) {
                    glow.alpha = alpha
                    face.alpha = alpha
                    rim.alpha = alpha
                }
                override fun setColorFilter(colorFilter: ColorFilter?) {
                    glow.colorFilter = colorFilter
                    face.colorFilter = colorFilter
                    rim.colorFilter = colorFilter
                }
                @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
            setTextColor(BASE)
            compoundDrawablesRelative[0]?.setTint(BASE)
            elevation = 0f
        }
        style(false)
        setOnFocusChangeListener { v, has ->
            style(has)
            v.grow(has, CHIP_GROW).start()
        }
    }

    /**
     * Focus growth for every control: scales by [by] and first lets its layout parents draw it past their bounds, so a
     * plain container can't cut the grown edge, rim and glow flat. It stops at the first scrolling view and leaves that
     * one alone: a scroller's clip is what hides rows and cards that have scrolled out, and unclipping it let Home's
     * rows slide over the title. Rows give their cards room to grow with their own padding.
     */
    protected fun View.grow(on: Boolean, by: Float = CARD_GROW): android.view.ViewPropertyAnimator {
        if (on) generateSequence(parent as? android.view.ViewGroup) { it.parent as? android.view.ViewGroup }
            .takeWhile { it !is android.widget.ScrollView && it !is android.widget.HorizontalScrollView && it !is android.widget.AbsListView }
            .forEach { it.clipChildren = false; it.clipToPadding = false }
        return animate().scaleX(if (on) by else 1f).scaleY(if (on) by else 1f).setDuration(150)
    }

    protected fun chip(words: Int, selected: Boolean = false) = chip(getString(words), selected)

    /** A persistent choice: its dot remains after remote focus moves. */
    protected fun chip(label: String, selected: Boolean = false) = text(label, SMALL).apply {
        select(label, selected)
        isFocusable = true
        gravity = Gravity.CENTER
        minHeight = dp(CHIP_H)
        setPadding(dp(16), 0, dp(16), 0)
        background = glass(CHIP_H / 2)
        setOnFocusChangeListener { v, has -> v.background = glass(CHIP_H / 2, has); v.grow(has, CHIP_GROW).start() }
    }

    /** A [chip]'s open state: the tungsten dot and full-strength words. */
    protected fun TextView.select(label: String, selected: Boolean) {
        setTextColor(if (selected) TEXT else TEXT_2)
        text = if (!selected) label else android.text.SpannableString("•  $label").apply {
            setSpan(android.text.style.ForegroundColorSpan(TUNGSTEN), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    protected fun caption(value: String) = text(value, SMALL).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = glassOver(this, 16)
    }

    /** "[OK] Play / pause": the key in a small box, then what it does. */
    protected fun keyHint(key: String, does: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(key, 9f, bold = true, color = BASE).apply {
            background = rounded(TEXT_2, 3)
            includeFontPadding = false
            setPadding(dp(4), dp(1), dp(4), dp(1))
        })
        addView(text(does, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
    }

    /** A quiet outlined tag beside a row's name ("Built-in", "ElfHosted", "THIS PROFILE"). */
    protected fun tag(label: String, color: Int = TEXT_3) = text(label, SMALL, color = color).apply {
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(5), dp(2), dp(5), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setStroke(dp(1), GLASS_EDGE) }
    }

    /** A hairline under a list row. */
    protected fun divider() = View(this).apply { setBackgroundColor(GLASS_EDGE) }

    /** Alder's mark beside the serif "Alder": top left of the picker and sign-in, and Settings › About. */
    protected fun brand(size: Float = WORDMARK) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val mark = (size * 1.3f).toInt() // the mark's art fills about two thirds of its square
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_alder_mark) }, LinearLayout.LayoutParams(dp(mark), dp(mark)))
        addView(text("Alder", size, serif = true).onArt(), wrap().apply { marginStart = dp(4) })
    }

    /** The IMDb tag beside a rating, as tall as the letters. */
    protected fun imdbTag() = text("IMDb", SMALL, bold = true, color = BASE).apply {
        background = rounded(TUNGSTEN, 3)
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    /** Null animates loading; for countdowns set progress to fraction × max as time changes. */
    protected fun ring(fraction: Float? = null): ProgressBar = object : ProgressBar(this, null,
        // A countdown needs a style that keeps its progress; the default one is indeterminate-only and reads 0.
        if (fraction == null) android.R.attr.progressBarStyle else android.R.attr.progressBarStyleHorizontal) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            if (fraction == null) return super.onDraw(canvas)
            val inset = stroke.strokeWidth / 2
            val bounds = RectF(inset, inset, width - inset, height - inset)
            stroke.color = PROGRESS_TRACK
            canvas.drawArc(bounds, 0f, 360f, false, stroke)
            stroke.color = TUNGSTEN
            canvas.drawArc(bounds, -90f, progress.toFloat() / max * 360f, false, stroke)
        }
    }.apply {
        require(fraction == null || (fraction.isFinite() && fraction in 0f..1f))
        isIndeterminate = fraction == null
        indeterminateTintList = android.content.res.ColorStateList.valueOf(TUNGSTEN)
        max = 1000
        if (fraction != null) progress = (fraction * max).toInt()
    }

    /** On-screen progress overlay: dark veil with a tungsten ring and status message in the center. */
    protected fun loadingOverlay(statusText: String = getString(R.string.finding_sources)): Pair<FrameLayout, TextView> {
        val label = text(statusText, BODY, color = TEXT_2).onArt()
        val veil = FrameLayout(this).apply {
            visibility = View.GONE
            isFocusable = true
            isClickable = true
            addView(View(context).apply { setBackgroundColor(0x800E0C0A.toInt()) }, FrameLayout.LayoutParams(-1, -1))
            val center = column(Gravity.CENTER_HORIZONTAL).apply {
                addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            }
            addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        return veil to label
    }

    /** One list-row focus treatment across settings, episodes, sources and menus. */
    protected fun View.rowFocus(radiusDp: Int = 8, rest: () -> android.graphics.drawable.Drawable? = { null }, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        background = rest()
        setOnFocusChangeListener { v, has ->
            v.background = if (has) GradientDrawable().apply {
                setColor(ROW_FOCUSED)
                cornerRadius = dp(radiusDp).toFloat()
            } else rest()
            also(has)
        }
    }

    /** One poster/portrait focus treatment; the poster itself supplies its glow colour. */
    protected fun View.cardFocus(radiusDp: Int = 6, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        setOnFocusChangeListener { v, has ->
            v.lift(has, radiusDp)
            also(has)
        }
    }

    private val glowCache = java.util.WeakHashMap<View, Pair<Bitmap, Int>>()

    private fun posterBitmap(view: View): Bitmap? = when (view) {
        is ImageView -> (view.drawable as? BitmapDrawable)?.bitmap
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { posterBitmap(view.getChildAt(it)) }
        else -> null
    }

    protected fun View.lift(on: Boolean, radiusDp: Int = 6) {
        foreground = if (on) GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), TUNGSTEN_RIM) // a hairline: the glow and the lift carry focus
        } else null
        if (on && android.os.Build.VERSION.SDK_INT >= 28) {
            val poster = posterBitmap(this)
            val known = if (poster == null) TUNGSTEN_GLOW else glowCache[this]?.takeIf { it.first === poster }?.second
            outlineSpotShadowColor = known ?: TUNGSTEN_GLOW
            // Reading a poster's pixels back from the GPU took ~7 ms on the main thread per press: the glow is sampled
            // off it and tints the shadow a moment later, while the lift is still growing.
            if (known == null && poster != null) background({ samplePixels(poster, 16, 24)?.let(::filmGlow) }) { r ->
                r.getOrNull()?.let { glow -> glowCache[this] = poster to glow; if (hasFocus()) outlineSpotShadowColor = glow }
            }
        }
        grow(on)
            .translationZ(if (on) dp(CARD_LIFT).toFloat() else 0f).setDuration(150).start()
    }

    /** The screen's art, behind its glass: what [glassOver] frosts. Screens with art behind them name it. */
    protected open val backdrop: ImageView? get() = null

    private var frostSource: Bitmap? = null
    private var frostBitmap: Bitmap? = null
    private val frostHosts = mutableListOf<View>()

    /**
     * Frosted glass, as the episode list draws it: the art behind, blurred, seen through a rounded pane under the
     * glass tint. A background for [host] (pills, the top bar, panels); without a [backdrop] it's the plain tint.
     */
    /** [shade]: how much of the screen's own shade lies over the art there (0..1), so glass over a shaded part of the art
     *  is as dark as the art around it instead of a milky patch of the unshaded picture. */
    protected fun glassOver(host: View, radiusDp: Int, shade: Float = 0f): Drawable {
        // No art to frost (Settings): the glass sits on the base colour, so a dialog doesn't show the rows under it.
        val behind = backdrop ?: return LayerDrawable(arrayOf(rounded(BASE, radiusDp), glass(radiusDp)))
        frostHosts += host
        return Frost(host, behind, dp(radiusDp).toFloat(), shade)
    }

    /** The art behind changed: glass redraws with the new picture once it's blurred. */
    protected fun refrost() {
        frostHosts.forEach { it.invalidate() }
        updateWindowShade()
    }

    /** The art blurred once per picture ([Images.frost]); the last one stays up while the next is made. */
    private fun frosted(d: Drawable?): Bitmap? {
        val src = (d as? BitmapDrawable)?.bitmap ?: return frostBitmap
        if (src !== frostSource) {
            frostSource = src
            background({ Images.frost(src, FROST_W) }) { r -> if (frostSource === src) { frostBitmap = r.getOrNull(); refrost() } }
        }
        return frostBitmap
    }

    private inner class Frost(private val host: View, private val behind: ImageView, private val radius: Float,
                              private val shade: Float = 0f) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_EDGE; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GLASS_HIGHLIGHT }
        private val path = Path()
        private val at = IntArray(2)
        private val art = IntArray(2)

        override fun draw(canvas: Canvas) {
            path.reset()
            path.addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW)
            val d = behind.drawable
            frosted(d)?.let { f ->
                canvas.save()
                canvas.clipPath(path)
                // The blurred art lines up with the sharp art behind: where the host sits on screen, through the same crop.
                // On screen, not in the window: a menu's glass lives in a dialog's window over the screen's art.
                host.getLocationOnScreen(at)
                behind.getLocationOnScreen(art)
                canvas.translate((art[0] - at[0]).toFloat(), (art[1] - at[1]).toFloat())
                canvas.concat(behind.imageMatrix)
                // As bright as the sharp art around it: a dimmed backdrop (Settings') dims its frosted copy too.
                if (behind.alpha < 1f) canvas.drawColor(BASE)
                paint.alpha = (behind.alpha * 255).toInt()
                canvas.drawBitmap(f, null, Rect(0, 0, d?.intrinsicWidth ?: f.width, d?.intrinsicHeight ?: f.height), paint)
                if (shade > 0f) canvas.drawColor(Color.argb((shade * 255).toInt(), 14, 12, 10))
                canvas.restore()
            }
            canvas.drawPath(path, tint)
            canvas.drawPath(path, edge)
            canvas.drawPath(highlight(RectF(bounds), radius), shine)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** A thin watched-so-far bar over a dark track. */
    protected fun progressBar(progress: Float, color: Int = TUNGSTEN) = LinearLayout(this).apply {
        val p = progress.coerceIn(0f, 1f)
        setBackgroundColor(PROGRESS_TRACK)
        addView(View(context).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, -1, p))
        addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - p))
    }

    protected fun isInside(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /**
     * A short list to pick from (a menu, a genre, a yes or no), on glass with tungsten focus, in
     * place of Android's grey dialog. [checked] gets a tick (the choice in use); the remote starts on [focus].
     */
    protected fun choose(title: String, items: List<String>, message: String? = null, checked: Int = -1,
                         focus: Int = maxOf(checked, 0), pick: (Int) -> Unit) {
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(12), dp(16), dp(12), dp(12)) }
        val words = column(Gravity.START, text(title, BODY, bold = true)).apply { setPadding(dp(16), 0, dp(16), dp(10)) }
        message?.let { words.addView(text(it, SMALL, color = TEXT_2).marginTop(6)) }
        list.addView(words, LinearLayout.LayoutParams(-1, -2))
        val rows = items.mapIndexed { i, label ->
            text(label, BODY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                if (i == checked) setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_check)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null)
                rowFocus()
                setOnClickListener { dialog.dismiss(); pick(i) }
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        dialog.setContentView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = glassOver(this, 16, shade = FROST_READING)
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(340), -2)
            setDimAmount(0.6f)
        }
        dialog.show()
        rows.getOrNull(focus)?.requestFocus()
    }

    /** Highlights come from CHANGELOG.md; the full release notes remain one remote action away. */
    protected fun whatsNew(version: String = Updates.mine(this), quiet: Boolean = false) {
        val changes = runCatching { assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { text -> changesFor(text, version).ifEmpty { changesFor(text, "Unreleased") } }.orEmpty()
        if (changes.isEmpty()) {
            if (!quiet) choose(getString(R.string.whats_new), listOf(getString(R.string.action_ok)),
                getString(R.string.whats_new_no_notes, version)) {}
            return
        }
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val highlights = showcaseHighlights(changes)

        val metrics = resources.displayMetrics
        val dialogW = minOf(dp(1060), (metrics.widthPixels * 0.88f).toInt())
        val dialogH = minOf(dp(620), (metrics.heightPixels * 0.84f).toInt())

        val root = FrameLayout(this).apply {
            background = glassOver(this, 16, shade = FROST_DENSE)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
            clipToOutline = true
        }

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(columns, FrameLayout.LayoutParams(-1, -1))

        // The selected change stays in view while the remote moves through the list.
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        columns.addView(left, LinearLayout.LayoutParams(0, -1, 0.58f))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brand(WORDMARK))
            addView(tag(getString(R.string.whats_new_version, version), TUNGSTEN).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }
            })
        }
        left.addView(header)

        val heroScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), dp(16), dp(16))
        }
        val categoryTag = caps(R.string.whats_new_group, TUNGSTEN)
        val titleView = text("", SERIF_TITLE, serif = true).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val descView = text("", BODY, color = TEXT).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        heroContent.addView(categoryTag)
        heroContent.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        heroContent.addView(descView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        heroScroll.addView(heroContent)
        left.addView(heroScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val gotIt = pill(R.string.action_got_it).apply {
            id = View.generateViewId()
            setOnClickListener { dialog.dismiss() }
        }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(gotIt)
        }
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        val divider = View(this).apply {
            setBackgroundColor(GLASS_EDGE)
        }
        columns.addView(divider, LinearLayout.LayoutParams(dp(1), -1).apply {
            marginStart = dp(24)
            marginEnd = dp(24)
        })

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        columns.addView(right, LinearLayout.LayoutParams(0, -1, 0.42f))

        val listHeader = caps(plural(R.plurals.whats_new_highlights, highlights.size), TUNGSTEN)
        right.addView(listHeader)

        val listScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
        }
        val entriesColumn = column(Gravity.START)
        listScroll.addView(entriesColumn, FrameLayout.LayoutParams(-1, -2))
        right.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        val keyHints = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(keyHint("↑ ↓", getString(R.string.key_browse)))
        }
        val backHint = keyHint(getString(R.string.key_back), getString(R.string.key_exit)).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
        }
        keyHints.addView(backHint)
        right.addView(keyHints, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        fun updateHero(change: Change, index: Int, total: Int) {
            val group = (change.group ?: getString(R.string.whats_new_group)).uppercase()
            categoryTag.text = "✦ $group  ·  ${index + 1} / $total"
            val displayTitle = change.title.ifEmpty { change.text }
            titleView.text = displayTitle
            val desc = change.description.ifEmpty { change.text }
            descView.text = changeLine(desc)
            heroScroll.scrollTo(0, 0)
        }

        var firstRow: View? = null
        var allChangesShown = false
        fun render(showAll: Boolean) {
            allChangesShown = showAll
            val visible = if (showAll) changes else highlights
            listHeader.text = if (showAll) getString(R.string.whats_new_all, changes.size)
                else plural(R.plurals.whats_new_highlights, highlights.size)
            (backHint.getChildAt(1) as TextView).text = getString(if (showAll) R.string.whats_new_highlights_short else R.string.key_exit)
            entriesColumn.removeAllViews()
            listScroll.scrollTo(0, 0)
            firstRow = null
            var currentGroup: String? = null
            visible.forEachIndexed { index, change ->
                if (showAll && change.group != null && change.group != currentGroup) {
                    currentGroup = change.group
                    entriesColumn.addView(caps(change.group, TUNGSTEN).apply {
                        setPadding(dp(10), dp(16), dp(10), dp(4))
                    })
                }
                val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                id = View.generateViewId()
                nextFocusLeftId = gotIt.id
                setPadding(dp(12), dp(9), dp(12), dp(9))

                val dot = View(context).apply {
                    background = rounded(TUNGSTEN, 3)
                    visibility = View.INVISIBLE
                }
                addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(10) })

                val rowTitle = change.title.ifEmpty { change.text }
                val label = text(rowTitle, BODY, color = TEXT_2).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))

                rowFocus(8, rest = { null }) { hasFocus ->
                    dot.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                    label.setTextColor(if (hasFocus) TEXT else TEXT_2)
                    if (hasFocus) {
                        gotIt.nextFocusRightId = id
                        updateHero(change, index, visible.size)
                    }
                }
                setOnClickListener { updateHero(change, index, visible.size) }
            }
                if (firstRow == null) {
                    firstRow = row
                }
                entriesColumn.addView(row, LinearLayout.LayoutParams(-1, -2))
            }

            val action = text(if (showAll) getString(R.string.whats_new_back) else plural(R.plurals.whats_new_see_all, changes.size),
                BODY, color = TUNGSTEN).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                nextFocusLeftId = gotIt.id
                setPadding(dp(28), dp(16), dp(12), dp(16))
                rowFocus(8, rest = { null }) { hasFocus ->
                    if (hasFocus) gotIt.nextFocusRightId = id
                }
                setOnClickListener { render(!showAll) }
            }
            entriesColumn.addView(action, LinearLayout.LayoutParams(-1, -2))
            if (firstRow == null) firstRow = action
            visible.firstOrNull()?.let { updateHero(it, 0, visible.size) }
            firstRow?.id?.let { gotIt.nextFocusRightId = it }
            if (dialog.isShowing) firstRow?.requestFocus()
        }

        render(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP && allChangesShown) {
                render(false)
                true
            } else false
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dialogW, dialogH)
            setDimAmount(0.6f)
        }
        dialog.show()
        firstRow?.requestFocus()
    }

    /** One glass error card with a plain message and a next action. */
    protected fun failed(message: String, retry: () -> Unit) = column(Gravity.START,
        text("!", SERIF_NAME, color = TUNGSTEN, serif = true),
        text(message, BODY, color = TEXT).marginTop(10),
        pill(R.string.action_try_again).apply { setOnClickListener { retry() } }.marginTop(16),
    ).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = glassOver(this, 14, shade = FROST_READING)
    }

    // House Lights' shared values. Screens keep their layout and content; the helpers above own component styling.
    private class Fonts(assets: android.content.res.AssetManager) {
        val medium: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 500").setWeight(500).build()
        val semiBold: Typeface = Typeface.Builder(assets, "fonts/inter.ttf").setFontVariationSettings("'wght' 600").setWeight(600).build()
        val serif: Typeface = Typeface.createFromAsset(assets, "fonts/instrument_serif.ttf")
    }

    companion object {
        private var loadedFonts: Fonts? = null // main thread only
        const val PAD = 48
        const val TILE_RADIUS = 8
        const val GLASS_INSET = 28 // dp a glass panel sits in from the screen's edges
        const val FROST_W = 360 // px the art is shrunk to before the blur: wider is less blur (240 read as fog, 480 as smeared)
        const val FROST_READING = 0.35f
        const val FROST_DENSE = 0.55f
        const val HERO_W = 520 // dp: a title's block on Home and its page, half the screen so the story reads in full lines
        const val BUTTON_H = 34
        const val SHORT_LIST = 5 // "What's new" entries shown before See all changes
        const val WHATS_NEW_W = 480 // dp
        const val CHIP_H = 24 // the mocks' 46–48 px chips
        const val CARD_GROW = 1.08f
        const val CHIP_GROW = 1.04f // a wide chip grown as much as a card filled the gap to its neighbour
        const val CARD_LIFT = 8
        // Spacing, everywhere rows of pictures appear: between cards, from a row's heading to its cards (plus room for
        // the focused card to grow), and above a heading, so each heading belongs to the row under it.
        const val CARD_GAP = 14
        const val POSTER_W = 88
        const val POSTER_H = 132
        const val HEADING_GAP = 24
        const val SECTION_GAP = 32
        const val ROW_H = 42 // one-line list row (Settings)
        const val ROW_H2 = 48 // two-line list row (name over what it does)
        const val PANEL_W = 0.573f // share of the screen a list panel takes, beside its side note
        const val DOT = "  ·  "

        const val WORDMARK = 22f // sp, "Alder" beside its mark
        const val SERIF_HERO = 38f
        const val SERIF_TITLE = 28.5f
        const val SERIF_NAME = 19f
        const val BODY = 14.5f
        const val SMALL = 12.5f
        const val CAPS = SMALL
        const val CODE = 36f
        val LEADING = mapOf(SERIF_HERO to 40f, SERIF_TITLE to 30f, SERIF_NAME to 21f, BODY to 20f, SMALL to 17f, CODE to 40f)

        const val BASE = 0xFF0E0C0A.toInt()
        const val TEXT = 0xFFF3EEE6.toInt()
        const val TEXT_2 = 0xB8F3EEE6.toInt()
        const val TEXT_3 = 0x73F3EEE6
        const val TUNGSTEN = 0xFFF3D9B1.toInt()
        const val TUNGSTEN_RIM = 0x99F3D9B1.toInt()
        const val TUNGSTEN_GLOW = 0x59F3D9B1
        const val GLASS = 0x4D28221C
        const val GLASS_FOCUSED = 0x3DF3EEE6 // a lit face: with no rim, the fill alone carries focus
        const val GLASS_EDGE = 0x24FFF0DC
        const val GLASS_HIGHLIGHT = 0x4DFFF5E6
        const val VIDEO_GLASS_BASE = 0xD90E0C0A.toInt()
        const val VIDEO_CONTROL_BASE = 0xA60E0C0A.toInt()
        const val ROW_FOCUSED = 0x29F3EEE6
        const val PROGRESS_TRACK = 0x4DFFFFFF // light enough that a short fill still reads as a bar, not a dash
        internal val pool = Executors.newFixedThreadPool(6)
    }
}

/** One changelog entry and the "### " heading it sits under (New, Easier to use, Fixes), null above the first. */
internal class Change(val group: String?, val text: String, val spotlight: Boolean = false) {
    /** The bold lead-in title from **Title.**, without markdown marks or trailing period. */
    val title: String get() = Regex("""\*\*(.+?)\*\*""").find(text)?.groupValues?.get(1)?.trimEnd('.').orEmpty()

    /** The description after the bold lead-in title, keeping markdown marks for changeLine(). */
    val description: String get() = text.replaceFirst(Regex("""^\s*-\s*"""), "")
        .replaceFirst(Regex("""^\s*\*\*(.+?)\*\*\.?\s*"""), "").trim()
}

/** Spotlight markers set the short first view. Older changelogs fall back to their first seven entries. */
internal fun showcaseHighlights(changes: List<Change>): List<Change> =
    changes.filter { it.spotlight }.ifEmpty { changes.take(7) }

/** The entries under "## [version]" in CHANGELOG.md, without their "- "; empty when that version has no section. */
internal fun changesFor(changelog: String, version: String): List<Change> {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.trim() == "## $version" }.takeIf { it >= 0 } ?: return emptyList()
    var group: String? = null
    var spotlight = false
    return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.mapNotNull { line ->
        when {
            line.startsWith("### ") -> { group = line.removePrefix("### ").trim(); spotlight = false; null }
            line.trim() == "<!-- Spotlight -->" -> { spotlight = true; null }
            line.startsWith("- ") -> Change(group, line.removePrefix("- ").trim(), spotlight).also { spotlight = false }
            else -> null
        }
    }
}

/** A changelog entry as it reads on screen: **bold** in bold, `code` and the other marks left out. */
internal fun changeLine(change: String): CharSequence {
    val out = android.text.SpannableStringBuilder()
    Regex("""\*\*(.+?)\*\*|`(.+?)`""").let { marks ->
        var at = 0
        marks.findAll(change).forEach { m ->
            out.append(change, at, m.range.first)
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) {
                val start = out.length
                out.append(bold)
                out.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, out.length, 0)
            } else out.append(m.groupValues[2])
            at = m.range.last + 1
        }
        out.append(change, at, change.length)
    }
    return out
}
