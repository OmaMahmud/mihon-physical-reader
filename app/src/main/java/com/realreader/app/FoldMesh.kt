package com.realreader.app

import kotlin.math.*

/** Native implementation of the approved HTML v4.0 crease equations. No Mihon dependency. */
object FoldMesh {
    fun create(progress: Float, grab: Float, move: Float, folded: Boolean): FloatArray {
        val t=progress.coerceIn(0f,1f).toDouble(); val gy=grab.toDouble()
        var tx=1-2*t; var ty=move.toDouble()
        val reach=hypot(tx,ty-gy)
        if(reach>1.04) { tx*=1.04/reach; ty=gy+(ty-gy)*1.04/reach }
        val rx=tx; val ry=ty
        fun safe(f: Double): Boolean {
            val cx=1+(rx-1)*f; val cy=gy+(ry-gy)*f; val dx=cx-1; val dy=cy-gy
            val mx=(1+cx)*.5; val my=(gy+cy)*.5
            return -mx*dx-my*dy>=-1e-7 && -mx*dx+(1-my)*dy>=-1e-7
        }
        if(!safe(1.0)) { var lo=0.0; var hi=1.0; repeat(24) { val m=(lo+hi)*.5; if(safe(m))lo=m else hi=m }; tx=1+(rx-1)*lo; ty=gy+(ry-gy)*lo }
        val d=hypot(tx-1,ty-gy); val nx=if(d>1e-5)(tx-1)/d else -1.0; val ny=if(d>1e-5)(ty-gy)/d else 0.0
        val mx=(1+tx)*.5; val my=(gy+ty)*.5
        data class P(val x: Double,val y: Double)
        fun signed(p: P)=(p.x-mx)*nx+(p.y-my)*ny
        val out=ArrayList<Float>(88*56*30)
        fun triangle(a: P,b: P,c: P) {
            val points=listOf(a,b,c); val clip=ArrayList<P>(4)
            for(k in 0..2) { val p=points[k]; val q=points[(k+1)%3]; val s=signed(p); val r=signed(q)
                val inside=if(folded)s<=0 else s>=0; val other=if(folded)r<=0 else r>=0
                if(inside)clip.add(p)
                if(inside!=other) { val f=s/(s-r); clip.add(P(p.x+(q.x-p.x)*f,p.y+(q.y-p.y)*f)) }
            }
            fun vertex(p: P) {
                val s=signed(p); var x=p.x; var y=p.y; var z=.012
                if(d>1e-5 && folded) { x-=2*s*nx; y-=2*s*ny; z+=.075*sin(PI*t)*exp(-abs(s)/.16) }
                if(d>1e-5) z+=.042*sin(PI*t)*exp(-abs(s)/.025)
                if(folded)z+=.003
                out.add(x.toFloat());out.add(y.toFloat());out.add(z.toFloat());out.add(p.x.toFloat());out.add(1-p.y.toFloat())
            }
            for(k in 1 until clip.size-1) { vertex(clip[0]);vertex(clip[k]);vertex(clip[k+1]) }
        }
        for(i in 0 until 88)for(j in 0 until 56) {
            val a=P(i/88.0,j/56.0);val b=P((i+1)/88.0,j/56.0);val c=P(i/88.0,(j+1)/56.0);val d2=P((i+1)/88.0,(j+1)/56.0)
            triangle(a,b,c);triangle(c,b,d2)
        }
        return out.toFloatArray()
    }
}
object ReadingRules {
    fun next(page: Int, count: Int, double: Boolean): Int = (page + if(double && page>0)2 else 1).coerceAtMost(count-1)
    fun previous(page: Int, double: Boolean): Int = (page - if(double && page>1)2 else 1).coerceAtLeast(0)
    fun edgeTurn(zoom: Float, pan: Float, bound: Float, dx: Float, dy: Float): Boolean =
        abs(dx)>80 && abs(dx)>abs(dy)*1.3f && (zoom<=1.01f || (dx<0 && pan<=-bound+2) || (dx>0 && pan>=bound-2))
}
