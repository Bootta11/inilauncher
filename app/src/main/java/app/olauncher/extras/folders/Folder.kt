package app.olauncher.extras.folders

import org.json.JSONArray
import org.json.JSONObject

/** An app inside a folder: package, launcher activity and user (serial number). */
data class FolderApp(val label: String, val packageName: String, val activityClassName: String?, val userSerial: Long)

data class Folder(val id: String, val name: String, val apps: List<FolderApp>)

/** JSON (de)serialisation of the folder list. Pure, so it's unit tested. */
object FolderJson {

    fun encode(folders: List<Folder>): String = JSONArray().apply {
        folders.forEach { folder ->
            put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("apps", appsArray(folder.apps))
            })
        }
    }.toString()

    /** Bad or missing data gives an empty list rather than a crash. */
    fun decode(json: String?): List<Folder> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                Folder(id = id, name = o.optString("name"), apps = apps(o.optJSONArray("apps") ?: JSONArray()))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** A plain list of apps (the home sub list), in the same format as a folder's apps. */
    fun encodeApps(apps: List<FolderApp>): String = appsArray(apps).toString()

    /** Bad or missing data gives an empty list rather than a crash. */
    fun decodeApps(json: String?): List<FolderApp> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            apps(JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun appsArray(apps: List<FolderApp>) = JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().apply {
                put("label", app.label)
                put("package", app.packageName)
                app.activityClassName?.let { put("activity", it) }
                put("user", app.userSerial)
            })
        }
    }

    private fun apps(array: JSONArray): List<FolderApp> = (0 until array.length()).mapNotNull { j ->
        val a = array.optJSONObject(j) ?: return@mapNotNull null
        val pkg = a.optString("package").ifBlank { return@mapNotNull null }
        FolderApp(
            label = a.optString("label", pkg),
            packageName = pkg,
            activityClassName = a.optString("activity").ifBlank { null },
            userSerial = a.optLong("user", 0),
        )
    }
}
