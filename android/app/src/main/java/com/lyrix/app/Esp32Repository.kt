package com.lyrix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Esp32Repository {

    // Your ESP32's CURRENT IP address.
    // Change this if the ESP32 gets a different IP.
    private const val ESP32_IP = "10.224.45.70"

    private const val BASE_URL = "http://$ESP32_IP"

    /**
     * Send synchronized LRC lyrics to ESP32.
     */
    suspend fun sendLyrics(lrc: String): Boolean =
        withContext(Dispatchers.IO) {

            try {

                val url =
                    URL("$BASE_URL/lyrics")

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "POST"

                connection.doOutput = true

                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.setRequestProperty(
                    "Content-Type",
                    "text/plain; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "User-Agent",
                    "LYRIX Android"
                )

                connection.outputStream.use { output ->

                    output.write(
                        lrc.toByteArray(Charsets.UTF_8)
                    )
                }

                val success =
                    connection.responseCode in 200..299

                connection.disconnect()

                success

            } catch (e: Exception) {

                e.printStackTrace()

                false
            }
        }


    /**
     * Push a single pre-rendered lyric line (as a hex-encoded
     * packed 1bpp bitmap - see BanglaTextRenderer) straight to
     * the OLED. Used for Bangla lines, where the ESP32 itself
     * can't shape the text correctly.
     */
    suspend fun sendBitmap(hex: String): Boolean =
        withContext(Dispatchers.IO) {

            try {

                val url =
                    URL("$BASE_URL/bitmap")

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "POST"

                connection.doOutput = true

                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.setRequestProperty(
                    "Content-Type",
                    "text/plain; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "User-Agent",
                    "LYRIX Android"
                )

                connection.outputStream.use { output ->

                    output.write(
                        hex.toByteArray(Charsets.US_ASCII)
                    )
                }

                val success =
                    connection.responseCode in 200..299

                connection.disconnect()

                success

            } catch (e: Exception) {

                e.printStackTrace()

                false
            }
        }


    /**
     * Push a single line of plain text to the ESP32's built-in
     * renderer. Used to explicitly switch the device back out of
     * bitmap mode once a Bangla line is followed by a plain-text
     * one.
     */
    suspend fun sendDisplayText(text: String): Boolean =
        withContext(Dispatchers.IO) {

            try {

                val encodedText =
                    URLEncoder.encode(text, "UTF-8")

                val url =
                    URL("$BASE_URL/display?text=$encodedText")

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                connection.setRequestProperty(
                    "User-Agent",
                    "LYRIX Android"
                )

                val success =
                    connection.responseCode in 200..299

                connection.disconnect()

                success

            } catch (e: Exception) {

                e.printStackTrace()

                false
            }
        }


    /**
     * Send current playback position to ESP32.
     *
     * Example:
     * /position?ms=52340&playing=1
     */
    suspend fun sendPosition(
        positionMs: Long,
        playing: Boolean
    ): Boolean =
        withContext(Dispatchers.IO) {

            try {

                val encodedPlaying =
                    if (playing) "1" else "0"

                val url =
                    URL(
                        "$BASE_URL/position" +
                                "?ms=$positionMs" +
                                "&playing=$encodedPlaying"
                    )

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                connection.setRequestProperty(
                    "User-Agent",
                    "LYRIX Android"
                )

                val success =
                    connection.responseCode in 200..299

                connection.disconnect()

                success

            } catch (e: Exception) {

                e.printStackTrace()

                false
            }
        }


    /**
     * Check whether ESP32 is reachable.
     */
    suspend fun isOnline(): Boolean =
        withContext(Dispatchers.IO) {

            try {

                val url =
                    URL("$BASE_URL/status")

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"

                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                val success =
                    connection.responseCode == 200

                connection.disconnect()

                success

            } catch (e: Exception) {

                false
            }
        }
}