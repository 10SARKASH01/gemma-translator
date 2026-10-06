package com.gemmatranslator.offline

import android.os.Looper
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.StringReader
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Local text-only translation. Call translate/close on the app's inference worker. */
class GemmaTranslator internal constructor(
    private val cacheDir: File,
    private val logger: (String) -> Unit,
    private val factory: GemmaEngineFactory,
) : AutoCloseable {
    constructor(cacheDir: File, logger: (String) -> Unit = { Log.i("GemmaTranslator", it) }) :
        this(cacheDir, logger, NativeGemmaEngineFactory)

    private val operationLock = Any()
    private val conversationLock = Any()
    private val cancellation = AtomicLong()
    @Volatile private var closed = false
    private var engine: GemmaEngine? = null
    private var modelIdentity: ModelIdentity? = null
    private var usingGpu = false
    private var activeConversation: GemmaConversation? = null

    fun translate(text: String, sourceName: String, targetName: String, modelFile: File): String {
        val ticket = cancellation.get()
        return synchronized(operationLock) {
            check(!closed) { "Gemma translator has been closed." }
            checkCancelled(ticket)
            if (text.isBlank()) return@synchronized ""
            require(sourceName.isNotBlank() && targetName.isNotBlank()) { "Select both translation languages." }
            require(modelFile.isFile && modelFile.length() > 0) {
                "Gemma model is missing. Complete model setup before translating."
            }
            val started = System.nanoTime()
            val identity = ModelIdentity(modelFile.canonicalPath, modelFile.length(), modelFile.lastModified())
            if (modelIdentity != identity || engine == null) {
                releaseEngine()
                initializeEngine(modelFile, identity, preferGpu = true, ticket = ticket)
            }
            checkCancelled(ticket)
            val prompt = translationPrompt(sourceName, targetName)
            val raw = try {
                infer(prompt, text, ticket)
            } catch (failure: Exception) {
                checkCancelled(ticket)
                if (failure is CancellationException || !usingGpu) throw failure
                // Some Android drivers initialize successfully but reject the first graph.
                // Release that engine before creating the CPU engine: never retain both.
                log("[Gemma] GPU inference failed; retrying locally on CPU: ${failure.message}")
                releaseEngine()
                initializeEngine(modelFile, identity, preferGpu = false, ticket = ticket)
                checkCancelled(ticket)
                infer(prompt, text, ticket)
            }
            checkCancelled(ticket)
            val result = parseTranslation(raw)
            log("[Perf] stage=translation engine=gemma/litert-lm backend=${if (usingGpu) "GPU" else "CPU"} " +
                "source=$sourceName target=$targetName total_ms=${elapsedMillis(started)}")
            result
        }
    }

    /** Safe from the UI thread. Cancels native decoding without deleting active handles. */
    fun cancel() {
        cancellation.incrementAndGet()
        synchronized(conversationLock) {
            activeConversation?.let { conversation ->
                runCatching { conversation.cancel() }
            }
        }
    }

    /** Cancel first, then release native memory on the inference worker. Idempotent. */
    override fun close() {
        closed = true
        cancel()
        synchronized(operationLock) {
            releaseEngine()
        }
    }

    private fun initializeEngine(modelFile: File, identity: ModelIdentity, preferGpu: Boolean, ticket: Long) {
        checkCancelled(ticket)
        val started = System.nanoTime()
        var chosenGpu = preferGpu
        val loaded = if (preferGpu) {
            try {
                factory.open(modelFile, cacheDir, gpu = true)
            } catch (failure: Exception) {
                checkCancelled(ticket)
                chosenGpu = false
                log("[Gemma] GPU unavailable; loading local CPU engine: ${failure.message}")
                factory.open(modelFile, cacheDir, gpu = false)
            }
        } else {
            factory.open(modelFile, cacheDir, gpu = false)
        }
        engine = loaded
        modelIdentity = identity
        usingGpu = chosenGpu
        log("[Gemma] backend=${if (usingGpu) "GPU" else "CPU"} context_tokens=$GEMMA_CONTEXT_TOKENS " +
            "load_ms=${elapsedMillis(started)}")
    }

    private fun infer(prompt: String, text: String, ticket: Long): String {
        val conversation = checkNotNull(engine).conversation(prompt)
        try {
            synchronized(conversationLock) {
                checkCancelled(ticket)
                activeConversation = conversation
            }
            checkCancelled(ticket)
            return conversation.send(text)
        } finally {
            synchronized(conversationLock) {
                if (activeConversation === conversation) activeConversation = null
                conversation.close()
            }
        }
    }

    private fun releaseEngine() {
        val previous = engine
        engine = null
        modelIdentity = null
        usingGpu = false
        previous?.close()
    }

    private fun checkCancelled(ticket: Long) {
        if (closed || cancellation.get() != ticket || Thread.currentThread().isInterrupted) {
            throw CancellationException("Translation cancelled.")
        }
    }

    private fun log(message: String) { runCatching { logger(message) } }
    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000
    private data class ModelIdentity(val path: String, val length: Long, val modified: Long)
}

