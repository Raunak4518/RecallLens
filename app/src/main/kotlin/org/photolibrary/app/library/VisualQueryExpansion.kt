package org.photolibrary.app.library

import java.text.Normalizer
import java.util.Locale

internal enum class ExpansionKind { TYPO, SYNONYM, TRANSLITERATION }

internal data class VisualQueryExpansion(val phrase: String, val kind: ExpansionKind) {
    companion object {
        private val word = Regex("""[\p{L}\p{M}]+""")
        private val safePhrase = Regex("""[\p{L}\p{M}\s]+""")
        private val fixed = setOf(
            "no", "not", "without", "except", "excluding", "nor", "neither", "or", "my", "me", "myself",
            "your", "his", "her", "our", "their", "near", "around", "before", "after",
            "wearing", "beside", "behind", "above", "below", "left", "right", "front", "between",
        )

        private val synonyms = mapOf(
            "bike" to "bicycle", "sofa" to "couch", "puppy" to "young dog", "automobile" to "car",
            "cellphone" to "mobile phone", "sneakers" to "athletic shoes", "spectacles" to "eyeglasses",
        )
        private val typos = mapOf(
            "bicyle" to "bicycle", "bycicle" to "bicycle", "reciept" to "receipt",
            "sceenshot" to "screenshot", "sunet" to "sunset", "mountian" to "mountain",
        )
        private val scriptVariants = mapOf(
            "कुत्ता" to "kutta", "बिल्ली" to "billi", "पहाड़" to "pahaad", "समुद्र" to "samudra",
            "सूर्यास्त" to "suryast", "दीवाली" to "diwali", "दिवाली" to "diwali",
            "કૂતરો" to "kutaro", "બિલાડી" to "biladi", "દરિયો" to "dariyo", "દિવાળી" to "diwali",
        )
        private val marks = Regex("""\p{M}+""")

        fun select(
            phrase: String,
            protectedWords: Set<String>,
            enabled: Set<ExpansionKind>,
        ): VisualQueryExpansion? {
            if (phrase.length !in 2..180 || !safePhrase.matches(phrase)) return null
            val spans = word.findAll(phrase).toList()
            if (spans.size !in 1..12 || spans.any { key(it.value) in fixed || key(it.value) in protectedWords }) return null
            val kinds = listOf(ExpansionKind.TYPO, ExpansionKind.SYNONYM, ExpansionKind.TRANSLITERATION)
            for (kind in kinds) {
                if (kind !in enabled) continue
                for (span in spans) {
                    val token = key(span.value)
                    val replacement = when (kind) {
                        ExpansionKind.TYPO -> typos[token]
                        ExpansionKind.SYNONYM -> synonyms[token]
                        ExpansionKind.TRANSLITERATION -> scriptVariants[token] ?: foldLatin(token)
                    } ?: continue
                    if (replacement == token || word.findAll(replacement).any { key(it.value) in protectedWords }) continue
                    return VisualQueryExpansion(phrase.replaceRange(span.range, replacement), kind)
                }
            }
            return null
        }

        private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        private fun foldLatin(value: String): String? {
            if (!value.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }) return null
            return Normalizer.normalize(value, Normalizer.Form.NFD).replace(marks, "").takeIf { it != value }
        }
    }
}
