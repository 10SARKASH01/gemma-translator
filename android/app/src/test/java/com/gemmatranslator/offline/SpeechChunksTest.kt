package com.gemmatranslator.offline

import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun emptyOrWhitespaceTextDoesNotCreateSpeechRequests() {
        for (text in listOf("", " ", "\n\t\r ")) assertTrue(SpeechChunks.split(text).isEmpty())
    }

    @Test fun PersianUrduAndCjkTextKeepsEveryNonWhitespaceCharacterWithinLimits() {
        val texts = listOf(
            "سلام دنیا. این یک ترجمه فارسی است و باید با صدای درست خوانده شود. ".repeat(20),
            "آپ کیسے ہیں؟ یہ اردو ترجمہ ہے اور آواز کے ساتھ چلنا چاہیے۔ ".repeat(20),
            "こんにちは世界。これはオフライン翻訳の音声です。".repeat(30),
            "你好世界。这段中文应该完整地读出来，不能丢掉任何字符。".repeat(30),
            "안녕하세요. 이 번역은 전화기에서 오프라인으로 읽힙니다. ".repeat(20),
            "مرحبا بالعالم؟ يجب الاحتفاظ بجميع الأحرف العربية. ".repeat(20),
            "Hello world! Preserve addresses, names, and punctuation. ".repeat(20),
            "Bonjour ! Gardez les noms et la ponctuation. ".repeat(20),
        )
        for (text in texts) assertPreservedAndBounded(text)
    }

    @Test fun whitespaceBoundariesKeepWholeWordsAndRemoveOnlyBoundaryWhitespace() {
        val text = "   " + "one\t two   three four five six seven eight nine ten ".repeat(20) + "\n  "
        val chunks = SpeechChunks.split(text)
        val words = text.trim().split(Regex("\\s+"))
        assertEquals(words, chunks.flatMap { it.split(Regex("\\s+")) })
        assertTrue(chunks.all { it == it.trim() })
    }

    @Test fun oversizedUnspacedTextIsPreservedWithoutAnUnboundedChunk() {
        val text = "x".repeat(1001)
        val chunks = SpeechChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(90, chunks.first().length)
        assertTrue(chunks.drop(1).all { it.length <= 180 })
    }

    @Test fun sentenceEndingsStartSpeechBeforeTheRemainingParagraph() {
        for (punctuation in listOf(".", "!", "?", "؟", "۔", "。", "！", "？")) {
            val sentence = "a".repeat(40) + punctuation
            val text = sentence + " " + "b".repeat(240)
            val chunks = SpeechChunks.split(text)
            assertEquals(sentence, chunks.first())
            assertPreservedAndBounded(text)
        }
    }

    @Test fun emojiAtEitherChunkBoundaryNeverCreatesHalfASurrogatePair() {
        for (prefix in listOf(29, 30, 88, 89, 90, 269, 270, 449, 450)) {
            for (emoji in listOf("😀", "🧑🏽‍💻", "🇨🇦", "𝄞")) {
                val text = "a".repeat(prefix) + emoji + "文".repeat(250) + emoji
                val chunks = SpeechChunks.split(text)
                assertEquals(text, chunks.joinToString(""))
                for (chunk in chunks) assertWellFormedUtf16(chunk)
                assertPreservedAndBounded(text)
            }
        }
    }

    private fun assertPreservedAndBounded(text: String) {
        val chunks = SpeechChunks.split(text)
        assertFalse(chunks.isEmpty())
        assertEquals(text.filterNot { it.isWhitespace() }, chunks.joinToString("").filterNot { it.isWhitespace() })
        assertTrue(chunks.first().length <= 90)
        assertTrue(chunks.drop(1).all { it.length <= 180 })
        assertTrue(chunks.all { it.isNotBlank() })
    }

    private fun assertWellFormedUtf16(text: String) {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.isHighSurrogate()) {
                assertTrue("High surrogate must keep its low surrogate", index + 1 < text.length && text[index + 1].isLowSurrogate())
                index += 2
            } else {
                assertFalse("A chunk must not start with a lone low surrogate", character.isLowSurrogate())
                index++
            }
        }
    }
}
