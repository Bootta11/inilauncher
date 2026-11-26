package app.olauncher.extras.folders

import android.content.Context
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import java.util.UUID

/** Folders saved in [ExtrasPrefs] as JSON. */
class FolderStore(context: Context) {

    private val prefs = ExtrasPrefs(context)

    /** Decoded once per change: the app list asks for each folder row it draws. */
    fun all(): List<Folder> {
        val json = prefs.string(ExtrasKeys.FOLDERS)
        last?.let { (lastJson, folders) -> if (lastJson == json) return folders }
        return FolderJson.decode(json).also { last = json to it }
    }

    fun get(id: String): Folder? = all().firstOrNull { it.id == id }

    private fun save(folders: List<Folder>) = prefs.setString(ExtrasKeys.FOLDERS, FolderJson.encode(folders))

    fun create(name: String, firstApp: FolderApp? = null): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name.trim(), listOfNotNull(firstApp))
        save(all() + folder)
        return folder
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim()) }

    fun delete(id: String) = save(all().filterNot { it.id == id })

    fun addApp(id: String, app: FolderApp) = update(id) { folder ->
        if (folder.apps.any { it.packageName == app.packageName && it.userSerial == app.userSerial }) folder
        else folder.copy(apps = folder.apps + app)
    }

    fun removeApp(id: String, app: FolderApp) = update(id) { folder ->
        folder.copy(apps = folder.apps.filterNot { it.packageName == app.packageName && it.userSerial == app.userSerial })
    }

    /** Moves [app] to place [to] inside the folder. */
    fun moveApp(id: String, app: FolderApp, to: Int) = update(id) { folder ->
        val apps = folder.apps.toMutableList()
        val from = apps.indexOfFirst { it.packageName == app.packageName && it.userSerial == app.userSerial }
        if (from < 0 || to !in apps.indices) return@update folder
        apps.add(to, apps.removeAt(from))
        folder.copy(apps = apps)
    }

    private fun update(id: String, change: (Folder) -> Folder) =
        save(all().map { if (it.id == id) change(it) else it })

    private companion object {
        @Volatile var last: Pair<String?, List<Folder>>? = null
    }
}
