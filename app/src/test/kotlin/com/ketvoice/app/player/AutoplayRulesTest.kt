package com.ketvoice.app.player

import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.Genre
import com.ketvoice.app.smartlib.SoundSignals
import com.ketvoice.app.smartlib.TrackFacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** « Lecture automatique » (7.0): which titles follow a title started on its own. No network: fake answers only. */
class AutoplayRulesTest {
    private fun t(id: String, title: String, artist: String, video: String? = null) = Track(id, title, artist, "", videoId = video)
    private val seed = t("yt:SEEDSEEDSEE", "Bande organisée", "Jul", "SEEDSEEDSEE")
    private val none: (Track) -> AutoplayRules.Feel? = { null }

    @Test fun keepsTheSourceOrderAndLeavesOutTheSeedTheQueueAndBlockedArtists() {
        val candidates = listOf(
            t("yt:SEEDSEEDSEE", "Bande organisée", "Jul", "SEEDSEEDSEE"),      // the seed itself
            t("yt:AAAAAAAAAAA", "Ma story", "SCH", "AAAAAAAAAAA"),
            t("um:1", "Bande Organisée (Clip officiel)", "JUL"),                  // the same recording elsewhere
            t("yt:BBBBBBBBBBB", "Already queued", "Naps", "BBBBBBBBBBB"),
            t("yt:CCCCCCCCCCC", "Blocked one", "Blocked Artist", "CCCCCCCCCCC"),
            t("yt:DDDDDDDDDDD", "Tout va bien", "Alonzo", "DDDDDDDDDDD"))
        val queue = listOf(seed, t("x", "Already queued", "Naps"))
        val out = AutoplayRules.pick(candidates, seed, queue, emptySet(), { it.artist != "Blocked Artist" }, none)
        assertEquals(listOf("Ma story", "Tout va bien"), out.map { it.title })
    }

    @Test fun neverATitleTwiceInASessionNorAnArtistMoreThanTwicePerBatch() {
        val candidates = (1..6).map { t("yt:SCH$it", "Titre $it", "SCH") } + t("yt:K", "Autre", "Kofs")
        val first = AutoplayRules.pick(candidates, seed, listOf(seed), emptySet(), { true }, none)
        assertEquals(listOf("Titre 1", "Titre 2", "Autre"), first.map { it.title })
        val heard = first.flatMap(AutoplayRules::heardKeys).toSet()
        val second = AutoplayRules.pick(candidates, seed, listOf(seed), heard, { true }, none)
        assertTrue(second.none { it in first })
        assertEquals(listOf("Titre 3", "Titre 4"), second.map { it.title })
    }

    @Test fun aVeryDifferentTempoOrEnergyIsLeftOutOnlyWhenBothAreMeasured() {
        val calm = t("yt:calm", "Calme", "A")
        val fast = t("yt:fast", "Rapide", "B")
        val double = t("yt:double", "Double", "C")
        val loud = t("yt:loud", "Fort", "D")
        val unknown = t("yt:unk", "Inconnu", "E")
        val feel = mapOf(seed.id to AutoplayRules.Feel(90f, -9f), calm.id to AutoplayRules.Feel(94f, -10f),
            fast.id to AutoplayRules.Feel(140f, -9f), double.id to AutoplayRules.Feel(180f, -8f), loud.id to AutoplayRules.Feel(92f, -25f))
        val out = AutoplayRules.pick(listOf(calm, fast, double, loud, unknown), seed, listOf(seed), emptySet(), { true }, { feel[it.id] })
        assertEquals("half or double time is the same pulse", listOf("Calme", "Double", "Inconnu"), out.map { it.title })
    }

    @Test fun offlineFollowsTheSameArtistThenTheSameStyle() {
        val local = t("dl:seed", "Seed", "Jul")
        val sameArtist = t("dl:1", "Encore Jul", "Jul")
        val sameStyle = t("dl:2", "Rap d'ailleurs", "Ninho")
        val other = t("dl:3", "Valse", "Chopin")
        val blocked = t("dl:4", "Même style bloqué", "Bloqué")
        val facts = mapOf(local.id to TrackFacts(genres = setOf(Genre.Rap), sound = SoundSignals(95f, -9f, true)),
            sameArtist.id to TrackFacts(genres = setOf(Genre.Rap)),
            sameStyle.id to TrackFacts(genres = setOf(Genre.Rap)),
            other.id to TrackFacts(genres = setOf(Genre.Classical), sound = SoundSignals(60f, -30f, false)),
            blocked.id to TrackFacts(genres = setOf(Genre.Rap)))
        val out = AutoplayRules.offline(local, listOf(other, sameStyle, blocked, sameArtist, local), facts, listOf(local), emptySet(),
            { it.artist != "Bloqué" }, Random(3))
        assertEquals(listOf("Encore Jul", "Rap d'ailleurs"), out.map { it.title })
    }

    @Test fun readsYouTubeMusicsUpNextPanel() {
        fun run(text: String, page: String? = null, id: String? = null) = JSONObject().put("text", text).apply {
            if (page != null) put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
                .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", page)))))
        }
        fun row(video: String, title: String, type: String, vararg runs: JSONObject) = JSONObject().put("playlistPanelVideoRenderer", JSONObject()
            .put("videoId", video).put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title))))
            .put("lengthText", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "3:05"))))
            .put("longBylineText", JSONObject().put("runs", JSONArray(runs.toList())))
            .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", video)
                .put("watchEndpointMusicSupportedConfigs", JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", type))))))
        val contents = JSONArray()
            .put(row("SEEDSEEDSEE", "Bande organisée", "MUSIC_VIDEO_TYPE_ATV", run("Jul", "MUSIC_PAGE_TYPE_ARTIST", "UCjul")))
            .put(row("AAAAAAAAAAA", "Ma story", "MUSIC_VIDEO_TYPE_ATV", run("SCH", "MUSIC_PAGE_TYPE_ARTIST", "UCsch"), run(" • "),
                run("JVLIVS", "MUSIC_PAGE_TYPE_ALBUM", "MPREb_x")))
            .put(row("BBBBBBBBBBB", "Naps - La kiffance (Clip officiel)", "MUSIC_VIDEO_TYPE_OMV", run("Naps", "MUSIC_PAGE_TYPE_ARTIST", "UCnaps")))
            .put(row("PODCASTPODC", "Un podcast", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE", run("Radio", "MUSIC_PAGE_TYPE_USER_CHANNEL", "UCr")))
            .put(row("AAAAAAAAAAA", "Ma story", "MUSIC_VIDEO_TYPE_ATV", run("SCH", "MUSIC_PAGE_TYPE_ARTIST", "UCsch")))
        val raw = JSONObject().put("contents", JSONObject().put("playlistPanelRenderer", JSONObject().put("contents", contents)))
        val out = AutoplayRules.parseRadio(raw, "SEEDSEEDSEE")
        assertEquals(listOf("Ma story", "La kiffance"), out.map { it.title })
        val first = out.first()
        assertEquals("yt:AAAAAAAAAAA", first.id)
        assertEquals("SCH", first.artist)
        assertEquals("JVLIVS", first.album)
        assertEquals("MPREb_x", first.albumId)
        assertEquals("UCsch", first.artistId)
        assertEquals(185_000L, first.durationMs)
    }
}
