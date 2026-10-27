package com.example.pdfreader

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class App : Application() {
    val library by lazy { Library(this) }
    val settings by lazy { Settings(this) }
}

val Context.app get() = applicationContext as App

enum class Status(val label: String) { NONE("No status"), TO_READ("To read"), READING("Reading now"), FINISHED("Have read") }

data class Bookmark(val page: Int, val note: String = "", val created: Long = System.currentTimeMillis())

data class Book(
    val uri: String,
    val title: String,
    val size: Long = 0,
    val pages: Int = 0,
    val page: Int = 0,
    val status: Status = Status.NONE,
    val favorite: Boolean = false,
    val collections: Set<String> = emptySet(),
    val bookmarks: List<Bookmark> = emptyList(),
    val added: Long = System.currentTimeMillis(),
    val opened: Long = 0,
) {
    val progress get() = if (pages <= 1) 0f else page / (pages - 1f)
}

private const val MEDIA_PREFIX = "content://media/"

class Library(private val context: Context) {
    val books = mutableStateListOf<Book>()
    val collections = mutableStateListOf<String>()
    var scanning by mutableStateOf(false)
        private set

    private val file = File(context.filesDir, "library.json")

    init {
        runCatching { load() }
    }

    fun get(uri: String) = books.firstOrNull { it.uri == uri }

    fun update(uri: String, change: (Book) -> Book) {
        val i = books.indexOfFirst { it.uri == uri }
        if (i >= 0) {
            books[i] = change(books[i])
            save()
        }
    }

    /** Adds a single document (picked file or "Open with"); returns its library entry. */
    fun add(uri: Uri): Book {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        get(uri.toString())?.let { return it }
        var title = uri.lastPathSegment ?: "Document"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) { title = c.getString(0) ?: title; size = c.getLong(1) } }
        }
        val book = Book(uri.toString(), title.removeSuffix(".pdf").removeSuffix(".PDF"), size)
        books.add(book)
        save()
        return book
    }

    fun remove(uri: String) {
        books.removeAll { it.uri == uri }
        File(context.cacheDir, "covers/${uri.hashCode()}.png").delete()
        save()
    }

    fun addCollection(name: String) {
        if (name.isNotBlank() && name !in collections) collections.add(name.trim())
        save()
    }

    fun removeCollection(name: String) {
        collections.remove(name)
        books.replaceAll { it.copy(collections = it.collections - name) }
        save()
    }

    /** All-files access (Android 11+) or storage read permission (older). */
    fun hasStorageAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Finds every PDF on the device through MediaStore and syncs the library with it. Call from the main thread. */
    suspend fun scan() {
        if (scanning || !hasStorageAccess()) return
        scanning = true
        val found = withContext(Dispatchers.IO) { runCatching { queryPdfs() }.getOrNull() }
        if (found != null) {
            val onDevice = found.mapTo(HashSet()) { it.uri }
            books.removeAll { it.uri.startsWith(MEDIA_PREFIX) && it.uri !in onDevice } // deleted from the phone
            val known = books.mapTo(HashSet()) { it.uri }
            books.addAll(found.filter { it.uri !in known })
            save()
        }
        scanning = false
    }

    // ponytail: relies on MediaStore's index, so a PDF copied in seconds ago may show up on the next scan; walk the filesystem if that matters.
    private fun queryPdfs(): List<Book> {
        val files = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE)
        val where = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val out = mutableListOf<Book>()
        context.contentResolver.query(files, cols, where, arrayOf("application/pdf", "%.pdf"), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Book(ContentUris.withAppendedId(files, c.getLong(0)).toString(), name.substringBeforeLast('.'), c.getLong(2))
            }
        }
        return out
    }

    /** First-page thumbnail, cached on disk. Also fills in the page count the first time. */
    suspend fun cover(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "covers/${uri.hashCode()}.png")
        if (f.exists()) return@withContext BitmapFactory.decodeFile(f.path)
        runCatching {
            PdfDoc.open(context, Uri.parse(uri))!!.use { doc ->
                val bmp = doc.render(0, 240, crop = false)!!.bitmap
                f.parentFile!!.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                val pages = doc.pageCount
                withContext(Dispatchers.Main) { update(uri) { it.copy(pages = pages) } }
                bmp
            }
        }.getOrNull()
    }

    // ponytail: whole library rewritten as JSON on every change; move to Room if libraries reach thousands of books.
    private fun save() {
        val json = JSONObject()
            .put("collections", JSONArray(collections))
            .put("books", JSONArray(books.map { b ->
                JSONObject()
                    .put("uri", b.uri).put("title", b.title).put("size", b.size)
                    .put("pages", b.pages).put("page", b.page).put("status", b.status.name)
                    .put("favorite", b.favorite).put("collections", JSONArray(b.collections))
                    .put("added", b.added).put("opened", b.opened)
                    .put("bookmarks", JSONArray(b.bookmarks.map {
                        JSONObject().put("page", it.page).put("note", it.note).put("created", it.created)
                    }))
            }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        collections += json.getJSONArray("collections").strings()
        val arr = json.getJSONArray("books")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val marks = o.getJSONArray("bookmarks")
            books += Book(
                uri = o.getString("uri"),
                title = o.getString("title"),
                size = o.optLong("size"),
                pages = o.optInt("pages"),
                page = o.optInt("page"),
                status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.NONE),
                favorite = o.optBoolean("favorite"),
                collections = o.getJSONArray("collections").strings().toSet(),
                added = o.optLong("added"),
                opened = o.optLong("opened"),
                bookmarks = (0 until marks.length()).map { j ->
                    val m = marks.getJSONObject(j)
                    Bookmark(m.getInt("page"), m.optString("note"), m.optLong("created"))
                },
            )
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

/** Reader and library preferences, backed by SharedPreferences and observable from Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var paged by pref(false)
    var theme by pref(0) // 0 day, 1 night, 2 sepia
    var crop by pref(false)
    var keepScreenOn by pref(true)
    var volumeKeys by pref(true)
    var orientation by pref(0) // 0 auto, 1 portrait, 2 landscape
    var brightness by pref(-1f) // -1 = system
    var sort by pref(0)
    var grid by pref(true) // library as cards instead of a list

    @Suppress("UNCHECKED_CAST")
    private fun <T> pref(default: T) = object : ReadWriteProperty<Any?, T> {
        private var state: androidx.compose.runtime.MutableState<T>? = null

        private fun state(key: String) = state ?: mutableStateOf(
            when (default) {
                is Boolean -> prefs.getBoolean(key, default)
                is Int -> prefs.getInt(key, default)
                is Float -> prefs.getFloat(key, default)
                else -> error("unsupported")
            } as T,
        ).also { state = it }

        override fun getValue(thisRef: Any?, property: KProperty<*>) = state(property.name).value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state(property.name).value = value
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(property.name, value)
                    is Int -> putInt(property.name, value)
                    is Float -> putFloat(property.name, value)
                }
            }.apply()
        }
    }
}
