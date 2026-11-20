package dev.dorfandiy.vanadiumupdater

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dorfandiy.vanadiumupdater.Vanadium.checkHours
import dev.dorfandiy.vanadiumupdater.Vanadium.expressive
import dev.dorfandiy.vanadiumupdater.Vanadium.notifyUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Item(val installed: String?, val remote: Remote?, val stored: String?, val progress: Float? = null, val busy: String? = null) {
    val outdated get() = installed != null && remote != null && stored != remote.commit
    val label get() = when {
        installed == null -> "Install"
        outdated -> "Update"
        else -> "Reinstall"
    }
}

class VM(app: Application) : AndroidViewModel(app) {
    var items by mutableStateOf(Comp.entries.associateWith { Item(null, null, null) })
        private set
    var checking by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var expressive by mutableStateOf(app.expressive); private set
    var notify by mutableStateOf(app.notifyUpdates); private set
    var hours by mutableStateOf(app.checkHours); private set

    init { refresh() }

    private fun put(c: Comp, f: (Item) -> Item) { items = items + (c to f(items.getValue(c))) }

    fun refreshLocal() {
        val ctx = getApplication<Application>()
        Comp.entries.forEach { c -> put(c) { Item(Vanadium.installedVersion(ctx, c.pkg), it.remote, Vanadium.storedCommit(ctx, c), it.progress, it.busy) } }
    }

    fun refresh() {
        if (checking) return
        checking = true; error = null
        refreshLocal()
        viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            Comp.entries.forEach { c ->
                val r = Vanadium.latest(c)
                if (r == null) failed = true
                put(c) { Item(it.installed, r ?: it.remote, it.stored, it.progress, it.busy) }
            }
            if (failed) error = "Could not reach GitLab. Check the connection and try again."
            checking = false
        }
    }

    fun act(c: Comp) {
        val ctx = getApplication<Application>()
        val r = items.getValue(c).remote ?: return refresh()
        if (items.getValue(c).busy != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                put(c) { Item(it.installed, it.remote, it.stored, 0f, "Downloading…") }
                val f = Vanadium.download(ctx, c, r) { p -> put(c) { Item(it.installed, it.remote, it.stored, p, "Downloading…") } }
                put(c) { Item(it.installed, it.remote, it.stored, null, "Installing…") }
                Vanadium.install(ctx, c, r, f)
            } catch (e: Exception) {
                Bus.message.value = e.message ?: "Failed"
            } finally {
                put(c) { Item(it.installed, it.remote, it.stored, null, null) }
            }
        }
    }

    fun uninstall(c: Comp) {
        val ctx = getApplication<Application>()
        if (ShizukuHelper.usable) {
            viewModelScope.launch(Dispatchers.IO) {
                val out = ShizukuHelper.exec("pm uninstall ${c.pkg}")
                Bus.message.value = if (out != null && out.startsWith("Success")) "${c.title} removed" else out ?: "Failed"
                Bus.tick.value++
            }
        } else {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${c.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }.onFailure { Bus.message.value = it.message }
        }
    }

    /** Asks Android to use Vanadium WebView. This only works if the system lists it as an allowed provider. */
    fun activateWebView() {
        viewModelScope.launch(Dispatchers.IO) {
            val out = ShizukuHelper.exec("cmd webviewupdate set-webview-implementation ${Comp.WEBVIEW.pkg}")
            Bus.message.value = when {
                out == null -> "Shizuku is not connected"
                out.startsWith("ERR") || out.contains("Failed", true) -> "Android refused: ${out.take(160)}"
                else -> out.ifBlank { "Done. Check Developer options → WebView implementation." }
            }
        }
    }

    fun changeExpressive(v: Boolean) { getApplication<Application>().expressive = v; expressive = v }
    fun changeNotify(v: Boolean) { val c = getApplication<Application>(); c.notifyUpdates = v; notify = v; CheckWorker.schedule(c, v, hours) }
    fun changeHours(h: Int) { val c = getApplication<Application>(); c.checkHours = h; hours = h; if (notify) CheckWorker.schedule(c, true, h) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuHelper.init()
        setContent {
            val vm: VM = viewModel()
            AppTheme(vm.expressive) { Root(vm) }
        }
    }
}

