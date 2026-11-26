package app.olauncher.extras.search

/**
 * Where each A–Z scroller letter jumps to, worked out in one pass over the list instead of
 * searching it on every letter change. Pure, unit tested.
 */
object LetterIndex {

    /** Scroller letters in order: A–Z then '#'. */
    val LETTERS: List<Char> = ('A'..'Z').toList() + '#'

    /** [jump]: list index to jump to per letter (-1 = none); [present]: the letter has apps of its own. */
    class Table(val jump: IntArray, val present: BooleanArray)

    /**
     * For each of [LETTERS], the list index to jump to; -1 for none. [labels] are in list order;
     * a null label (folders, the Widgets entry, headers) is skipped, so "Tools ›" pinned on top
     * doesn't make T jump there. A letter without apps jumps to the next letter that has some;
     * labels starting with another letter (É, Ж…) count as after Z; '#' is the first
     * label that doesn't start with a letter.
     */
    fun build(labels: List<String?>): Table {
        val first = IntArray(26) { -1 }
        var beyondZ = -1
        var symbols = -1
        labels.forEachIndexed { i, label ->
            val c = label?.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
            when {
                c in 'A'..'Z' -> if (first[c - 'A'] < 0) first[c - 'A'] = i
                c.isLetter() -> if (beyondZ < 0) beyondZ = i
                else -> if (symbols < 0) symbols = i
            }
        }
        val table = IntArray(LETTERS.size)
        // Walk Z → A keeping the earliest index of any later letter, for letters without apps
        var later = beyondZ
        for (l in 25 downTo 0) {
            val own = first[l]
            table[l] = if (own >= 0) own else later
            if (own >= 0 && (later < 0 || own < later)) later = own
        }
        table[26] = symbols
        val present = BooleanArray(LETTERS.size) { l -> if (l < 26) first[l] >= 0 else symbols >= 0 }
        return Table(table, present)
    }
}

/** Picking an app by pulling sideways off the A–Z strip: which rows belong to a letter. Pure, unit tested. */
object LetterPick {

    /** The strip letter a label belongs to: A–Z, '#' for digits/symbols, null for none (other alphabets, empty, folders). */
    fun letterOf(label: String?): Char? {
        val c = label?.firstOrNull()?.uppercaseChar() ?: return null
        return when {
            c in 'A'..'Z' -> c
            c.isLetter() -> null
            else -> '#'
        }
    }

    /** List positions of [letter]'s apps in [labels] (null = not an app), from the top of the list down. */
    fun rows(labels: List<String?>, letter: Char): List<Int> =
        labels.indices.filter { letterOf(labels[it]) == letter }
}
