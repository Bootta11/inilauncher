package app.olauncher.extras.iconpack

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.LruCache
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import app.olauncher.extras.folders.Folders
import app.olauncher.extras.widgets.WidgetPanel
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Optional app icons next to app names (app list and home): the system icons, a minimal style
 * (monochrome, grayscale or a letter dot) or an installed icon pack (any pack that answers the
 * common "org.adw.launcher.THEMES" intent and ships an appfilter.xml). Text-only stays the default.
 */
object AppIcons {

    const val MODE_OFF = ""
    const val MODE_SYSTEM = "system"
    const val MODE_MONO = "mono"
    const val MODE_GRAY = "gray"
    const val MODE_LETTER = "letter"
    const val MODE_LETTER_COLOR = "lettercolor"
    const val MODE_LETTER_OUTLINE = "letteroutline"

    /**
     * Icons drawn once into bitmaps at the size they're shown, so scrolling just blits them
     * (adaptive icons otherwise re-mask their layers on every draw). Sized in KB: up to 1/8 of
     * the heap, at most 48 MB, which holds several hundred list-sized icons.
     */
    private val cache by lazy {
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).coerceAtMost(48L * 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    /** One background thread loads icons (see [enqueue] for the order). */
    private val loader by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "inilauncher-icons").apply { priority = Thread.NORM_PRIORITY - 1 } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Views waiting for each key (main thread only), so one load serves all of them. */
    private val waiting = HashMap<String, MutableList<WeakReference<TextView>>>()

    @Volatile private var packMap: Map<String, String> = emptyMap()
    @Volatile private var packByPackage: Map<String, String> = emptyMap()
    @Volatile private var packRes: android.content.res.Resources? = null
    @Volatile private var packName: String? = null
    @Volatile private var cachedMode: String? = null

    fun mode(context: Context): String =
        cachedMode ?: ExtrasPrefs(context).string(ExtrasKeys.ICONS).orEmpty().also { cachedMode = it }

    fun setMode(context: Context, mode: String) {
        ExtrasPrefs(context).setString(ExtrasKeys.ICONS, mode)
        cachedMode = mode
        // Drop queued work for the old style
        backgroundQueue.clear(); waiting.clear(); promoted.clear()
        cache.evictAll()
        packName = null
    }

