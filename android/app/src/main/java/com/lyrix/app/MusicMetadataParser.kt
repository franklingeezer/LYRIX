package com.lyrix.app

data class CleanMusicMetadata(
    val title: String,
    val artist: String
)

object MusicMetadataParser {

    fun parse(
        rawTitle: String,
        rawArtist: String
    ): CleanMusicMetadata {

        var title = rawTitle.trim()
        var artist = rawArtist.trim()

        // If Android already provided a useful artist, keep it.
        if (
            artist.isNotBlank() &&
            !artist.equals(
                "Unknown Artist",
                ignoreCase = true
            )
        ) {
            return CleanMusicMetadata(
                title = title,
                artist = artist
            )
        }

        // Handle YouTube-style titles:
        // Song | Artist | Official Visualiser

        val parts = title
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (parts.size >= 2) {

            title = parts[0]

            artist = parts
                .drop(1)
                .firstOrNull {
                    !it.contains(
                        "official",
                        ignoreCase = true
                    ) &&
                            !it.contains(
                                "visualiser",
                                ignoreCase = true
                            ) &&
                            !it.contains(
                                "visualizer",
                                ignoreCase = true
                            )
                }
                ?: "Unknown Artist"
        }

        return CleanMusicMetadata(
            title = title,
            artist = artist
        )
    }
}