internal const val GEMMA_CONTEXT_TOKENS = 4096

/** Matches the kiosk prompt while keeping all language pairs equally supported. */
internal fun translationPrompt(sourceName: String, targetName: String): String =
    "Translate from $sourceName into $targetName. Preserve meaning and names. " +
        "Return only valid JSON: {\"translation\":\"translated text\"}. No explanations or Markdown."

/** A malformed response must not be displayed or spoken as a successful translation. */
internal fun parseTranslation(raw: String): String {
    var json = raw.trim().removePrefix("\uFEFF")
    if (json.startsWith("```")) {
        val firstLine = json.indexOf('\n')
        require(firstLine >= 0 && json.endsWith("```")) { "Gemma returned an incomplete translation." }
        val fence = json.substring(0, firstLine).trim()
        require(fence == "```" || fence == "```json") { "Gemma returned an unsupported response format." }
        json = json.substring(firstLine + 1, json.length - 3).trim()
    }
    try {
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            require(reader.hasNext() && reader.nextName() == "translation")
            require(reader.peek() == JsonToken.STRING)
            val translation = reader.nextString().trim()
            require(translation.isNotEmpty() && !reader.hasNext())
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            return translation
        }
    } catch (failure: Exception) {
        throw IllegalArgumentException("Gemma returned an invalid translation. Try a shorter recording.", failure)
    }
}

internal interface GemmaEngineFactory {
    fun open(modelFile: File, cacheDir: File, gpu: Boolean): GemmaEngine
}

internal interface GemmaEngine : AutoCloseable {
    fun conversation(prompt: String): GemmaConversation
}

internal interface GemmaConversation : AutoCloseable {
    fun send(text: String): String
    fun cancel()
}

internal fun engineConfig(modelFile: File, cacheDir: File, gpu: Boolean) = EngineConfig(
    modelPath = modelFile.absolutePath,
    backend = if (gpu) Backend.GPU() else Backend.CPU(numOfThreads = 4),
    maxNumTokens = GEMMA_CONTEXT_TOKENS,
    cacheDir = File(cacheDir, if (gpu) "gemma-gpu" else "gemma-cpu").absolutePath,
    // STT is separate; do not allocate the Gemma audio/vision encoders.
    audioBackend = null,
    visionBackend = null,
)

internal fun conversationConfig(prompt: String) = ConversationConfig(
    systemInstruction = Contents.of(prompt),
    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
    automaticToolCalling = false,
    extraContext = mapOf("enable_thinking" to false),
)

private object NativeGemmaEngineFactory : GemmaEngineFactory {
    override fun open(modelFile: File, cacheDir: File, gpu: Boolean): GemmaEngine {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Load Gemma on the inference worker, not the UI thread." }
        val config = engineConfig(modelFile, cacheDir, gpu)
        val compiledCache = File(checkNotNull(config.cacheDir))
        check(compiledCache.isDirectory || compiledCache.mkdirs()) { "Cannot create Gemma's local cache directory." }
        val native = Engine(config)
        try {
            native.initialize()
        } catch (failure: Throwable) {
            // Engine.close() requires successful initialization in LiteRT-LM 0.13.1.
            if (native.isInitialized()) runCatching { native.close() }
            throw failure
        }
        return object : GemmaEngine {
            override fun conversation(prompt: String): GemmaConversation {
                val conversation: Conversation = native.createConversation(conversationConfig(prompt))
                return object : GemmaConversation {
                    override fun send(text: String) = conversation.sendMessage(text).toString()
                    override fun cancel() = conversation.cancelProcess()
                    override fun close() = conversation.close()
                }
            }
            override fun close() = native.close()
        }
    }
}