    /** Installed icon packs: package → label. */
    fun installedPacks(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        return listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
            .flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /**
     * The icon choices in the order they're offered: off, system, the minimal styles, then any
     * installed icon packs (keyed by their package, which always has a dot, so no clash).
     */
    fun options(context: Context): List<Pair<String, String>> = listOf(
        MODE_OFF to context.getString(R.string.icons_off),
        MODE_SYSTEM to context.getString(R.string.icons_system),
        MODE_MONO to context.getString(R.string.icons_mono),
        MODE_GRAY to context.getString(R.string.icons_gray),
        MODE_LETTER to context.getString(R.string.icons_letter),
        MODE_LETTER_COLOR to context.getString(R.string.icons_letter_color),
        MODE_LETTER_OUTLINE to context.getString(R.string.icons_letter_outline),
    ) + installedPacks(context)

    /** The current choice's name, for the settings row. */
    fun label(context: Context, mode: String = mode(context)): String =
        options(context).firstOrNull { it.first == mode }?.second ?: context.getString(R.string.icons_system)

    /**
     * Puts the icon before the app name in [view], or removes it when icons are off.
     * A cached icon shows at once; otherwise the space is kept empty and the icon is loaded on a
     * background thread, so jumping through the list never waits on the package manager.
     * [async] = false loads in place (the home screen, a handful of icons, no pop-in).
     */
    fun decorate(view: TextView, packageName: String, activityClassName: String?, user: UserHandle, async: Boolean = true) {
        val mode = mode(view.context)
        view.setTag(R.id.extras_icon_key, null)
        if (mode == MODE_OFF || packageName.isEmpty()) {
            view.setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val color = view.currentTextColor
        val size = (view.textSize * 1.25f).toInt().coerceAtLeast(1)
        if (async) { listSize = size; listColor = color }
        val icon: Drawable? = when {
            Folders.isFolder(packageName) -> builtIn(view.context, R.drawable.ic_extras_folder, color)
            WidgetPanel.isPanel(packageName) -> builtIn(view.context, R.drawable.ic_extras_widgets, color)
            packageName.startsWith("inilauncher.") -> null
            mode == MODE_LETTER -> LetterIconDrawable(monogram(view.text?.toString().orEmpty()), color)
            else -> {
                val label = view.text?.toString().orEmpty()
                val key = key(mode, packageName, activityClassName, user, color, size, label)
                val cached = cache.get(key)
                when {
                    cached != null -> BitmapDrawable(view.resources, cached)
                    !async -> load(view.context.applicationContext, key, mode, packageName, activityClassName, user, color, size, label)
                        ?.let { BitmapDrawable(view.resources, it) }
                    else -> {
                        view.setTag(R.id.extras_icon_key, key)
                        request(view.context.applicationContext, view, key, mode, packageName, activityClassName, user, color, size, label)
                        ColorDrawable(Color.TRANSPARENT) // keeps the label where it will be
                    }
                }
            }
        }
        show(view, icon, size)
    }

    /**
     * One app's icon at [size] px in the current style, loaded in place and cached (the home sub
     * list: a handful of icons). Icons are the point there, so "off" falls back to system icons.
     */
    fun icon(context: Context, packageName: String, activityClassName: String?, user: UserHandle, size: Int, color: Int, label: String): Drawable? {
        val mode = mode(context).ifEmpty { MODE_SYSTEM }
        if (mode == MODE_LETTER) return LetterIconDrawable(monogram(label), color).apply { setBounds(0, 0, size, size) }
        val key = key(mode, packageName, activityClassName, user, color, size, label)
        val bitmap = load(context.applicationContext, key, mode, packageName, activityClassName, user, color, size, label) ?: return null
        return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, size, size) }
    }

    private fun show(view: TextView, icon: Drawable?, size: Int) {
        icon?.setBounds(0, 0, size, size)
        view.compoundDrawablePadding = (view.textSize * 0.5f).toInt()
        view.setCompoundDrawablesRelative(icon, null, null, null)
    }

    /** Size and colour of the last list icon, so [prefetch] renders the same bitmaps. */
    private var listSize = 0
    private var listColor = 0

    /**
     * Renders the icons of [apps] in the background ahead of time (as many as the cache holds),
     * so jumping to any letter shows them at once. Main thread; needs one list icon shown first.
     */
    fun prefetch(context: Context, apps: List<AppModel>) {
        val mode = mode(context)
        if (mode == MODE_OFF || mode == MODE_LETTER || listSize == 0) return
        val perIconKb = (listSize * listSize * 4 / 1024).coerceAtLeast(1)
        val room = (cache.maxSize() * 0.8f / perIconKb).toInt()
        val app = context.applicationContext
        apps.asSequence().filterIsInstance<AppModel.App>()
            .filterNot { it.appPackage.startsWith("inilauncher.") }
            .take(room)
            .forEach {
                val key = key(mode, it.appPackage, it.activityClassName, it.user, listColor, listSize, it.appLabel)
                if (cache.get(key) == null) request(app, null, key, mode, it.appPackage, it.activityClassName, it.user, listColor, listSize, it.appLabel)
            }
    }

    /** Cache key; colour letter dots also depend on the label (a renamed app gets new letters). */
    private fun key(mode: String, pkg: String, cls: String?, user: UserHandle, color: Int, size: Int, label: String): String =
        "$mode|$pkg|$cls|$user|$color|$size" + if (mode == MODE_LETTER_COLOR || mode == MODE_LETTER_OUTLINE) "|" + monogram(label) else ""

