package app.olauncher.extras.showcase

/**
 * The showcase: the real launcher screens with stand-in apps, run in their own `:showcase`
 * process with their own preference files, for screenshots without personal data
 * (docs/configuration.md#screenshots).
 *
 * This object is all the everyday launcher ever touches: one boolean, set once when the process
 * starts. Everything else in this package is only loaded from the export flow or the showcase.
 */
object Showcase {

    const val PROCESS_SUFFIX = ":showcase"
    private const val PREFS_PREFIX = "showcase."

    /** True only in the `:showcase` process. Hooks elsewhere check it to skip side effects. */
    @JvmField
    var inProcess = false

    /** The preferences file the showcase uses instead of [name]. */
    fun prefsName(name: String) = if (name.startsWith(PREFS_PREFIX)) name else PREFS_PREFIX + name
}
