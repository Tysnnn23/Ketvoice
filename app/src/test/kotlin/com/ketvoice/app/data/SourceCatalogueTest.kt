package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The website's source catalogue (schema version 1). */
class SourceCatalogueTest {

    @Test
    fun theStartingCatalogueHoldsTheLosslessManifest() {
        val raw = """{"schema":"ketvoice-source-catalogue","version":1,"updated":"2026-11-25","sources":[
            {"id":"ketvoice-lossless","name":"Ketvoice Lossless","manifest":"${CustomSources.LOSSLESS_MANIFEST}",
             "description":{"fr":"Recherche et lecture sans perte.","en":"Lossless search and playback."},
             "provides":["search","stream","teleport"],"quality":"lossless","official":true}]}"""
        val catalogue = SourceCatalogue.parse(raw)!!
        assertEquals(1, catalogue.version)
        val entry = catalogue.entries.single()
        assertEquals("ketvoice-lossless", entry.id)
        assertEquals(setOf("search", "stream"), entry.provides)
        assertEquals("lossless", entry.quality)
        assertTrue(entry.official)
        assertEquals("Lossless search and playback.", entry.describe("en"))
        assertEquals("Recherche et lecture sans perte.", entry.describe("de"))
        val installed = CustomSource(CustomSources.idOf(entry.manifestUrl), entry.manifestUrl, "x", "https://x")
        assertTrue(SourceCatalogue.installed(entry, listOf(installed)))
        assertFalse(SourceCatalogue.installed(entry, emptyList()))
    }

    @Test
    fun badEntriesAreSkippedAndNewerVersionsRefused() {
        val raw = """{"version":1,"sources":[
            {"name":"No https","manifest":"http://x.example.com/m.json"},
            {"name":"Not json","manifest":"https://x.example.com/m.xml"},
            {"manifest":"https://x.example.com/noname.json"},
            {"name":"Fine","manifest":"https://ok.example.com/m.json","description":"Texte"},
            {"name":"Twice","manifest":"https://ok.example.com/m.json"}]}"""
        val entries = SourceCatalogue.parse(raw)!!.entries
        assertEquals(listOf("Fine"), entries.map { it.name })
        assertEquals("Texte", entries.single().describe("en"))
        assertEquals(CustomSources.idOf("https://ok.example.com/m.json"), entries.single().id)
        assertNull(SourceCatalogue.parse("""{"version":2,"sources":[]}"""))
        assertNull(SourceCatalogue.parse("""{"schema":"other","version":1,"sources":[]}"""))
        assertNull(SourceCatalogue.parse("[]"))
    }

    /** The file served by the website, when the web copy sits next to the app (KETVOICE_WEB_DIR), must parse. */
    @Test
    fun theWebsiteFileParsesWhenPresent() {
        val dir = System.getenv("KETVOICE_WEB_DIR") ?: return
        val file = File(dir, "sources/catalogue.json").takeIf { it.isFile } ?: return
        val catalogue = SourceCatalogue.parse(file.readText())
        assertNotNull(catalogue)
        assertTrue(catalogue!!.entries.any { it.manifestUrl == CustomSources.LOSSLESS_MANIFEST })
    }
}
