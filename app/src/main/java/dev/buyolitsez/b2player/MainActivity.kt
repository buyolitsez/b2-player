package dev.buyolitsez.b2player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.compose.ui.graphics.Color as UiColor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.ui.platform.LocalFocusManager
import androidx.work.WorkManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private val importM3u = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ui.importM3u(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        val app = application as App
        ui = Ui(this, app, lifecycleScope)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!app.b2.settings.complete) { ui.open(Screen.Settings); ui.open(Screen.Accounts) }
        else if (!hasStorageAccess(this) && !app.prefs.askedStorage.value) {
            app.prefs.askedStorage.set(true)
            ui.toast("Allow access to all files: downloads go to ${app.prefs.downloadDir.value} and stay when the app is removed")
            startActivity(storageAccessIntent(this))
        } else if (!ignoresBatteryOptimizations(this) && !app.prefs.askedBattery.value) {
            app.prefs.askedBattery.set(true)
            startActivity(batteryExemptionIntent(this))
        }
        ui.refreshCatalog()
        // Likes and ratings made in cmus, and what it played for the Resume banner, show up on return to the app and every minute while it is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    withContext(Dispatchers.IO) { runCatching { app.syncLikes() }; runCatching { app.syncRatings() } }
                    runCatching { withContext(Dispatchers.IO) { app.handoffs.check(app.b2) } }
                        .onSuccess { app.handoffs.offer = it }.onFailure { AppLog.w("handoff", "Now playing fetch failed", it) }
                    delay(60_000)
                }
            }
        }
        if (intent.getBooleanExtra(OPEN_NOW_PLAYING, false)) ui.nowOpen = true
        if (intent.getBooleanExtra(Updates.INSTALL, false)) app.updates.install()
        // An update downloaded in the background offers to install while the app is open; the notification offers it too.
        lifecycleScope.launch {
            snapshotFlow { app.updates.ready }.collectLatest {
                if (it == null) return@collectLatest
                ui.snackbar.currentSnackbarData?.dismiss()
                val text = "B2 Player ${app.updates.newer.firstOrNull()?.version.orEmpty()} is ready"
                if (ui.snackbar.showSnackbar(text, "Install", duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) app.updates.install()
            }
        }
        // Download progress and finished downloads refresh the "downloaded" marks in lists,
        // at most every 2 s: each refresh recomputes the open list.
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(DownloadWorker.TAG)
                .map { list -> list.map { it.state to it.progress.getInt("done", 0) } }
                .distinctUntilChanged().drop(1).conflate().collect { ui.version++; delay(2000) }
        }
        setContent {
            val accent by animateColorAsState(ui.colors.accent, label = "accent")
            PlayerTheme(accent) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(ui) { importM3u.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "*/*")) } } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(OPEN_NOW_PLAYING, false)) ui.nowOpen = true
        if (intent.getBooleanExtra(Updates.INSTALL, false)) (application as App).updates.install()
    }

    companion object { const val OPEN_NOW_PLAYING = "openNowPlaying" }

    override fun onResume() {
        super.onResume()
        // Back from the all-files access screen or the battery dialog.
        ui.moveDownloads()
        ui.backgroundDownloads = ignoresBatteryOptimizations(this)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = ui.sync(player)
                // Otherwise a track that can't load, e.g. streamed without a network, just stays paused.
                override fun onPlayerError(error: PlaybackException) {
                    val offline = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    ui.toast(if (offline) "Can't play: no network, and the track isn't downloaded" else "Can't play: ${error.errorCodeName}")
                }
            })
            ui.sync(c)
            ui.controller = c
        }, MoreExecutors.directExecutor())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The UI is hidden (or memory is short): the thumbnails are cheap to decode again from disk.
        if (level >= TRIM_MEMORY_UI_HIDDEN) ui.covers.trim()
    }

    override fun onStop() {
        ui.controller?.release(); ui.controller = null
        (application as App).uploadLogs()
        super.onStop()
    }
}

