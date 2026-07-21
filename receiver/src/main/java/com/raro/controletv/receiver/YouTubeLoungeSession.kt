package com.raro.controletv.receiver

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cliente do protocolo Lounge (oficial) do YouTube — é assim que o app do YouTube "vê" esse
 * receiver como uma tela pra cast (DIAL). Quando o usuário manda um vídeo, chama [onVideo] com a
 * URL do watch, e quem decide o que fazer com isso é o NCastDlnaService (abre o app do YouTube).
 */
class YouTubeLoungeSession(
    context: Context,
    private val screenName: String,
    private val onVideo: (String) -> Unit,
) {
    companion object {
        private val MESSAGE_REGEX = Regex("\\[(\\d+),\\[\"(.+?)\"(?:,(.*?))?]]")
        private const val SCREEN_APP = "ytcr"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) NCast Receiver YouTube DIAL"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("youtube_lounge", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    var screenId: String = prefs.getString("screen_id", "") ?: ""
        private set
    @Volatile var lastPairingCode: String = ""
        private set

    @Volatile private var running = false
    @Volatile private var started = false
    private var loungeToken = ""
    private var sid = ""
    private var gsessionId = ""
    private var rid = Random.nextInt(41000, 49999)
    private var aid = 3

    fun ensureStarted() {
        if (started) return
        started = true
        running = true
        Thread({
            try {
                begin()
                rpcLoop()
            } catch (e: Exception) {
                started = false
            }
        }, "youtube-lounge-start").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        started = false
    }

    fun registerPairingCode(pairingCode: String) {
        lastPairingCode = pairingCode
        ensureStarted()
        Thread({
            try {
                waitUntilReady()
                val body = form(
                    "access_type" to "permanent",
                    "app" to SCREEN_APP,
                    "pairing_code" to pairingCode,
                    "screen_id" to screenId,
                    "screen_name" to screenName,
                    "device_id" to deviceId,
                )
                httpPost("https://www.youtube.com/api/lounge/pairing/register_pairing_code", body)
            } catch (e: Exception) {
                // pareamento falhou, o app do YouTube tenta de novo
            }
        }, "youtube-lounge-pair").apply { isDaemon = true; start() }
    }

    private fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + 15000
        while (running && System.currentTimeMillis() < deadline) {
            if (screenId.isNotBlank() && loungeToken.isNotBlank() && sid.isNotBlank() && gsessionId.isNotBlank()) return
            Thread.sleep(250)
        }
    }

    private fun begin() {
        if (screenId.isBlank()) {
            screenId = httpGet("https://www.youtube.com/api/lounge/pairing/generate_screen_id").trim()
            prefs.edit().putString("screen_id", screenId).apply()
        }
        loungeToken = fetchLoungeToken(screenId)
        val initBody = httpPost("https://www.youtube.com/api/lounge/bc/bind?${query("init")}", form("count" to "0"))
        handleMessages(initBody)
    }

    private fun fetchLoungeToken(id: String): String {
        val json = JSONObject(httpPost("https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch", form("screen_ids" to id)))
        val screens = json.optJSONArray("screens") ?: JSONArray()
        if (screens.length() == 0) throw IllegalStateException("No lounge token")
        return screens.getJSONObject(0).getString("loungeToken")
    }

    private fun rpcLoop() {
        while (running) {
            try {
                val url = "https://www.youtube.com/api/lounge/bc/bind?${query("rpc")}"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 70000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        if (!running) return@forEach
                        handleMessages(line)
                    }
                }
            } catch (e: Exception) {
                Thread.sleep(1500)
            }
        }
    }

    private fun handleMessages(data: String) {
        if (data.isBlank()) return
        for (match in MESSAGE_REGEX.findAll(data.replace("\n", ""))) {
            aid = maxOf(aid, match.groupValues[1].toIntOrNull() ?: 0)
            val name = match.groupValues[2]
            val rawPayload = match.groups[3]?.value

            when (name) {
                "playVideo" -> {
                    val videoId = parsePayloadObject(rawPayload)?.optString("videoId") ?: ""
                    if (videoId.isNotBlank()) onVideo("https://www.youtube.com/watch?v=$videoId")
                }
                "updatePlaylist", "setPlaylist" -> {
                    val videoIds = parsePayloadObject(rawPayload)?.optString("videoIds") ?: ""
                    val videoId = videoIds.split(',').firstOrNull { it.isNotBlank() } ?: ""
                    if (videoId.isNotBlank()) onVideo("https://www.youtube.com/watch?v=$videoId")
                }
                "S" -> gsessionId = parsePayloadValue(rawPayload) ?: ""
                "c" -> sid = parseArrayPayload(rawPayload)?.optString(0) ?: ""
            }
        }
    }

    private fun parsePayloadObject(raw: String?): JSONObject? = try {
        parseArrayPayload(raw)?.opt(0) as? JSONObject
    } catch (e: Exception) {
        null
    }

    private fun parsePayloadValue(raw: String?): String? = try {
        parseArrayPayload(raw)?.optString(0)
    } catch (e: Exception) {
        null
    }

    private fun parseArrayPayload(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        return JSONArray("[$raw]")
    }

    private fun query(type: String): String {
        val uuid = UUID.randomUUID().toString()
        val common = linkedMapOf(
            "device" to "LOUNGE_SCREEN",
            "id" to deviceId,
            "obfuscatedGaiaId" to "",
            "name" to screenName,
            "app" to SCREEN_APP,
            "theme" to "cl",
            "capabilities" to "dsp,mic,dpa,ntb",
            "cst" to "m",
            "mdxVersion" to "2",
            "loungeIdToken" to loungeToken,
            "VER" to "8",
            "v" to "2",
            "zx" to uuid.replace("-", "").take(12),
            "t" to "1",
        )
        if (type == "init") {
            val deviceInfo = JSONObject(
                mapOf(
                    "brand" to "NCast", "model" to "Receiver", "year" to 0,
                    "os" to "Android", "osVersion" to Build.VERSION.RELEASE, "chipset" to "",
                    "clientName" to "TVHTML5", "dialAdditionalDataSupportLevel" to "unsupported",
                    "mdxDialServerType" to "MDX_DIAL_SERVER_TYPE_UNKNOWN",
                )
            )
            common["deviceInfo"] = deviceInfo.toString()
            common["RID"] = (rid++).toString()
            common["CVER"] = "1"
        } else {
            common["RID"] = "rpc"
            common["SID"] = sid
            common["CI"] = "0"
            common["AID"] = aid.toString()
            common["gsessionid"] = gsessionId
            common["TYPE"] = "xmlhttp"
        }
        return form(*common.map { it.key to it.value }.toTypedArray())
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun httpPost(url: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Content-Length", bytes.size.toString())
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
