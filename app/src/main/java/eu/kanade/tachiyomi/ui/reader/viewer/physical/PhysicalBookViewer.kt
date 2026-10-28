package eu.kanade.tachiyomi.ui.reader.viewer.physical

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
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
import ca.mpreg.imagedecoder.ImageDecoder
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
import mihon.app.di.appGraph
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max

/** Mihon's default viewer, with native v4.0 page curl instead of a pager or embedded browser. */
class PhysicalBookViewer(private val activity: ReaderActivity, private val reversed: Boolean) : Viewer {
    private val scope = MainScope()
    private val decodeLock = Mutex()
    private val jobs = ConcurrentHashMap<Int, Job>()
    private var chapters: ViewerChapters? = null
    private var surface: BookSurface? = null

    @Volatile
    private var generation = 0
    private var knownDimensions = emptyMap<Int, Pair<Int, Int>>()
    private var metadataReady = false
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
                chapters?.currChapter?.takeIf { metadataReady }?.let { chapter ->
                    it.bindChapter(chapter.pages.orEmpty().size, chapter.requestedPage, knownDimensions, generation)
                }
            }
        }
        DisposableEffect(view, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        jobs.values.forEach { it.cancel() }
                        view.onPause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        view.onResume()
                        view.requestRender()
                    }
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                view.dispose()
                view.onPause()
                surface = null
            }
        }
        Box(modifier) {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.command {
                        readerGray = general.grayscale
                        inverted = general.invertedColors
                    }
                },
            )
            if (loading) LoadingScreen()
            failure?.let { (page, error) ->
                ReaderPageError(
                    page,
                    error,
                    activity.viewModel.source?.id,
                    Modifier.fillMaxSize(),
                    onRetry = {
                        failure = null
                        loading = true
                        if (page.chapter.pageLoader?.isLocal == true) {
                            page.status = Page.State.Ready
                        } else {
                            page.chapter.pageLoader?.retryPage(page)
                        }
                    },
                )
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        if (this.chapters?.currChapter === chapters.currChapter) {
            chapters.ref()
            this.chapters?.unref()
            this.chapters = chapters
            return
        }
        chapters.ref()
        val old = this.chapters
        this.chapters = chapters
        generation++
        Log.i(
            "NativePhysicalV40",
            "chapter=${chapters.currChapter.chapter.id} " +
                "requested=${chapters.currChapter.requestedPage} generation=$generation",
        )
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        selectedIndex = chapters.currChapter.requestedPage
        focusIndex = selectedIndex
        loading = true
        failure = null
        metadataReady = false
        knownDimensions = emptyMap()
        val token = generation
        scope.launch {
            val known = withContext(Dispatchers.IO) {
                val result = mutableMapOf<Int, Pair<Int, Int>>()
                for (page in chapters.currChapter.pages.orEmpty()) {
                    try {
                        val input = if (page.status == Page.State.Ready) page.stream?.invoke() else null
                        val cached = input ?: page.imageUrl?.let { activity.appGraph.chapterCache.openImage(it) }
                        cached?.use {
                            val encoded = it.readBytes()
                            ImageDecoder.open(ByteArrayInputStream(encoded)).use { decoder ->
                                result[page.index] = decoder.width to decoder.height
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Normal loading retains Mihon's visible error and retry handling.
                    }
                }
                result
            }
            if (generation != token) return@launch
            knownDimensions = known
            metadataReady = true
            surface?.bindChapter(chapters.currChapter.pages.orEmpty().size, selectedIndex, known, token)
        }
        old?.unref()
    }

    private fun requestPage(index: Int, required: Boolean) {
        scope.launch {
            if (!metadataReady) return@launch
            val chapter = chapters?.currChapter ?: return@launch
            val page = chapter.pages?.getOrNull(index) ?: return@launch
            if (required) {
                focusIndex = index
                (page.status as? Page.State.Error)?.let {
                    loading = false
                    failure = page to it.error
                }
            }
            if (jobs[index]?.isActive == true) return@launch
            val token = generation
            jobs[index] = scope.launch {
                while (isActive && token == generation) {
                    try {
                        val decodeLimit = surface?.maxPageDimension ?: 2048
                        val decoded = withContext(Dispatchers.IO) {
                            decodeLock.withLock {
                                coroutineScope {
                                    val load = launch { chapter.pageLoader?.loadPage(page) }
                                    try {
                                        // Let a retried HTTP page leave its previous Error state first.
                                        if (page.status is Page.State.Error) chapter.pageLoader?.retryPage(page)
                                        val state = page.statusFlow.first {
                                            it == Page.State.Ready ||
                                                it is Page.State.Error
                                        }
                                        if (state is Page.State.Error) throw state.error
                                        val stream = checkNotNull(page.stream)
                                        val encoded = stream().use { it.readBytes() }
                                        ImageDecoder.open(ByteArrayInputStream(encoded)).use { decoder ->
                                            val edge = max(decoder.width, decoder.height)
                                            var sample = 1
                                            while ((edge + sample - 1) / sample > decodeLimit) sample *= 2
                                            val decodedBitmap = decoder.decodeToBitmap(null, sample)
                                            val bitmap = if (decodedBitmap.config == Bitmap.Config.ARGB_8888) {
                                                decodedBitmap
                                            } else {
                                                checkNotNull(decodedBitmap.copy(Bitmap.Config.ARGB_8888, false))
                                                    .also { decodedBitmap.recycle() }
                                            }
                                            Triple(bitmap, decoder.width, decoder.height)
                                        }
                                    } finally {
                                        load.cancel()
                                    }
                                }
                            }
                        }
                        if (token != generation || surface == null) {
                            decoded.first.recycle()
                            return@launch
                        }
                        surface?.command(onDiscard = { decoded.first.recycle() }) {
                            if (token == generation) {
                                putPage(index, decoded.first, decoded.second, decoded.third)
                            } else {
                                decoded.first.recycle()
                            }
                        }
                        if (index == focusIndex) {
                            loading = false
                            failure = null
                        }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e("NativePhysicalV40", "Page $index in chapter ${chapter.chapter.id} failed", e)
                        page.status = Page.State.Error(e)
                        if (index == focusIndex) {
                            loading = false
                            failure = page to e
                        }
                        page.statusFlow.first { it !is Page.State.Error }
                    }
                }
            }
        }
    }

    override fun moveToPage(page: ReaderPage) {
        if (page.chapter !== chapters?.currChapter) return
        selectedIndex = page.index
        chapters?.currChapter?.requestedPage = page.index
        if (metadataReady) surface?.command { seek(page.index) }
    }
    override fun moveForward() {
        surface?.command { flip(1) }
    }
    override fun moveBackward() {
        surface?.command { flip(-1) }
    }
    override fun destroy() {
        generation++
        scope.cancel()
        chapters?.unref()
        chapters = null
    }

    private class RenderCommand(
        val action: PhysicalBookRenderer.() -> Unit,
        val discard: (() -> Unit)?,
    )

    private inner class BookSurface : GLSurfaceView(activity) {
        private val pending = ConcurrentLinkedQueue<RenderCommand>()

        @Volatile
        private var disposed = false
        private var activeChapterGeneration = -1
        private var downX = 0f
        private var downY = 0f
        private var scaling = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        val maxPageDimension: Int get() = renderer.maxPageDimension
        private val renderer = PhysicalBookRenderer(
            activity,
            reversed,
            requestPage = { index, required ->
                if (activeChapterGeneration == generation) requestPage(index, required)
            },
            selected = { index ->
                val callbackGeneration = activeChapterGeneration
                scope.launch {
                    if (!metadataReady || callbackGeneration != generation) return@launch
                    Log.i(
                        "NativePhysicalV40",
                        "selected=$index chapter=${chapters?.currChapter?.chapter?.id} generation=$generation",
                    )
                    selectedIndex = index
                    focusIndex = index
                    loading = false
                    failure = null
                    chapters?.currChapter?.pages?.getOrNull(index)?.let(activity::onPageSelected)
                    jobs.filterKeys { it !in index - 3..index + 4 }.values.forEach { it.cancel() }
                }
            },
            boundary = { forward ->
                val callbackGeneration = activeChapterGeneration
                scope.launch {
                    if (callbackGeneration != generation) return@launch
                    if (forward) activity.viewModel.loadNextChapter() else activity.viewModel.loadPreviousChapter()
                }
            },
            nextFrame = { postOnAnimation { requestRender() } },
        )
        private val scale =
            ScaleGestureDetector(
                activity,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val factor = detector.scaleFactor
                        command { zoomBy(factor) }
                        return true
                    }
                },
            )
        private val gesture = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    command { doubleTap() }
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (longPressed || scaling) return true
                    val x = e.x / width
                    val position = Offset(x, e.y / height)
                    if (x in 0.35f..0.65f) {
                        activity.toggleMenu()
                    } else if (width > height) {
                        val token = generation
                        command {
                            if (token == generation && !tapClosedBook(x.toDouble(), position.y.toDouble())) {
                                scope.launch {
                                    if (token == generation) activity.onViewerTap(position)
                                }
                            }
                        }
                    } else {
                        activity.onViewerTap(position)
                    }
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    if (!activity.settingsViewModel.general.value.longTapEnabled) return
                    longPressed = true
                    chapters?.currChapter?.pages?.getOrNull(selectedIndex)?.let(activity::onPageLongTap)
                }
            },
        )
        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    renderer.onSurfaceCreated(gl, config)
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    renderer.onSurfaceChanged(gl, width, height)
                }
                override fun onDrawFrame(gl: GL10?) {
                    if (disposed) return
                    while (true) {
                        val command = pending.poll() ?: break
                        command.action(renderer)
                    }
                    renderer.onDrawFrame(gl)
                }
            })
            renderMode = RENDERMODE_WHEN_DIRTY
        }
        fun bindChapter(count: Int, initial: Int, known: Map<Int, Pair<Int, Int>>, token: Int) {
            command {
                activeChapterGeneration = token
                setChapter(count, initial, known)
            }
        }
        fun command(onDiscard: (() -> Unit)? = null, action: PhysicalBookRenderer.() -> Unit) {
            if (disposed) {
                onDiscard?.invoke()
                return
            }
            pending.add(RenderCommand(action, onDiscard))
            requestRender()
        }
        fun dispose() {
            disposed = true
            while (true) {
                (pending.poll() ?: break).discard?.invoke()
            }
            queueEvent {
                if (android.opengl.EGL14.eglGetCurrentContext() != android.opengl.EGL14.EGL_NO_CONTEXT) {
                    renderer.release()
                }
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!metadataReady) return true
            scale.onTouchEvent(event)
            gesture.onTouchEvent(event)
            if (event.pointerCount > 1) scaling = true
            val x = event.x / width.toDouble()
            val y = event.y / height.toDouble()
            val eventTime = event.eventTime
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scaling = false
                    longPressed = false
                    downX = event.x
                    downY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    command { down(x, y, eventTime) }
                }
                MotionEvent.ACTION_MOVE -> if (!scaling) {
                    if (hypot(event.x - downX, event.y - downY) > slop) activity.hideMenu()
                    command { move(x, y, eventTime) }
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
