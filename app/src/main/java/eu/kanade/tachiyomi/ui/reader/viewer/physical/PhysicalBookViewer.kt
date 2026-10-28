package eu.kanade.tachiyomi.ui.reader.viewer.physical

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageError
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.screens.LoadingScreen
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max

/** Mihon's default viewer, with native v4.0 page curl instead of a pager or embedded browser. */
class PhysicalBookViewer(private val activity: ReaderActivity, private val reversed: Boolean) : Viewer {
    private val scope = MainScope()
    private val decodeLock = Mutex()
    private val jobs = ConcurrentHashMap<Int, Job>()
    private var chapters: ViewerChapters? = null
    private var surface: BookSurface? = null
    private var generation = 0
    private var selectedIndex = 0
    private var focusIndex = 0
    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<Pair<ReaderPage, Throwable>?>(null)

    @Composable
    override fun Content(modifier: Modifier) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val general by activity.settingsViewModel.general.collectAsState()
        val view = remember {
            BookSurface().also {
                surface = it
                chapters?.currChapter?.let { chapter ->
                    it.command { setChapter(chapter.pages.orEmpty().size, chapter.requestedPage) }
                }
            }
        }
        DisposableEffect(view, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_RESUME -> { view.onResume(); view.requestRender() }
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                view.command { release() }
                view.onPause()
                surface = null
            }
        }
        Box(modifier) {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize(),
                update = { it.command { readerGray = general.grayscale; inverted = general.invertedColors } },
            )
            if (loading) LoadingScreen()
            failure?.let { (page, error) ->
                ReaderPageError(page, error, activity.viewModel.source?.id, Modifier.fillMaxSize())
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        if (this.chapters?.currChapter === chapters.currChapter) return
        chapters.ref()
        val old = this.chapters
        this.chapters = chapters
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        selectedIndex = chapters.currChapter.requestedPage
        focusIndex = selectedIndex
        loading = true
        failure = null
        surface?.command { setChapter(chapters.currChapter.pages.orEmpty().size, selectedIndex) }
        old?.unref()
    }

    private fun requestPage(index: Int, required: Boolean) {
        scope.launch {
            val chapter = chapters?.currChapter ?: return@launch
            val page = chapter.pages?.getOrNull(index) ?: return@launch
            if (required) focusIndex = index
            if (jobs[index]?.isActive == true) return@launch
            val token = generation
            jobs[index] = scope.launch {
                while (isActive && token == generation) {
                    try {
                        val decoded = withContext(Dispatchers.IO) {
                            decodeLock.withLock {
                                coroutineScope {
                                    val load = launch { chapter.pageLoader?.loadPage(page) }
                                    try {
                                        // Let a retried HTTP page leave its previous Error state first.
                                        if (page.status is Page.State.Error) chapter.pageLoader?.retryPage(page)
                                        val state = page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                                        if (state is Page.State.Error) throw state.error
                                        val stream = checkNotNull(page.stream)
                                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                                        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
                                        val options = BitmapFactory.Options().apply {
                                            inPreferredConfig = Bitmap.Config.ARGB_8888
                                            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 2048) {
                                                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                                            }
                                        }
                                        val bitmap = stream().use { BitmapFactory.decodeStream(it, null, options) }
                                        Triple(checkNotNull(bitmap), bounds.outWidth, bounds.outHeight)
                                    } finally {
                                        load.cancel()
                                    }
                                }
                            }
                        }
                        if (token != generation || surface == null) { decoded.first.recycle(); return@launch }
                        surface?.command {
                            if (token == generation) putPage(index, decoded.first, decoded.second, decoded.third)
                            else decoded.first.recycle()
                        }
                        if (index == focusIndex) { loading = false; failure = null }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (index == focusIndex) { loading = false; failure = page to e }
                        page.statusFlow.first { it !is Page.State.Error && it != Page.State.Ready }
                    }
                }
            }
        }
    }

    override fun moveToPage(page: ReaderPage) {
        if (page.chapter !== chapters?.currChapter) return
        surface?.command { seek(page.index) }
    }
    override fun moveForward() { surface?.command { flip(1) } }
    override fun moveBackward() { surface?.command { flip(-1) } }
    override fun destroy() {
        scope.cancel()
        chapters?.unref()
        chapters = null
    }

    private inner class BookSurface : GLSurfaceView(activity) {
        private var downX = 0f
        private var downY = 0f
        private var scaling = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private val renderer = PhysicalBookRenderer(
            activity,
            reversed,
            requestPage = { index, required -> requestPage(index, required) },
            selected = { index ->
                scope.launch {
                    selectedIndex = index
                    focusIndex = index
                    loading = false
                    failure = null
                    chapters?.currChapter?.pages?.getOrNull(index)?.let(activity::onPageSelected)
                    jobs.filterKeys { it !in index - 3..index + 4 }.values.forEach { it.cancel() }
                }
            },
            boundary = { forward ->
                scope.launch {
                    if (forward) activity.viewModel.loadNextChapter() else activity.viewModel.loadPreviousChapter()
                }
            },
            nextFrame = { postOnAnimation { requestRender() } },
        )
        private val scale = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                command { zoomBy(factor) }
                return true
            }
        })
        private val gesture = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { command { doubleTap() }; return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (longPressed || scaling) return true
                val x = e.x / width
                if (x in 0.35f..0.65f) activity.toggleMenu() else activity.onViewerTap(Offset(x, e.y / height))
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!activity.settingsViewModel.general.value.longTapEnabled) return
                longPressed = true
                chapters?.currChapter?.pages?.getOrNull(selectedIndex)?.let(activity::onPageLongTap)
            }
        })
        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        }
        fun command(action: PhysicalBookRenderer.() -> Unit) {
            queueEvent { renderer.action() }
            requestRender()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scale.onTouchEvent(event)
            gesture.onTouchEvent(event)
            if (event.pointerCount > 1) scaling = true
            val x = event.x / width.toDouble()
            val y = event.y / height.toDouble()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scaling = false
                    longPressed = false
                    downX = event.x
                    downY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    command { down(x, y) }
                }
                MotionEvent.ACTION_MOVE -> if (!scaling) {
                    if (hypot(event.x - downX, event.y - downY) > slop) activity.hideMenu()
                    command { move(x, y) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tapped = hypot(event.x - downX, event.y - downY) <= slop || scaling || longPressed
                    command { up(x, event.actionMasked == MotionEvent.ACTION_CANCEL, tapped) }
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }
}
