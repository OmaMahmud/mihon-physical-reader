package eu.kanade.tachiyomi.ui.reader.viewer.physical

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLSurfaceView
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
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
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class NativePhysicalReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val graph = context.metroGraph<AppGraph>()
    private val proof = File(context.getExternalFilesDir(null), "native-physical-proof").apply { mkdirs() }

    @Test
    fun onlineLoaderRetriesAndReadsTheCacheAfterNetworkDisconnects() {
        val bytes = instrumentation.context.assets.open("real_comic/page1.jpg").use { it.readBytes() }
        val server = ServerSocket(0)
        val requests = AtomicInteger(0)
        val failing = AtomicBoolean(true)
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        requests.incrementAndGet()
                        val fail = failing.get()
                        val body = if (fail) byteArrayOf() else bytes
                        val status = if (fail) "500 Test retry" else "200 OK"
                        val headers = "HTTP/1.1 $status\r\nContent-Type: image/png\r\nContent-Length: " +
                            body.size + "\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray())
                        socket.getOutputStream().write(body)
                        socket.getOutputStream().flush()
                    }
                }
            } catch (e: Exception) {
                Log.i("NativePhysicalTest", "Test server stopped: $e")
            }
        }
        val source = object : HttpSource() {
            override val name = "Native physical network test"
            override val lang = "en"
            override val supportsLatest = false
            override val baseUrl = "http://127.0.0.1:" + server.localPort
            override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, imageUrl = "$baseUrl/page.png"))
        }
        val chapter = ReaderChapter(Chapter.create().copy(url = source.baseUrl + "/chapter"))
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loader = HttpPageLoader(chapter, source, graph.chapterCache, appScope) { true }
        try {
            runBlocking {
                val pages = loader.getPages()
                pages.forEach { it.chapter = chapter }
                chapter.state = ReaderChapter.State.Loaded(pages)
                chapter.pageLoader = loader
                val page = pages.single()
                Log.i("NativePhysicalTest", "Await initial error: status=${page.status} requests=${requests.get()}")
                withTimeout(30_000) { page.statusFlow.first { it is Page.State.Error } }
                Log.i("NativePhysicalTest", "Observed initial error: ${page.status}; requests=${requests.get()}")
                failing.set(false)
                Log.i("NativePhysicalTest", "Retry network image")
                loader.retryPage(page)
                withTimeout(30_000) { page.statusFlow.first { it == Page.State.Ready } }
                Log.i("NativePhysicalTest", "Network or cache image ready; requests=${requests.get()}")
                assertNotNull(page.stream?.invoke()?.use { BitmapFactory.decodeStream(it) })
                assertTrue(graph.chapterCache.isImageInCache(checkNotNull(page.imageUrl)))
                server.close()
                val before = requests.get()
                page.status = Page.State.Queue
                page.stream = null
                val load = appScope.launch { loader.loadPage(page) }
                withTimeout(30_000) { page.statusFlow.first { it == Page.State.Ready } }
                assertNotNull(page.stream?.invoke()?.use { BitmapFactory.decodeStream(it) })
                assertEquals("Cached image must not make another network request", before, requests.get())
                load.cancel()
            }
        } finally {
            server.close()
            worker.join(2000)
            loader.recycle()
            appScope.cancel()
        }
    }

    @Test
    fun realCbzUsesNativeCurlControlsProgressAndLifecycle() {
        val root = File(context.getExternalFilesDir(null), "native-reader-test").apply { mkdirs() }
        val folder = File(root, "local/Native Curl Proof").apply { mkdirs() }
        makeChapter(File(folder, "001.cbz"), 8)
        makeChapter(File(folder, "002.cbz"), 2)
        makeChapter(File(folder, "003.cbz"), 4, false)
        graph.storagePreferences.baseStorageDirectory.set(root.toUri().toString())
        graph.basePreferences.shownOnboardingFlow.set(true)
        graph.readerPreferences.defaultReadingMode.delete()
        assertEquals(ReadingMode.LEFT_TO_RIGHT.flagValue, graph.readerPreferences.defaultReadingMode.get())
        graph.readerPreferences.defaultOrientationType.set(ReaderOrientation.FREE.flagValue)
        graph.readerPreferences.showNavigationOverlayNewUser.set(false)
        graph.readerPreferences.showNavigationOverlayOnStart.set(false)
        graph.readerPreferences.fullscreen.set(true)
        val manga = runBlocking {
            graph.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        url = "Native Curl Proof",
                        title = "Native Curl Proof",
                        source = 0,
                        initialized = true,
                    ),
                ),
            ).single()
        }
        val chapters = runBlocking {
            graph.chapterRepository.updateFromRemote(
                emptyList(),
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "Native Curl Proof/001.cbz",
                        name = "Chapter 1",
                        chapterNumber = 1.0,
                        sourceOrder = 2,
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "Native Curl Proof/002.cbz",
                        name = "Chapter 2",
                        chapterNumber = 2.0,
                        sourceOrder = 1,
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "Native Curl Proof/003.cbz",
                        name = "Chapter 3",
                        chapterNumber = 3.0,
                        sourceOrder = 0,
                    ),
                ),
                emptyList(),
            )
        }
        val first = chapters.first { it.name == "Chapter 1" }
        SystemClock.sleep(500)
        val intent = ReaderActivity.newIntent(context, manga.id, first.id)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(250)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 1 && findSurface(it.window.decorView) != null }
            scenario.onActivity {
                val surface = checkNotNull(findSurface(it.window.decorView))
                assertTrue("Native renderer must fill the reader", surface.height > it.window.decorView.height * 0.85)
                assertFalse("No embedded webpage", containsWebView(it.window.decorView))
                assertEquals(
                    PhysicalBookViewer::class.java,
                    ReadingMode.toViewer(ReadingMode.LEFT_TO_RIGHT.flagValue, it)::class.java,
                )
                it.hideMenu()
            }
            SystemClock.sleep(600)
            capture("01-native-portrait")
            val before = AtomicBoolean(false)
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            waitFor(scenario) { it.viewModel.state.value.menuVisible.also(before::set) }
            assertTrue("Center tap reveals Mihon's controls", before.get())
            SystemClock.sleep(250)
            capture("07-native-mihon-controls")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            waitFor(scenario) { !it.viewModel.state.value.menuVisible }
            holdCurlAndCapture(scenario)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 2 }
            swipe(false)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 1 }
            swipe(true)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 2 }
            repeat(5) {
                device.swipe(
                    device.displayWidth * 9 / 10,
                    device.displayHeight * 3 / 5,
                    device.displayWidth / 10,
                    device.displayHeight * 3 / 5,
                    3,
                )
                SystemClock.sleep(40)
            }
            SystemClock.sleep(900)
            waitFor(scenario) { it.viewModel.state.value.currentPage > 2 }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp }
            SystemClock.sleep(1000)
            capture("03-native-book-layout")
            scenario.onActivity { it.moveToPageIndex(3) }
            waitFor(scenario) { it.viewModel.state.value.currentPage == 4 }
            SystemClock.sleep(600)
            capture("05-native-double-page-spread")
            swipe(false)
            waitFor(scenario) { it.viewModel.state.value.currentPage < 4 }
            swipe(true)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 4 }
            scenario.onActivity { it.viewModel.setMangaReadingMode(ReadingMode.RIGHT_TO_LEFT) }
            waitFor(scenario) { it.viewModel.getMangaReadingMode() == ReadingMode.RIGHT_TO_LEFT.flagValue }
            SystemClock.sleep(1000)
            swipe(false)
            waitFor(scenario) { it.viewModel.state.value.currentPage > 4 }
            capture("06-native-right-to-left")
            assertFalse("No webpage after rotation", containsWebViewOnUi(scenario))
            scenario.onActivity { it.viewModel.saveHistory() }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(scenario) { findSurface(it.window.decorView) != null }
            val adjacent = AtomicBoolean(false)
            scenario.onActivity { adjacent.set(it.viewModel.state.value.viewerChapters?.nextChapter != null) }
            assertTrue("Second chapter was loaded by Mihon", adjacent.get())
            scenario.onActivity { activity -> activity.lifecycleScopeForTestLoadNext() }
            waitFor(scenario) {
                it.viewModel.state.value.currentChapter?.chapter?.id != first.id &&
                    it.viewModel.state.value.currentPage == 1
            }
            capture("04-native-next-chapter")
            swipe(false)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 2 }
            swipe(true)
            waitFor(scenario) { it.viewModel.state.value.currentPage == 1 }
            verifyClosedBook(scenario, ReadingMode.LEFT_TO_RIGHT, 0, 1, false, "short-ltr")
            verifyClosedBook(scenario, ReadingMode.RIGHT_TO_LEFT, 0, 1, false, "short-rtl")
            scenario.onActivity { it.lifecycleScopeForTestLoadNext() }
            waitFor(scenario) { it.viewModel.state.value.currentChapter?.chapter?.name == "Chapter 3" }
            verifyClosedBook(scenario, ReadingMode.LEFT_TO_RIGHT, 1, 3, true, "spread-ltr")
            verifyClosedBook(scenario, ReadingMode.RIGHT_TO_LEFT, 1, 3, true, "spread-rtl")
        }
        ActivityScenario.launch<ReaderActivity>(intent).use { reopened ->
            waitFor(reopened) {
                it.viewModel.state.value.currentChapter?.chapter?.id == first.id &&
                    it.viewModel.state.value.currentPage > 1 && findSurface(it.window.decorView) != null
            }
            SystemClock.sleep(500)
            capture("08-native-reopened-progress")
            reopened.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor(reopened) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp }
            SystemClock.sleep(600)
            verifyClosedBook(reopened, ReadingMode.LEFT_TO_RIGHT, 7, 7, true, "wide-ltr")
            verifyClosedBook(reopened, ReadingMode.RIGHT_TO_LEFT, 7, 7, true, "wide-rtl")
            reopened.onActivity { it.moveToPageIndex(7) }
            waitFor(reopened) { it.viewModel.state.value.currentPage == 8 }
            SystemClock.sleep(500)
            swipe(false)
            val stillInFirstChapter = AtomicBoolean(false)
            reopened.onActivity {
                stillInFirstChapter.set(it.viewModel.state.value.currentChapter?.chapter?.id == first.id)
            }
            if (stillInFirstChapter.get()) swipe(false)
            waitFor(reopened) {
                it.viewModel.state.value.currentChapter?.chapter?.id != first.id &&
                    it.viewModel.state.value.currentPage == 1
            }
            capture("09-native-gesture-chapter-boundary")
        }
        val saved = runBlocking { graph.chapterRepository.getChapterById(first.id) }
        assertNotNull(saved)
        assertTrue("Progress is saved through Mihon", saved!!.lastPageRead > 0)
        assertTrue(
            "History is saved through Mihon",
            runBlocking {
                graph.historyRepository.getHistoryByMangaId(manga.id)
            }.isNotEmpty(),
        )
    }

    private fun verifyClosedBook(
        scenario: ActivityScenario<ReaderActivity>,
        mode: ReadingMode,
        start: Int,
        end: Int,
        spread: Boolean,
        label: String,
    ) {
        scenario.onActivity {
            it.viewModel.setMangaReadingMode(mode)
            it.hideMenu()
        }
        waitFor(scenario) { it.viewModel.getMangaReadingMode() == mode.flagValue }
        SystemClock.sleep(1000)
        scenario.onActivity { it.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_LANDSCAPE) }
        waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp }
        SystemClock.sleep(1000)
        // Changing direction displays Mihon's navigation guide. Dismiss it before sampling paper.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        SystemClock.sleep(400)
        scenario.onActivity { it.hideMenu() }
        SystemClock.sleep(1100)
        scenario.onActivity { it.moveToPageIndex(start) }
        waitFor(scenario) { it.viewModel.state.value.currentPage == start + 1 }
        SystemClock.sleep(600)
        val ltr = mode == ReadingMode.LEFT_TO_RIGHT
        waitForPaper(left = spread || !ltr, right = spread || ltr)
        swipe(ltr)
        SystemClock.sleep(400)
        waitFor(scenario) { it.viewModel.state.value.currentPage == end + 1 }
        waitForPaper(left = ltr, right = !ltr)
        SystemClock.sleep(400)
        waitForPaper(left = ltr, right = !ltr)
        capture("10-closed-$label")
        // A tap on the empty half must not reopen the comic or advance chapters.
        tapBook(if (ltr) 0.75f else 0.25f)
        SystemClock.sleep(500)
        waitForPaper(left = ltr, right = !ltr)
        tapBook(if (ltr) 0.25f else 0.75f)
        SystemClock.sleep(1000)
        waitForPaper(left = spread || !ltr, right = spread || ltr)
        waitFor(scenario) { it.viewModel.state.value.currentPage == start + 1 }
        capture("11-reopened-$label")
        swipe(ltr)
        SystemClock.sleep(400)
        waitForPaper(left = ltr, right = !ltr)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        SystemClock.sleep(600)
        waitForPaper(left = ltr, right = !ltr)
        tapBook(if (ltr) 0.25f else 0.75f)
        SystemClock.sleep(1000)
        waitForPaper(left = spread || !ltr, right = spread || ltr)
    }

    private fun tapBook(fraction: Float) {
        instrumentation.waitForIdleSync()
        val time = SystemClock.uptimeMillis()
        // Queue both halves of a short tap before waiting; an overloaded emulator must not hold DOWN.
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(
                time,
                time + index,
                action,
                device.displayWidth * fraction,
                device.displayHeight / 2f,
                0,
            )
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false))
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun waitForPaper(left: Boolean, right: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var actualLeft = 0.0
        var actualRight = 0.0
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            fun occupied(from: Double, to: Double): Double {
                var painted = 0
                for (row in 0 until 24) {
                    for (column in 0 until 24) {
                        val x = ((from + (to - from) * (column + 0.5) / 24) * bitmap.width).toInt()
                        val y = ((0.30 + 0.40 * (row + 0.5) / 24) * bitmap.height).toInt()
                        val pixel = bitmap.getPixel(x, y)
                        val difference = kotlin.math.abs(Color.red(pixel) - 19) +
                            kotlin.math.abs(Color.green(pixel) - 20) + kotlin.math.abs(Color.blue(pixel) - 23)
                        if (difference > 30) painted++
                    }
                }
                return painted / 576.0
            }
            actualLeft = occupied(0.15, 0.40)
            actualRight = occupied(0.60, 0.85)
            bitmap.recycle()
            fun matches(expected: Boolean, actual: Double) = if (expected) actual > 0.15 else actual < 0.02
            if (matches(left, actualLeft) && matches(right, actualRight)) return
            SystemClock.sleep(200)
        }
        capture("99-wrong-closed-side")
        throw AssertionError("Paper sides expected $left/$right, occupied $actualLeft/$actualRight")
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
        val startY = top + height * 0.76f
        val endY = top + height * 0.40f
        fun input(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        input(MotionEvent.ACTION_DOWN, start, startY)
        repeat(16) { step ->
            SystemClock.sleep(24)
            val fraction = (step + 1) / 16f
            input(MotionEvent.ACTION_MOVE, start + (end - start) * fraction, startY + (endY - startY) * fraction)
        }
        SystemClock.sleep(300)
        capture("02-native-held-page-curl")
        input(MotionEvent.ACTION_UP, end, endY)
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
        scenario.onActivity {
            Log.e(
                "NativePhysicalTest",
                "Timed out: chapter=${it.viewModel.state.value.currentChapter?.chapter?.id} " +
                    "page=${it.viewModel.state.value.currentPage} " +
                    "requested=${it.viewModel.state.value.currentChapter?.requestedPage} " +
                    "loading=${it.viewModel.state.value.isLoadingAdjacentChapter}",
            )
        }
        capture("99-native-failed-condition")
        throw AssertionError("Native reader condition timed out; inspect logcat and screenshots")
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val scale = minOf(1f, 1000f / max(screenshot.width, screenshot.height))
        val small = Bitmap.createScaledBitmap(
            screenshot,
            (screenshot.width * scale).toInt(),
            (screenshot.height * scale).toInt(),
            true,
        )
        File(proof, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val privateImage = File(proof, "$name.jpg")
        listOf(
            "mkdir -p /sdcard/Download/native-physical-proof",
            "cp ${privateImage.absolutePath} /sdcard/Download/native-physical-proof/$name.jpg",
        ).forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
                it.readBytes()
            }
        }
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

    private fun makeChapter(file: File, count: Int = 9, widePages: Boolean = true) {
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(count) { index ->
                if (index < 3) {
                    zip.putNextEntry(ZipEntry("${index.toString().padStart(3, '0')}.jpg"))
                    instrumentation.context.assets.open("real_comic/page${index + 1}.jpg").use { it.copyTo(zip) }
                    zip.closeEntry()
                    return@repeat
                }
                val wide = widePages && (index == 3 || index == 7)
                val bitmap = Bitmap.createBitmap(
                    if (wide) 1600 else 720,
                    if (wide) 900 else 1080,
                    Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                canvas.drawColor(
                    intArrayOf(Color.rgb(230, 50, 50), Color.rgb(35, 170, 90), Color.rgb(50, 85, 220))[
                        index %
                            3,
                    ],
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 80f
                    strokeWidth = 12f
                }
                canvas.drawText("${file.name} PAGE ${index + 1}", 30f, 130f, paint)
                paint.color = Color.BLACK
                for (x in 30 until bitmap.width step 180) {
                    canvas.drawLine(
                        x.toFloat(),
                        180f,
                        x.toFloat(),
                        bitmap.height - 20f,
                        paint,
                    )
                }
                for (y in 180 until bitmap.height step 180) {
                    canvas.drawLine(
                        30f,
                        y.toFloat(),
                        bitmap.width - 20f,
                        y.toFloat(),
                        paint,
                    )
                }
                zip.putNextEntry(ZipEntry("${index.toString().padStart(3, '0')}.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bitmap.recycle()
            }
        }
    }
}
