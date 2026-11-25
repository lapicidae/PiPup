package nl.rogro82.pipup

import nl.rogro82.pipup.ui.OpenSourceLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenSourceLicenses] JSON parsing and generic family grouping.
 */
class OpenSourceLicensesTest {

    @Test
    fun parseMapsLicensesAuthorsWebsitesAndSortsByName() {
        val json = """
            {
              "libraries": [
                {
                  "uniqueId": "org.nanohttpd:nanohttpd",
                  "artifactVersion": "2.3.1",
                  "name": "NanoHTTPD-Core",
                  "description": "Tiny embeddable HTTP server",
                  "developers": [{ "name": "Paul Hawke" }, { "name": "Jarno Elonen" }],
                  "scm": { "url": "https://github.com/NanoHttpd/nanohttpd" },
                  "licenses": ["BSD-3-Clause"]
                },
                {
                  "uniqueId": "com.github.bumptech.glide:glide",
                  "artifactVersion": "5.0.10",
                  "name": "Glide",
                  "website": "https://github.com/bumptech/glide",
                  "organization": { "name": "Bumptech" },
                  "licenses": ["BSD-2-Clause", "UNKNOWN_KEY"]
                }
              ],
              "licenses": {
                "BSD-3-Clause": {
                  "name": "BSD 3-Clause License",
                  "url": "https://spdx.org/licenses/BSD-3-Clause.html",
                  "content": "Redistribution and use in source and binary forms..."
                },
                "BSD-2-Clause": {
                  "name": "BSD 2-Clause License",
                  "url": "https://spdx.org/licenses/BSD-2-Clause.html",
                  "content": "Simplified BSD License text"
                }
              }
            }
        """.trimIndent()

        val result = OpenSourceLicenses.parse(json)
        assertEquals(2, result.size)

        val glide = result[0]
        assertEquals("Glide", glide.name)
        assertEquals("5.0.10", glide.version)
        assertEquals("Bumptech", glide.author)
        assertEquals("https://github.com/bumptech/glide", glide.website)
        assertEquals(2, glide.licenses.size)
        assertEquals("BSD 2-Clause License", glide.licenses[0].name)
        assertEquals("Simplified BSD License text", glide.licenses[0].content)
        assertEquals("UNKNOWN_KEY", glide.licenses[1].name)
        assertNull(glide.licenses[1].content)

        val nano = result[1]
        assertEquals("NanoHTTPD-Core", nano.name)
        assertEquals("Paul Hawke, Jarno Elonen", nano.author)
        assertEquals("https://github.com/NanoHttpd/nanohttpd", nano.website)
        assertEquals("Tiny embeddable HTTP server", nano.description)
        assertEquals(1, nano.licenses.size)
        assertEquals("BSD 3-Clause License", nano.licenses[0].name)
    }

    @Test
    fun parseGroupedCombinesAndroidXAndMavenFamiliesWhilePreservingArtifacts() {
        val grouped = OpenSourceLicenses.parseGrouped(SAMPLE_GROUPED_LIBRARIES_JSON)
        assertEquals(5, grouped.size)

        val byName = grouped.associateBy { it.name }

        val androidx = byName.getValue("AndroidX (Jetpack)")
        assertNull(androidx.version)
        assertEquals("The Android Open Source Project", androidx.author)
        assertEquals(
            listOf("androidx.appcompat:appcompat:1.8.0", "androidx.core:core-ktx:1.19.1"),
            androidx.artifacts,
        )

        val glide = byName.getValue("Glide")
        assertEquals("5.0.10", glide.version)
        assertEquals("https://github.com/bumptech/glide", glide.website)
        assertEquals(2, glide.artifacts.size)

        val kotlinx = byName.getValue("Kotlinx")
        assertNull(kotlinx.version)
        assertEquals(2, kotlinx.artifacts.size)

        val media3 = byName.getValue("Media3")
        assertEquals("1.11.1", media3.version)
        assertEquals(2, media3.artifacts.size)

        val nano = byName.getValue("NanoHttpd-Core")
        assertEquals("2.3.1", nano.version)
        assertEquals(listOf("org.nanohttpd:nanohttpd:2.3.1"), nano.artifacts)
    }

    @Test
    fun parseGroupedDoesNotMergeArtifactsWithDifferentLicenses() {
        val json = """
            {
              "libraries": [
                { "uniqueId": "com.example:lib-a", "name": "Lib A", "licenses": ["Apache-2.0"] },
                { "uniqueId": "com.example:lib-b", "name": "Lib B", "licenses": ["MIT"] }
              ],
              "licenses": {
                "Apache-2.0": { "name": "Apache License 2.0" },
                "MIT": { "name": "MIT License" }
              }
            }
        """.trimIndent()

        val grouped = OpenSourceLicenses.parseGrouped(json)
        assertEquals(2, grouped.size)
        assertEquals(listOf("Lib A", "Lib B"), grouped.map { it.name })
    }

    @Test
    fun parseReturnsEmptyListForBlankOrMalformedJson() {
        assertTrue(OpenSourceLicenses.parse("").isEmpty())
        assertTrue(OpenSourceLicenses.parse("   ").isEmpty())
        assertTrue(OpenSourceLicenses.parse("{not-valid-json").isEmpty())
        assertTrue(OpenSourceLicenses.parse("{}").isEmpty())
        assertTrue(OpenSourceLicenses.parseGrouped("").isEmpty())
    }

    private companion object {
        val SAMPLE_GROUPED_LIBRARIES_JSON = """
            {
              "libraries": [
                {
                  "uniqueId": "androidx.core:core-ktx",
                  "artifactVersion": "1.19.1",
                  "name": "Core Kotlin Extensions",
                  "developers": [{ "name": "The Android Open Source Project" }],
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "androidx.appcompat:appcompat",
                  "artifactVersion": "1.8.0",
                  "name": "AppCompat",
                  "developers": [{ "name": "The Android Open Source Project" }],
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "androidx.media3:media3-exoplayer",
                  "artifactVersion": "1.11.1",
                  "name": "Media3 ExoPlayer module",
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "androidx.media3:media3-ui",
                  "artifactVersion": "1.11.1",
                  "name": "Media3 UI module",
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "com.github.bumptech.glide:glide",
                  "artifactVersion": "5.0.10",
                  "name": "Glide",
                  "website": "https://github.com/bumptech/glide",
                  "licenses": ["Apache-2.0", "BSD-2-Clause"]
                },
                {
                  "uniqueId": "com.github.bumptech.glide:annotations",
                  "artifactVersion": "5.0.10",
                  "name": "Glide Annotations",
                  "licenses": ["Apache-2.0", "BSD-2-Clause"]
                },
                {
                  "uniqueId": "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                  "artifactVersion": "1.9.0",
                  "name": "kotlinx-coroutines-core",
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "org.jetbrains.kotlinx:kotlinx-serialization-core",
                  "artifactVersion": "1.7.3",
                  "name": "kotlinx-serialization-core",
                  "licenses": ["Apache-2.0"]
                },
                {
                  "uniqueId": "org.nanohttpd:nanohttpd",
                  "artifactVersion": "2.3.1",
                  "name": "NanoHttpd-Core",
                  "licenses": ["BSD-3-Clause"]
                }
              ],
              "licenses": {
                "Apache-2.0": { "name": "Apache License 2.0", "content": "Apache text" },
                "BSD-2-Clause": { "name": "BSD 2-Clause License", "content": "BSD2 text" },
                "BSD-3-Clause": { "name": "BSD 3-Clause License", "content": "BSD3 text" }
              }
            }
        """.trimIndent()
    }
}
