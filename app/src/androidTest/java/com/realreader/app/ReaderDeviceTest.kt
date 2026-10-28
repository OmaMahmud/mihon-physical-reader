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
 @Test fun completedTurnHoldsDestinationUntilTexturesArrive() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  lateinit var reader: PhysicalReader
  val turned=java.util.concurrent.CountDownLatch(1)
  fun image(color:Int)=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
  instrumentation.runOnMainSync {
   reader=PhysicalReader(context)
   compose.activity.setContentView(reader)
   reader.onTurn={turned.countDown()}
   reader.frames(image(Color.RED),image(Color.BLUE),null)
  }
  val deadline=android.os.SystemClock.uptimeMillis()+10000
  while(!reader.ready && android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(20)
  assertTrue(reader.ready)
  instrumentation.runOnMainSync {reader.turn(1)}
  assertTrue(turned.await(10,java.util.concurrent.TimeUnit.SECONDS))
  Thread.sleep(200) // Deliberately withhold new frames to reproduce the decode/upload gap.
  fun centerColor():Int {
   val output=Bitmap.createBitmap(reader.width,reader.height,Bitmap.Config.ARGB_8888)
   val copied=java.util.concurrent.CountDownLatch(1);var status=-1
   android.view.PixelCopy.request(reader,output,{status=it;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
   assertTrue(copied.await(5,java.util.concurrent.TimeUnit.SECONDS))
   if(status!=android.view.PixelCopy.SUCCESS){output.recycle();return Color.TRANSPARENT}
   return output.getPixel(output.width/2,output.height/2).also {output.recycle()}
  }
  val visibleDeadline=android.os.SystemClock.uptimeMillis()+15000
  var color=Color.RED
  while(android.os.SystemClock.uptimeMillis()<visibleDeadline) {
   instrumentation.runOnMainSync {reader.requestRender()}
   Thread.sleep(100)
   color=centerColor()
   if(color==Color.BLUE)break
  }
  assertEquals("Destination must remain visible while decoding",Color.BLUE,color)
  instrumentation.runOnMainSync {reader.frames(image(Color.BLUE),image(Color.GREEN),image(Color.RED))}
  Thread.sleep(200)
  val replacedDeadline=android.os.SystemClock.uptimeMillis()+10000
  var replaced=Color.TRANSPARENT
  while(android.os.SystemClock.uptimeMillis()<replacedDeadline) {
   instrumentation.runOnMainSync {reader.requestRender()};Thread.sleep(100)
   replaced=centerColor();if(replaced==Color.BLUE)break
  }
  assertEquals("Texture replacement must keep the destination",Color.BLUE,replaced)
  instrumentation.runOnMainSync {reader.dispose()}
 }
 @Test fun libraryGroupsIssuesBeforeOpeningSeries() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val store=LibraryStore(context)
  val names=listOf("Absolute Superman #1.cbz","Absolute Superman #2.cbz")+(1..4).map {"Absolute Batman #$it.cbz"}
  val uris=names.map {Uri.fromFile(File(context.cacheDir,it))}
  try {
   names.zip(uris).forEach {(name,uri)->store.db.add(uri,name)}
   compose.activityRule.scenario.recreate()
   compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
   compose.onAllNodesWithText("Absolute Superman",substring=false).assertCountEquals(1)
   compose.onAllNodesWithText("Absolute Batman",substring=false).assertCountEquals(1)
   compose.onNodeWithText("2 issues").assertExists()
   compose.onNodeWithText("4 issues").assertExists()
   compose.onNodeWithText("Absolute Superman",substring=false).performClick()
   compose.onNodeWithText("Issues in this series").assertExists()
   compose.onNodeWithText("Absolute Superman #2.cbz").assertExists()
  } finally {
   store.db.all().filter {c->uris.any {it.toString()==c.uri}}.forEach {store.db.remove(it.id)}
   store.db.close()
  }
 }
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
  compose.onNodeWithText("Pepper and Carrot",substring=false).performClick()
  compose.onNodeWithText("Start Reading").performClick()
  compose.waitUntil(30000) {store.db.all().first{it.name==file.name}.pages==3}
  Thread.sleep(500)
  fun proof(name:String){
   compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(150)
   val ui=instrumentation.uiAutomation
   ui.executeShellCommand("mkdir -p /sdcard/Download/real-reader-proof").let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}
   val image=ui.takeScreenshot()
   val pipes=ui.executeShellCommandRw("dd of=/sdcard/Download/real-reader-proof/$name.png")
   android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
   android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use{it.readBytes()};image.recycle()
  }
  proof("reader-single")
  compose.waitUntil(15000) {compose.activity.hasWindowFocus()}
  val w=compose.activity.window.decorView.width.toFloat();val h=compose.activity.window.decorView.height.toFloat();val now=android.os.SystemClock.uptimeMillis()
  instrumentation.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,w*.9f,h*.5f,0),true)
  instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+150,android.view.MotionEvent.ACTION_MOVE,w*.65f,h*.53f,0),true)
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
  assertFalse(store.db.all().first{it.name==file.name}.finished)
  proof("home-continue")
  compose.onNodeWithText("Continue Reading").performClick()
  compose.waitUntil(30000) {compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().size==1}
  compose.onNodeWithText("2 / 3").assertExists()
  compose.onNodeWithText("Bookmarked").assertExists()
  compose.onNodeWithContentDescription("Home",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Search series or filename").assertExists()
  proof("library")
  store.db.remove(store.db.all().first{it.name==file.name}.id);store.db.close();file.delete()
 }
 @Test fun nestedSafScanIsIdempotentAndPreservesOriginals() {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  instrumentation.uiAutomation.executeShellCommand("am broadcast --receiver-foreground -f 0x20 -n com.realreader.app.test/com.realreader.app.FixtureGrantReceiver").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
  val tree=android.provider.DocumentsContract.buildTreeDocumentUri("com.realreader.fixture.documents","root")
  val store=LibraryStore(context);val oldFolders=store.folders();store.prefs.edit().putStringSet("folders",setOf(tree.toString())).commit()
  try {val first=store.scan();val second=store.scan();val found=store.db.all().filter{it.name=="Test #1.cbz"};assertEquals("$first / $second",1,found.size);assertTrue(androidx.documentfile.provider.DocumentFile.fromSingleUri(context,Uri.parse(found.first().uri))!!.exists());assertTrue(found.first().uri.startsWith("content://"));assertEquals("Test",found.first().series);store.db.remove(found.first().id)}
  finally {store.prefs.edit().putStringSet("folders",oldFolders).commit();store.db.close()}
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
