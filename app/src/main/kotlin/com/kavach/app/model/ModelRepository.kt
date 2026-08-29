package com.kavach.app.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Stages the on-device model file.
 *
 * **The app never downloads anything.** It has no INTERNET permission and must
 * never acquire one — that absence is the privacy claim, and CI enforces it. So
 * the flow is: the app hands the official URL to the browser, the browser
 * downloads the file, and the user hands it back through the system file
 * picker. The picker also means no storage permission is needed: the user
 * grants access to exactly one file, once.
 *
 * The file lands in app-private storage, so no other app can read it and it is
 * removed when Kavach is uninstalled.
 */
class ModelRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** One import at a time: two writers would interleave into the same `.part`. */
    private val importLock = Mutex()

    /** `noBackupFilesDir`: a 2.77 GB model has no business in a cloud backup. */
    private val modelDir: File get() = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    fun fileFor(spec: ModelSpec): File = File(modelDir, spec.fileName)

    private fun partialFor(spec: ModelSpec) = File(modelDir, "${spec.fileName}.part")

    /**
     * Records that a file passed digest verification, so launch does not have to
     * re-hash 2.77 GB to know it is trustworthy.
     */
    private fun receiptFor(spec: ModelSpec) = File(modelDir, "${spec.fileName}.verified")

    /**
     * Re-reads what is actually on disk.
     *
     * Deliberately does not re-hash: that would cost tens of seconds on every
     * launch. It checks the size and the receipt written by a verified import,
     * which together mean "this exact file passed a full digest check here".
     */
    fun refresh(spec: ModelSpec = ModelCatalog.default): ModelState {
        reapOrphanedPartials(spec)

        val file = fileFor(spec)
        val next =
            when {
                !file.exists() -> ModelState.Absent
                file.length() != spec.sizeBytes ->
                    ModelState.Invalid(
                        "The file is ${ModelCatalog.formatBytes(file.length())}, " +
                            "but ${spec.displayName} should be ${ModelCatalog.formatBytes(spec.sizeBytes)}. " +
                            "The download was probably interrupted.",
                    )
                receiptFor(spec).takeIf { it.exists() }?.readText()?.trim() != spec.sha256 ->
                    ModelState.Invalid("This model has not been verified. Import it again.")
                else -> ModelState.Ready(spec.id, file.length())
            }
        _state.value = next
        return next
    }

    /**
     * A `.part` is never a valid resting state — only a crash mid-copy leaves
     * one. Left alone it would hold gigabytes hostage until the next import.
     */
    private fun reapOrphanedPartials(spec: ModelSpec) {
        if (importLock.isLocked) return
        partialFor(spec).takeIf { it.exists() }?.let {
            Log.i(TAG, "removing orphaned partial import (${it.length()} bytes)")
            it.delete()
        }
    }

    fun usableSpaceBytes(): Long = modelDir.usableSpace

    fun hasRoomFor(spec: ModelSpec): Boolean = usableSpaceBytes() >= ModelCatalog.requiredFreeBytes(spec)

    /**
     * Copies the picked file into private storage, reporting progress.
     *
     * Writes to a `.part` file and renames only on success, so an interrupted
     * import can never leave something that looks like a usable model.
     *
     * Verification is size **and** SHA-256, hashed as the bytes stream past so
     * it costs no extra read. Size alone would accept a bit-corrupted file, or
     * a different file that happened to be the same length — and the failure
     * would surface as an unexplained crash inside the inference runtime.
     */
    suspend fun import(
        uri: Uri,
        spec: ModelSpec = ModelCatalog.default,
    ): ModelState =
        importLock.withLock {
            withContext(dispatcher) {
                val partial = partialFor(spec)
                try {
                    copyAndVerify(uri, spec, partial)
                } catch (cancelled: CancellationException) {
                    // Structured concurrency: cancellation is not a failure to report,
                    // it is a signal to propagate. Clean up, then rethrow.
                    withContext(NonCancellable) { partial.delete() }
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "model import failed", error)
                    partial.delete()
                    fail(error.message ?: "The import failed.")
                }
            }
        }

    /**
     * Each early return is a distinct way staging a 2.77 GB file can fail — no
     * space, unreadable source, wrong file, unwritable destination — and each
     * one owns the sentence the user reads. Collapsing them would make the
     * failure reasons harder to follow, not easier.
     */
    @Suppress("ReturnCount")
    private suspend fun copyAndVerify(
        uri: Uri,
        spec: ModelSpec,
        partial: File,
    ): ModelState {
        if (!hasRoomFor(spec)) {
            return fail(
                "Not enough free space. ${spec.displayName} needs about " +
                    "${ModelCatalog.formatBytes(ModelCatalog.requiredFreeBytes(spec))}, " +
                    "and this phone has ${ModelCatalog.formatBytes(usableSpaceBytes())} free.",
            )
        }

        partial.delete()
        receiptFor(spec).delete()
        _state.value = ModelState.Importing(0, spec.sizeBytes)

        val digest =
            streamInto(uri, spec, partial)
                ?: return fail("Could not open the file you picked.")

        rejectionReason(spec, partial, digest)?.let { reason ->
            partial.delete()
            return fail(reason)
        }

        // renameTo replaces atomically within the same directory, so there is no
        // window where a previously good model has been deleted and the new one
        // is not yet in place.
        val target = fileFor(spec)
        if (!partial.renameTo(target)) {
            partial.delete()
            return fail("Could not save the model to private storage.")
        }
        receiptFor(spec).writeText(spec.sha256)

        return refresh(spec)
    }

    /**
     * Streams the picked file into [partial], hashing as the bytes go past so
     * verification costs no second read. Returns the hex digest, or null if the
     * file could not be opened at all.
     */
    private suspend fun streamInto(
        uri: Uri,
        spec: ModelSpec,
        partial: File,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return null

        input.use { source ->
            partial.outputStream().use { output ->
                copyStream(source, output, digest, spec)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun copyStream(
        source: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
        spec: ModelSpec,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var sinceReport = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            copied += read
            sinceReport += read
            if (sinceReport >= PROGRESS_REPORT_BYTES) {
                sinceReport = 0
                _state.value = ModelState.Importing(copied, spec.sizeBytes)
            }
        }
        output.flush()
    }

    /**
     * Why this file is unusable, in words a person can act on — or null if it is
     * genuinely the right file.
     *
     * Size alone would accept a bit-corrupted file, or a different file that
     * happened to be the same length; either would surface later as an
     * unexplained crash inside the inference runtime.
     */
    private fun rejectionReason(
        spec: ModelSpec,
        partial: File,
        digest: String,
    ): String? =
        when {
            partial.length() != spec.sizeBytes ->
                "That file is ${ModelCatalog.formatBytes(partial.length())}, but ${spec.displayName} " +
                    "should be ${ModelCatalog.formatBytes(spec.sizeBytes)}. " +
                    "Either the download did not finish, or a different file was picked."

            digest != spec.sha256 ->
                "That file is the right size but its contents do not match the " +
                    "official ${spec.displayName}. Download it again."

            else -> null
        }

    fun delete(spec: ModelSpec = ModelCatalog.default): ModelState {
        fileFor(spec).delete()
        partialFor(spec).delete()
        receiptFor(spec).delete()
        return refresh(spec)
    }

    private fun fail(reason: String): ModelState = ModelState.Invalid(reason).also { _state.value = it }

    private companion object {
        const val TAG = "KavachModel"
        const val COPY_BUFFER_BYTES = 1 shl 20
        const val PROGRESS_REPORT_BYTES = 8L shl 20
    }
}
