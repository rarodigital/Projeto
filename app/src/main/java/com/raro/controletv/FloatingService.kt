package com.raro.controletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
 * Controle flutuante (estilo "bolha de mensagem"): fica por cima de qualquer app.
 * Minimizado vira uma bolinha; toca nela e abre o controle. ✕ fecha de vez.
 * Reusa a conexão de [Remote] (a mesma do app).
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
    private var expanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        root = FrameLayout(this)
        showCollapsed()
        wm.addView(root, params)
    }

    // ---------- Bolha minimizada ----------
    private fun showCollapsed() {
        expanded = false
        root.removeAllViews()
        val bubble = TextView(this).apply {
            text = "📺"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            val s = dp(56)
            layoutParams = FrameLayout.LayoutParams(s, s)
            background = circle("#2962FF")
        }
        attachDragAndTap(bubble) { showExpanded() }
        root.addView(bubble)
        refresh()
    }

    // ---------- Controle aberto ----------
    private fun showExpanded() {
        expanded = true
        root.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded("#F0202124")
        }

        // Cabeçalho: arrastar + minimizar + fechar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Controle TV"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDrag(title) // arrasta o painel pelo título
        header.addView(title)
        header.addView(smallBtn("—") { showCollapsed() })
        header.addView(smallBtn("✕") { stopSelf() })
        panel.addView(header)

        // ▲
        panel.addView(centerRow(keyBtn("▲") { act(RemoteAction.UP) }))
        // ◀ OK ▶
        panel.addView(
            centerRow(
                keyBtn("◀") { act(RemoteAction.LEFT) },
                keyBtn("OK") { act(RemoteAction.OK) },
                keyBtn("▶") { act(RemoteAction.RIGHT) }
            )
        )
        // ▼
        panel.addView(centerRow(keyBtn("▼") { act(RemoteAction.DOWN) }))
        // Voltar / Home / Menu
        panel.addView(
            centerRow(
                keyBtn("Voltar") { act(RemoteAction.BACK) },
                keyBtn("Home") { act(RemoteAction.HOME) },
                keyBtn("Menu") { act(RemoteAction.MENU) }
            )
        )
        // Vol- / Mudo / Vol+
        panel.addView(
            centerRow(
                keyBtn("Vol −") { act(RemoteAction.VOL_DOWN) },
                keyBtn("Mudo") { act(RemoteAction.MUTE) },
                keyBtn("Vol +") { act(RemoteAction.VOL_UP) }
            )
        )
        // Play/Pause / Power
        panel.addView(
            centerRow(
                keyBtn("⏯") { act(RemoteAction.PLAY_PAUSE) },
                keyBtn("Power") { act(RemoteAction.POWER) }
            )
        )

        root.addView(panel)
        refresh()
    }

    // ---------- Ação ----------
    private fun act(a: RemoteAction) {
        scope.launch {
            try { withContext(Dispatchers.IO) { Remote.send(a) } } catch (_: Exception) {}
        }
    }

    // ---------- Helpers de UI ----------
    private fun keyBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setOnClickListener { onClick() }
    }

    private fun centerRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { addView(it) }
    }

    private fun circle(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(color))
    }

    private fun rounded(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor(color))
    }

    /** Arrasta a janela inteira tocando na [view] (sem detectar tap). */
    private fun attachDrag(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    refresh(); true
                }
                else -> false
            }
        }
    }

    /** Arrasta a bolha; se o movimento foi pequeno, conta como toque e chama [onTap]. */
    private fun attachDragAndTap(view: View, onTap: () -> Unit) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    refresh(); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun refresh() {
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // ---------- Notificação (foreground service) ----------
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Controle flutuante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_STOP }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 0, stopIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Controle TV flutuante ligado")
            .setContentText("Toque na bolha pra abrir o controle.")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar", stopPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(root) } catch (_: Exception) {}
        scope.cancel()
    }
}
