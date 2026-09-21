package com.example.autoclicker

import kotlin.math.max

/**
 * Finds the OCR block that best matches the current target.
 *
 * Matching order:
 * 1. Case-insensitive contains match.
 * 2. Fuzzy match using normalized Levenshtein similarity.
 *
 * This class only finds a match.
 * Sequence/order control is handled by TextSequence.
 */
object TextMatcher {

    private const val FUZZY_THRESHOLD = 0.82

    fun findMatch(
        blocks: List<OcrTextBlock>,
        target: String
    ): OcrTextBlock? {

        if (target.isBlank()) {
            return null
        }

        // Fast path: exact substring match, ignoring case.
        blocks.firstOrNull {
            it.text.contains(
                target,
                ignoreCase = true
            )
        }?.let {
            return it
        }

        // Fuzzy matching for small OCR mistakes.
        var bestMatch: OcrTextBlock? = null
        var bestScore = 0.0

        for (block in blocks) {

            val score =
                similarity(
                    block.text,
                    target
                )

            if (score > bestScore) {
                bestScore = score
                bestMatch = block
            }
        }

        return if (bestScore >= FUZZY_THRESHOLD) {
            bestMatch
        } else {
            null
        }
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {

        val lowerA = a.lowercase()
        val lowerB = b.lowercase()

        val distance =
            levenshtein(
                lowerA,
                lowerB
            )

        val longest =
            max(
                lowerA.length,
                lowerB.length
            )

        if (longest == 0) {
            return 1.0
        }

        return 1.0 -
            distance.toDouble() / longest
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        val dp =
            Array(a.length + 1) {
                IntArray(b.length + 1)
            }

        for (i in 0..a.length) {
            dp[i][0] = i
        }

        for (j in 0..b.length) {
            dp[0][j] = j
        }

        for (i in 1..a.length) {

            for (j in 1..b.length) {

                val cost =
                    if (a[i - 1] == b[j - 1]) {
                        0
                    } else {
                        1
                    }

                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
            }
        }

        return dp[a.length][b.length]
    }
}

/**
 * Maintains the ordered target sequence.
 *
 * Example:
 *
 * Target 1 -> Target 2 -> Target 3 -> Target 4
 *
 * advance() is called ONLY after the current target
 * has been successfully clicked.
 *
 * The sequence never loops back to Target 1.
 */
class TextSequence(
    rawInput: String
) {

    val targets: List<String> =
        rawInput
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    var currentIndex: Int = 0
        private set

    /**
     * Target currently being searched.
     *
     * If the current target is not found,
     * currentIndex does not change, so the same target
     * will be searched again.
     */
    val currentTarget: String?
        get() = targets.getOrNull(currentIndex)

    /**
     * True when there are no targets or the final target
     * has already been advanced past.
     */
    val isFinished: Boolean
        get() =
            targets.isEmpty() ||
                currentIndex >= targets.size

    /**
     * Moves to the next target.
     *
     * IMPORTANT:
     * This must be called only after a successful click.
     *
     * Example:
     *
     * Target 1 found -> click -> advance()
     * Target 2 found -> click -> advance()
     * Target 3 found -> click -> advance()
     *
     * After the final target, the sequence is finished.
     * It does NOT return to Target 1.
     */
    fun advance() {

        if (isFinished) {
            return
        }

        currentIndex += 1
    }

    /**
     * Resets the sequence to Target 1.
     *
     * This is only used when a completely new run needs
     * to start from the beginning.
     */
    fun reset() {
        currentIndex = 0
    }
}

अब इसका behavior बिल्कुल ऐसा है

Target 1 नहीं मिला:

"1 → 1 → 1 → 1..."

Target 1 मिला:

"1 → CLICK → 2"

Target 2 नहीं मिला:

"2 → 2 → 2 → 2..."

Target 2 मिला:

"2 → CLICK → 3"

और इसी तरह:

"3 → CLICK → 4 → CLICK → 5 → ... → आखिरी Target → FINISHED"

और आखिरी Target के बाद वापस Target 1 नहीं आएगा।

एक महत्वपूर्ण सुधार यह भी है कि मैंने "TextSequence" से "loop" parameter हटा दिया है, इसलिए अब गलती से कोई दूसरा code "loop=true" देकर इसे फिर से शुरू नहीं कर सकता। "AutoClickService.kt" में हमने पहले ही "TextSequence(targetText)" वाला उपयोग रखा है, इसलिए दोनों files आपस में match करती हैं।
