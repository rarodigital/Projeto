package com.raro.controletv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor DLNA/UPnP MediaRenderer + DIAL(YouTube) falso — se anuncia na rede (SSDP) como um
 * dispositivo de cast pra que apps como unitv/YouTube mandem o stream direto pra cá, sem precisar
 * sniffar rede manualmente. Ao receber Play (DLNA AVTransport), abre CastPlayerActivity com a URL.
 * Pro YouTube, usa YouTubeLoungeSession (protocolo Lounge oficial do YouTube) e abre o app oficial.
 */
class NCastDlnaService : Service() {

    companion object {
        const val ACTION_STOP = "com.raro.customcastreceiver.STOP"
        const val ACTION_PAUSE = "com.raro.customcastreceiver.PAUSE"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_META = "meta"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900

        fun xmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun extractTitle(meta: String): String {
            val match = Regex("<dc:title[^>]*>(.*?)</dc:title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(meta)
            return match?.groupValues?.getOrNull(1) ?: ""
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val queryParams: Map<String, String>,
    )

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var httpServer: ServerSocket? = null
    private val running = AtomicBoolean(false)
    @Volatile private var currentUri: String = ""
    @Volatile private var currentMeta: String = ""
    @Volatile private var transportState: String = "STOPPED"
    @Volatile private var volume: Int = 70
    private lateinit var youtubeLounge: YouTubeLoungeSession
    private val httpPort = 49152

    private val uuid: String by lazy {
        val bytes = (Build.MANUFACTURER + Build.MODEL + Build.SERIAL + "ncast-dlna").toByteArray(Charsets.UTF_8)
        "uuid:" + UUID.nameUUIDFromBytes(bytes)
    }
    private val dialUuid: String by lazy {
        val bytes = (Build.MANUFACTURER + Build.MODEL + Build.SERIAL + "ncast-youtube-dial").toByteArray(Charsets.UTF_8)
        "uuid:" + UUID.nameUUIDFromBytes(bytes)
    }

    override fun onCreate() {
        super.onCreate()
        // Precisa promover a foreground JÁ (senão o Android derruba o app com
        // "did not then call Service.startForeground()" quando iniciado via
        // startForegroundService) — só depois disso checar a licença.
        startForeground(1001, notification())
        // TEMP-BYPASS (2026-07-21, autorizado pelo Adalto): a checagem de premium ficou
        // impossível de revalidar nesta instalação (não existe tela de login no app
        // ainda) depois do uninstall que resolveu o problema de assinatura/Play Protect.
        // Reativar assim que houver uma tela de login chamando NServerAuth.login/validate,
        // ou confirmação de outro jeito de restaurar o premium salvo.
        // if (!NServerAuth.isPremium(this)) {
        //     stopSelf()
        //     return
        // }
        running.set(true)
        val name = getString(R.string.ncast_receiver_name)
        youtubeLounge = YouTubeLoungeSession(this, name) { url ->
            currentUri = url
            transportState = "PLAYING"
            launchYouTube(url)
        }
        youtubeLounge.ensureStarted()
        acquireLocks()
        startHttpServer()
        startSsdpResponder()
        startSsdpAliveBroadcaster()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running.set(true)
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ncast-ssdp-multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ncast:dlna-service").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Escuta M-SEARCH multicast (porta 1900) e responde unicast quando o ST bate com nossos targets. */
    private fun startSsdpResponder() = Thread({
        try {
            MulticastSocket(SSDP_PORT).use { socket ->
                socket.reuseAddress = true
                socket.joinGroup(java.net.InetAddress.getByName(SSDP_ADDRESS))
                val buf = ByteArray(4096)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val firstLine = text.lineSequence().firstOrNull() ?: continue
                        if (!firstLine.startsWith("M-SEARCH", ignoreCase = true)) continue
                        val st = headerValue(text, "ST")
                        if (st.isBlank()) continue
                        val ip = localIp()
                        val targets = rendererSearchTargets() + dialSearchTargets()
                        val matches = st == "ssdp:all" || targets.any { it.equals(st, ignoreCase = true) }
                        if (!matches) continue
                        val isDial = dialSearchTargets().any { it.equals(st, ignoreCase = true) }
                        val response = ssdpResponse(ip, st, isDial)
                        val data = response.toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(data, data.size, packet.address, packet.port))
                    } catch (e: Exception) {
                        if (!running.get()) break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, "ssdp-responder").apply { isDaemon = true; start() }

    private fun startSsdpAliveBroadcaster() = Thread({
        val target = InetSocketAddress(SSDP_ADDRESS, SSDP_PORT)
        DatagramSocket().use { socket ->
            while (running.get()) {
                try {
                    val ip = localIp()
                    for (nt in rendererSearchTargets()) {
                        try {
                            val data = ssdpNotify(ip, nt, false).toByteArray(Charsets.UTF_8)
                            socket.send(DatagramPacket(data, data.size, target))
                            Thread.sleep(80)
                        } catch (e: Exception) {
                            Thread.sleep(5000)
                        }
                    }
                    for (nt in dialSearchTargets()) {
                        val data = ssdpNotify(ip, nt, true).toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(data, data.size, target))
                        Thread.sleep(80)
                    }
                    Thread.sleep(30000)
                } catch (e: Exception) {
                    // segue tentando no próximo ciclo
                }
            }
        }
    }, "ssdp-alive").apply { isDaemon = true; start() }

    private fun startHttpServer() = Thread({
        try {
            val server = ServerSocket(httpPort)
            httpServer = server
            while (running.get()) {
                try {
                    val client = server.accept()
                    Thread({ handleHttp(client) }, "upnp-client").apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    // aceita de novo no próximo loop
                }
            }
            try { server.close() } catch (e: Exception) { /* já fechado */ }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, "upnp-http").apply { isDaemon = true; start() }

    private fun handleHttp(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 8000
            val req = readHttpRequest(sock) ?: return
            val method = req.method.uppercase(Locale.US)
            val path = req.path.substringBefore('?')

            if (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") {
                val out = sock.getOutputStream()
                writeRaw(out, "HTTP/1.1 200 OK\r\nSID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\nConnection: close\r\n\r\n")
                return
            }

            if (method == "POST" && path.startsWith("/control/")) {
                val soapAction = req.headers["soapaction"] ?: ""
                val response = handleSoap(path, soapAction, req.body)
                writeHttp(sock.getOutputStream(), response, "text/xml; charset=utf-8")
                return
            }

            if (path.startsWith("/ytcr/")) {
                handleDialHttp(sock.getOutputStream(), method, path, req.body)
                return
            }

            val body = when (path) {
                "/description.xml" -> descriptionXml(localIp())
                "/connectionmanager.xml" -> connectionManagerScpd()
                "/" -> statusText()
                "/play" -> {
                    val uri = req.queryParams[EXTRA_URL] ?: ""
                    if (uri.isNotBlank()) {
                        currentUri = uri
                        transportState = "PLAYING"
                        launchPlayer(uri)
                    }
                    "OK"
                }
                "/avtransport.xml" -> avTransportScpd()
                "/renderingcontrol.xml" -> renderingControlScpd()
                else -> "NCast Receiver OK"
            }
            val type = if (path.endsWith(".xml")) "text/xml; charset=utf-8" else "text/plain; charset=utf-8"
            writeHttp(sock.getOutputStream(), body, type)
        }
    }

    private fun handleSoap(path: String, soapAction: String, body: String): String {
        val action = soapAction.substringAfter('#', soapAction).trim('"')
        val isAvTransport = path.contains("avtransport", ignoreCase = true)
        val isConnMgr = path.contains("connectionmanager", ignoreCase = true)
        val isRenderCtl = path.contains("renderingcontrol", ignoreCase = true)

        return when {
            isAvTransport && action.contains("SetAVTransportURI", ignoreCase = true) -> {
                currentUri = extractTag(body, "CurrentURI")
                currentMeta = extractTag(body, "CurrentURIMetaData")
                transportState = "STOPPED"
                soapEnvelope("<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            }
            isAvTransport && action.contains("SetNextAVTransportURI", ignoreCase = true) ->
                soapEnvelope("<u:SetNextAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            isAvTransport && action.contains("Play", ignoreCase = true) -> {
                transportState = "PLAYING"
                launchPlayer(currentUri)
                soapEnvelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            }
            isAvTransport && action.contains("Pause", ignoreCase = true) -> {
                transportState = "PAUSED_PLAYBACK"
                sendPlayerCommand(ACTION_PAUSE)
                soapEnvelope("<u:PauseResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            }
            isAvTransport && action.contains("Stop", ignoreCase = true) -> {
                transportState = "STOPPED"
                sendPlayerCommand(ACTION_STOP)
                soapEnvelope("<u:StopResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            }
            isAvTransport && action.contains("Seek", ignoreCase = true) ->
                soapEnvelope("<u:SeekResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            isAvTransport && action.contains("GetTransportInfo", ignoreCase = true) -> soapEnvelope(
                """
                <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>$transportState</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                  <CurrentSpeed>1</CurrentSpeed>
                </u:GetTransportInfoResponse>
                """.trimIndent()
            )
            isAvTransport && action.contains("GetMediaInfo", ignoreCase = true) -> soapEnvelope(
                "<u:GetMediaInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<NrTracks>1</NrTracks><MediaDuration>00:00:00</MediaDuration>" +
                    "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI><CurrentURIMetaData>${xmlEscape(currentMeta)}</CurrentURIMetaData>" +
                    "<NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium>" +
                    "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>" +
                    "</u:GetMediaInfoResponse>"
            )
            isAvTransport && action.contains("GetPositionInfo", ignoreCase = true) -> soapEnvelope(
                "<u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<Track>1</Track><TrackDuration>00:00:00</TrackDuration><TrackMetaData>${xmlEscape(currentMeta)}</TrackMetaData>" +
                    "<TrackURI>${xmlEscape(currentUri)}</TrackURI><RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime>" +
                    "<RelCount>0</RelCount><AbsCount>0</AbsCount></u:GetPositionInfoResponse>"
            )
            isConnMgr && action.contains("GetProtocolInfo", ignoreCase = true) ->
                soapEnvelope(
                    "<u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">" +
                        "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/webm:*," +
                        "http-get:*:video/mpeg:*,http-get:*:video/MP2T:*,http-get:*:application/vnd.apple.mpegurl:*," +
                        "http-get:*:application/x-mpegURL:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*," +
                        "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:*:*</Sink></u:GetProtocolInfoResponse>"
                )
            isConnMgr && action.contains("GetCurrentConnectionIDs", ignoreCase = true) ->
                soapEnvelope("<u:GetCurrentConnectionIDsResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\"><ConnectionIDs>0</ConnectionIDs></u:GetCurrentConnectionIDsResponse>")
            isConnMgr && action.contains("GetCurrentConnectionInfo", ignoreCase = true) ->
                soapEnvelope("<u:GetCurrentConnectionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\"><RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo>http-get:*:*:*</ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status></u:GetCurrentConnectionInfoResponse>")
            isRenderCtl && action.contains("GetVolume", ignoreCase = true) ->
                soapEnvelope("<u:GetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"><CurrentVolume>$volume</CurrentVolume></u:GetVolumeResponse>")
            isRenderCtl && action.contains("SetVolume", ignoreCase = true) -> {
                extractTag(body, "DesiredVolume").toIntOrNull()?.let { volume = it.coerceIn(0, 100) }
                soapEnvelope("<u:SetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\" />")
            }
            isRenderCtl && action.contains("GetMute", ignoreCase = true) ->
                soapEnvelope("<u:GetMuteResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"><CurrentMute>0</CurrentMute></u:GetMuteResponse>")
            else -> {
                val safeAction = action.ifBlank { "OK" }
                soapEnvelope("<u:${safeAction}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\" />")
            }
        }
    }

    private fun extractTag(xml: String, tag: String): String {
        val re = Regex("<[^:>/]*:?$tag[^>]*>(.*?)</[^:>/]*:?$tag>", RegexOption.DOT_MATCHES_ALL)
        val raw = re.find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun launchPlayer(url: String) {
        if (url.isBlank()) return
        val intent = Intent(this, CastPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, extractTitle(currentMeta).ifBlank { "Transmissão DLNA" })
            putExtra(EXTRA_META, currentMeta)
        }
        startActivity(intent)
    }

    private fun launchYouTube(url: String) {
        for (pkg in youtubePackages()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // tenta o próximo pacote da lista
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            launchPlayer(url)
        }
    }

    private fun youtubePackages(): List<String> {
        val preferred = listOf(
            "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta",
            "app.revanced.android.youtube", "app.rvx.android.youtube",
            "com.vanced.android.youtube", "com.vanced.android.youtube.tv",
            "org.schabi.newpipe", "org.schabi.newpipelegacy",
            "com.google.android.youtube.tv", "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
        )
        val installed = try {
            packageManager.getInstalledApplications(0).mapNotNull { app: ApplicationInfo ->
                val pkg = app.packageName ?: return@mapNotNull null
                val label = try { packageManager.getApplicationLabel(app).toString() } catch (e: Exception) { "" }
                val hay = "$pkg $label".lowercase(Locale.US)
                if (listOf("smarttube", "revanced", "vanced", "youtube", "newpipe").any { hay.contains(it) }) pkg else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        val rank = { pkg: String ->
            when {
                pkg.contains("smarttube", ignoreCase = true) -> 0
                pkg.contains("revanced", ignoreCase = true) || pkg.contains("rvx", ignoreCase = true) -> 1
                pkg.contains("vanced", ignoreCase = true) -> 2
                pkg.contains("newpipe", ignoreCase = true) -> 3
                pkg == "com.google.android.youtube.tv" -> 4
                pkg == "com.google.android.youtube" -> 5
                else -> 6
            }
        }
        return (preferred + installed)
            .filter { it != packageName }
            .distinct()
            .sortedWith(compareBy({ rank(it) }, { it }))
    }

    private fun sendPlayerCommand(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun handleDialHttp(out: OutputStream, method: String, path: String, body: String) {
        val ip = localIp()
        when {
            method == "GET" && path == "/ytcr/ssdp/device-desc.xml" ->
                writeHttp(out, dialDescriptionXml(ip), "application/xml; charset=utf-8", mapOf("Application-URL" to "http://$ip:$httpPort/ytcr/apps"))
            method == "GET" && path == "/ytcr/apps" ->
                writeRaw(out, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
            method == "GET" && path == "/ytcr/apps/YouTube" ->
                writeHttp(out, youtubeAppXml(), "application/xml; charset=utf-8")
            method == "POST" && path == "/ytcr/apps/YouTube" -> {
                val params = parseQuery(body)
                params["pairingCode"]?.takeIf { it.isNotBlank() }?.let { youtubeLounge.registerPairingCode(it) }
                youtubeLounge.ensureStarted()
                val location = "http://$ip:$httpPort/ytcr/apps/YouTube/ncast-youtube"
                writeRaw(out, "HTTP/1.1 201 Created\r\nLOCATION: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            }
            else -> writeRaw(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
    }

    private fun soapEnvelope(inner: String): String = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>$inner</s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun descriptionXml(ip: String): String {
        val name = getString(R.string.ncast_receiver_name)
        return """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>http://$ip:$httpPort/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>${xmlEscape(name)}</friendlyName>
            <manufacturer>NControl</manufacturer>
            <manufacturerURL>https://local.receiver/</manufacturerURL>
            <modelName>NCast Receiver</modelName>
            <modelDescription>Android DLNA/UPnP MediaRenderer + receptor de controle</modelDescription>
            <modelNumber>0.5-s9-dlna</modelNumber>
            <serialNumber>${Build.MODEL}</serialNumber>
            <UDN>$uuid</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avtransport.xml</SCPDURL><controlURL>/control/avtransport</controlURL><eventSubURL>/event/avtransport</eventSubURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/connectionmanager.xml</SCPDURL><controlURL>/control/connectionmanager</controlURL><eventSubURL>/event/connectionmanager</eventSubURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/renderingcontrol.xml</SCPDURL><controlURL>/control/renderingcontrol</controlURL><eventSubURL>/event/renderingcontrol</eventSubURL></service>
            </serviceList>
          </device>
        </root>
        """.trimIndent()
    }

    private fun avTransportScpd(): String {
        fun action(name: String, spec: String): String {
            val args = spec.split(',').filter { it.isNotBlank() }.joinToString("") { part ->
                val bits = part.split(':')
                "<argument><name>${bits[0]}</name><direction>${bits[1]}</direction><relatedStateVariable>${bits[2]}</relatedStateVariable></argument>"
            }
            return "<action><name>$name</name><argumentList>$args</argumentList></action>"
        }
        return """
        <?xml version="1.0"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            ${action("SetAVTransportURI", "InstanceID:in:A_ARG_TYPE_InstanceID,CurrentURI:in:AVTransportURI,CurrentURIMetaData:in:AVTransportURIMetaData")}
            ${action("SetNextAVTransportURI", "InstanceID:in:A_ARG_TYPE_InstanceID,NextURI:in:AVTransportURI,NextURIMetaData:in:AVTransportURIMetaData")}
            ${action("Play", "InstanceID:in:A_ARG_TYPE_InstanceID,Speed:in:TransportPlaySpeed")}
            ${action("Pause", "InstanceID:in:A_ARG_TYPE_InstanceID")}
            ${action("Stop", "InstanceID:in:A_ARG_TYPE_InstanceID")}
            ${action("Seek", "InstanceID:in:A_ARG_TYPE_InstanceID,Unit:in:A_ARG_TYPE_SeekMode,Target:in:A_ARG_TYPE_SeekTarget")}
            ${action("GetTransportInfo", "InstanceID:in:A_ARG_TYPE_InstanceID,CurrentTransportState:out:TransportState,CurrentTransportStatus:out:TransportStatus,CurrentSpeed:out:TransportPlaySpeed")}
            ${action("GetMediaInfo", "InstanceID:in:A_ARG_TYPE_InstanceID,NrTracks:out:NumberOfTracks,MediaDuration:out:CurrentMediaDuration,CurrentURI:out:AVTransportURI,CurrentURIMetaData:out:AVTransportURIMetaData,NextURI:out:AVTransportURI,NextURIMetaData:out:AVTransportURIMetaData,PlayMedium:out:PlaybackStorageMedium,RecordMedium:out:RecordStorageMedium,WriteStatus:out:RecordMediumWriteStatus")}
            ${action("GetPositionInfo", "InstanceID:in:A_ARG_TYPE_InstanceID,Track:out:CurrentTrack,TrackDuration:out:CurrentTrackDuration,TrackMetaData:out:CurrentTrackMetaData,TrackURI:out:CurrentTrackURI,RelTime:out:RelativeTimePosition,AbsTime:out:AbsoluteTimePosition,RelCount:out:RelativeCounterPosition,AbsCount:out:AbsoluteCounterPosition")}
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue></allowedValueList></stateVariable>
            <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
        """.trimIndent()
    }

    private fun dialDescriptionXml(ip: String): String {
        val name = getString(R.string.ncast_receiver_name)
        return """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>http://$ip:$httpPort/ytcr</URLBase>
          <device>
            <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
            <friendlyName>${xmlEscape(name)}</friendlyName>
            <manufacturer>NCast</manufacturer>
            <modelName>Receiver</modelName>
            <UDN>$dialUuid</UDN>
            <serviceList>
              <service>
                <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
                <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
                <controlURL>/ytcr/ssdp/notfound</controlURL>
                <eventSubURL>/ytcr/ssdp/notfound</eventSubURL>
                <SCPDURL>/ytcr/ssdp/notfound</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
        """.trimIndent()
    }

    private fun youtubeAppXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
  <name>YouTube</name>
  <options allowStop="false"/>
  <state>running</state>
  <link rel="run" href="ncast-youtube" />
</service>"""

    private fun connectionManagerScpd(): String = "<?xml version=\"1.0\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>\n  <action><name>GetProtocolInfo</name><argumentList><argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument><argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument></argumentList></action>\n  <action><name>GetCurrentConnectionIDs</name><argumentList><argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument></argumentList></action>\n  <action><name>GetCurrentConnectionInfo</name><argumentList><argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument><argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument><argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument><argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument><argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument><argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument><argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument><argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument></argumentList></action>\n</actionList><serviceStateTable>\n  <stateVariable sendEvents=\"no\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"yes\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType></stateVariable>\n</serviceStateTable></scpd>"

    private fun renderingControlScpd(): String = "<?xml version=\"1.0\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>\n  <action><name>GetVolume</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument></argumentList></action>\n  <action><name>SetVolume</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument></argumentList></action>\n  <action><name>GetMute</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument></argumentList></action>\n</actionList><serviceStateTable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable><stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable><stateVariable sendEvents=\"yes\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable><stateVariable sendEvents=\"yes\"><name>Mute</name><dataType>boolean</dataType></stateVariable></serviceStateTable></scpd>"

    private fun rendererSearchTargets(): List<String> = listOf(
        "upnp:rootdevice", uuid,
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
    )

    private fun dialSearchTargets(): List<String> = listOf(
        dialUuid,
        "urn:dial-multiscreen-org:device:dial:1",
        "urn:dial-multiscreen-org:service:dial:1",
    )

    private fun ssdpResponse(ip: String, st: String, dial: Boolean): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=1800\r\n")
        append("DATE: ${Date()}\r\n")
        append("EXT:\r\n")
        append("LOCATION: http://$ip:$httpPort/${if (dial) "ytcr/ssdp/device-desc.xml" else "description.xml"}\r\n")
        if (dial) append("Application-URL: http://$ip:$httpPort/ytcr/apps\r\n")
        append("CONFIGID.UPNP.ORG: 7337\r\n")
        append("BOOTID.UPNP.ORG: 7337\r\n")
        append("SERVER: Android/${Build.VERSION.RELEASE} UPnP/1.1 NCast/0.6\r\n")
        append("ST: $st\r\n")
        append("USN: ${usnFor(st, dial)}\r\n")
        append("\r\n")
    }

    private fun ssdpNotify(ip: String, nt: String, dial: Boolean): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("CACHE-CONTROL: max-age=1800\r\n")
        append("LOCATION: http://$ip:$httpPort/${if (dial) "ytcr/ssdp/device-desc.xml" else "description.xml"}\r\n")
        if (dial) append("Application-URL: http://$ip:$httpPort/ytcr/apps\r\n")
        append("NT: $nt\r\n")
        append("NTS: ssdp:alive\r\n")
        append("SERVER: Android/${Build.VERSION.RELEASE} UPnP/1.1 NCast/0.6\r\n")
        append("USN: ${usnFor(nt, dial)}\r\n")
        append("\r\n")
    }

    private fun usnFor(target: String, dial: Boolean): String = when (target) {
        uuid -> uuid
        dialUuid -> dialUuid
        else -> "${if (dial) dialUuid else uuid}::$target"
    }

    private fun statusText(): String = """
        NCast Receiver OK
        Nome: ${getString(R.string.ncast_receiver_name)}
        IP: ${localIp()}
        Porta: $httpPort
        Estado: $transportState
        URL atual: $currentUri
    """.trimIndent()

    private fun readHttpRequest(sock: Socket): HttpRequest? {
        val input = sock.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (headerBytes.size() <= 32000) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            if (prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10) {
                val headerText = headerBytes.toString("UTF-8")
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull() ?: ""
                val first = requestLine.split(" ")
                if (first.size < 2) return null
                val headers = LinkedHashMap<String, String>()
                for (line in lines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase(Locale.US)
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                    }
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (len > 0) {
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(buf, read, len - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""
                val rawPath = first[1]
                val query = rawPath.substringAfter('?', "")
                return HttpRequest(first[0], rawPath, headers, body, parseQuery(query))
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return null
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=', "")
            val v = pair.substringAfter('=', "")
            if (k.isBlank()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()
    }

    private fun headerValue(raw: String, name: String): String {
        val line = raw.lineSequence().firstOrNull {
            it.substringBefore(':').trim().equals(name, ignoreCase = true)
        } ?: return ""
        return line.substringAfter(':', "").trim()
    }

    private fun writeHttp(out: OutputStream, body: String, type: String, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\n")
            for ((key, value) in extraHeaders) append("$key: $value\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        writeRaw(out, headers)
        out.write(bytes)
        out.flush()
    }

    private fun writeRaw(out: OutputStream, header: String) {
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun localIp(): String {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                return listOf(ip and 255, (ip shr 8) and 255, (ip shr 16) and 255, (ip shr 24) and 255).joinToString(".")
            }
        } catch (e: Exception) {
            // cai pro fallback via NetworkInterface
        }
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (ni.isUp && !ni.isLoopback) {
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        }
        return "0.0.0.0"
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("cast_receiver", "NCast Receiver", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "cast_receiver")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("NCast Receiver")
            .setContentText("DLNA ativo como ${getString(R.string.ncast_receiver_name)}")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        if (::youtubeLounge.isInitialized) youtubeLounge.stop()

        val ip = localIp()
        for (nt in rendererSearchTargets()) {
            try {
                val msg = ssdpNotify(ip, nt, false).replace("ssdp:alive", "ssdp:byebye")
                val data = msg.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { it.send(DatagramPacket(data, data.size, InetSocketAddress(SSDP_ADDRESS, SSDP_PORT))) }
            } catch (e: Exception) {
                // best-effort, segue pros próximos targets
            }
        }
        for (nt in dialSearchTargets()) {
            try {
                val msg = ssdpNotify(ip, nt, true).replace("ssdp:alive", "ssdp:byebye")
                val data = msg.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { it.send(DatagramPacket(data, data.size, InetSocketAddress(SSDP_ADDRESS, SSDP_PORT))) }
            } catch (e: Exception) {
                // best-effort
            }
        }
        try { httpServer?.close() } catch (e: Exception) { /* já fechado */ }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
