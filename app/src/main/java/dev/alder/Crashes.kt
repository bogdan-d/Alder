package dev.alder

import android.content.Context
import java.io.File

/** Starts before any screen, so a crash in the first activity (Android 6 and 7 on 1.3.1) is still written down. */
class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        ProgressSaves.init(this)
        Crashes.install(this)
    }
}

/**
 * The last time Alder stopped. It leaves the TV only when the viewer selects Send report (on Home after the crash, or in
 * Settings › About). Web addresses and long keys are removed before it is saved, since add-on addresses carry debrid
 * keys and stack messages can quote them.
 */
internal object Crashes {
    private fun file(c: Context) = File(c.noBackupFilesDir, "crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(describe(app) + "\n" + "Thread: ${thread.name}\n" + trace(error) + FailureLog.report()) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash, or null when Alder has not stopped since it was installed. */
    fun last(c: Context): String? = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()

    fun lastTime(c: Context): Long = file(c).lastModified()

    /** True once per crash: the next Home visit offers to send it, then not again until Alder stops again. */
    fun unseen(c: Context): Boolean {
        val prefs = c.getSharedPreferences("app", Context.MODE_PRIVATE)
        val at = lastTime(c)
        if (!sendable(c) || prefs.getLong("crashSeen", 0) >= at) return false
        prefs.edit().putLong("crashSeen", at).apply()
        return true
    }

    /** A crash is saved, not sent yet, and there is somewhere to send it. */
    fun sendable(c: Context): Boolean {
        val at = lastTime(c)
        return ENDPOINT.isNotEmpty() && at > 0 && c.getSharedPreferences("app", Context.MODE_PRIVATE).getLong("crashSent", 0) < at
    }

    /** Posts the saved crash to the crash worker (crash-worker/), which files it as a GitHub issue. Blocks. */
    fun send(c: Context) {
        val report = last(c) ?: return
        Http.post(ENDPOINT, report, timeoutMs = 15_000, headers = mapOf("X-Alder" to "1"))
        c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong("crashSent", lastTime(c)).apply()
    }

    // The crash worker (crash-worker/README.md). Empty hides Send report.
    private const val ENDPOINT = "https://alder-crash.alder-tv.workers.dev/"

    /** Version, build, Android and device: enough to repeat a report, and nothing about the viewer. */
    fun describe(c: Context): String {
        val b = android.os.Build.VERSION.SDK_INT
        @Suppress("DEPRECATION") // longVersionCode needs Android 9
        val code = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionCode }.getOrDefault(0)
        return "Alder ${Updates.mine(c)} ($code)\n" +
            "Android ${android.os.Build.VERSION.RELEASE} (API $b)\n" +
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})\n" +
            "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n"
    }

    /** The error and its causes, at most [MAX] characters, with addresses and keys taken out. */
    internal fun trace(error: Throwable): String = redact(error.stackTraceToString()).take(MAX)

    // ponytail: 20+ letters and digits in a row reads as a key; a long class name with a dot or $ stays readable.
    internal fun redact(text: String): String = text
        .replace(Regex("""(https?|wss?|magnet):\S+""")) { m ->
            Regex("""^\w+://([^/?#\s:]+)""").find(m.value)?.let { "${m.groupValues[1]}://${it.groupValues[1]}/…" } ?: "${m.groupValues[1]}:…"
        }
        .replace(Regex("""[A-Za-z0-9_\-]{20,}""")) { m -> if (m.value.any(Char::isDigit) || m.value.contains('-')) "…" else m.value }

    private const val MAX = 6_000
}
