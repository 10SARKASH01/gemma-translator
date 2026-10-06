package com.gemmatranslator.offline

import org.junit.Assert.*
import org.junit.Test

class SupportedLanguagesTest {
    @Test fun originalLanguageOrderAndPersianUrduFrenchArePreserved() {
        assertEquals(listOf("ar", "en", "es", "ja", "zh", "ko", "fa", "ur", "fr"), SupportedLanguages.all.map { it.code })
        assertEquals(9, SupportedLanguages.all.map { it.code }.toSet().size)
        assertEquals("Persian", SupportedLanguages.all.first { it.code == "fa" }.name)
        assertEquals("Urdu", SupportedLanguages.all.first { it.code == "ur" }.name)
        assertEquals("French", SupportedLanguages.all.first { it.code == "fr" }.name)
        assertTrue(SupportedLanguages.all.all { it.name.isNotBlank() })
    }

    @Test fun rightToLeftAppliesOnlyToArabicPersianAndUrdu() {
        for (language in SupportedLanguages.all) assertEquals(language.code in setOf("ar", "fa", "ur"), language.rtl)
    }

    @Test fun everyLanguagePairRotatesBothWaysWithoutSelectingTheOtherLane() {
        val indices = SupportedLanguages.all.indices
        for (current in indices) for (other in indices) {
            if (current == other) continue
            for (direction in listOf(-1, 1)) {
                val rotated = SupportedLanguages.rotate(current, other, direction)
                assertTrue(rotated in indices)
                assertNotEquals(other, rotated)
                assertEquals(current, SupportedLanguages.rotate(rotated, other, -direction))
            }
        }
    }

    @Test fun aCompleteRotationVisitsEveryAvailableLanguageExactlyOnce() {
        val indices = SupportedLanguages.all.indices
        for (start in indices) for (other in indices) {
            if (start == other) continue
            for (direction in listOf(-1, 1)) {
                val visited = mutableListOf<Int>()
                var current = start
                repeat(SupportedLanguages.all.size - 1) {
                    visited.add(current)
                    current = SupportedLanguages.rotate(current, other, direction)
                }
                assertEquals(start, current)
                assertEquals(indices.filter { it != other }.toSet(), visited.toSet())
                assertEquals(visited.size, visited.toSet().size)
            }
        }
    }

    @Test fun rotationRejectsDirectionsThatCannotDescribeASingleStep() {
        for (direction in listOf(-2, 0, 2)) {
            assertThrows(IllegalArgumentException::class.java) { SupportedLanguages.rotate(0, 1, direction) }
        }
    }
}
