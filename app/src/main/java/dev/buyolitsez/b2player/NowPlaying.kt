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
    LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(500) } }
    val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
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
            SmallIcon(if (ui.liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, if (ui.liked(t)) accent else white, 28.dp) { ui.toggleLike(t) }
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
                SliderDefaults.Track(
                    st, Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    colors = SliderDefaults.colors(activeTrackColor = white, inactiveTrackColor = white.copy(alpha = 0.22f)),
                )
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val at = (dragging?.let { it * dur } ?: pos.toFloat()) / 1000.0
            Text(formatTime(at), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC9C9C9))
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