@Composable
private fun cardShape(): RoundedCornerShape = RoundedCornerShape(if (LocalExpressive.current) 28.dp else 16.dp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bar(p: Float?, modifier: Modifier = Modifier) {
    val ex = LocalExpressive.current
    if (ex) {
        if (p != null) LinearWavyProgressIndicator(progress = { p }, modifier = modifier) else LinearWavyProgressIndicator(modifier)
    } else {
        if (p != null) LinearProgressIndicator(progress = { p }, modifier = modifier) else LinearProgressIndicator(modifier)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Spinner() {
    if (LocalExpressive.current) LoadingIndicator(Modifier.size(48.dp)) else CircularProgressIndicator(Modifier.size(36.dp))
}

@Composable
private fun Root(vm: VM) {
    var settings by remember { mutableStateOf(false) }
    BackHandler(settings) { settings = false }
    if (settings) SettingsScreen(vm) { settings = false } else Screen(vm) { settings = true }
}

@Composable
private fun Screen(vm: VM, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val tick by Bus.tick.collectAsState()
    val msg by Bus.message.collectAsState()
    val shizuku by ShizukuHelper.status.collectAsState()
    LaunchedEffect(tick) { vm.refreshLocal() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); Bus.message.value = null } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { vm.refreshLocal(); ShizukuHelper.refresh() } }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    fun run(c: Comp) {
        if (!ShizukuHelper.usable && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            Bus.message.value = "Allow installs from this app, then tap again"
        } else vm.act(c)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 700.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Surface(shape = cardShape(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Vanadium Updater", fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(if (shizuku == ShizukuStatus.READY) "No root · Shizuku active" else "No root", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            if (vm.checking) Spinner()
                            else FilledTonalButton(onClick = { vm.refresh() }) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Check")
                            }
                            IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    }
                }
                vm.error?.let { e -> item { Note(e) } }
                Comp.entries.forEach { c ->
                    item { CompCard(c, vm.items.getValue(c), shizuku == ShizukuStatus.READY, onRun = { run(c) }, onRemove = { vm.uninstall(c) }, onActivate = { vm.activateWebView() }) }
                }
                item { WebViewInfo() }
                item {
                    Text("Files come straight from GrapheneOS's Vanadium repository on GitLab. Without Shizuku they are installed with Android's installer, " +
                        "which checks the signature. Vanadium is not officially supported outside GrapheneOS.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Surface(shape = cardShape(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun CompCard(c: Comp, it: Item, shizuku: Boolean, onRun: () -> Unit, onRemove: () -> Unit, onActivate: () -> Unit) {
    val ctx = LocalContext.current
    val launch = remember(it.installed) { ctx.packageManager.getLaunchIntentForPackage(c.pkg) }
    Surface(shape = cardShape(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleLarge)
            Text(c.info, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (it.installed == null) "Not installed" else "Installed: ${it.installed}", fontWeight = FontWeight.Medium)
            it.remote?.let { r ->
                Text("Latest build: ${r.date}" + if (it.outdated) "  ·  update available" else "",
                    fontSize = 13.sp, color = if (it.outdated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (it.outdated) FontWeight.Bold else FontWeight.Normal)
            }
            if (it.busy != null) { Text(it.busy, fontSize = 13.sp); Bar(it.progress, Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRun, enabled = it.busy == null && it.remote != null) { Text(it.label) }
                if (launch != null) TextButton(onClick = { runCatching { ctx.startActivity(launch) } }) { Text("Open") }
                if (it.installed != null) TextButton(onClick = onRemove) { Text("Uninstall") }
            }
            if (c == Comp.WEBVIEW && it.installed != null) {
                TextButton(onClick = onActivate, enabled = shizuku) { Text("Try to set as system WebView") }
                if (!shizuku) Text("Needs Shizuku. Otherwise choose it in Developer options → WebView implementation if Android lists it.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WebViewInfo() {
    val cur = remember { runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() }
    Surface(shape = cardShape(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active system WebView", style = MaterialTheme.typography.titleMedium)
            Text("${cur?.packageName ?: "unknown"} ${cur?.versionName ?: ""}".trim(), fontWeight = FontWeight.Medium)
            Text("Stock Android only lists WebView providers named in the system image, so Vanadium WebView may not be selectable without a root module. " +
                "Installing it is harmless either way.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsScreen(vm: VM, back: () -> Unit) {
    val ctx = LocalContext.current
    val shizuku by ShizukuHelper.status.collectAsState()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toggleNotify(v: Boolean) {
        vm.changeNotify(v)
        if (v && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 700.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    Panel {
                        SwitchRow("Check for updates automatically", "Sends a notification when a new build appears", vm.notify) { toggleNotify(it) }
                        if (vm.notify) {
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(6 to "6 h", 12 to "12 h", 24 to "Daily", 168 to "Weekly").forEach { (h, n) ->
                                    FilterChip(vm.hours == h, { vm.changeHours(h) }, label = { Text(n) })
                                }
                            }
                            Text("Android may delay background checks to save battery.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                item {
                    Panel { SwitchRow("Material 3 Expressive", "Wavy progress, bigger shapes and springy motion. Off gives the standard Material 3 look.", vm.expressive) { vm.changeExpressive(it) } }
                }
                item {
                    Panel {
                        Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                        Text(when (shizuku) {
                            ShizukuStatus.READY -> "Connected. Installs and removals happen without dialogs."
                            ShizukuStatus.NO_PERMISSION -> "Running, but this app has no permission yet."
                            ShizukuStatus.NOT_RUNNING -> "Not running. Start Shizuku to install silently."
                        }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (shizuku == ShizukuStatus.NO_PERMISSION) Button(onClick = { ShizukuHelper.requestPermission() }, modifier = Modifier.padding(top = 8.dp)) { Text("Grant permission") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = cardShape(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange)
    }
}
