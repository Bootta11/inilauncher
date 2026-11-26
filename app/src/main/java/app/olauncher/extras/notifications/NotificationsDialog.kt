package app.olauncher.extras.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.extras.ForegroundStart
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.expandVisible
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showToast

/**
 * Lists notifications as cards, grouped by app: tap a card to open it, swipe it sideways to
 * dismiss, "Reply" where the app allows it. "Clear all" sweeps every card away. Persistent ones
 * Android won't clear are only hidden in Inilauncher.
 */
object NotificationsDialog {

    fun show(context: Context, onlyPackage: String? = null) {
        val all = InilauncherNotificationListener.notifications.value
        val items = if (onlyPackage == null) all else all.filter { it.packageName == onlyPackage }
        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.setText(R.string.notifications)
        binding.ivClose.setOnClickListener { dialog.dismiss() }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 16), 0, 0)
            clipChildren = false
        }
        // Swiped cards collapse their height frame by frame, so everything else that appears or
        // disappears here (footer lines, returning cards, "Clear all") does it the same way
        // (expandVisible): a container transition would aim at a stale size and overshoot.
        var hiddenCount = if (onlyPackage == null) InilauncherNotificationListener.hiddenCount.value else 0
        val empty = text(context, R.style.TextSmallLight, context.getString(R.string.notifications_none))
        list.addView(empty)
        val cards = mutableListOf<Pair<SwipeDismissLayout, NotificationItem>>()
        // Persistent cards cleared here stay in the system shade; this puts them back
        val showHidden = TextView(context, null, 0, R.style.TextSmallBold).apply {
            textSize = 14f
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        fun refreshFooter() {
            empty.expandVisible(cards.isEmpty() && hiddenCount == 0)
            binding.tvAction.expandVisible(cards.isNotEmpty())
            if (hiddenCount > 0) showHidden.text = context.resources.getQuantityString(R.plurals.notifications_show_hidden, hiddenCount, hiddenCount)
            showHidden.expandVisible(hiddenCount > 0, targetAlpha = 0.6f)
        }
        fun addCard(item: NotificationItem, index: Int) {
            lateinit var card: SwipeDismissLayout
            card = card(context, dialog, item, onLeaving = {
                // As its space starts closing, so the footer change moves together with it
                cards.removeAll { it.first === card }
                if (!item.clearable) hiddenCount++ // hidden, not cleared (see InilauncherNotificationListener.dismiss)
                refreshFooter()
            }) { list.removeView(card) }
            cards += card to item
            list.addView(card, index)
            if (list.isLaidOut) { card.isVisible = false; card.expandVisible(true) } // returning: grows in
        }
        /** Adds cards for notifications not shown yet, each in its place in the grouped order. */
        fun fill() {
            val now = InilauncherNotificationListener.notifications.value
            val wanted = if (onlyPackage == null) now else now.filter { it.packageName == onlyPackage }
            var index = 1 // after "No notifications"
            NotificationGrouping.byApp(wanted).flatMap { it.second }.forEach { item ->
                if (cards.none { it.second.key == item.key }) addCard(item, index)
                index++
            }
        }
        NotificationGrouping.byApp(items).flatMap { it.second }.forEach { addCard(it, list.childCount) }
        list.addView(showHidden)
        showHidden.isVisible = false
        showHidden.setOnClickListener {
            InilauncherNotificationListener.unhideAll()
            // The listener republishes on the next main-loop turn; then the cards slide back in here
            list.post {
                hiddenCount = 0
                fill()
                refreshFooter()
            }
        }

        binding.tvAction.setText(R.string.notifications_clear_all)
        binding.tvAction.setOnClickListener {
            // Dismiss now, not when each animation ends: the popup may close first. Persistent ones are hidden
            val anyHidden = cards.any { !it.second.clearable }
            cards.forEach { InilauncherNotificationListener.dismiss(it.second.key) }
            cards.toList().forEachIndexed { i, (card, _) -> card.flyOff(1f, delay = i * 45L) }
            // Stay open when some were only hidden, so "Show N hidden" is there to undo it; otherwise
            // close once the cards have flown off and collapsed
            if (!anyHidden) list.postDelayed({ dialog.dismiss() }, 220L + 220L + cards.size * 45L)
        }
        refreshFooter()

        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    /** One rounded card: app icon, app name and time, then title, text and Reply. */
    private fun card(context: Context, dialog: OlDialog, item: NotificationItem, onLeaving: () -> Unit, onRemoved: () -> Unit): SwipeDismissLayout {
        val primary = context.getColorFromAttr(R.attr.primaryColor)
        val pad = dp(context, 14)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(context, 12), pad, dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18).toFloat()
                setColor(ColorUtils.setAlphaComponent(primary, 0x1C))
                setStroke(dp(context, 1), ColorUtils.setAlphaComponent(primary, 0x14))
            }
        }

        // Header: icon · app · time
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        appIcon(context, item.packageName)?.let { icon ->
            header.addView(ImageView(context).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply { marginEnd = dp(context, 8) })
        }
        header.addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = appLabel(context, item.packageName)
            textSize = 13f
            alpha = 0.75f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            textSize = 12f
            alpha = 0.55f
        })
        column.addView(header)

        if (item.title.isNotBlank()) column.addView(TextView(context, null, 0, R.style.TextSmallBold).apply {
            text = item.title
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(context, 8), 0, 0)
        })
        if (item.text.isNotBlank()) column.addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = item.text
            textSize = 15f
            alpha = 0.85f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(0, dp(context, if (item.title.isBlank()) 8 else 2), 0, 0)
        })

        replyAction(item)?.let { (action, input) ->
            column.addView(link(context, R.string.notifications_reply) { showReply(context, column, action, input) })
        }

        return SwipeDismissLayout(context).apply {
            addView(column)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(context, 10) }
            isClickable = true
            setOnClickListener {
                open(context, item)
                dialog.dismiss()
            }
            onCollapseStart = onLeaving
            onDismiss = {
                InilauncherNotificationListener.dismiss(item.key)
                onRemoved()
            }
        }
    }

    private fun appIcon(context: Context, pkg: String) = try {
        context.packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun showReply(context: Context, column: LinearLayout, action: Notification.Action, input: RemoteInput) {
        if (column.findViewWithTag<View>("reply") != null) return
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "reply" }
        val field = (LayoutInflater.from(context).inflate(R.layout.extras_folder_name_input, box, false) as EditText).apply {
            setHint(R.string.notifications_reply_hint)
            filters = arrayOf()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(field)
        box.addView(link(context, R.string.notifications_send) {
            val message = field.text.toString().trim()
            if (message.isEmpty()) return@link
            sendReply(context, action, input, message)
            box.expandVisible(false)
        })
        column.addView(box)
        box.isVisible = false
        box.expandVisible(true) // opens smoothly; the card grows with it
        field.requestFocus()
    }

    private fun sendReply(context: Context, action: Notification.Action, input: RemoteInput, message: String) {
        val intent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, message) })
        try {
            action.actionIntent.send(context, 0, intent)
            context.showToast(context.getString(R.string.notifications_sent))
        } catch (e: PendingIntent.CanceledException) {
            context.showToast(context.getString(R.string.notifications_reply_failed))
        }
    }

    private fun replyAction(item: NotificationItem): Pair<Notification.Action, RemoteInput>? {
        val sbn = InilauncherNotificationListener.statusBarNotification(item.key) ?: return null
        sbn.notification.actions?.forEach { action ->
            action.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.let { return action to it }
        }
        return null
    }

    private fun open(context: Context, item: NotificationItem) {
        val sbn = InilauncherNotificationListener.statusBarNotification(item.key)
        val intent = sbn?.notification?.contentIntent
        try {
            if (intent != null) sendFromForeground(context, intent) else context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (sbn != null && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
                InilauncherNotificationListener.dismiss(item.key)
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_app))
        }
    }

    /** Opens another app's PendingIntent, lending the on-screen launcher's right to start it (see [ForegroundStart]). */
    private fun sendFromForeground(context: Context, intent: PendingIntent) =
        intent.send(context, 0, null, null, null, null, ForegroundStart.options())

    fun appLabel(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun text(context: Context, style: Int, value: String) = TextView(context, null, 0, style).apply {
        text = value
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun link(context: Context, label: Int, onClick: () -> Unit) = TextView(context, null, 0, R.style.TextSmallBold).apply {
        setText(label)
        textSize = 14f
        setPadding(0, dp(context, 10), dp(context, 20), 0)
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
