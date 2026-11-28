package app.olauncher.extras.config

/** One setting the import would change. [online]: it makes the launcher send something off the phone. */
data class Change(val part: Part, val key: String, val from: String, val to: String, val online: Boolean)

/** Everything an import would do, shown to the user before anything is written. */
class ImportPlan(
    val config: ParsedConfig,
    val parts: Set<Part>,
    val changes: List<Change>,
    internal val writes: List<Pair<Setting<*>, Any>>,
    internal val layout: ConfigLayout.Resolved?,
    val layoutSummary: List<String>,
    val skips: List<Skip>,
) {
    val isEmpty get() = writes.isEmpty() && layout == null
}

/** Turns a checked file into a plan for this phone, and applies it. Pure, so it's unit tested. */
object ConfigImport {

    /**
     * Matches [config] against this phone. Only the chosen [parts] count. Settings that need a
     * permission this phone hasn't granted, and icon packs that aren't installed, are skipped.
     */
    fun plan(config: ParsedConfig, parts: Set<Part>, store: Store, device: Device): ImportPlan {
        val skips = config.skips.toMutableList()
        val writes = mutableListOf<Pair<Setting<*>, Any>>()
        val changes = mutableListOf<Change>()
        for ((part, values) in config.values) {
            if (part !in parts) continue
            for ((key, value) in values) {
                val setting = ConfigSchema.byKey.getValue(key)
                if (setting.turnsOn(value) && !device.granted(setting.requires)) {
                    skips += Skip("${part.key}.$key", "needs ${requirementName(setting.requires)}: turn it on in Extras")
                    continue
                }
                if (key == "appIcons" && value is String && value.startsWith(ConfigSchema.ICON_PACK_PREFIX) &&
                    !device.iconPackInstalled(value.removePrefix(ConfigSchema.ICON_PACK_PREFIX))
                ) {
                    skips += Skip("${part.key}.$key", "icon pack ${value.removePrefix(ConfigSchema.ICON_PACK_PREFIX)} isn't installed here")
                    continue
                }
                writes += setting to value
                val current = setting.read(store)
                if (current != value) changes += Change(part, key, ConfigFile.display(current), ConfigFile.display(value), setting.online && value == true)
            }
        }
        val layout = if (Part.LAYOUT in parts) config.layout?.let { ConfigLayout.resolve(it, device, skips) } else null
        return ImportPlan(config, parts, changes, writes, layout, layout?.let(ConfigLayout::summary).orEmpty(), skips)
    }

    /** Writes everything the plan holds into [edits]; the caller commits them together. */
    fun apply(plan: ImportPlan, edits: Edits, device: Device) {
        plan.writes.forEach { (setting, value) -> setting.writeAny(edits, value) }
        plan.layout?.let { ConfigLayout.apply(it, edits, device) }
    }

    fun requirementName(r: Requirement) = when (r) {
        Requirement.CONTACTS -> "contacts access"
        Requirement.CALENDAR -> "calendar access"
        Requirement.NOTIFICATION_ACCESS -> "notification access"
        Requirement.USAGE_ACCESS -> "usage access"
        Requirement.NONE -> "nothing"
    }
}
