package app.olauncher.extras.sublist

import kotlin.math.max

/**
 * Pure rules for "Free placement", unit tested: every app in the sub list sits in one cell of a
 * grid, numbered in reading order (`row * columns + col`), and cells may stay empty.
 */
object SubListGrid {

    /** A saved cell, kept as row and column so a change in the number of columns can be noticed. */
    data class Cell(val row: Int, val col: Int)

    /** How many cells of [cellWidth] fit across [available], [gap] apart (at least one). */
    fun columns(available: Int, cellWidth: Int, gap: Int): Int =
        if (cellWidth <= 0) 1 else max(1, (available + gap) / (cellWidth + gap))

    /**
     * The cell of every app in [keys] (in that order). A saved cell is kept when it fits in
     * [columns] and no earlier app took it; the others fill the first free cells in reading order,
     * in [keys] order. With nothing saved this is the order of [keys], one after the other.
     */
    fun arrange(keys: List<String>, saved: Map<String, Cell>, columns: Int): List<Int> {
        val cols = max(1, columns)
        val result = IntArray(keys.size) { -1 }
        val taken = mutableSetOf<Int>()
        keys.forEachIndexed { i, key ->
            val cell = saved[key] ?: return@forEachIndexed
            if (cell.row < 0 || cell.col !in 0 until cols) return@forEachIndexed
            val index = cell.row * cols + cell.col
            if (taken.add(index)) result[i] = index
        }
        var next = 0
        result.indices.forEach { i ->
            if (result[i] >= 0) return@forEach
            while (next in taken) next++
            result[i] = next
            taken += next
        }
        return result.toList()
    }

    /** [cells] after the app at [from] moves to [to]; the one that was there takes its old cell. */
    fun swap(cells: List<Int>, from: Int, to: Int): List<Int> {
        if (from !in cells.indices) return cells
        val old = cells[from]
        return cells.map { if (it == to) old else it }.toMutableList().apply { this[from] = to }
    }

    /** How many rows [cells] need. */
    fun rows(cells: List<Int>, columns: Int): Int = cells.maxOrNull()?.let { it / max(1, columns) + 1 } ?: 0

    /** Indices of [cells] in reading order (for the peek, which shows the first apps). */
    fun readingOrder(cells: List<Int>): List<Int> = cells.indices.sortedBy { cells[it] }

    fun encode(cells: Map<String, Cell>): String =
        cells.entries.joinToString(";") { (key, cell) -> "$key=${cell.row},${cell.col}" }

    fun decode(value: String?): Map<String, Cell> =
        value.orEmpty().split(';').mapNotNull { part ->
            val at = part.lastIndexOf('=')
            if (at <= 0) return@mapNotNull null
            val numbers = part.substring(at + 1).split(',').mapNotNull { it.toIntOrNull() }
            if (numbers.size != 2) null else part.substring(0, at) to Cell(numbers[0], numbers[1])
        }.toMap()
}
