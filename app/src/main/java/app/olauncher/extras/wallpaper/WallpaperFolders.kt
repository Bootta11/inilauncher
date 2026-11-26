package app.olauncher.extras.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs

/**
 * The user's light and dark photo folders for the daily wallpaper, picked with the system folder
 * picker. Read access is kept across restarts; no storage permission is needed. Only photos directly
 * in the folder count, not ones in subfolders.
 */
object WallpaperFolders {

    private fun key(dark: Boolean) =
        if (dark) ExtrasKeys.DAILY_WALLPAPER_DARK_DIR else ExtrasKeys.DAILY_WALLPAPER_LIGHT_DIR

    fun folder(context: Context, dark: Boolean): Uri? =
        ExtrasPrefs(context).string(key(dark))?.let(Uri::parse)

    /** Keeps read access to [tree] and stores it; access to the folder it replaces is let go. */
    fun set(context: Context, dark: Boolean, tree: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val old = folder(context, dark)
        ExtrasPrefs(context).setString(key(dark), tree.toString())
        if (old != null && old != tree) release(context, old)
    }

    fun clear(context: Context, dark: Boolean) {
        val old = folder(context, dark) ?: return
        ExtrasPrefs(context).setString(key(dark), null)
        release(context, old)
    }

    /** Let go of [tree], unless the other folder is the same one. */
    private fun release(context: Context, tree: Uri) {
        if (folder(context, true) == tree || folder(context, false) == tree) return
        try {
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Already gone
        }
    }

    /** The folder's name, or null when it can't be read any more (deleted, access gone). */
    fun name(context: Context, tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** The photos directly in [tree] with their names. Empty when it can't be read. */
    fun photos(context: Context, tree: Uri): List<Pair<Uri, String>> = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2).orEmpty()
                    if (!mime.startsWith("image/")) continue
                    add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)) to cursor.getString(1).orEmpty())
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** Today's photo for [dark] (dark theme or night): from its own folder, else the other one. Null if neither has any. */
    fun todaysPhoto(context: Context, dark: Boolean, epochDay: Long): Uri? {
        for (tree in listOfNotNull(folder(context, dark), folder(context, !dark))) {
            WallpaperList.pickFromFolder(photos(context, tree), epochDay) { it.second }?.let { return it.first }
        }
        return null
    }
}
