package app.olauncher.extras.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.olauncher.extras.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/** A non-app search result: contact, app shortcut, calculator answer or web suggestion. */
sealed class SearchResult(val label: String) {
    class Contact(label: String, val uri: Uri) : SearchResult(label)
    class Shortcut(label: String, val appLabel: String, val info: ShortcutInfo) : SearchResult(label)
    class Calculation(label: String, val value: String) : SearchResult(label)
    class WebSuggestion(label: String) : SearchResult(label)
}

/** The search sources beyond upstream's app search. Each is off until turned on in Extras. */
object SearchProviders {

    const val MAX_PER_SOURCE = 3

    fun hasContactsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts whose name matches [query] (never leaves the phone). */
    fun contacts(context: Context, query: String): List<SearchResult.Contact> {
        if (query.length < 2 || !hasContactsPermission(context)) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val results = mutableListOf<SearchResult.Contact>()
        try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY,
            )?.use { c ->
                while (c.moveToNext() && results.size < MAX_PER_SOURCE) {
                    val name = c.getString(1) ?: continue
                    results += SearchResult.Contact(name, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.getLong(0)))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return results
    }

    fun contactIntent(result: SearchResult.Contact) =
        Intent(Intent.ACTION_VIEW, result.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Written in the background when the app list opens, read while typing. */
    @Volatile private var shortcutCache: List<ShortcutInfo>? = null

    /** True when Android lets us read other apps' shortcuts (Android 7.1+, only as the default launcher). */
    fun canReadShortcuts(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && try {
        (context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps).hasShortcutHostPermission()
    } catch (e: Exception) {
        false
    }

    /** Loads every app's static and dynamic shortcuts once per app-list visit. Slow: call off the main thread. */
    fun loadShortcuts(context: Context) {
        shortcutCache = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || !canReadShortcuts(context)) emptyList() else try {
            val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            )
            launcher.getShortcuts(query, Process.myUserHandle()).orEmpty().filter { it.isEnabled }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun shortcuts(context: Context, query: String): List<SearchResult.Shortcut> {
        if (query.length < 2 || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        val pm = context.packageManager
        return shortcutCache.orEmpty()
            .filter { (it.shortLabel ?: "").contains(query, ignoreCase = true) || (it.longLabel ?: "").contains(query, ignoreCase = true) }
            .take(MAX_PER_SOURCE)
            .map { info ->
                val appLabel = try { pm.getApplicationLabel(pm.getApplicationInfo(info.`package`, 0)).toString() } catch (e: Exception) { info.`package` }
                SearchResult.Shortcut(info.shortLabel?.toString() ?: info.id, appLabel, info)
            }
    }

    /** Shortcuts of one app, for its long-press menu. */
    fun shortcutsOf(context: Context, packageName: String): List<ShortcutInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        if (shortcutCache == null) loadShortcuts(context)
        return shortcutCache.orEmpty().filter { it.`package` == packageName }
    }

    fun launchShortcut(context: Context, info: ShortcutInfo): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && try {
        (context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps).startShortcut(info, null, null)
        true
    } catch (e: Exception) {
        false
    }

    fun calculation(query: String): SearchResult.Calculation? =
        ExpressionEvaluator.evaluate(query)?.let { SearchResult.Calculation("= $it", it) }

    /**
     * Whether [query] is sent to the web for suggestions: two characters or more, and not a sum while the
     * calculator is on (it answers sums on the phone, so "12*3" never leaves it). Pure, unit tested.
     */
    fun sendsToWeb(query: String, webOn: Boolean, calculatorOn: Boolean): Boolean =
        webOn && query.trim().length >= 2 && !(calculatorOn && ExpressionEvaluator.couldBeCalculation(query))

    /** DuckDuckGo's autocomplete (unofficial endpoint; any failure just shows nothing). Sends [query]. */
    suspend fun webSuggestions(query: String): List<SearchResult.WebSuggestion> = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.startsWith("!")) return@withContext emptyList()
        try {
            val url = "https://duckduckgo.com/ac/?type=list&q=" + URLEncoder.encode(query.trim(), "UTF-8")
            val body = Http.getText(url, timeoutMs = 4000, maxBytes = 64 * 1024) ?: return@withContext emptyList()
            parseSuggestions(body).take(MAX_PER_SOURCE).map { SearchResult.WebSuggestion(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Accepts both answer shapes: ["q", ["a","b"]] and [{"phrase":"a"}, …]. */
    fun parseSuggestions(body: String): List<String> = try {
        val array = JSONArray(body)
        if (array.length() == 2 && array.optJSONArray(1) != null) {
            val list = array.getJSONArray(1)
            (0 until list.length()).map { list.getString(it) }
        } else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("phrase")?.takeIf { p -> p.isNotBlank() } }
    } catch (e: Exception) {
        emptyList()
    }
}
