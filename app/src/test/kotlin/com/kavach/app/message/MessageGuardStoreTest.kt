package com.kavach.app.message

import com.kavach.domain.SmsMessageAnalyzer
import com.kavach.domain.TacticLexicon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageGuardStoreTest {
    private val store = MessageGuardStore(SmsMessageAnalyzer(loadLexicon()))

    @Test
    fun `duplicate notification update is ignored`() {
        val first = store.inspect("conversation", "Bank", SCAM, 1)
        val duplicate = store.inspect("conversation", "Bank", SCAM, 2)

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(1, store.detections.value.size)
    }

    @Test
    fun `later message in same conversation is inspected`() {
        store.inspect("conversation", "Bank", "Hello", 1)
        val later = store.inspect("conversation", "Bank", SCAM, 2)

        assertNotNull(later)
        assertEquals(1, store.detections.value.size)
    }

    @Test
    fun `clear messages are never recorded`() {
        assertNull(store.inspect("chat", "Amma", "Dinner at eight, come early.", 1))

        assertEquals(emptyList(), store.detections.value)
        assertEquals(0, store.unreviewed.value)
    }

    /**
     * The bug this guards: warnings used to share one notification id, so an
     * SMS burst — which is how these arrive — showed as a single warning.
     */
    @Test
    fun `each warning gets its own notification slot`() {
        val first = store.inspect("a", "Bank", SCAM, 1)
        val second = store.inspect("b", "Courier", OTHER_SCAM, 2)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first.notificationId != second.notificationId)
    }

    /**
     * The other half of that bug: ordinary chat used to fill the twelve visible
     * slots and evict the one detection the user opened the screen to read.
     */
    @Test
    fun `ordinary chat cannot evict a warning`() {
        val warning = store.inspect("scam", "Bank", SCAM, 1)
        repeat(30) { store.inspect("chat$it", "Friend", "Message number $it, nothing unusual.", it.toLong()) }

        assertNotNull(warning)
        assertEquals(listOf(warning.id), store.detections.value.map { it.id })
    }

    @Test
    fun `unreviewed count rises with warnings and clears on review`() {
        store.inspect("a", "Bank", SCAM, 1)
        store.inspect("b", "Courier", OTHER_SCAM, 2)
        assertEquals(2, store.unreviewed.value)

        store.markReviewed()

        assertEquals(0, store.unreviewed.value)
    }

    @Test
    fun `trusting a sender drops the finding and silences the next one`() {
        val detection = store.inspect("a", "Bank", SCAM, 1)
        assertNotNull(detection)

        store.trust(detection.id)

        assertEquals(emptyList(), store.detections.value)
        assertEquals(0, store.unreviewed.value)
        assertNull(store.inspect("b", "Bank", OTHER_SCAM, 2))
    }

    @Test
    fun `trusting one sender does not silence another`() {
        val detection = store.inspect("a", "Bank", SCAM, 1)
        assertNotNull(detection)
        store.trust(detection.id)

        assertNotNull(store.inspect("b", "Courier", OTHER_SCAM, 2))
    }

    @Test
    fun `a warning can be found again by id, for the capsule`() {
        val detection = store.inspect("a", "Bank", SCAM, 1)
        assertNotNull(detection)

        assertEquals(detection, store.find(detection.id))
        assertNull(store.find("nothing-like-this"))
    }

    @Test
    fun `connection state is reported, not assumed`() {
        assertEquals(false, store.connected.value)

        store.setConnected(true)

        assertEquals(true, store.connected.value)
    }

    private fun loadLexicon(): TacticLexicon {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, LEXICON).isFile }
        return TacticLexicon.parse(File(root, LEXICON).readText())
    }

    private companion object {
        const val LEXICON = "data/tactic_lexicon.json"
        const val SCAM = "Your account expires today. Update KYC now: http://sbi-verify.example/login"
        const val OTHER_SCAM = "Parcel held. Pay the customs fee immediately at http://192.0.2.10/pay or reply with OTP"
    }
}
