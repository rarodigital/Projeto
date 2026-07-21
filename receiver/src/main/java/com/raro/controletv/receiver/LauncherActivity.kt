package com.raro.controletv.receiver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SimpleAdapter
import android.widget.TextView

/**
 * Tela inicial do S9: grade de apps grandes (UniTV/YouTube/Chrome fixos + favoritos salvos).
 * Registrada como HOME no manifest — Android pergunta "usar como padrão?" na primeira vez.
 */
class LauncherActivity : Activity() {

    private data class Tile(val label: String, val pkg: String, val fixed: Boolean)

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E0E12"))
        }

        val title = TextView(this).apply {
            text = "NCast Receiver"
            setTextColor(Color.WHITE)
            textSize = 22f
        }
        root.addView(title)

        grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(16), 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        rebuildGrid()
        requestBluetoothPermissionIfNeeded()
    }

    /** No Android 12+ conectar bluetooth por código exige permissão em runtime. */
    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildGrid()
    }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val tiles = mutableListOf<Tile>()
        resolveFixed("unitv", listOf("unitv"))?.let { tiles += Tile("UniTV", it, true) }
        resolveFixed("YouTube", youtubeCandidates())?.let { tiles += Tile("YouTube", it, true) }
        resolveChrome()?.let { tiles += Tile("Chrome", it, true) }
        tiles += loadFavorites()

        for (t in tiles) addTile(t)
        addAddTile()
        addAppsTile()
        addSettingsTile()
    }

    // ---- resolução de apps fixos ----

    /** Acha o pacote instalado cujo nome/label contém alguma das palavras-chave. */
    private fun resolveFixed(label: String, keywords: List<String>): String? {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, 0)
        for (info in candidates) {
            val pkg = info.activityInfo.packageName
            val appLabel = info.loadLabel(pm).toString().lowercase()
            if (keywords.any { pkg.lowercase().contains(it) || appLabel.contains(it) }) return pkg
        }
        return null
    }

    private fun youtubeCandidates() = listOf(
        "com.google.android.youtube", "com.google.android.youtube.tv",
        "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.vanced.android.youtube", "com.vanced.android.youtube.tv",
        "smarttube", "youtube"
    )

    private fun resolveChrome(): String? {
        val pm = packageManager
        if (isInstalled("com.android.chrome")) return "com.android.chrome"
        // sem Chrome: usa o navegador padrão do aparelho
        val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return pm.resolveActivity(probe, 0)?.activityInfo?.packageName
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    // ---- favoritos (persistidos) ----

    private fun loadFavorites(): List<Tile> =
        (prefs.getString("favs", "") ?: "").lineSequence()
            .mapNotNull {
                val p = it.split("\t")
                if (p.size >= 2) Tile(p[0], p[1], false) else null
            }.toList()

    private fun saveFavorites(favs: List<Tile>) {
        prefs.edit().putString("favs", favs.joinToString("\n") { "${it.label}\t${it.pkg}" }).apply()
    }

    private fun addFavorite(label: String, pkg: String) {
        val favs = loadFavorites().toMutableList()
        if (favs.none { it.pkg == pkg }) {
            favs += Tile(label, pkg, false)
            saveFavorites(favs)
        }
        rebuildGrid()
    }

    private fun removeFavorite(pkg: String) {
        saveFavorites(loadFavorites().filter { it.pkg != pkg })
        rebuildGrid()
    }

    // ---- UI de cada ladrilho ----

    private fun addTile(t: Tile) {
        val icon = ImageView(this).apply {
            try { setImageDrawable(packageManager.getApplicationIcon(t.pkg)) } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        val label = TextView(this).apply {
            text = t.label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            isFocusable = true
            isFocusableInTouchMode = false
            background = tileBackground()
            addView(icon)
            addView(label)
            setOnClickListener {
                // UniTV é o receptor NCast: o atalho deve abrir a tela de transmissão do
                // próprio NCast Receiver (CastPlayerActivity), não o app UniTV nativo do S9.
                if (t.label == "UniTV") openTransmission() else launch(t.pkg)
            }
            if (!t.fixed) {
                setOnLongClickListener {
                    AlertDialog.Builder(this@LauncherActivity)
                        .setTitle("Remover \"${t.label}\" da tela inicial?")
                        .setPositiveButton("Remover") { _, _ -> removeFavorite(t.pkg) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    true
                }
            }
        }
        val params = GridLayout.LayoutParams().apply {
            width = dp(120); height = dp(120)
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }
        grid.addView(box, params)
    }

    private fun addAddTile() = addActionTile("+", "Adicionar app", dashed = true, iconSize = 32f) { showAppPicker() }

    private fun addAppsTile() = addActionTile("▦", "Apps", iconSize = 28f) { showAllAppsAndLaunch() }

    private fun addSettingsTile() = addActionTile("⚙", "Configurações", iconSize = 28f) {
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    /** Ladrilho genérico de ação (não abre um app fixo/favorito, executa [action] direto). */
    private fun addActionTile(icon: String, label: String, dashed: Boolean = false, iconSize: Float = 28f, action: () -> Unit) {
        val iconView = TextView(this).apply {
            text = icon
            setTextColor(Color.WHITE)
            textSize = iconSize
            gravity = Gravity.CENTER
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            background = tileBackground(dashed)
            addView(iconView)
            addView(labelView)
            setOnClickListener { action() }
        }
        val params = GridLayout.LayoutParams().apply {
            width = dp(120); height = dp(120)
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }
        grid.addView(box, params)
    }

    /** Lista TODOS os apps instalados; tocar já abre (painel "Apps" tipo Fire TV/Chromecast/TV Box). */
    private fun showAllAppsAndLaunch() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanback = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = (pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(leanback, 0))
            .distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }

        val listView = ListView(this)
        listView.adapter = SimpleAdapter(
            this, apps.map { mapOf("label" to it.first) },
            android.R.layout.simple_list_item_1, arrayOf("label"), intArrayOf(android.R.id.text1)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Todos os apps")
            .setView(FrameLayout(this).apply {
                addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)))
            })
            .setNegativeButton("Fechar", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            launch(apps[position].second)
        }
        dialog.show()
    }

    private fun tileBackground(dashed: Boolean = false): StateListDrawable {
        val normal = ColorDrawable(Color.parseColor(if (dashed) "#1A1A22" else "#1E1E26"))
        val focused = ColorDrawable(Color.parseColor("#3A6EE0"))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /** Lista todos os apps com launcher (comuns + leanback) pra escolher o que fixar. */
    private fun showAppPicker() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanback = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER")
        val apps = (pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(leanback, 0))
            .distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }

        val listView = ListView(this)
        listView.adapter = SimpleAdapter(
            this, apps.map { mapOf("label" to it.first) },
            android.R.layout.simple_list_item_1, arrayOf("label"), intArrayOf(android.R.id.text1)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Adicionar à tela inicial")
            .setView(FrameLayout(this).apply {
                addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)))
            })
            .setNegativeButton("Fechar", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val (label, pkg) = apps[position]
            addFavorite(label, pkg)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openTransmission() {
        startActivity(Intent(this, CastPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launch(pkg: String) {
        var intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            val lb = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER").setPackage(pkg)
            val ri = packageManager.queryIntentActivities(lb, 0).firstOrNull()
            if (ri != null) {
                intent = Intent(Intent.ACTION_MAIN)
                    .addCategory("android.intent.category.LEANBACK_LAUNCHER")
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            }
        }
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) startActivity(intent)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
