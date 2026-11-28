package app.olauncher.extras.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A file that passed the checks: only declared settings with valid values, plus what was skipped. */
data class ParsedConfig(
    val version: Int,
    val meta: ConfigMeta,
    val values: Map<Part, Map<String, Any>>,
    val layout: Layout?,
    val skips: List<Skip>,
) {
    val parts: Set<Part> get() = values.keys + (if (layout != null) setOf(Part.LAYOUT) else emptySet())
}

/** Reads and writes configuration files. Pure: no Android, so every rule is unit tested. */
object ConfigFile {

    private val TOP_KEYS = setOf("format", "version", "app", "meta", Part.LOOK.key, Part.SETTINGS.key, Part.LAYOUT.key)

    /**
     * Checks a whole file. Throws [SchemaError] when it can't be used at all (not JSON, not a
     * configuration, too big, newer format); otherwise bad values are listed in [ParsedConfig.skips].
     */
    fun parse(text: String): ParsedConfig {
        JsonGuard.check(text)
        val root = try { JSONObject(text) } catch (e: JSONException) { throw SchemaError("file", "isn't valid JSON") }
        if (root.opt("format") != ConfigSchema.FORMAT) throw SchemaError("format", "isn't an Inilauncher configuration")
        if (!root.has("version")) throw SchemaError("version", "is missing")
        val version = S.int(1, 1000).parse(root.opt("version"), "version")
        if (version > ConfigSchema.VERSION) throw SchemaError("version", "is from a newer Inilauncher; update the app first")
        val migrated = ConfigMigrations.migrate(root, version)

        val skips = mutableListOf<Skip>()
        migrated.keys().forEach { if (it !in TOP_KEYS) skips += Skip(it, "isn't a known part") }
        val meta = (migrated.opt("meta") as? JSONObject)?.let { m ->
            m.keys().forEach { if (it != "name" && it != "description") skips += Skip("meta.$it", "isn't allowed here") }
            ConfigMeta(
                name = attempt(skips) { m.field("name", ConfigMeta.NAME, "meta") }.orEmpty(),
                description = attempt(skips) { m.field("description", ConfigMeta.DESCRIPTION, "meta") }.orEmpty(),
            )
        } ?: ConfigMeta()

        val values = LinkedHashMap<Part, Map<String, Any>>()
        for (part in listOf(Part.LOOK, Part.SETTINGS)) {
            if (!migrated.has(part.key)) continue
            val o = migrated.opt(part.key) as? JSONObject ?: run { skips += Skip(part.key, "must be an object"); null } ?: continue
            val parsed = LinkedHashMap<String, Any>()
            o.keys().asSequence().sorted().forEach { key ->
                val setting = ConfigSchema.byKey[key]
                when {
                    setting == null -> skips += Skip("${part.key}.$key", "isn't a known setting")
                    setting.part != part -> skips += Skip("${part.key}.$key", "belongs in \"${setting.part.key}\"")
                    else -> attempt(skips) { setting.type.parse(o.opt(key), "${part.key}.$key") }?.let { parsed[key] = it }
                }
            }
            values[part] = parsed
        }
        val layout = (migrated.opt(Part.LAYOUT.key) as? JSONObject)?.let { ConfigLayout.parse(it, Part.LAYOUT.key, skips) }
        if (migrated.has(Part.LAYOUT.key) && layout == null) skips += Skip(Part.LAYOUT.key, "must be an object")
        return ParsedConfig(version, meta, values, layout, skips)
    }

    private fun <T> attempt(skips: MutableList<Skip>, block: () -> T): T? =
        try { block() } catch (e: SchemaError) { skips += Skip(e.path, e.reason); null }

    /** This phone's configuration with the chosen [parts], as canonical JSON (sorted keys, all values). */
    fun export(store: Store, parts: Set<Part>, meta: ConfigMeta, appVersion: String): String {
        val root = JSONObject()
            .put("format", ConfigSchema.FORMAT)
            .put("version", ConfigSchema.VERSION)
            .put("app", appVersion.take(40))
        val name = meta.name.filterNot(S::isUnsafeChar).trim().take(40)
        val description = meta.description.filterNot(S::isUnsafeChar).trim().take(200)
        if (name.isNotEmpty() || description.isNotEmpty()) root.put("meta", JSONObject().apply {
            if (name.isNotEmpty()) put("name", name)
            if (description.isNotEmpty()) put("description", description)
        })
        for (part in listOf(Part.LOOK, Part.SETTINGS)) {
            if (part !in parts) continue
            root.put(part.key, JSONObject().apply {
                ConfigSchema.settings.filter { it.part == part }.forEach { s -> put(s.key, encodeAny(s, s.read(store))) }
            })
        }
        if (Part.LAYOUT in parts) root.put(Part.LAYOUT.key, ConfigLayout.encode(ConfigLayout.read(store)))
        return canonical(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeAny(s: Setting<*>, value: Any): Any = (s.type as Type<Any>).encode(value)

    /** Current value of a setting as text, for the preview. */
    fun display(value: Any?): String = when (value) {
        null -> "–"
        true -> "on"
        false -> "off"
        is Double -> if (value == Math.floor(value)) "%.1f".format(java.util.Locale.ROOT, value) else value.toString()
        else -> value.toString()
    }

    /** JSON with sorted keys and two-space indent, so equal configurations give equal files. */
    fun canonical(value: Any?, indent: String = ""): String {
        val inner = "$indent  "
        return when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().sorted().toList()
                if (keys.isEmpty()) "{}"
                else keys.joinToString(",\n", "{\n", "\n$indent}") { k -> inner + JSONObject.quote(k) + ": " + canonical(value.opt(k), inner) }
            }
            is JSONArray -> {
                if (value.length() == 0) "[]"
                else (0 until value.length()).joinToString(",\n", "[\n", "\n$indent]") { inner + canonical(value.opt(it), inner) }
            }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            is Double, is Float -> {
                val d = (value as Number).toDouble()
                if (d == Math.floor(d) && !d.isInfinite()) "%.1f".format(java.util.Locale.ROOT, d) else d.toString()
            }
            else -> value.toString()
        }
    }
}

/** Upgrades older files to the current [ConfigSchema.VERSION]. Version 1 is the first: nothing to do yet. */
object ConfigMigrations {
    /** Each entry upgrades a file from its version to the next one. */
    private val steps: Map<Int, (JSONObject) -> JSONObject> = emptyMap()

    fun migrate(root: JSONObject, from: Int): JSONObject {
        var current = root
        for (v in from until ConfigSchema.VERSION) current = steps[v]?.invoke(current) ?: current
        return current
    }
}
