package com.kavach.app.capture

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.kavach.domain.AudioRingBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Kavach's own microphone.
 *
 * **This class is load-bearing and it is not interchangeable with letting
 * `SpeechRecognizer` open the mic itself.** Android's audio policy decides
 * whether to hand an app real samples or digital silence based on the UID that
 * opened the `AudioRecord`. When the recogniser opens it, the recording belongs
 * to the recogniser's process, which is not an accessibility service, so during
 * a call it is silenced — no matter what Kavach registers or shows on screen.
 * Opening it here puts the recording in Kavach's UID, which is the only way the
 * documented accessibility exemption can apply to us.
 *
 * Source must stay `VOICE_RECOGNITION`: the exemption covers `VOICE_RECOGNITION`
 * and `HOTWORD` only. The `MIC` fallback exists for devices that do not expose
 * the former, and is therefore refused during a call, where it cannot work.
 *
 * **Nothing here writes to disk.** No File, no OutputStream, no cache directory,
 * and there must never be one (CLAUDE.md hard rule 3).
 */
class MicCapture(
    private val audioManager: AudioManager,
    private val diagnostics: CaptureDiagnostics,
    private val ringBuffer: AudioRingBuffer = AudioRingBuffer.tenSeconds(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Every frame, not just voiced ones: a recogniser does its own endpointing
     * and gets confused by pre-gated audio. Bounded and DROP_OLDEST so a stalled
     * consumer degrades quality and never blocks the microphone thread
     * (CLAUDE.md hard rule 7).
     */
    private val frames = Channel<ShortArray>(capacity = FRAME_QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun frames(): Flow<ShortArray> = frames.receiveAsFlow()

    /**
     * Reads until the coroutine is cancelled.
     *
     * Failures are reported through [diagnostics] and rethrown, never swallowed:
     * a session that believes it is listening while it is not is the one failure
     * mode this whole design exists to prevent.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(): Unit =
        withContext(dispatcher) {
            val minBuffer =
                AudioRecord.getMinBufferSize(
                    AudioRingBuffer.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minBuffer > 0) { "AudioRecord reported no usable buffer size" }

            val record = createRecord(minBuffer)
            val watcher = silenceWatcher(record)
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
                record.startRecording()
                diagnostics.onOwner(MicOwner.KAVACH)

                val frame = ShortArray(AudioRingBuffer.SAMPLES_PER_FRAME)
                while (coroutineContext.isActive) {
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    ringBuffer.write(frame, read)
                    diagnostics.onFrame(rmsDb(frame, read))
                    frames.trySend(frame.copyOf(read))
                }
            } catch (e: IllegalStateException) {
                diagnostics.onCaptureError(e.message)
                throw e
            } finally {
                watcher?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
                runCatching { record.stop() }
                runCatching { record.release() }
                diagnostics.onOwner(MicOwner.NONE)
                // Leave nothing in RAM once the session ends.
                ringBuffer.clear()
            }
        }

    /**
     * Asks the platform, rather than guessing, whether it is feeding us silence.
     *
     * The callback only fires on change, which is why [rmsDb] is measured on
     * every frame as well — between them, "we are muted" and "the room is quiet"
     * are distinguishable, and the UI can say which.
     */
    private fun silenceWatcher(record: AudioRecord): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val callback =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val mine = configs?.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                    diagnostics.onSilenced(mine.isClientSilenced)
                    Log.i(
                        TAG,
                        "silenced=${mine.isClientSilenced} source=${mine.clientAudioSource} mode=${audioManager.mode}",
                    )
                }
            }
        audioManager.registerAudioRecordingCallback(callback, null)
        return callback
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(minBuffer: Int): AudioRecord {
        val bufferBytes =
            maxOf(minBuffer, AudioRingBuffer.SAMPLES_PER_FRAME * BUFFER_FRAMES * Short.SIZE_BYTES)

        for (sourceId in preferredSources()) {
            val candidate =
                runCatching {
                    AudioRecord(
                        sourceId,
                        AudioRingBuffer.SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes,
                    )
                }.getOrNull()
            if (candidate?.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "capturing with audio source $sourceId, mode ${audioManager.mode}")
                return candidate
            }
            runCatching { candidate?.release() }
        }
        error("no usable audio source")
    }

    /**
     * `VOICE_RECOGNITION` always. `MIC` only outside a call.
     *
     * The accessibility exemption lists `VOICE_RECOGNITION` and `HOTWORD`; `MIC`
     * is not on it. Falling back to `MIC` mid-call would open successfully and
     * then return silence, which is precisely the failure this class is built to
     * make impossible.
     */
    private fun preferredSources(): List<Int> =
        if (inCall()) {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        }

    private fun inCall(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    /** dBFS. Digital silence is -inf; a quiet room sits around -55 dB. */
    private fun rmsDb(
        frame: ShortArray,
        count: Int,
    ): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = frame[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else DB_PER_DECADE * log10(rms / Short.MAX_VALUE)
    }

    private companion object {
        const val TAG = "KavachMic"
        const val FRAME_QUEUE = 64
        const val BUFFER_FRAMES = 16

        /** Amplitude, not power, so twenty rather than ten. */
        const val DB_PER_DECADE = 20.0
    }
}
