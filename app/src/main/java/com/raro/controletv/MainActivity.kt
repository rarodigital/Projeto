package com.raro.controletv

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val box = TvBoxController()
    private val lg = LgTvController()

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

@Composable
fun RemoteScreen(box: TvBoxController, lg: LgTvController, prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()

    var device by remember { mutableStateOf(prefs.getString("device", "box") ?: "box") }
    var status by remember { mutableStateOf("Escolha o aparelho e conecte.") }

    // TV Box
    var boxIp by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<String>()) }

    // TV LG
    var lgIp by remember { mutableStateOf(prefs.getString("lg_ip", "") ?: "") }
    var lgPin by remember { mutableStateOf("") }

    // Atalhos / apps do TV Box
    var apps by remember { mutableStateOf(listOf<String>()) }
    var loadingApps by remember { mutableStateOf(false) }

    fun act(action: RemoteAction) {
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
        boxIp = target
        status = "Conectando a $target..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { box.connect(target.trim()) }
                prefs.edit().putString("ip", target.trim()).apply()
                status = "TV Box conectado (${box.host})"
            } catch (e: Exception) {
                status = "Falha: ${e.message}"
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

    fun loadApps() {
        loadingApps = true
        status = "Listando apps do TV Box..."
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { box.listUserApps() }
                status = "Apps: ${apps.size}. Toque pra abrir."
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
        }

        Spacer(Modifier.height(6.dp))
        Text(status)
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ===== Painel de controle (vale pros dois aparelhos) =====
        Button(onClick = { act(RemoteAction.UP) }, modifier = Modifier.width(130.dp)) { Text("▲") }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { act(RemoteAction.LEFT) }, modifier = Modifier.width(70.dp)) { Text("◀") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { act(RemoteAction.OK) }, modifier = Modifier.width(70.dp)) { Text("OK") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { act(RemoteAction.RIGHT) }, modifier = Modifier.width(70.dp)) { Text("▶") }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { act(RemoteAction.DOWN) }, modifier = Modifier.width(130.dp)) { Text("▼") }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { act(RemoteAction.BACK) }) { Text("Voltar") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.HOME) }) { Text("Home") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.MENU) }) { Text("Menu") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { act(RemoteAction.VOL_DOWN) }) { Text("Vol −") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.MUTE) }) { Text("Mudo") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.VOL_UP) }) { Text("Vol +") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { act(RemoteAction.PLAY_PAUSE) }) { Text("⏯  Play/Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { act(RemoteAction.POWER) }) { Text("Power") }
        }

        if (device == "box") {
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
                Text("Toque pra abrir (ex: UniTV está na lista):", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                apps.forEach { pkg ->
                    OutlinedButton(
                        onClick = { boxOp("Abrindo $pkg...") { box.launchApp(pkg) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(pkg) }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
