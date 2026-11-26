package app.olauncher

/**
 * Where new APKs come from (docs/app-updates.md). Neither value is secret: the channel key only reads that Zealot
 * channel.
 */
object AppRelease {
    const val ZEALOT_URL = "https://apps.bootta.dev"

    /** The Zealot channel the direct APK is uploaded to. Empty: the app doesn't look for APK updates. */
    const val ZEALOT_CHANNEL_KEY = ""
}

/** A newer APK on Zealot. Only the direct build ever has one (ApkUpdates in src/direct). */
data class ApkRelease(val versionName: String, val versionCode: Long, val changelog: String, val url: String)

/** Where an APK install stands (ApkUpdates.installState); one runs at a time, whichever screen started it. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val percent: Int) : InstallState
    data object Failed : InstallState
}

/** What's running, for a settings/about screen: "0.1.2 (102) · 1e5c898", or "… · dev" for local builds. */
val versionLine: String
    get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA.take(7)}"
