package app.olauncher.extras.feedback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.extras.ListDialog
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.showToast

/** Settings → Feedback: report a bug, suggest a feature, or (when set at build time) email. */
object FeedbackLinks {

    fun showMenu(anchor: View) {
        val context = anchor.context
        val email = BuildConfig.FEEDBACK_EMAIL
        val options = buildList {
            add(context.getString(R.string.feedback_bug))
            add(context.getString(R.string.feedback_feature))
            if (email.isNotBlank()) add(context.getString(R.string.feedback_email))
        }
        ListDialog.show(context, context.getString(R.string.feedback), options) { i ->
            when (i) {
                0 -> open(context, Intent.ACTION_VIEW, Feedback.issueUrl(Feedback.Kind.BUG, info(context)))
                1 -> open(context, Intent.ACTION_VIEW, Feedback.issueUrl(Feedback.Kind.FEATURE, info(context)))
                2 -> open(context, Intent.ACTION_SENDTO, Feedback.mailtoUri(email, info(context)))
            }
        }
    }

    internal fun info(context: Context) = Feedback.Info(
        version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        build = buildString {
            append(BuildConfig.FLAVOR)
            if (BuildConfig.DEBUG) append(" debug")
            append(", ").append(BuildConfig.GIT_SHA)
            if (context.isEinkDisplay()) append(", e-ink")
        },
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    private fun open(context: Context, action: String, uri: String) {
        try {
            context.startActivity(Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.showToast(R.string.feedback_no_app)
        }
    }
}
