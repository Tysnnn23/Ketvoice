package com.ketvoice.app.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ImportedLyricsTest {
    @Test fun importedLrcPreservesRealLineTimesAndUnicode() {
        val file = File.createTempFile("ketvoice", ".lrc")
        try {
            file.writeText("[00:01.50]Bonjour à tous\n[00:03.00]世界", Charsets.UTF_8)
            val result = ImportedLyrics.read(file)
            assertEquals(1500L, result.lines[0].timeMs)
            assertEquals("世界", result.lines[1].text)
            assertTrue(result.lines.all { it.words.isEmpty() })
        } finally { file.delete() }
    }
    @Test fun rejectsEmptyMalformedAndEntityBasedDocuments() {
        val file = File.createTempFile("ketvoice", ".ttml")
        try {
            file.writeText("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///secret'>]><tt><p begin='1s'>&x;</p></tt>")
            assertTrue(runCatching { ImportedLyrics.read(file) }.isFailure)
        } finally { file.delete() }
    }
    @Test fun nativeTtmlWordTimesArePreserved() {
        val file = File.createTempFile("ketvoice", ".ttml")
        try {
            file.writeText("<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='1s'><span begin='1s' end='2s'>Hello</span> <span begin='2s' end='3s'>world</span></p></div></body></tt>")
            val result = ImportedLyrics.read(file)
            assertEquals(2, result.lines.single().words.size)
            assertEquals(2000L, result.lines.single().words.first().endMs)
        } finally { file.delete() }
    }
}
