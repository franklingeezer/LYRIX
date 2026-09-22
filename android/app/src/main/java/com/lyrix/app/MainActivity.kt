package com.lyrix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyrix.app.ui.theme.LYRIXTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContent {

            LYRIXTheme {

                LyrixScreen()
            }
        }
    }
}


@Composable
fun LyrixScreen() {

    val context =
        LocalContext.current


    // ------------------------------------------------
    // CURRENT SONG
    // ------------------------------------------------

    var song by remember {

        mutableStateOf(
            "No music detected"
        )
    }


    var artist by remember {

        mutableStateOf(
            "—"
        )
    }


    var playing by remember {

        mutableStateOf(
            false
        )
    }


    var position by remember {

        mutableStateOf(
            0L
        )
    }


    // ------------------------------------------------
    // LYRICS STATUS
    // ------------------------------------------------

    var lyricsStatus by remember {

        mutableStateOf(
            "Waiting for music..."
        )
    }


    // ------------------------------------------------
    // ESP32 STATUS
    // ------------------------------------------------

    var esp32Status by remember {

        mutableStateOf(
            "ESP32: Checking..."
        )
    }


    // ------------------------------------------------
    // PREVENT RE-SENDING SAME SONG
    // ------------------------------------------------

    var lastSongKey by remember {

        mutableStateOf(
            ""
        )
    }


    // ------------------------------------------------
    // PARSED LYRICS (for phone-side Bangla bitmap push)
    // ------------------------------------------------

    var lyrics by remember {

        mutableStateOf<List<LyricLine>>(
            emptyList()
        )
    }


    // ------------------------------------------------
    // READ MEDIA INFORMATION
    // ------------------------------------------------

    LaunchedEffect(Unit) {

        while (true) {

            val prefs =
                context.getSharedPreferences(
                    "lyrix",
                    Context.MODE_PRIVATE
                )


            song =
                prefs.getString(
                    "song",
                    "No music detected"
                )
                    ?: "No music detected"


            artist =
                prefs.getString(
                    "artist",
                    "—"
                )
                    ?: "—"


            playing =
                prefs.getBoolean(
                    "playing",
                    false
                )


            position =
                PlaybackPositionEstimator.estimate(
                    prefs
                )


            delay(500)
        }
    }


    // ------------------------------------------------
    // CHECK ESP32
    // ------------------------------------------------

    LaunchedEffect(Unit) {

        while (true) {

            val online =
                Esp32Repository.isOnline()


            esp32Status =
                if (online) {

                    "ESP32: ONLINE"

                } else {

                    "ESP32: OFFLINE"
                }


            delay(5000)
        }
    }


    // ------------------------------------------------
    // SEND PLAYBACK POSITION
    // ------------------------------------------------

    LaunchedEffect(Unit) {

        while (true) {

            val prefs =
                context.getSharedPreferences(
                    "lyrix",
                    Context.MODE_PRIVATE
                )


            val currentPosition =
                PlaybackPositionEstimator.estimate(
                    prefs
                )


            val currentPlaying =
                prefs.getBoolean(
                    "playing",
                    false
                )


            position =
                currentPosition


            // Send current position to ESP32
            Esp32Repository.sendPosition(
                positionMs = currentPosition,
                playing = currentPlaying
            )


            delay(1000)
        }
    }


    // ------------------------------------------------
    // LYRIC LINE SCHEDULER
    // ------------------------------------------------
    //
    // Instead of polling on a fixed tick and pushing whatever line
    // happens to be current at that moment (which is always a bit
    // behind), this computes the live extrapolated position, finds
    // the exact timestamp of the NEXT line, and sleeps precisely
    // until then - so the push fires right as the song reaches it,
    // not up to a whole poll-interval late.
    //
    // Keyed on `lyrics` so it automatically resets (new coroutine,
    // fresh currentIndex) whenever a new song's lyrics come in.

    LaunchedEffect(lyrics) {

        if (lyrics.isEmpty()) {
            return@LaunchedEffect
        }

        var currentIndex = -1

        while (true) {

            val prefs =
                context.getSharedPreferences(
                    "lyrix",
                    Context.MODE_PRIVATE
                )

            val now =
                PlaybackPositionEstimator.estimate(
                    prefs
                )

            val playingNow =
                prefs.getBoolean(
                    "playing",
                    false
                )

            val newIndex =
                lyrics.indexOfLast {
                    it.timeMs <= now
                }

            if (
                newIndex != currentIndex &&
                newIndex >= 0
            ) {

                currentIndex = newIndex

                val lineText =
                    lyrics[currentIndex].text

                if (BanglaTextRenderer.isBangla(lineText)) {

                    val hex =
                        BanglaTextRenderer.renderToHex(
                            lineText
                        )

                    Esp32Repository.sendBitmap(hex)

                } else {

                    Esp32Repository.sendDisplayText(
                        lineText
                    )
                }
            }

            val nextTimeMs =
                lyrics.getOrNull(currentIndex + 1)
                    ?.timeMs

            // Sleep right up until the next line's timestamp so the
            // push happens exactly on time - clamped so we still
            // notice pauses/seeks reasonably quickly, and never sleep
            // a negative/zero amount if we're already past due.
            val sleepMs =
                if (playingNow && nextTimeMs != null) {

                    (nextTimeMs - now).coerceIn(
                        20L,
                        400L
                    )

                } else {

                    250L
                }

            delay(sleepMs)
        }
    }


    // ------------------------------------------------
    // DETECT NEW SONG + DOWNLOAD LYRICS
    // ------------------------------------------------

    LaunchedEffect(
        song,
        artist
    ) {

        if (
            song == "No music detected" ||
            song.isBlank()
        ) {

            lyricsStatus =
                "Waiting for music..."

            return@LaunchedEffect
        }


        val songKey =
            "$song|$artist"


        // Same song → don't download again
        if (
            songKey == lastSongKey
        ) {

            return@LaunchedEffect
        }


        lastSongKey =
            songKey


        lyricsStatus =
            "Searching for lyrics..."


        // --------------------------------------------
        // CLEAN YOUTUBE METADATA
        // --------------------------------------------

        val cleanMetadata =
            MusicMetadataParser.parse(
                rawTitle = song,
                rawArtist = artist
            )


        // --------------------------------------------
        // SEARCH LRCLIB
        // --------------------------------------------

        val result =
            LyricsRepository.getLyrics(
                trackName =
                    cleanMetadata.title,

                artistName =
                    cleanMetadata.artist
            )


        // --------------------------------------------
        // RESULT
        // --------------------------------------------

        if (result == null) {

            lyricsStatus =
                "Lyrics not found"

            return@LaunchedEffect
        }


        val syncedLyrics =
            result.syncedLyrics


        if (
            syncedLyrics.isNullOrBlank()
        ) {

            lyricsStatus =
                "Lyrics found • No sync data"

            return@LaunchedEffect
        }


        // --------------------------------------------
        // PARSE LOCALLY TOO
        // --------------------------------------------
        //
        // Needed so the lyric line scheduler (below) can compute
        // exact timestamps and spot Bangla lines to render itself -
        // the ESP32 still gets the raw LRC as a fallback copy.

        lyrics =
            LrcParser.parse(syncedLyrics)


        // --------------------------------------------
        // SEND LRC TO ESP32
        // --------------------------------------------

        lyricsStatus =
            "Sending lyrics to ESP32..."


        val sent =
            Esp32Repository.sendLyrics(
                syncedLyrics
            )


        lyricsStatus =
            if (sent) {

                "SYNCED LYRICS → ESP32"

            } else {

                "Lyrics found • ESP32 offline"
            }
    }


    // ------------------------------------------------
    // UI
    // ------------------------------------------------

    Surface(

        modifier =
            Modifier.fillMaxSize(),

        color =
            Color(0xFF0F0F0F)
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {


            // ----------------------------------------
            // LOGO
            // ----------------------------------------

            Text(

                text = "LYRIX",

                fontSize = 42.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color.White
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Text(

                text =
                    "AUTOMATIC LYRICS DISPLAY",

                fontSize = 12.sp,

                letterSpacing = 2.sp,

                color =
                    Color.Gray
            )


            Spacer(
                modifier =
                    Modifier.height(40.dp)
            )


            // ----------------------------------------
            // NOW PLAYING CARD
            // ----------------------------------------

            Column(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(

                            color =
                                Color(0xFF1E1E1E),

                            shape =
                                RoundedCornerShape(
                                    20.dp
                                )
                        )
                        .padding(24.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {


                Text(

                    text =
                        "NOW PLAYING",

                    fontSize = 12.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color.Gray
                )


                Spacer(
                    modifier =
                        Modifier.height(20.dp)
                )


                Text(

                    text =
                        song,

                    fontSize = 22.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color.White
                )


                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Text(

                    text =
                        artist,

                    fontSize = 16.sp,

                    color =
                        Color.Gray
                )


                Spacer(
                    modifier =
                        Modifier.height(20.dp)
                )


                Text(

                    text =
                        if (playing) {

                            "▶ PLAYING"

                        } else {

                            "⏸ PAUSED"
                        },

                    fontSize = 13.sp,

                    color =
                        if (playing) {

                            Color.White

                        } else {

                            Color.Gray
                        }
                )


                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )


                // ------------------------------------
                // POSITION
                // ------------------------------------

                Text(

                    text =
                        "Position: ${
                            position / 1000
                        }s",

                    fontSize = 12.sp,

                    color =
                        Color.Gray
                )


                Spacer(
                    modifier =
                        Modifier.height(18.dp)
                )


                // ------------------------------------
                // LYRICS STATUS
                // ------------------------------------

                Text(

                    text =
                        lyricsStatus,

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color.LightGray
                )


                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )


                // ------------------------------------
                // ESP32 STATUS
                // ------------------------------------

                Text(

                    text =
                        esp32Status,

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        if (
                            esp32Status ==
                            "ESP32: ONLINE"
                        ) {

                            Color.White

                        } else {

                            Color.Gray
                        }
                )
            }


            Spacer(
                modifier =
                    Modifier.height(30.dp)
            )


            // ----------------------------------------
            // MUSIC ACCESS
            // ----------------------------------------

            Button(

                onClick = {

                    context.startActivity(

                        Intent(

                            Settings
                                .ACTION_NOTIFICATION_LISTENER_SETTINGS
                        )
                    )
                }
            ) {

                Text(
                    text =
                        "OPEN MUSIC ACCESS SETTINGS"
                )
            }
        }
    }
}