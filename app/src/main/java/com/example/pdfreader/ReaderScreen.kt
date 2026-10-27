package com.example.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Search, text and links need the Android 15 PdfRenderer APIs. */
private val textApi = Build.VERSION.SDK_INT >= 35

class Link(val bounds: List<RectF>, val page: Int?, val url: Uri?)

/** A rendered page: [crop] is the region of the page (in PDF points) the bitmap shows. */
class Rendered(val bitmap: Bitmap, val crop: RectF, val links: List<Link>)

class Hit(val page: Int, val bounds: List<RectF>, val snippet: String)

/** PdfRenderer is not thread-safe: every access goes through this lock. */
class PdfDoc private constructor(pfd: ParcelFileDescriptor) : AutoCloseable {
    private val renderer = PdfRenderer(pfd)
    private var closed = false
    private val crops = arrayOfNulls<RectF>(renderer.pageCount)
    val pageCount = renderer.pageCount

    companion object {
        fun open(context: Context, uri: Uri) =
            context.contentResolver.openFileDescriptor(uri, "r")?.let(::PdfDoc)
    }

    @Synchronized
    fun render(index: Int, width: Int, crop: Boolean): Rendered? {
        if (closed) return null
        return renderer.openPage(index).use { page ->
            val area = areaOf(page, crop)
            val k = width / area.width()
            val bitmap = Bitmap.createBitmap(width, (area.height() * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val m = Matrix().apply { postTranslate(-area.left, -area.top); postScale(k, k) }
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Rendered(bitmap, area, if (textApi) links(page) else emptyList())
        }
    }

    /** The region of the page (PDF points) that is shown: the whole page, or its inked area when cropping. */
    @Synchronized
    fun area(index: Int, crop: Boolean): RectF? = if (closed) null else renderer.openPage(index).use { areaOf(it, crop) }

    private fun areaOf(page: PdfRenderer.Page, crop: Boolean) =
        if (crop) crops[page.index] ?: contentBounds(page).also { crops[page.index] = it }
        else RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())

    /** Low-res render to find where the ink is; used to crop white margins. */
    private fun contentBounds(page: PdfRenderer.Page): RectF {
        val full = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        val w = 200
        val k = w / full.width()
        val h = (full.height() * k).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, Matrix().apply { postScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            if ((c shr 16 and 0xff) < 230 || (c shr 8 and 0xff) < 230 || (c and 0xff) < 230) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < l || b < t) return full
        val pad = 4
        return RectF((l - pad).coerceAtLeast(0) / k, (t - pad).coerceAtLeast(0) / k, (r + pad).coerceAtMost(w) / k, (b + pad).coerceAtMost(h) / k)
    }

    @androidx.annotation.RequiresApi(35)
    private fun links(page: PdfRenderer.Page) =
        page.gotoLinks.map { Link(it.bounds, it.destination.pageNumber, null) } +
            page.linkContents.map { Link(it.bounds, null, it.uri) }

    @androidx.annotation.RequiresApi(35)
    @Synchronized
    fun text(index: Int): String =
        if (closed) "" else renderer.openPage(index).use { p -> p.textContents.joinToString("\n") { it.text } }

    @androidx.annotation.RequiresApi(35)
    @Synchronized
    fun search(index: Int, query: String): List<Hit> {
        if (closed) return emptyList()
        return renderer.openPage(index).use { p ->
            val matches = p.searchText(query)
            if (matches.isEmpty()) return@use emptyList()
            val text = p.textContents.joinToString("") { it.text }
            matches.map {
                val s = it.textStartIndex.coerceIn(0, text.length)
                Hit(index, it.bounds, text.substring((s - 40).coerceAtLeast(0), (s + query.length + 40).coerceAtMost(text.length)).replace('\n', ' '))
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        renderer.close()
    }
}

private val nightFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
)
private val sepiaFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(0.98f, 0f, 0f, 0f, 0f, 0f, 0.92f, 0f, 0f, 0f, 0f, 0f, 0.80f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)
private val themeNames = listOf("Day", "Night", "Sepia")
private val themeBackgrounds = listOf(Color(0xFFDADADA), Color(0xFF111111), Color(0xFFD9CBA9))

/** Called by the activity for volume keys; returns true if consumed. */
var volumeKeyHandler: ((Int) -> Boolean)? = null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(uri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val library = context.app.library
    val settings = context.app.settings
    val book = library.get(uri) ?: library.add(Uri.parse(uri))
    val doc = remember { runCatching { PdfDoc.open(context, Uri.parse(uri)) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    BackHandler(onBack = onBack)

    if (doc == null) {
        AlertDialog(
            onDismissRequest = onBack,
            confirmButton = { TextButton(onClick = onBack) { Text("OK") } },
            title = { Text("Can't open document") },
            text = { Text("The file is missing, damaged, or access to it was revoked.") },
        )
        return
    }

    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(book.page.coerceIn(0, doc.pageCount - 1)) }
    var showUi by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var sheet by remember { mutableStateOf<String?>(null) } // contents | display
    var dialog by remember { mutableStateOf<String?>(null) } // goto | text | info | bookmark
    var searching by remember { mutableStateOf(false) }
    val hits = remember { mutableStateListOf<Hit>() }
    var hitIndex by remember { mutableIntStateOf(-1) }
    var searchBarBottom by remember { mutableIntStateOf(0) } // px from the top of the screen

    // One scroll state per mode, created at the current page so switching modes keeps your place.
    val listState = remember(settings.paged) { LazyListState(page) }
    val pagerState = remember(settings.paged) { PagerState(page) { doc.pageCount } }

    fun goTo(target: Int) {
        val p = target.coerceIn(0, doc.pageCount - 1)
        scope.launch { if (settings.paged) pagerState.scrollToPage(p) else listState.scrollToItem(p) }
    }

    // Scroll mode: put the match a third of the way down the screen instead of just showing its page's top.
    fun goToHit(hit: Hit) = scope.launch {
        if (settings.paged) return@launch pagerState.scrollToPage(hit.page)
        listState.scrollToItem(hit.page)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == hit.page } ?: return@launch
        val area = withContext(Dispatchers.IO) { doc.area(hit.page, settings.crop) } ?: return@launch
        val y = (hit.bounds.minOf { it.top } - area.top) / area.height() * item.size
        listState.scrollToItem(hit.page, (y - listState.layoutInfo.viewportSize.height / 3f).roundToInt().coerceAtLeast(0))
    }

    LaunchedEffect(settings.paged) {
        if (settings.paged) snapshotFlow { pagerState.currentPage }.collect { page = it }
        else snapshotFlow { listState.firstVisibleItemIndex }.collect { page = it }
    }
    LaunchedEffect(Unit) {
        library.update(uri) {
            it.copy(pages = doc.pageCount, opened = System.currentTimeMillis(),
                status = if (it.status == Status.FINISHED) it.status else Status.READING)
        }
    }
    LaunchedEffect(page) {
        delay(500) // debounce position saves while scrolling
        library.update(uri) { it.copy(page = page, opened = System.currentTimeMillis()) }
    }

    WindowEffects(showUi, settings)

    // Volume keys turn pages.
    DisposableEffect(settings.volumeKeys, settings.paged) {
        volumeKeyHandler = { key ->
            val dir = when (key) { KeyEvent.KEYCODE_VOLUME_DOWN -> 1; KeyEvent.KEYCODE_VOLUME_UP -> -1; else -> 0 }
            if (!settings.volumeKeys || dir == 0) false else {
                scope.launch {
                    if (settings.paged) pagerState.animateScrollToPage((pagerState.currentPage + dir).coerceIn(0, doc.pageCount - 1))
                    else listState.animateScrollBy(dir * listState.layoutInfo.viewportSize.height * 0.9f)
                }
                true
            }
        }
        onDispose { volumeKeyHandler = null }
    }

    val filter = when (settings.theme) { 1 -> nightFilter; 2 -> sepiaFilter; else -> null }
    val bookmarked = library.get(uri)?.bookmarks?.any { it.page == page } == true

    Box(Modifier.fillMaxSize().background(themeBackgrounds[settings.theme])) {
        val contentTop = if (searching) with(LocalDensity.current) { searchBarBottom.toDp() } else 0.dp
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = contentTop).clipToBounds()) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            // ponytail: two render resolutions (1x, 2x); add more steps if users zoom to 5x often.
            val renderWidth = if (scale > 1.5f) (width * 2).coerceAtMost(2400) else width

            fun onPageTap(x: Float, pageWidth: Int) {
                when {
                    settings.paged && scale == 1f && x < pageWidth * 0.25f -> goTo(page - 1)
                    settings.paged && scale == 1f && x > pageWidth * 0.75f -> goTo(page + 1)
                    else -> showUi = !showUi
                }
            }

            val pageView: @Composable (Int, Modifier) -> Unit = { i, mod ->
                PdfPage(
                    doc, i, renderWidth, settings.crop, filter,
                    highlights = hits.filter { it.page == i },
                    current = hits.getOrNull(hitIndex)?.takeIf { it.page == i },
                    onTap = ::onPageTap,
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                    onLink = { link ->
                        link.page?.let(::goTo)
                        link.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, it)) } }
                    },
                    modifier = mod,
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    // Initial pass: catch pinches before the list/pager; single-finger drags still scroll them.
                    .pointerInput(width, height, settings.paged) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size > 1) {
                                    scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    event.changes.forEach { it.consume() }
                                }
                                val maxX = width * (scale - 1) / 2
                                val maxY = if (settings.paged) height * (scale - 1) / 2 else 0f
                                val pan = event.calculatePan()
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                if (settings.paged) {
                    HorizontalPager(pagerState, Modifier.fillMaxSize(), userScrollEnabled = scale == 1f, beyondViewportPageCount = 1) { i ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            pageView(i, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(doc.pageCount) { i -> pageView(i, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }

        // Top bar
        AnimatedVisibility(showUi || searching, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (searching) {
                        SearchBar(doc, hits, hitIndex, onBarBottom = { searchBarBottom = it }, onIndex = { hitIndex = it; hits.getOrNull(it)?.let(::goToHit) }, onClose = {
                            searching = false; hits.clear(); hitIndex = -1
                        })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Text(book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (textApi) IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                            IconButton(onClick = {
                                if (bookmarked) library.update(uri) { b -> b.copy(bookmarks = b.bookmarks.filter { it.page != page }) }
                                else dialog = "bookmark"
                            }) { Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Bookmark") }
                            IconButton(onClick = { sheet = "contents" }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                            IconButton(onClick = { sheet = "display" }) { Icon(Icons.Default.Tune, "Display settings") }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem({ Text("Go to page") }, onClick = { menu = false; dialog = "goto" })
                                    if (textApi) DropdownMenuItem({ Text("Page text") }, onClick = { menu = false; dialog = "text" })
                                    DropdownMenuItem({ Text("Mark as have read") }, onClick = {
                                        menu = false; library.update(uri) { it.copy(status = Status.FINISHED) }
                                    })
                                    DropdownMenuItem({ Text("Share") }, onClick = { menu = false; share(context, uri) })
                                    DropdownMenuItem({ Text("Document info") }, onClick = { menu = false; dialog = "info" })
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom bar: page slider
        AnimatedVisibility(showUi, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp)) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { goTo(page - 1) }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous page") }
                        Slider(
                            value = dragging ?: page.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { goTo(it.roundToInt()) }; dragging = null },
                            valueRange = 0f..(doc.pageCount - 1).coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { goTo(page + 1) }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next page") }
                    }
                    val shown = (dragging?.roundToInt() ?: page) + 1
                    Text(
                        "$shown / ${doc.pageCount}  ·  ${shown * 100 / doc.pageCount}%",
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Minimal page indicator while reading full-screen
        if (!showUi && !searching) {
            Text(
                "${page + 1} / ${doc.pageCount}",
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    when (sheet) {
        "contents" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ContentsSheet(doc, uri, page, onGo = { goTo(it); sheet = null })
        }
        "display" -> ModalBottomSheet(onDismissRequest = { sheet = null }) { DisplaySheet(settings) }
    }

    when (dialog) {
        "goto" -> GoToDialog(doc.pageCount, onDismiss = { dialog = null }) { goTo(it); dialog = null }
        "bookmark" -> TextInputDialog("Add bookmark", "Note (optional)", "Add", onDismiss = { dialog = null }) { note ->
            library.update(uri) { b -> b.copy(bookmarks = (b.bookmarks + Bookmark(page, note.trim())).sortedBy { it.page }) }
            dialog = null
        }
        "text" -> if (textApi) {
            val text by produceState("Loading…") { value = withContext(Dispatchers.IO) { doc.text(page) }.ifBlank { "No text on this page." } }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Page ${page + 1}") },
                text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
                confirmButton = {
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(book.title, text))
                        dialog = null
                    }) { Text("Copy all") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
        "info" -> InfoDialog(library.get(uri) ?: book, onDismiss = { dialog = null })
    }
}

@Composable
private fun PdfPage(
    doc: PdfDoc,
    index: Int,
    width: Int,
    crop: Boolean,
    filter: ColorFilter?,
    highlights: List<Hit>,
    current: Hit?,
    onTap: (Float, Int) -> Unit,
    onDoubleTap: () -> Unit,
    onLink: (Link) -> Unit,
    modifier: Modifier,
) {
    // produceState keeps the old bitmap on screen while a sharper one renders.
    val rendered by produceState<Rendered?>(null, index, width, crop) {
        value = withContext(Dispatchers.IO) { doc.render(index, width, crop) }
    }
    val r = rendered
    if (r == null) {
        Box(modifier.aspectRatio(1 / 1.414f).background(if (filter == nightFilter) Color.Black else Color.White))
        return
    }
    Image(
        r.bitmap.asImageBitmap(),
        contentDescription = "Page ${index + 1}",
        contentScale = ContentScale.FillWidth,
        colorFilter = filter,
        modifier = modifier
            .aspectRatio(r.bitmap.width.toFloat() / r.bitmap.height)
            .pointerInput(r) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { pos ->
                        val k = size.width / r.crop.width()
                        val x = pos.x / k + r.crop.left
                        val y = pos.y / k + r.crop.top
                        val link = r.links.firstOrNull { l -> l.bounds.any { it.contains(x, y) } }
                        if (link != null) onLink(link) else onTap(pos.x, size.width)
                    },
                )
            }
            .drawWithContent {
                drawContent()
                val k = size.width / r.crop.width()
                for (hit in highlights) for (b in hit.bounds) {
                    drawRect(
                        if (hit === current) Color(0x88FF6D00) else Color(0x66FFEB3B),
                        topLeft = Offset((b.left - r.crop.left) * k, (b.top - r.crop.top) * k),
                        size = Size(b.width() * k, b.height() * k),
                    )
                }
            },
    )
}

@Composable
private fun SearchBar(doc: PdfDoc, hits: MutableList<Hit>, index: Int, onBarBottom: (Int) -> Unit, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        hits.clear()
        onIndex(-1)
        if (query.length < 2 || !textApi) return@LaunchedEffect
        delay(400)
        running = true
        for (i in 0 until doc.pageCount) {
            val found = withContext(Dispatchers.IO) { doc.search(i, query) }
            ensureActive()
            hits += found
            if (index < 0 && found.isNotEmpty() && hits.size == found.size) onIndex(0)
        }
        running = false
    }
    Column {
        // The results list may cover the page; only the bar itself pushes the page down.
        Column(Modifier.onGloballyPositioned { onBarBottom(it.boundsInRoot().bottom.roundToInt()) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close search") }
                OutlinedTextField(query, { query = it }, Modifier.weight(1f).padding(vertical = 4.dp), singleLine = true, placeholder = { Text("Search in document") })
                IconButton(onClick = { if (hits.isNotEmpty()) onIndex((index - 1 + hits.size) % hits.size) }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous match") }
                IconButton(onClick = { if (hits.isNotEmpty()) onIndex((index + 1) % hits.size) }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next match") }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        hits.isEmpty() && running -> "Searching…"
                        hits.isEmpty() && query.length >= 2 -> "No matches"
                        hits.isEmpty() -> ""
                        else -> "${index + 1} of ${hits.size}${if (running) "+" else ""}"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (hits.isNotEmpty()) TextButton(onClick = { showList = !showList }) { Text(if (showList) "Hide results" else "All results") }
            }
        }
        if (showList) {
            LazyColumn(Modifier.height(280.dp)) {
                items(hits.size) { i ->
                    val h = hits[i]
                    ListItem(
                        headlineContent = { Text(h.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Page ${h.page + 1}") },
                        modifier = Modifier.clickable { onIndex(i); showList = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(doc: PdfDoc, uri: String, current: Int, onGo: (Int) -> Unit) {
    val library = LocalContext.current.app.library
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().height(520.dp)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Pages") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks") })
        }
        if (tab == 0) {
            val gridState = rememberLazyGridState(current)
            LazyVerticalGrid(GridCells.Adaptive(96.dp), state = gridState, contentPadding = PaddingValues(8.dp)) {
                items(doc.pageCount) { i ->
                    Column(
                        Modifier.padding(4.dp).clickable { onGo(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val thumb by produceState<Bitmap?>(null, i) { value = withContext(Dispatchers.IO) { doc.render(i, 180, false)?.bitmap } }
                        val t = thumb
                        if (t == null) Box(Modifier.fillMaxWidth().aspectRatio(1 / 1.414f).background(Color.White))
                        else Image(t.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else {
            val marks = library.get(uri)?.bookmarks.orEmpty()
            if (marks.isEmpty()) {
                Text("No bookmarks yet. Tap the bookmark icon while reading.", Modifier.padding(24.dp))
            }
            LazyColumn {
                items(marks) { m ->
                    ListItem(
                        headlineContent = { Text("Page ${m.page + 1}") },
                        supportingContent = {
                            Text((if (m.note.isNotBlank()) m.note + "\n" else "") + DateFormat.format("yyyy-MM-dd HH:mm", m.created))
                        },
                        trailingContent = {
                            IconButton(onClick = { library.update(uri) { b -> b.copy(bookmarks = b.bookmarks - m) } }) {
                                Icon(Icons.Default.Delete, "Delete bookmark")
                            }
                        },
                        modifier = Modifier.clickable { onGo(m.page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplaySheet(settings: Settings) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading mode", style = MaterialTheme.typography.titleSmall)
        Chips(listOf("Scroll", "Pages"), if (settings.paged) 1 else 0) { settings.paged = it == 1 }
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Chips(themeNames, settings.theme) { settings.theme = it }
        Text("Screen orientation", style = MaterialTheme.typography.titleSmall)
        Chips(listOf("Auto", "Portrait", "Landscape"), settings.orientation) { settings.orientation = it }
        HorizontalDivider()
        SwitchRow("Crop margins", settings.crop) { settings.crop = it }
        SwitchRow("Keep screen on", settings.keepScreenOn) { settings.keepScreenOn = it }
        SwitchRow("Turn pages with volume keys", settings.volumeKeys) { settings.volumeKeys = it }
        SwitchRow("System brightness", settings.brightness < 0) { settings.brightness = if (it) -1f else 0.5f }
        if (settings.brightness >= 0) {
            Slider(settings.brightness, { settings.brightness = it }, valueRange = 0.01f..1f)
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label -> FilterChip(i == selected, { onSelect(i) }, { Text(label) }) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun GoToDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit) },
                label = { Text("1 – $pageCount") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { n?.let { onGo(it - 1) } }, enabled = n != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, label: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Format: PDF")
                if (book.size > 0) Text("Size: ${Formatter.formatFileSize(context, book.size)}")
                if (book.pages > 0) Text("Pages: ${book.pages}  ·  read ${(book.progress * 100).roundToInt()}%")
                Text("Status: ${book.status.label}")
                if (book.collections.isNotEmpty()) Text("Collections: ${book.collections.joinToString()}")
                Text("Bookmarks: ${book.bookmarks.size}")
                Text("Added: ${DateFormat.format("yyyy-MM-dd", book.added)}")
                if (book.opened > 0) Text("Last opened: ${DateFormat.format("yyyy-MM-dd HH:mm", book.opened)}")
                Spacer(Modifier.height(4.dp))
                Text(Uri.decode(book.uri), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

fun share(context: Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share document"))
}

/** Full-screen when the reader UI is hidden, plus brightness, keep-awake and orientation lock. */
@Composable
private fun WindowEffects(showUi: Boolean, settings: Settings) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val window = activity.window
    DisposableEffect(showUi) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showUi) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(settings.brightness) {
        window.attributes = window.attributes.apply { screenBrightness = settings.brightness }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = -1f } }
    }
    DisposableEffect(settings.orientation) {
        activity.requestedOrientation = when (settings.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
