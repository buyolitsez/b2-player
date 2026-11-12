package dev.buyolitsez.b2player

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        ui = Ui(this, app, lifecycleScope)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!app.b2.settings.complete) ui.open(Screen.Settings)
        ui.refreshCatalog()
        setContent {
            val accent by animateColorAsState(ui.colors.accent, label = "accent")
            PlayerTheme(accent) { Surface(Modifier.fillMaxSize(), color = Color.Black) { Root(ui) } }
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
    }

    override fun onStop() {
        ui.controller?.release(); ui.controller = null
        super.onStop()
    }
}

private val tabIcons: Map<Tab, ImageVector> = mapOf(
    Tab.Queue to Icons.AutoMirrored.Filled.QueueMusic, Tab.Now to Icons.Default.PlayCircle, Tab.Folders to Icons.Default.Folder,
    Tab.Albums to Icons.Default.Album, Tab.Artists to Icons.Default.Person, Tab.Liked to Icons.Default.Favorite,
    Tab.Search to Icons.Default.Search,
)

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(ui: Ui) {
    val cat by ui.app.catalog.collectAsState()
    val offline by ui.app.prefs.offlineOnly.collectAsState()
    val stack = ui.stack()
    val screen = stack.last()
    BackHandler(stack.size > 1 || (ui.tab == Tab.Search && ui.query.isNotEmpty())) { ui.back() }

    val coverKey = ui.currentId?.let { cat?.byId?.get(it) }?.album?.cover
    LaunchedEffect(coverKey) {
        ui.colors = coverKey?.let { k -> ui.covers.load(k)?.let { coverColors(it) } } ?: DefaultColors
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(ui.snackbar) { Snackbar(it, containerColor = Gray2, contentColor = Color.White, actionColor = MaterialTheme.colorScheme.primary) } },
        topBar = {
            if (ui.tab == Tab.Now && screen == Screen.Root) { Spacer(Modifier.statusBarsPadding()); return@Scaffold }
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                title = {
                    val title = when (screen) {
                        is Screen.AlbumView -> screen.album.title
                        is Screen.ArtistView -> screen.name
                        is Screen.FolderView -> screen.path.substringAfterLast('/')
                        Screen.Downloads -> "Загрузки"
                        Screen.Settings -> "Настройки"
                        Screen.Root -> ui.tab.title
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = { if (stack.size > 1) IconButton({ ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (offline) Icon(Icons.Default.CloudOff, null, Modifier.padding(end = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    if (screen == Screen.Root && ui.tab == Tab.Albums || screen is Screen.ArtistView) {
                        IconButton({ ui.app.prefs.albumGrid = !ui.app.prefs.albumGrid; ui.version++ }) {
                            ui.version
                            Icon(if (ui.app.prefs.albumGrid) Icons.Default.ViewList else Icons.Default.GridView, null)
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(menu, { menu = false }, containerColor = Gray2) {
                            DropdownMenuItem({ Text(if (offline) "Выключить «только офлайн»" else "Только офлайн") },
                                { menu = false; ui.app.prefs.setOfflineOnly(!offline); ui.version++ },
                                leadingIcon = { Icon(Icons.Default.CloudOff, null) })
                            DropdownMenuItem({ Text("Загрузки") }, { menu = false; ui.open(Screen.Downloads) }, leadingIcon = { Icon(Icons.Default.Download, null) })
                            DropdownMenuItem({ Text("Обновить каталог") }, { menu = false; ui.refreshCatalog() }, leadingIcon = { Icon(Icons.Default.Sync, null) })
                            DropdownMenuItem({ Text("Настройки") }, { menu = false; ui.open(Screen.Settings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(Color.Black).navigationBarsPadding()) {
                if (ui.tab != Tab.Now) MiniPlayer(ui, cat)
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
            ui.status?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val c = cat
            when {
                screen == Screen.Settings -> SettingsScreen(ui, c)
                c == null -> Placeholder("Каталог ещё не загружен")
                screen == Screen.Downloads -> DownloadsScreen(ui, c)
                screen is Screen.AlbumView -> AlbumScreen(ui, screen.album)
                screen is Screen.ArtistView -> ArtistScreen(ui, c, screen.name)
                screen is Screen.FolderView -> FolderScreen(ui, c, screen.path)
                else -> when (ui.tab) {
                    Tab.Queue -> QueueTab(ui, c)
                    Tab.Now -> NowPlayingTab(ui, c)
                    Tab.Folders -> FolderScreen(ui, c, "")
                    Tab.Albums -> AlbumsTab(ui, c)
                    Tab.Artists -> ArtistsTab(ui, c)
                    Tab.Liked -> LikedTab(ui, c)
                    Tab.Search -> SearchTab(ui, c)
                }
            }
        }
    }
}
