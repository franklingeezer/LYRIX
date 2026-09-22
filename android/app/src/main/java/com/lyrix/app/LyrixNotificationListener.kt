package com.lyrix.app

import android.content.ComponentName
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

class LyrixNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var prefs: SharedPreferences

    private val handler =
        Handler(Looper.getMainLooper())

    private val pollInterval = 300L

    private var lastSentSongKey = ""

    private val mediaPoller =
        object : Runnable {

            override fun run() {

                updateCurrentSong()

                handler.postDelayed(
                    this,
                    pollInterval
                )
            }
        }


    override fun onListenerConnected() {

        super.onListenerConnected()

        prefs =
            getSharedPreferences(
                "lyrix",
                MODE_PRIVATE
            )

        mediaSessionManager =
            getSystemService(
                MEDIA_SESSION_SERVICE
            ) as MediaSessionManager

        updateCurrentSong()

        handler.post(
            mediaPoller
        )

        Log.d(
            "LYRIX_MEDIA",
            "Notification listener connected"
        )
    }


    private fun updateCurrentSong() {

        try {

            val componentName =
                ComponentName(
                    this,
                    LyrixNotificationListener::class.java
                )

            val controllers =
                mediaSessionManager
                    .getActiveSessions(componentName)

            val controller =
                controllers.firstOrNull {
                    it.playbackState?.state ==
                            PlaybackState.STATE_PLAYING
                }
                    ?: controllers.firstOrNull()


            if (controller == null) {

                saveSong(
                    title = "No music detected",
                    artist = "—",
                    playing = false,
                    position = 0L,
                    positionUpdateTime = SystemClock.elapsedRealtime(),
                    positionSpeed = 1f
                )

                return
            }


            val metadata =
                controller.metadata

            val playbackState =
                controller.playbackState


            val title =
                metadata
                    ?.getString(
                        MediaMetadata.METADATA_KEY_TITLE
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Unknown Song"


            val artist =
                metadata
                    ?.getString(
                        MediaMetadata.METADATA_KEY_ARTIST
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Unknown Artist"


            val playbackStatus =
                playbackState?.state
                    ?: PlaybackState.STATE_NONE


            val playing =
                playbackStatus ==
                        PlaybackState.STATE_PLAYING


            val position =
                playbackState?.position
                    ?.coerceAtLeast(0L)
                    ?: 0L


            val positionUpdateTime =
                playbackState?.lastPositionUpdateTime
                    ?.takeIf { it > 0L }
                    ?: SystemClock.elapsedRealtime()


            val positionSpeed =
                playbackState?.playbackSpeed
                    ?.takeIf { it > 0f }
                    ?: 1f


            saveSong(
                title = title,
                artist = artist,
                playing = playing,
                position = position,
                positionUpdateTime = positionUpdateTime,
                positionSpeed = positionSpeed
            )


            Log.d(
                "LYRIX_MEDIA",
                "Song: $title | " +
                        "Artist: $artist | " +
                        "Playing: $playing | " +
                        "Position: ${position}ms"
            )

        } catch (e: Exception) {

            Log.e(
                "LYRIX_MEDIA",
                "Media session error",
                e
            )
        }
    }


    private fun saveSong(
        title: String,
        artist: String,
        playing: Boolean,
        position: Long,
        positionUpdateTime: Long,
        positionSpeed: Float
    ) {

        prefs.edit()

            .putString(
                "song",
                title
            )

            .putString(
                "artist",
                artist
            )

            .putBoolean(
                "playing",
                playing
            )

            // Kept for anything reading a plain snapshot (e.g. the
            // "Position: Xs" label) - live consumers should use
            // PlaybackPositionEstimator instead.
            .putLong(
                "position",
                position
            )

            .putLong(
                "positionBaseMs",
                position
            )

            .putLong(
                "positionBaseElapsedRealtime",
                positionUpdateTime
            )

            .putFloat(
                "positionSpeed",
                positionSpeed
            )

            .apply()
    }


    override fun onListenerDisconnected() {

        handler.removeCallbacks(
            mediaPoller
        )

        super.onListenerDisconnected()
    }


    override fun onDestroy() {

        handler.removeCallbacks(
            mediaPoller
        )

        super.onDestroy()
    }
}