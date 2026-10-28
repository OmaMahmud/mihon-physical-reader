package eu.kanade.tachiyomi.ui.reader.viewer.physical

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** Direct native port of the approved v4.0 mesh(), including crease clipping and spine safety. */
internal object PhysicalPageMesh {
    fun create(t: Double, grabY: Double, moveY: Double, back: Boolean, folded: Boolean): FloatArray {
        val phase = t.coerceIn(0.0, 1.0)
        val gx = 1.0
        val gy = grabY
        var tx = 1 - 2 * phase
        var ty = moveY
        val reach = hypot(tx, ty - gy)
        if (reach > 1.04) {
            val scale = 1.04 / reach
            tx *= scale
            ty = gy + (ty - gy) * scale
        }
        val requestedX = tx
        val requestedY = ty
        fun spineSafe(f: Double): Boolean {
            val cx = gx + (requestedX - gx) * f
            val cy = gy + (requestedY - gy) * f
            val ddx = cx - gx
            val ddy = cy - gy
            val mx = (gx + cx) * 0.5
            val my = (gy + cy) * 0.5
            return -mx * ddx - my * ddy >= -1e-7 && -mx * ddx + (1 - my) * ddy >= -1e-7
        }
        if (!spineSafe(1.0)) {
            var lo = 0.0
            var hi = 1.0
            repeat(24) {
                val mid = (lo + hi) * 0.5
                if (spineSafe(mid)) lo = mid else hi = mid
            }
            tx = gx + (requestedX - gx) * lo
            ty = gy + (requestedY - gy) * lo
        }
        val dx = tx - gx
        val dy = ty - gy
        val dist = hypot(dx, dy)
        val nx = if (dist > 1e-5) dx / dist else -1.0
        val ny = if (dist > 1e-5) dy / dist else 0.0
        val midX = (gx + tx) * 0.5
        val midY = (gy + ty) * 0.5
        fun signed(x: Double, y: Double) = (x - midX) * nx + (y - midY) * ny
        val output = FloatArray(88 * 56 * 2 * 6 * 5)
        val triangleX = DoubleArray(3)
        val triangleY = DoubleArray(3)
        val polygonX = DoubleArray(4)
        val polygonY = DoubleArray(4)
        val vertices = DoubleArray(20)
        var size = 0
        val lift = 0.075 * sin(PI * phase)
        val lip = 0.042 * sin(PI * phase)
        fun emit(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) {
            triangleX[0] = ax
            triangleY[0] = ay
            triangleX[1] = bx
            triangleY[1] = by
            triangleX[2] = cx
            triangleY[2] = cy
            var polygonSize = 0
            for (k in 0..2) {
                val next = (k + 1) % 3
                val x = triangleX[k]
                val y = triangleY[k]
                val nextX = triangleX[next]
                val nextY = triangleY[next]
                val sa = signed(x, y)
                val sb = signed(nextX, nextY)
                val inside = if (folded) sa <= 0 else sa >= 0
                val nextInside = if (folded) sb <= 0 else sb >= 0
                if (inside) {
                    polygonX[polygonSize] = x
                    polygonY[polygonSize++] = y
                }
                if (inside != nextInside) {
                    val q = sa / (sa - sb)
                    polygonX[polygonSize] = x + (nextX - x) * q
                    polygonY[polygonSize++] = y + (nextY - y) * q
                }
            }
            if (polygonSize < 3) return
            for (k in 0 until polygonSize) {
                val u = polygonX[k]
                val v = polygonY[k]
                val s = signed(u, v)
                var x = u
                var y = v
                var z = 0.012
                if (dist > 1e-5 && folded) {
                    x = u - 2 * s * nx
                    y = v - 2 * s * ny
                    z += lift * exp(-abs(s) / 0.16)
                }
                if (dist > 1e-5) z += lip * exp(-abs(s) / 0.025)
                if (folded) z += 0.003
                val base = k * 5
                vertices[base] = x
                vertices[base + 1] = y
                vertices[base + 2] = z
                vertices[base + 3] = if (back) 1 - u else u
                vertices[base + 4] = v
            }
            fun vertex(i: Int) {
                for (j in 0..4) output[size++] = vertices[i * 5 + j].toFloat()
            }
            for (k in 1 until polygonSize - 1) {
                vertex(0)
                vertex(if (back) k + 1 else k)
                vertex(if (back) k else k + 1)
            }
        }
        for (i in 0 until 88) {
            for (j in 0 until 56) {
                val x0 = i / 88.0
                val x1 = (i + 1) / 88.0
                val y0 = j / 56.0
                val y1 = (j + 1) / 56.0
                emit(x0, y0, x1, y0, x0, y1)
                emit(x0, y1, x1, y0, x1, y1)
            }
        }
        return output.copyOf(size)
    }
}
