package com.realreader.app
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
/** Instrumentation APK only: genuine DocumentsProvider contract, never in the app manifest. */
class FixtureDocuments: DocumentsProvider() {
 private fun root()=File(context!!.filesDir,"saf-fixture").apply{mkdirs()}
 private fun file(id:String):File {val f=if(id=="root")root() else File(root(),id.removePrefix("root/"));require(f.canonicalPath.startsWith(root().canonicalPath));return f}
 override fun isChildDocument(parentDocumentId:String,documentId:String)=file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath+"/")
 override fun onCreate():Boolean {val nested=File(root(),"nested").apply{mkdirs()};File(nested,"Test #1.cbz").writeText("Index fixture");File(nested,"unrelated.txt").writeText("ignored");return true}
 override fun queryRoots(projection:Array<out String>?):Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)).apply{addRow(arrayOf("root","root","Comic fixture",Root.FLAG_LOCAL_ONLY))}
 private fun cursor()=MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE))
 private fun add(c:MatrixCursor,f:File){val id=if(f==root())"root" else "root/"+f.relativeTo(root()).path;c.addRow(arrayOf(id,f.name,if(f.isDirectory)Document.MIME_TYPE_DIR else "application/octet-stream",0,f.length()))}
 override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor=cursor().apply{add(this,file(documentId))}
 override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor=cursor().apply{file(parentDocumentId).listFiles()?.forEach{add(this,it)}}
 override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.MODE_READ_ONLY)
}
