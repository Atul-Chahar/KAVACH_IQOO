package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun `catalogue entries point at the official litert-community repo`() {
        ModelCatalog.all.forEach { spec ->
            assertTrue(
                spec.fileUrl.startsWith("https://huggingface.co/litert-community/"),
                "${spec.id} must come from Google's own LiteRT distribution org, got ${spec.fileUrl}",
            )
            assertTrue(spec.fileUrl.endsWith(spec.fileName), "${spec.id} URL must end in its filename")
            assertTrue(spec.sizeBytes > 0, "${spec.id} needs an exact size to verify against")
        }
    }

    @Test
    fun `every url is https - a plain http model download would be tamperable`() {
        ModelCatalog.all.forEach {
            assertTrue(it.pageUrl.startsWith("https://"), "${it.id} page")
            assertTrue(it.fileUrl.startsWith("https://"), "${it.id} file")
        }
    }

    @Test
    fun `every entry carries a full sha256 digest`() {
        ModelCatalog.all.forEach { spec ->
            assertEquals(64, spec.sha256.length, "${spec.id} digest must be 64 hex characters")
            assertTrue(
                spec.sha256.all { it in "0123456789abcdef" },
                "${spec.id} digest must be lowercase hex, got ${spec.sha256}",
            )
        }
    }

    @Test
    fun `ids are unique`() {
        assertEquals(
            ModelCatalog.all.size,
            ModelCatalog.all
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `required free space exceeds the file itself`() {
        val spec = ModelCatalog.default
        assertTrue(ModelCatalog.requiredFreeBytes(spec) > spec.sizeBytes)
    }

    @Test
    fun `byte formatting reads the way a person reads it`() {
        // The size the publisher's own download page shows, so the user can
        // compare the two without converting units in their head.
        assertEquals("2.97 GB", ModelCatalog.formatBytes(2_969_059_328L))
        assertEquals("500 MB", ModelCatalog.formatBytes(500_000_000L))
        assertEquals("512 B", ModelCatalog.formatBytes(512))
    }

    @Test
    fun `import progress is bounded`() {
        assertEquals(0.5f, ModelState.Importing(50, 100).fraction)
        assertEquals(0f, ModelState.Importing(10, 0).fraction)
        assertEquals(1f, ModelState.Importing(200, 100).fraction)
    }

    @Test
    fun `default model is the one the catalogue lists`() {
        assertTrue(ModelCatalog.default in ModelCatalog.all)
        assertEquals(ModelCatalog.default, ModelCatalog.byId(ModelCatalog.default.id))
    }
}
