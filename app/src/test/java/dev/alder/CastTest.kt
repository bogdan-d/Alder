package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTest {

    @Test
    fun parseCastMemberValid() {
        val member = parseCastMember("  Cillian Murphy  ", "  J. Robert Oppenheimer  ", " https://img.org/cillian.jpg ")
        assertEquals("Cillian Murphy", member?.name)
        assertEquals("J. Robert Oppenheimer", member?.character)
        assertEquals("https://img.org/cillian.jpg", member?.photo)
        assertEquals("CM", member?.initials)
        assertFalse(member?.isDirector == true)
    }

    @Test
    fun parseCastMemberDirector() {
        val dir = CastMember("Christopher Nolan", character = "Director", role = "director", tmdbId = 525)
        assertEquals("Christopher Nolan", dir.name)
        assertEquals("Director", dir.character)
        assertTrue(dir.isDirector)
        assertEquals("CN", dir.initials)
        assertEquals(525, dir.tmdbId)
    }

    @Test
    fun parseCastMemberBlanks() {
        assertNull(parseCastMember("   "))
        assertNull(parseCastMember(null))

        val noRole = parseCastMember("Emily Blunt", "   ", "")
        assertEquals("Emily Blunt", noRole?.name)
        assertNull(noRole?.character)
        assertNull(noRole?.photo)
    }

    @Test
    fun initialsGeneration() {
        assertEquals("CM", CastMember("Cillian Murphy").initials)
        assertEquals("Z", CastMember("Zendaya").initials)
        assertEquals("RD", CastMember("Robert Downey Jr.").initials)
        assertEquals("RD", CastMember("Robert Downey Jr").initials)
        assertEquals("AC", CastMember("A B C").initials)
        assertEquals("", CastMember("").initials)
    }

    @Test
    fun tmdbInvalidId() {
        assertEquals(emptyList<CastMember>(), Tmdb.cast("invalid_id", "movie"))
        assertEquals(emptyList<CastMember>(), Tmdb.cast("", "series"))
        assertEquals(emptySet<String>(), Tmdb.personCredits(null, "", false))
    }

    @Test
    fun castChipLabelOmitsDirPrefix() {
        val dir = CastMember("Denis Villeneuve", character = "Director", role = "director")
        val actor = CastMember("Timothée Chalamet", character = "Paul Atreides")
        assertEquals("Denis Villeneuve", castChipLabel(dir))
        assertEquals("Timothée Chalamet", castChipLabel(actor))
    }

    @Test
    fun heroCastPreviewCapsDirectorsAtOneAndPrioritizesLeadActors() {
        val dir1 = CastMember("Vince Gilligan", character = "Director", role = "director")
        val dir2 = CastMember("Peter Gould", character = "Director", role = "director")
        val dir3 = CastMember("Michelle MacLaren", character = "Director", role = "director")
        val actor1 = CastMember("Bryan Cranston", character = "Walter White")
        val actor2 = CastMember("Aaron Paul", character = "Jesse Pinkman")
        val actor3 = CastMember("Anna Gunn", character = "Skyler White")

        val preview = heroCastPreview(listOf(dir1, dir2, dir3, actor1, actor2, actor3), maxCount = 3)
        assertEquals(listOf("Vince Gilligan", "Bryan Cranston", "Aaron Paul"), preview.map { it.name })
    }
}
