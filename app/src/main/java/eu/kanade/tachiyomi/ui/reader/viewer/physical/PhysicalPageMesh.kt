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
        fun signed(p: DoubleArray) = (p[0] - midX) * nx + (p[1] - midY) * ny
        val output = FloatArray(88 * 56 * 2 * 6 * 5)
        var size = 0
        fun emit(triangle: Array<DoubleArray>) {
            val polygon = ArrayList<DoubleArray>(4)
            triangle.indices.forEach { k ->
                val a = triangle[k]
                val b = triangle[(k + 1) % 3]
                val sa = signed(a)
                val sb = signed(b)
                val ina = if (folded) sa <= 0 else sa >= 0
                val inb = if (folded) sb <= 0 else sb >= 0
                if (ina) polygon.add(a)
                if (ina != inb) {
                    val q = sa / (sa - sb)
                    polygon.add(doubleArrayOf(a[0] + (b[0] - a[0]) * q, a[1] + (b[1] - a[1]) * q))
                }
            }
            if (polygon.size < 3) return
            val vertices = polygon.map { p ->
                val u = p[0]
                val v = p[1]
                val s = signed(p)
                var x = u
                var y = v
                var z = 0.012
                if (dist > 1e-5 && folded) {
                    x = u - 2 * s * nx
                    y = v - 2 * s * ny
                    z += 0.075 * sin(PI * phase) * exp(-abs(s) / 0.16)
                }
                if (dist > 1e-5) z += 0.042 * sin(PI * phase) * exp(-abs(s) / 0.025)
                if (folded) z += 0.003
                doubleArrayOf(x, y, z, if (back) 1 - u else u, v)
            }
            fun vertex(i: Int) {
                for (value in vertices[i]) output[size++] = value.toFloat()
            }
            for (k in 1 until vertices.lastIndex) {
                vertex(0)
                vertex(if (back) k + 1 else k)
                vertex(if (back) k else k + 1)
            }
        }
        for (i in 0 until 88) {
            for (j in 0 until 56) {
                val a = doubleArrayOf(i / 88.0, j / 56.0)
                val b = doubleArrayOf((i + 1) / 88.0, j / 56.0)
                val c = doubleArrayOf(i / 88.0, (j + 1) / 56.0)
                val d = doubleArrayOf((i + 1) / 88.0, (j + 1) / 56.0)
                emit(arrayOf(a, b, c))
                emit(arrayOf(c, b, d))
            }
        }
        return output.copyOf(size)
    }
}
