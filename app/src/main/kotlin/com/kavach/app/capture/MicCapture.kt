package com.kavach.app.capture

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.sqrt

/**
 * Ambient microphone capture into a fixed in-memory ring buffer.
 *
 * We capture the room, not the call: Android has blocked third-party access to
 * call audio since Android 10, and `VOICE_CALL` is unavailable without root
 * (docs/ARCHITECTURE.md 2). Ambient capture also covers WhatsApp video calls,
 * which is where digital-arrest scams actually happen.
 *
 * **Nothing here writes to disk.** There is no File, no OutputStream and no
 * cache directory in this class, and there must never be one.
 *
 * Feeds the Whisper path. It is deliberately NOT used alongside
 * [com.kavach.app.inference.SystemAsrTranscriptSource] — the microphone is
 * exclusive, and two owners would fight over it.
 */
class MicCapture(
    private val ringBuffer: AudioRingBuffer = AudioRingBuffer.tenSeconds(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Voiced frames only, bounded and DROP_OLDEST so a slow consumer never blocks the mic. */
    private val frames = Channel<ShortArray>(capacity = FRAME_QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun voicedFrames(): Flow<ShortArray> = frames.receiveAsFlow()

    /**
     * Reads until the coroutine is cancelled. Throws [IllegalStateException] if
     * the microphone is unavailable — which happens legitimately during a call,
     * and is reported to the user rather than swallowed.
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
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
                record.startRecording()

                val frame = ShortArray(AudioRingBuffer.SAMPLES_PER_FRAME)
                while (coroutineContext.isActive) {
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    ringBuffer.write(frame, read)
                    if (isVoiced(frame, read)) frames.trySend(frame.copyOf(read))
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                // Leave nothing in RAM once the session ends.
                ringBuffer.clear()
            }
        }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(minBuffer: Int): AudioRecord {
        // VOICE_RECOGNITION skips the AGC and noise suppression tuned for calls,
        // which is what an ASR front-end wants. MIC is the fallback where an OEM
        // does not expose it.
        for (sourceId in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate =
                runCatching {
                    AudioRecord(
                        sourceId,
                        AudioRingBuffer.SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, AudioRingBuffer.SAMPLES_PER_FRAME * BUFFER_FRAMES * Short.SIZE_BYTES),
                    )
                }.getOrNull()
            if (candidate?.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "capturing with audio source $sourceId")
                return candidate
            }
            runCatching { candidate?.release() }
        }
        error("no usable audio source")
    }

    /** Energy-based VAD. Crude on purpose — it only has to skip silence. */
    private fun isVoiced(
        frame: ShortArray,
        count: Int,
    ): Boolean {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = frame[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count) > VAD_RMS_THRESHOLD
    }

    private companion object {
        const val TAG = "KavachMic"
        const val FRAME_QUEUE = 64
        const val BUFFER_FRAMES = 16
        const val VAD_RMS_THRESHOLD = 500.0
    }
}
