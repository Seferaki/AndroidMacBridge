package dev.seferaki.bridge

import android.content.Context
import java.util.Locale

/** Frequency-ordered word completions, searched locally without sending typed text. */
internal class KeyboardWordDictionary(private val context: Context) {
    private val russian by lazy { read("ru") }
    private val english by lazy { read("en") }

    private fun read(language: String): List<String> =
        context.assets.open("words/$language.txt").bufferedReader(Charsets.UTF_8).useLines { it.toList() }

    fun complete(prefix: String, learned: Map<String, Int> = emptyMap()): List<String> {
        val lower = prefix.lowercase(Locale.ROOT)
        val dictionary = if (lower.any { it in 'а'..'я' || it == 'ё' }) russian else english
        return rankWordCompletions(prefix, dictionary, learned)
    }
}

internal fun rankWordCompletions(prefix: String, dictionary: List<String>, learned: Map<String, Int>): List<String> {
        val lower = prefix.lowercase(Locale.ROOT)
        val personal = learned.entries.asSequence()
            .filter { it.key.length > lower.length && it.key.startsWith(lower) && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        return (personal + dictionary.asSequence().filter { it.length > lower.length && it.startsWith(lower) }).distinct().take(3).map { word ->
            when {
                prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.ROOT)
                prefix.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> word
            }
        }.toList()
}
