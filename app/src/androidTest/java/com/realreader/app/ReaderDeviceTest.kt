package com.realreader.app
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun nativeNavigationHasHomeAndBack() {
  compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Search series or filename").assertExists()
  compose.onNodeWithContentDescription("Home",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Digital comics. Real reading.").assertExists()
  compose.onNodeWithText("Settings",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Library folders").assertExists()
  compose.onNodeWithContentDescription("Back",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Digital comics. Real reading.").assertExists()
 }
 @Test fun realArchiveImportAndProgressSurviveReopen() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val file=File(context.cacheDir,"Fixture #2.cbz")
  ZipOutputStream(file.outputStream()).use {zip ->
   for(n in listOf(10,2,1)){zip.putNextEntry(ZipEntry("page$n.png"));val b=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888);b.eraseColor(if(n==1)Color.RED else Color.BLUE);b.compress(Bitmap.CompressFormat.PNG,100,zip);b.recycle();zip.closeEntry()}
  }
  val store=LibraryStore(context);val uri=Uri.fromFile(file);store.db.add(uri,file.name)
  val comic=store.db.all().first {it.uri==uri.toString()}
  ComicDocument(context,comic).use {doc ->assertEquals(3,doc.count);val b=doc.page(0);assertEquals(Color.RED,b.getPixel(0,0));b.recycle();store.db.position(comic.id,1,doc.count)}
  store.db.marks(comic.id,"[1]");store.db.close()
  val reopened=LibraryDb(context);val saved=reopened.all().first {it.id==comic.id};assertEquals(1,saved.page);assertEquals("[1]",saved.marks);reopened.remove(comic.id);reopened.close();file.delete()
 }
 @Test fun corruptArchiveProducesControlledError() {
  val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"broken.cbz");file.writeText("bad archive")
  val c=Comic(0,Uri.fromFile(file).toString(),file.name,"Broken",0.0,0,0,0,0,"[]")
  assertThrows(Exception::class.java){ComicDocument(context,c)};file.delete()
 }
}
