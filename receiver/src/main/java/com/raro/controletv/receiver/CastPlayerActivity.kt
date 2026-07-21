package com.raro.controletv.receiver

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Player de tela cheia usado pelo NCastDlnaService pra tocar o que chega via DLNA (SetAVTransportURI).
 * NÃO é usado pro cast do YouTube (isso abre o app oficial direto, ver NCastDlnaService.launchYouTube).
 */
class CastPlayerActivity : Activity() {

    private data class TextTrack(val group: Tracks.Group, val index: Int, val label: String)

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var ccButton: TextView
    private var subtitleStep = -1

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NCastDlnaService.ACTION_STOP -> finish()
                NCastDlnaService.ACTION_PAUSE -> player?.pause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeAndUnlock()
        hideSystemUi()

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
            setShowSubtitleButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setBackgroundColor(ViewCompat.MEASURED_STATE_MASK)
        }
        overlay = TextView(this).apply {
            setTextColor(-1)
            setBackgroundColor(-1728053248)
            textSize = 16f
            setPadding(24, 14, 24, 14)
            visibility = View.GONE
        }
        ccButton = TextView(this).apply {
            text = "CC"
            setTextColor(-1)
            setBackgroundColor(-1440866786)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(24, 14, 24, 14)
            setOnClickListener { cycleSubtitles() }
        }

        val layout = FrameLayout(this)
        layout.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val overlayParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        layout.addView(overlay, overlayParams)
        val ccParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 18
            rightMargin = 18
        }
        layout.addView(ccButton, ccParams)
        setContentView(layout)

        val filter = IntentFilter().apply {
            addAction(NCastDlnaService.ACTION_STOP)
            addAction(NCastDlnaService.ACTION_PAUSE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }

        playFromIntent(intent)
    }

    /** Acorda a tela e passa por cima do bloqueio — pra tocar sozinho quando o Play chegar via DLNA sem precisar abrir o app na mão. */
    private fun wakeAndUnlock() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        keyguardManager?.requestDismissKeyguard(this, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playFromIntent(intent)
    }

    private fun playFromIntent(intent: Intent) {
        val url = intent.getStringExtra(NCastDlnaService.EXTRA_URL) ?: ""
        val title = intent.getStringExtra(NCastDlnaService.EXTRA_TITLE) ?: ""
        val meta = intent.getStringExtra(NCastDlnaService.EXTRA_META) ?: ""
        subtitleStep = -1

        if (url.isBlank()) {
            showMessage("Nenhuma URL recebida")
            return
        }
        showMessage(if (title.isNotBlank()) "Carregando: $title" else "Carregando transmissão DLNA...")

        if (player == null) {
            // Fix: sem isso o ExoPlayer usa User-Agent genérico, não segue redirect http<->https,
            // e timeout padrão de 8s — vários servidores IPTV bloqueiam/travam nesse cenário
            // (era a causa do ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT reportado).
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) NCastReceiver/ExoPlayer")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
            val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

            val exo = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> showMessage("Bufferizando...")
                        Player.STATE_READY -> {
                            overlay.visibility = View.GONE
                            updateCcLabel()
                        }
                        Player.STATE_ENDED -> showMessage("Transmissão encerrada")
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateCcLabel()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val message = error.message ?: "Verifique se o stream é compatível."
                    showMessage("Erro ao reproduzir: ${error.errorCodeName}\n$message")
                }
            })
            player = exo
        }

        val subtitles = extractSubtitleConfigs(meta)
        if (subtitles.isNotEmpty()) {
            showMessage("Carregando com ${subtitles.size} legenda(s) externa(s) detectada(s)...")
        }
        val item = MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(subtitles).build()
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun textTracks(): List<TextTrack> {
        val tracks = player?.currentTracks ?: return emptyList()
        return tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).mapNotNull { index ->
                if (!group.isTrackSupported(index)) return@mapNotNull null
                val format = group.getTrackFormat(index)
                val label = listOfNotNull(format.label, format.language).firstOrNull { it.isNotBlank() }
                    ?: "Legenda ${index + 1}"
                TextTrack(group, index, label)
            }
        }
    }

    private fun cycleSubtitles() {
        val p = player ?: return
        val tracks = textTracks()
        if (tracks.isEmpty()) {
            showMessage("Nenhuma legenda foi recebida neste stream.\nSe o UniTV não enviar a faixa de legenda por DLNA, o receiver não consegue exibir.")
            return
        }
        subtitleStep = if (subtitleStep < 0) 1 else (subtitleStep + 1) % (tracks.size + 1)
        val builder = p.trackSelectionParameters.buildUpon()
        if (subtitleStep == 0) {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            p.trackSelectionParameters = builder.build()
            showMessage("Legendas desligadas")
        } else {
            val selected = tracks[subtitleStep - 1]
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(selected.group.mediaTrackGroup, listOf(selected.index)))
            p.trackSelectionParameters = builder.build()
            showMessage("Legenda: ${selected.label}")
        }
        updateCcLabel()
    }

    private fun updateCcLabel() {
        val count = textTracks().size
        ccButton.text = if (count > 0) "CC $count" else "CC —"
    }

    private fun extractSubtitleConfigs(meta: String): List<MediaItem.SubtitleConfiguration> {
        if (meta.isBlank()) return emptyList()
        val regex = Regex("""https?://[^\s<'"]+\.(?:srt|vtt|ass|ssa)(?:\?[^\s<'"]*)?""", RegexOption.IGNORE_CASE)
        val urls = regex.findAll(meta).map { it.value.replace("&amp;", "&") }.distinct().toList()
        return urls.map { u ->
            val lower = u.lowercase()
            val mime = when {
                lower.contains(".vtt") -> MimeTypes.TEXT_VTT
                lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(u)).setMimeType(mime).setSelectionFlags(1).build()
        }
    }

    private fun showMessage(text: String) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            // já desregistrado
        }
        player?.release()
        player = null
        super.onDestroy()
    }
}
