package com.gemmatranslator.offline

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

data class SpeechAudio(val samples: FloatArray, val sampleRate: Int)

/** Native, local-only speech. One resident recognizer and one resident voice bound RAM use. */
class OfflineSpeech(
    private val root: File,
    private val logger: (String) -> Unit = {},
) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null
    private var activeVoiceId: String? = null
    private var closed = false

    @Synchronized
    fun transcribe(samples: FloatArray, languageCode: String): String {
        check(!closed) { "Speech engine has been closed." }
        SpeechAssets.voice(languageCode) // Reject unsupported languages instead of guessing English.
        require(samples.isNotEmpty()) { "No microphone audio was recorded. Hold the talk button while speaking." }
        require(samples.all { it.isFinite() }) { "Microphone audio contains invalid samples." }
        logger("[STT] lang=$languageCode engine=sherpa-onnx/whisper-small-int8")
        val directory = requireAsset(SpeechAssets.STT)
        val config = whisperConfig(directory, languageCode)
        val engine = recognizer ?: nativeOperation("Load the multilingual Whisper model") {
            OfflineRecognizer(config = config).also { recognizer = it }
        }
        // Sherpa updates decoder language without reloading encoder/decoder model weights.
        engine.setConfig(config)
        val texts = mutableListOf<String>()
        for (range in audioWindows(samples.size)) {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(range.first, range.last + 1), SAMPLE_RATE)
                nativeOperation("Recognize $languageCode speech") { engine.decode(stream) }
                engine.getResult(stream).text.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
            } finally {
                stream.release()
            }
        }
        return texts.joinToString(" ")
    }

    @Synchronized
    fun synthesize(text: String, languageCode: String): SpeechAudio {
        check(!closed) { "Speech engine has been closed." }
        require(text.isNotBlank()) { "There is no translated text to speak." }
        val asset = SpeechAssets.voice(languageCode)
        logger("[TTS] lang=$languageCode engine=sherpa-onnx/${asset.id}")
        val directory = requireAsset(asset)
        if (activeVoiceId != asset.id) {
            tts?.release()
            tts = null
            activeVoiceId = null
        }
        val engine = tts ?: nativeOperation("Load the $languageCode offline voice") {
            OfflineTts(config = ttsConfig(directory, languageCode)).also {
                tts = it
                activeVoiceId = asset.id
            }
        }
        val audio = nativeOperation("Synthesize $languageCode speech") {
            engine.generateWithConfig(text.trim(), generationConfig(languageCode))
        }
        check(audio.sampleRate > 0 && audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() }
            && audio.samples.any { kotlin.math.abs(it) > 0.000001f }) {
            "The $languageCode voice returned invalid audio. Reinstall its voice in Offline setup."
        }
        return SpeechAudio(audio.samples, audio.sampleRate)
    }

    private fun requireAsset(asset: AssetSpec): File {
        val directory = File(root, asset.id)
        val missing = asset.requiredFiles.filter { !File(directory, it).let { file ->
            if (file.isDirectory) file.walkTopDown().any { it.isFile && it.length() > 0L }
            else file.isFile && file.length() > 0L
        } }
        check(missing.isEmpty()) {
            "${asset.title} is missing or incomplete (${missing.firstOrNull()}). Open Offline setup and download this model."
        }
        return directory
    }

    private inline fun <T> nativeOperation(action: String, block: () -> T): T = try {
        block()
    } catch (error: LinkageError) {
        throw IllegalStateException("$action failed: the ARM64 speech runtime could not load. Reinstall the APK on a 64-bit Android phone.", error)
    } catch (error: Exception) {
        throw IllegalStateException("$action failed: ${error.message ?: error.javaClass.simpleName}. Check Offline setup or reinstall the affected model.", error)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer?.release()
        recognizer = null
        tts?.release()
        tts = null
        activeVoiceId = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        // Native Whisper accepts at most 29.5 seconds. Split explicitly so long input is preserved.
        internal const val WINDOW_SAMPLES = SAMPLE_RATE * 28

        internal fun audioWindows(sampleCount: Int): List<IntRange> {
            require(sampleCount >= 0)
            return (0 until sampleCount step WINDOW_SAMPLES).map { start ->
                start until minOf(start + WINDOW_SAMPLES, sampleCount)
            }
        }

        internal fun whisperConfig(directory: File, languageCode: String): OfflineRecognizerConfig {
            SpeechAssets.voice(languageCode)
            return OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(directory, "small-encoder.int8.onnx").absolutePath,
                        decoder = File(directory, "small-decoder.int8.onnx").absolutePath,
                        language = languageCode,
                        task = "transcribe",
                    ),
                    tokens = File(directory, "small-tokens.txt").absolutePath,
                    modelType = "whisper",
                    numThreads = 4,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
        }

        internal fun generationConfig(languageCode: String): GenerationConfig {
            val asset = SpeechAssets.voice(languageCode)
            return when (asset.id) {
                SpeechAssets.SUPERTONIC.id -> GenerationConfig(
                    sid = 6, numSteps = 8, speed = 1.0f, extra = mapOf("lang" to languageCode),
                )
                SpeechAssets.CHINESE.id -> GenerationConfig(sid = 47, speed = 1.0f)
                else -> GenerationConfig(sid = 0, speed = 1.0f)
            }
        }

        internal fun ttsConfig(directory: File, languageCode: String): OfflineTtsConfig {
            val asset = SpeechAssets.voice(languageCode)
            fun path(name: String) = File(directory, name).absolutePath
            val model = when (asset.id) {
                SpeechAssets.SUPERTONIC.id -> OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = path("duration_predictor.int8.onnx"),
                        textEncoder = path("text_encoder.int8.onnx"),
                        vectorEstimator = path("vector_estimator.int8.onnx"),
                        vocoder = path("vocoder.int8.onnx"),
                        ttsJson = path("tts.json"),
                        unicodeIndexer = path("unicode_indexer.bin"),
                        voiceStyle = path("voice.bin"),
                    ), numThreads = 2, provider = "cpu",
                )
                SpeechAssets.CHINESE.id -> OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = path("model.int8.onnx"), voices = path("voices.bin"),
                        tokens = path("tokens.txt"), lexicon = path("lexicon-zh.txt"),
                        dataDir = path("espeak-ng-data"), lang = "zh",
                    ), numThreads = 2, provider = "cpu",
                )
                else -> {
                    val stem = if (languageCode == "fa") "fa_IR-amir-medium" else "ur_PK-fasih-medium"
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = path("$stem.onnx"), tokens = path("tokens.txt"),
                            dataDir = path("espeak-ng-data"),
                        ), numThreads = 2, provider = "cpu",
                    )
                }
            }
            return OfflineTtsConfig(
                model = model,
                ruleFsts = if (languageCode == "zh") path("number-zh.fst") else "",
                maxNumSentences = 1,
            )
        }
    }
}
