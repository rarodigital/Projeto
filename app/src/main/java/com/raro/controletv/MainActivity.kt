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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dadb.AdbKeyPair
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Conexão compartilhada com o controle flutuante (Remote).
    private val box = Remote.box
    private val lg = Remote.lg

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ctv", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen(box, lg, prefs)
                }
            }
        }
    }
}

/**
 * Botão que dispara a ação UMA vez ao tocar e, se você SEGURAR, repete sozinho
 * (ótimo pra volume e setas). Visual próprio pra controlar o gesto sem brigar com o ripple.
 */
@Composable
fun HoldKey(
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    onPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                onPress()                       // dispara já no toque
                val job = scope.launch {
                    delay(450)                  // segurou? começa a repetir
                    while (isActive) { onPress(); delay(110) }
                }
                waitForUpOrCancellation()       // soltou
                job.cancel()
            }
        }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun RemoteScreen(box: TvBoxController, lg: LgTvController, prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Chave ADB persistente: a TV lembra a autorização e não pede toda vez.
    val keyPair = remember {
        try {
            val priv = File(ctx.filesDir, "adbkey")
            val pub = File(ctx.filesDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
            AdbKeyPair.read(priv, pub)
        } catch (e: Exception) {
            null
        }
    }

    var device by remember { mutableStateOf(prefs.getString("device", "box") ?: "box") }
    var status by remember { mutableStateOf("Escolha o aparelho e conecte.") }

    // TV Box
    var boxIp by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<String>()) }

    // TV LG
    var lgIp by remember { mutableStateOf(prefs.getString("lg_ip", "") ?: "") }
    var lgPin by remember { mutableStateOf("") }
    var lgMac by remember { mutableStateOf(prefs.getString("lg_mac", "") ?: "") }
    var lgFound by remember { mutableStateOf(listOf<String>()) }

    // Atalhos / apps do TV Box
    var apps by remember { mutableStateOf(listOf<String>()) }
    var loadingApps by remember { mutableStateOf(false) }

    // Teclado
    var typed by remember { mutableStateOf("") }

    // Play on TV / busca
    var castUrl by remember { mutableStateOf("") }
    var ytQuery by remember { mutableStateOf("") }

    // Atalhos fixados (nome bonito -> pacote)
    fun loadFavs(): List<Pair<String, String>> =
        (prefs.getString("favs", "") ?: "")
            .split("\n")
            .filter { it.contains("\t") }
            .map { val p = it.split("\t"); p[0] to p.getOrElse(1) { "" } }
            .filter { it.second.isNotBlank() }
    var favs by remember { mutableStateOf(loadFavs()) }
    fun saveFavs(list: List<Pair<String, String>>) {
        favs = list
        prefs.edit().putString("favs", list.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }
    // Diálogo de fixar app (pedir nome bonito)
    var pinPkg by remember { mutableStateOf<String?>(null) }
    var pinName by remember { mutableStateOf("") }

    fun act(action: RemoteAction) {
        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (device == "box") box.send(action) else lg.send(action)
                }
            } catch (e: Exception) {
                status = "Erro: ${e.message}"
            }
        }
    }

    fun connectBox(target: String) {
        if (target.isBlank()) return
        boxIp = target
        status = "Conectando a $target..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { box.connect(target.trim(), keyPair = keyPair) }
                prefs.edit().putString("ip", target.trim()).apply()
                status = "TV Box conectado (${box.host})"
            } catch (e: Exception) {
                status = "Falha: ${e.message}"
            }
        }
    }

    fun lgScan() {
        status = "Procurando TV LG na rede..."
        scope.launch {
            val list = withContext(Dispatchers.IO) { NetworkScanner.scan(port = 8080) }
            lgFound = list
            status = if (list.isEmpty())
                "Nenhuma TV LG encontrada (ela precisa estar ligada e na rede)."
            else "Encontrei ${list.size}. Toque pra usar como IP da LG."
        }
    }

    fun wakeLg() {
        if (lgMac.isBlank()) { status = "Digite o MAC da TV LG pra ligar."; return }
        status = "Enviando sinal pra ligar a TV..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WakeOnLan.send(lgMac.trim()) }
                prefs.edit().putString("lg_mac", lgMac.trim()).apply()
                status = "Sinal enviado. Se a TV suportar, vai ligar."
            } catch (e: Exception) {
                status = "Falha ao enviar: ${e.message}"
            }
        }
    }

    fun doScan() {
        scanning = true
        found = emptyList()
        status = "Procurando TV Box na rede..."
        scope.launch {
            val list = NetworkScanner.scan()
            found = list
            scanning = false
            status = if (list.isEmpty())
                "Nenhum TV Box com ADB encontrado. Ligue a Depuração ADB no Box."
            else "Encontrei ${list.size}. Toque no IP pra conectar."
        }
    }

    fun boxOp(label: String, op: () -> Unit) {
        status = label
        scope.launch {
            try { withContext(Dispatchers.IO) { op() } }
            catch (e: Exception) { status = "Erro: ${e.message}" }
        }
    }

    // Touchpad: arrasta pra rolar a tela do Box (um swipe a partir do centro).
    fun boxScroll(dx: Float, dy: Float) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (w, h) = box.screenSize()
                    val cx = w / 2
                    val cy = h / 2
                    val nx = (cx + dx).toInt().coerceIn(0, w - 1)
                    val ny = (cy + dy).toInt().coerceIn(0, h - 1)
                    box.swipe(cx, cy, nx, ny, 120)
                }
            } catch (e: Exception) { status = "Erro: ${e.message}" }
        }
    }

    fun loadApps() {
        loadingApps = true
        status = "Listando apps do TV Box..."
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { box.listUserApps() }
                status = "Apps: ${apps.size}. Toque pra abrir ou ⭐ pra fixar."
            } catch (e: Exception) {
                status = "Erro ao listar apps: ${e.message}"
            }
            loadingApps = false
        }
    }

    fun lgShowKey() {
        if (lgIp.isBlank()) { status = "Digite o IP da TV LG."; return }
        status = "Pedindo o PIN na TV..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { lg.requestPairingKey(lgIp.trim()) }
                prefs.edit().putString("lg_ip", lgIp.trim()).apply()
                status = "Veja o PIN na tela da TV e digite abaixo."
            } catch (e: Exception) {
                status = "Falha ao falar com a TV: ${e.message}"
            }
        }
    }

    fun lgPair() {
        status = "Pareando com a TV LG..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { lg.pair(lgIp.trim(), lgPin.trim()) }
                status = "TV LG pareada (${lg.host})"
            } catch (e: Exception) {
                status = "Falha no pareamento: ${e.message}"
            }
        }
    }

    fun sendText() {
        if (typed.isBlank()) return
        val t = typed
        boxOp("Digitando na TV...") { box.text(t) }
    }

    fun castOpen() {
        if (castUrl.isBlank()) return
        val u = castUrl
        boxOp("Abrindo na TV...") { box.openUrl(u) }
    }

    fun ytSearch() {
        if (ytQuery.isBlank()) return
        val q = ytQuery
        boxOp("Buscando \"$q\" na TV...") { box.youtubeSearch(q) }
    }

    fun lgInput() = boxOp("Trocando entrada da TV...") { lg.switchInput() }

    // Espelhamento: o app abre a tela de "Transmitir tela" do PRÓPRIO Android.
    // Não dá pra espelhar de dentro do app — quem espelha é o sistema (Miracast/Smart View),
    // e a TV/Box precisa suportar e estar com o Miracast ligado.
    fun openCastPanel() {
        try {
            ctx.startActivity(
                Intent("android.settings.CAST_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status = "Escolha a TV/Box na lista pra espelhar (precisa ter Miracast ligado na TV)."
        } catch (e: Exception) {
            status = "Seu Android não abriu a tela de espelhar: ${e.message}"
        }
    }

    // Liga o controle flutuante (bolha por cima dos outros apps).
    fun enableFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            status = "Permita 'desenhar sobre outros apps' e toque de novo."
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return
        }
        val i = Intent(ctx, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
        else ctx.startService(i)
        status = "Controle flutuante ligado. Pode sair do app — a bolha fica na tela."
    }

    // Reconecta sozinho no último TV Box ao abrir o app.
    LaunchedEffect(Unit) {
        if (device == "box" && boxIp.isNotBlank()) connectBox(boxIp)
    }
    // Mantém o aparelho ativo em sincronia com o controle flutuante.
    LaunchedEffect(device) { Remote.device = device }

    // Diálogo: dar um nome bonito pro app fixado
    pinPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pinPkg = null },
            title = { Text("Fixar atalho") },
            text = {
                Column {
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinName,
                        onValueChange = { pinName = it },
                        label = { Text("Nome do atalho (ex: UniTV)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pinName.isNotBlank(),
                    onClick = {
                        saveFavs(favs.filterNot { it.second == pkg } + (pinName.trim() to pkg))
                        status = "Atalho '${pinName.trim()}' fixado."
                        pinPkg = null
                    }
                ) { Text("Fixar") }
            },
            dismissButton = { TextButton(onClick = { pinPkg = null }) { Text("Cancelar") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Controle TV", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        // Seletor de aparelho
        Row {
            FilterChip(
                selected = device == "box",
                onClick = { device = "box"; prefs.edit().putString("device", "box").apply() },
                label = { Text("📦 TV Box") }
            )
            Spacer(Modifier.width(10.dp))
            FilterChip(
                selected = device == "lg",
                onClick = { device = "lg"; prefs.edit().putString("device", "lg").apply() },
                label = { Text("📺 TV LG") }
            )
        }
        Spacer(Modifier.height(14.dp))

        if (device == "box") {
            OutlinedTextField(
                value = boxIp,
                onValueChange = { boxIp = it },
                label = { Text("IP do TV Box (ex: 192.168.0.50)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { connectBox(boxIp.trim()) },
                enabled = boxIp.isNotBlank() && !scanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Conectar") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { doScan() },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (scanning) "Procurando..." else "🔍 Procurar TV Box na rede") }
            if (found.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                found.forEach { dev ->
                    OutlinedButton(
                        onClick = { connectBox(dev) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📺  $dev") }
                    Spacer(Modifier.height(4.dp))
                }
            }
        } else {
            OutlinedTextField(
                value = lgIp,
                onValueChange = { lgIp = it },
                label = { Text("IP da TV LG (ex: 192.168.0.20)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { lgShowKey() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("1) Mostrar PIN na TV") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lgPin,
                onValueChange = { lgPin = it },
                label = { Text("PIN que apareceu na TV") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { lgPair() },
                enabled = lgIp.isNotBlank() && lgPin.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("2) Parear") }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { lgScan() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔍 Procurar TV LG na rede") }
            if (lgFound.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                lgFound.forEach { dev ->
                    OutlinedButton(
                        onClick = { lgIp = dev },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📺  $dev") }
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = lgMac,
                onValueChange = { lgMac = it },
                label = { Text("MAC da TV (pra ligar): AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { wakeLg() },
                enabled = lgMac.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("⚡ Ligar TV (Wake-on-LAN)") }
        }

        Spacer(Modifier.height(6.dp))
        Text(status)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { enableFloating() }, modifier = Modifier.fillMaxWidth()) {
            Text("🫧 Controle flutuante (continua na tela ao sair)")
        }
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ===== Atalhos fixados (aparecem nos dois, mas só funcionam no Box) =====
        if (device == "box" && favs.isNotEmpty()) {
            Text("⭐ Atalhos fixados", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            favs.forEach { (name, pkg) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { boxOp("Abrindo $name...") { box.launchApp(pkg) } },
                        modifier = Modifier.weight(1f)
                    ) { Text(name) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { saveFavs(favs.filterNot { it.second == pkg }) }) { Text("✕") }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
        }

        // ===== D-Pad redondo (vale pros dois aparelhos) — segure as setas pra repetir =====
        HoldKey("▲", shape = CircleShape, modifier = Modifier.size(72.dp)) { act(RemoteAction.UP) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoldKey("◀", shape = CircleShape, modifier = Modifier.size(72.dp)) { act(RemoteAction.LEFT) }
            Spacer(Modifier.width(12.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .size(76.dp)
                    .pointerInput(Unit) { detectTapGestures { act(RemoteAction.OK) } }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("OK", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            HoldKey("▶", shape = CircleShape, modifier = Modifier.size(72.dp)) { act(RemoteAction.RIGHT) }
        }
        Spacer(Modifier.height(8.dp))
        HoldKey("▼", shape = CircleShape, modifier = Modifier.size(72.dp)) { act(RemoteAction.DOWN) }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { act(RemoteAction.BACK) }) { Text("Voltar") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.HOME) }) { Text("Home") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.MENU) }) { Text("Menu") }
        }
        Spacer(Modifier.height(10.dp))
        // Volume: segure pra repetir
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoldKey("Vol −", modifier = Modifier.width(96.dp)) { act(RemoteAction.VOL_DOWN) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.MUTE) }) { Text("Mudo") }
            Spacer(Modifier.width(8.dp))
            HoldKey("Vol +", modifier = Modifier.width(96.dp)) { act(RemoteAction.VOL_UP) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { act(RemoteAction.PLAY_PAUSE) }) { Text("⏯  Play/Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.POWER) }) { Text("Power") }
        }

        if (device == "lg") {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { lgInput() }, modifier = Modifier.fillMaxWidth()) {
                Text("🔀 Trocar entrada / fonte (Input)")
            }
        }

        if (device == "box") {
            // ===== Teclado (digitar texto na TV) =====
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("⌨️ Teclado", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Texto pra digitar na TV") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { sendText() }, enabled = typed.isNotBlank()) { Text("Enviar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { boxOp("Apagando...") { box.backspace() } }) { Text("⌫") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { boxOp("Enter...") { box.enter() } }) { Text("Enter") }
            }

            // ===== Touchpad (rolar a tela) =====
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("🖱️ Touchpad — arraste pra rolar, toque = OK", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { act(RemoteAction.OK) }
                    }
                    .pointerInput(Unit) {
                        var accX = 0f
                        var accY = 0f
                        detectDragGestures(
                            onDragStart = { accX = 0f; accY = 0f },
                            onDragEnd = {
                                // escala o gesto pra um swipe perceptível na TV
                                if (kotlin.math.abs(accX) > 30f || kotlin.math.abs(accY) > 30f) {
                                    boxScroll(accX * 1.4f, accY * 1.4f)
                                }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            accX += dragAmount.x
                            accY += dragAmount.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("deslize aqui", style = MaterialTheme.typography.bodyMedium)
            }

            // ===== Play on TV / Cast =====
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("▶️ Play on TV / Cast", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = castUrl,
                onValueChange = { castUrl = it },
                label = { Text("Link pra abrir na TV (vídeo, YouTube, site)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { castOpen() },
                enabled = castUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Abrir na TV") }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ytQuery,
                onValueChange = { ytQuery = it },
                label = { Text("Buscar no YouTube da TV") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { ytSearch() },
                enabled = ytQuery.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔎 Buscar e jogar na TV") }

            // ===== Espelhamento =====
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("📲 Espelhar tela do celular", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "O espelhamento (com áudio) é feito pelo próprio Android. Ligue o Miracast no TV Box e toque abaixo pra escolher ele na lista.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openCastPanel() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📲 Espelhar tela (Smart View / Transmitir)") }

            // ===== Atalhos do TV Box =====
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Atalhos do TV Box", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { boxOp("Abrindo YouTube...") { box.launchYoutube() } }) { Text("YouTube") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { boxOp("Abrindo Configurações...") { box.openSettings() } }) { Text("Config") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { boxOp("Abrindo Aplicativos...") { box.openAppsSettings() } }) { Text("Aplicativos") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { boxOp("Limpando cache...") { box.clearCache() } }) { Text("Limpar cache") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { boxOp("Fechando apps...") { box.closeApps() } }) { Text("Fechar apps") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { loadApps() }, enabled = !loadingApps) {
                    Text(if (loadingApps) "..." else "📋 Meus apps")
                }
            }
            if (apps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Toque pra abrir, ⭐ pra fixar como atalho:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                apps.forEach { pkg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { boxOp("Abrindo $pkg...") { box.launchApp(pkg) } },
                            modifier = Modifier.weight(1f)
                        ) { Text(pkg) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { pinName = ""; pinPkg = pkg }) { Text("⭐") }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