    private fun request(context: Context, view: TextView?, key: String, mode: String, pkg: String, cls: String?, user: UserHandle, color: Int, size: Int, label: String) {
        val existing = waiting[key]
        val list = existing ?: mutableListOf<WeakReference<TextView>>().also { waiting[key] = it }
        view?.let { list += WeakReference(it) }
        val urgent = view != null
        // Already queued: only queue again when a view on screen now needs it and it was a prefetch
        if (existing != null && !(urgent && promoted.add(key))) return
        if (existing == null && urgent) promoted += key
        enqueue(urgent) {
            val bitmap = load(context, key, mode, pkg, cls, user, color, size, label)
            main.post {
                promoted -= key
                waiting.remove(key)?.forEach { ref ->
                    val v = ref.get() ?: return@forEach
                    // Recycled for another app meanwhile? Then it's waiting for a different key.
                    if (v.getTag(R.id.extras_icon_key) != key) return@forEach
                    v.setTag(R.id.extras_icon_key, null)
                    show(v, bitmap?.let { BitmapDrawable(v.resources, it) }, size)
                }
            }
        }
    }

    /** Keys queued for a view on screen (main thread only). */
    private val promoted = HashSet<String>()
    /** On-screen requests run first, newest first (after a jump the new rows win); prefetch after. */
    private val urgentQueue = ConcurrentLinkedDeque<Runnable>()
    private val backgroundQueue = ConcurrentLinkedQueue<Runnable>()

    private fun enqueue(urgent: Boolean, task: Runnable) {
        if (urgent) urgentQueue.addFirst(task) else backgroundQueue.add(task)
        // One token per task; each token runs whichever task is most important by then
        loader.execute { (urgentQueue.pollFirst() ?: backgroundQueue.poll())?.run() }
    }

    /** Loads, styles and rasterizes one icon (any thread), and caches it. */
    private fun load(context: Context, key: String, mode: String, pkg: String, cls: String?, user: UserHandle, color: Int, size: Int, label: String): Bitmap? {
        cache.get(key)?.let { return it }
        val drawable = try {
            when (mode) {
                MODE_SYSTEM -> systemIcon(context, pkg, cls, user)
                MODE_LETTER_COLOR -> LetterIconDrawable(monogram(label), color, LetterIconDrawable.Style.FILLED,
                    systemIcon(context, pkg, cls, user)?.let(::iconColour))
                MODE_LETTER_OUTLINE -> LetterIconDrawable(monogram(label), color, LetterIconDrawable.Style.OUTLINE,
                    systemIcon(context, pkg, cls, user)?.let(::iconColour)?.let { IconColour.readable(it, color) })
                MODE_MONO -> monochrome(context, pkg, cls, user, color)
                MODE_GRAY -> systemIcon(context, pkg, cls, user)?.let { grayscale(it) }
                else -> packIcon(context, mode, pkg, cls) ?: systemIcon(context, pkg, cls, user)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val bitmap = try {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bmp))
            }
        } catch (e: Exception) {
            return null
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Two-letter monogram of an app label, so a dot says more than the first letter beside it:
     * the initials of the first two words (CamelCase counts: WhatsApp → WA, KOReader → KR), or for
     * one word its first letter plus the start of its next syllable (Viber → VB). Upper-cased;
     * '#' when there is nothing usable. Pure, unit tested.
     */
    fun monogram(label: String): String {
        val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.flatMap(::splitCamel)
        if (words.isEmpty()) return "#"
        if (words.size >= 2) return (words[0].take(1) + words[1].take(1)).uppercase()
        val w = words[0]
        if (w.length == 1) return w.uppercase()
        val second = if (!w[0].isLetter()) w[1] else syllableStart(w) ?: w[1]
        return ("" + w[0] + second).uppercase()
    }

    /** CamelCase parts: lower→Upper (WhatsApp → Whats|App) and UPPER→Upper+lower (KOReader → KO|Reader). */
    private fun splitCamel(word: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for (i in 1 until word.length) {
            val prev = word[i - 1]; val c = word[i]
            val lowerToUpper = prev.isLowerCase() && c.isUpperCase()
            val acronymEnd = prev.isUpperCase() && c.isUpperCase() && i + 1 < word.length && word[i + 1].isLowerCase()
            if (lowerToUpper || acronymEnd) { parts += word.substring(start, i); start = i }
        }
        parts += word.substring(start)
        return parts
    }

    private const val VOWELS = "aeiouywàáâãäåæèéêëìíîïòóôõöøùúûüýÿœ"

    /** First consonant after a vowel, past the first letter (Viber → b, Browser → s); null if none. */
    private fun syllableStart(word: String): Char? {
        var seenVowel = word[0].lowercaseChar() in VOWELS
        for (c in word.substring(1)) {
            if (c.lowercaseChar() in VOWELS) seenVowel = true
            // Latin letters only: other scripts fall back to the second letter
            else if (seenVowel && c.isLetter() && c.code < 0x250) return c
        }
        return null
    }

    /** The app icon's main colour, from a small render of it (see [IconColour]). */
    private fun iconColour(icon: Drawable): Int? {
        val side = 24
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, side, side)
        icon.draw(Canvas(bmp))
        val pixels = IntArray(side * side)
        bmp.getPixels(pixels, 0, side, 0, 0, side, side)
        bmp.recycle()
        return IconColour.dominant(pixels)
    }

