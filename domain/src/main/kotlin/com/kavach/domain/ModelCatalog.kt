package com.kavach.domain

/**
 * A downloadable on-device model.
 *
 * Kavach ships without the weights: 2.77 GB cannot go in an APK, and shipping
 * it would also mean every user pays for a model most of them never load. The
 * app points at the official file and imports what the user downloaded.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    /** Official download page, opened in the browser. */
    val pageUrl: String,
    /** Direct file URL, also opened in the browser — the app never fetches it. */
    val fileUrl: String,
    val fileName: String,
    /** Exact expected size. Checked first because it is free. */
    val sizeBytes: Long,
    /**
     * SHA-256 of the file, from the publisher's own LFS metadata. Computed
     * during the import copy, so a corrupted or substituted file is caught
     * before the inference runtime ever sees it.
     */
    val sha256: String,
    val backend: String,
    val licence: String,
    val notes: String,
)

/**
 * The catalogue offered in-app.
 *
 * Sizes and filenames were read from the Hugging Face API on 28 Aug 2026, not
 * from memory — a wrong URL here is a silent, unrecoverable failure on the day.
 * The repo is `litert-community`, Google's own LiteRT distribution org, and it
 * is ungated: no account, no licence click-through, no token.
 */
object ModelCatalog {
    const val PROVIDER = "litert-community on Hugging Face"

    val GEMMA_4_E4B_GPU =
        ModelSpec(
            id = "gemma-4-e4b-it-gpu",
            displayName = "Gemma 4 E4B (GPU)",
            pageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            fileUrl =
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
                    "resolve/main/gemma-4-E4B-it-gpu.litertlm",
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            sizeBytes = 2_969_059_328L,
            sha256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
            backend = "GPU",
            licence = "Apache 2.0",
            notes = "Most capable. Reasoning runs on the GPU; speech stays on the NPU.",
        )

    val all: List<ModelSpec> = listOf(GEMMA_4_E4B_GPU)

    val default: ModelSpec = GEMMA_4_E4B_GPU

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /**
     * Headroom on top of the file itself. The import copies the file, so the
     * device briefly needs room for the download *and* the copy.
     */
    const val FREE_SPACE_HEADROOM_BYTES = 350L * 1024 * 1024 // 350 MB

    fun requiredFreeBytes(spec: ModelSpec): Long = spec.sizeBytes + FREE_SPACE_HEADROOM_BYTES

    /**
     * Decimal, not binary, and the difference is the whole reason this is
     * spelled out.
     *
     * These numbers exist to be compared against a download page. Hugging Face
     * lists this file as 2.97 GB; at 1024-based units Kavach rendered the same
     * 2,969,059,328 bytes as "2.77 GB" and then told the user their correct
     * download "should be 2.77 GB". Anyone checking would conclude they had
     * fetched the wrong file. The publisher's unit wins.
     */
    private const val BYTES_PER_KB = 1000.0
    private const val BYTES_PER_MB = BYTES_PER_KB * 1000
    private const val BYTES_PER_GB = BYTES_PER_MB * 1000

    /** Renders a byte count the way a person reads it. */
    fun formatBytes(bytes: Long): String =
        when {
            bytes >= BYTES_PER_GB -> "%.2f GB".format(bytes / BYTES_PER_GB)
            bytes >= BYTES_PER_MB -> "%.0f MB".format(bytes / BYTES_PER_MB)
            else -> "$bytes B"
        }
}

/**
 * Whether the model is usable. Modelled explicitly rather than as a boolean,
 * because "not there", "half imported" and "there but wrong" need different
 * words in front of a user.
 */
sealed interface ModelState {
    /** No model file. The app is fully functional; Tier 2 is simply off. */
    data object Absent : ModelState

    data class Importing(
        val copiedBytes: Long,
        val totalBytes: Long,
    ) : ModelState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    data class Ready(
        val specId: String,
        val sizeBytes: Long,
    ) : ModelState

    /** Imported but unusable — truncated download, wrong file picked. */
    data class Invalid(
        val reason: String,
    ) : ModelState
}
