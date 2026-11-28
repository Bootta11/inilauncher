package app.olauncher.extras

import app.olauncher.extras.feedback.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedbackTest {

    private val info = Feedback.Info("0.2.0 (200)", "direct, abc1234", "15 (API 35)", "Google Pixel 8 & Co")

    @Test
    fun bugUrlPrefillsEveryField() {
        val url = Feedback.issueUrl(Feedback.Kind.BUG, info)
        assertTrue(url.startsWith("${Feedback.REPO}/issues/new?template=bug_report.yml&"))
        assertTrue(url.contains("&version=0.2.0%20%28200%29"))
        assertTrue(url.contains("&device=Google%20Pixel%208%20%26%20Co"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun featureUrlHasNoDeviceDetails() {
        assertEquals("${Feedback.REPO}/issues/new?template=feature_request.yml",
            Feedback.issueUrl(Feedback.Kind.FEATURE, info))
    }

    @Test
    fun templatesAndFieldIdsExist() {
        val dir = listOf(File("../.github/ISSUE_TEMPLATE"), File(".github/ISSUE_TEMPLATE")).first { it.isDirectory }
        Feedback.Kind.entries.forEach { assertTrue(it.template, File(dir, it.template).isFile) }
        val bug = File(dir, Feedback.Kind.BUG.template).readText()
        Feedback.BUG_FIELDS.forEach { assertTrue("id $it", Regex("""(?m)^\s+id: $it$""").containsMatchIn(bug)) }
    }

    @Test
    fun mailtoKeepsAtSignAndEncodesBody() {
        val uri = Feedback.mailtoUri("someone@example.com", info)
        assertTrue(uri.startsWith("mailto:someone@example.com?subject=Inilauncher%20feedback&body="))
        assertTrue(uri.contains("App%20version%3A%200.2.0"))
        assertTrue(Feedback.mailBody(info).endsWith("Phone: Google Pixel 8 & Co"))
    }
}
