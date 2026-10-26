package cz.plain.tvstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class IcyTest {
    @Test fun splitsOnlyOnSpacedDash() {
        val p = IcyParser.parse("Jay-Z - 99 Problems - Remix")
        assertEquals("Jay-Z", p.artist); assertEquals("99 Problems - Remix", p.title); assertTrue(p.isTrack)
    }
    @Test fun noDashMeansTitleOnly() {
        val p = IcyParser.parse("Hip-Hop Hits")
        assertNull(p.artist); assertEquals("Hip-Hop Hits", p.title)
    }
    @Test fun stationNameAndUrlsAreNotTracks() {
        assertFalse(IcyParser.parse("Radio Wave", "Rádio Wave").isTrack)
        assertFalse(IcyParser.parse("www.example.com").isTrack)
        assertFalse(IcyParser.parse("Reklama - Spot").isTrack)
    }
    @Test fun streamTitleBlockWithApostrophe() {
        assertEquals("Guns N' Roses - Paradise City", IcyParser.streamTitle("StreamTitle='Guns N' Roses - Paradise City';StreamUrl='';"))
    }
    @Test fun fixesLatin1Mojibake() {
        assertEquals("Dvořák", IcyParser.fixEncoding(String("Dvořák".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
    }
    @Test fun trackerIgnoresDuplicatesAndResets() {
        val t = NowPlayingTracker("s", "Station")
        assertTrue(t.onIcy("A - B", 1000))
        assertFalse(t.onIcy("A - B", 2000))
        assertEquals("B", t.current.trackTitle); assertEquals("A", t.current.artist)
        t.reset(); assertFalse(t.current.hasTrack)
    }
    @Test fun programDoesNotOverrideTrack() {
        val t = NowPlayingTracker("s", "Station")
        t.onIcy("A - B", 1000); t.onProgram("Ranní show")
        assertEquals("B", t.current.trackTitle); assertEquals("Ranní show", t.current.programTitle)
    }
    @Test fun staleTrackDetected() {
        val n = NowPlaying("s", "S", trackTitle = "x", startedAt = 1000, duration = 1000)
        assertTrue(n.isStale(1000 + 1000 + 100_000)); assertFalse(n.isStale(1500))
    }
}

class FftTest {
    @Test fun sineLandsInExpectedBin() {
        val fft = Fft(1024)
        val sr = 44100; val f = 1000.0
        val s = FloatArray(1024) { sin(2 * PI * f * it / sr).toFloat() }
        val out = FloatArray(512); fft.spectrum(s, out)
        val peak = out.indices.maxByOrNull { out[it] }!!
        val expected = (f / sr * 1024).toInt()
        assertTrue("peak $peak vs $expected", kotlin.math.abs(peak - expected) <= 1)
    }
    @Test fun silenceIsFlat() {
        val out = FloatArray(512); Fft(1024).spectrum(FloatArray(1024), out)
        assertTrue(out.all { it < 1e-6f })
    }
    @Test fun rmsOfFullScaleSine() {
        val s = FloatArray(1024) { sin(2 * PI * it / 64).toFloat() }
        assertEquals(0.707f, Fft.rms(s, 1024), 0.02f)
    }
}

class SortingTest {
    private class T(override val name: String, override val number: Int, override val index: Int, override val favorite: Boolean = false, override val lastPlayed: Long = 0, override val plays: Int = 0) : Sortable

    @Test fun czechCollationPutsChAfterH() {
        val l = listOf(T("Cyklo", 0, 0), T("Chata", 0, 1), T("Hudba", 0, 2), T("Ceska", 0, 3))
        val r = Sorting.sort(l, SortMode.NAME_ASC).map { it.name }
        assertEquals(listOf("Ceska", "Cyklo", "Hudba", "Chata"), r)
    }
    @Test fun diacriticsIgnored() { assertEquals(Sorting.fold("Čt"), Sorting.fold("ct")) }
    @Test fun numberZeroGoesLastInBothDirections() {
        val l = listOf(T("a", 0, 0), T("b", 5, 1), T("c", 2, 2))
        assertEquals(listOf("c", "b", "a"), Sorting.sort(l, SortMode.NUMBER_ASC).map { it.name })
        assertEquals(listOf("b", "c", "a"), Sorting.sort(l, SortMode.NUMBER_DESC).map { it.name })
    }
    @Test fun stableForEqualKeys() {
        val l = listOf(T("x", 1, 2), T("x", 1, 0), T("x", 1, 1))
        assertEquals(listOf(0, 1, 2), Sorting.sort(l, SortMode.NAME_ASC).map { it.index })
    }
    @Test fun favoritesFirst() {
        val l = listOf(T("a", 1, 0), T("b", 2, 1, favorite = true))
        assertEquals("b", Sorting.sort(l, SortMode.FAV_FIRST).first().name)
    }
    @Test fun filtersActive() { assertFalse(Filters().active); assertTrue(Filters(epgOnly = true).active) }
}

class ClassifyTest {
    @Test fun detectsRadioByNameAndCategory() {
        assertEquals(ChannelKind.RADIO, Classify.kind("ČRo Vltava", emptyList()))
        assertEquals(ChannelKind.RADIO, Classify.kind("Evropa 2", listOf("Radio stanice")))
        assertEquals(ChannelKind.RADIO, Classify.kind("Radio Impuls", emptyList()))
        assertEquals(ChannelKind.TV, Classify.kind("Radio TV Cinema", emptyList()))
        assertEquals(ChannelKind.TV, Classify.kind("ČT1 HD", listOf("Czech")))
    }
    @Test fun manualHintWins() {
        assertEquals(ChannelKind.RADIO, Classify.kind("ČT1 HD", emptyList(), hint = true))
        assertEquals(ChannelKind.TV, Classify.kind("Radio Wave", emptyList(), hint = false))
    }
    @Test fun metaFromName() {
        val m = ChannelMeta.of("CZ: Nova Cinema FHD")
        assertEquals("FHD", m.quality); assertEquals("CZ", m.language)
    }
}

class XmltvTest {
    @Test fun parsesTimeWithZone() {
        assertEquals(Xmltv.time("20261025213000 +0000"), Xmltv.time("20261025233000 +0200"))
    }
    @Test fun rejectsGarbage() { assertNull(Xmltv.time("x")); assertNull(Xmltv.time(null)) }
    @Test fun cleansAndTruncates() { assertEquals("a b", Xmltv.clean("  a \n  b ", 10)); assertEquals(3, Xmltv.clean("abcdef", 3).length) }
}

class CoverMatchTest {
    @Test fun acceptsMatchingResult() { assertTrue(CoverMatch.accept("Ben Bohmer", "Rust", "Ben Böhmer", "Rust")) }
    @Test fun rejectsDifferentArtist() { assertFalse(CoverMatch.accept("Ben Bohmer", "Rust", "Someone Else", "Rust")) }
}

class RedactTest {
    @Test fun hidesXtreamPathCredentials() {
        val r = Redact.url("http://h:8080/live/user1/secretpw/123.m3u8")
        assertFalse(r.contains("secretpw")); assertFalse(r.contains("user1"))
    }
    @Test fun hidesQueryCredentials() {
        val r = Redact.url("http://h/player_api.php?username=bob&password=hunter2&action=x")
        assertFalse(r.contains("hunter2")); assertFalse(r.contains("bob")); assertTrue(r.contains("action=x"))
    }
    @Test fun hidesMacInQuery() { assertFalse(Redact.url("http://h/server/load.php?mac=00:1A:79:AA:BB:CC&x=1").contains("00:1A")) }
    @Test fun hidesUserInfo() { assertFalse(Redact.url("http://u:p@host/x").contains("u:p")) }
}

class StalkerProtoTest {
    @Test fun loadUrlVariants() {
        assertEquals("http://h/server/load.php", StalkerProto.loadUrl("http://h/c/"))
        assertEquals("http://h/stalker_portal/server/load.php", StalkerProto.loadUrl("http://h/stalker_portal/c"))
    }
    @Test fun candidatesIncludePortalPhp() { assertTrue(StalkerProto.loadUrls("http://h:80/c/").contains("http://h:80/portal.php")) }
    @Test fun cleansCmd() {
        assertEquals("http://x/y", StalkerProto.cleanCmd("ffmpeg http://x/y"))
        assertEquals("http://x/y", StalkerProto.cleanCmd("http://x/y"))
    }
    @Test fun macHandling() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerProto.normalizeMac("00-1a-79-aa-bb-cc".replace('-', ':')))
        assertEquals("00:1A:79:AA:BB:CC", StalkerProto.normalizeMac("001a79aabbcc"))
        assertTrue(StalkerProto.macOk("00:1A:79:AA:BB:CC")); assertFalse(StalkerProto.macOk("zz"))
    }
    @Test fun parsesChannelsAndEpg() {
        val ch = StalkerProto.parseChannels("""{"js":{"data":[{"id":"1","name":" A ","number":"3","tv_genre_id":"10","cmd":"ffrt http://l/1"}]}}""")
        assertEquals(1, ch.size); assertEquals("A", ch[0].name); assertEquals(3, ch[0].number)
        val epg = StalkerProto.parseEpg("""{"js":[{"name":"X","start_timestamp":100,"stop_timestamp":200,"descr":"d"}]}""")
        assertEquals(100_000L, epg[0].start)
    }
    @Test fun absoluteLogo() {
        assertEquals("http://h/logos/1.png", StalkerProto.absolute("http://h/c/", "/logos/1.png"))
        assertEquals("http://h/stalker_portal/logos/1.png", StalkerProto.absolute("http://h/stalker_portal/c/", "logos/1.png"))
    }
}

class TimelineTest {
    private fun p(s: Long, e: Long) = Program(s * 60_000, e * 60_000, "t$s", "")
    private val progs = listOf(p(0, 30), p(30, 90), p(90, 120))

    @Test fun floorsToHalfHour() { assertEquals(30 * 60_000L, Timeline.floor30(45 * 60_000L)); assertEquals(-30 * 60_000L, Timeline.floor30(-1L)) }
    @Test fun programAtAndNeighbours() {
        assertEquals("t30", Timeline.at(progs, 40 * 60_000L)!!.title)
        assertEquals("t90", Timeline.next(progs, 40 * 60_000L)!!.title)
        assertEquals("t0", Timeline.previous(progs, 40 * 60_000L)!!.title)
        assertNull(Timeline.previous(progs, 10 * 60_000L)); assertNull(Timeline.next(progs, 100 * 60_000L))
    }
    @Test fun geometry() { assertEquals(60f, Timeline.x(30 * 60_000L, 0, 2f), 0.01f) }
    @Test fun scrollOnlyWhenNeeded() {
        val w = 0L
        assertEquals(w, Timeline.scrollTarget(w, 240f, p(40, 70)))
        assertNotNull(Timeline.scrollTarget(w, 240f, p(300, 330)))
        assertTrue(Timeline.scrollTarget(w, 240f, p(300, 330)) > w)
    }
}

class QualityGovernorTest {
    @Test fun fixedSettingsAreRespected() {
        assertEquals(3, QualityGovernor { "high" }.current()); assertEquals(1, QualityGovernor { "low" }.current()); assertEquals(0, QualityGovernor { "off" }.current())
    }
    @Test fun autoDegradesWhenSlowAndRecovers() {
        val g = QualityGovernor { "auto" }
        repeat(100) { g.frameDrawn(40, 33) }
        assertTrue(g.current() < 3)
        val low = g.current()
        repeat(2000) { g.frameDrawn(1, 33) }
        assertTrue(g.current() > low)
    }
}
