package com.maukingdom.musicplayer.ui.v2.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maukingdom.musicplayer.data.SongEntity
import com.maukingdom.musicplayer.ui.MusicViewModel
import com.maukingdom.musicplayer.ui.v2.Modern
import com.maukingdom.musicplayer.ui.v2.ModernDimens
import com.maukingdom.musicplayer.ui.v2.ModernMotion
import com.maukingdom.musicplayer.ui.v2.components.ListRow
import com.maukingdom.musicplayer.ui.v2.components.SongRowSizes
import com.maukingdom.musicplayer.ui.v2.components.V2IconButton
import com.maukingdom.musicplayer.ui.v2.components.songCoverUri
import com.maukingdom.musicplayer.ui.v2.cover.LivingCover
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel
import com.maukingdom.musicplayer.ui.v2.library.PlaylistItem
import com.maukingdom.musicplayer.ui.v2.library.SongGroup
import com.maukingdom.musicplayer.ui.v2.library.UNKNOWN_ARTIST
import com.maukingdom.musicplayer.ui.v2.library.albumMetaText
import com.maukingdom.musicplayer.ui.v2.library.playlistMetaText
import com.maukingdom.musicplayer.ui.v2.modernTween
import com.maukingdom.musicplayer.ui.v2.playlist.rememberPlaylistWash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/** Pause after the last keystroke before searching. */
private const val DEBOUNCE_MS = 150L

private val FieldWhite = Color(0xFFFFFFFF)
private val FieldText = Color(0xFF1A1A1F)
private val FieldHint = Color(0xFF55555C)
private val FieldHeight = 46.dp
private val TileHeight = 96.dp
private val TileCover = 64.dp
private val TileGap = 12.dp

/**
 * Search tab: [query] is owned by the caller (saved there, so it survives
 * tab switches and rotation). When [focusOnOpen] is true the field takes
 * focus and the keyboard opens once, then [onFocusHandled] is called.
 */
@Composable
fun SearchRoute(
    musicViewModel: MusicViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    focusOnOpen: Boolean,
    onFocusHandled: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val counts by musicViewModel.playlistSongCounts.collectAsStateWithLifecycle()
    val folderIds by musicViewModel.folderLinkedPlaylistIds.collectAsStateWithLifecycle()
    val songs by musicViewModel.songs.collectAsStateWithLifecycle()

    val tiles = remember(playlists, counts, folderIds) { browseTiles(playlists, counts, folderIds) }

    // Debounced and computed off the main thread. produceState keeps the
    // previous results while the next ones are worked out, so typing never
    // flashes an empty list.
    val results by produceState<SearchContent>(SearchContent.Browse, query, playlists, counts, folderIds, songs) {
        if (query.isBlank()) {
            value = SearchContent.Browse
            return@produceState
        }
        delay(DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) {
            buildSearchResults(query, playlists, counts, folderIds, songs)
        }
    }

    SearchScreen(
        query = query,
        onQueryChange = onQueryChange,
        focusOnOpen = focusOnOpen,
        onFocusHandled = onFocusHandled,
        content = if (query.isBlank()) SearchContent.Browse else results,
        tiles = tiles,
        onOpenPlaylist = onOpenPlaylist,
        onPlaySongs = musicViewModel::playSongs,
        contentPadding = contentPadding,
        modifier = modifier
    )
}

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    focusOnOpen: Boolean,
    onFocusHandled: () -> Unit,
    content: SearchContent,
    tiles: List<BrowseTile>,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySongs: (List<SongEntity>, Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(focusOnOpen) {
        if (focusOnOpen) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusHandled()
        }
    }

    // The list ends clear of the mini player and navigation; imePadding
    // (applied in layout) keeps results above the keyboard.
    val listPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Search",
            style = Modern.type.screenTitle,
            color = Modern.colors.textPrimary,
            modifier = Modifier
                .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, top = 12.dp)
                .heightIn(min = 44.dp)
                .semantics { heading() }
        )

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = ::hideKeyboard,
            onClear = {
                onQueryChange("")
                focusRequester.requestFocus()
            },
            modifier = Modifier
                .padding(horizontal = ModernDimens.screenPadding, vertical = 8.dp)
                .focusRequester(focusRequester)
        )

        val listModifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .imePadding()

        when (content) {
            SearchContent.Browse -> {
                val gridState = rememberLazyGridState()
                HideKeyboardOnScroll(gridState, ::hideKeyboard)
                BrowseGrid(
                    tiles = tiles,
                    onOpenPlaylist = onOpenPlaylist,
                    contentPadding = listPadding,
                    state = gridState,
                    modifier = listModifier
                )
            }

            is SearchContent.Results -> {
                val listState = rememberLazyListState()
                HideKeyboardOnScroll(listState, ::hideKeyboard)
                LazyColumn(state = listState, modifier = listModifier, contentPadding = listPadding) {
                    if (content.isEmpty) {
                        item(key = "none", contentType = "none") { NoResults(content.query) }
                    } else {
                        resultSections(content, onOpenPlaylist, onPlaySongs)
                    }
                }
            }
        }
    }
}

