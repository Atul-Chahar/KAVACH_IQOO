package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the JUnit5 + kotlin.test harness runs. Delete alongside [BuildInfo]. */
class BuildInfoTest {
    @Test
    fun `domain test harness runs`() {
        assertEquals("Kavach", BuildInfo.NAME)
    }
}
