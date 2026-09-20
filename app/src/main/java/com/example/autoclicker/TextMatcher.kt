package com.example.autoclicker

import kotlin.math.max

/**
 * Finds the OCR block that best matches a target string.
 * Uses a two-stage strategy:
 *  1. Case-insensitive "contains" match (fast path, exact substring).
 *  2. Fuzzy match via normalized Levenshtein distance (handles minor
 *     OCR misreads, e.g. "Subrnit" vs "Submit").
 */
object TextMatcher {

    private const val FUZZY_THRESHOLD = 0.82

    fun findMatch(blocks: List<OcrTextBlock>, target: String): OcrTextBlock? {
        if (target.isBlank()) return null

        blocks.firstOrNull { it.text.contains(target, ignoreCase = true) }?.let { return it }

        var best: OcrTextBlock? = null
        var bestScore = 0.0
        for (block in blocks) {
            val score = similarity(block.text, target)
            if (score > bestScore) {
                bestScore = score
                best = block
            }
        }
        return if (bestScore >= FUZZY_THRESHOLD) best else null
    }

    private fun similarity(a: String, b: String): Double {
        val lowerA = a.lowercase()
        val lowerB = b.lowercase()
        val distance = levenshtein(lowerA, lowerB)
        val longest = max(lowerA.length, lowerB.length)
        if (longest == 0) return 1.0
        return 1.0 - distance.toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
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
 * Holds an ordered list of target texts and tracks which one is
 * currently "active". Supports the multiple-text sequence feature:
 * once the current target is clicked, the sequence advances to the
 * next target. Set [loop] = true to restart from index 0 at the end.
 */
class TextSequence(
    rawInput: String,
    private val loop: Boolean = true
) {
    val targets: List<String> = rawInput
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    var currentIndex: Int = 0
        private set

    val currentTarget: String?
        get() = targets.getOrNull(currentIndex)

    val isFinished: Boolean
        get() = targets.isEmpty() || (!loop && currentIndex >= targets.size)

    fun advance() {
        if (targets.isEmpty()) return
        currentIndex += 1
        if (loop && currentIndex >= targets.size) {
            currentIndex = 0
        }
    }

    fun reset() {
        currentIndex = 0
    }
}
