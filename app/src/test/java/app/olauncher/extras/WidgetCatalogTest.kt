package app.olauncher.extras

import app.olauncher.extras.search.LetterIndex
import app.olauncher.extras.widgets.WidgetCatalog
import app.olauncher.extras.widgets.WidgetCatalog.Mode
import app.olauncher.extras.widgets.WidgetCatalog.Row
import app.olauncher.extras.widgets.WidgetStore
import app.olauncher.helper.appLabelMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCatalogTest {

    private fun e(pkg: String, app: String, label: String) = WidgetCatalog.Entry(label, pkg, app, label)

    private val entries = listOf(
        e("com.airvisual", "AirVisual", "\"(big) IQAir \""),
        e("com.adblock", "Adblock Browser", "Adblock Browser bookmarks"),
        e("com.airvisual", "AirVisual", "\" (small) IQAir\""),
        e("com.cafe", "Café Menu", "Today"),
        e("com.adblock", "Adblock Browser", "Quick search"),
    )

    private val match: (String, String) -> Boolean = { label, q -> appLabelMatches(label, q) }

    @Test
    fun groupsByAppSortedIgnoringLeadingPunctuation() {
        val groups = WidgetCatalog.group(entries)
        assertEquals(listOf("Adblock Browser", "AirVisual", "Café Menu"), groups.map { it.appLabel })
        assertEquals(listOf("\"(big) IQAir \"", "\" (small) IQAir\""), groups[1].widgets.map { it.label })
        assertEquals("big) iqair \"", WidgetCatalog.sortKey("\"(big) IQAir \""))
    }

    @Test
    fun filterByAppKeepsAllWidgetsByWidgetKeepsMatches() {
        val groups = WidgetCatalog.group(entries)
        val byApp = WidgetCatalog.filter(groups, "adblock", match)
        assertEquals(1, byApp.size)
        assertEquals(2, byApp[0].widgets.size)
        val byWidget = WidgetCatalog.filter(groups, "small", match)
        assertEquals(listOf("\" (small) IQAir\""), byWidget.single().widgets.map { it.label })
        assertEquals("Café Menu", WidgetCatalog.filter(groups, "cafe", match).single().appLabel)
        assertTrue(WidgetCatalog.filter(groups, "zzz", match).isEmpty())
        assertEquals(groups, WidgetCatalog.filter(groups, "  ", match))
    }

    private fun shape(rows: List<Row<*>>) = rows.map {
        when (it) {
            is Row.Header<*> -> "H"
            is Row.Widgets<*> -> "R"
            is Row.Cards<*> -> "G${it.widgets.size}"
            is Row.Section -> "S"
        }
    }

    @Test
    fun rowsFollowModesWithSingleWidgetAppsAndSearchesStartingAsRow() {
        val groups = WidgetCatalog.group(entries)
        // Café has one widget: a row; the others closed
        assertEquals(listOf("H", "H", "H", "R"), shape(WidgetCatalog.rows(groups, emptyMap(), searching = false)))
        assertEquals(listOf("H", "R", "H", "H", "R"),
            shape(WidgetCatalog.rows(groups, mapOf("com.adblock" to Mode.ROW), searching = false)))
        assertEquals(listOf("H", "R", "H", "R", "H", "R"), shape(WidgetCatalog.rows(groups, emptyMap(), searching = true)))
    }

    @Test
    fun gridIsCutIntoLinesOfColumns() {
        val many = WidgetCatalog.group((1..30).map { e("com.hourdrain", "Hourdrain", "Widget $it") })
        val rows = WidgetCatalog.rows(many, mapOf("com.hourdrain" to Mode.GRID), searching = false, columns = 2)
        assertEquals(listOf("H") + List(15) { "G2" }, shape(rows))
        val three = WidgetCatalog.rows(many, mapOf("com.hourdrain" to Mode.GRID), searching = false, columns = 4)
        assertEquals(listOf("H") + List(7) { "G4" } + "G2", shape(three))
        // Letters still jump to the header, not into the grid
        assertEquals(0, LetterIndex.build(WidgetCatalog.letterLabels(rows)).jump[LetterIndex.LETTERS.indexOf('H')])
    }

    @Test
    fun titleTapStepsClosedRowGrid() {
        val groups = WidgetCatalog.group(entries)
        fun header(mode: Mode, pkg: String = "com.adblock") = Row.Header(groups.first { it.packageName == pkg }, mode, false)
        assertEquals(Mode.ROW, WidgetCatalog.nextMode(header(Mode.CLOSED), searching = false))
        assertEquals(Mode.GRID, WidgetCatalog.nextMode(header(Mode.ROW), searching = false))
        assertEquals(Mode.CLOSED, WidgetCatalog.nextMode(header(Mode.GRID), searching = false))
        // While searching (or with one widget) it can't close: grid goes back to the row
        assertEquals(Mode.ROW, WidgetCatalog.nextMode(header(Mode.GRID), searching = true))
        assertEquals(Mode.ROW, WidgetCatalog.nextMode(header(Mode.GRID, "com.cafe"), searching = false))
    }

    @Test
    fun recentSectionOnlyWhenNotSearchingAndLettersSkipIt() {
        val groups = WidgetCatalog.group(entries)
        val rows = WidgetCatalog.rows(groups, emptyMap(), false, listOf("com.cafe", "gone.app"), "Recent", "All apps")
        assertEquals(Row.Section("Recent"), rows[0])
        assertTrue((rows[1] as Row.Header<*>).recent)
        assertEquals(Row.Section("All apps"), rows[3])
        // The recent copy opens on its own key
        assertEquals("recent:com.cafe", WidgetCatalog.expandKey(rows[1] as Row.Header<*>))
        val table = LetterIndex.build(WidgetCatalog.letterLabels(rows))
        assertEquals(4, table.jump[LetterIndex.LETTERS.indexOf('A')])
        assertEquals(rows.indexOfLast { it is Row.Header<*> && it.group.packageName == "com.cafe" }, table.jump[LetterIndex.LETTERS.indexOf('C')])
        assertTrue(WidgetCatalog.rows(groups, emptyMap(), true, listOf("com.cafe")).none { it is Row.Section })
    }

    @Test
    fun recentAppsMoveToFrontAndAreCapped() {
        assertEquals(listOf("b", "a"), WidgetStore.pushRecent(listOf("a", "b"), "b"))
        assertEquals(WidgetStore.RECENT_APPS, WidgetStore.pushRecent(listOf("a", "b", "c", "d", "e"), "f").size)
        assertEquals("f", WidgetStore.pushRecent(listOf("a", "b", "c", "d", "e"), "f").first())
    }
}
