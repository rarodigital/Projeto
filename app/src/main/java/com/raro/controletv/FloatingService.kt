package com.raro.controletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Controle flutuante por cima de qualquer app. Bolha minimizada → toca e abre.
 * Tem modo Controle e modo Mouse, troca de aparelho, atalhos, arrasta de qualquer
 * lugar livre e dá pra mudar o tamanho. Reusa a conexão de [Remote].
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.raro.controletv.STOP_FLOATING"
        private const val CHANNEL = "floating"
        private const val NOTIF_ID = 42
    }

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("ctv", Context.MODE_PRIVATE) }

    private var mouseMode = false
    private val scales = floatArrayOf(0.85f, 1f, 1.2f)
    private var sizeIdx = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }
        root = FrameLayout(this)
        showCollapsed()
        wm.addView(root, params)
    }

    // ---------- Bolha ----------
    private fun showCollapsed() {
        root.removeAllViews()
        val s = dp(56)
        val bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(s, s)
            background = circle("#2962FF")
            addView(RemoteIcon(this@FloatingService), FrameLayout.LayoutParams(s, s))
        }
        attachDragAndTap(bubble) { showExpanded() }
        root.addView(bubble)
        refresh()
    }

    // ---------- Painel aberto ----------
    private fun showExpanded() {
        root.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(8), u(8), u(8), u(8))
            background = rounded("#F01C1F26")
        }
        attachDrag(panel) // arrasta por qualquer parte livre

        // Cabeçalho: título + modo + tamanho + minimizar + fechar
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = if (mouseMode) "Mouse" else "Controle"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scales[sizeIdx])
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDrag(title)
        header.addView(title)
        header.addView(smallBtn(if (mouseMode) "🎮" else "🖱") { mouseMode = !mouseMode; showExpanded() })
        header.addView(smallBtn("⤢") { sizeIdx = (sizeIdx + 1) % scales.size; showExpanded() })
        header.addView(smallBtn("—") { showCollapsed() })
        header.addView(smallBtn("✕") { prefs.edit().putBoolean("float_auto", false).apply(); stopSelf() })
        panel.addView(header)

        // Troca de aparelho
        deviceRow()?.let { panel.addView(it) }

        if (mouseMode) {
            panel.addView(centerRow(trackpad()))
            panel.addView(centerRow(
                keyBtn("Clique") { mClick() },
                keyBtn("Voltar") { act(RemoteAction.BACK) },
                keyBtn("Home") { act(RemoteAction.HOME) }
            ))
        } else {
            panel.addView(centerRow(keyBtn("▲") { act(RemoteAction.UP) }))
            panel.addView(centerRow(
                keyBtn("◀") { act(RemoteAction.LEFT) },
                keyBtn("OK") { act(RemoteAction.OK) },
                keyBtn("▶") { act(RemoteAction.RIGHT) }
            ))
            panel.addView(centerRow(keyBtn("▼") { act(RemoteAction.DOWN) }))
            panel.addView(centerRow(
                keyBtn("Voltar") { act(RemoteAction.BACK) },
                keyBtn("Home") { act(RemoteAction.HOME) },
                keyBtn("Menu") { act(RemoteAction.MENU) }
            ))
            panel.addView(centerRow(
                keyBtn("Vol −") { act(RemoteAction.VOL_DOWN) },
                keyBtn("🔇") { act(RemoteAction.MUTE) },
                keyBtn("Vol +") { act(RemoteAction.VOL_UP) }
            ))
            panel.addView(centerRow(
                keyBtn("⏯") { act(RemoteAction.PLAY_PAUSE) },
                keyBtn("Power") { act(RemoteAction.POWER) }
            ))
        }

        // Atalhos (apps fixados)
        val favs = favs()
        if (favs.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            favs.take(4).forEach { (name, pkg) -> row.addView(keyBtn(name) { launch(pkg) }) }
            panel.addView(row)
        }

        root.addView(panel)
        refresh()
    }

    private fun deviceRow(): View? {
        val devices = DeviceStore.load(prefs)
        if (devices.isEmpty()) return null
        val active = DeviceStore.activeId(prefs)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        devices.take(4).forEach { d ->
            val b = smallBtn((if (d.type == "lg") "📺 " else "📦 ") + d.name) { activate(d) }
            if (d.id == active) b.setTextColor(Color.parseColor("#6C8CFF"))
            row.addView(b)
        }
        return row
    }

    private fun activate(d: SavedDevice) {
        DeviceStore.setActive(prefs, d.id)
        if (d.type == "lg") { Remote.device = "lg"; Remote.lg.useHost(d.ip) }
        else {
            Remote.device = "box"; Remote.boxMode = d.mode
            if (d.mode == "receiver") scope.launch {
                try { withContext(Dispatchers.IO) { Remote.boxReceiver.setHost(d.ip); Remote.boxReceiver.fetchSize() } } catch (_: Exception) {}
            }
        }
        showExpanded()
    }

    // ---------- Trackpad (modo mouse) ----------
    private fun trackpad(): View {
        val v = View(this).apply {
            background = rounded("#33FFFFFF")
            layoutParams = LinearLayout.LayoutParams(u(210), u(130)).apply { setMargins(u(6), u(6), u(6), u(6)) }
        }
        var lastX = 0f; var lastY = 0f; var moved = 0f; var downT = 0L
        var accX = 0f; var accY = 0f; var last = 0L
        val sens = 2.2f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; moved = 0f; downT = System.currentTimeMillis(); accX = 0f; accY = 0f; mShow(true); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX; val dy = e.rawY - lastY; lastX = e.rawX; lastY = e.rawY
                    moved += abs(dx) + abs(dy); accX += dx * sens; accY += dy * sens
                    val t = System.currentTimeMillis()
                    if (t - last > 45) { mMove(accX.toInt(), accY.toInt()); accX = 0f; accY = 0f; last = t }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved < dp(10) && System.currentTimeMillis() - downT < 300) mClick()
                    else mMove(accX.toInt(), accY.toInt())
                    true
                }
                else -> false
            }
        }
        return v
    }

    // ---------- Ações ----------
    private fun act(a: RemoteAction) { scope.launch { try { withContext(Dispatchers.IO) { Remote.send(a) } } catch (_: Exception) {} } }
    private fun mShow(on: Boolean) { scope.launch { try { withContext(Dispatchers.IO) { Remote.mouseShow(on) } } catch (_: Exception) {} } }
    private fun mMove(x: Int, y: Int) { scope.launch { try { withContext(Dispatchers.IO) { Remote.mouseMove(x, y) } } catch (_: Exception) {} } }
    private fun mClick() { scope.launch { try { withContext(Dispatchers.IO) { Remote.mouseShow(true); Remote.mouseClick() } } catch (_: Exception) {} } }
    private fun launch(pkg: String) { scope.launch { try { withContext(Dispatchers.IO) { Remote.launchApp(pkg) } } catch (_: Exception) {} } }

    private fun favs(): List<Pair<String, String>> =
        (prefs.getString("favs", "") ?: "").split("\n").filter { it.contains("\t") }
            .map { val p = it.split("\t"); p[0] to p.getOrElse(1) { "" } }.filter { it.second.isNotBlank() }

    // ---------- UI helpers ----------
    private fun keyBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; minWidth = 0; minimumWidth = u(48); minHeight = u(44); minimumHeight = u(44)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scales[sizeIdx])
        setPadding(u(12), u(8), u(12), u(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(u(5), u(5), u(5), u(5)) }
        setOnClickListener { onClick() }
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scales[sizeIdx])
        setPadding(u(8), u(4), u(8), u(4)); setOnClickListener { onClick() }
    }

    private fun centerRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; views.forEach { addView(it) }
    }

    private fun circle(color: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
    private fun rounded(color: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(color)) }

    private fun attachDrag(view: View) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt(); refresh(); true }
                else -> false
            }
        }
    }

    private fun attachDragAndTap(view: View, onTap: () -> Unit) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    params.x = sx + dx.toInt(); params.y = sy + dy.toInt(); refresh(); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
    }

    private fun refresh() { try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun u(v: Int): Int = (v * resources.displayMetrics.density * scales[sizeIdx]).toInt()

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Controle flutuante", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_STOP }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 0, stopIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Controle TV flutuante")
            .setContentText("Toque na bolha pra abrir.")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar", stopPi)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(root) } catch (_: Exception) {}
        scope.cancel()
    }

    /** Ícone de controle remoto desenhado (pra bolha). */
    private class RemoteIcon(ctx: Context) : View(ctx) {
        private val body = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.FILL }
        private val btn = Paint().apply { color = Color.parseColor("#2962FF"); isAntiAlias = true; style = Paint.Style.FILL }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val bw = w * 0.40f; val bh = h * 0.66f; val l = (w - bw) / 2; val t = (h - bh) / 2
            c.drawRoundRect(l, t, l + bw, t + bh, bw * 0.42f, bw * 0.42f, body)
            c.drawCircle(w / 2, t + bh * 0.26f, bw * 0.15f, btn)
            c.drawCircle(w / 2, t + bh * 0.6f, bw * 0.07f, btn)
            c.drawCircle(w / 2, t + bh * 0.78f, bw * 0.07f, btn)
        }
    }
}