/** Scrolling the results or tiles puts the keyboard away. Observed outside composition. */
@Composable
private fun HideKeyboardOnScroll(state: ScrollableState, hide: () -> Unit) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .filter { it }
            .collect { hide() }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val type = Modern.type
    val textStyle = remember(type) { type.rowTitle.copy(color = FieldText) }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(FieldText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ModernDimens.touchEffective),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.heightIn(min = ModernDimens.touchEffective),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FieldHeight)
                        .clip(RoundedCornerShape(ModernDimens.miniPlayer))
                        .drawBehind { drawRect(FieldWhite) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = FieldText,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Songs, artists, playlists",
                                style = textStyle,
                                color = FieldHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        V2IconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = "Clear search",
                            onClick = onClear,
                            tint = FieldText
                        )
                    } else {
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }
    )
}

@Composable
private fun BrowseGrid(
    tiles: List<BrowseTile>,
    onOpenPlaylist: (Long) -> Unit,
    contentPadding: PaddingValues,
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = ModernDimens.screenPadding,
            end = ModernDimens.screenPadding,
            bottom = contentPadding.calculateBottomPadding()
        ),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalArrangement = Arrangement.spacedBy(TileGap)
    ) {
        item(key = "browse-title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
            Text(
                text = "Browse your folders",
                style = rememberSectionStyle(),
                color = Modern.colors.textPrimary,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .semantics { heading() }
            )
        }

        if (tiles.isEmpty()) {
            item(key = "browse-empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                Text(
                    text = "Your SD-card folders and playlists will show up here.",
                    style = Modern.type.rowSubtitle,
                    color = Modern.colors.textSecondary
                )
            }
        }

        items(tiles, key = { it.playlistId }, contentType = { "tile" }) { tile ->
            BrowseTileView(tile = tile, onOpen = { onOpenPlaylist(tile.playlistId) })
        }
    }
}

/** 16 sp / 700 section title in Inter. */
@Composable
private fun rememberSectionStyle() = Modern.type.sectionTitle.let {
    remember(it) { it.copy(fontSize = 16.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp) }
}

@Composable
private fun BrowseTileView(tile: BrowseTile, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Modern.colors
    val bg = colors.bg.toArgb()
    val wash = rememberPlaylistWash(tile.playlistId, tile.name)
    val target = remember(wash, tile.name, bg) {
        Color(wash?.let { tileColorFromWash(it.toArgb(), bg) } ?: tileFallbackColor(tile.name))
    }
    val fill = animateColorAsState(target, modernTween(ModernMotion.COLOR_MS), label = "tileColour")
    val nameStyle = Modern.type.screenTitle.let {
        remember(it) {
            it.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight(800),
                letterSpacing = 0.sp,
                // Keeps the name readable where it meets the cover.
                shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 6f)
            )
        }
    }
    val coverShape = RoundedCornerShape(ModernDimens.coverSmall)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TileHeight)
            .clip(RoundedCornerShape(ModernDimens.cardCover))
            .drawBehind { drawRect(fill.value) }
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen)
    ) {
        // Peeks from the bottom-right corner, tilted; the tile clips it. Drawn
        // first, so the name stays on top where a long name reaches it.
        LivingCover(
            playlistId = tile.playlistId,
            playlistName = tile.name,
            size = TileCover,
            corner = ModernDimens.coverSmall,
            animate = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 8.dp)
                .graphicsLayer {
                    rotationZ = 25f
                    shadowElevation = 8.dp.toPx()
                    shape = coverShape
                    clip = true
                }
        )

        Text(
            text = tile.name,
            style = nameStyle,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 40.dp)
        )
    }
}

