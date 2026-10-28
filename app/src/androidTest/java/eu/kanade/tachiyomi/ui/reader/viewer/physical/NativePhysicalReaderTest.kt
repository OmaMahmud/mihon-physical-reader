package eu.kanade.tachiyomi.ui.reader.viewer.physical

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class NativePhysicalReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val graph = context.metroGraph<AppGraph>()
    private val proof = File(context.getExternalFilesDir(null), "native-physical-proof").apply { mkdirs() }

    @Test
    fun realCbzUsesNativeCurlControlsProgressAndLifecycle() {
        val root = File(context.getExternalFilesDir(null), "native-reader-test").apply { mkdirs() }
        val folder = File(root, "local/Native Curl Proof").apply { mkdirs() }
        makeChapter(File(folder, "001.cbz"))
        makeChapter(File(folder, "002.cbz"))
        graph.storagePreferences.baseStorageDirectory.set(root.toUri().toString())
        graph.basePreferences.shownOnboardingFlow.set(true)
        graph.readerPreferences.defaultReadingMode.set(ReadingMode.LEFT_TO_RIGHT.flagValue)
        graph.readerPreferences.defaultOrientationType.set(ReaderOrientation.FREE.flagValue)
        graph.readerPreferences.showNavigationOverlayNewUser.set(false)
        graph.readerPreferences.showNavigationOverlayOnStart.set(false)
        graph.readerPreferences.fullscreen.set(true)
        val manga = runBlocking {
            graph.mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(url = "Native Curl Proof", title = "Native Curl Proof", source = 0, initialized = true)),
            ).single()
        }
        val chapters = runBlocking {
            graph.chapterRepository.updateFromRemote(
                emptyList(),
                listOf(
                    Chapter.create().copy(mangaId = manga.id, url = "Native Curl Proof/001.cbz", name = "Chapter 1", chapterNumber = 1.0, sourceOrder = 1),
                    Chapter.create().copy(mangaId = manga.id, url = "Native Curl Proof/002.cbz", name = "Chapter 2", chapterNumber = 2.0, sourceOrder = 0),
                ),
                emptyList(),
            )
        }
        val first = chapters.first { it.name == "Chapter 1" }
        SystemClock.sleep(500)
        val intent = ReaderActivity.newIntent(context, manga.id, first.id)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            waitFor(scenario) { it.viewModel.state.value.currentPage == 1 && findSurface(it.window.decorView) != null }
            scenario.onActivity {
                val surface = checkNotNull(findSurface(it.window.decorView))
                assertTrue("Native renderer must fill the reader", surface.height > it.window.decorView.height * 0.85)
                assertFalse("No embedded webpage", containsWebView(it.window.decorView))
                assertEquals(PhysicalBookViewer::class.java, ReadingMode.toViewer(ReadingMode.LEFT_TO_RIGHT.flagValue, it)::class.java)
                it.hideMenu()
            }
            SystemClock.sleep(600)
            capture("01-native-portrait")
            val before = AtomicBoolean(false)
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            waitFor(scenario) { it.viewModel.state.value.menuVisible.also(before::set) }
            assertTrue("Center tap reveals Mihon's controls", before.get())
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            waitFor(scenario) { !it.viewModel.state.value.menuVisible }
            holdCurlAndCapture(scenario)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 2 }
            swipe(false)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 1 }
            swipe(true)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 2 }
            repeat(5) { swipe(true); SystemClock.sleep(50) }
            SystemClock.sleep(900)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp }
            SystemClock.sleep(1000)
            capture("03-native-book-layout")
            assertFalse("No webpage after rotation", containsWebViewOnUi(scenario))
            scenario.onActivity { it.viewModel.saveHistory() }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(scenario) { findSurface(it.window.decorView) != null }
            val adjacent = AtomicBoolean(false)
            scenario.onActivity { adjacent.set(it.viewModel.state.value.viewerChapters?.nextChapter != null) }
            assertTrue("Second chapter was loaded by Mihon", adjacent.get())
            scenario.onActivity { activity -> activity.lifecycleScopeForTestLoadNext() }
            waitFor(scenario) { it.viewModel.state.value.currentChapter?.chapter?.id != first.id }
            capture("04-native-next-chapter")
        }
        val saved = runBlocking { graph.chapterRepository.getChapterById(first.id) }
        assertNotNull(saved)
        assertTrue("Progress is saved through Mihon", saved!!.lastPageRead > 0)
        assertTrue("History is saved through Mihon", runBlocking { graph.historyRepository.getHistoryByMangaId(manga.id) }.isNotEmpty())
    }

    private fun ReaderActivity.lifecycleScopeForTestLoadNext() {
        lifecycleScope.launch { viewModel.loadNextChapter() }
    }

    private fun holdCurlAndCapture(scenario: ActivityScenario<ReaderActivity>) {
        var left = 0
        var top = 0
        var width = 0
        var height = 0
        scenario.onActivity {
            val surface = checkNotNull(findSurface(it.window.decorView))
            val location = IntArray(2)
            surface.getLocationOnScreen(location)
            left = location[0]
            top = location[1]
            width = surface.width
            height = surface.height
        }
        val down = SystemClock.uptimeMillis()
        val start = left + width * 0.87f
        val end = left + width * 0.16f
        val y = top + height * 0.62f
        fun input(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        input(MotionEvent.ACTION_DOWN, start)
        repeat(16) { step ->
            SystemClock.sleep(24)
            input(MotionEvent.ACTION_MOVE, start + (end - start) * (step + 1) / 16)
        }
        SystemClock.sleep(300)
        capture("02-native-held-page-curl")
        input(MotionEvent.ACTION_UP, end)
    }

    private fun swipe(forward: Boolean) {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(if (forward) w * 9 / 10 else w / 10, h * 3 / 5, if (forward) w / 10 else w * 9 / 10, h * 3 / 5, 18)
        SystemClock.sleep(550)
    }

    private fun waitFor(scenario: ActivityScenario<ReaderActivity>, condition: (ReaderActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < end) {
            val ready = AtomicBoolean(false)
            scenario.onActivity { ready.set(condition(it)) }
            if (ready.get()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("Native reader condition timed out; inspect logcat and screenshots")
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val scale = minOf(1f, 1000f / max(screenshot.width, screenshot.height))
        val small = Bitmap.createScaledBitmap(screenshot, (screenshot.width * scale).toInt(), (screenshot.height * scale).toInt(), true)
        File(proof, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (small !== screenshot) small.recycle()
        screenshot.recycle()
    }

    private fun findSurface(view: View): GLSurfaceView? {
        if (view is GLSurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun containsWebView(view: View): Boolean {
        if (view is WebView) return true
        return view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) }
    }
    private fun containsWebViewOnUi(scenario: ActivityScenario<ReaderActivity>): Boolean {
        val result = AtomicBoolean(false)
        scenario.onActivity { result.set(containsWebView(it.window.decorView)) }
        return result.get()
    }

    private fun makeChapter(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(9) { index ->
                val wide = index == 3 || index == 7
                val bitmap = Bitmap.createBitmap(if (wide) 1600 else 720, if (wide) 900 else 1080, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(intArrayOf(Color.rgb(230, 50, 50), Color.rgb(35, 170, 90), Color.rgb(50, 85, 220))[index % 3])
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 80f; strokeWidth = 12f }
                canvas.drawText("CHAPTER PAGE ${index + 1}", 30f, 130f, paint)
                paint.color = Color.BLACK
                for (x in 30 until bitmap.width step 180) canvas.drawLine(x.toFloat(), 180f, x.toFloat(), bitmap.height - 20f, paint)
                for (y in 180 until bitmap.height step 180) canvas.drawLine(30f, y.toFloat(), bitmap.width - 20f, y.toFloat(), paint)
                zip.putNextEntry(ZipEntry("${index.toString().padStart(3, '0')}.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bitmap.recycle()
            }
        }
    }
}
