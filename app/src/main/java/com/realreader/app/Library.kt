package com.realreader.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max

/** No network access: original files are retained at their SAF URI. */
data class Comic(val id: Long, val uri: String, val name: String, val series: String, val issue: Double,
    val pages: Int, val page: Int, val opened: Long, val added: Long, val marks: String, val completed: Boolean=false) {
    val finished get() = completed || pages > 0 && page >= pages - 1
}
object ComicNames {
    private val number = Regex("^(.*?)[ #_-]+(\\d+(?:\\.\\d+)?)(?:[ _-].*)?$")
    fun parse(name: String): Pair<String, Double> {
        val stem = name.substringBeforeLast('.').replace('_', ' ').trim()
        val m = number.matchEntire(stem)
        return if (m != null) m.groupValues[1].trim().ifEmpty { stem } to (m.groupValues[2].toDoubleOrNull() ?: 0.0) else stem to 0.0
    }
    fun image(n: String) = n.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")
    fun natural(n: String): String = Regex("\\d+").replace(n.lowercase()) { it.value.padStart(12, '0') }
}
class LibraryDb(context: Context) : SQLiteOpenHelper(context, "real-reader.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE comics(id INTEGER PRIMARY KEY,uri TEXT UNIQUE,name TEXT,series TEXT,issue REAL,pages INTEGER DEFAULT 0,page INTEGER DEFAULT 0,opened INTEGER DEFAULT 0,added INTEGER,marks TEXT DEFAULT '[]',finished INTEGER DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {if(old<2)db.execSQL("ALTER TABLE comics ADD COLUMN finished INTEGER DEFAULT 0")}
    @Synchronized fun all(): List<Comic> = readableDatabase.rawQuery("SELECT * FROM comics ORDER BY series COLLATE NOCASE,issue,name", null).use { c ->
        buildList { while (c.moveToNext()) add(Comic(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getDouble(4),c.getInt(5),c.getInt(6),c.getLong(7),c.getLong(8),c.getString(9),c.getInt(10)!=0)) }
    }
    @Synchronized fun add(uri: Uri, name: String) {
        val (series, issue) = ComicNames.parse(name)
        writableDatabase.execSQL("INSERT OR IGNORE INTO comics(uri,name,series,issue,added) VALUES(?,?,?,?,?)",arrayOf(uri.toString(),name,series,issue,System.currentTimeMillis()))
    }
    @Synchronized fun position(id: Long, page: Int, count: Int, done: Boolean=false) {
        writableDatabase.execSQL("UPDATE comics SET page=?,pages=?,opened=?,finished=MAX(finished,?) WHERE id=?",arrayOf(page.coerceIn(0,max(0,count-1)),count,System.currentTimeMillis(),if(done)1 else 0,id))
    }
    @Synchronized fun marks(id: Long, marks: String) { writableDatabase.execSQL("UPDATE comics SET marks=? WHERE id=?",arrayOf(marks,id)) }
    @Synchronized fun renameSeries(id: Long, series: String) { writableDatabase.execSQL("UPDATE comics SET series=? WHERE id=?",arrayOf(series.trim(),id)) }
    @Synchronized fun remove(id: Long) { writableDatabase.execSQL("DELETE FROM comics WHERE id=?",arrayOf(id)) }
}
class LibraryStore(val context: Context) {
    val db = LibraryDb(context)
    val prefs = context.getSharedPreferences("reader",Context.MODE_PRIVATE)
    fun formats() = prefs.getStringSet("formats",setOf("cbz","zip","jpg","jpeg","png","webp"))!!.toSet()
    fun folders() = prefs.getStringSet("folders",emptySet())!!.toSet()
    fun addFolder(uri: Uri) { prefs.edit().putStringSet("folders",folders()+uri.toString()).apply() }
    fun name(uri: Uri): String = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Comic"
    fun import(uri: Uri) { val n=name(uri); require(n.substringAfterLast('.').lowercase() in formats()) { "Format disabled or unsupported: $n" }; db.add(uri,n) }
    fun scan(): String {
        var found=0; val errors=mutableListOf<String>(); val allowed=formats()
        for (folder in folders()) {
            try {
                val root=DocumentFile.fromTreeUri(context,Uri.parse(folder)) ?: error("Folder unavailable")
                require(root.exists() && root.canRead()) { "Folder permission lost" }
                val pending=ArrayDeque<Pair<DocumentFile,Int>>(); pending.add(root to 0)
                val visited=HashSet<String>()
                while(pending.isNotEmpty()) {
                    val (dir,depth)=pending.removeFirst()
                    if(!visited.add(dir.uri.toString())) continue
                    require(depth < 64 && visited.size < 20000) { "Folder scan safety limit reached" }
                    for(file in dir.listFiles()) {
                        if(file.isDirectory) pending.add(file to depth+1)
                        else if(file.name?.substringAfterLast('.')?.lowercase() in allowed) { db.add(file.uri,file.name!!); found++ }
                    }
                }
            } catch(e: Exception) { errors.add(e.message ?: "Folder scan failed") }
        }
        return "Scan complete: $found supported files found." + if(errors.isEmpty()) "" else " ${errors.joinToString()}"
    }
}
class ScanWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result { val s=LibraryStore(applicationContext); return try { s.scan(); Result.success() } finally { s.db.close() } }
}
/** One temporary seekable copy; bounded bitmap decode, entries never extracted by archive path. */
class ComicDocument(private val context: Context, val comic: Comic): AutoCloseable {
    private var temp: File?=null
    private var zip: ZipFile?=null
    private var pdf: PdfRenderer?=null
    private var fd: ParcelFileDescriptor?=null
    private var entries: List<String> = emptyList()
    val count: Int
    init {
        try {
            val ext=comic.name.substringAfterLast('.').lowercase()
            if(ext in setOf("cbz","zip","pdf")) {
                val file=File.createTempFile("comic-",".$ext",context.cacheDir); temp=file
                context.contentResolver.openInputStream(Uri.parse(comic.uri)).use { input ->
                    requireNotNull(input) { "File permission unavailable" }
                    file.outputStream().use { out ->
                        val buffer=ByteArray(65536); var total=0L
                        while(true) { val n=input.read(buffer); if(n<0)break; total+=n; require(total<=1024L*1024*1024) { "Files over 1 GB are not supported in this alpha" }; out.write(buffer,0,n) }
                    }
                }
                if(ext=="pdf") { fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY); pdf=PdfRenderer(fd!!); count=pdf!!.pageCount }
                else { zip=ZipFile(file); entries=zip!!.entries().asSequence().filter { !it.isDirectory && ComicNames.image(it.name) && !it.name.startsWith("__MACOSX/") }.map { it.name }.sortedBy { ComicNames.natural(it) }.toList(); count=entries.size }
            } else { require(ComicNames.image(comic.name)) { "Unsupported file format" }; count=1 }
            require(count in 1..20000) { "No readable pages or too many pages" }
        } catch(e: Exception) { close(); throw e }
    }
    @Synchronized fun wide(index: Int): Boolean {
        if(pdf!=null)return pdf!!.openPage(index).use { it.width > it.height * 1.25f }
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        val stream=if(zip!=null)zip!!.getInputStream(zip!!.getEntry(entries[index])) else context.contentResolver.openInputStream(Uri.parse(comic.uri))
        stream.use {BitmapFactory.decodeStream(it,null,options)}
        return options.outWidth > options.outHeight*1.25f
    }
    @Synchronized fun page(index: Int, maxSide: Int=2048): Bitmap {
        val i=index.coerceIn(0,count-1)
        if(pdf!=null) return pdf!!.openPage(i).use { p ->
            val scale=maxSide.toFloat()/max(p.width,p.height)
            val b=Bitmap.createBitmap(max(1,(p.width*scale).toInt()),max(1,(p.height*scale).toInt()),Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE); p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b
        }
        fun stream() = if(zip!=null) zip!!.getInputStream(zip!!.getEntry(entries[i])) else requireNotNull(context.contentResolver.openInputStream(Uri.parse(comic.uri)))
        if(zip!=null) require(zip!!.getEntry(entries[i]).size in 1..(128L*1024*1024)) { "Page is too large or has an invalid archive size" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        stream().use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Unreadable image on page ${i+1}" }
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>maxSide)sample*=2
        val opts=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
        return stream().use { BitmapFactory.decodeStream(it,null,opts) } ?: error("Cannot decode page ${i+1}")
    }
    @Synchronized override fun close() { pdf?.close(); pdf=null; fd?.close(); fd=null; zip?.close();zip=null;temp?.delete();temp=null }
}
