package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDesignTest {
    @Test fun posterGlowUsesArtAndCapsBrightPosters() {
        assertEquals(0x8CFF0000.toInt(), filmGlow(intArrayOf(0xFFFF0000.toInt())))
        assertEquals(0x8CB2B2B2.toInt(), filmGlow(intArrayOf(0xFFFFFFFF.toInt())))
    }

    @Test fun shadeRespondsToBrightnessOnTheTextSide() {
        assertEquals(true, brightLeft(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2))
        assertEquals(false, brightLeft(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2))
    }

    @Test fun homeSynopsisEndsAtACompleteSentenceWhenClipped() {
        assertEquals("First sentence.", synopsisAtSentence("First sentence. The second is longer.", 23))
        assertEquals("No…", synopsisAtSentence("No punctuation here", 7))
        val city = "Sgt. Wayne Jenkins leads a unit. It goes wrong."
        assertEquals("Sgt. Wayne Jenkins…", synopsisAtSentence(city, 20))
        assertEquals("Sgt. Wayne Jenkins leads a unit.", synopsisAtSentence(city, 40))
        assertEquals("Agents in the U.S. hunt a spy.", synopsisAtSentence("Agents in the U.S. hunt a spy. Then more.", 35))
        assertEquals("Find the remaining Horcruxes…", synopsisAtSentence("Find the remaining Horcruxes, and return.", 30))
    }

    @Test fun languageCodesFromEverySourceMatch() {
        listOf("es", "spa", "spl", "es-MX", "Spanish").forEach { assertEquals("es", Languages.code(it)) }
        assertEquals("de", Languages.code("ger"))
        assertEquals("fr", Languages.code("fre"))
        assertEquals("nl", Languages.code("dut"))
        listOf("pob", "pt-BR", "pt_br", "por-BR", "Portuguese (Brazil)").forEach { assertEquals("pt-BR", Languages.code(it)) }
        listOf("pt", "por", "pom", "pt-PT", "Portuguese").forEach { assertEquals("pt", Languages.code(it)) }
        assertEquals(2, Languages.match("pob", "pt-BR"))
        assertEquals(1, Languages.match("por", "pt-BR"))
        assertEquals(0, Languages.match("spa", "pt-BR"))
        assertEquals("Portuguese (Brazil)", Languages.name("pob"))
        assertEquals("he", Languages.code("heb"))
        assertEquals(null, Languages.code("und"))
        assertEquals(true, Languages.same("eng", "en"))
        assertEquals(false, Languages.same("und", "und"))
        assertEquals(listOf("en", "es"), Languages.all.take(2))
    }

    @Test fun audioLanguagesFromFlagsNamesAndTags() {
        assertEquals(setOf("en", "it"), audioLanguagesIn("👤 52 💾 4.2 GB ⚙️ ThePirateBay 🇬🇧 / 🇮🇹"))
        assertEquals(setOf("en", "es"), audioLanguagesIn("🇺🇸🇪🇸")) // not also Sweden from the middle letters
        assertEquals(setOf("en", "hi"), audioLanguagesIn("🌐 English | Hindi"))
        assertEquals(setOf("it"), audioLanguagesIn("Movie.2024.1080p.BluRay.ITA.x264"))
        assertEquals(setOf("fr"), audioLanguagesIn("Movie 2024 TRUEFRENCH 2160p WEB"))
        assertEquals(emptySet<String>(), audioLanguagesIn("Movie.2024.2160p.WEB-DL.DV.HDR.H265"))
    }

    @Test fun whatsNewReadsOneVersionsEntries() {
        val log = "# Changelog\n\n- a rule, not a change\n\n## Unreleased\n\n- Next\n\n## 1.2\n\n### New\n- **One.** First\n\n### Fixes\n- Two\n\n## 1.1\n\n- Old\n"
        assertEquals(listOf("**One.** First", "Two"), changesFor(log, "1.2").map { it.text })
        assertEquals(listOf("New", "Fixes"), changesFor(log, "1.2").map { it.group })
        assertEquals(listOf(null), changesFor(log, "Unreleased").map { it.group })
        assertEquals(0, changesFor(log, "1.3").size)
    }

    @Test fun whatsNewHighlightsFollowEditorialOrderAcrossGroups() {
        val log = """## Unreleased
            |### New
            |<!-- Spotlight -->
            |- **Trailers.** Play a trailer.
            |- **30 GB limit.** Choose a limit.
            |<!-- Spotlight -->
            |- **Live TV.** Watch a channel.
            |### Easier to use
            |<!-- Spotlight -->
            |- **Direct play.** Select Play.
            |### Fixes
            |- **Back.** Close the panel.
        """.trimMargin()
        val changes = changesFor(log, "Unreleased")
        assertEquals(listOf("Trailers", "Live TV", "Direct play"), showcaseHighlights(changes).map { it.title })
        assertEquals(listOf("New", "New", "Easier to use"), showcaseHighlights(changes).map { it.group })
        assertEquals(5, changes.size)
        assertEquals(listOf("Trailers", "30 GB limit", "Live TV", "Direct play", "Back"),
            showcaseHighlights(changes.map { Change(it.group, it.text) }).map { it.title })
    }

    @Test fun debridServiceFromTagsAndLinks() {
        fun of(name: String, url: String = "https://example.com/v.mkv") = debridOf(name, url)?.let { "${it.short} ${it.cached}" }
        assertEquals("RD true", of("[RD+] Torrentio\n4k"))
        assertEquals("RD false", of("[RD download] Torrentio\n1080p"))
        assertEquals("TB true", of("[TB⚡] Comet 2160p"))
        assertEquals("AD false", of("[AD⏳] AIOStreams"))
        assertEquals("RD true", of("MediaFusion ⚡RD 4K"))
        assertEquals("PM null", of("Torrentio\n4k", "https://torrentio.strem.fun/resolve/premiumize/key/hash/null/0/v.mkv"))
        assertEquals("RD null", of("Direct", "https://abc.download.real-debrid.com/d/X/v.mkv"))
        assertEquals(null, of("Movie.2024.1080p.WEB-DL.DDP5.1")) // DL and DD aren't services without a tag
        assertEquals(null, of("[HDR] 4K"))
    }

    @Test fun homeRecommendationRowHasTheShortLabel() {
        assertEquals("More like Dune", homeRowName("Because you watched Dune"))
        assertEquals("More like Crayon Shin-chan", homeRowName("Because you watched Crayon Shin-chan: Pursuit"))
        assertEquals("Popular", homeRowName("Popular"))
    }

    @Test fun seasonCountIsSingularForOne() {
        assertEquals("1 season", seasons(1))
        assertEquals("5 seasons", seasons(5))
    }

    @Test fun catalogGenresUseAvailableOptionsInScreenOrder() {
        assertEquals(listOf("Drama", "Comedy", "Sci-Fi", "Western"),
            catalogGenres(listOf("Western", "Comedy", "All", "Drama", "Sci-Fi", "drama")))
    }

    @Test fun genreStripWaitsForAllRowsAndKeepsOnlyRowsWithTitles() {
        val names = listOf("Drama", "Comedy", "Sci-Fi")
        assertEquals(null, settledGenres(names, mapOf("Drama" to true, "Comedy" to false)))
        assertEquals(listOf("Drama", "Sci-Fi"), settledGenres(names,
            mapOf("Drama" to true, "Comedy" to false, "Sci-Fi" to true)))
        assertEquals(emptyList<String>(), settledGenres(names, names.associateWith { false }))
    }

    @Test fun catalogRowsHaveShortNames() {
        assertEquals("For you", shortRowName("Top Picks for You"))
        assertEquals("Popular", shortRowName("Trending Now"))
        assertEquals("Drama", shortRowName("Drama"))
        assertEquals("Recommended in India", shortRowName("Recommended in India"))
        assertEquals("Comedy Sci-Fi", shortRowName("Comedy Science Fiction"))
        assertEquals("Adventure", shortRowName("Short & Sweet Adventure", listOf("Drama", "Adventure")))
    }

    @Test fun homeRowsIdentifyMoviesAndShows() {
        assertEquals("Popular Movies", homeSectionTitle("Popular", true))
        assertEquals("Popular Shows", homeSectionTitle("Popular", false))
        assertEquals("Top Movies", homeSectionTitle("Top", true))
        assertEquals("Top Shows", homeSectionTitle("Top", false))
        assertEquals("Top Movie Picks", homeSectionTitle("Top Picks for You", true))
        assertEquals("Top Show Picks", homeSectionTitle("Top Picks for You", false))
        assertEquals("Recommended Movies", homeSectionTitle("Recommended for You", true))
        assertEquals("Recommended Shows", homeSectionTitle("Recommended for You", false))
        assertEquals("Trending Movies", homeSectionTitle("Trending Now", true))
        assertEquals("Trending Shows", homeSectionTitle("Trending Now", false))
        assertEquals("Trending Movies in India", homeSectionTitle("Trending in India", true))
        assertEquals("Popular Movies on Netflix", homeSectionTitle("Popular on Netflix", true))
        assertEquals("Action Movies", homeSectionTitle("Action", true))
        assertEquals("Action Shows", homeSectionTitle("Action", false))
        assertEquals("Popular - Movies", homeSectionTitle("Popular - Movies", true))
    }

    @Test fun hiddenRowsFilterOutCatalogs() {
        val allRows = listOf("Popular Movies", "Trending Shows", "Action Movies")
        val hidden = setOf("Trending Shows")
        val visible = allRows.filterNot { it in hidden }
        assertEquals(listOf("Popular Movies", "Action Movies"), visible)
    }

    @Test fun runtimeReadsInHoursAndMinutes() {
        assertEquals("2h 27m", runtime("147 min"))
        assertEquals("1h 05m", runtime("65 min"))
        assertEquals("45 min", runtime("45 min"))
        assertEquals("1h 30m", runtime("1h 30min"))
        assertEquals("2h 35m", runtime("2h35min"))
    }

    @Test fun addonStatusIsNotATitle() {
        assertEquals(true, isAddonMessage("Trakt Authentication Required"))
        assertEquals(false, isAddonMessage("The Authentication"))
    }

    @Test fun myListKeepsTheLastPartialRow() {
        assertEquals(0, listRowCount(0, 6))
        assertEquals(1, listRowCount(6, 6))
        assertEquals(2, listRowCount(7, 6))
        assertEquals(4, listRowCount(24, 6))
    }

    @Test fun catalogsContinueAfterTheFirstTwentyInSmallBatches() {
        assertEquals(4, nextCatalogSection(37, 0))
        assertEquals(8, nextCatalogSection(37, 4))
        assertEquals(37, nextCatalogSection(37, 36))
    }

    @Test fun catalogAnswersAreNotReusedAfterTheirSourceChanges() {
        val first = catalogAnswerKey("Shows", "Popular", null, listOf("addon-a/series/popular"))
        assertEquals(first, catalogAnswerKey("Shows", "Popular", null, listOf("addon-a/series/popular")))
        assertEquals(false, first == catalogAnswerKey("Shows", "Popular", null, listOf("addon-b/series/popular")))
        assertEquals(false, first == catalogAnswerKey("Shows", "Popular", "Drama", listOf("addon-a/series/popular")))
    }

    @Test fun shortEpisodePlaceholdersCannotAdvanceTheSeries() {
        assertEquals(false, plausibleEpisode(30_000L))
        assertEquals(false, plausibleEpisode(119_999L))
        assertEquals(true, plausibleEpisode(120_000L))
    }

    @Test fun unwatchedEpisodeThumbnailsBlurWhenEnabled() {
        val watched = setOf("tt123:1:1", "tt123:1:2")
        val shouldBlur = { enabled: Boolean, videoId: String -> enabled && videoId !in watched }
        assertEquals(true, shouldBlur(true, "tt123:1:3"))
        assertEquals(false, shouldBlur(true, "tt123:1:1"))
        assertEquals(false, shouldBlur(false, "tt123:1:3"))
        assertEquals(false, shouldBlur(false, "tt123:1:1"))
    }

    @Test fun settingsChoicesWrapAndCustomStartsAtTheEndForLeft() {
        assertEquals(0, nextSettingIndex(-1, 4, 1))
        assertEquals(3, nextSettingIndex(-1, 4, -1))
        assertEquals(0, nextSettingIndex(3, 4, 1))
        assertEquals(3, nextSettingIndex(0, 4, -1))
    }

    @Test fun searchRanksTitleMatchesAndKeepsTiesInAddonOrder() {
        val titles = listOf("Dune Part Two", "Arrival", " DUNE ", "Dune Messiah", "dune")
        assertEquals(listOf(" DUNE ", "dune", "Dune Part Two", "Dune Messiah", "Arrival"),
            rankSearchTitles(titles, "Dune") { it })
        assertEquals(listOf("Amelie", "Amélie Returns", "Arrival"),
            rankSearchTitles(listOf("Arrival", "Amélie Returns", "Amelie"), "Amélie") { it })
    }

    @Test fun partialSearchAnswersKeepAddonOrder() {
        val answers = arrayOf<List<String>?>(null, listOf("later"))
        assertEquals(listOf("later"), searchAnswersInAddonOrder(answers))
        answers[0] = listOf("first")
        assertEquals(listOf("first", "later"), searchAnswersInAddonOrder(answers))
    }

    @Test fun episodeDateUsesOnlyValidAvailableDates() {
        assertEquals("25 Sep 2013", episodeDate("2013-09-25T00:00:00.000Z", java.util.Locale.US))
        assertEquals(null, episodeDate("2013-02-30"))
        assertEquals(null, episodeDate(null))
    }

    @Test fun unairedEpisodesAreProtectedAndBadged() {
        val fixedNow = 1793275200000L // 2026-10-29 12:00:00 UTC

        // Null / blank / malformed dates count as aired (never locked out)
        assertEquals(true, isEpisodeAired(null, fixedNow))
        assertEquals(true, isEpisodeAired("", fixedNow))
        assertEquals(true, isEpisodeAired("not-a-date", fixedNow))
        assertEquals(true, isEpisodeAired("2026-02-30", fixedNow))

        // Past dates are aired
        assertEquals(true, isEpisodeAired("2026-10-28", fixedNow))
        assertEquals(true, isEpisodeAired("2026-10-29", fixedNow))
        assertEquals(true, isEpisodeAired("2026-10-29T10:00:00.000Z", fixedNow))

        // Future dates are unaired
        assertEquals(false, isEpisodeAired("2026-11-14", fixedNow))
        assertEquals(false, isEpisodeAired("2026-11-14T00:00:00.000Z", fixedNow))

        // Air date labels
        assertEquals("14 Nov", airDay("2026-11-14", java.util.Locale.US))
        assertEquals("14 Nov", airDay("2026-11-14T00:00:00.000Z", java.util.Locale.US))
        assertEquals(null, airDay(null))

        // Recently aired: within the last 7 days
        assertEquals(true, isRecentlyAired("2026-10-29", fixedNow)) // today
        assertEquals(true, isRecentlyAired("2026-10-25", fixedNow)) // 4 days ago
        assertEquals(true, isRecentlyAired("2026-10-22", fixedNow)) // 7 days ago
        assertEquals(false, isRecentlyAired("2026-10-15", fixedNow)) // 14 days ago
        assertEquals(false, isRecentlyAired("2026-11-14", fixedNow)) // future
    }

    @Test fun repeatedBufferingOffersOneRealQualityStep() {
        assertEquals(1080, lowerSourceHeight(2160))
        assertEquals(720, lowerSourceHeight(1080))
        assertEquals(null, lowerSourceHeight(480))
    }

    @Test fun upNextNeverInterruptsMoreThanTheFinalMinute() {
        assertEquals(15_000L, upNextWindow(10 * 60_000L))
        assertEquals(25_000L, upNextWindow(60 * 60_000L))
    }

    @Test fun sourceRowsUseFileFactsWithoutShowingAddonDescriptions() {
        val stream = Stream("Torrentio (1080p)", "extra text", "https://example.invalid/video", emptyMap(),
            405_000_000L, "First.Days.1080p.BluRay.HEVC.5.1.mkv", null)
        assertEquals(1080, stream.height)
        assertEquals("BluRay · HEVC · 5.1", sourceSummary(stream))
        assertEquals("405 MB", sourceSize(stream.size))
        assertEquals(480, Stream("480p", "", "https://example.invalid/low", emptyMap(), 175_000_000L, null, null).height)
    }

    @Test fun sourceSelectionKeepsAddonOrderUntilItsShortWaitEnds() {
        val first = Stream("first", "", "https://example.invalid/first", emptyMap(), 0, null, null)
        val later = Stream("later", "", "https://example.invalid/later", emptyMap(), 0, null, null)
        val answers = arrayOf<List<Stream>?>(null, listOf(later))
        assertEquals(emptyList<Stream>(), readyStreams(answers, false))
        assertEquals(listOf(later), readyStreams(answers, true))
        answers[0] = listOf(first)
        assertEquals(listOf(first, later), readyStreams(answers, false))
    }

    @Test fun addonRowsUseShortNamesAndActualResources() {
        assertEquals("Watchly", addonShortName("Watchly | ElfHosted"))
        assertEquals("OpenSubtitles", addonShortName("OpenSubtitles v3"))
        assertEquals("Local Files", addonShortName("Local Files (without catalog support)"))
        assertEquals("Syncribullet", addonShortName("syncribullet"))
        assertEquals("Files on this network", addonRole("Local Files", "org.stremio.local", setOf("stream", "meta")))
        assertEquals("third", ordinal(3))
        assertEquals("11th", ordinal(11))
        assertEquals("22nd", ordinal(22))
        assertEquals("Recommendations · Top Picks on Home", addonRole("Watchly", "com.watchly", setOf("catalog")))
        assertEquals("Subtitles", addonRole("OpenSubtitles", "org.opensubtitles", setOf("subtitles")))
        assertEquals("Streams · Catalogs", addonRole("Example", "org.example", setOf("stream", "catalog")))
        assertEquals("Titles and details", addonRole("Cinemeta", "com.cinemeta", setOf("catalog", "meta")))
    }

    @Test fun liveTypesAreChannelsSportAndEventsOnly() {
        listOf("tv", "TV", "live", "sport", "Sports", "events").forEach { assertEquals(true, isLiveType(it)) }
        listOf("movie", "series", "anime", "channel", "other").forEach { assertEquals(false, isLiveType(it)) }
    }

    @Test fun logoTintTakesTheColourfulPixelsAndSkipsPlainLogos() {
        val red = 0xFFE00000.toInt()
        val clear = 0x00000000
        val white = 0xFFFFFFFF.toInt()
        assertEquals(0xFF780000.toInt(), logoTint(intArrayOf(red, red, clear, white)))
        assertEquals(null, logoTint(intArrayOf(white, white, 0xFF000000.toInt(), clear)))
        assertEquals(null, logoTint(intArrayOf(clear, clear)))
    }

    @Test fun episodeNavigationOrderAndPriorEpisodes() {
        val e0 = Video("s0e1", "Special", 0, 1, null, null, null)
        val e1 = Video("s1e1", "Pilot", 1, 1, null, null, null)
        val e2 = Video("s1e2", "Second", 1, 2, null, null, null)
        val e3 = Video("s2e1", "Season 2 Opener", 2, 1, null, null, null)
        val videos = listOf(e0, e1, e2, e3)

        assertEquals(emptyList<Video>(), Library.priorEpisodes(videos, e1))
        assertEquals(listOf(e1), Library.priorEpisodes(videos, e2))
        assertEquals(listOf(e1, e2), Library.priorEpisodes(videos, e3))
        assertEquals(emptyList<Video>(), Library.priorEpisodes(videos, e0))

        assertEquals(e2, Library.nextEpisode(videos, e1))
        assertEquals(e3, Library.nextEpisode(videos, e2))
        assertEquals(null, Library.nextEpisode(videos, e3))

        assertEquals(e3, Library.nextEpisodeAfterSeason(videos, 1))
        assertEquals(null, Library.nextEpisodeAfterSeason(videos, 2))
    }

    @Test fun subtitleTimingLabelsFormatCorrectly() {
        assertEquals("On time", formatTiming(0L))
        assertEquals("-0.25 s  earlier", formatTiming(-250L))
        assertEquals("-1.00 s  earlier", formatTiming(-1000L))
        assertEquals("+0.25 s  later", formatTiming(250L))
        assertEquals("+0.50 s  later", formatTiming(500L))
        assertEquals("+2.50 s  later", formatTiming(2500L))
    }

    @Test fun smartPinningOrdersOffActivePreferredAndRecents() {
        val tracks = listOf(
            TrackChoice("off", "Off", isOff = true),
            TrackChoice("en", "English", langCode = "en", isSelected = true),
            TrackChoice("es", "Spanish", langCode = "es"),
            TrackChoice("fr", "French", langCode = "fr"),
            TrackChoice("de", "German", langCode = "de"),
            TrackChoice("it", "Italian", langCode = "it")
        )
        val (pinned, more) = partitionPinnedTracks(tracks, preferredLang = "es", recentLangs = listOf("fr", "de"))
        assertEquals(listOf("off", "en", "es", "fr", "de"), pinned.map { it.id })
        assertEquals(listOf("it"), more.map { it.id })
    }

    @Test fun smartPinningDoesNotDuplicateWhenActiveMatchesPreferredOrRecent() {
        val tracks = listOf(
            TrackChoice("off", "Off", isOff = true),
            TrackChoice("en", "English", langCode = "en", isSelected = true),
            TrackChoice("es", "Spanish", langCode = "es")
        )
        val (pinned, more) = partitionPinnedTracks(tracks, preferredLang = "en", recentLangs = listOf("en", "es"))
        assertEquals(listOf("off", "en", "es"), pinned.map { it.id })
        assertEquals(emptyList<String>(), more.map { it.id })
    }

    @Test fun smartPinningForAudioTracksWithoutOff() {
        val tracks = listOf(
            TrackChoice("a1", "English 5.1", langCode = "en", isSelected = true),
            TrackChoice("a2", "Spanish 2.0", langCode = "es"),
            TrackChoice("a3", "French 5.1", langCode = "fr")
        )
        val (pinned, more) = partitionPinnedTracks(tracks, preferredLang = "fr", recentLangs = emptyList())
        assertEquals(listOf("a1", "a3"), pinned.map { it.id })
        assertEquals(listOf("a2"), more.map { it.id })
    }

    @Test fun smartPinningPreservesAllMoreTracksWhenAddonSubtitlesArrive() {
        val initialTracks = listOf(
            TrackChoice("sub:0", "Off", isOff = true),
            TrackChoice("sub:1", "English", "in the file", langCode = "en", isSelected = true)
        )
        val (initialPinned, initialMore) = partitionPinnedTracks(initialTracks, preferredLang = "en", recentLangs = emptyList())
        assertEquals(listOf("sub:0", "sub:1"), initialPinned.map { it.id })
        assertEquals(emptyList<String>(), initialMore.map { it.id })

        val updatedWithAddons = initialTracks + listOf(
            TrackChoice("sub:2", "English", "OpenSubtitles", langCode = "en"),
            TrackChoice("sub:3", "Spanish", "OpenSubtitles", langCode = "es"),
            TrackChoice("sub:4", "French", "OpenSubtitles", langCode = "fr"),
            TrackChoice("sub:5", "German", "OpenSubtitles", langCode = "de")
        )
        val (updatedPinned, updatedMore) = partitionPinnedTracks(updatedWithAddons, preferredLang = "en", recentLangs = emptyList())
        assertEquals(listOf("sub:0", "sub:1", "sub:2"), updatedPinned.map { it.id })
        assertEquals(listOf("sub:3", "sub:4", "sub:5"), updatedMore.map { it.id })
    }

    @Test fun recentLanguagesUpdatesAndCapsToThree() {
        assertEquals(listOf("en"), updateRecentLanguages(emptyList(), "en"))
        assertEquals(listOf("es", "en"), updateRecentLanguages(listOf("en"), "es"))
        assertEquals(listOf("fr", "es", "en"), updateRecentLanguages(listOf("es", "en"), "fr"))
        assertEquals(listOf("de", "fr", "es"), updateRecentLanguages(listOf("fr", "es", "en"), "de"))
        // Existing language moves to front without duplicating
        assertEquals(listOf("es", "de", "fr"), updateRecentLanguages(listOf("de", "fr", "es"), "es"))
        // Undefined language is ignored
        assertEquals(listOf("es", "de", "fr"), updateRecentLanguages(listOf("es", "de", "fr"), "und"))
        assertEquals(listOf("es", "de", "fr"), updateRecentLanguages(listOf("es", "de", "fr"), ""))
    }

    @Test fun chunkGridSplitsTracksIntoColumns() {
        val items = listOf("1", "2", "3", "4", "5", "6", "7")
        val grid = chunkGrid(items, 3)
        assertEquals(3, grid.size)
        assertEquals(listOf("1", "2", "3"), grid[0])
        assertEquals(listOf("4", "5", "6"), grid[1])
        assertEquals(listOf("7"), grid[2])
    }

    @Test fun trailerStreamsAndYoutubeIdsParseFromMetadataAndImdb() {
        assertEquals(0, trailerNodePriority("Trailer", "Clip 1"))
        assertEquals(1, trailerNodePriority("Clip", "Official Trailer"))
        assertEquals(2, trailerNodePriority("Clip", "Behind the Scenes"))

        val ordered = orderTrailerStreams(
            "Official Trailer",
            listOf(
                "480p" to "https://cdn.example/t480.mp4",
                "1080p" to "https://cdn.example/t1080.mp4",
                "AUTO" to "https://cdn.example/master.m3u8",
                "1080p" to "https://cdn.example/t1080.mp4"
            )
        )
        assertEquals(
            listOf("https://cdn.example/t1080.mp4", "https://cdn.example/master.m3u8", "https://cdn.example/t480.mp4"),
            ordered.map { it.url }
        )
        assertEquals(
            listOf("Official Trailer (1080p)", "Official Trailer", "Official Trailer (480p)"),
            ordered.map { it.title }
        )
    }

    @Test fun contentRatingParsesFromImdbAndMeta() {
        assertEquals("TV-MA", parseImdbCertificate(org.json.JSONObject("""{"data":{"title":{"certificate":{"rating":"TV-MA"}}}}""")))
        assertEquals(null, parseImdbCertificate(org.json.JSONObject("""{"data":{"title":{"certificate":null}}}""")))
        assertEquals("PG-13", Meta(org.json.JSONObject("""{"id":"tt1","certification":"PG-13"}""")).certification)
        assertEquals("R", Meta(org.json.JSONObject("""{"id":"tt1","contentRating":"R"}""")).certification)
        assertEquals(null, Meta(org.json.JSONObject("""{"id":"tt1"}""")).certification)
    }

    @Test fun playerPauseFactsAndBrandBadges() {
        assertEquals("2025  ·  Ends 7:18 PM", playerPauseFacts("2025", "7:18 PM", isTrailer = false, isLive = false))
        assertEquals("S5 E2  ·  Ends 8:56 PM", playerPauseFacts("S5 E2", "8:56 PM", isTrailer = false, isLive = false))
        assertEquals("Trailer", playerPauseFacts("Trailer", "7:18 PM", isTrailer = true, isLive = false))
        assertEquals("2025", playerPauseFacts("2025", "7:18 PM", isTrailer = false, isLive = true))

        val dvAtmosRd = Stream(
            "[RD+] Torrentio (2160p)",
            "Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.DDP5.1.Atmos.H265.mkv",
            "https://example.invalid/dune.mkv",
            emptyMap(),
            18_000_000_000L,
            "Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.DDP5.1.Atmos.H265.mkv",
            null,
        )
        assertEquals(
            listOf(
                PlayerBadge(BadgeBrand.NONE, "4K", accent = true),
                PlayerBadge(BadgeBrand.DOLBY, "VISION", accent = true),
                PlayerBadge(BadgeBrand.DOLBY, "ATMOS", accent = true),
                PlayerBadge(BadgeBrand.NONE, "ENGLISH 5.1"),
                PlayerBadge(BadgeBrand.SUBTITLES, "ENGLISH"),
                PlayerBadge(BadgeBrand.DEBRID, "RD", code = "RD ⚡", accent = true),
            ),
            playerBadges(dvAtmosRd, audioLanguage = "en", subtitleLanguage = "en"),
        )

        val dtsRemuxTb = Stream(
            "[TB⚡] Comet 1080p",
            "The.Raid.2011.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1.mkv",
            "https://example.invalid/raid.mkv",
            emptyMap(),
            24_000_000_000L,
            "The.Raid.2011.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1.mkv",
            null,
        )
        assertEquals(
            listOf(
                PlayerBadge(BadgeBrand.NONE, "1080p"),
                PlayerBadge(BadgeBrand.DTS, "HD"),
                PlayerBadge(BadgeBrand.NONE, "INDONESIAN 5.1"),
                PlayerBadge(BadgeBrand.SUBTITLES, "ENGLISH"),
                PlayerBadge(BadgeBrand.NONE, "REMUX"),
                PlayerBadge(BadgeBrand.DEBRID, "TB", code = "TB ⚡", accent = true),
            ),
            playerBadges(dtsRemuxTb, audioLanguage = "id", audioChannels = 6, subtitleLanguage = "eng"),
        )
    }

    @Test fun playerBadgesEmptyWhenNoStreamOrTracks() {
        assertEquals(emptyList<PlayerBadge>(), playerBadges(null))
    }

    @Test fun playerPauseFactsHandlesLoadingStates() {
        assertEquals("", playerPauseFacts("", null, isTrailer = false, isLive = false))
        assertEquals("S1 E1", playerPauseFacts("S1 E1", null, isTrailer = false, isLive = false))
        assertEquals("S1 E1  ·  Ends 10:00 PM", playerPauseFacts("S1 E1", "10:00 PM", isTrailer = false, isLive = false))
    }

    @Test fun subtitleColorResolvesCorrectly() {
        assertEquals(0xFFFFFFFF.toInt(), subtitleColorValue("white"))
        assertEquals(0xFFFFE600.toInt(), subtitleColorValue("yellow"))
        assertEquals(0xFF00FFFF.toInt(), subtitleColorValue("cyan"))
        assertEquals(0xFFFFF5C0.toInt(), subtitleColorValue("cream"))
        assertEquals(0xFFF3EEE6.toInt(), subtitleColorValue("warm_white"))
        assertEquals(0xFFF3EEE6.toInt(), subtitleColorValue("warm white"))
        assertEquals(0xFFFFFFFF.toInt(), subtitleColorValue("unknown"))
        assertEquals(0xFFFFFFFF.toInt(), subtitleColorValue(""))
    }
}
