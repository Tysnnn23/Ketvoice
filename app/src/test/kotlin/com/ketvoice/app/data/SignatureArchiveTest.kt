package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SignatureArchiveTest {
    private fun wiki(file: String, license: String = "PD-signature") = """{"asset":{"url":"https://upload.wikimedia.org/x/$file","format":"svg"},
        "source":{"provider":"wikimedia_commons","url":"https://commons.wikimedia.org/wiki/File:$file"},"license":{"name":"$license"}}"""

    @Test fun onlyTheArtistsOwnFileIsKept() {
        val root = JSONObject("""{"signatures":[${wiki("Francis_Drake_Signature.svg")},${wiki("Drake_sig.svg")},${wiki("Nick_Drake_sig.svg")}]}""")
        val found = ArtistSignatures.parseArchive(root, "Drake")!!
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Drake_sig.svg?width=800", found.url)
        assertEquals("Wikimedia Commons · PD-signature", found.attribution)
        assertTrue(found.monochrome)
        val other = JSONObject("""{"signatures":[${wiki("Francis_Drake_Signature.svg")},${wiki("Drake_Bell_signature.png")}]}""")
        assertNull("Homonyms are refused", ArtistSignatures.parseArchive(other, "Drake"))
    }

    @Test fun theCuratedArchiveIsReadAsIs() {
        val root = JSONObject("""{"signatures":[{"asset":{"url":"https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Daft%20Punk.png","format":"png"},
            "source":{"provider":"signature_archive","url":"https://github.com/itsmeadarsh2008/signature-archive/blob/main/assets/Daft%20Punk.png"},"license":{"name":"CC0 1.0"}}]}""")
        val found = ArtistSignatures.parseArchive(root, "Daft Punk")!!
        assertEquals("https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Daft%20Punk.png", found.url)
        assertEquals("Signature Archive · CC0 1.0", found.attribution)
        assertNull(ArtistSignatures.parseArchive(root, "Justice"))
    }

    @Test fun unexpectedHostsAndShapesAreRefused() {
        val root = JSONObject("""{"signatures":[{"asset":{"url":"https://evil.example/assets/Daft%20Punk.png"},"source":{"provider":"signature_archive","url":"x"}},
            {"asset":{"url":"x"},"source":{"provider":"wikimedia_commons","url":"http://commons.wikimedia.org/wiki/File:Daft_Punk_sig.svg"}},
            {"asset":{"url":"x"},"source":{"provider":"other","url":"https://commons.wikimedia.org/wiki/File:Daft_Punk_sig.svg"}}]}""")
        assertNull(ArtistSignatures.parseArchive(root, "Daft Punk"))
        assertNull(ArtistSignatures.parseArchive(JSONObject("{}"), "Daft Punk"))
    }
}
