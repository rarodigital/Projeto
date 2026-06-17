package com.raro.controletv.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Executa os comandos do controle DENTRO do TV Box, sem ADB nem root.
 * Navegação (setas) usa o foco de acessibilidade — funciona na maioria dos apps,
 * mas pode falhar em alguns (limitação do Android pra apps comuns).
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null

        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    // ---- Ações globais ----
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun powerDialog() = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- D-Pad (navegação por foco) ----
    fun up() = move(View.FOCUS_UP)
    fun down() = move(View.FOCUS_DOWN)
    fun left() = move(View.FOCUS_LEFT)
    fun right() = move(View.FOCUS_RIGHT)

    fun ok(): Boolean {
        val node = focusedNode()
        if (node != null) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n = n.parent
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // sem nó focado clicável: clica no centro da tela (UIs de toque)
        val (w, h) = screenSize()
        tap(w / 2, h / 2)
        return true
    }

    private fun move(direction: Int): Boolean {
        // 1) tenta mover o FOCO (apps que usam foco padrão do Android — como o CetusPlay faz)
        val node = focusedNode()
        if (node != null) {
            val next = node.focusSearch(direction)
            if (next != null && next != node) {
                if (next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
                if (next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) return true
            }
        }
        // 2) tenta ROLAR um container (listas/grades) no sentido do movimento
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollable(root)
            if (scrollable != null) {
                val act = when (direction) {
                    View.FOCUS_DOWN, View.FOCUS_RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(act)) return true
            }
        }
        // 3) fallback: deslize LONGO e firme (nunca um toque, pra não "abrir" sem querer)
        val (w, h) = screenSize()
        val cx = w / 2
        val cy = h / 2
        val dx = (w * 0.32f).toInt()
        val dy = (h * 0.32f).toInt()
        when (direction) {
            View.FOCUS_DOWN -> swipe(cx, cy, cx, cy - dy, 180)
            View.FOCUS_UP -> swipe(cx, cy, cx, cy + dy, 180)
            View.FOCUS_RIGHT -> swipe(cx, cy, cx - dx, cy, 180)
            View.FOCUS_LEFT -> swipe(cx, cy, cx + dx, cy, 180)
        }
        return true
    }

    // ---- Gestos de toque (modo mouse) ----
    fun tap(x: Int, y: Int) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs.toLong().coerceIn(20, 2000)))
            .build()
        dispatchGesture(g, null, null)
    }

    fun screenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        } catch (e: Exception) {
            1920 to 1080
        }
    }

    // ---- Cursor do mouse (trackpad de verdade) ----
    // Desenha uma bolinha por cima da tela (TYPE_ACCESSIBILITY_OVERLAY, não precisa de permissão).
    private val main = Handler(Looper.getMainLooper())
    private var cursor: View? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    private var cx = 0f
    private var cy = 0f
    private var cursorSize = 0

    fun showCursor() = main.post {
        if (cursor != null) return@post
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (w, h) = screenSize()
        cx = w / 2f; cy = h / 2f
        cursorSize = (40 * resources.displayMetrics.density).toInt()
        val v = CursorView(this)  // setinha de mouse (tip no canto superior-esquerdo)
        val lp = WindowManager.LayoutParams(
            cursorSize, cursorSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = cx.toInt()   // a ponta da setinha fica em (cx, cy)
            y = cy.toInt()
        }
        try { wm.addView(v, lp); cursor = v; cursorLp = lp } catch (_: Exception) {}
    }

    fun hideCursor() = main.post {
        cursor?.let { v ->
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        }
        cursor = null; cursorLp = null
    }

    /** Move o cursor por um delta (vindo do trackpad do celular). */
    fun moveCursor(dx: Float, dy: Float) = main.post {
        if (cursor == null) { showCursor(); return@post }
        val (w, h) = screenSize()
        cx = (cx + dx).coerceIn(0f, w - 1f)
        cy = (cy + dy).coerceIn(0f, h - 1f)
        val lp = cursorLp ?: return@post
        lp.x = cx.toInt()
        lp.y = cy.toInt()
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(cursor, lp) } catch (_: Exception) {}
    }

    /** Clica na posição atual do cursor. */
    fun clickCursor() {
        if (cursor == null) showCursor()
        tap(cx.toInt(), cy.toInt())
    }

    /** Rola na posição do cursor (swipe vertical). */
    fun scrollCursor(amount: Float) {
        val (_, h) = screenSize()
        val dist = (h * 0.25f)
        val toY = (cy + if (amount > 0) dist else -dist)
        swipe(cx.toInt(), cy.toInt(), cx.toInt(), toY.toInt(), 200)
    }

    private fun focusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val r = findScrollable(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    // ---- Limpar (fechar apps): abre recentes e clica em "limpar tudo" ----
    fun closeAll(): Boolean {
        recents()
        main.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val n = findClickableByText(root, listOf("limpar tudo", "fechar tudo", "clear all", "close all", "fechar todos", "limpar", "clear"))
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, 800)
        return true
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
        if (words.any { t.contains(it) }) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) { if (n.isClickable) return n; n = n.parent }
            return node
        }
        for (i in 0 until node.childCount) {
            val r = findClickableByText(node.getChild(i), words)
            if (r != null) return r
        }
        return null
    }

    // ---- Texto ----
    fun typeText(text: String): Boolean {
        val node = focusedNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

/** Desenha uma setinha de mouse (ponta no canto superior-esquerdo = posição do cursor). */
private class CursorView(context: Context) : View(context) {
    private val fill = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val stroke = Paint().apply {
        color = Color.parseColor("#111111")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.74f)
            lineTo(w * 0.22f, h * 0.57f)
            lineTo(w * 0.38f, h * 0.92f)
            lineTo(w * 0.52f, h * 0.85f)
            lineTo(w * 0.36f, h * 0.51f)
            lineTo(w * 0.62f, h * 0.49f)
            close()
        }
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }
}
