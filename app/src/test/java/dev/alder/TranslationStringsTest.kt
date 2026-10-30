package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Every language Alder ships has every word the English has, with the same placeholders; every word is used; and no
 * screen puts English on the TV without going through resources.
 */
class TranslationStringsTest {
    private val res = File("src/main/res").takeIf { it.exists() } ?: File("app/src/main/res")
    private val code = File(res.parentFile, "java/dev/alder")
    private val locales = listOf("es", "pt", "fr", "de", "it", "hi")

    /** "string:name" (and "plurals:name#one", "array:name[0]") to its text, as Android reads it. */
    private fun words(folder: String): Map<String, String> {
        val file = File(res, "$folder/strings.xml")
        assertTrue("$folder/strings.xml must exist", file.exists())
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = linkedMapOf<String, String>()
        fun each(tag: String, block: (Element) -> Unit) {
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) block(nodes.item(i) as Element)
        }
        each("string") { if (it.getAttribute("translatable") != "false") out["string:" + it.getAttribute("name")] = it.textContent }
        each("plurals") { p ->
            val items = p.getElementsByTagName("item")
            for (i in 0 until items.length) (items.item(i) as Element).let {
                out["plurals:" + p.getAttribute("name") + "#" + it.getAttribute("quantity")] = it.textContent
            }
        }
        each("string-array") { a ->
            val items = a.getElementsByTagName("item")
            for (i in 0 until items.length) out["array:" + a.getAttribute("name") + "[$i]"] = items.item(i).textContent
        }
        return out
    }

    private fun placeholders(text: String) =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""").findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test fun everyLanguageHasEveryWordWithTheSamePlaceholders() {
        val english = words("values")
        assertTrue(english.isNotEmpty())
        val englishPlurals = english.keys.filter { it.startsWith("plurals:") }.map { it.substringBefore('#') }.toSet()
        for (locale in locales) {
            val theirs = words("values-$locale")
            for ((key, value) in english) {
                if (key.startsWith("plurals:")) continue // forms differ by language; checked below
                val translated = theirs[key]
                assertTrue("values-$locale is missing $key", translated != null)
                assertTrue("values-$locale has a blank $key", translated!!.isNotBlank())
                assertEquals("Placeholders differ in values-$locale for $key", placeholders(value), placeholders(translated))
            }
            for (plural in englishPlurals) {
                val forms = theirs.filterKeys { it.substringBefore('#') == plural }
                assertTrue("values-$locale is missing $plural#other", forms.containsKey("$plural#other"))
                val want = placeholders(english.getValue("$plural#other"))
                forms.forEach { (key, text) -> assertEquals("Placeholders differ in values-$locale for $key", want, placeholders(text)) }
            }
            val extra = theirs.keys.map { it.substringBefore('#') } - english.keys.map { it.substringBefore('#') }.toSet()
            assertTrue("values-$locale has words English doesn't: $extra", extra.isEmpty())
        }
    }

    @Test fun everyWordIsUsed() {
        val source = code.walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = words("values").keys.map { key ->
            val type = key.substringBefore(':')
            "R.$type." + key.substringAfter(':').substringBefore('#').substringBefore('[')
        }.distinct().filterNot { Regex(Regex.escape(it) + "\\b").containsMatchIn(source) }
        assertTrue("Words nobody shows: $unused", unused.isEmpty())
    }

    /** Words on screen go through resources; only names stay as they are. */
    @Test fun screensHaveNoHardcodedEnglish() {
        val calls = Regex("""(?<![.\w])(text|caps|pill|chip|choose|actionRow|button|tag|failed|caption|onboardingButton|timingPill|settingRow)\("([^"]*)"""")
        val assigned = Regex("""\b(contentDescription|hint)\s*=\s*"([^"]*)"""")
        val toast = Regex("""Toast\.makeText\(this, "([^"]*)"""")
        val names = setOf("IMDb", "Alder", "OK", "ABC", "https://…/manifest.json")
        val found = code.walk().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex().flatMap { (i, line) ->
                (calls.findAll(line).map { it.groupValues[2] } + assigned.findAll(line).map { it.groupValues[2] } +
                    toast.findAll(line).map { it.groupValues[1] })
                    .filter { words ->
                        val shown = words.replace(Regex("""\$\{[^}]*\}|\$\w+"""), "") // what the template fills in isn't words
                        Regex("[A-Za-z]{2,}").containsMatchIn(shown) && words !in names && !words.startsWith("DOLBY ") && !words.startsWith("DTS")
                    }
                    .map { "${file.name}:${i + 1}: \"$it\"" }
            }
        }.toList()
        assertTrue("Put these words in res/values/strings.xml:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun localesConfigListsEveryLanguage() {
        val config = File(res, "xml/locales_config.xml").readText()
        (listOf("en") + locales.map { if (it == "pt") "pt-BR" else it }).forEach { lang ->
            assertTrue("locales_config.xml must declare $lang", config.contains("""<locale android:name="$lang"/>"""))
        }
    }
}
