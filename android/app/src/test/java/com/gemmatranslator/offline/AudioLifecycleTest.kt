package com.gemmatranslator.offline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AudioLifecycleTest {
    @Test fun microphoneNormalizesMonoPcmAndReleasesTheDeviceBeforeDelivery() {
        val input = FakeInput(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))
        val recorder = MicrophoneRecorder({ input }, 100)
        val ready = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val result = AtomicReference<FloatArray>()
        assertTrue(recorder.start({ ready.countDown() }, { fail(it) }, { fail("Unexpected limit") }))
        assertFalse(recorder.start({}, {}, {}))
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertTrue(input.readOnce.await(2, TimeUnit.SECONDS))
        recorder.stop { samples ->
            assertEquals(1, input.closes.get())
            result.set(samples)
            delivered.countDown()
        }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768f), result.get(), 0f)
        recorder.close()
    }

    @Test fun releasingBeforeMicrophoneInitializationDiscardsLateCapture() {
        val initializing = CountDownLatch(1)
        val allowInitialization = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val readyCount = AtomicInteger()
        val input = FakeInput(shortArrayOf(1))
        val recorder = MicrophoneRecorder({
            initializing.countDown()
            assertTrue(allowInitialization.await(2, TimeUnit.SECONDS))
            input
        }, 100)
        assertTrue(recorder.start({ readyCount.incrementAndGet() }, { fail(it) }, {}))
        assertTrue(initializing.await(2, TimeUnit.SECONDS))
        recorder.stop { samples ->
            assertTrue(samples.isEmpty())
            delivered.countDown()
        }
        allowInitialization.countDown()
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(0, input.starts.get())
        assertEquals(0, readyCount.get())
        assertEquals(1, input.closes.get())
        recorder.close()
    }

    @Test fun microphoneLimitRetainsSamplesUntilTheStopCallback() {
        val input = FakeInput(shortArrayOf(1, 2, 3, 4, 5))
        val limit = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val recorder = MicrophoneRecorder({ input }, 3)
        assertTrue(recorder.start({}, { fail(it) }, { limit.countDown() }))
        assertTrue(limit.await(2, TimeUnit.SECONDS))
        assertFalse(recorder.start({}, {}, {}))
        recorder.stop { samples ->
            assertArrayEquals(floatArrayOf(1f / 32768f, 2f / 32768f, 3f / 32768f), samples, 0f)
            delivered.countDown()
        }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, input.closes.get())
        recorder.close()
    }

    @Test fun cancelledMicrophoneDoesNotDeliverOrStartAgainAfterClose() {
        val input = FakeInput(shortArrayOf(1))
        val ready = CountDownLatch(1)
        val recorder = MicrophoneRecorder({ input }, 100)
        recorder.start({ ready.countDown() }, { fail(it) }, { fail("Unexpected limit") })
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        recorder.cancel()
        assertTrue(input.released.await(2, TimeUnit.SECONDS))
        recorder.close()
        recorder.close()
        assertFalse(recorder.start({}, {}, {}))
        assertEquals(1, input.closes.get())
    }

    @Test fun missingMicrophonePermissionGivesAnActionableError() {
        val failure = AtomicReference<String>()
        val errored = CountDownLatch(1)
        val recorder = MicrophoneRecorder({ throw SecurityException("denied") }, 100)
        recorder.start({}, { message -> failure.set(message); errored.countDown() }, {})
        assertTrue(errored.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get().contains("permission"))
        assertTrue(failure.get().contains("Settings"))
        recorder.close()
    }

    @Test fun playbackRejectsInvalidEngineAudioAndClampsFiniteSamples() {
        assertArrayEquals(shortArrayOf(-32767, -16383, 0, 16383, 32767),
            pcm16ForPlayback(floatArrayOf(-2f, -0.5f, 0f, 0.5f, 2f), 22050))
        for (samples in listOf(FloatArray(0), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { pcm16ForPlayback(samples, 16000) }
        }
        assertThrows(IllegalArgumentException::class.java) { pcm16ForPlayback(floatArrayOf(0f), 0) }
        assertThrows(IllegalArgumentException::class.java) { pcm16ForPlayback(FloatArray(8000 * 121), 8000) }
    }

    @Test fun playbackFutureWaitsForAudibleFramesAndReleasesTrackAndFocus() {
        val factory = FakePlaybackFactory()
        val player = PcmAudioPlayer(factory)
        val future = player.play(floatArrayOf(0f, 0.5f, -0.5f), 16000)
        assertTrue(factory.trackReady.await(2, TimeUnit.SECONDS))
        assertTrue(factory.track.wrote.await(2, TimeUnit.SECONDS))
        assertFalse(future.isDone)
        factory.track.frames.set(3)
        future.get(2, TimeUnit.SECONDS)
        assertArrayEquals(shortArrayOf(0, 16383, -16383), factory.track.pcm.get())
        assertEquals(1, factory.track.closes.get())
        assertEquals(1, factory.abandoned.get())
        player.close()
        player.close()
    }

    @Test fun stoppingPlaybackCancelsWaitingAudioAndReleasesResources() {
        val factory = FakePlaybackFactory()
        val player = PcmAudioPlayer(factory)
        val future = player.play(FloatArray(100), 16000)
        assertTrue(factory.track.wrote.await(2, TimeUnit.SECONDS))
        player.stop()
        assertTrue(future.isCancelled)
        assertEquals(1, factory.track.closes.get())
        assertEquals(1, factory.abandoned.get())
        player.close()
    }

    @Test fun audioFocusLossCancelsSpeechAndReturnsTheFocus() {
        val factory = FakePlaybackFactory()
        val player = PcmAudioPlayer(factory)
        val future = player.play(FloatArray(100), 16000)
        assertTrue(factory.track.wrote.await(2, TimeUnit.SECONDS))
        factory.loss.get().invoke()
        assertTrue(future.isCancelled)
        assertEquals(1, factory.track.closes.get())
        assertEquals(1, factory.abandoned.get())
        player.close()
    }

    private class FakeInput(val pcm: ShortArray) : RecordingInput {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val readOnce = CountDownLatch(1)
        val released = CountDownLatch(1)
        var read = false
        override fun start() { starts.incrementAndGet() }
        override fun read(samples: ShortArray): Int {
            if (read) return 0
            pcm.copyInto(samples)
            read = true
            readOnce.countDown()
            return pcm.size
        }
        override fun close() { closes.incrementAndGet(); released.countDown() }
    }

    private class FakePlaybackFactory : PlaybackFactory {
        val abandoned = AtomicInteger()
        val track = FakeTrack()
        val trackReady = CountDownLatch(1)
        val loss = AtomicReference<() -> Unit>()
        override fun focus(onLoss: () -> Unit): AutoCloseable {
            loss.set(onLoss)
            return AutoCloseable { abandoned.incrementAndGet() }
        }
        override fun track(sampleRate: Int): PlaybackTrack { trackReady.countDown(); return track }
    }

    private class FakeTrack : PlaybackTrack {
        val pcm = AtomicReference<ShortArray>()
        val frames = AtomicInteger()
        val closes = AtomicInteger()
        val wrote = CountDownLatch(1)
        override fun start() {}
        override fun write(samples: ShortArray, offset: Int, length: Int): Int {
            pcm.set(samples.copyOfRange(offset, offset + length))
            wrote.countDown()
            return length
        }
        override fun playedFrames() = frames.get().toLong()
        override fun close() { closes.incrementAndGet() }
    }
}
