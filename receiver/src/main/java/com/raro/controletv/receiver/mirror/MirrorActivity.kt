package com.raro.controletv.receiver.mirror

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

private const val MIRROR_PORT = 8555

/** Tela cheia que recebe o espelhamento (PC/celular) e mostra o vídeo ao vivo. */
class MirrorActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var surface: Surface? = null
    private var server: MirrorServer? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(statusText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.setBackgroundColor(0xFF000000.toInt())
        setContentView(root)

        statusText.text = "Aguardando conexão em ${localIp()}:$MIRROR_PORT\n\n" +
            "No PC: rode o script de envio apontando pra esse IP.\nMantenha esta tela aberta."

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                startServer()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
            }
        })
    }

    private fun startServer() {
        if (serverJob != null) return
        val srv = MirrorServer(
            port = MIRROR_PORT,
            onHandshake = { hs ->
                runOnUiThread {
                    applyOrientation(hs.orientation)
                    statusText.visibility = View.GONE
                }
            },
            onLog = { msg -> runOnUiThread { if (statusText.visibility == View.VISIBLE) statusText.text = msg } }
        )
        server = srv
        serverJob = scope.launch { srv.start(surfaceProvider = { surface }) }
    }

    private fun applyOrientation(orientation: String) {
        requestedOrientation = if (orientation.equals("portrait", ignoreCase = true)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun localIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    // só IPv4 da rede local (192.168.x/10.x/172.16-31.x) - pula VPN/tunnel (ex.: Cloudflare WARP)
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        serverJob?.cancel()
    }
}
