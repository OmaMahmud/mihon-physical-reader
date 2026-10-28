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
        val out=FloatArray(88*56*2*6*5);var size=0
        val px=DoubleArray(4);val py=DoubleArray(4)
        val xs=DoubleArray(3);val ys=DoubleArray(3)
        fun signed(x:Double,y:Double)=(x-mx)*nx+(y-my)*ny
        val lift=.075*sin(PI*t);val lip=.042*sin(PI*t)
        fun vertex(x0:Double,y0:Double) {
            val s=signed(x0,y0);var x=x0;var y=y0;var z=.012
            if(d>1e-5 && folded) {x-=2*s*nx;y-=2*s*ny;z+=lift*exp(-abs(s)/.16)}
            if(d>1e-5)z+=lip*exp(-abs(s)/.025)
            if(folded)z+=.003
            out[size++]=x.toFloat();out[size++]=y.toFloat();out[size++]=z.toFloat();out[size++]=x0.toFloat();out[size++]=1-y0.toFloat()
        }
        fun triangle(ax:Double,ay:Double,bx:Double,by:Double,cx:Double,cy:Double) {
            xs[0]=ax;xs[1]=bx;xs[2]=cx;ys[0]=ay;ys[1]=by;ys[2]=cy
            var count=0
            for(k in 0..2) {
                val n=(k+1)%3;val s=signed(xs[k],ys[k]);val r=signed(xs[n],ys[n])
                val inside=if(folded)s<=0 else s>=0;val other=if(folded)r<=0 else r>=0
                if(inside){px[count]=xs[k];py[count++]=ys[k]}
                if(inside!=other){val f=s/(s-r);px[count]=xs[k]+(xs[n]-xs[k])*f;py[count++]=ys[k]+(ys[n]-ys[k])*f}
            }
            for(k in 1 until count-1){vertex(px[0],py[0]);vertex(px[k],py[k]);vertex(px[k+1],py[k+1])}
        }
        for(i in 0 until 88)for(j in 0 until 56) {
            val x=i/88.0;val y=j/56.0;val xx=(i+1)/88.0;val yy=(j+1)/56.0
            triangle(x,y,xx,y,x,yy);triangle(x,yy,xx,y,xx,yy)
        }
        return out.copyOf(size)
    }
}
object ReadingRules {
    fun next(page: Int, count: Int, double: Boolean): Int = (page + if(double && page>0)2 else 1).coerceAtMost(count-1)
    fun previous(page: Int, double: Boolean): Int = (page - if(double && page>1)2 else 1).coerceAtLeast(0)
    fun edgeTurn(zoom: Float, pan: Float, bound: Float, dx: Float, dy: Float): Boolean =
        abs(dx)>80 && abs(dx)>abs(dy)*1.3f && (zoom<=1.01f || (dx<0 && pan<=-bound+2) || (dx>0 && pan>=bound-2))
}
