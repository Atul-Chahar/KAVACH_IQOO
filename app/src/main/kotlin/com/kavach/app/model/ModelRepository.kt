package com.kavach.app.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

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

    /** `noBackupFilesDir`: a 2.77 GB model has no business in a cloud backup. */
    private val modelDir: File get() = File(context.noBackupFilesDir, "models").apply { mkdirs() }

    fun fileFor(spec: ModelSpec): File = File(modelDir, spec.fileName)

    /** Re-reads what is actually on disk. Called at startup and after any change. */
    fun refresh(spec: ModelSpec = ModelCatalog.default): ModelState {
        val file = fileFor(spec)
        val next =
            when {
                !file.exists() -> ModelState.Absent
                file.length() == spec.sizeBytes -> ModelState.Ready(spec.id, file.length())
                else ->
                    ModelState.Invalid(
                        "The file is ${ModelCatalog.formatBytes(file.length())}, " +
                            "but ${spec.displayName} should be ${ModelCatalog.formatBytes(spec.sizeBytes)}. " +
                            "The download was probably interrupted.",
                    )
            }
        _state.value = next
        return next
    }

    fun usableSpaceBytes(): Long = modelDir.usableSpace

    fun hasRoomFor(spec: ModelSpec): Boolean = usableSpaceBytes() >= ModelCatalog.requiredFreeBytes(spec)

    /**
     * Copies the picked file into private storage, reporting progress.
     *
     * Writes to a `.part` file and renames only on success, so an interrupted
     * import can never leave something that looks like a usable model. Verifies
     * the exact byte count afterwards: a truncated 2.77 GB download is the most
     * likely failure on venue wifi, and it must be caught here rather than as a
     * mysterious crash inside the inference runtime.
     */
    suspend fun import(
        uri: Uri,
        spec: ModelSpec = ModelCatalog.default,
    ): ModelState =
        withContext(dispatcher) {
            val target = fileFor(spec)
            val partial = File(modelDir, "${spec.fileName}.part")

            runCatching {
                if (!hasRoomFor(spec)) {
                    return@withContext fail(
                        "Not enough free space. ${spec.displayName} needs about " +
                            "${ModelCatalog.formatBytes(ModelCatalog.requiredFreeBytes(spec))}, " +
                            "and this phone has ${ModelCatalog.formatBytes(usableSpaceBytes())} free.",
                    )
                }

                partial.delete()
                _state.value = ModelState.Importing(0, spec.sizeBytes)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        var sinceReport = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                partial.delete()
                                return@withContext fail("Import cancelled.")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            sinceReport += read
                            if (sinceReport >= PROGRESS_REPORT_BYTES) {
                                sinceReport = 0
                                _state.value = ModelState.Importing(copied, spec.sizeBytes)
                            }
                        }
                        output.flush()
                    }
                } ?: return@withContext fail("Could not open the file you picked.")

                if (partial.length() != spec.sizeBytes) {
                    val actual = ModelCatalog.formatBytes(partial.length())
                    partial.delete()
                    return@withContext fail(
                        "That file is $actual, but ${spec.displayName} should be " +
                            "${ModelCatalog.formatBytes(spec.sizeBytes)}. " +
                            "Either the download did not finish, or a different file was picked.",
                    )
                }

                target.delete()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    return@withContext fail("Could not save the model to private storage.")
                }

                refresh(spec)
            }.getOrElse { error ->
                Log.w(TAG, "model import failed", error)
                partial.delete()
                fail(error.message ?: "The import failed.")
            }
        }

    fun delete(spec: ModelSpec = ModelCatalog.default): ModelState {
        fileFor(spec).delete()
        File(modelDir, "${spec.fileName}.part").delete()
        return refresh(spec)
    }

    private fun fail(reason: String): ModelState = ModelState.Invalid(reason).also { _state.value = it }

    private companion object {
        const val TAG = "KavachModel"
        const val COPY_BUFFER_BYTES = 1 shl 20
        const val PROGRESS_REPORT_BYTES = 8L shl 20
    }
}
