package com.raro.controletv.receiver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.raro.controletv.receiver.mirror.MirrorActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tela do app que fica NO TV BOX. Liga a acessibilidade e o receptor.
 * Depois é só usar o celular — ele acha o Box sozinho na rede.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Controle TV — Receptor"
            textSize = 24f
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val accBtn = Button(this).apply {
            text = "1) Ativar acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val startBtn = Button(this).apply {
            text = "2) Ligar receptor"
            setOnClickListener { startReceiver(); refresh() }
        }
        val mirrorBtn = Button(this).apply {
            text = "3) Espelhar Tela do PC"
            setOnClickListener { startActivity(Intent(this@MainActivity, MirrorActivity::class.java)) }
        }

        root.addView(title)
        root.addView(status)
        root.addView(accBtn)
        root.addView(startBtn)
        root.addView(mirrorBtn)

        val help = TextView(this).apply {
            text = "Como usar:\n\n" +
                "1) Toque em 'Ativar acessibilidade', ache 'Controle TV (Receptor)' na lista e ligue.\n" +
                "2) Volte e toque em 'Ligar receptor'.\n\n" +
                "Pronto! No celular, abra o app Controle TV e toque em 'Procurar' — ele acha este aparelho sozinho.\n\n" +
                "Deixe este app instalado; o receptor liga sozinho quando você abre o app."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        root.addView(help)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // liga o receptor automaticamente sempre que abrir
        startReceiver()
        refresh()
    }

    private fun startReceiver() {
        val i = Intent(this, ReceiverService::class.java)
        val dlna = Intent(this, NCastDlnaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
            startForegroundService(dlna)
        } else {
            startService(i)
            startService(dlna)
        }
    }

    private fun refresh() {
        val accOn = isAccessibilityOn()
        val ip = localIp() ?: "?"
        status.text = buildString {
            append("Acessibilidade: ").append(if (accOn) "LIGADA ✅" else "DESLIGADA ❌ (toque em 1)")
            append("\nReceptor: ligado na porta ${ReceiverService.PORT}")
            append("\nIP deste aparelho: ").append(ip)
            if (!accOn) append("\n\n⚠️ Sem a acessibilidade as setas/OK não funcionam.")
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${RemoteAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (s in splitter) {
            if (s.equals(me, ignoreCase = true)) return true
        }
        return false
    }

    private fun localIp(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
