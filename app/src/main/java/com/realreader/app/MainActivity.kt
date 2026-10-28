package com.realreader.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.*
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.max

private val Night=Color(0xFF090D16)
private val Panel=Color(0xFF172132)
private val Blue=Color(0xFF7AAAFF)
private val symbols=FontFamily(Font(R.font.material_symbols))
private fun symbolText(name: String): String = mapOf("home" to "\ue9b2","newsstand" to "\ue9c4","settings" to "\ue8b8","arrow_back" to "\ue5c4","add" to "\ue145","folder_open" to "\ue2c8","play_arrow" to "\ue037","chevron_right" to "\ue5cc","auto_stories" to "\ue666","bookmark" to "\ue8e7","tune" to "\ue429","chevron_left" to "\ue5cb","zoom_out_map" to "\ue56b","close" to "\ue5cd","skip_next" to "\ue044")[name] ?: ""
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("library-scan",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ScanWorker>(6,TimeUnit.HOURS).build())
        setContent { RealReaderApp() }
    }
}
@Composable fun Symbol(name: String, label: String?=null) {
    Text(symbolText(name),fontFamily=symbols,fontSize=24.sp, modifier=Modifier.size(24.dp),maxLines=1,softWrap=false)
}
@Composable fun Action(symbol: String,label: String,onClick: ()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {
        Text(symbolText(symbol),fontFamily=symbols,fontSize=24.sp,modifier=Modifier.semanticsLabel(label))
    }
}
private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription=label })
@Composable fun Glass(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=Panel.copy(alpha=.9f),border=BorderStroke(1.dp,Color.White.copy(alpha=.16f)),tonalElevation=3.dp) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha=.07f),Color.Transparent))).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RealReaderApp() {
    val context=LocalContext.current
    val store=remember {LibraryStore(context)}
    val scope=rememberCoroutineScope()
    val nav=rememberNavController()
    var comics by remember {mutableStateOf(store.db.all())}
    var message by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var formatsVersion by remember {mutableIntStateOf(0)}
    val snack=remember {SnackbarHostState()}
    fun refresh() {comics=store.db.all()}
    fun scan() {if(busy)return;busy=true;scope.launch { message=withContext(Dispatchers.IO) {store.scan()};refresh();busy=false}}
    fun retain(uri: Uri) {try {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException) {message="This provider did not grant persistent access; reselect the file if access expires."}}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) {busy=true;scope.launch {
            val errors=withContext(Dispatchers.IO) {uris.mapNotNull {uri ->try {retain(uri);store.import(uri);null}catch(e:Exception){e.message ?: "Import failed"}}}
            refresh();busy=false;message=if(errors.isEmpty())"Added ${uris.size} files" else errors.joinToString("\n")
        }}
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri -> if(uri!=null){retain(uri);store.addFolder(uri);scan()}}
    val entry by nav.currentBackStackEntryAsState()
    val route=entry?.destination?.route ?: "home"
    fun home() {nav.navigate("home") {popUpTo("home"){inclusive=true};launchSingleTop=true};refresh()}
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {val observer=LifecycleEventObserver {_,event ->if(event==Lifecycle.Event.ON_RESUME)refresh()};lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}}
    LaunchedEffect(message) {if(message.isNotEmpty()){snack.showSnackbar(message);message=""}}
    MaterialTheme(colorScheme=darkColorScheme(primary=Blue,secondary=Color(0xFFA997FF),background=Night,surface=Panel,onBackground=Color(0xFFF7F9FF),onSurface=Color(0xFFF7F9FF))) {
        Scaffold(containerColor=Night,snackbarHost={SnackbarHost(snack)},topBar={if(!route.startsWith("reader")) {
            TopAppBar(title={Text(if(route=="home")"Real Reader" else if(route.startsWith("series"))"Series" else route.replaceFirstChar {it.uppercase()},fontSize=23.sp)},navigationIcon={if(route!="home")Action("arrow_back","Back"){if(!nav.popBackStack())home()}},actions={if(route!="home")Action("home","Home",::home);Action("add","Import comics"){import.launch(arrayOf("*/*"))}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Night))
        }},bottomBar={if(!route.startsWith("reader")) {
            NavigationBar(containerColor=Panel.copy(alpha=.96f)) {
                listOf(Triple("home","home","Home"),Triple("library","newsstand","Library"),Triple("settings","settings","Settings")).forEach { (path,icon,label) ->
                    NavigationBarItem(selected=route==path,onClick={if(path=="home")home() else nav.navigate(path){popUpTo("home");launchSingleTop=true}},icon={Symbol(icon)},label={Text(label)})
                }
            }
        }}) { padding ->
            NavHost(nav,startDestination="home",modifier=Modifier.padding(if(route.startsWith("reader"))PaddingValues(0.dp) else padding)) {
                composable("home") {
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        item {Text("Digital comics. Real reading.",fontSize=28.sp);Text("Your stories, on your device.",color=Color(0xFF9EAFC5))}
                        if(comics.isEmpty()) item {
                            Glass {Text("Build your library",style=MaterialTheme.typography.headlineSmall);Text("Choose comic files or a folder. Subfolders are scanned without moving your originals.")
                                Button(onClick={folder.launch(null)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Symbol("folder_open");Spacer(Modifier.width(12.dp));Text("Choose a folder")}
                                OutlinedButton(onClick={import.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text("Import comic files")}
                                Text("CBZ, ZIP and images enabled. Enable PDF in Settings before importing PDFs.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {Text("Currently Reading",style=MaterialTheme.typography.titleLarge)}
                        val current=comics.filter {it.opened>0 && !it.finished}.sortedByDescending {it.opened}.take(3)
                        if(current.isEmpty())item {Text("Open a comic from your library to start reading.",color=Color(0xFF9EAFC5))}
                        items(current,key={"reading-${it.id}"}) {comic ->
                            Glass {
                                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {Cover(comic,Modifier.width(72.dp).height(104.dp));Column(Modifier.weight(1f)) {Text(comic.series,style=MaterialTheme.typography.titleMedium);Text(comic.name,maxLines=2,overflow=TextOverflow.Ellipsis);Text("Page ${comic.page+1} of ${comic.pages}",style=MaterialTheme.typography.bodySmall);LinearProgressIndicator(progress={if(comic.pages>0)(comic.page+1f)/comic.pages else 0f},modifier=Modifier.fillMaxWidth().padding(top=12.dp))}}
                                Button(onClick={nav.navigate("reader/${comic.id}")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Symbol("play_arrow");Spacer(Modifier.width(12.dp));Text("Continue Reading",fontSize=16.sp)}
                            }
                        }
                        item {Text("Recently Added",style=MaterialTheme.typography.titleLarge)}
                        items(comics.sortedByDescending {it.added}.take(8),key={"recent-${it.id}"}) {ComicRow(it,{nav.navigate("series/${it.id}")})}
                    }
                }
                composable("library") {
                    var query by rememberSaveable {mutableStateOf("")};var filter by rememberSaveable {mutableStateOf("All")};var recent by rememberSaveable {mutableStateOf(false)}
                    val filtered=comics.filter { (query.isBlank() || "${it.series} ${it.name}".contains(query,true)) && (filter=="All" || filter=="Unread" && it.opened==0L || filter=="Reading" && it.opened>0 && !it.finished || filter=="Finished" && it.finished)}.let {if(recent)it.sortedByDescending {c->c.added} else it}
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {OutlinedTextField(query,{query=it},label={Text("Search series or filename")},singleLine=true,modifier=Modifier.fillMaxWidth())}
                        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!recent,{recent=false},label={Text("Series")});FilterChip(recent,{recent=true},label={Text("Recently Added")})}}
                        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("All","Unread","Reading","Finished").forEach {label->FilterChip(filter==label,{filter=label},label={Text(label,fontSize=12.sp)})}}}
                        if(filtered.isEmpty())item {Glass {Text("No comics match");Text("Import a supported file or choose a folder in Settings.")}}
                        val groups=filtered.groupBy {ComicNames.seriesKey(it.series)}.values
                            .let {if(recent)it.sortedByDescending {issues->issues.maxOf {it.added}} else it.sortedBy {issues->ComicNames.seriesKey(issues.first().series)}}
                        items(groups,key={ComicNames.seriesKey(it.first().series)}) {issues ->
                            val first=issues.minWithOrNull(compareBy<Comic>{it.issue}.thenBy{it.name})!!
                            SeriesRow(first,comics.count {ComicNames.seriesKey(it.series)==ComicNames.seriesKey(first.series)}) {nav.navigate("series/${first.id}")}
                        }
                    }
                }
                composable("series/{id}") {back ->
                    val comic=comics.find {it.id==back.arguments?.getString("id")?.toLongOrNull()}
                    var edit by remember {mutableStateOf(false)};var name by remember {mutableStateOf(comic?.series ?: "")}
                    if(comic!=null)LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        item {Glass {
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Cover(comic,Modifier.width(100.dp).height(150.dp));Column(Modifier.weight(1f)){Text(comic.series,style=MaterialTheme.typography.headlineSmall);Text(comic.name);Text(if(comic.finished)"Finished" else if(comic.opened>0)"In progress" else "Unread")}}
                            Button(onClick={nav.navigate("reader/${comic.id}")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text(if(comic.opened>0)"Continue Reading" else "Start Reading",fontSize=16.sp)}
                            OutlinedButton(onClick={edit=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Correct series grouping")}
                            Text("Bookmarks: ${JSONArray(comic.marks).length()}",style=MaterialTheme.typography.bodyMedium)
                            for(i in 0 until JSONArray(comic.marks).length()) {val page=JSONArray(comic.marks).getInt(i);TextButton(onClick={store.db.position(comic.id,page,comic.pages);refresh();nav.navigate("reader/${comic.id}")}){Text("Open bookmark · Page ${page+1}")}}
                        }}
                        item {Text("Issues in this series",style=MaterialTheme.typography.titleLarge)}
                        items(comics.filter {ComicNames.seriesKey(it.series)==ComicNames.seriesKey(comic.series)}.sortedBy {it.issue},key={it.id}) {ComicRow(it,{nav.navigate("reader/${it.id}")})}
                        item {OutlinedButton(onClick={store.db.remove(comic.id);refresh();home()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Remove from index (keep original)")}}
                    }
                    if(edit)AlertDialog(onDismissRequest={edit=false},title={Text("Series grouping")},text={OutlinedTextField(name,{name=it},label={Text("Series name")})},confirmButton={TextButton(onClick={if(name.isNotBlank()){store.db.renameSeries(comic!!.id,name);refresh();edit=false}}){Text("Save")}},dismissButton={TextButton(onClick={edit=false}){Text("Cancel")}})
                }
                composable("settings") {
                    val enabled=remember(formatsVersion){store.formats()}
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Glass {Text("Library folders",style=MaterialTheme.typography.titleLarge);Text("${store.folders().size} folders · Subfolders included · Rescan approximately every 6 hours when Android permits.")
                            Button(onClick={folder.launch(null)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text("Add folder and scan")}
                            OutlinedButton(onClick={scan()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(busy)"Scanning…" else "Rescan now")}
                            store.folders().forEach {uri ->Text(uri,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis);TextButton(onClick={store.prefs.edit().putStringSet("folders",store.folders()-uri).apply();formatsVersion++}){Text("Stop scanning this folder")}}
                        }
                        Glass {Text("File types",style=MaterialTheme.typography.titleLarge);Text("Enable PDF only for folders containing comics; all matching PDFs in a chosen folder will be indexed.")
                            listOf("cbz","zip","pdf","jpg","jpeg","png","webp").forEach {ext ->Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {Text(ext.uppercase(),Modifier.weight(1f));Switch(ext in enabled,{checked->store.prefs.edit().putStringSet("formats",if(checked)enabled+ext else enabled-ext).apply();formatsVersion++})}}
                            Text("CBR/RAR is not supported in this alpha. Convert owned archives to CBZ to read them.",style=MaterialTheme.typography.bodySmall)
                        }
                        Glass {Text("About this alpha",style=MaterialTheme.typography.titleLarge);Text("Real Reader 0.1.0 · Independent native Android codebase. Local files and progress stay on this device. No ads, account, subscription, or Internet permission.");Text("Remote servers, metadata enrichment, panel recognition and CBR parsing are outstanding. Glass uses translucent tonal surfaces; live background blur is not implemented.",style=MaterialTheme.typography.bodySmall)}
                    }
                }
                composable("reader/{id}") {back ->
                    val comic=comics.find {it.id==back.arguments?.getString("id")?.toLongOrNull()}
                    if(comic!=null)ReaderScreen(comic,store,onBack={refresh();if(!nav.popBackStack())home()},onHome=::home,onNext={next ->refresh();nav.navigate("reader/$next"){popUpTo("reader/{id}"){inclusive=true}}})
                    else LaunchedEffect(Unit){home()}
                }
            }
            if(busy && !route.startsWith("reader")) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
@Composable private fun SeriesRow(comic: Comic, count: Int, onClick: ()->Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Cover(comic,Modifier.width(54.dp).height(78.dp))
            Column(Modifier.weight(1f)) {
                Text(comic.series,style=MaterialTheme.typography.titleMedium)
                Text("$count ${if(count==1)"issue" else "issues"}",color=Blue,style=MaterialTheme.typography.bodyMedium)
            }
            Symbol("chevron_right")
        }
    }
}
@Composable private fun ComicRow(comic: Comic,onClick: ()->Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Cover(comic,Modifier.width(54.dp).height(78.dp));Column(Modifier.weight(1f)){Text(comic.series,style=MaterialTheme.typography.titleMedium);Text(comic.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall);if(comic.pages>0)Text("${comic.page+1}/${comic.pages} · ${if(comic.finished)"Finished" else "Reading"}",color=Blue,style=MaterialTheme.typography.bodySmall)};Symbol("chevron_right")
        }
    }
}
@Composable private fun Cover(comic: Comic,modifier: Modifier) {
    val context=LocalContext.current
    var cover by remember(comic.uri){mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(comic.uri) {cover=withContext(Dispatchers.IO) {try {
        val cached=File(context.cacheDir,"cover-${comic.uri.hashCode()}-${comic.added}.png")
        if(cached.exists())BitmapFactory.decodeFile(cached.path) else ComicDocument(context,comic).use {doc->doc.page(0,256).also{b->cached.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
    }catch(_:Exception){null}}}
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF294368),Panel)),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
        if(cover!=null) Image(cover!!.asImageBitmap(),"Cover: ${comic.name}",Modifier.fillMaxSize()) else Text(symbolText("auto_stories"),fontFamily=symbols,fontSize=30.sp,color=Blue)
    }
    // Small thumbnails are collected with their composition, never the reader texture cache.
}
