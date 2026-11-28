package app.olauncher.extras

import app.olauncher.extras.config.ConfigFile
import app.olauncher.extras.config.ConfigImport
import app.olauncher.extras.config.ConfigLayout
import app.olauncher.extras.config.ConfigMeta
import app.olauncher.extras.config.ConfigSchema
import app.olauncher.extras.config.Device
import app.olauncher.extras.config.Edits
import app.olauncher.extras.config.JsonGuard
import app.olauncher.extras.config.KeyEdits
import app.olauncher.extras.config.KeyValues
import app.olauncher.extras.config.Part
import app.olauncher.extras.config.Requirement
import app.olauncher.extras.config.S
import app.olauncher.extras.config.SchemaError
import app.olauncher.extras.config.Store
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.FolderJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ConfigTest {

    /** A preferences file in memory. */
    private class MapPrefs : KeyValues, KeyEdits {
        val map = HashMap<String, Any?>()
        override fun bool(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun int(key: String, default: Int) = map[key] as? Int ?: default
        override fun float(key: String, default: Float) = map[key] as? Float ?: default
        override fun string(key: String) = map[key] as? String
        override fun putBool(key: String, value: Boolean) { map[key] = value }
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private class Phone(
        val apps: List<FolderApp> = listOf(
            app("com.android.camera2", "com.android.camera.CameraLauncher", "Camera"),
            app("com.google.android.deskclock", "com.android.deskclock.DeskClock", "Clock"),
            app("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", "Maps"),
        ),
        val granted: Set<Requirement> = emptySet(),
        val packs: Set<String> = emptySet(),
    ) : Device {
        val up = MapPrefs()
        val ex = MapPrefs()
        val store = Store(up, ex)
        val edits = Edits(up, ex)
        private var ids = 0
        override fun app(packageName: String, activity: String?) =
            apps.firstOrNull { it.packageName == packageName && (activity == null || it.activityClassName == activity) }
        override fun iconPackInstalled(packageName: String) = packageName in packs
        override fun granted(requirement: Requirement) = requirement in granted
        override val mainUser = "UserHandle{0}"
        override val widgetsLabel = "Widgets ›"
        override fun newId() = "id${++ids}"

        fun import(text: String, parts: Set<Part> = Part.entries.toSet()) =
            ConfigImport.plan(ConfigFile.parse(text), parts, store, this).also { ConfigImport.apply(it, edits, this) }

        companion object {
            fun app(pkg: String, activity: String, label: String) = FolderApp(label, pkg, activity, 0)
        }
    }

    private fun file(body: String) = """{"format":"inilauncher-config","version":1,$body}"""

    private fun fails(text: String, path: String) {
        try {
            ConfigFile.parse(text)
            fail("expected $path to be rejected")
        } catch (e: SchemaError) {
            assertEquals(path, e.path)
        }
    }

    // ---- Schema types ----

    @Test
    fun typesCheckRangesAndKinds() {
        assertEquals(3, S.int(0, 8).parse(3, "x"))
        listOf(9, -1, 2.5, "3", true, null).forEach { v -> assertRejects { S.int(0, 8).parse(v, "x") } }
        assertEquals(1.2, S.decimal(0.5, 2.0).parse(1.2, "x"), 0.0)
        listOf(0.4, 2.1, Double.NaN, "1").forEach { v -> assertRejects { S.decimal(0.5, 2.0).parse(v, "x") } }
        assertEquals(true, S.bool().parse(true, "x"))
        listOf("true", 1, null).forEach { v -> assertRejects { S.bool().parse(v, "x") } }
        assertEquals("dark", S.choice("dark", "light").parse("dark", "x"))
        listOf("Dark", "blue", 1).forEach { v -> assertRejects { S.choice("dark", "light").parse(v, "x") } }
    }

    @Test
    fun textRejectsControlAndDirectionCharactersAndLongText() {
        assertEquals("Night minimal", S.text(40).parse("  Night minimal ", "x"))
        listOf("a\u0000b", "line\nbreak", "evil\u202Etxt", "zero\u200Bwidth", "x".repeat(41)).forEach { v ->
            assertRejects { S.text(40).parse(v, "x") }
        }
    }

    @Test
    fun packageAndClassNamesMustBeIdentifiers() {
        listOf("com.android.camera2", "org.example_app.x1").forEach { assertEquals(it, S.packageName().parse(it, "x")) }
        listOf("camera", "com..x", "com.x/.Main", "com.x;rm -rf", "1com.x", "com.x ", "https://evil.example").forEach { v ->
            if (v.trim() != v) return@forEach // trimmed text is fine
            assertRejects { S.packageName().parse(v, "x") }
        }
        assertEquals("com.android.camera.CameraLauncher", S.className().parse("com.android.camera.CameraLauncher", "x"))
        assertRejects { S.className().parse("../../etc/passwd", "x") }
    }

    private fun assertRejects(block: () -> Unit) {
        try { block(); fail("expected a SchemaError") } catch (e: SchemaError) { assertTrue(e.reason.isNotBlank()) }
    }

    // ---- Whole files ----

    @Test
    fun rejectsFilesThatArentConfigurations() {
        fails("not json", "file")
        fails("""{"format":"something-else","version":1}""", "format")
        fails("""{"format":"inilauncher-config"}""", "version")
        fails("""{"format":"inilauncher-config","version":99}""", "version")
        fails("""{"format":"inilauncher-config","version":"1"}""", "version")
    }

    @Test
    fun guardsSizeDepthAndCount() {
        fails(file("\"meta\":{\"description\":\"${"x".repeat(JsonGuard.MAX_BYTES)}\"}"), "file")
        fails(file("\"look\":" + "[".repeat(20) + "]".repeat(20)), "file")
        fails(file("\"look\":[" + (1..JsonGuard.MAX_VALUES).joinToString(",") + "]"), "file")
        // Brackets inside strings don't count as nesting
        ConfigFile.parse(file("\"meta\":{\"name\":\"[[[[[[[[[[\"}"))
    }

    @Test
    fun unknownAndInvalidValuesAreSkippedNotApplied() {
        val phone = Phone()
        val plan = phone.import(file("""
            "look": {"theme": "light", "textSize": 9, "hiddenAppsLock": false, "font": "comic"},
            "settings": {"theme": "dark", "search.calculator": true, "APP_THEME": 2},
            "evil": {"run": "rm -rf /"}
        """))
        val skipped = plan.skips.map { it.path }.toSet()
        assertTrue(skipped.containsAll(listOf("look.textSize", "look.hiddenAppsLock", "look.font", "settings.theme", "settings.APP_THEME", "evil")))
        assertEquals(1, phone.up.map["APP_THEME"]) // light
        assertEquals(true, phone.ex.map[ExtrasKeys.SEARCH_CALCULATOR])
        assertFalse(phone.up.map.containsKey("TEXT_SIZE_SCALE"))
        assertFalse(phone.ex.map.containsKey(ExtrasKeys.LOCK_HIDDEN_APPS))
        assertFalse(phone.ex.map.containsKey(ExtrasKeys.FONT))
    }

    @Test
    fun securitySettingsCantBeSetByAnyKey() {
        val phone = Phone()
        phone.ex.map[ExtrasKeys.LOCK_HIDDEN_APPS] = true
        val keys = ConfigSchema.NEVER_EXPORTED.keys + listOf("lockHiddenApps", "hiddenApps", "HIDDEN_APPS", "lock")
        val body = keys.joinToString(",") { "\"$it\": false" }
        phone.import(file("\"look\":{$body},\"settings\":{$body}"))
        assertEquals(true, phone.ex.map[ExtrasKeys.LOCK_HIDDEN_APPS])
        assertTrue(ConfigSchema.settings.none { s -> s.internalKeys.any { it in ConfigSchema.NEVER_EXPORTED } })
    }

    @Test
    fun permissionSettingsNeedThePermissionOnThisPhone() {
        val text = file(""""settings": {"search.contacts": true, "suggested.enabled": true, "search.calculator": true}""")
        val without = Phone()
        val plan = without.import(text)
        assertEquals(setOf("settings.search.contacts", "settings.suggested.enabled"), plan.skips.map { it.path }.toSet())
        assertFalse(without.ex.map.containsKey(ExtrasKeys.SEARCH_CONTACTS))
        val with = Phone(granted = setOf(Requirement.CONTACTS, Requirement.USAGE_ACCESS))
        with.import(text)
        assertEquals(true, with.ex.map[ExtrasKeys.SEARCH_CONTACTS])
        // Turning one off never needs anything
        without.ex.map[ExtrasKeys.SEARCH_CONTACTS] = true
        without.import(file(""""settings": {"search.contacts": false}"""))
        assertEquals(false, without.ex.map[ExtrasKeys.SEARCH_CONTACTS])
    }

    @Test
    fun onlineSettingsAreMarkedInThePreview() {
        val plan = Phone().import(file(""""settings": {"search.web": true}"""))
        assertTrue(plan.changes.single { it.key == "search.web" }.online)
    }

    @Test
    fun iconPacksMustBeInstalled() {
        val text = file(""""look": {"appIcons": "pack:com.example.icons"}""")
        assertEquals("look.appIcons", Phone().import(text).skips.single().path)
        val phone = Phone(packs = setOf("com.example.icons"))
        phone.import(text)
        assertEquals("com.example.icons", phone.ex.map[ExtrasKeys.ICONS])
    }

    @Test
    fun onlyChosenPartsAreApplied() {
        val phone = Phone()
        phone.import(file(""""look": {"theme": "light"}, "settings": {"az.enabled": true}"""), setOf(Part.SETTINGS))
        assertFalse(phone.up.map.containsKey("APP_THEME"))
        assertEquals(true, phone.ex.map[ExtrasKeys.AZ_SCROLLER])
    }

    @Test
    fun metaIsPlainLimitedText() {
        val parsed = ConfigFile.parse(file(""""meta": {"name": "Night", "description": "Dark and calm", "url": "https://evil.example"}"""))
        assertEquals(ConfigMeta("Night", "Dark and calm"), parsed.meta)
        assertEquals("meta.url", parsed.skips.single().path)
        val bad = ConfigFile.parse(file(""""meta": {"name": "${"n".repeat(41)}"}"""))
        assertEquals("", bad.meta.name)
    }

    // ---- Layout ----

    private val layoutFile = file("""
        "layout": {
          "homeAppsCount": 4,
          "homeApps": [{"package": "com.android.camera2"}, {"folder": "Travel"}, {"widgets": true}, null,
                       {"package": "com.android.settings", "activity": "com.android.settings.SomeHiddenActivity"}],
          "swipeLeft": {"package": "com.google.android.deskclock"},
          "slotSwipes": [{"slot": 1, "side": "left", "package": "com.google.android.apps.maps"},
                         {"slot": 2, "side": "right", "package": "com.not.installed"}],
          "sublist": {"apps": [{"package": "com.google.android.apps.maps"}], "cells": [{"package": "com.google.android.apps.maps", "row": 1, "col": 0}]},
          "folders": [{"name": "Travel", "apps": [{"package": "com.google.android.apps.maps"}, {"package": "com.not.installed"}]}]
        }
    """)

    @Test
    fun layoutOnlyUsesInstalledLauncherApps() {
        val phone = Phone()
        val plan = phone.import(layoutFile)
        val up = phone.up.map
        assertEquals("com.android.camera2", up["APP_PACKAGE_1"])
        assertEquals("Camera", up["APP_NAME_1"]) // the phone's label, not the file's
        assertEquals("UserHandle{0}", up["APP_USER_1"])
        assertEquals("inilauncher.folder", up["APP_PACKAGE_2"])
        assertEquals("id1", up["APP_ACTIVITY_CLASS_NAME_2"]) // a new folder id
        assertEquals("inilauncher.widgets", up["APP_PACKAGE_3"])
        assertEquals("", up["APP_PACKAGE_4"])
        // A hidden activity of another app is never stored
        assertEquals("", up["APP_PACKAGE_5"])
        assertTrue(plan.skips.any { it.path == "layout.homeApps[4]" })
        assertEquals("com.google.android.deskclock", up["APP_PACKAGE_SWIPE_LEFT"])
        assertEquals("""{"1L":"com.google.android.apps.maps"}""", phone.ex.map[ExtrasKeys.SLOT_SWIPES])
        val folders = FolderJson.decode(phone.ex.string(ExtrasKeys.FOLDERS))
        assertEquals(listOf("com.google.android.apps.maps"), folders.single().apps.map { it.packageName })
        assertEquals("com.google.android.apps.maps|0=1,0", phone.ex.map[ExtrasKeys.SUBLIST_CELLS])
        assertTrue(plan.layoutSummary.first().startsWith("Home apps: Camera, Travel ›, Widgets"))
    }

    @Test
    fun badLayoutItemsAreSkippedOneByOne() {
        val plan = Phone().import(file(""""layout": {"homeApps": [{"package": "bad name"}, {"package": "com.android.camera2", "extra": 1}, {"package": "com.android.camera2"}], "folders": "x"}"""))
        val paths = plan.skips.map { it.path }
        assertTrue(paths.containsAll(listOf("layout.homeApps[0].package", "layout.homeApps[1].extra", "layout.folders")))
    }

    // ---- Export ----

    @Test
    fun exportRoundTripsAndKeepsPrivateThingsOut() {
        val phone = Phone()
        phone.import(layoutFile)
        phone.import(file(""""look": {"theme": "light", "textSize": 1.3, "font": "serif", "appIcons": "letter", "sublist.style": "names"},
            "settings": {"az.side": "left", "notifications.line": false}"""))
        phone.ex.map[ExtrasKeys.NOTE_TEXT] = "my secret note"
        phone.ex.map[ExtrasKeys.WEATHER_PLACE_NAME] = "Home town"
        phone.ex.map[ExtrasKeys.LOCK_HIDDEN_APPS] = true
        val text = ConfigFile.export(phone.store, Part.entries.toSet(), ConfigMeta("Mine", ""), "0.1.6")
        listOf("my secret note", "Home town", ExtrasKeys.LOCK_HIDDEN_APPS, "hidden").forEach { assertFalse("export holds $it", text.contains(it, ignoreCase = true)) }

        val other = Phone()
        val plan = other.import(text)
        assertTrue(plan.skips.joinToString { it.path }, plan.skips.isEmpty())
        assertEquals(text, ConfigFile.export(other.store, Part.entries.toSet(), ConfigMeta("Mine", ""), "0.1.6"))
        assertEquals(1.3f, other.up.map["TEXT_SIZE_SCALE"])
        assertEquals("SERIF", other.ex.map[ExtrasKeys.FONT])
    }

    @Test
    fun lookOnlyExportHasNoApps() {
        val phone = Phone()
        phone.import(layoutFile)
        val text = ConfigFile.export(phone.store, setOf(Part.LOOK), ConfigMeta(), "0.1.6")
        assertFalse(text.contains("com."))
        assertFalse(JSONObject(text).has("layout"))
        assertFalse(JSONObject(text).has("settings"))
    }

    @Test
    fun exportIsCanonical() {
        val a = ConfigFile.export(Phone().store, Part.entries.toSet(), ConfigMeta(), "x")
        val b = ConfigFile.export(Phone().store, Part.entries.toSet(), ConfigMeta(), "x")
        assertEquals(a, b)
        val keys = JSONObject(a).getJSONObject("look").keys().asSequence().toList()
        assertEquals(a.indexOf("\"alignment\"") < a.indexOf("\"theme\""), true)
        assertTrue(keys.isNotEmpty())
    }

    // ---- Coverage and shipped files ----

    @Test
    fun everyExtrasKeyIsCoveredOrLeftOutOnPurpose() {
        val all = ExtrasKeys::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        val covered = ConfigSchema.settings.flatMap { it.internalKeys } + ConfigSchema.LAYOUT_KEYS + ConfigSchema.NEVER_EXPORTED.keys
        val missing = all.filter { it !in covered }
        assertTrue("Add these to ConfigSchema (a setting, LAYOUT_KEYS or NEVER_EXPORTED): $missing", missing.isEmpty())
    }

    @Test
    fun fontsMatchTheApp() {
        assertEquals(app.olauncher.extras.look.FontChoice.entries.map { it.name.lowercase() }, ConfigSchema.FONTS)
    }

    private fun repoFile(path: String) = listOf(File("../$path"), File(path)).first { it.parentFile?.exists() == true }

    @Test
    fun shippedDemoConfigIsValid() {
        val parsed = ConfigFile.parse(repoFile("configs/emulator-demo.json").readText())
        assertTrue(parsed.skips.joinToString(), parsed.skips.isEmpty())
        assertNotNull(parsed.layout)
    }

    /** docs/config.schema.json is generated from the code; regenerate with UPDATE_SCHEMA=1. */
    @Test
    fun publishedJsonSchemaIsUpToDate() {
        val generated = ConfigFile.canonical(ConfigSchema.jsonSchema()) + "\n"
        val file = repoFile("docs/config.schema.json")
        if (System.getenv("UPDATE_SCHEMA") == "1") file.writeText(generated)
        assertEquals("docs/config.schema.json is out of date: run the tests with UPDATE_SCHEMA=1", generated, file.readText())
    }

    @Test
    fun layoutSchemaListsAllFields() {
        val props = ConfigLayout.jsonSchema().getJSONObject("properties").keys().asSequence().toSet()
        assertEquals(setOf("homeAppsCount", "homeApps", "swipeLeft", "swipeRight", "slotSwipes", "sublist", "folders"), props)
    }
}
