package com.raro.controletv.receiver

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

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
        val node = focusedNode() ?: return false
        // tenta o nó clicável mais próximo subindo na árvore
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun move(direction: Int): Boolean {
        val node = focusedNode()
        val next = node?.focusSearch(direction)
        if (next != null) {
            val ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (ok) return true
        }
        // fallback: rolar a tela no sentido do movimento
        val root = rootInActiveWindow ?: return false
        val scrollAction = when (direction) {
            View.FOCUS_DOWN, View.FOCUS_RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val scrollable = findScrollable(root)
        return scrollable?.performAction(scrollAction) ?: false
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

    // ---- Texto ----
    fun typeText(text: String): Boolean {
        val node = focusedNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
