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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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

    fun setNotify(v: Boolean) {
        val ctx = getApplication<Application>()
        ctx.notifyUpdates = v; CheckWorker.schedule(ctx, v)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Screen(vm: VM = viewModel()) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val tick by Bus.tick.collectAsState()
    val msg by Bus.message.collectAsState()
    LaunchedEffect(tick) { vm.refreshLocal() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); Bus.message.value = null } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshLocal() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notify by remember { mutableStateOf(ctx.notifyUpdates) }

    fun run(c: Comp) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
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
                    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Vanadium Updater", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("No root needed", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            if (vm.checking) LoadingIndicator(Modifier.size(48.dp))
                            else FilledTonalButton(onClick = { vm.refresh() }) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Check")
                            }
                        }
                    }
                }
                vm.error?.let { e -> item { Note(e) } }
                Comp.entries.forEach { c ->
                    item { CompCard(c, vm.items.getValue(c), onRun = { run(c) }) }
                }
                item { WebViewCard() }
                item {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().clickable {
                            notify = !notify; vm.setNotify(notify)
                            if (notify && Build.VERSION.SDK_INT >= 33 &&
                                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Notify about updates", style = MaterialTheme.typography.titleMedium)
                                Text("Checks once a day", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(notify, { notify = it; vm.setNotify(it) })
                        }
                    }
                }
                item {
                    Text("Files come straight from GrapheneOS's Vanadium repository on GitLab and are installed over Android's own installer, " +
                        "which checks the signature. Vanadium is not officially supported outside GrapheneOS.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompCard(c: Comp, it: Item, onRun: () -> Unit) {
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleLarge)
            Text(c.info, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (it.installed == null) "Not installed" else "Installed: ${it.installed}", fontWeight = FontWeight.Medium)
            it.remote?.let { r ->
                Text("Latest build: ${r.date}" + if (it.outdated) "  ·  update available" else "",
                    fontSize = 13.sp, color = if (it.outdated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (it.outdated) FontWeight.Bold else FontWeight.Normal)
            }
            if (it.busy != null) {
                Text(it.busy, fontSize = 13.sp)
                if (it.progress != null) LinearWavyProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRun, enabled = it.busy == null && (it.remote != null)) { Text(it.label) }
                if (it.installed != null) {
                    TextButton(onClick = {
                        ctx.packageManager.getLaunchIntentForPackage(c.pkg)?.let { i -> ctx.startActivity(i) }
                    }) { Text("Open") }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${c.pkg}")))
                    }) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun WebViewCard() {
    val ctx = LocalContext.current
    val cur = remember { runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() }
    val has = Vanadium.installedVersion(ctx, "app.vanadium.webview")
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vanadium WebView", style = MaterialTheme.typography.titleLarge)
            Text("Active WebView: ${cur?.packageName ?: "unknown"} ${cur?.versionName ?: ""}".trim(), fontWeight = FontWeight.Medium)
            if (has != null) Text("Vanadium WebView is installed ($has).", fontSize = 13.sp)
            Text("Android only lets root modules register a custom WebView, so it cannot be installed from here without root. " +
                "If you get root later, use a Magisk/KernelSU module for it; the Browser and Config above work either way.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hddq/magisk-vanadium-webview")))
            }) { Text("Root module (GitHub)") }
        }
    }
}
