package com.sayit.translator

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class PairLanguageDecision(
    val language: AppLanguage,
    val scoreA: Float,
    val scoreB: Float,
    val method: String,
    val evidence: String,
)

internal class PairLanguageClassifier {
    private val identifier: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(MIN_LANGUAGE_CONFIDENCE)
            .build(),
    )

    suspend fun classify(
        text: String,
        languageA: AppLanguage,
        languageB: AppLanguage,
    ): PairLanguageDecision {
        require(languageA != languageB) { "Pair classification requires two different languages." }
        strongPairLanguageDecision(text, languageA, languageB)?.let { return it }

        val identified = runCatching {
            identifier.identifyPossibleLanguages(text).awaitResult()
        }.getOrDefault(emptyList())
        var scoreA = lexicalScore(text, languageA)
        var scoreB = lexicalScore(text, languageB)
        identified.forEach { result ->
            val code = normalizeLanguageCode(result.languageTag)
            scoreA += mappedPairScore(code, result.confidence, languageA, languageA, languageB)
            scoreB += mappedPairScore(code, result.confidence, languageB, languageA, languageB)
        }

        val selected = when {
            scoreA > scoreB -> languageA
            scoreB > scoreA -> languageB
            else -> tieBreakByAlphabet(text, languageA, languageB)
                ?: stablePairTieBreak(languageA, languageB)
        }
        return PairLanguageDecision(
            language = selected,
            scoreA = scoreA,
            scoreB = scoreB,
            method = when {
                identified.isNotEmpty() -> "ml_kit_pair"
                scoreA > 0f || scoreB > 0f -> "lexical_pair"
                else -> "stable_pair_tie"
            },
            evidence = if (identified.isEmpty()) {
                "ml_kit=none"
            } else {
                "ml_kit=" + identified.joinToString(",") { result ->
                    "${normalizeLanguageCode(result.languageTag)}:" +
                        String.format(Locale.US, "%.4f", result.confidence)
                }
            },
        )
    }

    fun close() = identifier.close()
}

internal fun normalizeLanguageCode(languageCode: String): String = languageCode
    .trim()
    .lowercase()
    .replace('_', '-')
    .substringBefore('-')

internal fun strongPairLanguageDecision(
    text: String,
    languageA: AppLanguage,
    languageB: AppLanguage,
): PairLanguageDecision? {
    val letters = text.count(Char::isLetter)
    if (letters == 0) return null
    val cyrillic = text.count { it in '\u0400'..'\u04ff' }
    val cyrillicShare = cyrillic.toFloat() / letters
    val pair = setOf(languageA, languageB)

    if (pair == RUSSIAN_CROATIAN_PAIR && cyrillicShare >= 0.5f) {
        russianCroatianCyrillicDecision(text, languageA, languageB)?.let { return it }
    }

    if (pair == setOf(AppLanguage.RUSSIAN, AppLanguage.SERBIAN) && cyrillicShare >= 0.5f) {
        val normalized = text.lowercase()
        val serbianUnique = normalized.count { it in "јљњђћџ" }
        val russianUnique = normalized.count { it in "ёыэъьщюяй" }
        if (serbianUnique != russianUnique) {
            val selected = if (serbianUnique > russianUnique) {
                AppLanguage.SERBIAN
            } else {
                AppLanguage.RUSSIAN
            }
            return scriptDecision(
                selected,
                languageA,
                languageB,
                evidence = "distinctive_cyrillic_characters",
            )
        }
    }

    val cyrillicCandidate = listOf(languageA, languageB).singleOrNull { language ->
        language == AppLanguage.RUSSIAN || language == AppLanguage.SERBIAN
    }
    val ambiguousRussianCroatianCyrillic =
        pair == RUSSIAN_CROATIAN_PAIR && cyrillicShare > 0f
    if (cyrillicCandidate != null && !ambiguousRussianCroatianCyrillic) {
        if (cyrillicShare >= 0.7f) {
            return scriptDecision(
                cyrillicCandidate,
                languageA,
                languageB,
                evidence = "dominant_cyrillic_for_only_cyrillic_candidate",
            )
        }
        if (cyrillicShare == 0f) {
            val other = if (languageA == cyrillicCandidate) languageB else languageA
            if (cyrillicCandidate == AppLanguage.RUSSIAN) {
                return scriptDecision(
                    other,
                    languageA,
                    languageB,
                    evidence = "latin_text_excludes_russian",
                )
            }
        }
    }

    val normalized = text.lowercase()
    val romanianMarks = normalized.count { it in "ăâîșşțţ" }
    val spanishMarks = normalized.count { it in "ñ¿¡" }
    if (romanianMarks > 0 && AppLanguage.ROMANIAN in pair) {
        return scriptDecision(
            AppLanguage.ROMANIAN,
            languageA,
            languageB,
            evidence = "romanian_diacritics",
        )
    }
    if (spanishMarks > 0 && AppLanguage.SPANISH in pair) {
        return scriptDecision(
            AppLanguage.SPANISH,
            languageA,
            languageB,
            evidence = "spanish_distinctive_characters",
        )
    }
    return null
}

