package dev.buyolitsez.b2player

import android.Manifest
import android.content.ComponentName
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.ui.platform.LocalFocusManager
import androidx.work.WorkManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private var handoffJob: Job? = null
    private val importM3u = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ui.importM3u(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        ui = Ui(this, app, lifecycleScope)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!app.b2.settings.complete) ui.open(Screen.Settings)
        ui.refreshCatalog()
        // Likes made in cmus show up on return to the app and every minute while it is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { withContext(Dispatchers.IO) { runCatching { app.syncLikes() } }; delay(60_000) } }
        }
        // Download progress and finished downloads refresh the "downloaded" marks in lists.
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(DownloadWorker.TAG)
                .map { list -> list.map { it.state to it.progress.getInt("done", 0) } }
                .distinctUntilChanged().drop(1).collect { ui.version++ }
        }
        setContent {
            val accent by animateColorAsState(ui.colors.accent, label = "accent")
            val system = isSystemInDarkTheme()
            val dark = when (ui.prefs.theme.value) { "Light" -> false; "Dark" -> true; else -> system }
            // Status and navigation bar icons follow the chosen theme, not only the system one.
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(bars, bars)
            }
            PlayerTheme(accent, dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(ui) { importM3u.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "*/*")) } } }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = ui.sync(player)
            })
            ui.sync(c)
            ui.controller = c
        }, MoreExecutors.directExecutor())
        // What cmus played meanwhile, for the Resume banner.
        val app = application as App
        if (app.b2.settings.complete) handoffJob = lifecycleScope.launch {
            while (true) {
                runCatching { withContext(Dispatchers.IO) { app.handoffs.check(app.b2) } }
                    .onSuccess { app.handoffs.offer = it }.onFailure { AppLog.w("handoff", "Now playing fetch failed", it) }
                delay(60_000)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The UI is hidden (or memory is short): the thumbnails are cheap to decode again from disk.
        if (level >= TRIM_MEMORY_UI_HIDDEN) ui.covers.trim()
    }

    override fun onStop() {
        handoffJob?.cancel()
        ui.controller?.release(); ui.controller = null
        (application as App).uploadLogs()
        super.onStop()
    }
}

