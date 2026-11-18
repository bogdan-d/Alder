package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PhoneEntryTest {
    private fun send(port: Int, path: String, form: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream.bufferedReader().readText() else "")
    }

    @Test fun phoneSendsTheKeyOnce() {
        val got = CompletableFuture<String>()
        val entry = PhoneEntry({ saved -> if (saved) "saved" else "form" }) { got.complete(it) }
        try {
            assertEquals(404, send(entry.port, "/wrong").first)
            assertEquals("form", send(entry.port, entry.path).second)
            assertEquals("form", send(entry.port, entry.path, "text=%20%20").second) // blank isn't sent
            assertFalse(got.isDone)
            assertEquals("saved", send(entry.port, entry.path, "text=%20ab%2Bc12%20").second)
            assertEquals("ab+c12", got.get(2, TimeUnit.SECONDS))
            assertTrue(runCatching { send(entry.port, entry.path) }.isFailure) // closed after the key
        } finally { entry.close() }
    }

    @Test fun formFields() {
        assertEquals("a b&c", formValue("x=1&text=a+b%26c", "text"))
        assertNull(formValue("x=1", "text"))
    }

    @Test fun pageEscapesWords() {
        assertTrue(phoneEntryPage("<Key>", "L", "B", null).contains("&lt;Key&gt;"))
        assertTrue(phoneEntryPage("T", "L", "B", "Saved & done").contains("Saved &amp; done"))
    }
}
