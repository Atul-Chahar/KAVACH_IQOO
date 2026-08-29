package com.kavach.app.message

import com.kavach.domain.SmsMessageAnalyzer
import com.kavach.domain.TacticLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageGuardStoreTest {
    private val store = MessageGuardStore(SmsMessageAnalyzer(loadLexicon()))

    @Test
    fun `duplicate notification update is ignored`() {
        val first = store.inspect("conversation", "Enter OTP at http://unsafe.example", 1)
        val duplicate = store.inspect("conversation", "Enter OTP at http://unsafe.example", 2)

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(1, store.detections.value.size)
    }

    @Test
    fun `later message in same conversation is inspected`() {
        store.inspect("conversation", "Hello", 1)
        val later = store.inspect("conversation", "Send your OTP immediately", 2)

        assertNotNull(later)
        assertEquals(2, store.detections.value.size)
    }

    private fun loadLexicon(): TacticLexicon {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, LEXICON).isFile }
        return TacticLexicon.parse(File(root, LEXICON).readText())
    }

    private companion object {
        const val LEXICON = "data/tactic_lexicon.json"
    }
}
