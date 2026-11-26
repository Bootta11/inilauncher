package app.olauncher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Asks Zealot whether a newer build of this app exists (direct build only, docs/app-updates.md):
 * GET /api/apps/latest?channel_key=…&bundle_id=…&release_version=…&build_version=…. Used by the stacks that check
 * natively (native Android, React Native, Flutter); the Capacitor template does the same in TypeScript.
 */
public final class ZealotClient {

    public static final class Release {
        public final String versionName;
        public final long versionCode;
        public final String changelog;
        public final String url;

        Release(String versionName, long versionCode, String changelog, String url) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.changelog = changelog;
            this.url = url;
        }
    }

    /** Zealot's answer is a few KB; anything far bigger isn't Zealot. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private ZealotClient() {}

    /** Whether [url] is on the same https host as [zealotUrl]: APKs come only from our own Zealot. Pure, unit tested. */
    public static boolean sameHost(String url, String zealotUrl) {
        try {
            java.net.URI a = new java.net.URI(url);
            java.net.URI b = new java.net.URI(zealotUrl);
            return "https".equalsIgnoreCase(a.getScheme()) && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** The installed versionName and versionCode. */
    @SuppressWarnings("deprecation")
    public static PackageInfo installed(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    }

    public static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : versionCodeLegacy(info);
    }

    @SuppressWarnings("deprecation")
    private static long versionCodeLegacy(PackageInfo info) {
        return info.versionCode;
    }

    /** The newest release above the installed one, or null. Blocking: call it off the main thread. */
    public static Release latest(Context context, String zealotUrl, String channelKey) throws Exception {
        PackageInfo info = installed(context);
        long current = versionCode(info);
        String url = Uri.parse(zealotUrl + "/api/apps/latest").buildUpon()
            .appendQueryParameter("channel_key", channelKey)
            .appendQueryParameter("bundle_id", context.getPackageName())
            .appendQueryParameter("release_version", info.versionName)
            .appendQueryParameter("build_version", String.valueOf(current))
            .build().toString();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        String body;
        try {
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BODY_BYTES) throw new java.io.IOException("Zealot answer too big");
                }
                body = out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }

        // Zealot answers one release or a list of the newer ones.
        Object raw = new JSONObject(body).opt("releases");
        JSONArray releases = raw instanceof JSONArray ? (JSONArray) raw : new JSONArray();
        if (raw instanceof JSONObject) releases.put(raw);
        Release newest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null) continue;
            long code;
            try {
                code = Long.parseLong(r.optString("build_version"));
            } catch (NumberFormatException e) {
                continue;
            }
            String installUrl = r.optString("install_url");
            if (code <= current) continue;
            if (!sameHost(installUrl, zealotUrl)) {
                android.util.Log.w("ZealotClient", "Skipping release " + code + ": its APK isn't on " + zealotUrl);
                continue;
            }
            if (newest == null || code > newest.versionCode) {
                newest = new Release(r.optString("release_version"), code, changelog(r.opt("changelog")), installUrl);
            }
        }
        return newest;
    }

    private static String changelog(Object c) {
        if (c instanceof String) return ((String) c).trim();
        if (!(c instanceof JSONArray)) return "";
        StringBuilder text = new StringBuilder();
        JSONArray entries = (JSONArray) c;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            String message = e == null ? "" : e.optString("message").trim();
            if (message.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(message);
        }
        return text.toString();
    }
}
