package com.realreader.app
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
 @Test fun actualComicTurnsBookmarksAndResumes() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val file=File(context.filesDir,"Pepper and Carrot #1.cbz")
  ZipOutputStream(file.outputStream()).use {zip ->for(n in 1..3){zip.putNextEntry(ZipEntry("page$n.jpg"));instrumentation.context.assets.open("comic/page$n.jpg").use{it.copyTo(zip)};zip.closeEntry()}}
  val store=LibraryStore(context);store.db.add(Uri.fromFile(file),file.name)
  compose.activityRule.scenario.recreate()
  compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Pepper and Carrot #1.cbz").performClick()
  compose.onNodeWithText("Start Reading").performClick()
  compose.waitUntil(30000) {store.db.all().first{it.name==file.name}.pages==3}
  Thread.sleep(500)
  fun proof(name:String){val dir=File(context.getExternalFilesDir(null),"proof");dir.mkdirs();val image=instrumentation.uiAutomation.takeScreenshot();val output=File(dir,"$name.png");output.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle();instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/real-reader-proof && cp ${output.absolutePath} /sdcard/Download/real-reader-proof/$name.png").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
  proof("reader-single")
  val w=compose.activity.window.decorView.width.toFloat();val h=compose.activity.window.decorView.height.toFloat();val now=android.os.SystemClock.uptimeMillis()
  instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,w*.9f,h*.5f,0))
  instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+150,android.view.MotionEvent.ACTION_MOVE,w*.65f,h*.53f,0))
  Thread.sleep(200);proof("reader-held-curl")
  instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+400,android.view.MotionEvent.ACTION_CANCEL,w*.65f,h*.53f,0));Thread.sleep(600)
  assertEquals(0,store.db.all().first{it.name==file.name}.page)
  compose.onRoot().performTouchInput {swipeLeft()}
  compose.waitUntil(30000) {store.db.all().first{it.name==file.name}.page==1}
  compose.onRoot().performTouchInput {click(center)}
  compose.onNodeWithText("2 / 3").assertExists()
  compose.onNodeWithContentDescription("Bookmark page",useUnmergedTree=true).performClick()
  compose.onNodeWithContentDescription("Home",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Continue Reading").assertExists()
  proof("home-continue")
  compose.onNodeWithText("Continue Reading").performClick()
  compose.waitUntil(30000) {store.db.all().first{it.name==file.name}.page==1}
  compose.onNodeWithText("2 / 3").assertExists()
  compose.onNodeWithText("Bookmarked").assertExists()
  compose.onNodeWithContentDescription("Home",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
  proof("library")
  store.db.remove(store.db.all().first{it.name==file.name}.id);store.db.close();file.delete()
 }
 @Test fun nestedSafScanIsIdempotentAndPreservesOriginals() {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
  val tree=android.provider.DocumentsContract.buildTreeDocumentUri("com.realreader.fixture.documents","root")
  val store=LibraryStore(context);val oldFolders=store.folders();store.prefs.edit().putStringSet("folders",setOf(tree.toString())).commit()
  try {store.scan();store.scan();val found=store.db.all().filter{it.name=="Test #1.cbz"};assertEquals(1,found.size);assertTrue(androidx.documentfile.provider.DocumentFile.fromSingleUri(context,Uri.parse(found.first().uri))!!.exists());assertTrue(found.first().uri.startsWith("content://"));assertEquals("Test",found.first().series);store.db.remove(found.first().id)}
  finally {store.prefs.edit().putStringSet("folders",oldFolders).commit();store.db.close();instrumentation.uiAutomation.dropShellPermissionIdentity()}
 }
 @Test fun pdfUsesNativeRenderer() {
  val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"Fixture.pdf")
  val pdf=android.graphics.pdf.PdfDocument();val p=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(120,180,1).create());p.canvas.drawColor(Color.GREEN);pdf.finishPage(p);file.outputStream().use{pdf.writeTo(it)};pdf.close()
  val c=Comic(0,Uri.fromFile(file).toString(),file.name,"PDF",0.0,0,0,0,0,"[]")
  ComicDocument(context,c).use{doc->assertEquals(1,doc.count);val bitmap=doc.page(0,256);assertEquals(Color.GREEN,bitmap.getPixel(10,10));bitmap.recycle()};file.delete()
 }
 @Test fun corruptArchiveProducesControlledError() {
  val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"broken.cbz");file.writeText("bad archive")
  val c=Comic(0,Uri.fromFile(file).toString(),file.name,"Broken",0.0,0,0,0,0,"[]")
  assertThrows(Exception::class.java){ComicDocument(context,c)};file.delete()
 }
}
