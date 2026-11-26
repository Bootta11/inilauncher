package app.olauncher.extras

import app.olauncher.extras.folders.Folder
import app.olauncher.extras.folders.FolderApp
import app.olauncher.extras.folders.FolderJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderJsonTest {

    private val folders = listOf(
        Folder("a", "Work", listOf(
            FolderApp("Mail", "com.example.mail", "com.example.mail.Main", 0),
            FolderApp("Chat", "com.example.chat", null, 10),
        )),
        Folder("b", "Empty", emptyList()),
    )

    @Test
    fun roundTrip() {
        assertEquals(folders, FolderJson.decode(FolderJson.encode(folders)))
    }

    @Test
    fun badInputGivesEmptyList() {
        assertTrue(FolderJson.decode(null).isEmpty())
        assertTrue(FolderJson.decode("").isEmpty())
        assertTrue(FolderJson.decode("not json").isEmpty())
        assertTrue(FolderJson.decode("{}").isEmpty())
    }

    @Test
    fun skipsEntriesWithoutIdOrPackage() {
        val json = """[{"name":"no id"},{"id":"x","name":"X","apps":[{"label":"no package"},{"package":"p"}]}]"""
        val decoded = FolderJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(listOf(FolderApp("p", "p", null, 0)), decoded[0].apps)
    }

    @Test
    fun appListRoundTrip() {
        val apps = folders[0].apps
        assertEquals(apps, FolderJson.decodeApps(FolderJson.encodeApps(apps)))
        assertTrue(FolderJson.decodeApps(null).isEmpty())
        assertTrue(FolderJson.decodeApps("{}").isEmpty())
    }
}