    private fun builtIn(context: Context, res: Int, color: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(color) }

    /**
     * The app's own single-colour icon (Android 13 "themed icons"), tinted with the text colour.
     * The layer is drawn for a 108dp adaptive canvas, so it's enlarged to fill the slot.
     * Apps without one get a grayscale icon instead.
     */
    private fun monochrome(context: Context, pkg: String, cls: String?, user: UserHandle, color: Int): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val adaptive = activity(context, pkg, cls, user)?.getIcon(context.resources.displayMetrics.densityDpi) as? AdaptiveIconDrawable
            adaptive?.monochrome?.mutate()?.let { mono ->
                mono.setTint(color)
                return InsetDrawable(mono, -0.25f)
            }
        }
        return systemIcon(context, pkg, cls, user)?.let { grayscale(it, 0.7f) }
    }

    private fun grayscale(icon: Drawable, alpha: Float = 0.85f): Drawable = icon.mutate().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        this.alpha = (alpha * 255).toInt()
    }

    private fun activity(context: Context, pkg: String, cls: String?, user: UserHandle): LauncherActivityInfo? = try {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcher.getActivityList(pkg, user)
        activities.firstOrNull { it.componentName.className == cls } ?: activities.firstOrNull()
    } catch (e: Exception) {
        null
    }

    private fun systemIcon(context: Context, pkg: String, cls: String?, user: UserHandle): Drawable? = try {
        activity(context, pkg, cls, user)?.getBadgedIcon(context.resources.displayMetrics.densityDpi)
    } catch (e: Exception) {
        null
    }

    @SuppressLint("DiscouragedApi")
    private fun packIcon(context: Context, pack: String, pkg: String, cls: String?): Drawable? {
        if (packName != pack) loadPack(context, pack)
        val name = packMap["$pkg/$cls"] ?: packByPackage[pkg] ?: return null
        return try {
            val res = packRes ?: return null
            val id = res.getIdentifier(name, "drawable", pack)
            if (id == 0) null else androidx.core.content.res.ResourcesCompat.getDrawable(res, id, null)
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("DiscouragedApi")
    @Synchronized
    private fun loadPack(context: Context, pack: String) {
        if (packName == pack) return
        // Map first, then the name: another thread that sees the name also sees its map
        packRes = try { context.packageManager.getResourcesForApplication(pack) } catch (e: Exception) { null }
        packMap = try {
            val res = packRes ?: throw PackageManager.NameNotFoundException(pack)
            val xmlId = res.getIdentifier("appfilter", "xml", pack)
            if (xmlId != 0) res.getXml(xmlId).use { AppFilterParser.parse(it) }
            else res.assets.open("appfilter.xml").use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, null)
                AppFilterParser.parse(parser)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        packByPackage = AppFilterParser.byPackage(packMap)
        packName = pack
    }

    private inline fun <T> android.content.res.XmlResourceParser.use(block: (XmlPullParser) -> T): T =
        try { block(this) } finally { close() }
}
