package app.olauncher.extras.config

import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.folders.Folder
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.FolderJson
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.sublist.SubListGrid
import app.olauncher.extras.widgets.WidgetPanel
import org.json.JSONArray
import org.json.JSONObject

/** An app named in a file: only ever matched against apps installed on this phone. */
data class AppRef(val packageName: String, val activity: String? = null)

sealed interface HomeEntry {
    data class App(val app: AppRef) : HomeEntry
    data class Folder(val name: String) : HomeEntry
    data object Widgets : HomeEntry
    data object Empty : HomeEntry
}

data class SlotSwipe(val slot: Int, val left: Boolean, val packageName: String)
data class CellRef(val packageName: String, val row: Int, val col: Int)
data class FolderSpec(val name: String, val apps: List<AppRef>)

/** The home layout part. Each field is optional; a missing one leaves that part of the phone alone. */
data class Layout(
    val homeAppsCount: Int? = null,
    val homeApps: List<HomeEntry>? = null,
    val swipeLeft: AppRef? = null,
    val swipeRight: AppRef? = null,
    val slotSwipes: List<SlotSwipe>? = null,
    val sublistApps: List<AppRef>? = null,
    val sublistCells: List<CellRef>? = null,
    val folders: List<FolderSpec>? = null,
)

/** Something in a file that won't be applied, and why. */
data class Skip(val path: String, val reason: String)

/** What the phone can tell the importer. Every app reference is resolved here, never trusted. */
interface Device {
    /** The installed app in the main profile with this package and launcher activity (first one if null). */
    fun app(packageName: String, activity: String?): FolderApp?
    fun iconPackInstalled(packageName: String): Boolean
    fun granted(requirement: Requirement): Boolean
    /** UserHandle.toString() of the main profile, as upstream stores it. */
    val mainUser: String
    val widgetsLabel: String
    fun newId(): String
}

object ConfigLayout {

    const val MAX_SLOTS = 8
    private const val FOLDER_SUFFIX = " ›"

