package com.example.autoclicker

import kotlin.math.max

/**
 * Finds the OCR block that best matches the current target.
 *
 * Matching order:
 * 1. Normalized case-insensitive contains match.
 * 2. Normalized fuzzy match using Levenshtein similarity.
 *
 * This class ONLY finds a matching OCR block.
 * Sequence/order control is handled by TextSequence.
 */
object TextMatcher {

    /**
     * Minimum fuzzy similarity required for a match.
     */
    private const val FUZZY_THRESHOLD = 0.82

    /**
     * Fuzzy matching is disabled for very short targets.
     *
     * Short strings such as:
     * "OK"
     * "1"
     * "2"
     *
     * can easily produce false positives.
     */
    private const val MIN_FUZZY_LENGTH = 3

    /**
     * Finds the OCR block matching the target.
     *
     * Returns:
     * - matching OcrTextBlock when found
     * - null when the target is not found
     *
     * IMPORTANT:
     * This function does NOT change sequence state.
     */
    fun findMatch(
        blocks: List<OcrTextBlock>,
        target: String
    ): OcrTextBlock? {

        if (blocks.isEmpty() || target.isBlank()) {
            return null
        }

        val normalizedTarget = normalize(target)

        if (normalizedTarget.isEmpty()) {
            return null
        }

        /*
         * ---------------------------------------------------------
         * FAST PATH
         * ---------------------------------------------------------
         *
         * First look for a normalized contains match.
         *
         * Example:
         *
         * Target:
         * "Continue"
         *
         * OCR:
         * "Please click Continue button"
         *
         * => MATCH
         */
        blocks.firstOrNull { block ->

            val text = normalize(block.text)

            text.contains(
                normalizedTarget,
                ignoreCase = true
            )

        }?.let { block ->
            return block
        }

        /*
         * ---------------------------------------------------------
         * FUZZY MATCH
         * ---------------------------------------------------------
         *
         * Used for small OCR mistakes.
         *
         * Example:
         *
         * Target:
         * "Continue"
         *
         * OCR:
         * "Contlnue"
         *
         * => possible fuzzy MATCH
         */
        if (normalizedTarget.length < MIN_FUZZY_LENGTH) {
            return null
        }

        var bestMatch: OcrTextBlock? = null
        var bestScore = 0.0

        for (block in blocks) {

            val normalizedText = normalize(block.text)

            if (normalizedText.isEmpty()) {
                continue
            }

            val score = similarity(
                normalizedText,
                normalizedTarget
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

    /**
     * Normalizes OCR text before comparison.
     *
     * Handles:
     * - upper/lower case
     * - repeated spaces
     * - line breaks
     * - leading/trailing spaces
     */
    private fun normalize(
        value: String
    ): String {

        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    /**
     * Calculates normalized Levenshtein similarity.
     *
     * Result:
     *
     * 1.0 = identical
     * 0.0 = completely different
     */
    private fun similarity(
        a: String,
        b: String
    ): Double {

        if (a == b) {
            return 1.0
        }

        if (a.isEmpty() || b.isEmpty()) {
            return 0.0
        }

        val distance = levenshtein(
            a,
            b
        )

        val longest = max(
            a.length,
            b.length
        )

        return 1.0 -
            distance.toDouble() / longest
    }

    /**
     * Calculates Levenshtein edit distance.
     */
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
