package com.realreader.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReaderScreen(comic: Comic,store: LibraryStore,onBack:()->Unit,onHome:()->Unit,onNext:(Long)->Unit) {
    val context=LocalContext.current
    val activity=context as MainActivity
    var document by remember(comic.id){mutableStateOf<ComicDocument?>(null)}
    var page by rememberSaveable(comic.id){mutableIntStateOf(comic.page)}
    var scrub by remember(page){mutableFloatStateOf(page.toFloat())}
    var count by remember(comic.id){mutableIntStateOf(0)}
    var error by remember(comic.id){mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(true)}
    var controls by rememberSaveable{mutableStateOf(true)}
    var settings by rememberSaveable{mutableStateOf(false)}
    var rtl by rememberSaveable{mutableStateOf(store.prefs.getBoolean("rtl",false))}
    var layout by rememberSaveable{mutableStateOf(store.prefs.getString("layout","Auto")!!)}
    var orientation by rememberSaveable{mutableStateOf("Auto")}
    var brightness by rememberSaveable{mutableFloatStateOf(1f)}
    var marks by remember(comic.id){mutableStateOf((0 until JSONArray(comic.marks).length()).map {JSONArray(comic.marks).getInt(it)}.toSet())}
    val landscape=LocalConfiguration.current.screenWidthDp>LocalConfiguration.current.screenHeightDp
    val double=layout=="Double" || layout=="Auto" && landscape
    var starts by remember{mutableStateOf(listOf(0))}
    val view=remember(comic.id){PhysicalReader(context)}
    val lifecycle=LocalLifecycleOwner.current
    val latestPage by rememberUpdatedState(page)
    val latestCount by rememberUpdatedState(count)
    DisposableEffect(view,lifecycle) {
        val observer=LifecycleEventObserver{_,event ->when(event){Lifecycle.Event.ON_RESUME->view.onResume();Lifecycle.Event.ON_PAUSE->{view.onPause();if(latestCount>0)store.db.position(comic.id,latestPage,latestCount)};else->Unit}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);view.dispose();activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;activity.window.attributes=activity.window.attributes.apply {screenBrightness=-1f};if(latestCount>0)store.db.position(comic.id,latestPage,latestCount)}
    }
    LaunchedEffect(comic.id) {
        try {document=withContext(Dispatchers.IO){ComicDocument(context,comic)};count=document!!.count;page=page.coerceIn(0,count-1)} catch(e:Exception){error=e.message ?: "Unable to open comic";loading=false}
    }
    DisposableEffect(document) {val opened=document;onDispose {opened?.close()}}
    LaunchedEffect(document,double) {
        val doc=document ?: return@LaunchedEffect
        starts=withContext(Dispatchers.IO) {PageLayout.starts(BooleanArray(doc.count){doc.wide(it)},double)}
        page=starts.lastOrNull {it<=page} ?: 0
    }
    fun move(delta: Int) {
        val pos=starts.indexOfLast {it<=page}.coerceAtLeast(0)
        val next=starts.getOrNull(pos+delta)
        if(next!=null){page=next;controls=false}else {controls=true;view.ready=true}
    }
    view.bookMode=double;view.onTurn={move(it)};view.onControls={controls=!controls;settings=false};view.rtl=rtl
    LaunchedEffect(document,page,double,rtl,starts) {
        val doc=document ?: return@LaunchedEffect
        loading=true;view.ready=false
        try {
            val pos=starts.indexOfLast {it<=page}.coerceAtLeast(0)
            val frames=withContext(Dispatchers.IO) {
                fun make(index:Int): Bitmap {
                    val first=doc.page(index)
                    val paired=double && index>0 && !doc.wide(index) && index+1<doc.count && !doc.wide(index+1)
                    if(!paired)return first
                    val second=doc.page(index+1)
                    val h=1600;val w1=(h*first.width.toFloat()/first.height).toInt();val w2=(h*second.width.toFloat()/second.height).toInt()
                    val out=Bitmap.createBitmap(w1+w2,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(out);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val left=if(rtl)second else first;val right=if(rtl)first else second;val lw=if(rtl)w2 else w1
                    canvas.drawBitmap(left,null,RectF(0f,0f,lw.toFloat(),h.toFloat()),paint);canvas.drawBitmap(right,null,RectF(lw.toFloat(),0f,out.width.toFloat(),h.toFloat()),paint)
                    first.recycle();second.recycle();return out
                }
                val a=make(page);var b:Bitmap?=null;var c:Bitmap?=null
                try {b=starts.getOrNull(pos+1)?.let{make(it)};c=starts.getOrNull(pos-1)?.let{make(it)};Triple(a,b,c)}catch(e:Exception){a.recycle();b?.recycle();c?.recycle();throw e}
            }
            view.frames(frames.first,frames.second,frames.third);store.db.position(comic.id,page,count,starts.getOrNull(pos+1)==null);error=null
        } catch(e:Exception){error=e.message ?: "Page decoding failed";view.ready=false}
        loading=false
    }
    BackHandler {if(settings)settings=false else if(!controls)controls=true else onBack()}
    Box(Modifier.fillMaxSize().background(Color(0xFF05070B))) {
        AndroidView(factory={view},modifier=Modifier.fillMaxSize())
        if(loading)CircularProgressIndicator(Modifier.align(Alignment.Center))
        if(error!=null)Glass(Modifier.align(Alignment.Center).padding(24.dp)) {Text("Could not read this comic",style=MaterialTheme.typography.titleLarge);Text(error!!);Button(onClick=onBack,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text("Back to library")};TextButton(onClick=onHome){Text("Home")}}
        if(controls) {
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)) {
                Glass {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Action("arrow_back","Back",onBack);Text(comic.name,Modifier.weight(1f),maxLines=1);Action("home","Home",onHome)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        Text(if(page in marks)"Bookmarked" else "",Modifier.weight(1f))
                        Action("bookmark","Bookmark page") {marks=if(page in marks)marks-page else marks+page;store.db.marks(comic.id,JSONArray(marks.sorted()).toString())}
                        Action("zoom_out_map","Reset zoom"){view.resetZoom()}
                        Action("tune","Reader settings"){settings=true}
                    }
                }
            }
            Glass(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Action("chevron_left","Previous page"){view.turn(-1)}
                    Text("${page+1} / $count",Modifier.weight(1f))
                    Action("chevron_right","Next page"){view.turn(1)}
                }
                if(count>1)Slider(value=scrub,onValueChange={scrub=it},onValueChangeFinished={page=starts.lastOrNull {it<=scrub.toInt()} ?: 0;view.resetZoom()},valueRange=0f..(count-1).toFloat(),modifier=Modifier.fillMaxWidth())
                if(count>0 && (page==starts.lastOrNull())) {
                    val next=store.db.all().filter {it.series==comic.series && (it.issue>comic.issue || it.issue==comic.issue && it.name>comic.name)}.minWithOrNull(compareBy<Comic>{it.issue}.thenBy{it.name})
                    if(next!=null)Button(onClick={onNext(next.id)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text("Next issue: ${next.name}")}
                }
            }
        }
        if(settings)ModalBottomSheet(onDismissRequest={settings=false},containerColor=Color(0xFF172132)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Reader settings",style=MaterialTheme.typography.headlineSmall)
                Text("Layout")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Auto","Single","Double").forEach {m ->FilterChip(layout==m,{layout=m;store.prefs.edit().putString("layout",m).apply();view.resetZoom()},label={Text(m)})}}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("Read right to left",Modifier.weight(1f));Switch(rtl,{rtl=it;store.prefs.edit().putBoolean("rtl",it).apply()})}
                Text("Orientation")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Auto","Portrait","Landscape").forEach {m ->FilterChip(orientation==m,{orientation=m;activity.requestedOrientation=when(m){"Portrait"->ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;"Landscape"->ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;else->ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}},label={Text(m)})}}
                Text("Screen brightness")
                Slider(brightness,{brightness=it;activity.window.attributes=activity.window.attributes.apply {screenBrightness=it}},valueRange=.1f..1f)
                Text("Pinch or double tap to zoom. While zoomed, drag to pan; swipe again from a horizontal edge to turn. Center tap reveals controls.")
                Button(onClick={settings=false},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text("Done")}
            }
        }
    }
}
object PageLayout {
    fun starts(wide: BooleanArray,double: Boolean): List<Int> {
        val result=mutableListOf<Int>();var index=0
        while(index<wide.size) {result.add(index);index+=if(double && index>0 && !wide[index] && index+1<wide.size && !wide[index+1])2 else 1}
        return result
    }
}
