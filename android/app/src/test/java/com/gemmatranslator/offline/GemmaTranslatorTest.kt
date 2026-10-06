package com.gemmatranslator.offline

import com.google.ai.edge.litertlm.Backend
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GemmaTranslatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyLanguagePairUsesTheSameCompactPrompt() {
        val languages = SupportedLanguages.all.map { it.name }
        for (source in languages) for (target in languages) {
            if (source == target) continue
            val prompt = translationPrompt(source, target)
            assertTrue(prompt.contains("from $source into $target"))
            assertTrue(prompt.contains("{\"translation\":\"translated text\"}"))
            assertTrue(prompt.length < 200)
        }
    }

    @Test fun configurationIsBoundedTextOnlyAndDeterministic() {
        val model = temporary.newFile("model.litertlm")
        val gpu = engineConfig(model, temporary.root, gpu = true)
        assertTrue(gpu.backend is Backend.GPU)
        assertEquals(4096, gpu.maxNumTokens)
        assertNull(gpu.audioBackend)
        assertNull(gpu.visionBackend)
        val cpu = engineConfig(model, temporary.root, gpu = false)
        assertEquals(4, (cpu.backend as Backend.CPU).numOfThreads)
        val conversation = conversationConfig("Translate.")
        assertEquals(0.0, conversation.samplerConfig!!.temperature, 0.0)
        assertEquals(1, conversation.samplerConfig!!.topK)
        assertEquals(false, conversation.extraContext["enable_thinking"])
        assertFalse(conversation.automaticToolCalling)
        assertTrue(conversation.initialMessages.isEmpty())
    }

    @Test fun parserHandlesUnicodeEscapesWhitespaceAndCompleteCodeFences() {
        assertEquals("سلام دنیا", parseTranslation("{\"translation\":\"سلام دنیا\"}"))
        assertEquals("ہیلو", parseTranslation("```json\n{\"translation\":\"ہیلو\"}\n```"))
        assertEquals("こんにちは", parseTranslation("```\n{\"translation\":\"こんにちは\"}\n```"))
        assertEquals("Hi\n\"Sam\"", parseTranslation("{\"translation\":\"  Hi\\n\\\"Sam\\\"  \"}"))
        assertEquals("سلام", parseTranslation("\uFEFF{\"translation\":\"\\u0633\\u0644\\u0627\\u0645\"}"))
    }

    @Test fun parserRejectsInvalidMissingAndNonStringTranslations() {
        for (raw in listOf("", "hello", "{}", "{\"translation\":null}", "{\"translation\":true}",
            "{\"translation\":42}", "{\"translation\":\" \"}", "{\"translation\":\"hi\",\"explanation\":\"x\"}",
            "{\"translation\":\"hi\"} trailing", "{translation:'hi'}", "```json\n{\"translation\":\"hi\"}")) {
            assertThrows("Must reject $raw", IllegalArgumentException::class.java) { parseTranslation(raw) }
        }
    }

    @Test fun retainedEngineUsesFreshClosedConversationsAndIdempotentClose() {
        val factory = FakeFactory()
        val translator = translator(factory)
        val model = model()
        repeat(2) { assertEquals("سلام", translator.translate("Hello", "English", "Persian", model)) }
        assertEquals(listOf(true), factory.attempts)
        assertEquals(2, factory.engines.single().conversations.size)
        assertTrue(factory.engines.single().conversations.all { it.closed })
        translator.close()
        translator.close()
        assertEquals(1, factory.engines.single().closes)
        assertThrows(IllegalStateException::class.java) { translator.translate("Hello", "English", "Persian", model) }
    }

    @Test fun gpuInitializationFailureFallsBackToOneRetainedCpuEngine() {
        val factory = FakeFactory(gpuInitializationFailure = true)
        val translator = translator(factory)
        val model = model()
        repeat(2) { translator.translate("Hello", "English", "Persian", model) }
        assertEquals(listOf(true, false), factory.attempts)
        assertEquals(1, factory.engines.size)
        translator.close()
    }

    @Test fun gpuInferenceFailureClosesGpuBeforeCpuRetry() {
        val factory = FakeFactory(gpuInferenceFailure = true)
        val translator = translator(factory)
        assertEquals("سلام", translator.translate("Hello", "English", "Persian", model()))
        assertEquals(listOf(true, false), factory.attempts)
        assertEquals(1, factory.engines.first().closes)
        assertTrue(factory.engines.first().conversations.single().closed)
        translator.close()
    }

    @Test fun malformedOutputIsNotMistakenForAGpuFailure() {
        val factory = FakeFactory(response = "invalid JSON")
        val translator = translator(factory)
        assertThrows(IllegalArgumentException::class.java) { translator.translate("Hello", "English", "Persian", model()) }
        assertEquals(listOf(true), factory.attempts)
        assertTrue(factory.engines.single().conversations.single().closed)
        translator.close()
    }

    @Test fun changedModelReleasesOldEngineAndMissingOrEmptyInputNeverLoads() {
        val factory = FakeFactory()
        val translator = translator(factory)
        val missing = File(temporary.root, "missing.litertlm")
        assertEquals("", translator.translate(" ", "English", "Persian", missing))
        assertThrows(IllegalArgumentException::class.java) { translator.translate("Hello", "English", "Persian", missing) }
        assertTrue(factory.attempts.isEmpty())
        val first = model()
        translator.translate("Hello", "English", "Persian", first)
        val second = temporary.newFile("second.litertlm").apply { writeText("model data") }
        translator.translate("Hello", "English", "Persian", second)
        assertEquals(1, factory.engines.first().closes)
        assertEquals(2, factory.engines.size)
        translator.close()
    }

    @Test fun cancellationStopsActiveNativeWorkAndDoesNotRetryOnCpu() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val factory = FakeFactory(entered = entered, released = released)
        val translator = translator(factory)
        val model = model()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { translator.translate("Hello", "English", "Persian", model) }
            catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        translator.cancel()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertEquals(listOf(true), factory.attempts)
        assertTrue(factory.engines.single().conversations.single().closed)
        translator.close()
    }

    private fun model() = temporary.newFile("model.litertlm").apply { writeText("model data") }
    private fun translator(factory: FakeFactory) = GemmaTranslator(temporary.root, {}, factory)

    private class FakeFactory(
        val gpuInitializationFailure: Boolean = false,
        val gpuInferenceFailure: Boolean = false,
        val response: String = "{\"translation\":\"سلام\"}",
        val entered: CountDownLatch? = null,
        val released: CountDownLatch? = null,
    ) : GemmaEngineFactory {
        val attempts = mutableListOf<Boolean>()
        val engines = mutableListOf<FakeEngine>()
        override fun open(modelFile: File, cacheDir: File, gpu: Boolean): GemmaEngine {
            attempts.add(gpu)
            if (gpu && gpuInitializationFailure) throw IllegalStateException("GPU driver unavailable")
            assertTrue("Close the old engine before opening another", engines.all { it.closes == 1 })
            return FakeEngine(gpu, this).also { engines.add(it) }
        }
    }

    private class FakeEngine(val gpu: Boolean, val owner: FakeFactory) : GemmaEngine {
        var closes = 0
        val conversations = mutableListOf<FakeConversation>()
        override fun conversation(prompt: String): GemmaConversation = FakeConversation(this).also { conversations.add(it) }
        override fun close() { closes++ }
    }

    private class FakeConversation(val engine: FakeEngine) : GemmaConversation {
        var closed = false
        override fun send(text: String): String {
            if (engine.gpu && engine.owner.gpuInferenceFailure) throw IllegalStateException("GPU kernel failed")
            engine.owner.entered?.countDown()
            engine.owner.released?.let {
                assertTrue(it.await(2, TimeUnit.SECONDS))
                throw CancellationException("Native decoding cancelled")
            }
            return engine.owner.response
        }
        override fun cancel() { engine.owner.released?.countDown() }
        override fun close() { closed = true }
    }
}
