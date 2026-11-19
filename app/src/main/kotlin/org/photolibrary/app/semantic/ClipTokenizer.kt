package org.photolibrary.app.semantic

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONObject

internal class ClipTokenizer(spec: JSONObject) {
    private val whitespace = Pattern.compile("\\p{White_Space}+")
    private val pieces = Pattern.compile(
        spec.getJSONObject("pre_tokenizer").getJSONArray("pretokenizers").getJSONObject(0)
            .getJSONObject("pattern").getString("Regex").replace("\\s", "\\p{White_Space}"),
    )
    private val byteLevel = Pattern.compile(
        "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
            .replace("\\s", "\\p{White_Space}").replace("\\S", "\\P{White_Space}"),
    )
    private val model = spec.getJSONObject("model")
    private val suffix = model.getString("end_of_word_suffix")
    private val vocabulary = model.getJSONObject("vocab").let { vocab -> vocab.keys().asSequence().associateWith(vocab::getInt) }
    private val merges = model.getJSONArray("merges").let { ranks -> (0 until ranks.length()).associateBy(ranks::getString) }
    private val start = vocabulary.getValue("<|startoftext|>").toLong()
    private val stop = vocabulary.getValue("<|endoftext|>").toLong()
    private val byteSymbols = ByteSymbols()
    private val cache = object : LinkedHashMap<String, List<Long>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Long>>?) = size > 512
    }

    fun encode(query: String): LongArray {
        val normalized = whitespace.matcher(Normalizer.normalize(query.take(512), Normalizer.Form.NFC))
            .replaceAll(" ").lowercase(Locale.ROOT)
        val tokens = mutableListOf(start)
        val matches = pieces.matcher(normalized)
        while (matches.find() && tokens.size < CONTEXT - 1) {
            val word = matches.group()
            when (word) {
                "<|startoftext|>" -> tokens += start
                "<|endoftext|>" -> tokens += stop
                else -> {
                    val fragments = byteLevel.matcher(word)
                    while (fragments.find() && tokens.size < CONTEXT - 1) {
                        tokens += cache.getOrPut(fragments.group()) { encodeWord(fragments.group()) }
                            .take(CONTEXT - 1 - tokens.size)
                    }
                }
            }
        }
        tokens += stop
        return tokens.toLongArray()
    }

    private fun encodeWord(word: String): List<Long> {
        var symbols = word.encodeToByteArray().map { byteSymbols[it.toInt() and 255] }
        symbols = symbols.dropLast(1) + (symbols.last() + suffix)
        while (symbols.size > 1) {
            val best = symbols.zipWithNext().minByOrNull { (left, right) -> merges["$left $right"] ?: Int.MAX_VALUE }
                ?: break
            if (best.first + " " + best.second !in merges) break
            val merged = ArrayList<String>(symbols.size)
            var index = 0
            while (index < symbols.size) {
                if (index + 1 < symbols.size && symbols[index] == best.first && symbols[index + 1] == best.second) {
                    merged += best.first + best.second
                    index += 2
                } else {
                    merged += symbols[index++]
                }
            }
            symbols = merged
        }
        return symbols.map { vocabulary.getValue(it).toLong() }
    }

    private class ByteSymbols {
        private val symbols = Array(256) { "" }

        init {
            var extra = 256
            for (byte in symbols.indices) {
                val codePoint = if (byte in 33..126 || byte in 161..172 || byte in 174..255) byte else extra++
                symbols[byte] = codePoint.toChar().toString()
            }
        }

        operator fun get(byte: Int) = symbols[byte]
    }

    private companion object {
        const val CONTEXT = 77
    }
}
