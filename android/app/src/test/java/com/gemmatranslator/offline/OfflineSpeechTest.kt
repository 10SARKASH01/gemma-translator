package com.gemmatranslator.offline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSpeechTest {
    private val languages = listOf("ar", "en", "es", "ja", "zh", "ko", "fa", "ur", "fr")

    @Test
    fun everyLanguageUsesForcedMultilingualTranscription() {
        for (language in languages) {
            val config = OfflineSpeech.whisperConfig(File("models/whisper"), language)
            assertEquals(language, config.modelConfig.whisper.language)
            assertEquals("transcribe", config.modelConfig.whisper.task)
            assertTrue(config.modelConfig.whisper.encoder.endsWith("small-encoder.int8.onnx"))
            assertTrue(config.modelConfig.whisper.decoder.endsWith("small-decoder.int8.onnx"))
            assertFalse(config.modelConfig.tokens.contains(".en-"))
        }
    }

    @Test
    fun neuralVoicesUseCorrectLanguageAndDistinctPersianUrduModels() {
        assertEquals(languages.toSet(), SpeechAssets.VOICES.keys)
        for (language in listOf("ar", "en", "es", "ja", "ko", "fr")) {
            assertEquals(SpeechAssets.SUPERTONIC, SpeechAssets.voice(language))
            assertEquals(language, OfflineSpeech.generationConfig(language).extra?.get("lang"))
            assertTrue(OfflineSpeech.ttsConfig(File("voices"), language).model.supertonic.textEncoder.endsWith("text_encoder.int8.onnx"))
        }
        assertEquals(SpeechAssets.CHINESE, SpeechAssets.voice("zh"))
        assertEquals(47, OfflineSpeech.generationConfig("zh").sid)
        assertTrue(OfflineSpeech.ttsConfig(File("voices"), "zh").model.kokoro.lexicon.endsWith("lexicon-zh.txt"))
        assertTrue(OfflineSpeech.ttsConfig(File("voices"), "fa").model.vits.model.endsWith("fa_IR-amir-medium.onnx"))
        assertTrue(OfflineSpeech.ttsConfig(File("voices"), "ur").model.vits.model.endsWith("ur_PK-fasih-medium.onnx"))
    }

    @Test
    fun longAudioIsEntirelyCoveredWithoutNativeTruncation() {
        for (count in listOf(0, 1, OfflineSpeech.WINDOW_SAMPLES, OfflineSpeech.WINDOW_SAMPLES + 1, 16_000 * 90)) {
            val windows = OfflineSpeech.audioWindows(count)
            assertEquals(count, windows.sumOf { it.last - it.first + 1 })
            assertTrue(windows.all { it.last - it.first + 1 <= OfflineSpeech.WINDOW_SAMPLES })
            for ((left, right) in windows.zipWithNext()) assertEquals(left.last + 1, right.first)
        }
    }

    @Test
    fun setupCatalogPinsHashesAndDeduplicatesSharedVoicePacks() {
        assertEquals(5, SpeechAssets.ALL.size)
        assertEquals(SpeechAssets.ALL.size, SpeechAssets.ALL.map { it.id }.toSet().size)
        assertTrue(SpeechAssets.ALL.all { it.sha256.matches(Regex("[a-f0-9]{64}")) && it.bytes > 0 })
        assertTrue(SpeechAssets.ALL.all { it.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/") })
        assertTrue(SpeechAssets.URDU.requiredFiles.contains("espeak-ng-data/lang/inc/ur"))
        assertTrue(SpeechAssets.PERSIAN.requiredFiles.contains("espeak-ng-data/lang/ira/fa"))
        for (asset in listOf(SpeechAssets.CHINESE, SpeechAssets.PERSIAN, SpeechAssets.URDU)) {
            // Native eSpeak also requires shared tables/default dictionaries; preserve the complete tree.
            assertTrue(asset.requiredFiles.contains("espeak-ng-data"))
        }
    }

    @Test
    fun missingModelsAndUnsupportedLanguagesHaveUsefulErrorsWithoutEnglishFallback() {
        val directory = File(System.getProperty("java.io.tmpdir"), "speech-test-${System.nanoTime()}")
        val engine = OfflineSpeech(directory)
        try {
            assertFailureContains("Offline setup") { engine.transcribe(floatArrayOf(0.1f), "fa") }
            assertFailureContains("Offline setup") { engine.synthesize("یہ ایک ٹیسٹ ہے", "ur") }
            assertFailureContains("Unsupported speech language") { engine.transcribe(floatArrayOf(0.1f), "xx") }
            assertFailureContains("Unsupported speech language") { engine.synthesize("test", "xx") }
        } finally {
            engine.close()
        }
        assertFailureContains("closed") { engine.synthesize("Bonjour", "fr") }
    }

    private fun assertFailureContains(message: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected an error containing $message")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(message))
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(message))
        }
    }
}
