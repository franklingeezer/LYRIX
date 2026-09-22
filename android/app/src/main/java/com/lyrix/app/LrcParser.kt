package com.lyrix.app

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object LrcParser {

    fun parse(lrc: String): List<LyricLine> {

        val result = mutableListOf<LyricLine>()

        val lines =
            lrc.lines()

        for (line in lines) {

            val regex =
                Regex(
                    """\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)"""
                )

            val match =
                regex.find(line)
                    ?: continue

            val minutes =
                match.groupValues[1]
                    .toLongOrNull()
                    ?: continue

            val seconds =
                match.groupValues[2]
                    .toLongOrNull()
                    ?: continue

            val fraction =
                match.groupValues[3]

            val milliseconds =
                when (fraction.length) {

                    1 ->
                        fraction
                            .toLong()
                            .times(100)

                    2 ->
                        fraction
                            .toLong()
                            .times(10)

                    3 ->
                        fraction
                            .toLong()

                    else ->
                        0L
                }

            val timeMs =
                minutes * 60_000L +
                        seconds * 1_000L +
                        milliseconds

            val text =
                match.groupValues[4]
                    .trim()

            if (text.isNotBlank()) {

                result.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = text
                    )
                )
            }
        }

        return result
            .sortedBy {
                it.timeMs
            }
    }

    fun currentLine(
        lyrics: List<LyricLine>,
        positionMs: Long
    ): LyricLine? {

        if (lyrics.isEmpty()) {
            return null
        }

        return lyrics
            .lastOrNull {
                it.timeMs <= positionMs
            }
    }

    fun nextLine(
        lyrics: List<LyricLine>,
        positionMs: Long
    ): LyricLine? {

        return lyrics
            .firstOrNull {
                it.timeMs > positionMs
            }
    }
}