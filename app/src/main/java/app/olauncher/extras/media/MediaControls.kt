package app.olauncher.extras.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.olauncher.extras.notifications.InilauncherNotificationListener

/** Now-playing info and controls, read through the notification-access permission. */
object MediaControls {

    data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val controller: MediaController)

    fun current(context: Context): NowPlaying? {
        if (!InilauncherNotificationListener.isAccessGranted(context)) return null
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = manager.getActiveSessions(InilauncherNotificationListener.component(context))
            val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
                ?: return null
            val meta = controller.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            if (title.isBlank()) return null
            NowPlaying(
                title = title,
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                controller = controller,
            )
        } catch (e: SecurityException) {
            null
        }
    }

    fun togglePlay(now: NowPlaying) =
        if (now.playing) now.controller.transportControls.pause() else now.controller.transportControls.play()

    fun next(now: NowPlaying) = now.controller.transportControls.skipToNext()
    fun previous(now: NowPlaying) = now.controller.transportControls.skipToPrevious()
}