    private val appRef = object : Type<AppRef>() {
        private val pkg = S.packageName()
        private val cls = S.className()
        override fun parse(value: Any?, path: String): AppRef {
            val o = value as? JSONObject ?: throw SchemaError(path, "must be an app ({\"package\": …})")
            onlyKeys(o, path, "package", "activity")
            return AppRef(o.field("package", pkg, path) ?: throw SchemaError("$path.package", "is missing"), o.field("activity", cls, path))
        }
        override fun encode(value: AppRef): Any = JSONObject().put("package", value.packageName).apply { value.activity?.let { put("activity", it) } }
        override fun jsonSchema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("package")))
            .put("properties", JSONObject().put("package", pkg.jsonSchema()).put("activity", cls.jsonSchema()))
    }

    private val folderName = S.text(40, minLength = 1)

    private val homeEntry = object : Type<HomeEntry>() {
        override fun parse(value: Any?, path: String): HomeEntry {
            if (value == null || value == JSONObject.NULL) return HomeEntry.Empty
            val o = value as? JSONObject ?: throw SchemaError(path, "must be an app, a folder, widgets or null")
            return when {
                o.has("folder") -> { onlyKeys(o, path, "folder"); HomeEntry.Folder(o.field("folder", folderName, path)!!) }
                o.has("widgets") -> { onlyKeys(o, path, "widgets"); if (o.field("widgets", S.bool(), path) == true) HomeEntry.Widgets else HomeEntry.Empty }
                o.length() == 0 -> HomeEntry.Empty
                else -> HomeEntry.App(appRef.parse(o, path))
            }
        }
        override fun encode(value: HomeEntry): Any = when (value) {
            is HomeEntry.App -> appRef.encode(value.app)
            is HomeEntry.Folder -> JSONObject().put("folder", value.name)
            HomeEntry.Widgets -> JSONObject().put("widgets", true)
            HomeEntry.Empty -> JSONObject.NULL
        }
        override fun jsonSchema(): JSONObject = JSONObject().put("oneOf", JSONArray()
            .put(JSONObject().put("type", "null"))
            .put(appRef.jsonSchema())
            .put(JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(listOf("folder")))
                .put("properties", JSONObject().put("folder", folderName.jsonSchema())))
            .put(JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray(listOf("widgets")))
                .put("properties", JSONObject().put("widgets", JSONObject().put("const", true)))))
    }

    private val slotSwipe = object : Type<SlotSwipe>() {
        private val slot = S.int(1, MAX_SLOTS)
        private val side = S.choice("left", "right")
        private val pkg = S.packageName()
        override fun parse(value: Any?, path: String): SlotSwipe {
            val o = value as? JSONObject ?: throw SchemaError(path, "must be {\"slot\", \"side\", \"package\"}")
            onlyKeys(o, path, "slot", "side", "package")
            return SlotSwipe(
                o.field("slot", slot, path) ?: throw SchemaError("$path.slot", "is missing"),
                (o.field("side", side, path) ?: throw SchemaError("$path.side", "is missing")) == "left",
                o.field("package", pkg, path) ?: throw SchemaError("$path.package", "is missing"),
            )
        }
        override fun encode(value: SlotSwipe): Any =
            JSONObject().put("slot", value.slot).put("side", if (value.left) "left" else "right").put("package", value.packageName)
        override fun jsonSchema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("slot", "side", "package")))
            .put("properties", JSONObject().put("slot", slot.jsonSchema()).put("side", side.jsonSchema()).put("package", pkg.jsonSchema()))
    }

    private val cell = object : Type<CellRef>() {
        private val pkg = S.packageName()
        private val row = S.int(0, 50)
        private val col = S.int(0, 20)
        override fun parse(value: Any?, path: String): CellRef {
            val o = value as? JSONObject ?: throw SchemaError(path, "must be {\"package\", \"row\", \"col\"}")
            onlyKeys(o, path, "package", "row", "col")
            return CellRef(
                o.field("package", pkg, path) ?: throw SchemaError("$path.package", "is missing"),
                o.field("row", row, path) ?: throw SchemaError("$path.row", "is missing"),
                o.field("col", col, path) ?: throw SchemaError("$path.col", "is missing"),
            )
        }
        override fun encode(value: CellRef): Any = JSONObject().put("package", value.packageName).put("row", value.row).put("col", value.col)
        override fun jsonSchema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("package", "row", "col")))
            .put("properties", JSONObject().put("package", pkg.jsonSchema()).put("row", row.jsonSchema()).put("col", col.jsonSchema()))
    }

    private val apps = S.list(appRef, 100)
    private val folderApps = S.list(appRef, 100)
    private const val MAX_FOLDERS = 50

    private fun onlyKeys(o: JSONObject, path: String, vararg allowed: String) {
        o.keys().forEach { if (it !in allowed) throw SchemaError("$path.$it", "isn't allowed here") }
    }

    /** Reads the layout part. A bad item is skipped (and reported); the rest still counts. */
    fun parse(o: JSONObject, path: String, skips: MutableList<Skip>): Layout {
        fun <T> attempt(block: () -> T): T? = try { block() } catch (e: SchemaError) { skips += Skip(e.path, e.reason); null }
        fun <T> items(name: String, max: Int, item: Type<T>): List<T>? {
            if (!o.has(name) || o.isNull(name)) return null
            val array = o.opt(name) as? JSONArray ?: run { skips += Skip("$path.$name", "must be a list"); return null }
            if (array.length() > max) { skips += Skip("$path.$name", "must have at most $max items"); return null }
            return (0 until array.length()).mapNotNull { i -> attempt { item.parse(array.opt(i), "$path.$name[$i]") } }
        }
        o.keys().forEach { key ->
            if (key !in setOf("homeAppsCount", "homeApps", "swipeLeft", "swipeRight", "slotSwipes", "sublist", "folders"))
                skips += Skip("$path.$key", "isn't a known setting")
        }
        val sublist = attempt { o.optJSONObject("sublist")?.also { onlyKeys(it, "$path.sublist", "apps", "cells") } }
        val folders = items("folders", MAX_FOLDERS, folderSpec)?.let { list ->
            val seen = HashSet<String>()
            list.filter { f -> seen.add(f.name.lowercase()).also { if (!it) skips += Skip("$path.folders", "has two folders named \"${f.name}\"") } }
        }
        val homeApps = if (!o.has("homeApps") || o.isNull("homeApps")) null else {
            val array = o.opt("homeApps") as? JSONArray
            if (array == null || array.length() > MAX_SLOTS) { skips += Skip("$path.homeApps", "must be a list of at most $MAX_SLOTS"); null }
            else (0 until array.length()).map { i -> attempt { homeEntry.parse(array.opt(i), "$path.homeApps[$i]") } ?: HomeEntry.Empty }
        }
        return Layout(
            homeAppsCount = attempt { o.field("homeAppsCount", S.int(0, MAX_SLOTS), path) },
            homeApps = homeApps,
            swipeLeft = attempt { o.field("swipeLeft", appRef, path) },
            swipeRight = attempt { o.field("swipeRight", appRef, path) },
            slotSwipes = items("slotSwipes", MAX_SLOTS * 2, slotSwipe),
            sublistApps = sublist?.let { s -> attempt { s.field("apps", apps, "$path.sublist") } },
            sublistCells = sublist?.let { s ->
                if (!s.has("cells")) null else {
                    val array = s.opt("cells") as? JSONArray
                    if (array == null || array.length() > 100) { skips += Skip("$path.sublist.cells", "must be a list of at most 100"); null }
                    else (0 until array.length()).mapNotNull { i -> attempt { cell.parse(array.opt(i), "$path.sublist.cells[$i]") } }
                }
            },
            folders = folders,
        )
    }

    private val folderSpec = object : Type<FolderSpec>() {
        override fun parse(value: Any?, path: String): FolderSpec {
            val o = value as? JSONObject ?: throw SchemaError(path, "must be {\"name\", \"apps\"}")
            onlyKeys(o, path, "name", "apps")
            return FolderSpec(o.field("name", folderName, path) ?: throw SchemaError("$path.name", "is missing"), o.field("apps", folderApps, path) ?: emptyList())
        }
        override fun encode(value: FolderSpec): Any = JSONObject().put("name", value.name).put("apps", folderApps.encode(value.apps))
        override fun jsonSchema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(listOf("name")))
            .put("properties", JSONObject().put("name", folderName.jsonSchema()).put("apps", folderApps.jsonSchema()))
    }

    fun encode(layout: Layout): JSONObject = JSONObject().apply {
        layout.homeAppsCount?.let { put("homeAppsCount", it) }
        layout.homeApps?.let { put("homeApps", JSONArray(it.map(homeEntry::encode))) }
        layout.swipeLeft?.let { put("swipeLeft", appRef.encode(it)) }
        layout.swipeRight?.let { put("swipeRight", appRef.encode(it)) }
        layout.slotSwipes?.let { put("slotSwipes", JSONArray(it.map(slotSwipe::encode))) }
        if (layout.sublistApps != null || layout.sublistCells != null) put("sublist", JSONObject().apply {
            layout.sublistApps?.let { put("apps", apps.encode(it)) }
            layout.sublistCells?.let { put("cells", JSONArray(it.map(cell::encode))) }
        })
        layout.folders?.let { put("folders", JSONArray(it.map(folderSpec::encode))) }
    }

    fun jsonSchema(): JSONObject = JSONObject().put("type", "object").put("additionalProperties", false).put("properties", JSONObject()
        .put("homeAppsCount", S.int(0, MAX_SLOTS).jsonSchema())
        .put("homeApps", JSONObject().put("type", "array").put("maxItems", MAX_SLOTS).put("items", homeEntry.jsonSchema()))
        .put("swipeLeft", appRef.jsonSchema())
        .put("swipeRight", appRef.jsonSchema())
        .put("slotSwipes", JSONObject().put("type", "array").put("maxItems", MAX_SLOTS * 2).put("items", slotSwipe.jsonSchema()))
        .put("sublist", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", JSONObject()
            .put("apps", apps.jsonSchema())
            .put("cells", JSONObject().put("type", "array").put("maxItems", 100).put("items", cell.jsonSchema()))))
        .put("folders", JSONObject().put("type", "array").put("maxItems", MAX_FOLDERS).put("items", folderSpec.jsonSchema())))

    // ---- This phone's layout ----

    /** The current layout, as a file would hold it (pinned shortcuts aren't exported). */
    fun read(store: Store): Layout {
        val up = store.upstream
        val folders = FolderJson.decode(store.extras.string(ExtrasKeys.FOLDERS))
        // Folder names must be unique in a file: number repeats
        val names = HashMap<String, Int>()
        val folderNames = folders.associate { f ->
            val base = f.name.trim().take(36).ifBlank { "Folder" }
            val n = (names[base.lowercase()] ?: 0) + 1
            names[base.lowercase()] = n
            f.id to if (n == 1) base else "$base $n"
        }
        fun ref(pkg: String?, activity: String?) =
            pkg?.takeIf { Regex(S.PACKAGE_PATTERN).matches(it) }?.let { AppRef(it, activity?.takeIf { a -> Regex(S.CLASS_PATTERN).matches(a) }) }
        val home = (1..MAX_SLOTS).map { slot ->
            val pkg = up.string("APP_PACKAGE_$slot").orEmpty()
            val activity = up.string("APP_ACTIVITY_CLASS_NAME_$slot")
            when {
                up.bool("IS_SHORTCUT_$slot", false) || pkg.isBlank() -> HomeEntry.Empty
                pkg == Folders.FOLDER_PACKAGE -> folderNames[activity]?.let { HomeEntry.Folder(it) } ?: HomeEntry.Empty
                pkg == WidgetPanel.PACKAGE -> HomeEntry.Widgets
                else -> ref(pkg, activity)?.let { HomeEntry.App(it) } ?: HomeEntry.Empty
            }
        }.dropLastWhile { it == HomeEntry.Empty }
        val slotSwipes = try {
            val o = JSONObject(store.extras.string(ExtrasKeys.SLOT_SWIPES) ?: "{}")
            o.keys().asSequence().mapNotNull { key ->
                val slot = key.dropLast(1).toIntOrNull()?.takeIf { it in 1..MAX_SLOTS } ?: return@mapNotNull null
                val pkg = o.optString(key).takeIf { Regex(S.PACKAGE_PATTERN).matches(it) } ?: return@mapNotNull null
                SlotSwipe(slot, key.endsWith("L"), pkg)
            }.sortedWith(compareBy({ it.slot }, { !it.left })).toList()
        } catch (e: Exception) { emptyList() }
        val sublistApps = FolderJson.decodeApps(store.extras.string(ExtrasKeys.SUBLIST_APPS)).mapNotNull { ref(it.packageName, it.activityClassName) }
        val cells = SubListGrid.decode(store.extras.string(ExtrasKeys.SUBLIST_CELLS)).mapNotNull { (key, c) ->
            val pkg = key.substringBefore('|').takeIf { Regex(S.PACKAGE_PATTERN).matches(it) } ?: return@mapNotNull null
            CellRef(pkg, c.row.coerceIn(0, 50), c.col.coerceIn(0, 20))
        }.sortedWith(compareBy({ it.row }, { it.col }))
        return Layout(
            homeAppsCount = up.int("HOME_APPS_NUM", 4).coerceIn(0, MAX_SLOTS),
            homeApps = home,
            swipeLeft = ref(up.string("APP_PACKAGE_SWIPE_LEFT"), up.string("APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT")),
            swipeRight = ref(up.string("APP_PACKAGE_SWIPE_RIGHT"), up.string("APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT")),
            slotSwipes = slotSwipes,
            sublistApps = sublistApps,
            sublistCells = cells,
            folders = folders.map { f -> FolderSpec(folderNames.getValue(f.id), f.apps.mapNotNull { ref(it.packageName, it.activityClassName) }) },
        )
    }

    /** A layout matched against this phone: only installed apps, the user's own profile, new folder ids. */
    data class Resolved(val layout: Layout, val home: List<Any?>, val swipeLeft: FolderApp?, val swipeRight: FolderApp?,
                        val slotSwipes: List<SlotSwipe>, val sublist: List<FolderApp>?, val cells: Map<String, SubListGrid.Cell>?,
                        val folders: List<Folder>?)

    fun resolve(layout: Layout, device: Device, skips: MutableList<Skip>): Resolved {
        fun find(ref: AppRef, path: String): FolderApp? = device.app(ref.packageName, ref.activity).also {
            if (it == null) skips += Skip(path, "${ref.packageName} isn't installed here (or isn't an app you can open)")
        }
        val folders = layout.folders?.mapIndexed { i, f ->
            Folder(device.newId(), f.name, f.apps.mapIndexedNotNull { j, a -> find(a, "layout.folders[$i].apps[$j]") }.distinctBy { it.packageName })
        }
        val home = layout.homeApps?.mapIndexed { i, entry ->
            when (entry) {
                is HomeEntry.App -> find(entry.app, "layout.homeApps[$i]")
                is HomeEntry.Folder -> folders?.firstOrNull { it.name.equals(entry.name, ignoreCase = true) }
                    ?: null.also { skips += Skip("layout.homeApps[$i]", "folder \"${entry.name}\" isn't in the file") }
                HomeEntry.Widgets -> HomeEntry.Widgets
                HomeEntry.Empty -> null
            }
        }.orEmpty()
        val slotSwipes = layout.slotSwipes.orEmpty().filterIndexed { i, s ->
            (device.app(s.packageName, null) != null).also { if (!it) skips += Skip("layout.slotSwipes[$i]", "${s.packageName} isn't installed here") }
        }
        val sublist = layout.sublistApps?.mapIndexedNotNull { i, a -> find(a, "layout.sublist.apps[$i]") }?.distinctBy { it.packageName }
        val cells = layout.sublistCells?.mapNotNull { c ->
            val app = sublist?.firstOrNull { it.packageName == c.packageName } ?: device.app(c.packageName, null) ?: return@mapNotNull null
            "${app.packageName}|${app.userSerial}" to SubListGrid.Cell(c.row, c.col)
        }?.toMap()
        return Resolved(layout, home, layout.swipeLeft?.let { find(it, "layout.swipeLeft") },
            layout.swipeRight?.let { find(it, "layout.swipeRight") }, slotSwipes, sublist, cells, folders)
    }

    /** Writes a resolved layout. Only the fields the file had are touched. */
    fun apply(r: Resolved, edits: Edits, device: Device) {
        val up = edits.upstream
        r.folders?.let { edits.extras.putString(ExtrasKeys.FOLDERS, FolderJson.encode(it)) }
        r.layout.homeAppsCount?.let { up.putInt("HOME_APPS_NUM", it) }
        if (r.layout.homeApps != null) {
            for (slot in 1..MAX_SLOTS) {
                val (name, pkg, activity) = when (val e = r.home.getOrNull(slot - 1)) {
                    is FolderApp -> Triple(e.label, e.packageName, e.activityClassName.orEmpty())
                    is Folder -> Triple(e.name + FOLDER_SUFFIX, Folders.FOLDER_PACKAGE, e.id)
                    HomeEntry.Widgets -> Triple(device.widgetsLabel, WidgetPanel.PACKAGE, WidgetPanel.PACKAGE)
                    else -> Triple("", "", "")
                }
                up.putString("APP_NAME_$slot", name)
                up.putString("APP_PACKAGE_$slot", pkg)
                up.putString("APP_ACTIVITY_CLASS_NAME_$slot", activity)
                up.putString("APP_USER_$slot", if (pkg.isEmpty()) "" else device.mainUser)
                up.putBool("IS_SHORTCUT_$slot", false)
                up.putString("SHORTCUT_ID_$slot", "")
            }
        }
        fun swipe(side: String, app: FolderApp?) {
            app ?: return
            up.putString("APP_NAME_SWIPE_$side", app.label)
            up.putString("APP_PACKAGE_SWIPE_$side", app.packageName)
            up.putString("APP_ACTIVITY_CLASS_NAME_SWIPE_$side", app.activityClassName.orEmpty())
            up.putString("APP_USER_SWIPE_$side", device.mainUser)
            up.putBool("IS_SHORTCUT_SWIPE_$side", false)
            up.putString("SHORTCUT_ID_SWIPE_$side", "")
        }
        swipe("LEFT", r.swipeLeft)
        swipe("RIGHT", r.swipeRight)
        if (r.layout.slotSwipes != null) {
            val json = JSONObject()
            r.slotSwipes.forEach { json.put("${it.slot}${if (it.left) "L" else "R"}", it.packageName) }
            edits.extras.putString(ExtrasKeys.SLOT_SWIPES, json.toString())
        }
        r.sublist?.let { edits.extras.putString(ExtrasKeys.SUBLIST_APPS, FolderJson.encodeApps(it)) }
        r.cells?.let { edits.extras.putString(ExtrasKeys.SUBLIST_CELLS, SubListGrid.encode(it).ifEmpty { null }) }
    }

    /** Short lines for the import preview. */
    fun summary(r: Resolved): List<String> = buildList {
        if (r.layout.homeApps != null) add("Home apps: " + r.home.mapNotNull {
            when (it) { is FolderApp -> it.label; is Folder -> it.name + FOLDER_SUFFIX; HomeEntry.Widgets -> "Widgets"; else -> null }
        }.ifEmpty { listOf("none") }.joinToString(", "))
        r.layout.homeAppsCount?.let { add("Apps on home screen: $it") }
        r.swipeLeft?.let { add("Swipe left: ${it.label}") }
        r.swipeRight?.let { add("Swipe right: ${it.label}") }
        if (r.layout.slotSwipes != null) add("Per-app swipes: ${r.slotSwipes.size}")
        r.sublist?.let { list -> add("Sub list: " + list.joinToString(", ") { it.label }.ifEmpty { "empty" }) }
        r.folders?.let { list -> add("Folders (replace yours): " + list.joinToString(", ") { it.name }.ifEmpty { "none" }) }
    }
}
