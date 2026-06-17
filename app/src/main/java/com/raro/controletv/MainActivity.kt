package com.raro.controletv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dadb.AdbKeyPair
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6C8CFF),
    onPrimary = Color(0xFF06122E),
    secondary = Color(0xFF00D6B2),
    onSecondary = Color(0xFF00201A),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE7EAF0),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE7EAF0),
    surfaceVariant = Color(0xFF222732),
    onSurfaceVariant = Color(0xFFC2C8D4)
)

class MainActivity : ComponentActivity() {
    private val box = Remote.box
    private val lg = Remote.lg

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ctv", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen(box, lg, prefs)
                }
            }
        }
    }
}

/** Botão circular/arredondado: dispara ao tocar e, se segurar, repete. Anima ao apertar. */
@Composable
fun HoldKey(
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    fontSize: Int = 18,
    onPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "press")
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = modifier
            .scale(scale)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onPress()
                    val job = scope.launch { delay(450); while (isActive) { onPress(); delay(110) } }
                    waitForUpOrCancellation()
                    job.cancel()
                    pressed = false
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 14.dp)) {
            Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Touchpad: 1 dedo move o cursor / toque clica / 2 dedos rolam. */
@Composable
fun Trackpad(
    modifier: Modifier = Modifier,
    sens: Float = 2.4f,
    onMove: (Int, Int) -> Unit,
    onScroll: (Int) -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var maxP = 1; var moved = 0f
                var accX = 0f; var accY = 0f; var accS = 0f; var last = 0L
                while (true) {
                    val e = awaitPointerEvent()
                    val pr = e.changes.filter { it.pressed }
                    if (pr.isEmpty()) break
                    if (pr.size > maxP) maxP = pr.size
                    if (pr.size >= 2) {
                        val dy = pr.map { it.position.y - it.previousPosition.y }.average().toFloat()
                        accS += dy; pr.forEach { it.consume() }
                        if (abs(accS) > 48f) { onScroll(if (accS > 0) 1 else -1); accS = 0f }
                    } else {
                        val c = pr[0]
                        val dx = c.position.x - c.previousPosition.x
                        val dy = c.position.y - c.previousPosition.y
                        moved += abs(dx) + abs(dy); accX += dx; accY += dy; c.consume()
                        val now = System.currentTimeMillis()
                        if (now - last > 45) { onMove((accX * sens).toInt(), (accY * sens).toInt()); accX = 0f; accY = 0f; last = now }
                    }
                }
                if (maxP == 1 && moved < 16f) onClick()
                else if (maxP == 1 && (accX != 0f || accY != 0f)) onMove((accX * sens).toInt(), (accY * sens).toInt())
            }
        }
    )
}

/** Barra de rolagem lateral (igual a da LG): arrasta pra cima/baixo. */
@Composable
fun ScrollStrip(modifier: Modifier = Modifier, onScroll: (Int) -> Unit) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            var acc = 0f
            detectVerticalDragGestures(onDragEnd = { acc = 0f }) { change, d ->
                change.consume(); acc += d
                if (abs(acc) > 40f) { onScroll(if (acc > 0) 1 else -1); acc = 0f }
            }
        }
    )
}