private val tabIcons: Map<Tab, ImageVector> = mapOf(
    Tab.Library to Icons.Outlined.LibraryMusic, Tab.Playlists to Icons.AutoMirrored.Outlined.QueueMusic, Tab.Search to Icons.Outlined.Search,
)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Root(ui: Ui, importM3u: () -> Unit) {
    val cat by ui.app.catalog.collectAsState()
    val stack = ui.stack()
    val screen = stack.last()
    val saved = rememberSaveableStateHolder()
    // Back first closes the keyboard, then goes back.
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    // On the start tab's root the app goes to the background instead of closing, so the open screens and the assistant chat stay.
    val activity = LocalActivity.current
    BackHandler {
        if (imeVisible) focusManager.clearFocus() else if (!ui.back()) activity?.moveTaskToBack(true)
    }
    LaunchedEffect(screen, ui.tab, ui.section) { ui.filterOpen = false }

    val coverKey = ui.currentId?.let { cat?.byId?.get(it) }?.album?.cover
    LaunchedEffect(coverKey, ui.prefs.coverTint.value) {
        ui.colors = (if (ui.prefs.coverTint.value) coverKey?.let { k -> ui.covers.load(k)?.let { withContext(Dispatchers.Default) { coverColors(it) } } } else null) ?: DefaultColors
    }
    // The album and Liked screens get their color at the top, fading out under the list.
    val headerTint by produceState<UiColor?>(null, screen) {
        value = when (screen) {
            is Screen.AlbumView -> screen.album.cover?.let { k -> ui.covers.load(k)?.let { withContext(Dispatchers.Default) { coverColors(it).background } } }
            Screen.LikedView -> LikedTint
            else -> null
        }
    }
    val haze = rememberHazeState()
    val tintHeight = with(LocalDensity.current) { 700.dp.toPx() }
    val barFade = with(LocalDensity.current) { 20.dp.toPx() }
    // Screens that have a search field above their list.
    val hasFilter = when (screen) {
        Screen.Root -> ui.tab != Tab.Search
        is Screen.ArtistView, is Screen.GenreView, is Screen.FolderView, is Screen.PlaylistView, Screen.LikedView, Screen.Downloads -> true
        else -> false
    }
    // Screens that follow the library's like filter.
    val likeFiltered = screen == Screen.Root || screen is Screen.AlbumView || screen is Screen.ArtistView || screen is Screen.GenreView ||
        screen is Screen.FolderView || screen is Screen.PlaylistView

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        headerTint?.let { Box(Modifier.fillMaxSize().background(fade(it, tintHeight))) }
        Scaffold(
            containerColor = UiColor.Transparent,
            topBar = {
                val root = screen == Screen.Root
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = UiColor.Transparent, scrolledContainerColor = UiColor.Transparent),
                    title = {
                        val title = when (screen) {
                            // These screens show their name in a large header of their own.
                            is Screen.AlbumView, is Screen.PlaylistView, Screen.LikedView, Screen.Settings, Screen.Accounts -> ""
                            is Screen.ArtistView -> screen.name
                            is Screen.GenreView -> screen.name.ifEmpty { NO_GENRE }
                            is Screen.FolderView -> screen.path.substringAfterLast('/')
                            Screen.HistoryView -> "History and stats"
                            Screen.Downloads -> "Downloads"
                            Screen.EqualizerView -> "Equalizer"
                            Screen.Assistant -> "Assistant"
                            Screen.Root -> ui.tab.title
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (root) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
                    },
                    navigationIcon = { if (stack.size > 1) IconButton({ ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = {
                        if (hasFilter) IconButton({ ui.filterOpen = !ui.filterOpen }) { Icon(Icons.Default.Search, "Search in this list") }
                        // Tap: liked tracks only or everything; long press: tracks that aren't liked.
                        if (likeFiltered) {
                            val f = ui.libraryFilter
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                                    onLongClick = { ui.setLibraryFilter(LikeFilter.NotLiked) },
                                    onClick = { ui.setLibraryFilter(if (f == LikeFilter.All) LikeFilter.Liked else LikeFilter.All) },
                                ),
                                Alignment.Center,
                            ) {
                                when (f) {
                                    LikeFilter.All -> Icon(Icons.Default.FavoriteBorder, "Show liked tracks only")
                                    LikeFilter.Liked -> Icon(Icons.Default.Favorite, "Show all tracks", tint = MaterialTheme.colorScheme.primary)
                                    LikeFilter.NotLiked -> Icon(Icons.Default.HeartBroken, "Show all tracks", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (screen is Screen.ArtistView || screen is Screen.GenreView) {
                            IconButton({ ui.prefs.albumGrid.set(!ui.prefs.albumGrid.value) }) {
                                Icon(if (ui.prefs.albumGrid.value) Icons.Default.ViewList else Icons.Default.GridView, null)
                            }
                        }
                        if (root && ui.tab == Tab.Playlists) IconButton({ ui.newPlaylist = true }) { Icon(Icons.Default.Add, "New playlist") }
                        if (screen == Screen.Assistant) {
                            var confirmNew by remember { mutableStateOf(false) }
                            IconButton({ if (ui.assistant.entries.isEmpty()) ui.assistant.clear() else confirmNew = true }) { Icon(Icons.Default.AddComment, "New chat") }
                            if (confirmNew) AlertDialog(
                                { confirmNew = false }, containerColor = Gray2, title = { Text("Start a new chat?") },
                                text = { Text("This conversation will be cleared.") },
                                confirmButton = { TextButton({ ui.assistant.clear(); confirmNew = false }) { Text("New chat") } },
                                dismissButton = { TextButton({ confirmNew = false }) { Text("Cancel") } },
                            )
                        }
                        else IconButton({ ui.open(Screen.Assistant) }) { Icon(Icons.Default.AutoAwesome, "Assistant") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }, containerColor = Gray2) {
                                if (root && ui.tab == Tab.Playlists) DropdownMenuItem({ Text("Import .m3u") }, { menu = false; importM3u() }, leadingIcon = { Icon(Icons.Default.FileOpen, null) })
                                DropdownMenuItem({ Text("Downloads") }, { menu = false; ui.open(Screen.Downloads) }, leadingIcon = { Icon(Icons.Default.Download, null) })
                                DropdownMenuItem({ Text("Reload library") }, { menu = false; ui.refreshCatalog() }, leadingIcon = { Icon(Icons.Default.Sync, null) })
                                DropdownMenuItem({ Text("Settings") }, { menu = false; ui.open(Screen.Settings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                            }
                        }
                    },
                )
            },
            bottomBar = {
                // One frosted panel under the mini player and the tabs; the list shows blurred through it.
                // Above the keyboard when it is open, so the tabs and mini player stay visible.
                Column(
                    Modifier.hazeEffect(haze) {
                        blurRadius = 24.dp
                        backgroundColor = UiColor.Black
                        tints = listOf(HazeTint(UiColor(0xC7080A09)))
                        fallbackTint = HazeTint(UiColor(0xF0080A09))
                        // Fades in over the top padding only, so a taller panel (with the Resume banner) is solid under its content.
                        mask = Brush.verticalGradient(listOf(UiColor.Transparent, UiColor.Black), endY = barFade)
                    }.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(top = 20.dp)
                ) {
                    ResumeBanner(ui, cat)
                    MiniPlayer(ui, cat)
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tab.entries.forEach { t ->
                            val sel = t == ui.tab
                            val color = if (sel) UiColor(0xFFDCDCDC) else Faint
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { ui.selectTab(t) },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    Modifier.size(56.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) UiColor.White.copy(alpha = 0.07f) else UiColor.Transparent),
                                    Alignment.Center,
                                ) { Icon(tabIcons[t]!!, null, Modifier.size(24.dp), tint = color) }
                                Text(t.title, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), color = color)
                            }
                        }
                    }
                }
            },
        ) { pad ->
            // The list runs under the panel at the bottom and pads its end instead.
            CompositionLocalProvider(LocalListBottom provides pad.calculateBottomPadding()) {
                Column(Modifier.fillMaxSize().hazeSource(haze).padding(top = pad.calculateTopPadding())) {
                    ui.status?.let {
                        val progress = it == Ui.UPDATING
                        Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = if (progress) Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    val full = cat
                    val c = remember(full, ui.libraryFilter, ui.version) { full?.let { ui.libraryView(it) } }
                    // Each open screen keeps its own scroll position and other saved state, so going back returns to where it was.
                    saved.SaveableStateProvider("${ui.tab}/${stack.size}/$screen") {
                        when {
                            screen == Screen.Settings -> SettingsScreen(ui, full)
                            screen == Screen.Accounts -> AccountsScreen(ui)
                            c == null || full == null -> Placeholder(if (ui.status == Ui.UPDATING || ui.library.catalogFile.exists()) "Loading the library…" else "The library isn't loaded yet")
                            screen == Screen.Downloads -> DownloadsScreen(ui, full)
                            screen == Screen.Assistant -> AssistantScreen(ui)
                            screen == Screen.EqualizerView -> EqualizerScreen(ui)
                            // the album as in the current catalog, so tracks deleted from it leave the screen
                            screen is Screen.AlbumView -> AlbumScreen(ui, c.albums.find { it.id == screen.album.id } ?: screen.album)
                            screen is Screen.ArtistView -> ArtistScreen(ui, c, screen.name)
                            screen is Screen.GenreView -> GenreScreen(ui, c, screen.name)
                            screen is Screen.FolderView -> FolderScreen(ui, c, screen.path)
                            // The whole catalog: the playlist's own rows keep their places, the filter only hides some.
                            screen is Screen.PlaylistView -> PlaylistScreen(ui, full, screen.id)
                            screen == Screen.LikedView -> LikedScreen(ui, full)
                            screen == Screen.HistoryView -> HistoryScreen(ui, full)
                            else -> when (ui.tab) {
                                Tab.Library -> Column {
                                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val chips = rememberScrollState()
                                        Row(
                                            // Chips that don't fit fade out at the edge instead of being cut off by the buttons.
                                            Modifier.weight(1f).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                                                drawContent()
                                                val w = 40.dp.toPx()
                                                if (chips.canScrollForward) drawRect(Brush.horizontalGradient(listOf(UiColor.Black, UiColor.Transparent), size.width - w, size.width), blendMode = BlendMode.DstIn)
                                                if (chips.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(UiColor.Transparent, UiColor.Black), 0f, w), blendMode = BlendMode.DstIn)
                                            }.horizontalScroll(chips).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) { LibrarySection.entries.forEach { s -> Pill(s.name, active = s == ui.section) { ui.showSection(s) } } }
                                        // What the open view lists, with its sort and period, in order or shuffled.
                                        fun shown() = when (ui.section) {
                                            LibrarySection.Albums -> sortAlbums(ui, c.albums).flatMap { it.tracks }
                                            LibrarySection.Tracks -> sortTracks(ui, c.tracks)
                                            else -> c.tracks
                                        }
                                        SmallIcon(Icons.Default.Shuffle, MaterialTheme.colorScheme.onSurface, 24.dp) { ui.play(shown(), shuffled = true) }
                                        Box(
                                            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { ui.play(shown()) },
                                            Alignment.Center,
                                        ) { Icon(Icons.Default.PlayArrow, "Play all", Modifier.size(24.dp), tint = UiColor.Black) }
                                    }
                                    when (ui.section) {
                                        LibrarySection.Albums -> AlbumsTab(ui, c)
                                        LibrarySection.Tracks -> TracksTab(ui, c)
                                        LibrarySection.Artists -> ArtistsTab(ui, c)
                                        LibrarySection.Genres -> GenresTab(ui, c)
                                        LibrarySection.Folders -> FolderScreen(ui, c, "")
                                    }
                                }
                                Tab.Playlists -> PlaylistsTab(ui, full)
                                Tab.Search -> SearchTab(ui, c)
                            }
                        }
                    }
                }
            }
        }
        // Now playing and the queue cover everything; the empty click keeps taps from reaching the tabs below.
        val c = cat
        if (ui.nowOpen && c != null) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}) {
            if (ui.queueOpen) QueueScreen(ui, c) else NowPlayingScreen(ui, c)
        }
        // Messages float just above the mini player, in its shape.
        SnackbarHost(ui.snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = if (ui.nowOpen) 8.dp else 128.dp)) { data ->
            key(data) {
                val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v != SwipeToDismissBoxValue.Settled) data.dismiss()
                    true
                })
                SwipeToDismissBox(state, backgroundContent = {}) {
                    Snackbar(
                        Modifier.padding(horizontal = 24.dp).shadow(16.dp, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp),
                        containerColor = UiColor(0xFF2A2A2A), contentColor = MaterialTheme.colorScheme.onSurface,
                        action = data.visuals.actionLabel?.let { label ->
                            { TextButton({ data.performAction() }) { Text(label, color = MaterialTheme.colorScheme.primary) } }
                        },
                    ) { Text(data.visuals.message, Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }
    }
    AddToPlaylistDialog(ui)
    DeleteDialog(ui)
    TrackInfoDialog(ui)
    UpdateDialog(ui.app.updates)
    TrackSheet(ui)
}
