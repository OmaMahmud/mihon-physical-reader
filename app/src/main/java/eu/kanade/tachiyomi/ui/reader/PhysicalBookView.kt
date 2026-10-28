package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** Hosts the bundled physical renderer using the active chapter's own page streams. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class PhysicalBookView(
    context: Context,
    private val chapter: ReaderChapter,
    private val onPage: (ReaderPage) -> Unit,
    private val onMenu: () -> Unit,
    private val onNextChapter: () -> Unit,
    private val onPreviousChapter: () -> Unit,
) : WebView(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chapterPages = chapter.pages.orEmpty()
    private val initialPage = chapter.requestedPage.coerceIn(0, (chapterPages.size - 1).coerceAtLeast(0))
    private val pageLock = Any()

    @Volatile
    private var released = false

    init {
        chapter.ref()
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        addJavascriptInterface(Bridge(), "MihonPhysical")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST || released) return response("")
                return when {
                    uri.path == "/reader" -> response(readerHtml())
                    uri.path == "/bridge.js" -> WebResourceResponse(
                        "text/javascript",
                        "UTF-8",
                        context.assets.open("physical_book_bridge.js"),
                    )
                    uri.path?.startsWith("/page/") == true -> pageResponse(uri.lastPathSegment?.toIntOrNull())
                    else -> response("")
                }
            }
        }
        loadUrl("https://$HOST/reader")
    }

    private fun response(html: String) = WebResourceResponse(
        "text/html",
        "UTF-8",
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )

    private fun pageResponse(index: Int?): WebResourceResponse = synchronized(pageLock) {
        val page = index?.let(chapterPages::getOrNull) ?: return@synchronized response("")
        try {
            // Serialize requests: the HTTP loader prioritizes one rolling window at a time.
            val job = scope.launch { chapter.pageLoader?.loadPage(page) }
            try {
                runBlocking(scope.coroutineContext) {
                    withTimeout(90_000) {
                        page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                    }
                }
                check(!released && page.status == Page.State.Ready)
                val stream = checkNotNull(page.stream).invoke()
                WebResourceResponse("image/*", null, stream)
            } finally {
                job.cancel()
            }
        } catch (_: Exception) {
            response("")
        }
    }

    private fun readerHtml(): String {
        val urls = JSONArray(chapterPages.indices.map { "https://$HOST/page/$it" })
        val html = context.assets.open("physical_book_demo.html").bufferedReader().use { it.readText() }
        val start = html.indexOf("const images=")
        val end = html.indexOf("</script>", start)
        check(start >= 0 && end > start)
        return html.replaceRange(start, end, "const images=$urls;const initialSourcePage=$initialPage;")
            .replace("Promise.all(images.map(loadImage))", "loadChapterImages()")
            .replace(
                "index=orientation==='book'?-1:0;layout();render();",
                "restoreSourcePage(initialSourcePage);layout();render();",
            )
            .replace("requestAnimationFrame(render);", "reportPhysicalPage();requestAnimationFrame(render);")
            .replace("</head>", "<script src=\"https://$HOST/bridge.js\"></script></head>")
    }

    fun moveToPage(index: Int) {
        val target = index.coerceIn(0, (chapterPages.size - 1).coerceAtLeast(0))
        evaluateJavascript("window.seekPhysicalPage?.($target)", null)
    }

    fun moveForward() {
        evaluateJavascript("forward()", null)
    }

    fun moveBackward() {
        evaluateJavascript("backward()", null)
    }

    fun release() {
        released = true
        scope.cancel()
        stopLoading()
        removeJavascriptInterface("MihonPhysical")
        destroy()
        chapter.unref()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pageSelected(index: Int) {
            post { if (!released) chapterPages.getOrNull(index)?.let(onPage) }
        }

        @JavascriptInterface
        fun menu() {
            post { if (!released) onMenu() }
        }

        @JavascriptInterface
        fun nextChapter() {
            post { if (!released) onNextChapter() }
        }

        @JavascriptInterface
        fun previousChapter() {
            post { if (!released) onPreviousChapter() }
        }
    }

    private companion object {
        const val HOST = "physical-reader.invalid"
    }
}
