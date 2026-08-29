package com.kavach.app.inference

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechModelManagerTest {
    @Test
    fun `default target languages include English India and Hindi`() {
        val targets = SpeechModelManager.TARGET_LANGUAGES
        assertTrue(targets.contains("en-IN"))
        assertTrue(targets.contains("hi-IN"))
    }

    @Test
    fun `default languages in PipedAsrTranscriptSource includes Hindi and Indian English`() {
        val langs = PipedAsrTranscriptSource.defaultLanguages()
        assertTrue(langs.contains("en-IN"))
        assertTrue(langs.contains("hi-IN"))
    }

    @Test
    fun `SpeechModelStatus detects Hindi and Indian English correctly`() {
        val statusWithBoth =
            SpeechModelStatus(
                checked = true,
                isSupported = true,
                installedLanguages = listOf("en-IN", "hi-IN"),
            )
        assertTrue(statusWithBoth.hasHindi)
        assertTrue(statusWithBoth.hasIndianEnglish)

        val statusWithNone =
            SpeechModelStatus(
                checked = true,
                isSupported = true,
                installedLanguages = listOf("fr-FR"),
            )
        assertFalse(statusWithNone.hasHindi)
        assertFalse(statusWithNone.hasIndianEnglish)
    }

    @Test
    fun `SpeechModelStatus detects downloading state`() {
        val downloadingStatus =
            SpeechModelStatus(
                downloadingLanguages = setOf("hi-IN"),
            )
        assertTrue(downloadingStatus.isDownloading)

        val idleStatus =
            SpeechModelStatus(
                downloadingLanguages = emptySet(),
            )
        assertFalse(idleStatus.isDownloading)
    }
}
