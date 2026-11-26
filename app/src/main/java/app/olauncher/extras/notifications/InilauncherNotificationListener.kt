package app.olauncher.extras.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.olauncher.extras.ExtrasKeys
import app.olauncher.extras.ExtrasPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One notification as shown in Inilauncher (no data leaves the phone). */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean,
)

/**
 * Reads other apps' notifications so the home screen can show unread counts and
 * a notification list with open/reply/dismiss. You turn it on in Android's
 * "Notification access" settings; it stays off until then.
 */
class InilauncherNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var publishQueued = false
    private val publishQueuedNow = Runnable {
        publishQueued = false
        publish()
    }

    override fun onListenerConnected() {
        instance = this
        prefs = ExtrasPrefs(this)
        hidden = prefs?.string(ExtrasKeys.NOTIFICATIONS_HIDDEN).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        publish()
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(publishQueuedNow)
        publishQueued = false
        instance = null
        state.value = emptyList()
        hiddenState.value = 0
    }

    override fun onDestroy() {
        handler.removeCallbacks(publishQueuedNow)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = queuePublish()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = queuePublish()

    /**
     * At most one refresh per [PUBLISH_EVERY_MS]: a download's progress notification updates many times a
     * second, and each refresh redraws the home screen.
     */
    private fun queuePublish() {
        if (publishQueued) return
        publishQueued = true
        handler.postDelayed(publishQueuedNow, PUBLISH_EVERY_MS)
    }

    private fun publish() {
        val active = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        // Forget hidden keys once their notification is gone, so a new one from that app shows again
        val stillHidden = hidden.filterTo(mutableSetOf()) { key -> active.any { it.key == key } }
        if (stillHidden != hidden) setHidden(stillHidden)
        val shown = NotificationGrouping.visible(active.map { it.toItem() }, packageName)
        state.value = shown.filter { it.key !in hidden }
        hiddenState.value = shown.count { it.key in hidden }
        raw = active.associateBy { it.key }
    }

    private fun StatusBarNotification.toItem(): NotificationItem {
        val extras = notification.extras
        return NotificationItem(
            key = key,
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            postTime = postTime,
            clearable = isClearable && !isOngoing && notification.flags and Notification.FLAG_GROUP_SUMMARY == 0,
        )
    }

    companion object {
        private const val PUBLISH_EVERY_MS = 250L
        private val state = MutableStateFlow<List<NotificationItem>>(emptyList())
        val notifications: StateFlow<List<NotificationItem>> = state.asStateFlow()
        private val hiddenState = MutableStateFlow(0)
        /** How many current notifications are hidden in Inilauncher (see [dismiss]). */
        val hiddenCount: StateFlow<Int> = hiddenState.asStateFlow()

        @Volatile private var instance: InilauncherNotificationListener? = null
        @Volatile private var raw: Map<String, StatusBarNotification> = emptyMap()

        /**
         * Persistent notifications (e.g. "Android Auto is available") that Android won't let anyone
         * cancel: dismissing one hides it in Inilauncher only. It stays in the system shade.
         */
        @Volatile private var hidden: Set<String> = emptySet()
        @Volatile private var prefs: ExtrasPrefs? = null

        private fun setHidden(keys: Set<String>) {
            hidden = keys
            prefs?.setString(ExtrasKeys.NOTIFICATIONS_HIDDEN, keys.joinToString("\n").ifEmpty { null })
        }

        fun component(context: Context) = ComponentName(context, InilauncherNotificationListener::class.java)

        fun isAccessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component(context) }
        }

        fun statusBarNotification(key: String): StatusBarNotification? = raw[key]

        /** Clears a notification; one Android won't clear is hidden in Inilauncher instead. */
        fun dismiss(key: String) {
            val service = instance ?: return
            val sbn = raw[key]
            if (sbn != null && (!sbn.isClearable || sbn.isOngoing || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)) {
                setHidden(hidden + key)
                service.handler.post { service.publish() }
                return
            }
            try { service.cancelNotification(key) } catch (e: Exception) { }
        }

        /** Shows every hidden notification on the launcher again. */
        fun unhideAll() {
            val service = instance ?: return
            setHidden(emptySet())
            service.handler.post { service.publish() }
        }
    }
}
