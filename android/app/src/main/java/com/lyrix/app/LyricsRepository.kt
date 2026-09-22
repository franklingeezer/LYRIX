package com.lyrix.app

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LyricsResult(
    val trackName: String,
    val artistName: String,
    val duration: Double,
    val syncedLyrics: String?
)

object LyricsRepository {

    suspend fun getLyrics(
        trackName: String,
        artistName: String
    ): LyricsResult? = withContext(Dispatchers.IO) {

        var connection: HttpURLConnection? = null

        try {

            Log.d(
                "LYRIX_LYRICS",
                "Searching: $trackName - $artistName"
            )

            val url =
                Uri.parse(
                    "https://lrclib.net/api/get"
                )
                    .buildUpon()
                    .appendQueryParameter(
                        "track_name",
                        trackName
                    )
                    .appendQueryParameter(
                        "artist_name",
                        artistName
                    )
                    .build()
                    .toString()

            Log.d(
                "LYRIX_LYRICS",
                "URL: $url"
            )

            connection =
                URL(url)
                    .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "User-Agent",
                "LYRIX/0.1 Android"
            )

            connection.connectTimeout =
                4000

            connection.readTimeout =
                4000

            val responseCode =
                connection.responseCode

            Log.d(
                "LYRIX_LYRICS",
                "HTTP response: $responseCode"
            )

            if (responseCode != 200) {

                return@withContext null
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            Log.d(
                "LYRIX_LYRICS",
                "Lyrics response received"
            )

            val json =
                JSONObject(response)

            val synced =
                json.optString(
                    "syncedLyrics",
                    ""
                )
                    .takeIf {
                        it.isNotBlank()
                    }

            LyricsResult(

                trackName =
                    json.optString(
                        "trackName",
                        trackName
                    ),

                artistName =
                    json.optString(
                        "artistName",
                        artistName
                    ),

                duration =
                    json.optDouble(
                        "duration",
                        0.0
                    ),

                syncedLyrics =
                    synced
            )

        } catch (e: Exception) {

            Log.e(
                "LYRIX_LYRICS",
                "Lyrics request failed: ${e.message}",
                e
            )

            null

        } finally {

            connection?.disconnect()
        }
    }
}