private fun LazyListScope.resultSections(
    content: SearchContent.Results,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySongs: (List<SongEntity>, Int) -> Unit
) {
    if (content.playlists.isNotEmpty()) {
        sectionHeader("Playlists")
        items(content.playlists, key = { "pl:${it.id}" }, contentType = { "playlist" }) { item ->
            PlaylistResult(item = item, onOpen = { onOpenPlaylist(item.id) })
        }
    }

    if (content.songs.isNotEmpty()) {
        sectionHeader("Songs")
        itemsIndexed(content.songs, key = { _, song -> "s:${song.id}" }, contentType = { _, _ -> "song" }) { index, song ->
            ListRow(
                coverUri = songCoverUri(song.id),
                title = song.title,
                subtitle = song.artist,
                onClick = { onPlaySongs(content.songs, index) },
                rowHeight = SongRowSizes.height,
                coverSize = SongRowSizes.cover,
                onClickLabel = "Play"
            )
        }
    }

    if (content.artists.isNotEmpty()) {
        sectionHeader("Artists")
        items(content.artists, key = { "ar:${it.key}" }, contentType = { "group" }) { group ->
            GroupResult(group = group, album = false, onPlay = { onPlaySongs(group.songs, 0) })
        }
    }

    if (content.albums.isNotEmpty()) {
        sectionHeader("Albums")
        items(content.albums, key = { "al:${it.key}" }, contentType = { "group" }) { group ->
            GroupResult(group = group, album = true, onPlay = { onPlaySongs(group.songs, 0) })
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "h:$title", contentType = "header") {
        Text(
            text = title,
            style = rememberSectionStyle(),
            color = Modern.colors.textPrimary,
            modifier = Modifier
                .padding(start = ModernDimens.screenPadding, end = ModernDimens.screenPadding, top = 16.dp, bottom = 4.dp)
                .semantics { heading() }
        )
    }
}

@Composable
private fun PlaylistResult(item: PlaylistItem, onOpen: () -> Unit) {
    ListRow(
        // Still, like Library rows: first song picture with the era look.
        cover = {
            LivingCover(
                playlistId = item.id,
                playlistName = item.name,
                size = ModernDimens.listRowCover,
                corner = ModernDimens.coverSmall,
                animate = false
            )
        },
        title = item.name,
        subtitle = playlistMetaText(item.isFolder, item.songCount),
        onClick = onOpen,
        titleStyle = Modern.type.playlistTitle,
        onClickLabel = "Open"
    )
}

@Composable
private fun GroupResult(group: SongGroup, album: Boolean, onPlay: () -> Unit) {
    ListRow(
        coverUri = songCoverUri(group.coverSongId),
        title = group.name,
        subtitle = if (album) albumMetaText(group.artist ?: UNKNOWN_ARTIST, group.songs.size) else songCountLabel(group.songs.size),
        onClick = onPlay,
        titleStyle = Modern.type.playlistTitle,
        // Like Library: artists round, albums square.
        coverCorner = if (album) ModernDimens.coverSmall else ModernDimens.listRowCover / 2,
        onClickLabel = "Play"
    )
}

@Composable
private fun NoResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No results for “$query”",
            style = rememberSectionStyle(),
            color = Modern.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Check the spelling or try a shorter search.",
            style = Modern.type.rowSubtitle,
            color = Modern.colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
