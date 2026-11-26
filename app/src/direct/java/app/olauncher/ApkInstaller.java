package app.olauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Installs a new APK of this app (direct build only, docs/app-updates.md): downloads it, reporting progress, and hands
 * it to Android's PackageInstaller. Android checks that it's signed with the same key as the installed app. On Android
 * 12+ the update installs without a confirm screen when this app installed the current version; otherwise
 * InstallStatusReceiver opens the confirm screen. Every stack's bridge (Capacitor plugin, React Native module, Flutter
 * channel, or the app's own Kotlin) calls this.
 */
public final class ApkInstaller {

    public interface Progress {
        void onPercent(int percent);
    }

    /** Far above the app's size; a bigger download is refused. */
    private static final long MAX_APK_BYTES = 150L * 1024 * 1024;

    private ApkInstaller() {}

    /** Whether Android lets this app install APKs ("Install unknown apps" for this app). */
    public static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getPackageManager().canRequestPackageInstalls();
    }

    /** Android's "Install unknown apps" switch for this app. */
    public static void openInstallSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Downloads and commits the APK. Blocking: call it off the main thread. Returns once Android took over. */
    public static void install(Context context, String url, Progress progress) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new IllegalArgumentException("An https URL is required");
        File apk = download(context, url, progress);
        try {
            verify(context, apk);
            commit(context, apk);
        } finally {
            apk.delete();
        }
    }

    private static File download(Context context, String url, Progress progress) throws Exception {
        // Not the cache: the launcher clears it every few hours, maybe mid-download. Not backed up either.
        File apk = new File(context.getNoBackupFilesDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        try {
            if (conn.getResponseCode() != 200) throw new Exception("download answered " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            if (total > MAX_APK_BYTES) throw new Exception("download too big (" + total + " bytes)");
            long done = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int lastPercent = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done > MAX_APK_BYTES) throw new Exception("download too big");
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onPercent(percent);
                    }
                }
            }
            if (total > 0 && done != total) throw new Exception("download incomplete (" + done + " of " + total + " bytes)");
        } catch (Exception e) {
            apk.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
        return apk;
    }

    /**
     * Checks the APK before Android sees it: this app's package, a newer version, and (Android 9+) signed by a key the
     * installed app has. Android checks the signature again; this only turns a wrong file into a clear error.
     */
    @SuppressWarnings("deprecation")
    private static void verify(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null) throw new Exception("the download isn't an APK");
        if (!context.getPackageName().equals(archive.packageName)) throw new Exception("the APK is for another app (" + archive.packageName + ")");
        long installed = ZealotClient.versionCode(ZealotClient.installed(context));
        if (ZealotClient.versionCode(archive) <= installed) throw new Exception("the APK isn't newer than the installed version");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && archive.signingInfo != null) {
            SigningInfo info = archive.signingInfo;
            Signature[] signers = info.hasMultipleSigners() ? info.getApkContentsSigners() : info.getSigningCertificateHistory();
            boolean known = false;
            for (Signature signer : signers) {
                if (pm.hasSigningCertificate(context.getPackageName(), signer.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)) {
                    known = true;
                    break;
                }
            }
            if (!known) throw new Exception("the APK is signed with a different key");
        }
    }

    private static void commit(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        // Only an update of this app: Android refuses any other package.
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            // Mutable: the installer adds the status extras to this intent.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            Intent status = new Intent(context, InstallStatusReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
        }
    }
}
