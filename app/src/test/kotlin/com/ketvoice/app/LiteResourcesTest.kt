package com.ketvoice.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Ketvoice Lite's lighter images (src/lite, made by tools/lite/make_lite_resources.py) must stay copies of
 * the current images: when an image of src/main or src/standard changes, the script has to be run again,
 * or Lite would show the old one. An image Lite replaced by a placeholder must stay unused.
 */
class LiteResourcesTest {
    private val app = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/lite/lite-resources.json").isFile || File(it, "app/src/lite/lite-resources.json").isFile }
        .let { if (File(it, "src/lite").isDirectory) it else File(it, "app") }
    private val record = JSONObject(File(app, "src/lite/lite-resources.json").readText())

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun entries(part: String) = record.getJSONObject(part).let { o -> o.keys().asSequence().map { it to o.getJSONObject(it) }.toList() }

    @Test fun everyLiteCopyMatchesItsCurrentSource() {
        val all = entries("converted") + entries("placeholders") + entries("assets")
        assertTrue(all.size > 40)
        all.forEach { (copy, source) ->
            val original = File(app, source.getString("source"))
            assertTrue("$original is gone: run tools/lite/make_lite_resources.py", original.isFile)
            assertEquals("$original changed: run tools/lite/make_lite_resources.py", source.getString("sha256"), sha256(original))
            assertTrue("$copy is missing", File(app, copy).isFile)
            // Lite's copy has the same resource name, so it replaces the original in the Lite APK only.
            assertEquals(original.nameWithoutExtension, File(copy).nameWithoutExtension)
            if (!copy.contains("/assets/")) assertEquals(original.parentFile.name, File(copy).parentFile.name)
        }
    }

    @Test fun everyMainImageHasALiteCopy() {
        val covered = (entries("converted") + entries("placeholders")).map { it.second.getString("source") }.toSet()
        val images = File(app, "src/main/res").listFiles()!!.flatMap { dir -> dir.listFiles()!!.filter { it.name.endsWith(".png") && !it.name.endsWith(".9.png") } }
            .map { it.relativeTo(app).invariantSeparatorsPath }
        assertEquals("new images: run tools/lite/make_lite_resources.py", images.toSet() - covered, emptySet<String>())
    }

    @Test fun placeholdersStandOnlyForUnusedImages() {
        val sources = File(app, "src").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") && !it.path.contains("${File.separator}lite${File.separator}") }
            .map { it.readText() }.toList()
        entries("placeholders").forEach { (copy, _) ->
            val name = File(copy).nameWithoutExtension
            val use = Regex("""(R\.(drawable|mipmap)\.$name\b|@(drawable|mipmap)/$name\b)""")
            assertFalse("$name is used again: run tools/lite/make_lite_resources.py", sources.any { use.containsMatchIn(it) })
        }
    }

    @Test fun theBannerAssetOfEachEditionIsPackaged() {
        assertTrue(File(app, "src/standard/assets/" + LiteTuning.bannerAsset(lite = false)).isFile)
        assertTrue(File(app, "src/lite/assets/" + LiteTuning.bannerAsset(lite = true)).isFile)
    }
}
