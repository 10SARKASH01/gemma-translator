package com.gemmatranslator.offline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.Executors

/** Push-to-talk capture: 16 kHz mono PCM, retained only in memory. */
class MicrophoneRecorder internal constructor(
    private val inputFactory: () -> RecordingInput,
    private val maxSamples: Int = MICROPHONE_SAMPLE_RATE * 60,
) : AutoCloseable {
    constructor() : this({ NativeRecordingInput() })

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "translator-microphone") }
    private var active: Capture? = null
    private var closed = false

    fun start(onReady: () -> Unit, onError: (String) -> Unit, onLimit: () -> Unit): Boolean = synchronized(lock) {
        if (closed || active != null) return@synchronized false
        val capture = Capture(onReady, onError, onLimit)
        active = capture
        executor.execute { captureAudio(capture) }
        true
    }

    /** Returns asynchronously; never joins the capture thread or blocks the UI. */
    fun stop(onSamples: (FloatArray) -> Unit) {
        synchronized(lock) {
            val capture = active
            if (closed || capture == null) {
                if (!closed) executor.execute { onSamples(FloatArray(0)) }
                return
            }
            if (capture.delivery != null) return
            capture.delivery = onSamples
            // A released button during permission/device startup cannot start late recording.
            if (!capture.ready) capture.discard = true
            capture.stop = true
            if (capture.finished) executor.execute { deliver(capture) }
        }
    }

    fun cancel() {
        synchronized(lock) {
            active?.let { capture ->
                capture.discard = true
                capture.stop = true
                capture.delivery = null
                if (capture.finished) active = null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cancel()
            executor.shutdown()
        }
    }

    private fun captureAudio(capture: Capture) {
        var input: RecordingInput? = null
        val samples = FloatArray(maxSamples)
        var size = 0
        var failure: String? = null
        var limited = false
        try {
            if (capture.stop) return
            val device = inputFactory()
            input = device
            synchronized(lock) {
                if (capture.stop || closed) return
                device.start()
                capture.ready = true
                runCatching { capture.onReady() }
            }
            val pcm = ShortArray(2048)
            while (!capture.stop) {
                // Nonblocking reads let stop/cancel finish without joining native I/O.
                val count = device.read(pcm)
                if (count < 0) throw IllegalStateException(microphoneReadError(count))
                if (count == 0) {
                    Thread.sleep(10)
                    continue
                }
                val accepted = minOf(count, samples.size - size)
                for (index in 0 until accepted) samples[size + index] = pcm[index] / 32768.0f
                size += accepted
                if (size == samples.size) {
                    limited = true
                    capture.stop = true
                    break
                }
            }
        } catch (error: SecurityException) {
            failure = "Microphone permission is missing. Allow microphone access in Android Settings."
        } catch (error: Exception) {
            if (!capture.stop) failure = error.message ?: "Microphone capture failed. Check the recording permission and try again."
        } finally {
            runCatching { input?.close() }
            synchronized(lock) {
                capture.samples = if (capture.discard || failure != null) FloatArray(0) else samples.copyOf(size)
                capture.finished = true
                if (failure != null) capture.delivery = null
                if (capture.discard || failure != null || closed) {
                    if (active === capture) active = null
                }
            }
            if (!capture.discard && !closed) {
                if (failure != null) runCatching { capture.onError(checkNotNull(failure)) }
                else if (limited) runCatching { capture.onLimit() }
            }
            deliver(capture)
        }
    }

    private fun deliver(capture: Capture) {
        val delivery: ((FloatArray) -> Unit)?
        val samples: FloatArray
        synchronized(lock) {
            delivery = if (!closed && !capture.delivered) capture.delivery else null
            if (delivery == null) return
            capture.delivered = true
            samples = capture.samples ?: FloatArray(0)
            capture.samples = null
            if (active === capture) active = null
        }
        runCatching { delivery?.invoke(samples) }
    }

    private class Capture(val onReady: () -> Unit, val onError: (String) -> Unit, val onLimit: () -> Unit) {
        @Volatile var stop = false
        @Volatile var discard = false
        var ready = false
        var finished = false
        var delivered = false
        var delivery: ((FloatArray) -> Unit)? = null
        var samples: FloatArray? = null
    }
}

internal const val MICROPHONE_SAMPLE_RATE = 16000

internal fun microphoneReadError(code: Int) = when (code) {
    AudioRecord.ERROR_DEAD_OBJECT -> "Microphone device disconnected. Reconnect it and record again."
    AudioRecord.ERROR_INVALID_OPERATION -> "Microphone could not start. Close other recording apps and try again."
    AudioRecord.ERROR_BAD_VALUE -> "Microphone does not support the required 16 kHz recording format."
    else -> "Microphone read failed ($code). Check the audio device and try again."
}

internal interface RecordingInput : AutoCloseable {
    fun start()
    fun read(samples: ShortArray): Int
}

private class NativeRecordingInput : RecordingInput {
    private val record: AudioRecord

    init {
        val minimum = AudioRecord.getMinBufferSize(
            MICROPHONE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Microphone does not support 16 kHz mono PCM recording on this device." }
        record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(MICROPHONE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                .build()
        } catch (error: SecurityException) {
            // Permission can be revoked after the Activity's check, before this worker runs.
            throw SecurityException("Microphone permission was denied or revoked. Allow it in Android Settings.", error)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone initialization failed. Check permission and close other recording apps.")
        }
    }

    override fun start() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not begin recording." }
    }

    override fun read(samples: ShortArray) = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)

    override fun close() {
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        record.release()
    }
}
