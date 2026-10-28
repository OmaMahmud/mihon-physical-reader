package eu.kanade.tachiyomi.ui.reader.viewer.physical

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_COMPILE_STATUS
import android.opengl.GLES20.GL_CULL_FACE
import android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES20.GL_DEPTH_TEST
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_LEQUAL
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_LINK_STATUS
import android.opengl.GLES20.GL_MAX_TEXTURE_SIZE
import android.opengl.GLES20.GL_LINEAR_MIPMAP_LINEAR
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_REPEAT
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.GL_TEXTURE0
import android.opengl.GLES20.GL_TEXTURE1
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.GL_TRIANGLES
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glActiveTexture
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glBlendFunc
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glDeleteProgram
import android.opengl.GLES20.glDeleteShader
import android.opengl.GLES20.glDeleteTextures
import android.opengl.GLES20.glDepthFunc
import android.opengl.GLES20.glDepthMask
import android.opengl.GLES20.glDisable
import android.opengl.GLES20.glDrawArrays
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glGenerateMipmap
import android.opengl.GLES20.glGetIntegerv
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetProgramInfoLog
import android.opengl.GLES20.glGetProgramiv
import android.opengl.GLES20.glGetShaderInfoLog
import android.opengl.GLES20.glGetShaderiv
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glShaderSource
import android.opengl.GLES20.glTexParameteri
import android.opengl.GLES20.glUniform1f
import android.opengl.GLES20.glUniform1i
import android.opengl.GLES20.glUniform2f
import android.opengl.GLES20.glUniform4f
import android.opengl.GLES20.glUniformMatrix4fv
import android.opengl.GLES20.glUseProgram
import android.opengl.GLES20.glVertexAttribPointer
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Native OpenGL ES renderer. Mesh, draw ordering, timing and gestures follow the approved v4.0 source. */
internal class PhysicalBookRenderer(
    private val context: Context,
    private val reversed: Boolean,
    private val requestPage: (Int, Boolean) -> Unit,
    private val selected: (Int) -> Unit,
    private val boundary: (Boolean) -> Unit,
    private val nextFrame: () -> Unit,
) : GLSurfaceView.Renderer {
    private data class Face(val source: Int, val side: Int = -1, val wide: Boolean = false, val aspect: Float = 1.36f)
    private data class Texture(val id: Int, val width: Int, val height: Int)
    private val textures = mutableMapOf<Int, Texture>()
    private val dimensions = mutableMapOf<Int, Pair<Int, Int>>()
    private var faces = emptyList<Face>()
    private var count = 0
    private var index = 0
    private var source = 0
    private var lastReported = -1
    private var book = false
    private var width = 1
    private var height = 1
    private var ph = 1.36f
    private var viewSw = 2f
    private var viewSh = 2f
    private var measuredFrames = 0
    private var measuredNanos = 0L
    private var measuredMaxNanos = 0L
    @Volatile
    var maxPageDimension = 2048
        private set
    private var program = 0
    private var grain = 0
    private var backing = 0
    private var notice = 0
    private var projection = FloatArray(16)
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(
        1_000_000 * 4,
    ).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val uniforms = mutableMapOf<String, Int>()
    private var progress = 0.0
    private var grabY = 0.5
    private var moveY = 0.5
    private var direction = 1
    private var drag = false
    private var spreadTurn = false
    private var spreadLastX = 0.0
    private var edgePull = 0.0
    private var edgeDirection = 0
    private var pan = 0.0
    private var startX = 0.0
    private var lastX = 0.0
    private var lastTime = 0L
    private var velocity = 0.0
    private var animationStart = 0L
    private var animationDuration = 0.0
    private var animationFrom = 0.0
    private var animationTo = 0.0
    private var animationY = 0.5
    private var pendingSeek: Int? = null
    private var pendingDirection = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var panStartX = 0f
    private var panStartY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    var readerGray = false
    var inverted = false
    private fun u(name: String) = uniforms.getOrPut(name) { glGetUniformLocation(program, name) }
    private fun scalar(name: String, value: Float) = glUniform1f(u(name), value)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textures.clear()
        val limit = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, limit, 0)
        maxPageDimension = min(4096, limit[0].coerceAtLeast(2048))
        uniforms.clear()
        fun shader(type: Int, path: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, context.assets.open(path).bufferedReader().use { it.readText() })
            glCompileShader(id)
            val result = IntArray(1)
            glGetShaderiv(id, GL_COMPILE_STATUS, result, 0)
            check(result[0] != 0) { glGetShaderInfoLog(id) }
            return id
        }
        program = glCreateProgram()
        val vertex = shader(GL_VERTEX_SHADER, "physical_v40.vert")
        val fragment = shader(GL_FRAGMENT_SHADER, "physical_v40.frag")
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        glUseProgram(program)
        glUniform1i(u("uTex"), 0)
        glUniform1i(u("uPaperGrain"), 1)
        val paper = context.assets.open("physical_v40_grain.png").use { BitmapFactory.decodeStream(it) }
        grain = upload(checkNotNull(paper), true)
        paper.recycle()
        val blank = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        blank.eraseColor(0xff24242a.toInt())
        backing = upload(blank)
        blank.eraseColor(0xfff5f0e5.toInt())
        notice = upload(blank)
        blank.recycle()
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDisable(GL_CULL_FACE)
        glClearColor(0.075f, 0.08f, 0.09f, 1f)
        scalar("uPageGloss", 1f)
        scalar("uMaterial", 1f)
        scalar("uRtlSign", if (reversed) -1f else 1f)
        scalar("uTextureFlip", if (reversed) 1f else 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        val old = currentSource()
        book = width > height
        rebuild(old)
        progress = 0.0
        drag = false
        animationDuration = 0.0
        glViewport(0, 0, width, height)
    }

    fun setChapter(pageCount: Int, initial: Int, known: Map<Int, Pair<Int, Int>> = emptyMap()) {
        textures.values.forEach { glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures.clear()
        dimensions.clear()
        dimensions.putAll(known)
        count = pageCount
        source = initial.coerceIn(0, max(0, count - 1))
        lastReported = -1
        animationDuration = 0.0
        drag = false
        progress = 0.0
        pan = 0.0
        zoom = 1f
        panX = 0f
        panY = 0f
        pendingSeek = null
        rebuild(source)
    }

    fun putPage(source: Int, bitmap: Bitmap, originalWidth: Int, originalHeight: Int) {
        val old = textures.remove(source)
        old?.let { glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures[source] = Texture(upload(bitmap), originalWidth, originalHeight)
        bitmap.recycle()
        dimensions[source] = originalWidth to originalHeight
        if (!drag && animationDuration == 0.0) rebuild(currentSource())
        pendingSeek?.let {
            if (textures.containsKey(it)) {
                pendingSeek = null
                seek(it)
            }
        }
        if (pendingDirection != 0 && readyToTurn(pendingDirection)) {
            val pending = pendingDirection
            pendingDirection = 0
            flip(pending)
        }
    }

    private fun upload(bitmap: Bitmap, repeat: Boolean = false): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        if (repeat) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        }
        return ids[0]
    }

    private fun rebuild(keep: Int) {
        val result = ArrayList<Face>()
        for (n in 0 until count) {
            val (w, h) = dimensions[n] ?: (1000 to 1360)
            val wide = w > h * 1.12
            if (book && wide) {
                if (result.size % 2 == 0) result.add(Face(-1, aspect = 1.5f))
                for (side in 0..1) result.add(Face(n, if (reversed) 1 - side else side, true, h / (w / 2f)))
            } else {
                result.add(Face(n, wide = wide, aspect = h / (if (wide) w / 2f else w.toFloat())))
            }
        }
        faces = result
        val face = faces.indexOfFirst { it.source == keep }.coerceAtLeast(0)
        index = if (book) {
            if (keep == 0) {
                -1
            } else if (face % 2 == 1) {
                face
            } else {
                face - 1
            }
        } else {
            face
        }
    }

    private fun currentSource(): Int = faces.getOrNull(max(0, index))?.source?.takeIf { it >= 0 } ?: source
    private fun canTurn(dir: Int): Boolean = if (book) {
        if (dir == 1) {
            if (index == -1) faces.size > 2 else index < faces.lastIndex
        } else {
            index != -1
        }
    } else if (dir == 1) {
        index + 1 < faces.size
    } else {
        index > 0
    }

    private fun readyToTurn(dir: Int): Boolean {
        val wanted = if (book) {
            if (dir == 1) listOf(index + 1, index + 2, index + 3) else listOf(index - 2, index - 1)
        } else {
            listOf(index + dir)
        }
        var ready = true
        wanted.forEach { faceIndex ->
            val face = faces.getOrNull(faceIndex) ?: return@forEach
            if (face.source >= 0 && !textures.containsKey(face.source)) {
                requestPage(face.source, true)
                ready = false
            }
        }
        return ready
    }

    fun seek(page: Int) {
        val next = page.coerceIn(0, max(0, count - 1))
        if (!textures.containsKey(next)) {
            pendingSeek = next
            requestPage(next, true)
            return
        }
        animationDuration = 0.0
        progress = 0.0
        drag = false
        source = next
        rebuild(next)
        pan = 0.0
        zoom = 1f
        panX = 0f
        panY = 0f
        nextFrame()
    }

    fun flip(dir: Int) {
        if (animationDuration > 0 || drag) return
        if (!book && faces.getOrNull(index)?.wide == true) {
            if (dir == 1 && pan < 1) {
                pan = 1.0
                nextFrame()
                return
            }
            if (dir == -1 && pan > 0) {
                pan = 0.0
                nextFrame()
                return
            }
        }
        if (!canTurn(dir)) {
            boundary(dir == 1)
            return
        }
        if (!readyToTurn(dir)) {
            pendingDirection = dir
            return
        }
        direction = dir
        grabY = 0.5
        moveY = 0.5
        animate(1.0)
    }

    fun down(rawX: Double, y: Double) {
        if (animationDuration > 0) return
        val x = if (reversed) 1 - rawX else rawX
        pointerX = rawX.toFloat()
        pointerY = y.toFloat()
        panStartX = panX
        panStartY = panY
        startX = x
        lastX = x
        lastTime = SystemClock.uptimeMillis()
        velocity = 0.0
        direction = if (x < 0.5) -1 else 1
        if (zoom > 1.001) return
        if (!canTurn(direction)) return
        if (!readyToTurn(direction)) return
        drag = true
        spreadTurn = false
        spreadLastX = x
        edgePull = 0.0
        edgeDirection = 0
        grabY = 1 - y
        moveY = grabY
        progress = 0.0
    }

    fun move(rawX: Double, y: Double) {
        if (zoom > 1.001) {
            panX = panStartX - (rawX.toFloat() - pointerX) * viewSw / zoom
            panY = panStartY + (y.toFloat() - pointerY) * viewSh / zoom
            clampPan()
            return
        }
        if (!drag) return
        val x = if (reversed) 1 - rawX else rawX
        val now = SystemClock.uptimeMillis()
        val elapsed = max(1L, now - lastTime)
        velocity = 0.65 * velocity + 0.35 * ((x - lastX) / elapsed * 1000)
        lastX = x
        lastTime = now
        if (!book && faces.getOrNull(index)?.wide == true && !spreadTurn) {
            val dx = x - spreadLastX
            spreadLastX = x
            pan = (pan - dx * 2).coerceIn(0.0, 1.0)
            val edge = if (pan >= 0.999 && dx < 0) {
                1
            } else if (pan <= 0.001 && dx > 0) {
                -1
            } else {
                0
            }
            if (edge != 0 && canTurn(edge)) {
                if (edgeDirection != edge) {
                    edgeDirection = edge
                    edgePull = 0.0
                }
                edgePull += abs(dx)
                if (edgePull > 0.055) {
                    direction = edge
                    grabY = 1 - y
                    moveY = grabY
                    startX = x
                    spreadTurn = true
                    progress = 0.015
                }
            } else {
                edgeDirection = 0
                edgePull = 0.0
            }
            return
        }
        moveY = 1 - y
        val moved = (if (direction == 1) startX - x else x - startX) * width
        progress = (moved / max(1.0, 1.5 * height / max(0.1, ph.toDouble()))).coerceIn(0.0, 1.0)
    }

    fun up(rawX: Double, cancel: Boolean, tapped: Boolean) {
        if (!drag) return
        drag = false
        if (tapped || cancel) {
            animate(0.0)
            return
        }
        if (!book && faces.getOrNull(index)?.wide == true && !spreadTurn) return
        val x = if (reversed) 1 - rawX else rawX
        val speed = if (direction == 1) -velocity else velocity
        val travel = abs(x - startX)
        val commit = progress > 0.27 || (travel > 0.045 && speed > 0.36) || (travel > 0.13 && progress > 0.10)
        animate(if (progress > 0.018 && commit) 1.0 else 0.0)
    }

    fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, 4f)
        drag = false
        progress = 0.0
        if (zoom == 1f) {
            panX = 0f
            panY = 0f
        }
        clampPan()
    }
    fun zoomBy(factor: Float) = setZoom(zoom * factor)
    fun doubleTap() = setZoom(if (zoom > 1.01f) 1f else 2f)
    private fun clampPan() {
        val xLimit = max(0f, viewSw / 2 * (1 - 1 / zoom))
        val yLimit = max(0f, viewSh / 2 * (1 - 1 / zoom))
        panX = panX.coerceIn(-xLimit, xLimit)
        panY = panY.coerceIn(-yLimit, yLimit)
    }
    private fun animate(to: Double) {
        animationFrom = progress
        animationTo = to
        animationY = moveY
        animationStart = SystemClock.uptimeMillis()
        val distance = abs(to - progress)
        animationDuration = if (to == 0.0) max(180.0, min(520.0, 170 + 410 * distance)) else 250 + 240 * distance
        nextFrame()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (count == 0 || faces.isEmpty()) return
        val current = currentSource()
        if (!textures.containsKey(current)) {
            requestPage(current, true)
            return
        }
        if (animationDuration > 0) {
            val a = ((SystemClock.uptimeMillis() - animationStart) / animationDuration).coerceIn(0.0, 1.0)
            val ease = a * a * (3 - 2 * a)
            progress = animationFrom + (animationTo - animationFrom) * ease
            if (animationTo == 0.0) moveY = animationY + (grabY - animationY) * ease
            if (a >= 1) {
                if (animationTo == 1.0) {
                    index = if (book) {
                        if (direction == 1) {
                            if (index == -1) 1 else min(faces.lastIndex, index + 2)
                        } else if (index == faces.lastIndex) {
                            faces.size - 3
                        } else if (index == 1) {
                            -1
                        } else {
                            index - 2
                        }
                    } else {
                        (index + direction).coerceIn(0, faces.lastIndex)
                    }
                    pan = if (direction == 1) {
                        0.0
                    } else if (faces.getOrNull(index)?.wide == true) {
                        1.0
                    } else {
                        0.0
                    }
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                }
                progress = 0.0
                moveY = grabY
                spreadTurn = false
                animationDuration = 0.0
                source = currentSource()
                rebuild(source)
            }
        }
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, grain)
        glActiveTexture(GL_TEXTURE0)
        scalar("uReaderGray", if (readerGray) 1f else 0f)
        scalar("uInvert", if (inverted) 1f else 0f)
        val candidate = if (book) {
            listOfNotNull(faces.getOrNull(max(0, index)), faces.getOrNull(max(0, index + 1)))
        } else {
            listOfNotNull(faces.getOrNull(index))
        }
        ph = candidate.map { it.aspect }.filter { it > 0.7f && it < 2.4f }.maxOrNull() ?: 1.36f
        val fit = min(width / ((if (book) 2f else 1f) + 0.1f), height / (ph + 0.1f))
        viewSw = width / fit
        viewSh = height / fit
        val sw = viewSw / zoom
        val sh = viewSh / zoom
        android.opengl.Matrix.orthoM(
            projection,
            0,
            -sw / 2 + panX,
            sw / 2 + panX,
            -sh / 2 + panY,
            sh / 2 + panY,
            -3f,
            3f,
        )
        glUniformMatrix4fv(u("uProj"), 1, false, projection, 0)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val renderStart = SystemClock.elapsedRealtimeNanos()
        drawBook()
        if (progress > 0.001) {
            val elapsed = SystemClock.elapsedRealtimeNanos() - renderStart
            measuredFrames++
            measuredNanos += elapsed
            measuredMaxNanos = max(measuredMaxNanos, elapsed)
        }
        if (!drag && animationDuration == 0.0 && measuredFrames > 0) {
            val average = measuredNanos / measuredFrames / 1_000_000.0
            val slowest = measuredMaxNanos / 1_000_000.0
            Log.i("NativePhysicalV40", "curl frames=$measuredFrames cpuMeanMs=$average cpuMaxMs=$slowest")
            measuredFrames = 0
            measuredNanos = 0
            measuredMaxNanos = 0
        }
        if (!drag && animationDuration == 0.0) {
            val selectedSource = currentSource()
            if (selectedSource != lastReported) {
                lastReported = selectedSource
                selected(selectedSource)
            }
            val retained = (selectedSource - 2..selectedSource + 3).filter { it in 0 until count }.toSet()
            listOf(1, -1, 2, -2, 3).map { selectedSource + it }.filter { it in retained }
                .forEach { if (!textures.containsKey(it)) requestPage(it, false) }
            val stale = textures.keys.filter { it !in retained }
            stale.forEach { key -> textures.remove(key)?.let { glDeleteTextures(1, intArrayOf(it.id), 0) } }
        }
        if (animationDuration > 0) nextFrame()
    }

    private fun rect(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        z: Float,
        u0: Float = 0f,
        u1: Float = 1f,
    ) = floatArrayOf(
        x0, y0, z, u0, 0f, x1, y0, z, u1, 0f, x0, y1, z, u0, 1f,
        x0, y1, z, u0, 1f, x1, y0, z, u1, 0f, x1, y1, z, u1, 1f,
    )

    private fun draw(
        vertices: FloatArray,
        face: Face?,
        light: Float = 1f,
        gray: Boolean = false,
        turning: Boolean = false,
        reverse: Boolean = false,
        mesh: Boolean = false,
        zOffset: Float = 0f,
        shadow: FloatArray? = null,
        uvMapped: Boolean = false,
    ) {
        if (vertices.isEmpty() || face == null) return
        val texture = if (face.source ==
            -1
        ) {
            notice
        } else if (face.source == -2) {
            backing
        } else {
            textures[face.source]?.id ?: return
        }
        val crop = if (face.side >= 0) 0.5f else 1f
        glUniform2f(u("uCrop"), if (face.side >= 0) face.side * 0.5f else 0f, crop)
        scalar("uGray", if (gray) 1f else 0f)
        scalar("uShade", light)
        scalar("uSpineShadow", if (book && shadow == null) 1f else 0f)
        val local = if (direction == -1) 2f else 1f
        scalar(
            "uSpineLocal",
            if (book && turning) {
                if (reverse) 3f - local else local
            } else {
                0f
            },
        )
        scalar("uShadowPass", if (shadow != null) 1f else 0f)
        glUniform4f(
            u("uShadow"),
            shadow?.get(0) ?: 0f,
            shadow?.get(1) ?: 0f,
            shadow?.get(2) ?: 0f,
            if (shadow !=
                null
            ) {
                1f
            } else {
                0f
            },
        )
        if (mesh) {
            val sx = if (direction == 1) 1f else -1f
            val offset = if (book) {
                0f
            } else if (direction == 1) {
                -0.5f
            } else {
                0.5f
            }
            glUniform4f(u("uModel"), sx, offset, ph, -ph / 2)
            scalar("uReverseUv", if (direction == -1 && !uvMapped) 1f else 0f)
        } else {
            glUniform4f(u("uModel"), 1f, 0f, 1f, 0f)
            scalar("uReverseUv", 0f)
        }
        scalar("uZOffset", zOffset)
        glBindTexture(GL_TEXTURE_2D, texture)
        if (buffer.capacity() <
            vertices.size
        ) {
            buffer =
                ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        buffer.clear()
        buffer.put(vertices)
        buffer.position(0)
        val pos = glGetAttribLocation(program, "aPos")
        val uv = glGetAttribLocation(program, "aUv")
        glEnableVertexAttribArray(pos)
        glEnableVertexAttribArray(uv)
        glVertexAttribPointer(pos, 3, GL_FLOAT, false, 20, buffer)
        buffer.position(3)
        glVertexAttribPointer(uv, 2, GL_FLOAT, false, 20, buffer)
        glDrawArrays(GL_TRIANGLES, 0, vertices.size / 5)
    }

    private fun page(n: Int, left: Float, right: Float, z: Float = -0.07f) {
        val face = faces.getOrNull(n) ?: return
        val offset = if (!book && face.wide) {
            if (direction == -1) 0.5f else 0f
        } else {
            0f
        }
        draw(rect(left, -ph / 2, right, ph / 2, z, offset, if (!book && face.wide) offset + 0.5f else 1f), face)
    }

    private fun shadows(back: FloatArray, zOffset: Float) {
        if (progress <= 0.001) return
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        val sign = if (book && direction == -1) -1f else 1f
        val layers = arrayOf(
            floatArrayOf(0.094f, 0.095f, 1f, -0.0633f),
            floatArrayOf(0.072f, 0.15f, 1f, -0.0636f),
            floatArrayOf(0.050f, 0.205f, 1f, -0.0639f),
            floatArrayOf(0.012f, 0.33f, 0.30f, -0.0627f),
            floatArrayOf(0.002f, 0.38f, 0.11f, -0.0624f),
        )
        for (layer in layers) {
            draw(
                back,
                Face(-2),
                layer[1],
                mesh = true,
                zOffset = zOffset,
                shadow = floatArrayOf(sign * (layer[2] + layer[0]), -(0.82f * layer[2] + layer[0]), layer[3]),
            )
        }
        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawBook() {
        val closed = book && (index == -1 || index == faces.lastIndex)
        if (closed && progress <= 0.001) {
            page(if (index == faces.lastIndex) faces.lastIndex else 0, 0f, 1f)
            return
        }
        if (book && progress <= 0.001) {
            page(index, -1f, 0f)
            page(index + 1, 0f, 1f)
            return
        }
        if (!book && progress <= 0.001) {
            val face = faces.getOrNull(index) ?: return
            val offset = if (face.wide) (pan * 0.5).toFloat() else 0f
            draw(rect(-0.5f, -ph / 2, 0.5f, ph / 2, -0.07f, offset, if (face.wide) offset + 0.5f else 1f), face)
            return
        }
        val front = PhysicalPageMesh.create(progress, grabY, moveY, back = false, folded = false)
        val back = PhysicalPageMesh.create(progress, grabY, moveY, back = true, folded = true)
        if (book) {
            val frontIndex: Int
            val backIndex: Int
            if (direction == 1) {
                if (index == -1) {
                    page(2, 0f, 1f)
                    frontIndex = 0
                    backIndex = 1
                } else {
                    page(index, -1f, 0f)
                    page(
                        if (index ==
                            faces.size - 3
                        ) {
                            faces.lastIndex
                        } else {
                            index + 3
                        },
                        0f,
                        1f,
                    )
                    frontIndex = index + 1
                    backIndex =
                        index + 2
                }
            } else {
                if (index == 1) {
                    page(2, 0f, 1f, -0.075f)
                } else {
                    page(index - 2, -1f, 0f)
                    page(index + 1, 0f, 1f)
                }
                frontIndex = index
                backIndex = index - 1
            }
            shadows(back, -0.001f)
            draw(front, faces.getOrNull(frontIndex), 0.99f, turning = true, mesh = true)
            draw(
                back,
                faces.getOrNull(backIndex),
                0.96f,
                turning = true,
                reverse = true,
                mesh = true,
                zOffset = -0.001f,
            )
        } else {
            page(index + direction, -0.5f, 0.5f, -0.085f)
            val current = faces.getOrNull(index)
            if (current?.wide ==
                true
            ) {
                for (i in 3 until front.size step 5) {
                    front[i] =
                        (if (direction == 1) 0.5f else 0f) + (if (direction == -1) 1f - front[i] else front[i]) * 0.5f
                }
            }
            shadows(back, 0f)
            draw(front, current, 0.99f, mesh = true, uvMapped = current?.wide == true)
            val incoming = faces.getOrNull(index + direction)
            if (incoming?.wide ==
                true
            ) {
                for (i in 3 until back.size step 5) {
                    back[i] =
                        (if (direction == -1) 0.5f else 0f) + (if (direction == -1) 1f - back[i] else back[i]) * 0.5f
                }
            }
            draw(
                back,
                incoming ?: Face(-2),
                if (incoming !=
                    null
                ) {
                    0.97f
                } else {
                    0.93f
                },
                gray = incoming != null,
                mesh = true,
                uvMapped = incoming?.wide == true,
            )
        }
    }

    fun release() {
        textures.values.forEach { glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures.clear()
        glDeleteTextures(3, intArrayOf(grain, backing, notice), 0)
        glDeleteProgram(program)
    }
}
