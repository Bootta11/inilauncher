package app.olauncher.extras.feedback

import java.net.URLEncoder

/**
 * Builds the links behind Settings → Feedback: GitHub's issue forms (.github/ISSUE_TEMPLATE)
 * pre-filled with the app and phone details, or a plain email with the same details.
 * Nothing is sent by the app itself: the person reviews and posts it, under their own account.
 */
object Feedback {

    const val REPO = "https://github.com/Bootta11/inilauncher"

    enum class Kind(val template: String) {
        BUG("bug_report.yml"),
        FEATURE("feature_request.yml"),
    }

    /** What the forms pre-fill. No personal data: no app list, no settings, no account. */
    data class Info(
        val version: String,
        val build: String,
        val android: String,
        val device: String,
    ) {
        fun lines() = listOf(
            "App version: $version",
            "Build: $build",
            "Android: $android",
            "Phone: $device",
        )
    }

    /** Form field ids, the same as the `id:` of the fields in bug_report.yml. */
    internal val BUG_FIELDS = listOf("version", "build", "android", "device")

    fun issueUrl(kind: Kind, info: Info): String {
        val params = mutableListOf("template" to kind.template)
        if (kind == Kind.BUG) {
            params += BUG_FIELDS.zip(listOf(info.version, info.build, info.android, info.device))
        }
        return "$REPO/issues/new?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    fun mailSubject(kind: Kind?): String = when (kind) {
        Kind.BUG -> "Inilauncher: bug report"
        Kind.FEATURE -> "Inilauncher: feature idea"
        null -> "Inilauncher feedback"
    }

    fun mailBody(info: Info): String = "\n\n\n---\n" + info.lines().joinToString("\n")

    /** mailto: with subject and body; RFC 6068 wants %20 for spaces, not +. */
    fun mailtoUri(address: String, info: Info): String =
        "mailto:${encode(address).replace("%40", "@")}?subject=${encode(mailSubject(null))}&body=${encode(mailBody(info))}"

    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
