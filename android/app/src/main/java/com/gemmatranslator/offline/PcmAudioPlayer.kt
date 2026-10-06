package com.gemmatranslator.offline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One current PCM chunk. Futures finish after audible playback, not after enqueueing. */
class PcmAudioPlayer internal constructor(private val factory: PlaybackFactory) : AutoCloseable {
    constructor(context: Context) : this(NativePlaybackFactory(context.applicationContext))

    private val lock = Any()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "translator-playback") })
    private var active: Playback? = null
    private var closed = false

    fun play(samples: FloatArray, sampleRate: Int): Future<*> {
        val pcm = pcm16ForPlayback(samples, sampleRate)
        stop()
        return synchronized(lock) {
            check(!closed) { "Speech playback has been closed." }
            val playback = Playback()
            active = playback
            executor.submit { play(playback, pcm, sampleRate) }.also { playback.future = it }
        }
    }

    fun stop() {
        val playback = synchronized(lock) {
            val previous = active
            active = null
            if (previous != null) previous.cancelled.set(true)
            executor.queue.clear()
            previous
        } ?: return
        playback.future?.cancel(true)
        release(playback)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        executor.shutdownNow()
    }

    private fun play(playback: Playback, pcm: ShortArray, sampleRate: Int) {
        try {
            checkPlaying(playback)
            val focus = factory.focus { cancel(playback) }
            synchronized(lock) {
                if (playback.cancelled.get()) {
                    focus.close()
                    throw CancellationException("Speech playback cancelled.")
                }
                playback.focus = focus
            }
            val track = factory.track(sampleRate)
            synchronized(lock) {
                if (playback.cancelled.get()) {
                    track.close()
                    throw CancellationException("Speech playback cancelled.")
                }
                playback.track = track
            }
            track.start()
            val deadline = System.nanoTime() + ((pcm.size.toDouble() / sampleRate + 10) * 1_000_000_000).toLong()
            var written = 0
            while (written < pcm.size) {
                checkPlaying(playback)
                check(System.nanoTime() < deadline) { "Speech playback stalled. Check the output device." }
                val count = track.write(pcm, written, minOf(4096, pcm.size - written))
                check(count >= 0) { "Could not play speech audio ($count). Check the output device." }
                written += count
                if (count == 0) Thread.sleep(5)
            }
            while (track.playedFrames() < pcm.size) {
                checkPlaying(playback)
                check(System.nanoTime() < deadline) { "Speech playback stalled. Check the output device." }
                Thread.sleep(10)
            }
            checkPlaying(playback)
        } finally {
            release(playback)
            synchronized(lock) { if (active === playback) active = null }
        }
    }

    private fun cancel(playback: Playback) {
        playback.cancelled.set(true)
        playback.future?.cancel(true)
        release(playback)
    }

    private fun checkPlaying(playback: Playback) {
        if (playback.cancelled.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Speech playback cancelled.")
        }
    }

    private fun release(playback: Playback) {
        val resources = synchronized(lock) {
            val pair = Pair(playback.track, playback.focus)
            playback.track = null
            playback.focus = null
            pair
        }
        runCatching { resources.first?.close() }
        runCatching { resources.second?.close() }
    }

    private class Playback {
        val cancelled = AtomicBoolean()
        @Volatile var future: Future<*>? = null
        var track: PlaybackTrack? = null
        var focus: AutoCloseable? = null
    }
}

/** Validate engine output before touching Android audio hardware. */
internal fun pcm16ForPlayback(samples: FloatArray, sampleRate: Int): ShortArray {
    require(sampleRate in 8000..96000) { "Speech engine returned an unsupported sample rate ($sampleRate)." }
    require(samples.isNotEmpty() && samples.size.toLong() <= sampleRate.toLong() * 120) {
        "Speech engine returned empty or excessively long audio."
    }
    require(samples.all { it.isFinite() }) { "Speech engine returned invalid audio samples." }
    return ShortArray(samples.size) { index ->
        (samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
}

internal interface PlaybackFactory {
    fun focus(onLoss: () -> Unit): AutoCloseable
    fun track(sampleRate: Int): PlaybackTrack
}

internal interface PlaybackTrack : AutoCloseable {
    fun start()
    fun write(samples: ShortArray, offset: Int, length: Int): Int
    fun playedFrames(): Long
}

private class NativePlaybackFactory(context: Context) : PlaybackFactory {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override fun focus(onLoss: () -> Unit): AutoCloseable {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) onLoss() }, Handler(Looper.getMainLooper()))
            .build()
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio focus is unavailable. Keep the app open and finish any call before playing speech."
        }
        val abandoned = AtomicBoolean()
        return AutoCloseable { if (abandoned.compareAndSet(false, true)) manager.abandonAudioFocusRequest(request) }
    }

    override fun track(sampleRate: Int): PlaybackTrack {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Audio output does not support $sampleRate Hz mono speech." }
        val audio = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (audio.state != AudioTrack.STATE_INITIALIZED) {
            audio.release()
            throw IllegalStateException("Audio output initialization failed. Check the speaker or headphones.")
        }
        return object : PlaybackTrack {
            private val trackLock = Any()
            private var released = false
            override fun start() = synchronized(trackLock) {
                check(!released) { "Speech playback cancelled." }
                audio.play()
            }
            override fun write(samples: ShortArray, offset: Int, length: Int) = synchronized(trackLock) {
                if (released) 0 else audio.write(samples, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            }
            override fun playedFrames() = synchronized(trackLock) {
                if (released) 0L else audio.playbackHeadPosition.toLong() and 0xffffffffL
            }
            override fun close() = synchronized(trackLock) {
                if (!released) {
                    released = true
                    runCatching { audio.pause() }
                    runCatching { audio.flush() }
                    audio.release()
                }
            }
        }
    }
}