private val tabIcons: Map<Tab, ImageVector> = mapOf(
    Tab.Queue to Icons.Default.List, Tab.Now to Icons.Default.PlayCircle, Tab.Folders to Icons.Default.Folder,
    Tab.Albums to Icons.Default.Album, Tab.Artists to Icons.Default.Person, Tab.Genres to Icons.Default.Category, Tab.Playlists to Icons.AutoMirrored.Filled.QueueMusic,
    Tab.Search to Icons.Default.Search,
)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Root(ui: Ui, importM3u: () -> Unit) {
    val cat by ui.app.catalog.collectAsState()
    val offline = ui.offlineOnly
    LaunchedEffect(Unit) { ui.watchOffline() }
    val stack = ui.stack()
    val screen = stack.last()
    // Back first closes the keyboard, then goes back.
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    // On the start tab's root the app goes to the background instead of closing, so the open screens and the assistant chat stay.
    val activity = LocalActivity.current
    BackHandler {
        if (imeVisible) focusManager.clearFocus() else if (!ui.back()) activity?.moveTaskToBack(true)
    }

    val coverKey = ui.currentId?.let { cat?.byId?.get(it) }?.album?.cover
    val dark = LocalDark.current
    LaunchedEffect(coverKey, ui.prefs.coverTint.value, dark) {
        ui.colors = (if (ui.prefs.coverTint.value) coverKey?.let { k -> ui.covers.load(k)?.let { coverColors(it, dark) } } else null) ?: defaultColors(dark)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(ui.snackbar) { data ->
                key(data) {
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                        if (v != SwipeToDismissBoxValue.Settled) data.dismiss()
                        true
                    })
                    SwipeToDismissBox(state, backgroundContent = {}) {
                        Snackbar(data, containerColor = Gray2, contentColor = MaterialTheme.colorScheme.onSurface, actionColor = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        topBar = {
            if (ui.tab == Tab.Now && screen == Screen.Root) { Spacer(Modifier.statusBarsPadding()); return@Scaffold }
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    val title = when (screen) {
                        is Screen.AlbumView -> screen.album.title
                        is Screen.ArtistView -> screen.name
                        is Screen.GenreView -> screen.name.ifEmpty { NO_GENRE }
                        is Screen.FolderView -> screen.path.substringAfterLast('/')
                        is Screen.PlaylistView -> ui.app.playlists.get(screen.id)?.name ?: "Playlist"
                        Screen.LikedView -> "Liked"
                        Screen.HistoryView -> "History and stats"
                        Screen.Downloads -> "Downloads"
                        Screen.EqualizerView -> "Equalizer"
                        Screen.Settings -> "Settings"
                        Screen.Assistant -> "Assistant"
                        Screen.Root -> ui.tab.title
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = { if (stack.size > 1) IconButton({ ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (offline) Icon(Icons.Default.CloudOff, null, Modifier.padding(end = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    if (screen == Screen.Root && ui.tab == Tab.Albums || screen is Screen.ArtistView || screen is Screen.GenreView) {
                        IconButton({ ui.prefs.albumGrid.set(!ui.prefs.albumGrid.value) }) {
                            ui.version
                            Icon(if (ui.prefs.albumGrid.value) Icons.Default.ViewList else Icons.Default.GridView, null)
                        }
                    }
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
                            DropdownMenuItem({ Text(if (offline) "Turn off offline only" else "Offline only") },
                                { menu = false; ui.app.prefs.setOfflineOnly(!offline); ui.version++ },
                                leadingIcon = { Icon(Icons.Default.CloudOff, null) })
                            DropdownMenuItem({ Text("Downloads") }, { menu = false; ui.open(Screen.Downloads) }, leadingIcon = { Icon(Icons.Default.Download, null) })
                            DropdownMenuItem({ Text("Reload library") }, { menu = false; ui.refreshCatalog() }, leadingIcon = { Icon(Icons.Default.Sync, null) })
                            DropdownMenuItem({ Text("Settings") }, { menu = false; ui.open(Screen.Settings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Above the keyboard when it is open, so the tabs, mini player and messages stay visible.
            Column(Modifier.background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                ResumeBanner(ui, cat)
                if (ui.tab != Tab.Now || screen != Screen.Root) MiniPlayer(ui, cat)
                HorizontalDivider(color = Gray2)
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tab.entries.forEach { t ->
                        val sel = t == ui.tab
                        IconButton({ ui.selectTab(t) }, Modifier.weight(1f)) {
                            Icon(tabIcons[t]!!, t.title, tint = if (sel) MaterialTheme.colorScheme.primary else Muted)
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ui.status?.let {
                val progress = it == Ui.UPDATING
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = if (progress) Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val c = cat
            when {
                screen == Screen.Settings -> SettingsScreen(ui, c)
                c == null -> Placeholder(if (ui.status == Ui.UPDATING || ui.library.catalogFile.exists()) "Loading the library…" else "The library isn't loaded yet")
                screen == Screen.Downloads -> DownloadsScreen(ui, c)
                screen == Screen.Assistant -> AssistantScreen(ui)
                screen == Screen.EqualizerView -> EqualizerScreen(ui)
                screen is Screen.AlbumView -> AlbumScreen(ui, screen.album)
                screen is Screen.ArtistView -> ArtistScreen(ui, c, screen.name)
                screen is Screen.GenreView -> GenreScreen(ui, c, screen.name)
                screen is Screen.FolderView -> FolderScreen(ui, c, screen.path)
                screen is Screen.PlaylistView -> PlaylistScreen(ui, c, screen.id)
                screen == Screen.LikedView -> LikedScreen(ui, c)
                screen == Screen.HistoryView -> HistoryScreen(ui, c)
                else -> when (ui.tab) {
                    Tab.Queue -> QueueTab(ui, c)
                    Tab.Now -> NowPlayingTab(ui, c)
                    Tab.Folders -> FolderScreen(ui, c, "")
                    Tab.Albums -> AlbumsTab(ui, c)
                    Tab.Artists -> ArtistsTab(ui, c)
                    Tab.Genres -> GenresTab(ui, c)
                    Tab.Playlists -> PlaylistsTab(ui, c, importM3u)
                    Tab.Search -> SearchTab(ui, c)
                }
            }
        }
    }
    AddToPlaylistDialog(ui)
    TrackInfoDialog(ui)
}