private fun russianCroatianCyrillicDecision(
    text: String,
    languageA: AppLanguage,
    languageB: AppLanguage,
): PairLanguageDecision? {
    val normalized = text.lowercase()
    val latinProbe = transliterateCyrillicForCroatian(normalized)
    val croatianTokens = lexicalTokens(latinProbe)
    val russianTokens = lexicalTokens(normalized)
    val southSlavicUnique = normalized.count { it in SOUTH_SLAVIC_UNIQUE_CYRILLIC }
    val russianUnique = normalized.count { it in RUSSIAN_UNIQUE_CYRILLIC }
    val croatianHints = croatianTokens.count { it in CROATIAN_CYRILLIC_HINTS }
    val russianHints = russianTokens.count { it in RUSSIAN_CYRILLIC_HINTS }
    val croatianLexical = lexicalMatchCount(latinProbe, AppLanguage.CROATIAN)
    val russianLexical = lexicalMatchCount(normalized, AppLanguage.RUSSIAN)

    val selected = when {
        southSlavicUnique > russianUnique -> AppLanguage.CROATIAN
        croatianHints > russianHints -> AppLanguage.CROATIAN
        russianUnique > southSlavicUnique && croatianHints == 0 -> AppLanguage.RUSSIAN
        russianHints > croatianHints -> AppLanguage.RUSSIAN
        croatianLexical >= 2 && croatianLexical > russianLexical -> AppLanguage.CROATIAN
        russianLexical >= 2 && russianLexical > croatianLexical -> AppLanguage.RUSSIAN
        else -> return null
    }
    val croatianScore = southSlavicUnique + croatianHints + croatianLexical
    val russianScore = russianUnique + russianHints + russianLexical
    return PairLanguageDecision(
        language = selected,
        scoreA = if (languageA == AppLanguage.CROATIAN) {
            croatianScore.toFloat()
        } else {
            russianScore.toFloat()
        },
        scoreB = if (languageB == AppLanguage.CROATIAN) {
            croatianScore.toFloat()
        } else {
            russianScore.toFloat()
        },
        method = "cyrillic_russian_croatian_pair",
        evidence = "south_slavic_unique=$southSlavicUnique," +
            "russian_unique=$russianUnique,croatian_hints=$croatianHints," +
            "russian_hints=$russianHints,croatian_lexical=$croatianLexical," +
            "russian_lexical=$russianLexical",
    )
}

private fun scriptDecision(
    selected: AppLanguage,
    languageA: AppLanguage,
    languageB: AppLanguage,
    evidence: String,
) = PairLanguageDecision(
    language = selected,
    scoreA = if (selected == languageA) 1f else 0f,
    scoreB = if (selected == languageB) 1f else 0f,
    method = "script_pair",
    evidence = evidence,
)

