package dev.buyolitsez.b2player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Now playing, over the tabs. */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(ui: Ui, cat: Catalog) {
    val c = ui.controller
    val t = ui.currentId?.let { cat.byId[it] }
    // The controller connects a moment after the app comes back (from the notification, say); close only when nothing plays.
    if (c == null) return
    if (t == null) { LaunchedEffect(Unit) { ui.nowOpen = false }; return }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sleep by remember { mutableStateOf(false) }
    var lyricsOn by remember { mutableStateOf(false) }
    var buffered by remember { mutableLongStateOf(0L) }
    val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
    // A download or a track cached whole is loaded to the end; the player only buffers some way ahead of it.
    val whole = remember(t.id, ui.version) { ui.library.isDownloaded(t) || ui.library.isCached(t) }
    LaunchedEffect(c, whole) { while (true) { pos = c.currentPosition; buffered = if (whole) dur else c.bufferedPosition; delay(500) } }
    val accent = MaterialTheme.colorScheme.primary
    val white = Color.White
    val openMenu = {
        ui.trackMenu = Ui.TrackMenuRequest(t, null) { close ->
            MenuItem("Sleep timer", Icons.Default.Bedtime, close) { sleep = true }
            MenuItem("Equalizer", Icons.Default.Equalizer, close) { ui.open(Screen.EqualizerView) }
        }
    }
    // Title, buttons, seek bar; [fill] pushes "Next" to the bottom in portrait.
    @Composable
    fun ColumnScope.details(fill: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Color(0xFFC9C9C9),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { ui.open(Screen.ArtistView(t.album.artist), Tab.Library) })
            }
            // Shifted right so the heart lines up with the cover's edge, not the button's.
            SmallIcon(if (ui.liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, white, 24.dp, Modifier.offset(x = 10.dp)) { ui.toggleLike(t) }
        }
        // The line is always there, so the controls don't jump when a timer is set.
        val label = sleepLabel(ui)
        Text(label ?: "", Modifier.padding(top = 2.dp).then(if (label != null) Modifier.clickable { sleep = true } else Modifier),
            style = MaterialTheme.typography.bodySmall, color = accent)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { c.seekTo((it * dur).toLong()) }; dragging = null },
            modifier = Modifier.height(20.dp),
            thumb = { Box(Modifier.size(12.dp).background(white, CircleShape)) },
            track = { st ->
                Box(contentAlignment = Alignment.CenterStart) {
                    SliderDefaults.Track(
                        st, Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                        colors = SliderDefaults.colors(activeTrackColor = white, inactiveTrackColor = white.copy(alpha = 0.22f)),
                    )
                    // How much is loaded, grey over the rest of the track.
                    Box(Modifier.fillMaxWidth((buffered.toFloat() / dur).coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(white.copy(alpha = 0.35f)))
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val at = (dragging?.let { it * dur } ?: pos.toFloat()) / 1000.0
            Text(formatTime(at), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Small steps for long tracks and concerts.
                SmallIcon(Icons.Default.Replay10, Color(0xFFC9C9C9), 22.dp, Modifier.size(36.dp)) { pos = (c.currentPosition - 10_000).coerceAtLeast(0); c.seekTo(pos) }
                // Lite streaming for a slow network; lit when on.
                val lite = ui.prefs.liteStreaming.value
                Text("LITE", Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(if (lite) accent.copy(alpha = 0.22f) else white.copy(alpha = 0.08f))
                    .clickable { ui.toggleLite() }.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = if (lite) accent else Color(0xFF9A9A9A))
                SmallIcon(Icons.Default.Forward30, Color(0xFFC9C9C9), 22.dp, Modifier.size(36.dp)) { pos = (c.currentPosition + 30_000).coerceAtMost(dur); c.seekTo(pos) }
            }
            Text("-" + formatTime((t.duration - at).coerceAtLeast(0.0)), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(Icons.Default.Shuffle, if (ui.shuffle) accent else Color(0xFFC9C9C9), 24.dp) { c.shuffleModeEnabled = !ui.shuffle }
            SmallIcon(Icons.Default.SkipPrevious, white, 40.dp) { c.seekToPrevious() }
            Box(Modifier.size(76.dp).clip(CircleShape).background(white).clickable { if (ui.playing) c.pause() else c.resume() }, Alignment.Center) {
                Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp), tint = Color.Black)
            }
            SmallIcon(Icons.Default.SkipNext, white, 40.dp) { c.seekToNextMediaItem() }
            SmallIcon(
                if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                if (ui.repeat != Player.REPEAT_MODE_OFF) accent else Color(0xFFC9C9C9), 24.dp,
            ) {
                c.repeatMode = when (ui.repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        if (fill) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
        // One pill opens the queue and shows what plays next.
        val next = ui.nextId?.let { cat.byId[it] }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(white.copy(alpha = 0.10f))
                .clickable { ui.queueOpen = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp), tint = Color(0xFFE6E6E6))
            if (next != null) {
                Text("Next", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${next.title} · ${next.artist}", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE6E6E6), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text("Queue", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6E6E6))
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp), tint = Color(0xFFE6E6E6))
        }
    }
    // The cover; a tap turns it into the lyrics and back.
    @Composable
    fun art(modifier: Modifier) {
        val toggle = { lyricsOn = !lyricsOn }
        Box(modifier.shadow(24.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).clickable(remember { MutableInteractionSource() }, null, onClick = toggle)) {
            Crossfade(lyricsOn, label = "cover") { on ->
                if (on) LyricsPanel(ui, t, Modifier.fillMaxSize().background(Gray1), toggle)
                else Cover(ui, t.album.cover, null, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
            }
        }
    }
    val tint = if (ui.prefs.coverTint.value) ui.colors.background else Color.Transparent
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        val landscape = maxWidth > maxHeight
        // Landscape: the cover takes the height under the top row.
        val coverSize = maxHeight - 96.dp
        Box(Modifier.fillMaxSize().background(fade(tint, h * 1.15f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Close, the album it plays from, the track menu.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(Icons.Default.KeyboardArrowDown, white, 30.dp) { ui.nowOpen = false }
                Column(Modifier.weight(1f).clickable { ui.open(Screen.AlbumView(t.album), Tab.Library) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FROM THE ALBUM", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC9C9C9))
                    Text(t.album.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallIcon(Icons.Default.MoreVert, white, 22.dp) { openMenu() }
            }
            if (landscape) {
                // Landscape: cover on the left, everything else scrolls on the right.
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    art(Modifier.size(coverSize))
                    Column(Modifier.weight(1f).padding(start = 24.dp).verticalScroll(rememberScrollState())) { details(fill = false) }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(24.dp))
                    art(Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(32.dp))
                    details(fill = true)
                }
            }
        }
    }
    if (sleep) SleepTimerDialog(ui) { sleep = false }
}

/** Rounded mini player above the tabs: a tap opens Now playing, a swipe skips. */
@UnstableApi
@Composable
fun MiniPlayer(ui: Ui, cat: Catalog?) {
    val t = ui.currentId?.let { cat?.byId?.get(it) } ?: return
    val c = ui.controller
    var drag by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).height(56.dp)
            .shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(ui.colors.background)
            .pointerInput(c) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag < -120) c?.seekToNextMediaItem() else if (drag > 120) c?.seekToPreviousMediaItem()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, dx -> drag += dx }
            }
            .clickable { ui.nowOpen = true }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui, t.album.cover, 40.dp, shape = CircleShape)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
        }
        SmallIcon(if (ui.liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
            if (ui.liked(t)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, 22.dp) { ui.toggleLike(t) }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { c?.run { if (ui.playing) pause() else resume() } }, Alignment.Center) {
            if (c != null) MiniProgress(c, t)
            Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
        }
    }
}

