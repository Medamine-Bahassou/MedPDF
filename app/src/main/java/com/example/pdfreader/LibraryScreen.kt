package com.example.pdfreader

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class Section(val key: String, val label: String, val icon: ImageVector)

private val sections = listOf(
    Section("all", "All documents", Icons.AutoMirrored.Filled.LibraryBooks),
    Section("recent", "Recent", Icons.Default.History),
    Section("reading", "Reading now", Icons.AutoMirrored.Filled.MenuBook),
    Section("fav", "Favorites", Icons.Default.Star),
    Section("toread", "To read", Icons.Default.Schedule),
    Section("done", "Have read", Icons.Default.CheckCircle),
)

private val sortNames = listOf("Last opened", "Title", "Date added", "Size", "Progress")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val library = context.app.library
    val settings = context.app.settings
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var section by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var newCollection by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { library.add(it) }
        if (uris.size == 1) onOpen(uris[0].toString())
    }
    var access by remember { mutableStateOf(library.hasStorageAccess()) }
    val requestLegacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = granted
        if (granted) scope.launch { library.scan() }
    }
    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            val app = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            // Some OEM builds lack the per-app screen; fall back to the global list.
            runCatching { context.startActivity(app) }
                .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestLegacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // Rescan whenever the app comes back to the foreground (also picks up a freshly granted permission).
    LifecycleResumeEffect(Unit) {
        access = library.hasStorageAccess()
        scope.launch { library.scan() }
        onPauseOrDispose {}
    }
    BackHandler(query != null) { query = null }
    BackHandler(query == null && section != "all") { section = "all" }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("MedPDF", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                    fun go(key: String) { section = key; scope.launch { drawer.close() } }
                    for (s in sections) {
                        NavigationDrawerItem(
                            label = { Text(s.label) },
                            icon = { Icon(s.icon, null) },
                            badge = { Text("${library.books.count { matches(it, s.key) }}") },
                            selected = section == s.key,
                            onClick = { go(s.key) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Collections", Modifier.padding(horizontal = 28.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                    for (c in library.collections) {
                        NavigationDrawerItem(
                            label = { Text(c) },
                            icon = { Icon(Icons.Default.CollectionsBookmark, null) },
                            badge = { Text("${library.books.count { c in it.collections }}") },
                            selected = section == "col:$c",
                            onClick = { go("col:$c") },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("New collection") },
                        icon = { Icon(Icons.Default.Add, null) },
                        selected = false,
                        onClick = { newCollection = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val q = query
                        if (q != null) {
                            TextField(q, { query = it }, placeholder = { Text("Search titles") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(sectionLabel(section))
                        }
                    },
                    navigationIcon = {
                        if (query != null) IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                        else IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    actions = {
                        if (query == null) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = { scope.launch { library.scan() } }, enabled = access && !library.scanning) {
                            Icon(Icons.Default.Refresh, "Rescan device")
                        }
                        IconButton(onClick = { settings.grid = !settings.grid }) {
                            if (settings.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "List view")
                            else Icon(Icons.Default.GridView, "Card view")
                        }
                        var sortMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                sortNames.forEachIndexed { i, name ->
                                    DropdownMenuItem(
                                        text = { Text(if (i == settings.sort) "✓  $name" else "     $name") },
                                        onClick = { settings.sort = i; sortMenu = false },
                                    )
                                }
                            }
                        }
                        if (section.startsWith("col:")) {
                            IconButton(onClick = { library.removeCollection(section.removePrefix("col:")); section = "all" }) {
                                Icon(Icons.Default.Delete, "Delete collection")
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { pickFiles.launch(arrayOf("application/pdf")) }) { Icon(Icons.Default.Add, "Open files") }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (library.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!access) {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Find all PDFs on your phone", style = MaterialTheme.typography.titleSmall)
                            Text("Allow access to files so the library fills itself with every PDF on this device.", Modifier.padding(vertical = 8.dp))
                            Button(onClick = requestAccess) { Text("Allow access") }
                        }
                    }
                }
                run {
                    val q = query.orEmpty()
                    val shown = library.books
                        .filter { matches(it, section) && it.title.contains(q, ignoreCase = true) }
                        .let { list ->
                            when (settings.sort) {
                                1 -> list.sortedBy { it.title.lowercase() }
                                2 -> list.sortedByDescending { it.added }
                                3 -> list.sortedByDescending { it.size }
                                4 -> list.sortedByDescending { it.progress }
                                else -> list.sortedWith(compareByDescending<Book> { it.opened }.thenBy { it.title.lowercase() })
                            }
                        }
                    if (shown.isEmpty()) {
                        EmptyState(section, access, library.scanning, onAddFiles = { pickFiles.launch(arrayOf("application/pdf")) })
                    } else if (settings.grid) {
                        LazyVerticalGrid(
                            columns = CardColumns,
                            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(shown, key = { it.uri }) { book -> BookCard(book, onOpen = { onOpen(book.uri) }) }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(shown, key = { it.uri }) { book -> BookRow(book, onOpen = { onOpen(book.uri) }) }
                        }
                    }
                }
            }
        }
    }

    if (newCollection) {
        TextInputDialog("New collection", "Name", "Create", onDismiss = { newCollection = false }) {
            library.addCollection(it)
            newCollection = false
        }
    }
}

