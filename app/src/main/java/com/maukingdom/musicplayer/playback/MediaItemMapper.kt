package com.maukingdom.musicplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.maukingdom.musicplayer.data.SongEntity

/**
 * Converts a Room song record to a Media3 item.
 *
 * Artwork bytes may be supplied by the caller after reading embedded artwork
 * off the main thread. Every item also gets an [ArtworkProvider] URI, which
 * other apps (Android Auto, the system media controls) can read.
 */
object MediaItemMapper {

    fun fromSong(
        song: SongEntity,
        embeddedArtwork: ByteArray? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsPlayable(true)

        if (embeddedArtwork != null && embeddedArtwork.isNotEmpty()) {
            metadata.setArtworkData(
                embeddedArtwork,
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
        }

        metadata.setArtworkUri(Uri.parse(ArtworkPaths.songUri(song.id)))

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(song.uri))
            .setMediaMetadata(metadata.build())
            .build()
    }
}
