package dev.buyolitsez.b2player

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class AlbumView(val album: Album) : Screen
    data class ArtistView(val name: String) : Screen
    data object NowPlaying : Screen
    data object Settings : Screen
}

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val app get() = application as App
    private lateinit var covers: Covers

    private var catalog by mutableStateOf<Catalog?>(null)
    private var status by mutableStateOf<String?>(null)
    private var stack by mutableStateOf(listOf<Screen>(Screen.Home))
    private var tab by mutableIntStateOf(0)
    private var query by mutableStateOf("")
    private var libVersion by mutableIntStateOf(0)

    private var controller by mutableStateOf<MediaController?>(null)
    private var currentId by mutableStateOf<String?>(null)
    private var playing by mutableStateOf(false)
    private var shuffle by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        covers = Covers(app)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!app.b2.settings.complete) stack = listOf(Screen.Home, Screen.Settings)
        loadCatalog()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync(player)
            })
            sync(c)
            controller = c
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release(); controller = null
        super.onStop()
    }

    private fun sync(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        shuffle = p.shuffleModeEnabled
    }

    private fun loadCatalog() = lifecycleScope.launch {
        val file = app.library.catalogFile
        val saved = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { Catalog.parse(file.readBytes()) }.getOrNull() else null
        }
        if (saved != null) catalog = saved
        if (!app.b2.settings.complete) return@launch
        status = "Обновляю каталог…"
        status = try {
            val bytes = withContext(Dispatchers.IO) { app.b2.open("catalog.json.gz").inputStream.use { it.readBytes() } }
            catalog = withContext(Dispatchers.Default) { Catalog.parse(bytes) }
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            null
        } catch (e: Exception) {
            "Каталог не загрузился: ${e.message}"
        }
    }

    private fun push(s: Screen) { stack = stack + s }
    private fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    private fun play(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { app.library.mediaItem(it) }, index, 0)
        c.prepare(); c.play()
    }

    private fun download(name: String, tracks: List<Track>) {
        DownloadWorker.enqueue(this, name, tracks.map { it.path })
        Toast.makeText(this, "Скачиваю: ${tracks.size} тр.", Toast.LENGTH_SHORT).show()
    }

    private fun liked(t: Track): Boolean { libVersion; return app.library.isLiked(t) }
    private fun toggleLike(t: Track) { app.library.setLiked(t, !app.library.isLiked(t)); libVersion++ }

    // ---------------- UI ----------------

    @kotlin.OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val screen = stack.last()
        BackHandler(stack.size > 1) { pop() }
        BackHandler(stack.size == 1 && query.isNotEmpty()) { query = "" }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        when (screen) {
                            Screen.Home -> OutlinedTextField(
                                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text("Поиск") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                            )
                            is Screen.AlbumView -> Text(screen.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            is Screen.ArtistView -> Text(screen.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Screen.NowPlaying -> Text("Сейчас играет")
                            Screen.Settings -> Text("Настройки")
                        }
                    },
                    navigationIcon = {
                        if (stack.size > 1) IconButton({ pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    },
                    actions = {
                        if (screen == Screen.Home) IconButton({ push(Screen.Settings) }) { Icon(Icons.Default.Settings, null) }
                    },
                )
            },
            bottomBar = { if (screen != Screen.NowPlaying) MiniPlayer() },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                status?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                val cat = catalog
                when {
                    screen == Screen.Settings -> SettingsScreen()
                    screen == Screen.NowPlaying -> NowPlayingScreen()
                    cat == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Каталог ещё не загружен") }
                    screen is Screen.AlbumView -> AlbumScreen(screen.album)
                    screen is Screen.ArtistView -> AlbumList(cat.albums.filter { it.artist == screen.name })
                    query.isNotBlank() -> SearchScreen(cat, query.trim())
                    else -> HomeScreen(cat)
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(cat: Catalog) {
        val tabs = listOf("Альбомы", "Исполнители", "Любимые", "Офлайн")
        ScrollableTabRow(tab, edgePadding = 0.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) } }
        when (tab) {
            0 -> AlbumList(cat.albums)
            1 -> LazyColumn {
                items(cat.artists, key = { it.first }) { (name, albums) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("альбомов: ${albums.size}") },
                        modifier = Modifier.clickable { push(Screen.ArtistView(name)) },
                    )
                }
            }
            2 -> {
                val liked = cat.tracks.filter { liked(it) }
                TrackList(liked, header = {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ play(liked, 0) }, enabled = liked.isNotEmpty()) { Text("Слушать (${liked.size})") }
                        OutlinedButton({ download("liked", liked) }, enabled = liked.isNotEmpty()) { Text("Скачать") }
                    }
                }, showAlbum = true)
            }
            3 -> {
                libVersion
                TrackList(cat.tracks.filter { app.library.isOffline(it) }, showAlbum = true)
            }
        }
    }

    @Composable
    private fun SearchScreen(cat: Catalog, q: String) {
        val albums = remember(cat, q) {
            cat.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.dir.contains(q, true) }
        }
        val tracks = remember(cat, q) { cat.tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(200) }
        LazyColumn {
            if (albums.isNotEmpty()) item { SectionTitle("Альбомы") }
            items(albums.take(50), key = { "a" + it.id }) { AlbumRow(it) }
            if (tracks.isNotEmpty()) item { SectionTitle("Треки") }
            itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t -> TrackRow(t, true) { play(tracks, i) } }
        }
    }

    @Composable
    private fun SectionTitle(s: String) =
        Text(s, Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)

    @Composable
    private fun AlbumList(albums: List<Album>) {
        LazyColumn { items(albums, key = { it.id }) { AlbumRow(it) } }
    }

    @Composable
    private fun AlbumRow(a: Album) {
        ListItem(
            leadingContent = { Cover(a.cover, 52.dp) },
            headlineContent = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            modifier = Modifier.clickable { push(Screen.AlbumView(a)) },
        )
    }

    @Composable
    private fun AlbumScreen(a: Album) {
        TrackList(a.tracks, header = {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(a.cover, 120.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(a.artist, modifier = Modifier.clickable { push(Screen.ArtistView(a.artist)) })
                    a.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ play(a.tracks, 0) }) { Icon(Icons.Default.PlayArrow, null); Text("Слушать") }
                OutlinedButton({ controller?.shuffleModeEnabled = true; play(a.tracks, a.tracks.indices.random()) }) {
                    Icon(Icons.Default.Shuffle, null)
                }
                OutlinedButton({ download(a.id, a.tracks) }) { Icon(Icons.Default.Download, null); Text("Офлайн") }
            }
        })
    }

    @Composable
    private fun TrackList(tracks: List<Track>, header: (@Composable () -> Unit)? = null, showAlbum: Boolean = false) {
        LazyColumn {
            header?.let { item { it() } }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, showAlbum) { play(tracks, i) } }
        }
    }

    @Composable
    private fun TrackRow(t: Track, showAlbum: Boolean, onClick: () -> Unit) {
        val active = t.id == currentId
        ListItem(
            leadingContent = if (showAlbum) ({ Cover(t.album?.cover, 44.dp) }) else ({
                Text(t.number?.toString() ?: "", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall)
            }),
            headlineContent = {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                val sub = if (showAlbum) "${t.artist} · ${t.album?.title}" else t.artist
                Text("$sub · ${fmt(t.duration)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    libVersion
                    if (app.library.isOffline(t)) Icon(Icons.Default.DownloadDone, null, Modifier.size(18.dp))
                    IconButton({ toggleLike(t) }) {
                        Icon(if (liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, null)
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    private fun Cover(key: String?, size: Dp) {
        val bmp by produceState(key?.let { covers.cached(it) }, key) { if (key != null && value == null) value = covers.load(key) }
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Album, null)
        }
    }

    private fun currentTrack(): Track? = currentId?.let { catalog?.byId?.get(it) }

    @Composable
    private fun MiniPlayer() {
        val t = currentTrack() ?: return
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().clickable { push(Screen.NowPlaying) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(t.album?.cover, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton({ controller?.run { if (playing) pause() else play() } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
                IconButton({ controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }

    @Composable
    private fun NowPlayingScreen() {
        val c = controller
        val t = currentTrack()
        if (c == null || t == null) { Text("Ничего не играет", Modifier.padding(16.dp)); return }
        var pos by remember { mutableLongStateOf(0L) }
        var dragging by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(c) { while (true) { pos = c.currentPosition; delay(500) } }
        val dur = (t.duration * 1000).toLong().coerceAtLeast(1)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(t.album?.cover, 300.dp)
            Spacer(Modifier.height(24.dp))
            Text(t.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            Text(t.artist, modifier = Modifier.clickable { push(Screen.ArtistView(t.artist)) })
            Text(t.album?.title ?: "", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { t.album?.let { push(Screen.AlbumView(it)) } })
            Spacer(Modifier.height(16.dp))
            Slider(
                value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { c.seekTo((it * dur).toLong()) }; dragging = null },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos / 1000.0)); Text(fmt(t.duration))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ c.shuffleModeEnabled = !shuffle }) {
                    Icon(Icons.Default.Shuffle, null, tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton({ c.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null) }
                FilledIconButton({ if (playing) c.pause() else c.play() }, Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
                IconButton({ c.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null) }
                IconButton({ toggleLike(t) }) {
                    Icon(if (liked(t)) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, null)
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val s = remember { app.b2.settings }
        var keyId by remember { mutableStateOf(s.keyId) }
        var appKey by remember { mutableStateOf(s.appKey) }
        var bucket by remember { mutableStateOf(s.bucket) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ключ Backblaze B2 только на чтение для бакета с музыкой.")
            OutlinedTextField(keyId, { keyId = it }, Modifier.fillMaxWidth(), label = { Text("keyID") }, singleLine = true)
            OutlinedTextField(
                appKey, { appKey = it }, Modifier.fillMaxWidth(), label = { Text("applicationKey") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(bucket, { bucket = it }, Modifier.fillMaxWidth(), label = { Text("Бакет") }, singleLine = true)
            Button({
                app.b2.settings = B2.Settings(keyId, appKey, bucket)
                pop(); loadCatalog()
            }) { Text("Сохранить") }
        }
    }

    private fun fmt(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
