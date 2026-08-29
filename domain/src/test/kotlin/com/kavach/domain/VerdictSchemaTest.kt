package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerdictSchemaTest {
    private val families =
        TestFixtures.lexicon.families
            .map { it.id }
            .toSet()

    private fun parse(raw: String?) = VerdictSchema.parseOrNull(raw, families)

    @Test
    fun `parses a well formed verdict`() {
        val verdict =
            parse(
                """{"risk":73,"tactics":["AUTHORITY_IMPERSONATION","CREDENTIAL_EXTRACTION"],
               "one_line_reason":"Caller claims to be police and is asking for an OTP.",
               "recommended_action":"HANG_UP"}""",
            )
        assertEquals(73, verdict?.risk)
        assertEquals(2, verdict?.tactics?.size)
    }

    @Test
    fun `survives the code fences and prose models add anyway`() {
        val verdict =
            parse(
                """
                Sure! Here is the JSON you asked for:
                ```json
                {"risk": 55, "tactics": [], "one_line_reason": "Some urgency language.", "recommended_action": "CAUTION"}
                ```
                Let me know if you need anything else.
                """.trimIndent(),
            )
        assertEquals(55, verdict?.risk)
    }

    @Test
    fun `rejects malformed json`() = assertNull(parse("{\"risk\": 73, \"tactics\": [unclosed"))

    @Test
    fun `rejects non json`() = assertNull(parse("I think this is probably a scam, be careful!"))

    @Test
    fun `rejects null and empty`() {
        assertNull(parse(null))
        assertNull(parse(""))
    }

    @Test
    fun `rejects an out of range score`() {
        assertNull(parse("""{"risk":140,"one_line_reason":"x"}"""))
        assertNull(parse("""{"risk":-5,"one_line_reason":"x"}"""))
    }

    @Test
    fun `rejects a verdict with no reason - an unexplained score is unusable`() {
        assertNull(parse("""{"risk":80,"tactics":["CREDENTIAL_EXTRACTION"],"one_line_reason":"  "}"""))
    }

    @Test
    fun `drops invented tactic names`() {
        val verdict =
            parse(
                """{"risk":50,"tactics":["CREDENTIAL_EXTRACTION","MIND_CONTROL_RAY"],"one_line_reason":"x"}""",
            )
        assertEquals(listOf("CREDENTIAL_EXTRACTION"), verdict?.tactics)
    }

    @Test
    fun `truncates a runaway reason so it cannot blow up the alert card`() {
        val verdict = parse("""{"risk":50,"one_line_reason":"${"very long ".repeat(80)}"}""")
        assertTrue((verdict?.oneLineReason?.length ?: 0) <= 160)
    }

    @Test
    fun `tolerates missing optional fields`() {
        assertEquals(42, parse("""{"risk":42,"one_line_reason":"Something odd."}""")?.risk)
    }
}
