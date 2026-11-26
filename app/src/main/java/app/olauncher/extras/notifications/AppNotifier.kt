package app.olauncher.extras.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.olauncher.MainActivity
import app.olauncher.R

/**
 * Notifications Inilauncher posts itself (for example "Inilauncher updated, tap to open").
 * Local only: no Firebase, no push server.
 */
object AppNotifier {

    private const val CHANNEL_ID = "inilauncher_updates"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun hasPermission(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Asks once at startup (Android 13+); the launcher works the same if you say no. */
    fun requestPermissionIfNeeded(context: Context, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(context))
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Posts [text]; tapping it opens the launcher. Skipped while the launcher is on screen. */
    fun notify(context: Context, id: Int, title: String, text: String, launcherVisible: Boolean) {
        if (launcherVisible || !hasPermission(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_inilauncher)
            .setColor(ContextCompat.getColor(context, R.color.inilauncher_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile
        }
    }
}
