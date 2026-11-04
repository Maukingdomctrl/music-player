package com.maukingdom.musicplayer.ui.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.components.CoverImage
import com.maukingdom.musicplayer.ui.v2.components.SectionHeader
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton
import com.maukingdom.musicplayer.ui.v2.components.playlistCoverUri
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import java.util.Calendar
import kotlinx.coroutines.delay

/** Height of the skin-tinted wash behind the top of Home. */
val HomeWashHeight = 290.dp

/**
 * The warm wash behind the greeting: the skin accent mixed into the
 * background, fading to the background. Drawn by ModernRoot behind the
 * insets, so it also sits under the status bar.
 */
@Composable
fun HomeHeaderWash(modifier: Modifier = Modifier) {
    val colors = Modern.colors
    val brush = remember(colors.accent, colors.bg) {
        Brush.verticalGradient(
            0f to lerp(colors.bg, colors.accent, 0.14f),
            1f to colors.bg
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HomeWashHeight)
            .background(brush)
    )
}

/**
 * Home tab: collects library data from [musicViewModel] and shows
 * [HomeScreen]. Tiles and folder cards play the playlist for now; they
 * will open the Playlist screen once it exists (UI v2 Step 5).
 */
@Composable
fun HomeRoute(
    musicViewModel: MusicViewModel,
    onSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val counts by musicViewModel.playlistSongCounts.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()
    val songs by musicViewModel.songs.collectAsStateWithLifecycle()

    val content = remember(playlists, counts, folderIds, songs) {
        buildHomeContent(playlists, counts, folderIds, songs)
    }
    val greeting = rememberGreeting()

    HomeScreen(
        content = content,
        greeting = greeting,
        onSettings = onSettings,
        onPlayPlaylist = musicViewModel::playPlaylist,
        onPlayRecent = { index -> musicViewModel.playSongs(content.recentlyAdded, index) },
        onOpenLibrary = onOpenLibrary,
        modifier = modifier
    )
}

/** The greeting for the current hour, refreshed when the hour changes. */
@Composable
private fun rememberGreeting(): String {
    val hour by produceState(initialValue = currentHour()) {
        while (true) {
            val now = Calendar.getInstance()
            val msIntoHour = now.get(Calendar.MINUTE) * 60_000L +
                now.get(Calendar.SECOND) * 1_000L +
                now.get(Calendar.MILLISECOND)
            delay(60 * 60_000L - msIntoHour + 500L)
            value = currentHour()
        }
    }
    return greetingFor(hour)
}

private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

@Composable
fun HomeScreen(
    content: HomeContent,
    greeting: String,
    onSettings: () -> Unit,
    onPlayPlaylist: (Long) -> Unit,
    onPlayRecent: (Int) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ModernDimens.sectionGap)
    ) {
        item(key = "header", contentType = "header") {
            HomeHeader(greeting = greeting, onSettings = onSettings)
        }

        if (content.isEmpty) {
            item(key = "empty", contentType = "empty") {
                EmptyHome(onOpenLibrary = onOpenLibrary)
            }
            return@LazyColumn
        }

        if (content.quickTiles.isNotEmpty()) {
            item(key = "quick", contentType = "quick") {
                QuickTileGrid(tiles = content.quickTiles, onPlay = onPlayPlaylist)
            }
        }

        if (content.folders.isNotEmpty()) {
            item(key = "folders-title", contentType = "section") {
                HomeSection(title = "Your SD-card folders")
            }
            item(key = "folders", contentType = "folders") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ModernDimens.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(ModernDimens.itemGapLarge)
                ) {
                    items(content.folders, key = { it.playlistId }) { folder ->
                        FolderCardView(folder = folder, onPlay = { onPlayPlaylist(folder.playlistId) })
                    }
                }
            }
        }

        if (content.recentlyAdded.isNotEmpty()) {
            item(key = "recent-title", contentType = "section") {
                HomeSection(title = "Recently added")
            }
            item(key = "recent", contentType = "recent") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ModernDimens.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(ModernDimens.itemGapLarge)
                ) {
                    itemsIndexed(content.recentlyAdded, key = { _, song -> song.id }) { index, song ->
                        RecentCard(song = song, onPlay = { onPlayRecent(index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(greeting: String, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = ModernDimens.screenPadding, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = greeting,
            style = Modern.type.screenTitle,
            color = Modern.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )

        V2IconButton(
            icon = Icons.Outlined.Settings,
            contentDescription = "Settings",
            onClick = onSettings
        )
    }
}

@Composable
private fun HomeSection(title: String) {
    SectionHeader(
        title = title,
        modifier = Modifier.padding(
            start = ModernDimens.screenPadding,
            end = ModernDimens.screenPadding,
            top = 18.dp,
            bottom = 4.dp
        )
    )
}

/** Two columns of 56 dp tiles, 8 dp apart. */
@Composable
private fun QuickTileGrid(tiles: List<QuickTile>, onPlay: (Long) -> Unit) {
    Column(
        modifier = Modifier.padding(
            start = ModernDimens.screenPadding,
            end = ModernDimens.screenPadding,
            top = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    QuickTileView(
                        tile = tile,
                        onPlay = { onPlay(tile.playlistId) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val QuickTileFill = Color.White.copy(alpha = 0.08f)

@Composable
private fun QuickTileView(tile: QuickTile, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = ModernDimens.quickTileHeight)
            .clip(RoundedCornerShape(ModernDimens.coverMedium))
            .background(QuickTileFill)
            .clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            uri = playlistCoverUri(tile.playlistId),
            size = ModernDimens.quickTileHeight,
            corner = 0.dp
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = tile.name,
            style = Modern.type.tileTitle,
            color = Modern.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
    }
}

@Composable
private fun FolderCardView(folder: FolderCard, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(ModernDimens.cardCover)

    Column(
        modifier = Modifier
            .width(ModernDimens.folderCardCover)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay)
            .padding(bottom = 4.dp)
    ) {
        CoverImage(
            uri = playlistCoverUri(folder.playlistId),
            size = ModernDimens.folderCardCover,
            corner = ModernDimens.cardCover,
            modifier = Modifier.shadow(elevation = 10.dp, shape = shape, clip = false)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = folder.name,
            style = Modern.type.cardTitle,
            color = Modern.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = songCountLabel(folder.songCount),
            style = Modern.type.caption,
            color = Modern.colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

private val RecentCardSize = 120.dp

@Composable
private fun RecentCard(song: SongEntity, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .width(RecentCardSize)
            .clip(RoundedCornerShape(ModernDimens.cardCover))
            .clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay)
            .padding(bottom = 4.dp)
    ) {
        CoverImage(
            uri = songCoverUri(song.id),
            size = RecentCardSize,
            corner = ModernDimens.cardCover
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = song.title,
            style = Modern.type.miniTitle,
            color = Modern.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = song.artist,
            style = Modern.type.caption,
            color = Modern.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun EmptyHome(onOpenLibrary: () -> Unit) {
    val colors = Modern.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Your music will show up here",
            style = Modern.type.sectionTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Import an SD-card folder in Your Library to get started.",
            style = Modern.type.rowSubtitle,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .heightIn(min = ModernDimens.touchEffective)
                .clip(RoundedCornerShape(ModernDimens.touchEffective / 2))
                .background(colors.chipSelected)
                .clickable(role = Role.Button, onClick = onOpenLibrary)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Go to Your Library",
                style = Modern.type.cardTitle,
                color = colors.chipSelectedText
            )
        }
    }
}
