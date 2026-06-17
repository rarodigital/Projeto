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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val controller = TvBoxController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ctv", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RemoteScreen(controller, prefs)
                }
            }
        }
    }
}

@Composable
fun RemoteScreen(controller: TvBoxController, prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var status by remember { mutableStateOf("Desconectado") }
    var connected by remember { mutableStateOf(false) }

    fun send(code: Int) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { controller.keyevent(code) }
            } catch (e: Exception) {
                status = "Erro: ${e.message}"
                connected = false
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
        Text("Controle TV Box", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP do TV Box (ex: 192.168.0.50)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                status = "Conectando..."
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { controller.connect(ip.trim()) }
                        prefs.edit().putString("ip", ip.trim()).apply()
                        connected = true
                        status = "Conectado a ${controller.host}"
                    } catch (e: Exception) {
                        connected = false
                        status = "Falha: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (connected) "Reconectar" else "Conectar") }

        Spacer(Modifier.height(6.dp))
        Text(status)
        Spacer(Modifier.height(20.dp))

        // D-Pad
        Button(onClick = { send(Key.UP) }, modifier = Modifier.width(130.dp)) { Text("▲") }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { send(Key.LEFT) }, modifier = Modifier.width(70.dp)) { Text("◀") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send(Key.OK) }, modifier = Modifier.width(70.dp)) { Text("OK") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send(Key.RIGHT) }, modifier = Modifier.width(70.dp)) { Text("▶") }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { send(Key.DOWN) }, modifier = Modifier.width(130.dp)) { Text("▼") }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { send(Key.BACK) }) { Text("Voltar") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { send(Key.HOME) }) { Text("Home") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { send(Key.MENU) }) { Text("Menu") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { send(Key.VOL_DOWN) }) { Text("Vol −") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { send(Key.MUTE) }) { Text("Mudo") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { send(Key.VOL_UP) }) { Text("Vol +") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { send(Key.PLAY_PAUSE) }) { Text("⏯  Play/Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { send(Key.POWER) }) { Text("Power") }
        }
    }
}
