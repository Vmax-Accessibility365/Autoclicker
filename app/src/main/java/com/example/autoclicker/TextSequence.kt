package com.example.autoclicker

/**
 * Maintains the ordered target sequence.
 *
 * Example:
 *
 * Target 1 -> Target 2 -> Target 3 -> Target 4
 *
 * The sequence advances ONLY when the caller confirms
 * that the current target was successfully clicked.
 *
 * IMPORTANT:
 *
 * TextSequence does NOT perform clicks.
 * It only controls which target should currently be searched.
 */
class TextSequence(
    rawInput: String
) {

    /**
     * Converts the input into an ordered target list.
     *
     * Example:
     *
     * "Login, Continue, Confirm, Submit"
     *
     * becomes:
     *
     * 0 -> Login
     * 1 -> Continue
     * 2 -> Confirm
     * 3 -> Submit
     */
    val targets: List<String> =
        rawInput
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Index of the target currently being searched.
     *
     * Starts from Target 1.
     */
    var currentIndex: Int = 0
        private set

    /**
     * Returns the target currently being searched.
     *
     * Example:
     *
     * currentIndex = 0 -> Target 1
     * currentIndex = 1 -> Target 2
     * currentIndex = 2 -> Target 3
     *
     * After the final target:
     * currentTarget = null
     */
    val currentTarget: String?
        get() = targets.getOrNull(currentIndex)

    /**
     * Returns true when the sequence has completed.
     *
     * Empty input:
     * FINISHED
     *
     * After the last target has been clicked:
     * FINISHED
     */
    val isFinished: Boolean
        get() =
            targets.isEmpty() ||
                currentIndex >= targets.size

    /**
     * Advances exactly ONE target.
     *
     * IMPORTANT:
     *
     * Call this ONLY after a successful click.
     *
     * Correct:
     *
     * Target 1 found
     *     ↓
     * CLICK
     *     ↓
     * advance()
     *     ↓
     * Target 2
     *
     * If Target 1 is NOT found:
     *
     * Target 1
     *     ↓
     * no advance()
     *     ↓
     * Target 1
     *     ↓
     * no advance()
     *     ↓
     * Target 1
     */
    fun advance() {

        if (isFinished) {
            return
        }

        currentIndex += 1
    }

    /**
     * Returns the sequence to Target 1.
     *
     * Use this ONLY when starting a completely new run.
     *
     * It is NOT called automatically after a failed search.
     */
    fun reset() {
        currentIndex = 0
    }
}