internal fun pairLanguageDiagnosticFeatures(text: String): Map<String, String> {
    val letters = text.count(Char::isLetter)
    val latinLetters = text.count { it.isLetter() && it in '\u0041'..'\u024f' }
    val cyrillicLetters = text.count { it.isLetter() && it in '\u0400'..'\u04ff' }
    val normalized = text.lowercase()
    val croatianLatinProbe = transliterateCyrillicForCroatian(normalized)
    return mapOf(
        "text_characters" to text.length.toString(),
        "letter_characters" to letters.toString(),
        "latin_letters" to latinLetters.toString(),
        "cyrillic_letters" to cyrillicLetters.toString(),
        "cyrillic_share" to if (letters == 0) {
            "0.000"
        } else {
            String.format(Locale.US, "%.3f", cyrillicLetters.toFloat() / letters)
        },
        "south_slavic_unique_cyrillic" to
            normalized.count { it in SOUTH_SLAVIC_UNIQUE_CYRILLIC }.toString(),
        "russian_unique_cyrillic" to
            normalized.count { it in RUSSIAN_UNIQUE_CYRILLIC }.toString(),
        "croatian_cyrillic_hints" to lexicalTokens(croatianLatinProbe)
            .count { it in CROATIAN_CYRILLIC_HINTS }
            .toString(),
        "russian_cyrillic_hints" to lexicalTokens(normalized)
            .count { it in RUSSIAN_CYRILLIC_HINTS }
            .toString(),
        "croatian_latin_probe" to croatianLatinProbe,
        "romanian_distinctive" to normalized.count { it in "ăâîșşțţ" }.toString(),
        "spanish_distinctive" to normalized.count { it in "ñ¿¡" }.toString(),
    )
}

private fun mappedPairScore(
    detectedCode: String,
    confidence: Float,
    candidate: AppLanguage,
    languageA: AppLanguage,
    languageB: AppLanguage,
): Float {
    if (detectedCode == candidate.code) return confidence
    val aliases = LANGUAGE_FAMILY_ALIASES[candidate].orEmpty()
    if (detectedCode !in aliases) return 0f
    val pair = setOf(languageA, languageB)
    val matchingCandidates = pair.count { detectedCode in LANGUAGE_FAMILY_ALIASES[it].orEmpty() }
    return when (matchingCandidates) {
        1 -> confidence * FAMILY_MAPPING_WEIGHT
        else -> 0f
    }
}

private fun lexicalScore(text: String, language: AppLanguage): Float {
    val normalized = if (
        language == AppLanguage.CROATIAN && text.any { it in '\u0400'..'\u04ff' }
    ) {
        transliterateCyrillicForCroatian(text)
    } else {
        text.lowercase()
    }
    val tokens = lexicalTokens(normalized)
    if (tokens.isEmpty()) return 0f
    val matches = tokens.count { it in COMMON_WORDS[language].orEmpty() }
    return matches.toFloat() / tokens.size
}

private fun lexicalMatchCount(text: String, language: AppLanguage): Int =
    lexicalTokens(text).count { it in COMMON_WORDS[language].orEmpty() }

private fun lexicalTokens(text: String): List<String> =
    Regex("[\\p{L}]+").findAll(text.lowercase()).map { it.value }.toList()

internal fun transliterateCyrillicForCroatian(text: String): String = buildString {
    text.lowercase().forEach { character ->
        append(CYRILLIC_TO_CROATIAN_LATIN[character] ?: character.toString())
    }
}

private fun tieBreakByAlphabet(
    text: String,
    languageA: AppLanguage,
    languageB: AppLanguage,
): AppLanguage? {
    val pair = setOf(languageA, languageB)
    val hasCyrillic = text.any { it in '\u0400'..'\u04ff' }
    if (!hasCyrillic) return null
    if (pair == RUSSIAN_CROATIAN_PAIR) return null
    return pair.singleOrNull {
        it == AppLanguage.RUSSIAN || it == AppLanguage.SERBIAN
    }
}

internal fun stablePairTieBreak(
    languageA: AppLanguage,
    languageB: AppLanguage,
): AppLanguage = minOf(languageA, languageB, compareBy(AppLanguage::code))

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { throwable ->
        if (continuation.isActive) continuation.resumeWithException(throwable)
    }
    addOnCanceledListener { continuation.cancel() }
}

private const val MIN_LANGUAGE_CONFIDENCE = 0.01f
private const val FAMILY_MAPPING_WEIGHT = 0.95f