private fun matches(book: Book, section: String) = when (section) {
    "recent" -> book.opened > 0
    "reading" -> book.status == Status.READING
    "fav" -> book.favorite
    "toread" -> book.status == Status.TO_READ
    "done" -> book.status == Status.FINISHED
    else -> if (section.startsWith("col:")) section.removePrefix("col:") in book.collections else true
}

private fun sectionLabel(key: String) =
    sections.firstOrNull { it.key == key }?.label ?: key.removePrefix("col:")

/** Card grid columns: as many 140dp cards as fit, but never fewer than 2 or more than 5 per row. */
private object CardColumns : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = ((availableSize + spacing) / (140.dp.roundToPx() + spacing)).coerceIn(2, 5)
        val usable = availableSize - spacing * (count - 1)
        return List(count) { usable / count + if (it < usable % count) 1 else 0 }
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier) {
    val library = LocalContext.current.app.library
    val cover by produceState<Bitmap?>(null, book.uri) { value = library.cover(book.uri) }
    Box(modifier.background(Color.LightGray)) {
        cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun details(context: android.content.Context, book: Book) = listOfNotNull(
    "PDF",
    book.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
    book.pages.takeIf { it > 0 }?.let { "$it pages" },
    book.status.takeIf { it != Status.NONE }?.label,
).joinToString("  ·  ")

@Composable
private fun BookRow(book: Book, onOpen: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.size(56.dp, 78.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(details(context, book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.opened > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator({ book.progress }, Modifier.weight(1f))
                    Text("  ${(book.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        FavoriteButton(book)
        BookMenu(book)
    }
}

@Composable
private fun BookCard(book: Book, onOpen: () -> Unit) {
    Card(onClick = onOpen) {
        Box {
            BookCover(book, Modifier.fillMaxWidth().aspectRatio(0.72f))
            if (book.opened > 0) {
                LinearProgressIndicator({ book.progress }, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Row(Modifier.padding(start = 10.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text(book.title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (book.opened > 0) "${(book.progress * 100).roundToInt()}% read" else book.pages.takeIf { it > 0 }?.let { "$it pages" } ?: "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            BookMenu(book, withFavorite = true)
        }
    }
}

@Composable
private fun FavoriteButton(book: Book) {
    val library = LocalContext.current.app.library
    IconButton(onClick = { library.update(book.uri) { it.copy(favorite = !it.favorite) } }) {
        Icon(
            if (book.favorite) Icons.Default.Star else Icons.Default.StarBorder,
            if (book.favorite) "Remove from favorites" else "Add to favorites",
            tint = if (book.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Overflow menu with every per-book action, plus the dialogs it opens. */
@Composable
private fun BookMenu(book: Book, withFavorite: Boolean = false) {
    val context = LocalContext.current
    val library = context.app.library
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (withFavorite) DropdownMenuItem(
                { Text(if (book.favorite) "Remove from favorites" else "Add to favorites") },
                onClick = { menu = false; library.update(book.uri) { it.copy(favorite = !it.favorite) } },
            )
            for (s in Status.entries) {
                if (s == book.status) continue
                DropdownMenuItem(
                    { Text(if (s == Status.NONE) "Clear status" else "Mark as ${s.label.lowercase()}") },
                    onClick = { menu = false; library.update(book.uri) { it.copy(status = s) } },
                )
            }
            if (book.page > 0) DropdownMenuItem({ Text("Reset progress") }, onClick = {
                menu = false; library.update(book.uri) { it.copy(page = 0) }
            })
            DropdownMenuItem({ Text("Collections…") }, onClick = { menu = false; dialog = "collections" })
            DropdownMenuItem({ Text("Share") }, onClick = { menu = false; share(context, book.uri) })
            DropdownMenuItem({ Text("Document info") }, onClick = { menu = false; dialog = "info" })
            DropdownMenuItem({ Text("Remove from library") }, onClick = { menu = false; dialog = "remove" })
        }
    }

    when (dialog) {
        "info" -> InfoDialog(book, onDismiss = { dialog = null })
        "collections" -> CollectionsDialog(book, onDismiss = { dialog = null })
        "remove" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove from library?") },
            text = { Text("\"${book.title}\" and its bookmarks will be removed from the library. The file itself is not deleted.") },
            confirmButton = { TextButton(onClick = { library.remove(book.uri); dialog = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CollectionsDialog(book: Book, onDismiss: () -> Unit) {
    val library = LocalContext.current.app.library
    var newName by remember { mutableStateOf<String?>(null) }
    val current = library.get(book.uri) ?: book
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column {
                if (library.collections.isEmpty()) Text("No collections yet.")
                for (c in library.collections) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            library.update(book.uri) { it.copy(collections = if (c in it.collections) it.collections - c else it.collections + c) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(c in current.collections, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(c)
                    }
                }
                TextButton(onClick = { newName = "" }) { Text("+ New collection") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (newName != null) {
        TextInputDialog("New collection", "Name", "Create", onDismiss = { newName = null }) { name ->
            library.addCollection(name)
            if (name.isNotBlank()) library.update(book.uri) { it.copy(collections = it.collections + name.trim()) }
            newName = null
        }
    }
}

@Composable
private fun EmptyState(section: String, access: Boolean, scanning: Boolean, onAddFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        if (section == "all") {
            Text(
                when {
                    scanning -> "Looking for PDFs…"
                    access -> "No PDFs found on this device"
                    else -> "Your library is empty"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAddFiles) { Text("Open files") }
        } else {
            Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        }
    }
}