@Composable
fun RemoteScreen(box: TvBoxController, lg: LgTvController, prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyPair = remember {
        try {
            val priv = File(ctx.filesDir, "adbkey")
            val pub = File(ctx.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
            AdbKeyPair.read(priv, pub)
        } catch (e: Exception) { null }
    }

    var tab by remember { mutableStateOf(0) }
    var device by remember { mutableStateOf(prefs.getString("device", "box") ?: "box") }
    var status by remember { mutableStateOf("Conecte um aparelho na aba Conexão.") }

    var boxIp by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var boxMode by remember { mutableStateOf(prefs.getString("boxmode", "receiver") ?: "receiver") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<Pair<String, String>>()) }  // ip, modelo

    // Aparelhos salvos
    var devices by remember { mutableStateOf(DeviceStore.load(prefs)) }
    var activeId by remember { mutableStateOf(DeviceStore.activeId(prefs)) }
    var showSaveDevice by remember { mutableStateOf(false) }
    var newDevName by remember { mutableStateOf("") }

    var lgIp by remember { mutableStateOf(prefs.getString("lg_ip", "") ?: "") }
    var lgPin by remember { mutableStateOf("") }
    var lgMac by remember { mutableStateOf(prefs.getString("lg_mac", "") ?: "") }
    var lgFound by remember { mutableStateOf(listOf<String>()) }

    var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var loadingApps by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var castUrl by remember { mutableStateOf("") }
    var ytQuery by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun loadFavs(): List<Pair<String, String>> =
        (prefs.getString("favs", "") ?: "").split("\n").filter { it.contains("\t") }
            .map { val p = it.split("\t"); p[0] to p.getOrElse(1) { "" } }.filter { it.second.isNotBlank() }
    var favs by remember { mutableStateOf(loadFavs()) }
    fun saveFavs(list: List<Pair<String, String>>) {
        favs = list
        prefs.edit().putString("favs", list.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }
    var pinPkg by remember { mutableStateOf<String?>(null) }
    var pinName by remember { mutableStateOf("") }

    fun act(action: RemoteAction) {
        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
        scope.launch {
            try { withContext(Dispatchers.IO) { Remote.send(action) } }
            catch (e: Exception) { status = "Erro: ${e.message}" }
        }
    }
    fun io(block: () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (_: Exception) {} } }

    fun connectBox(target: String) {
        if (target.isBlank()) return
        boxIp = target; status = "Conectando a $target..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { box.connect(target.trim(), keyPair = keyPair) }
                prefs.edit().putString("ip", target.trim()).apply()
                status = "TV Box conectado (${box.host})"
            } catch (e: Exception) { status = "Falha: ${e.message}" }
        }
    }
    fun connectReceiver(target: String) {
        if (target.isBlank()) return
        boxIp = target; status = "Conectando ao app do Box em $target..."
        scope.launch {
            try {
                val acc = withContext(Dispatchers.IO) {
                    Remote.boxReceiver.connect(target.trim()); Remote.boxReceiver.fetchSize(); Remote.boxReceiver.accessibilityReady()
                }
                prefs.edit().putString("ip", target.trim()).apply()
                status = if (acc) "Box conectado pelo app ✅" else "Conectado, mas LIGUE a acessibilidade no app do Box."
            } catch (e: Exception) { status = "Não achei o app do Box: ${e.message}" }
        }
    }
    fun scanReceiver() {
        scanning = true; found = emptyList(); status = "Procurando o Box na rede..."
        scope.launch {
            val ips = withContext(Dispatchers.IO) { NetworkScanner.scan(port = BoxReceiverController.PORT) }
            val withModel = withContext(Dispatchers.IO) { ips.map { it to (BoxReceiverController.fetchInfo(it) ?: "") } }
            found = withModel; scanning = false
            status = if (ips.isEmpty()) "Não achei o app do Box. Instalou e ligou o receptor?" else "Achei ${ips.size}. Toque pra conectar."
        }
    }
    fun doScan() {
        scanning = true; found = emptyList(); status = "Procurando TV Box (ADB) na rede..."
        scope.launch {
            val ips = NetworkScanner.scan(); found = ips.map { it to "" }; scanning = false
            status = if (ips.isEmpty()) "Nenhum TV Box com ADB encontrado." else "Encontrei ${ips.size}. Toque pra conectar."
        }
    }
    fun activateDevice(d: SavedDevice) {
        DeviceStore.setActive(prefs, d.id); activeId = d.id; boxIp = d.ip
        if (d.type == "lg") {
            device = "lg"; lg.useHost(d.ip); lgIp = d.ip; if (d.mac.isNotBlank()) lgMac = d.mac
            status = "Ativo: ${d.name}"
        } else {
            device = "box"; boxMode = d.mode
            if (d.mode == "receiver") {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Remote.boxReceiver.setHost(d.ip); Remote.boxReceiver.fetchSize() }
                        status = "Ativo: ${d.name} ✅"
                    } catch (e: Exception) { status = "Ativo: ${d.name} (sem resposta — ligue o receptor)" }
                }
            } else connectBox(d.ip)
        }
    }
    fun saveCurrentDevice(name: String) {
        if (name.isBlank()) return
        val d = SavedDevice(
            id = DeviceStore.newId(), name = name.trim(),
            type = if (device == "lg") "lg" else "box",
            mode = if (device == "lg") "lg" else boxMode,
            ip = (if (device == "lg") lgIp else boxIp).trim(),
            mac = if (device == "lg") lgMac.trim() else ""
        )
        devices = devices.filterNot { it.ip == d.ip && it.type == d.type } + d
        DeviceStore.save(prefs, devices); DeviceStore.setActive(prefs, d.id); activeId = d.id
        status = "Aparelho '${d.name}' salvo."
    }
    fun deleteDevice(d: SavedDevice) {
        devices = devices.filterNot { it.id == d.id }; DeviceStore.save(prefs, devices)
        if (activeId == d.id) activeId = null
    }
    fun lgScan() {
        status = "Procurando TV LG na rede..."
        scope.launch {
            val list = withContext(Dispatchers.IO) { NetworkScanner.scan(port = 8080) }
            lgFound = list
            status = if (list.isEmpty()) "Nenhuma TV LG encontrada (ligue a TV)." else "Encontrei ${list.size}. Toque pra usar."
        }
    }
    fun wakeLg() {
        if (lgMac.isBlank()) { status = "Digite o MAC da TV LG."; return }
        status = "Enviando sinal pra ligar..."
        scope.launch {
            try { withContext(Dispatchers.IO) { WakeOnLan.send(lgMac.trim()) }; prefs.edit().putString("lg_mac", lgMac.trim()).apply(); status = "Sinal enviado." }
            catch (e: Exception) { status = "Falha: ${e.message}" }
        }
    }
    fun lgShowKey() {
        if (lgIp.isBlank()) { status = "Digite o IP da TV LG."; return }
        status = "Pedindo o PIN na TV..."
        scope.launch {
            try { withContext(Dispatchers.IO) { lg.requestPairingKey(lgIp.trim()) }; prefs.edit().putString("lg_ip", lgIp.trim()).apply(); status = "Veja o PIN na TV e digite abaixo." }
            catch (e: Exception) { status = "Falha ao falar com a TV: ${e.message}" }
        }
    }
    fun lgPair() {
        status = "Pareando..."
        scope.launch {
            try { withContext(Dispatchers.IO) { lg.pair(lgIp.trim(), lgPin.trim()) }; status = "TV LG pareada ✅" }
            catch (e: Exception) { status = "Falha no pareamento: ${e.message}" }
        }
    }
    fun boxAction(label: String, op: () -> String) {
        status = label
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { op() } } catch (e: Exception) { "erro: ${e.message}" }
            status = when {
                r.startsWith("ok") -> "Feito ✅"
                r.startsWith("not-found") -> "App não encontrado nesse aparelho."
                r.startsWith("no-accessibility") -> "Ligue a acessibilidade no app do Box."
                else -> r
            }
        }
    }
    fun loadApps() {
        loadingApps = true; status = "Listando apps..."
        scope.launch {
            try { apps = withContext(Dispatchers.IO) { Remote.listApps() }; status = "Apps: ${apps.size}." }
            catch (e: Exception) { status = "Erro ao listar: ${e.message}" }
            loadingApps = false
        }
    }
    fun enableFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            status = "Permita 'desenhar sobre outros apps' e toque de novo."
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return
        }
        val i = Intent(ctx, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        status = "Controle flutuante ligado."
    }
    fun openCastPanel() {
        // tenta o Smart View da Samsung primeiro (é o que acha o Box pra ele), depois o painel padrão
        val attempts = listOf(
            Intent().setComponent(android.content.ComponentName("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.SmartMirroringActivity")),
            Intent("com.samsung.android.smartmirroring.action.UNIFIED_CONNECT"),
            Intent("android.settings.CAST_SETTINGS"),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS")
        )
        for (i in attempts) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                status = "Abrindo espelhamento — escolha a TV/Box na lista."
                return
            } catch (_: Exception) {}
        }
        status = "Não consegui abrir; use o Smart View pela barra de notificações."
    }

    LaunchedEffect(Unit) {
        if (device == "box" && boxIp.isNotBlank()) { if (boxMode == "receiver") connectReceiver(boxIp) else connectBox(boxIp) }
    }
    LaunchedEffect(device) { Remote.device = device }
    LaunchedEffect(boxMode) { Remote.boxMode = boxMode; prefs.edit().putString("boxmode", boxMode).apply() }

    // Diálogo de fixar atalho
    pinPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pinPkg = null },
            title = { Text("Fixar atalho") },
            text = {
                Column {
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pinName, onValueChange = { pinName = it }, label = { Text("Nome (ex: UniTV)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = pinName.isNotBlank(), onClick = {
                    saveFavs(favs.filterNot { it.second == pkg } + (pinName.trim() to pkg)); status = "Atalho fixado."; pinPkg = null
                }) { Text("Fixar") }
            },
            dismissButton = { TextButton(onClick = { pinPkg = null }) { Text("Cancelar") } }
        )
    }

    // Diálogo de digitar (acessível do Controle e do Mouse)
    if (showText) {
        AlertDialog(
            onDismissRequest = { showText = false },
            title = { Text("Digitar na TV") },
            text = {
                Column {
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(onClick = { clipboard.getText()?.let { typed += it.text } }) { Text("Colar") }
                        TextButton(onClick = { typed = "" }) { Text("Limpar") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = typed.isNotBlank(), onClick = { boxAction("Digitando...") { Remote.boxText(typed) }; showText = false }) { Text("Enviar") }
            },
            dismissButton = { TextButton(onClick = { showText = false }) { Text("Fechar") } }
        )
    }

    if (showSaveDevice) {
        AlertDialog(
            onDismissRequest = { showSaveDevice = false },
            title = { Text("Salvar aparelho") },
            text = {
                Column {
                    Text("Dê um nome pra este aparelho (ex: TV Box sala, Projetor, TV LG quarto)", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newDevName, onValueChange = { newDevName = it }, label = { Text("Nome") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = newDevName.isNotBlank(), onClick = { saveCurrentDevice(newDevName); newDevName = ""; showSaveDevice = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { showSaveDevice = false }) { Text("Cancelar") } }
        )
    }

    val tabs = listOf("🎮" to "Controle", "🖱️" to "Mouse", "📺" to "Apps", "🎬" to "Cast", "🔌" to "Conexão")

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar com seletor de aparelho sempre visível
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Controle TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (devices.isNotEmpty()) {
                // troca rápida entre aparelhos salvos
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    devices.forEach { d ->
                        FilterChip(
                            selected = activeId == d.id,
                            onClick = { activateDevice(d) },
                            label = { Text("${if (d.type == "lg") "📺" else "📦"} ${d.name}") }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            } else {
                Row {
                    FilterChip(selected = device == "box", onClick = { device = "box"; prefs.edit().putString("device", "box").apply() }, label = { Text("📦 Box") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = device == "lg", onClick = { device = "lg"; prefs.edit().putString("device", "lg").apply() }, label = { Text("📺 LG") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Divider(color = MaterialTheme.colorScheme.surfaceVariant)

        Crossfade(targetState = tab, label = "tab", modifier = Modifier.weight(1f)) { t ->
            when (t) {
                0 -> ControlTab(device, { act(it) }, { io { lg.switchInput() } }, onKeyboard = { showText = true })
                1 -> MouseTab({ act(it) }, onMove = { x, y -> io { Remote.mouseMove(x, y) } }, onScroll = { d -> io { Remote.mouseScroll(d) } }, onClick = { io { Remote.mouseShow(true); Remote.mouseClick() } }, onHide = { io { Remote.mouseShow(false) } }, onKeyboard = { showText = true })
                2 -> AppsTab(
                    favs = favs, apps = apps, loadingApps = loadingApps,
                    onYoutube = { boxAction("Abrindo YouTube...") { Remote.openUrl("https://www.youtube.com/") } },
                    onLoadApps = { loadApps() },
                    onLaunch = { name, pkg -> boxAction("Abrindo $name...") { Remote.launchApp(pkg) } },
                    onPin = { name, pkg -> pinName = name; pinPkg = pkg },
                    onUnpin = { pkg -> saveFavs(favs.filterNot { it.second == pkg }) },
                    onCloseAll = { boxAction("Fechando apps abertos...") { Remote.closeAll() } }
                )
                3 -> CastTab(
                    castUrl = castUrl, onCastUrl = { castUrl = it }, onCast = { boxAction("Abrindo na TV...") { Remote.openUrl(castUrl) } },
                    ytQuery = ytQuery, onYt = { ytQuery = it }, onYtSearch = { boxAction("Buscando na TV...") { Remote.youtubeSearch(ytQuery) } },
                    typed = typed, onTyped = { typed = it }, onSendText = { boxAction("Digitando...") { Remote.boxText(typed) } },
                    onMirror = { openCastPanel() },
                    onMirrorApp = { boxAction("Abrindo AirScreen na TV...") { Remote.launchApp("com.ionitech.airscreen") } }
                )
                else -> ConexaoTab(
                    devices = devices, activeId = activeId, onActivate = { activateDevice(it) }, onDelete = { deleteDevice(it) },
                    onSaveCurrent = { newDevName = ""; showSaveDevice = true },
                    device = device, onDevice = { device = it; prefs.edit().putString("device", it).apply() },
                    boxMode = boxMode, onBoxMode = { boxMode = it; found = emptyList() },
                    boxIp = boxIp, onBoxIp = { boxIp = it }, scanning = scanning,
                    onConnectBox = { if (boxMode == "receiver") connectReceiver(boxIp.trim()) else connectBox(boxIp.trim()) },
                    onScanBox = { if (boxMode == "receiver") scanReceiver() else doScan() },
                    found = found, onPick = { if (boxMode == "receiver") connectReceiver(it) else connectBox(it) },
                    lgIp = lgIp, onLgIp = { lgIp = it }, lgPin = lgPin, onLgPin = { lgPin = it }, lgMac = lgMac, onLgMac = { lgMac = it },
                    lgFound = lgFound, onLgShowKey = { lgShowKey() }, onLgPair = { lgPair() }, onLgScan = { lgScan() }, onLgPick = { lgIp = it }, onWake = { wakeLg() },
                    onFloating = { enableFloating() }
                )
            }
        }

        NavigationBar {
            tabs.forEachIndexed { i, pair ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Text(pair.first, fontSize = 20.sp) },
                    label = { Text(pair.second, fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
private fun ControlTab(device: String, act: (RemoteAction) -> Unit, onLgInput: () -> Unit, onKeyboard: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // topo: Power / Home / Mudo
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HoldKey("⏻", shape = CircleShape, container = Color(0xFF8A2B2B), content = Color.White, modifier = Modifier.size(60.dp)) { act(RemoteAction.POWER) }
            HoldKey("⌂", shape = CircleShape, modifier = Modifier.size(60.dp)) { act(RemoteAction.HOME) }
            HoldKey("🔇", shape = CircleShape, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(60.dp)) { act(RemoteAction.MUTE) }
        }
        Spacer(Modifier.height(22.dp))
        // D-pad redondo
        HoldKey("▲", shape = CircleShape, modifier = Modifier.size(76.dp)) { act(RemoteAction.UP) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoldKey("◀", shape = CircleShape, modifier = Modifier.size(76.dp)) { act(RemoteAction.LEFT) }
            Spacer(Modifier.width(14.dp))
            HoldKey("OK", shape = CircleShape, container = MaterialTheme.colorScheme.secondary, content = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(84.dp)) { act(RemoteAction.OK) }
            Spacer(Modifier.width(14.dp))
            HoldKey("▶", shape = CircleShape, modifier = Modifier.size(76.dp)) { act(RemoteAction.RIGHT) }
        }
        Spacer(Modifier.height(10.dp))
        HoldKey("▼", shape = CircleShape, modifier = Modifier.size(76.dp)) { act(RemoteAction.DOWN) }

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { act(RemoteAction.BACK) }) { Text("Voltar") }
            OutlinedButton(onClick = { act(RemoteAction.HOME) }) { Text("Home") }
            OutlinedButton(onClick = { act(RemoteAction.MENU) }) { Text("Menu") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldKey("Vol −", modifier = Modifier.width(100.dp)) { act(RemoteAction.VOL_DOWN) }
            OutlinedButton(onClick = { act(RemoteAction.MUTE) }) { Text("Mudo") }
            HoldKey("Vol +", modifier = Modifier.width(100.dp)) { act(RemoteAction.VOL_UP) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { act(RemoteAction.PLAY_PAUSE) }) { Text("⏯  Play/Pause") }
            OutlinedButton(onClick = onKeyboard) { Text("⌨️ Teclado") }
            if (device == "lg") OutlinedButton(onClick = onLgInput) { Text("🔀 Entrada") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldKey("CH −", modifier = Modifier.width(100.dp)) { act(RemoteAction.CHANNEL_DOWN) }
            Text("Canal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HoldKey("CH +", modifier = Modifier.width(100.dp)) { act(RemoteAction.CHANNEL_UP) }
        }
    }
}

@Composable
private fun MouseTab(act: (RemoteAction) -> Unit, onMove: (Int, Int) -> Unit, onScroll: (Int) -> Unit, onClick: () -> Unit, onHide: () -> Unit, onKeyboard: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Touchpad — mova o cursor; toque = clique; 2 dedos ou a barra → rolar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Trackpad(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)),
                onMove = onMove, onScroll = onScroll, onClick = onClick
            )
            Spacer(Modifier.width(10.dp))
            ScrollStrip(
                modifier = Modifier.width(46.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)),
                onScroll = onScroll
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldKey("Vol −", modifier = Modifier.weight(1f)) { act(RemoteAction.VOL_DOWN) }
            Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text("Clique") }
            HoldKey("Vol +", modifier = Modifier.weight(1f)) { act(RemoteAction.VOL_UP) }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act(RemoteAction.BACK) }, modifier = Modifier.weight(1f)) { Text("Voltar") }
            OutlinedButton(onClick = { act(RemoteAction.HOME) }, modifier = Modifier.weight(1f)) { Text("Home") }
            OutlinedButton(onClick = onKeyboard, modifier = Modifier.weight(1f)) { Text("⌨️") }
            OutlinedButton(onClick = onHide, modifier = Modifier.weight(1f)) { Text("Esconder") }
        }
    }
}

@Composable
private fun AppsTab(
    favs: List<Pair<String, String>>, apps: List<Pair<String, String>>, loadingApps: Boolean,
    onYoutube: () -> Unit, onLoadApps: () -> Unit, onLaunch: (String, String) -> Unit,
    onPin: (String, String) -> Unit, onUnpin: (String) -> Unit, onCloseAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onYoutube) { Text("▶️ YouTube") }
            OutlinedButton(onClick = onLoadApps, enabled = !loadingApps) { Text(if (loadingApps) "..." else "📋 Meus apps") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCloseAll, modifier = Modifier.fillMaxWidth()) { Text("🧹 Fechar apps abertos (limpar)") }
        if (favs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp)); Text("⭐ Atalhos fixados", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
            favs.forEach { (name, pkg) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onLaunch(name, pkg) }, modifier = Modifier.weight(1f)) { Text(name) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { onUnpin(pkg) }) { Text("✕") }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp)); Text("Toque pra abrir, ⭐ pra fixar", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp))
            apps.forEach { (name, pkg) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onLaunch(name, pkg) }, modifier = Modifier.weight(1f)) { Text(name) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { onPin(name, pkg) }) { Text("⭐") }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CastTab(
    castUrl: String, onCastUrl: (String) -> Unit, onCast: () -> Unit,
    ytQuery: String, onYt: (String) -> Unit, onYtSearch: () -> Unit,
    typed: String, onTyped: (String) -> Unit, onSendText: () -> Unit,
    onMirror: () -> Unit, onMirrorApp: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("▶️ Play on TV", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = castUrl, onValueChange = onCastUrl, label = { Text("Link pra abrir no navegador da TV") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Button(onClick = onCast, enabled = castUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Abrir na TV") }

        Spacer(Modifier.height(18.dp)); Text("🔎 Buscar no YouTube da TV", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = ytQuery, onValueChange = onYt, label = { Text("O que buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onYtSearch, enabled = ytQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Buscar e jogar na TV") }

        Spacer(Modifier.height(18.dp)); Text("⌨️ Teclado", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = typed, onValueChange = onTyped, label = { Text("Texto pra digitar na TV") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Button(onClick = onSendText, enabled = typed.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Enviar texto") }

        Spacer(Modifier.height(18.dp)); Text("📲 Espelhar tela", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp))
        Text("1) Liga o receptor de tela no Box (AirScreen) → 2) abre o Smart View e escolhe o Box. (Espelhamento instantâneo num toque: em breve.)", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onMirrorApp, modifier = Modifier.fillMaxWidth()) { Text("1) Ligar AirScreen na TV") }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onMirror, modifier = Modifier.fillMaxWidth()) { Text("2) Espelhar (Smart View)") }
    }
}

@Composable
private fun ConexaoTab(
    devices: List<SavedDevice>, activeId: String?, onActivate: (SavedDevice) -> Unit, onDelete: (SavedDevice) -> Unit, onSaveCurrent: () -> Unit,
    device: String, onDevice: (String) -> Unit,
    boxMode: String, onBoxMode: (String) -> Unit,
    boxIp: String, onBoxIp: (String) -> Unit, scanning: Boolean,
    onConnectBox: () -> Unit, onScanBox: () -> Unit, found: List<Pair<String, String>>, onPick: (String) -> Unit,
    lgIp: String, onLgIp: (String) -> Unit, lgPin: String, onLgPin: (String) -> Unit, lgMac: String, onLgMac: (String) -> Unit,
    lgFound: List<String>, onLgShowKey: () -> Unit, onLgPair: () -> Unit, onLgScan: () -> Unit, onLgPick: (String) -> Unit, onWake: () -> Unit,
    onFloating: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Aparelhos salvos
        if (devices.isNotEmpty()) {
            Text("Meus aparelhos", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onActivate(d) },
                        modifier = Modifier.weight(1f),
                        colors = if (activeId == d.id) androidx.compose.material3.ButtonDefaults.buttonColors()
                        else androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) { Text("${if (d.type == "lg") "📺" else "📦"} ${d.name}${if (activeId == d.id) "  • ativo" else ""}") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { onDelete(d) }) { Text("✕") }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp)); Divider(); Spacer(Modifier.height(12.dp))
        }
        Text("Adicionar / conectar", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = device == "box", onClick = { onDevice("box") }, label = { Text("📦 TV Box") })
            FilterChip(selected = device == "lg", onClick = { onDevice("lg") }, label = { Text("📺 TV LG") })
        }
        Spacer(Modifier.height(14.dp))
        if (device == "box") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = boxMode == "receiver", onClick = { onBoxMode("receiver") }, label = { Text("📲 App do Box") })
                FilterChip(selected = boxMode == "adb", onClick = { onBoxMode("adb") }, label = { Text("🔌 ADB") })
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = boxIp, onValueChange = onBoxIp, label = { Text("IP do TV Box") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnectBox, enabled = boxIp.isNotBlank() && !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (boxMode == "receiver") "Conectar (app do Box)" else "Conectar (ADB)") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onScanBox, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Procurando..." else "🔍 Procurar Box na rede") }
            found.forEach { (ip, model) ->
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onPick(ip) }, modifier = Modifier.fillMaxWidth()) {
                    Text("📺  $ip" + if (model.isNotBlank()) "  —  $model" else "")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(if (boxMode == "receiver") "Instale o app 'Controle TV (Receptor)' no Box e ligue a acessibilidade." else "ADB precisa do 'ADB pela rede' ligado (porta 5555).", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(value = lgIp, onValueChange = onLgIp, label = { Text("IP da TV LG") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLgShowKey, modifier = Modifier.fillMaxWidth()) { Text("1) Mostrar PIN na TV") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = lgPin, onValueChange = onLgPin, label = { Text("PIN da TV") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = onLgPair, enabled = lgIp.isNotBlank() && lgPin.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("2) Parear") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLgScan, modifier = Modifier.fillMaxWidth()) { Text("🔍 Procurar TV LG") }
            lgFound.forEach { dev -> Spacer(Modifier.height(6.dp)); OutlinedButton(onClick = { onLgPick(dev) }, modifier = Modifier.fillMaxWidth()) { Text("📺  $dev") } }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = lgMac, onValueChange = onLgMac, label = { Text("MAC da TV (pra ligar)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onWake, enabled = lgMac.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("⚡ Ligar TV (Wake-on-LAN)") }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth()) { Text("💾 Salvar este aparelho (dar um nome)") }

        Spacer(Modifier.height(18.dp)); Divider(); Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onFloating, modifier = Modifier.fillMaxWidth()) { Text("🫧 Controle flutuante (continua na tela ao sair)") }
    }
}
