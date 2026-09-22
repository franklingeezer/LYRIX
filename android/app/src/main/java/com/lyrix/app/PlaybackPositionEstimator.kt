package com.lyrix.app

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Extrapolates the actual, *live* playback position from the last
 * snapshot LyrixNotificationListener recorded, instead of using
 * that snapshot directly.
 *
 * PlaybackState.position is only accurate as of
 * PlaybackState.lastPositionUpdateTime - by the time anything
 * reads it, real playback has moved on. Without this
 * extrapolation, every consumer is always behind by however long
 * it's been since the last MediaSession update, which is exactly
 * why lyric lines were changing late.
 */
object PlaybackPositionEstimator {

    fun estimate(prefs: SharedPreferences): Long {

        val baseMs =
            prefs.getLong(
                "positionBaseMs",
                0L
            )

        val baseElapsedRealtime =
            prefs.getLong(
                "positionBaseElapsedRealtime",
                0L
            )

        val speed =
            prefs.getFloat(
                "positionSpeed",
                1f
            )

        val playing =
            prefs.getBoolean(
                "playing",
                false
            )

        if (!playing || baseElapsedRealtime <= 0L) {
            return baseMs
        }

        val elapsedSinceUpdate =
            SystemClock.elapsedRealtime() - baseElapsedRealtime

        return (baseMs + (elapsedSinceUpdate * speed))
            .toLong()
            .coerceAtLeast(0L)
    }
}
