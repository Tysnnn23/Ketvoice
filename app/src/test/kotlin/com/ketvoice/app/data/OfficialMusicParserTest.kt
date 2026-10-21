package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfficialMusicParserTest {
    private fun browse(id: String, type: String) = JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
        .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", type))))
    private fun row(type: String = "MUSIC_VIDEO_TYPE_ATV", album: Boolean = true, artist: Boolean = true, title: String = "Titre") : JSONObject {
        val watch = JSONObject().put("watchEndpoint", JSONObject().put("videoId", "abcdefghijk")
            .put("watchEndpointMusicSupportedConfigs", JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", type))))
        val meta = JSONArray()
        if (artist) meta.put(JSONObject().put("text", "Artiste officiel").put("navigationEndpoint", browse("UCofficial", "MUSIC_PAGE_TYPE_ARTIST")))
        if (album) meta.put(JSONObject().put("text", "Vrai album").put("navigationEndpoint", browse("MPREalbum", "MUSIC_PAGE_TYPE_ALBUM")))
        meta.put(JSONObject().put("text", "3:31"))
        fun column(runs: JSONArray) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", runs)))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject().put("flexColumns", JSONArray()
            .put(column(JSONArray().put(JSONObject().put("text", title).put("navigationEndpoint", watch))))
            .put(column(meta))))
    }
    private fun response(row: JSONObject) = JSONObject().put("contents", JSONArray().put(row)).toString()

    @Test fun acceptsCatalogAudioWithArtistAndReleaseEvidence() {
        val track = OfficialMusicParser.parse(response(row())).single()
        assertTrue(track.youtubeCatalogVerified)
        assertEquals(211000L, track.durationMs)
        assertEquals("MPREalbum", track.albumId)
    }
    @Test fun rejectsUploadsEvenWithOfficialInTheirChannelName() {
        assertTrue(OfficialMusicParser.parse(response(row(type = "MUSIC_VIDEO_TYPE_UGC"))).isEmpty())
    }
    @Test fun unrelatedMenuEndpointsCannotCertifyAnUpload() {
        val upload = row(type = "MUSIC_VIDEO_TYPE_UGC")
        upload.getJSONObject("musicResponsiveListItemRenderer").put("menu", JSONObject().put("watchEndpoint",
            JSONObject().put("videoId", "anotherId12").put("watchEndpointMusicSupportedConfigs",
                JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")))))
        assertTrue(OfficialMusicParser.parse(response(upload)).isEmpty())
    }
    @Test fun rejectsVideosAndUnknownCatalogueEvidence() {
        assertTrue(OfficialMusicParser.parse(response(row(type = "MUSIC_VIDEO_TYPE_OMV"))).isEmpty())
        assertTrue(OfficialMusicParser.parse(response(row(album = false))).isEmpty())
        assertTrue(OfficialMusicParser.parse(response(row(artist = false))).isEmpty())
    }
    @Test fun alteredVersionsRequireAnExplicitSearch() {
        val json = response(row(title = "Titre (slowed)"))
        assertTrue(OfficialMusicParser.parse(json, "Titre Artiste").isEmpty())
        assertEquals(1, OfficialMusicParser.parse(json, "Titre slowed").size)
    }
    @Test fun catalogTracksRetainRealReleaseAndArtistIds() {
        val tracks = CatalogRepo.parseTracks(JSONArray("""[
          {"id":123,"title":"Titre","duration":211,"readable":true,"artist":{"id":5,"name":"Artiste"},"album":{"id":7,"title":"Album","cover_xl":"https://image.example/7.jpg"}},
          {"id":124,"title":"Indisponible","readable":false}
        ]"""))
        assertEquals(1, tracks.size)
        assertEquals("7", tracks.single().albumId)
        assertEquals("5", tracks.single().artistId)
    }
}