private val RUSSIAN_CROATIAN_PAIR = setOf(AppLanguage.RUSSIAN, AppLanguage.CROATIAN)
private const val SOUTH_SLAVIC_UNIQUE_CYRILLIC = "јљњђћџї"
private const val RUSSIAN_UNIQUE_CYRILLIC = "ёыэъьщюяй"

private val CROATIAN_CYRILLIC_HINTS = setOf(
    "može", "mogu", "možete", "jedan", "jedna", "jedno", "edna", "jdna",
    "velik", "velika", "veliko", "mali", "mala", "molim", "hvala", "trebam",
    "treba", "želim", "hoću", "gdje", "gde", "što", "šta", "koliko", "imate",
    "dobar", "dobra", "pljeskavica", "pleskavica", "ćevapi", "račun", "kava",
)

private val RUSSIAN_CYRILLIC_HINTS = setOf(
    "можно", "может", "один", "одна", "одно", "большой", "большая", "большое",
    "маленький", "маленькая", "спасибо", "пожалуйста", "нужно", "хочу", "желательно",
    "где", "что", "сколько", "есть", "дайте", "добрый", "хорошо", "счёт", "кофе",
)

private val CYRILLIC_TO_CROATIAN_LATIN = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ",
    'е' to "e", 'ё' to "jo", 'ж' to "ž", 'з' to "z", 'и' to "i", 'і' to "i",
    'ї' to "j", 'й' to "j", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj",
    'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r",
    'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u", 'ф' to "f", 'х' to "h",
    'ц' to "c", 'ч' to "č", 'џ' to "dž", 'ш' to "š", 'щ' to "šč", 'ы' to "y",
    'э' to "e", 'ю' to "ju", 'я' to "ja", 'ь' to "", 'ъ' to "",
)

private val LANGUAGE_FAMILY_ALIASES = mapOf(
    AppLanguage.SERBIAN to setOf("sr", "hr", "bs", "sl", "mk"),
    AppLanguage.CROATIAN to setOf("hr", "sr", "bs", "sl"),
    AppLanguage.ENGLISH to setOf("en"),
    AppLanguage.ROMANIAN to setOf("ro", "mo"),
    AppLanguage.RUSSIAN to setOf("ru", "uk", "be", "bg"),
    AppLanguage.SPANISH to setOf("es", "ca", "gl"),
)

private val COMMON_WORDS = mapOf(
    AppLanguage.SERBIAN to setOf(
        "ја", "је", "сам", "си", "смо", "није", "да", "шта", "где", "како",
        "hvala", "molim", "treba", "želim", "šta", "gde", "kako", "dobro",
    ),
    AppLanguage.CROATIAN to setOf(
        "ja", "je", "sam", "nije", "da", "što", "gdje", "kako", "hvala", "molim",
        "trebam", "treba", "želim", "dobro", "može", "mogu", "možete", "jedan",
        "jedna", "jedno", "edna", "jdna", "velik", "velika", "veliko", "mali",
        "mala", "hoću", "gde", "šta", "koliko", "imate", "dobar", "dobra", "dan",
        "pljeskavica", "pleskavica", "ćevapi", "račun", "kava", "voda", "pivo",
    ),
    AppLanguage.ENGLISH to setOf(
        "the", "a", "an", "is", "are", "i", "you", "we", "where", "what", "how",
        "please", "thank", "need", "want", "hello",
    ),
    AppLanguage.ROMANIAN to setOf(
        "un", "o", "este", "sunt", "eu", "tu", "noi", "unde", "ce", "cum", "vă",
        "mulțumesc", "vreau", "am", "bună",
    ),
    AppLanguage.RUSSIAN to setOf(
        "я", "ты", "мы", "это", "есть", "не", "да", "что", "где", "как", "спасибо",
        "пожалуйста", "нужно", "хочу", "здравствуйте", "можно", "может", "один",
        "одна", "одно", "большой", "большая", "большое", "маленький", "маленькая",
        "желательно", "сколько", "дайте", "добрый", "хорошо", "счёт", "кофе",
    ),
    AppLanguage.SPANISH to setOf(
        "el", "la", "un", "una", "es", "soy", "yo", "tú", "nosotros", "dónde", "qué",
        "cómo", "gracias", "por", "favor", "necesito", "quiero", "hola",
    ),
)