// Ring around the play button; polls on its own so the rest doesn't recompose.
@Composable
private fun MiniProgress(c: Player, t: Track) {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(250) } }
    val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
    CircularProgressIndicator(
        { (pos.toFloat() / dur).coerceIn(0f, 1f) }, Modifier.size(38.dp), color = Color.White, strokeWidth = 2.5.dp,
        trackColor = Color.White.copy(alpha = 0.18f), gapSize = 0.dp,
    )
}

// ---------------- queue ----------------

/** The queue, over Now playing: what played, the current track, and what comes next. */
@UnstableApi
@Composable
fun QueueScreen(ui: Ui, cat: Catalog) {
    val ids = ui.queueIds
    val tracks = ids.map { cat.byId[it] }
    // Rows follow the play order; with shuffle on that is not the queue order.
    val order = ui.playOrder.takeIf { it.size == ids.size } ?: ids.indices.toList()
    val keys = stableKeys(ids)
    val indexOf = remember(keys) { keys.withIndex().associate { (i, k) -> k to i } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = indexOf[from.key] ?: return@rememberReorderableLazyListState
        val b = indexOf[to.key] ?: return@rememberReorderableLazyListState
        // A moved track plays after the track above it in the queue (see Ui.moveInQueue), so it goes
        // after the row it passes going down, or after the row above that one going up.
        val pb = order.indexOf(b)
        val above = if (pb > order.indexOf(a)) b else order.getOrNull(pb - 1)
        ui.moveInQueue(a, above?.let { if (a < it) it else it + 1 } ?: 0)
    }
    val at = order.indexOf(ui.currentIndex).coerceAtLeast(0)
    val saved = remember(ui.version) { ui.library.savedQueues }
    val left = order.drop(at).sumOf { tracks.getOrNull(it)?.duration ?: 0.0 }
    var showSaved by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    val fq = remember(q) { Query(q) }
    val played = if (fq.isEmpty()) order.take(at) else emptyList()
    val next = if (fq.isEmpty()) order.drop(at + 1) else order.filterBy(fq) { tracks.getOrNull(it)?.searchKey.orEmpty() }
    val current = tracks.getOrNull(ui.currentIndex)
    // Start at the current track, with what already played above it.
    LaunchedEffect(Unit) { if (played.isNotEmpty()) listState.scrollToItem(played.size + 1) }
    // Saved queues are at the top of the list, above what played.
    LaunchedEffect(showSaved) { if (showSaved) listState.animateScrollToItem(0) }
    val drag = fq.isEmpty()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        Box(Modifier.fillMaxSize().background(fade(if (ui.prefs.coverTint.value) ui.colors.background else Color.Transparent, h, 0.75f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(Icons.Default.KeyboardArrowDown, Color.White, 30.dp) { ui.queueOpen = false }
                Spacer(Modifier.weight(1f))
                SmallIcon(Icons.Default.Search, Color.White, 22.dp) { filter = !filter }
                Box {
                    SmallIcon(Icons.Default.MoreVert, Color.White, 22.dp) { menu = true }
                    DropdownMenu(menu, { menu = false }, containerColor = Gray2) {
                        // The player's shuffle mode, as in Now Playing; turning it off plays the queue in its own order.
                        DropdownMenuItem({ Text(if (ui.shuffle) "Unshuffle" else "Shuffle") },
                            { menu = false; ui.controller?.shuffleModeEnabled = !ui.shuffle }, leadingIcon = { Icon(Icons.Default.Shuffle, null) })
                        DropdownMenuItem({ Text("Save as playlist") }, { menu = false; ui.saveQueueAsPlaylist() }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
                        DropdownMenuItem({ Text("Clear") }, { menu = false; ui.clearQueue() }, leadingIcon = { Icon(Icons.Default.ClearAll, null) })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                if (ids.isNotEmpty()) Text("${at + 1} of ${ids.size} · ${formatTime(left)} left", Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LikeFilter.entries.forEach { f -> Pill(f.title, active = f == ui.queueFilter) { ui.setQueueFilter(f) } }
            }
            if (filter || q.isNotEmpty()) SearchBox(q, { q = it }, "Search")
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (showSaved) {
                    item { SectionTitle("Saved queues") }
                    if (saved.isEmpty()) item { Text("No saved queues", Modifier.padding(horizontal = 20.dp), color = Muted) }
                    itemsIndexed(saved) { i, s ->
                        ListRow(null, s.name, "${s.index + 1} / ${s.ids.size}", trailing = { SmallIcon(Icons.Default.Close) { ui.deleteSaved(i) } }) {
                            showSaved = false; ui.resumeSaved(i)
                        }
                    }
                }
                if (ids.isEmpty()) item { Text("The queue is empty. Play an album or a track.", Modifier.padding(20.dp), color = Muted) }
                val row: @Composable LazyItemScope.(Int) -> Unit = row@{ i ->
                    val t = tracks[i] ?: return@row
                    ReorderableItem(reorder, key = keys[i], enabled = drag) {
                        TrackRow(ui, t, showCover = true, onClick = { ui.controller?.run { seekTo(i, 0); resume() } }, queueIndex = i,
                            dragHandle = if (drag) ({ DragHandle(Modifier.draggableHandle()) }) else null,
                            dragModifier = if (drag) longPressDrag() else Modifier)
                    }
                }
                if (played.isNotEmpty()) item { SectionTitle("Played") }
                items(played, key = { keys[it] }) { row(it) }
                if (fq.isEmpty() && current != null) {
                    item(key = "now") {
                        Column {
                            SectionTitle("Now playing")
                            Row(
                                Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(ui, current.album.cover, 46.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(current.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(current.artist, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(formatTime(current.duration), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
                            }
                        }
                    }
                }
                if (next.isNotEmpty()) item { SectionTitle(if (fq.isEmpty()) "Next up" else "Found") }
                items(next, key = { keys[it] }) { row(it) }
            }
            Row(Modifier.padding(20.dp, 8.dp, 20.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable fun button(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean = false, onClick: () -> Unit) = Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(22.dp)).clickable(onClick = onClick),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
                button("Save for later", Icons.Default.BookmarkBorder) { ui.putQueueAside() }
                button("Saved queues · ${saved.size}", Icons.Default.Bookmarks, active = showSaved) { showSaved = !showSaved }
            }
        }
    }
}
