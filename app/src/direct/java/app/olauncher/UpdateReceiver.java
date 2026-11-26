package app.olauncher;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * After the app updated itself, Android doesn't let it reopen on its own (no activity starts from the background),
 * so this posts "Updated to X — tap to open".
 */
public class UpdateReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "app-updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        }

        String version = "";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // Shown without the version.
        }
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open == null) return;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_inilauncher)
            .setColor(0xFF3F6E5A)
            .setContentTitle(version.isEmpty() ? "Inilauncher updated" : "Inilauncher updated to " + version)
            .setContentText("Tap to open")
            .setContentIntent(tap)
            .setAutoCancel(true);
        try {
            manager.notify(CHANNEL.hashCode(), notification.build());
        } catch (SecurityException ignored) {
            // Notification permission revoked meanwhile.
        }
    }
}
