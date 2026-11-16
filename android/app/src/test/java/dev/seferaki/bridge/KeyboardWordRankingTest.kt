package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardWordRankingTest {
    @Test fun frequentPersonalWordsComeFirstWithoutDuplicates() {
        val result = rankWordCompletions("при", listOf("привет", "прийти", "пример"), mapOf("пример" to 4, "привет" to 2))
        assertEquals(listOf("пример", "привет", "прийти"), result)
    }

    @Test fun preservesUppercaseAndIgnoresUnrelatedWords() {
        val result = rankWordCompletions("ПРИ", listOf("привет", "пока"), mapOf("приятель" to 2, "друг" to 9))
        assertEquals(listOf("ПРИЯТЕЛЬ", "ПРИВЕТ"), result)
    }
}
