package dev.alder

import java.io.DataInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A one-box web page a phone opens from a QR code, for text too long to type with a remote (an API key). It answers
 * only on [path], a random part only the QR carries, and only while it's open; what's typed goes from the phone
 * straight to the TV over the home network. [got] runs on the server's thread with the text, once, then it closes.
 */
internal class PhoneEntry(private val page: (saved: Boolean) -> String, private val got: (String) -> Unit) : AutoCloseable {
    val path = "/" + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    private val server = ServerSocket(0)
    val port get() = server.localPort

    /** The address for the QR, or null when the TV has no home-network address (then type it on the TV). */
    val url: String? = lanAddress()?.let { "http://$it:$port$path" }

    init {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { socket.use(::answer) }
            }
        }, "phone-entry").apply { isDaemon = true }.start()
    }

    override fun close() = runCatching { server.close() }.let {}

    private fun answer(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val (method, target) = head.lineSequence().first().split(" ").let { it.getOrNull(0) to it.getOrNull(1) }
        val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = if (length in 1..MAX_BODY) String(ByteArray(length).also { DataInputStream(input).readFully(it) }, Charsets.UTF_8) else ""
        val typed = if (method == "POST") formValue(body, "text")?.trim()?.takeIf { it.isNotEmpty() } else null
        val (status, html) = when {
            target != path -> "404 Not Found" to ""
            typed != null -> "200 OK" to page(true)
            else -> "200 OK" to page(false)
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
        if (typed != null && target == path) { got(typed); close() }
    }

    // ponytail: byte-at-a-time header read, fine for one request from one phone.
    private fun readHead(input: InputStream): String? {
        val out = StringBuilder()
        while (out.length < MAX_HEAD) {
            val b = input.read().takeIf { it >= 0 } ?: return null
            out.append(b.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString()
        }
        return null
    }

    private companion object {
        // No i, l, o, 0, 1 (look alike), and no x: Inter draws "6x7" as "6×7".
        const val ALPHABET = "abcdefghjkmnpqrstuvwyz23456789"
        const val MAX_HEAD = 8_192
        const val MAX_BODY = 4_096
        val random = SecureRandom()
    }
}

/** One field of an HTML form's body ("text=ab%2Bc&x=1" → "ab+c"). */
internal fun formValue(body: String, name: String): String? = body.split("&").firstNotNullOfOrNull { pair ->
    val (k, v) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    if (URLDecoder.decode(k, "UTF-8") == name) URLDecoder.decode(v, "UTF-8") else null
}

/** The TV's IPv4 address on the home network (Wi-Fi or Ethernet). */
internal fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/** The phone's page: one box and a button, or a "saved" note, in the TV's language. Words are escaped. */
internal fun phoneEntryPage(title: String, label: String, button: String, saved: String?): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    // The House Lights tokens, as CSS.
    fun css(c: Int) = "rgba(${c shr 16 and 255},${c shr 8 and 255},${c and 255},${"%.2f".format(java.util.Locale.US, (c ushr 24) / 255f)})"
    val base = css(Screen.BASE); val text = css(Screen.TEXT); val text2 = css(Screen.TEXT_2)
    val edge = css(Screen.GLASS_EDGE); val glass = css(Screen.GLASS_EDGE or 0x10000000); val lamp = css(Screen.TUNGSTEN)
    val inner = if (saved != null) "<p>${esc(saved)}</p>"
        else "<form method=\"post\"><label>${esc(label)}<input name=\"text\" autocomplete=\"off\" autocapitalize=\"off\" " +
            "spellcheck=\"false\" autofocus></label><button>${esc(button)}</button></form>"
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>${esc(title)}</title><style>body{margin:0;padding:32px 20px;background:$base;color:$text;" +
        "font:17px/1.4 system-ui,sans-serif}h1{font-size:22px;margin:0 0 20px}label{display:block;color:$text2}" +
        "input{display:block;box-sizing:border-box;width:100%;margin:8px 0 16px;padding:14px;border-radius:10px;" +
        "border:1px solid $edge;background:$glass;color:$text;font:16px monospace}" +
        "button{width:100%;padding:14px;border:0;border-radius:24px;background:$lamp;color:$base;font:600 17px system-ui}" +
        "</style></head><body><h1>${esc(title)}</h1>$inner</body></html>"
}
