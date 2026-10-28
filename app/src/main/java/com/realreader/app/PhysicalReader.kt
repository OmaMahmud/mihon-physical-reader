package com.realreader.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Hardware rendered full-screen paper, native gestures; texture data supplied by local importers. */
class PhysicalReader(context: Context): GLSurfaceView(context) {
    var onTurn: (Int)->Unit = {}; var onControls: ()->Unit = {}
    var rtl=false
        set(value) {field=value;paper.rtl=value;requestRender()}
    var bookMode=false
        set(value) {field=value;paper.book=value;requestRender()}
    var ready=false
    private val paper=PaperRenderer()
    private var zoom=1f; private var panX=0f; private var panY=0f
    private var startX=0f;private var startY=0f;private var lastX=0f;private var lastY=0f
    private var velocity=0f;private var lastTime=0L
    private var originalPan=0f;private var dragging=false;private var pinched=false;private var turning=0
    private var animator: ValueAnimator?=null
    private val scale=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { pinched=true; zoom=(zoom*d.scaleFactor).coerceIn(1f,4f);clamp();return true }
    })
    private val taps=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent)=true
        override fun onDoubleTap(e: MotionEvent): Boolean { pinched=true;zoom=if(zoom>1)1f else 2f;panX=0f;panY=0f;clamp();return true }
    })
    init { setEGLContextClientVersion(2);setRenderer(paper);renderMode=RENDERMODE_WHEN_DIRTY;preserveEGLContextOnPause=true; contentDescription="Comic page. Pinch to zoom, drag to pan, swipe at an edge to turn; center tap for controls." }
    fun frames(current: Bitmap,next: Bitmap?,previous: Bitmap?) {
        ready=true;queueEvent { paper.setFrames(current,next,previous) };requestRender()
    }
    fun resetZoom() { zoom=1f;panX=0f;panY=0f;clamp() }
    private fun horizontalBound()=max(0f,width*(paper.fitX*zoom-1)/2)
    private fun clamp() { val bx=horizontalBound();val by=max(0f,height*(paper.fitY*zoom-1)/2);panX=panX.coerceIn(-bx,bx);panY=panY.coerceIn(-by,by);paper.zoom=zoom;paper.panX=if(width>0)panX*2/width else 0f;paper.panY=if(height>0)-panY*2/height else 0f;requestRender() }
    fun turn(logical: Int) { if(!ready || animator!=null)return; val physical=logical*(if(rtl)-1 else 1); turning=logical;paper.backward=physical<0;paper.target=logical;paper.grab=.5f;paper.move=.5f;animate(true) }
    private fun animate(commit: Boolean) {
        val end=if(commit)1f else 0f
        animator=ValueAnimator.ofFloat(paper.progress,end).apply {
            val distance=abs(end-paper.progress)
            duration=(if(commit)250+240*distance else (170+410*distance).coerceIn(180f,520f)).toLong()
            interpolator=android.animation.TimeInterpolator {it*it*(3-2*it)}
            val initialMove=paper.move;val rest=paper.grab
            addUpdateListener { paper.progress=it.animatedValue as Float;if(!commit)paper.move=initialMove+(rest-initialMove)*it.animatedFraction;requestRender() }
            addListener(object: android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animator=null
                    if(commit) { ready=false; onTurn(turning); resetZoom() }
                    paper.progress=0f;requestRender()
                }
            });start()
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN)pinched=false
        scale.onTouchEvent(e);taps.onTouchEvent(e)
        if(animator!=null || !ready)return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=e.x;lastX=e.x;startY=e.y;lastY=e.y;originalPan=panX;dragging=false;velocity=0f;lastTime=e.eventTime }
            MotionEvent.ACTION_POINTER_DOWN -> { pinched=true; if(dragging) {paper.progress=0f;dragging=false;requestRender()} }
            MotionEvent.ACTION_MOVE -> {
                if(pinched || scale.isInProgress)return true
                val dx=e.x-startX;val dy=e.y-startY
                if(zoom<=1.01f && abs(dx)>16 && abs(dx)>abs(dy)*1.3f) {
                    if(!dragging) { dragging=true;turning=(if(dx<0)1 else -1)*(if(rtl)-1 else 1);paper.backward=dx>0;paper.target=turning;paper.grab=1-startY/height }
                    val elapsed=max(1L,e.eventTime-lastTime)
                    velocity=.65f*velocity+.35f*((e.x-lastX)/width/elapsed*1000)
                    val travel=max(1f,1.5f*paper.fitX*width/(if(bookMode)2 else 1))
                    paper.move=1-e.y/height;paper.progress=(abs(dx)/travel).coerceIn(.001f,.99f);requestRender()
                } else if(zoom>1.01f) {panX+=e.x-lastX;panY+=e.y-lastY;clamp()}
                lastX=e.x;lastY=e.y;lastTime=e.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val dx=e.x-startX;val dy=e.y-startY
                if(pinched)return true
                if(dragging) { val flick=if(dx<0)-velocity else velocity
                    val travel=abs(dx)/width
                    animate(paper.progress>.27f || travel>.045f && flick>.36f || travel>.13f && paper.progress>.10f);dragging=false }
                else if(zoom>1.01f && ReadingRules.edgeTurn(zoom,originalPan,horizontalBound(),dx,dy)) turn((if(dx<0)1 else -1)*(if(rtl)-1 else 1))
                else if(hypot(dx,dy)<16) {
                    if(e.x/width in .3f.. .7f)onControls()
                    else if(zoom<=1.01f)turn((if(e.x>width*.7)1 else -1)*(if(rtl)-1 else 1))
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> if(dragging) {animate(false);dragging=false}
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    fun dispose() { animator?.cancel();queueEvent {paper.release()} }
}
private class PaperRenderer: GLSurfaceView.Renderer {
    @Volatile var progress=0f;@Volatile var grab=.5f;@Volatile var move=.5f
    @Volatile var backward=false;@Volatile var target=1
    @Volatile var zoom=1f;@Volatile var panX=0f;@Volatile var panY=0f
    @Volatile var fitX=1f;@Volatile var fitY=1f
    @Volatile var rtl=false
    @Volatile var book=false
    private var program=0;private var screenRatio=1f;private var ratio=1f
    private val textures=IntArray(3);private var hasNext=false;private var hasPrev=false
    private var source: Array<Bitmap?> = arrayOfNulls(3)
    private var buffer=ByteBuffer.allocateDirect(88*56*2*6*5*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs="""attribute vec3 aPosition;attribute vec2 aUv;varying vec2 vUv;varying float vZ;uniform vec4 uRect;uniform vec3 uZoom;uniform float uMirror;uniform vec2 uLeaf;uniform vec2 uUv;
        void main(){float x=mix(aPosition.x,1.0-aPosition.x,uMirror);x=uLeaf.x+x*uLeaf.y;vec2 p=vec2((x*2.0-1.0)*uRect.x,(aPosition.y*2.0-1.0)*uRect.y);gl_Position=vec4(p*uZoom.x+uZoom.yz,-aPosition.z,1.0);vUv=vec2(uUv.x+mix(aUv.x,1.0-aUv.x,uMirror)*uUv.y,aUv.y);vZ=aPosition.z;}"""
        val fs="""precision mediump float;varying vec2 vUv;varying float vZ;uniform sampler2D uTexture;uniform float uBack;
        void main(){vec4 c=texture2D(uTexture,vUv);float shade=1.0-min(.32,vZ*2.5);vec3 paper=mix(c.rgb,vec3(.88,.87,.83),.86);gl_FragColor=vec4(mix(c.rgb,paper,uBack)*shade,1.0);}"""
        fun shader(type:Int,s:String):Int { val id=glCreateShader(type);glShaderSource(id,s);glCompileShader(id); val status=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,status,0);check(status[0]!=0){glGetShaderInfoLog(id)};return id }
        program=glCreateProgram();val v=shader(GL_VERTEX_SHADER,vs);val f=shader(GL_FRAGMENT_SHADER,fs);glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f)
        glGenTextures(3,textures,0); source.forEachIndexed { i,b -> if(b!=null)upload(i,b) }
        glClearColor(.02f,.028f,.043f,1f);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL)
    }
    override fun onSurfaceChanged(gl: GL10?,width:Int,height:Int) {glViewport(0,0,width,height);screenRatio=width.toFloat()/height}
    private fun upload(i:Int,b:Bitmap) {glBindTexture(GL_TEXTURE_2D,textures[i]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GL_TEXTURE_2D,0,b,0)}
    fun setFrames(c:Bitmap,n:Bitmap?,p:Bitmap?) {
        val old=source;source=arrayOf(c,n,p);ratio=c.width.toFloat()/c.height;hasNext=n!=null;hasPrev=p!=null
        source.forEachIndexed { i,b -> if(b!=null)upload(i,b) };old.filterNotNull().filter { it !in source }.forEach {it.recycle()}
    }
    private fun flat(z:Float=0f)=floatArrayOf(0f,0f,z,0f,1f,1f,0f,z,1f,1f,0f,1f,z,0f,0f,0f,1f,z,0f,0f,1f,0f,z,1f,1f,1f,1f,z,1f,0f)
    private fun draw(data:FloatArray,texture:Int,back:Float=0f,mirror:Float=0f,leafBase:Float=0f,leafScale:Float=1f,uvBase:Float=0f,uvScale:Float=1f) {
        if(data.isEmpty())return
        buffer.clear();buffer.put(data);buffer.position(0)
        val pos=glGetAttribLocation(program,"aPosition");val uv=glGetAttribLocation(program,"aUv")
        glEnableVertexAttribArray(pos);glEnableVertexAttribArray(uv);glVertexAttribPointer(pos,3,GL_FLOAT,false,20,buffer);buffer.position(3);glVertexAttribPointer(uv,2,GL_FLOAT,false,20,buffer)
        glUniform2f(glGetUniformLocation(program,"uLeaf"),leafBase,leafScale);glUniform2f(glGetUniformLocation(program,"uUv"),uvBase,uvScale);glUniform1f(glGetUniformLocation(program,"uBack"),back);glUniform1f(glGetUniformLocation(program,"uMirror"),mirror);glBindTexture(GL_TEXTURE_2D,textures[texture]);glDrawArrays(GL_TRIANGLES,0,data.size/5)
    }
    override fun onDrawFrame(gl:GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);if(source[0]==null)return
        glUseProgram(program)
        val fitRatio=if(book && ratio<1.25f)ratio*2 else ratio
        val x=if(fitRatio>screenRatio)1f else fitRatio/screenRatio;val y=if(fitRatio>screenRatio)screenRatio/fitRatio else 1f
        fitX=x;fitY=y
        glUniform4f(glGetUniformLocation(program,"uRect"),x,y,0f,0f);glUniform3f(glGetUniformLocation(program,"uZoom"),zoom,panX,panY)
        val valid=if(target>0)hasNext else hasPrev
        val solo=book && ratio<1.25f
        if(progress<=0 || !valid) {draw(flat(),0,leafBase=if(solo && !rtl).5f else 0f,leafScale=if(solo).5f else 1f);return}
        val incoming=if(target>0)1 else 2
        val mirror=if(backward)1f else 0f
        if(book) {
            val incomingSolo=source[incoming]!!.width.toFloat()/source[incoming]!!.height<1.25f
            draw(flat(-.02f),incoming,leafBase=if(incomingSolo && !rtl).5f else 0f,leafScale=if(incomingSolo).5f else 1f)
            // Keep the opposite leaf stationary. Only the outside leaf rotates about the spine.
            if(!solo)draw(flat(-.005f),0,leafBase=if(backward).5f else 0f,leafScale=.5f,uvBase=if(backward).5f else 0f,uvScale=.5f)
            val leafBase=if(backward)0f else .5f
            val frontBase=if(solo)0f else leafBase
            val frontScale=if(solo)1f else .5f
            draw(FoldMesh.create(progress,grab,move,false),0,0f,mirror,leafBase,.5f,frontBase,frontScale)
            // Verso belongs to the next physical leaf, correctly reversed along the fold.
            draw(FoldMesh.create(progress,grab,move,true),incoming,0f,mirror,leafBase,.5f,if(incomingSolo)1f else if(backward)1f else .5f,if(incomingSolo)-1f else -.5f)
        } else {
            draw(flat(-.01f),incoming)
            draw(FoldMesh.create(progress,grab,move,false),0,0f,mirror)
            draw(FoldMesh.create(progress,grab,move,true),incoming,1f,mirror)
        }
    }
    fun release() {glDeleteTextures(3,textures,0);source.filterNotNull().forEach {it.recycle()};source=arrayOfNulls(3);glDeleteProgram(program)}
}
