package com.raro.controletv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Servidor local (HTTP simples na porta 8787) que recebe os comandos do celular
 * e executa no TV Box via [RemoteAccessibilityService] / AudioManager / PackageManager.
 */
class ReceiverService : Service() {

    companion object {
        const val PORT = 8787
        private const val CHANNEL = "receiver"
        private const val NOTIF_ID = 7
        const val VERSION = "1.0"
        private const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        startServer()
    }

    private fun startServer() {
        scope.launch {
            try {
                val s = ServerSocket(PORT)
                server = s
                while (!s.isClosed) {
                    val client = try { s.accept() } catch (e: Exception) { break }
                    scope.launch { handle(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val line = reader.readLine() ?: return
                // "GET /path?query HTTP/1.1"
                val parts = line.split(" ")
                val target = if (parts.size >= 2) parts[1] else "/"
                val body = route(target)
                val out = c.getOutputStream()
                val resp = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                    body
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun route(target: String): String {
        val path = target.substringBefore("?")
        val query = target.substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val i = it.indexOf("="); if (i < 0) null else
                it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

        val acc = RemoteAccessibilityService.instance

        return when {
            path == "/ping" -> "controletv-receiver $VERSION acc=${if (acc != null) 1 else 0}"

            path.startsWith("/key/") -> {
                val name = path.removePrefix("/key/").uppercase()
                if (acc == null) return "no-accessibility"
                val ok = when (name) {
                    "UP" -> acc.up(); "DOWN" -> acc.down(); "LEFT" -> acc.left(); "RIGHT" -> acc.right()
                    "OK" -> acc.ok(); "BACK" -> acc.back(); "HOME" -> acc.home()
                    "MENU", "RECENTS" -> acc.recents()
                    else -> false
                }
                if (ok) "ok" else "fail"
            }

            path.startsWith("/vol/") -> {
                val dir = path.removePrefix("/vol/")
                volume(dir); "ok"
            }

            path.startsWith("/media/") -> {
                val k = path.removePrefix("/media/")
                media(k); "ok"
            }

            path == "/power" -> { acc?.powerDialog(); "ok" }

            path == "/size" -> sizeStr()

            path == "/tap" -> {
                val x = params["x"]?.toIntOrNull(); val y = params["y"]?.toIntOrNull()
                if (acc == null) "no-accessibility"
                else if (x != null && y != null) { acc.tap(x, y); "ok" } else "bad"
            }

            path == "/swipe" -> {
                val x1 = params["x1"]?.toIntOrNull(); val y1 = params["y1"]?.toIntOrNull()
                val x2 = params["x2"]?.toIntOrNull(); val y2 = params["y2"]?.toIntOrNull()
                val dur = params["dur"]?.toIntOrNull() ?: 120
                if (acc == null) "no-accessibility"
                else if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    acc.swipe(x1, y1, x2, y2, dur); "ok"
                } else "bad"
            }

            // ---- Mouse (trackpad com cursor na tela) ----
            path == "/mouse" -> {
                if (acc == null) return "no-accessibility"
                if (params["on"] == "0") acc.hideCursor() else acc.showCursor(); "ok"
            }
            path == "/move" -> {
                val dx = params["dx"]?.toFloatOrNull() ?: 0f
                val dy = params["dy"]?.toFloatOrNull() ?: 0f
                if (acc == null) "no-accessibility" else { acc.moveCursor(dx, dy); "ok" }
            }
            path == "/click" -> { if (acc == null) "no-accessibility" else { acc.clickCursor(); "ok" } }
            path == "/closeall" -> { if (acc == null) "no-accessibility" else { acc.closeAll(); "ok" } }
            path == "/scroll" -> {
                val d = params["d"]?.toFloatOrNull() ?: 1f
                if (acc == null) "no-accessibility" else { acc.scrollCursor(d); "ok" }
            }

            path == "/launch" -> {
                val pkg = params["pkg"] ?: return "no-pkg"
                launch(pkg)
            }

            path == "/open" -> {
                val url = params["url"] ?: return "no-url"
                openUrl(url)
            }

            path == "/text" -> {
                val t = params["t"] ?: return "no-text"
                if (acc == null) "no-accessibility" else if (acc.typeText(t)) "ok" else "fail"
            }

            path == "/apps" -> listApps()

            else -> "unknown"
        }
    }

    private fun volume(dir: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (dir) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun media(k: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = when (k) {
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun launch(pkg: String): String {
        var intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            // apps de TV (leanback) não têm launcher comum
            val lb = Intent(Intent.ACTION_MAIN)
                .addCategory(LEANBACK)
                .setPackage(pkg)
            val ri = packageManager.queryIntentActivities(lb, 0).firstOrNull()
            if (ri != null) {
                intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(LEANBACK)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            }
        }
        if (intent == null) return "not-found"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { startActivity(intent); "ok" } catch (e: Exception) { "fail" }
    }

    private fun openUrl(url: String): String {
        var u = url.trim()
        if (!u.contains("://")) u = "https://$u"
        return try {
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // força abrir no navegador (nova aba), não num app aleatório
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val browser = packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
            if (browser != null && browser != "android") open.setPackage(browser)
            startActivity(open)
            "ok"
        } catch (e: Exception) { "fail: ${e.message}" }
    }

    private fun listApps(): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(LEANBACK)
        val all = pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(leanback, 0)
        return all
            .map { (it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName) to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .joinToString("\n") { "${it.first}\t${it.second}" }
    }

    private fun sizeStr(): String {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            "${dm.widthPixels}x${dm.heightPixels}"
        } catch (e: Exception) { "1920x1080" }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Receptor do controle", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Controle TV — receptor ligado")
            .setContentText("Pronto pra receber comandos do celular (porta $PORT).")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.close() } catch (_: Exception) {}
        scope.cancel()
    }
}
