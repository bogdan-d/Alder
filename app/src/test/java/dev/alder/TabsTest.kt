package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class TabsTest {
    @Test fun tabsFollowTheAddonsCatalogs() {
        assertEquals(listOf("shows", "movies", "live", "type:trakt", "type:anime", "library"),
            tabsFor(listOf("movie", "series", "Trakt", "tv", "anime")))
        assertEquals(listOf("shows", "movies", "library"), tabsFor(listOf("movie", "series")))
    }

    @Test fun nothingSavedKeepsAldersOrder() {
        val available = listOf("shows", "movies", "type:trakt", "library")
        assertEquals(available, orderTabs(emptyList(), available))
    }

    @Test fun builtInTabsKeepTheirSavedOrderWithoutAddonData() =
        assertEquals(listOf("library", "movies", "shows"),
            orderTabs(listOf("library", "type:anime", "movies", "live", "shows"), tabsFor(emptyList())))

    @Test fun reorderingBuiltInTabsKeepsUnavailableAddonTabs() =
        assertEquals(listOf("library", "type:anime", "shows", "movies"),
            saveTabOrder(listOf("shows", "type:anime", "movies", "library"), listOf("library", "shows", "movies")))

    @Test fun savedOrderWins() =
        assertEquals(listOf("library", "type:trakt", "shows", "movies"),
            orderTabs(listOf("library", "type:trakt", "shows", "movies"), listOf("shows", "movies", "type:trakt", "library")))

    @Test fun aNewAddonsTabGoesAfterTheTabItFollows() =
        assertEquals(listOf("library", "shows", "movies", "type:marvel"),
            orderTabs(listOf("library", "shows", "movies"), listOf("shows", "movies", "type:marvel", "library")))

    @Test fun aNewFirstTabGoesFirst() =
        assertEquals(listOf("shows", "movies", "library"), orderTabs(listOf("movies", "library"), listOf("shows", "movies", "library")))

    @Test fun aRemovedAddonsTabIsLeftOut() =
        assertEquals(listOf("shows", "movies", "library"),
            orderTabs(listOf("type:old", "shows", "movies", "library", "shows"), listOf("shows", "movies", "library")))
}
