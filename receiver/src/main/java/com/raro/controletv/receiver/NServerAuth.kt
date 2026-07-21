package com.raro.controletv.receiver

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Ponte com a licença Premium do Nserver — o NCastDlnaService só liga se [isPremium] for true.
 * `discover`/`ping` foram decompilados de forma limpa (baixo risco). `validate`/`login` NÃO
 * decompilaram (bytecode otimizado demais pro jadx reconstruir o controle de fluxo) — a versão
 * abaixo é uma reconstrução best-effort a partir do padrão de endpoint já confirmado em `ping`
 * (`/api/ncast/...`). Antes de confiar na tela de login, VALIDAR contra o Nserver real — não
 * mexi na API do Nserver em si, só estou tentando reconstruir como o cliente já conversava com ela.
 */
object NServerAuth {
    private const val PREF = "nserver_auth"
    private const val KEY_SERVER = "server_url"
    private const val KEY_USER = "username"
    private const val KEY_PREMIUM = "premium_ok"
    private const val KEY_TOKEN = "premium_token"
    private const val KEY_LAST = "last_check"

    fun serverUrl(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SERVER, "") ?: ""

    fun username(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_USER, "") ?: ""

    private fun token(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun isPremium(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_PREMIUM, false)

    fun logout(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PREMIUM, false)
            .remove(KEY_USER)
            .remove(KEY_TOKEN)
            .apply()
    }

    fun discover(context: Context): Pair<Boolean, String> {
        val saved = serverUrl(context)
        if (saved.isNotBlank() && ping(saved)) return true to saved

        val local = localIp(context) ?: return false to "Não consegui identificar o IP local deste aparelho."
        val prefix = local.substringBeforeLast('.', "")
        if (prefix.isBlank()) return false to "Rede local não identificada."

        val executor = Executors.newFixedThreadPool(32)
        try {
            val tasks = (1..254).map { host ->
                java.util.concurrent.Callable<String?> {
                    val candidate = "http://$prefix.$host:8791"
                    if (ping(candidate)) candidate else null
                }
            }
            val futures = executor.invokeAll(tasks, 9, TimeUnit.SECONDS)
            val found = futures.asSequence().mapNotNull { runCatching { it.get() }.getOrNull() }.firstOrNull()
            return if (found != null) {
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SERVER, found).apply()
                true to found
            } else {
                false to "Não achei o Nserver nesta rede. Confirme que celular/receiver estão no mesmo Wi-Fi e Nserver está ligado."
            }
        } catch (e: Exception) {
            return false to "Falha ao procurar Nserver: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            executor.shutdownNow()
        }
    }

    /** Confere junto ao Nserver se a licença salva (token+usuário) ainda é premium; atualiza os prefs locais. */
    fun validate(context: Context): Pair<Boolean, String> {
        val server = normalizeServer(serverUrl(context))
        val user = username(context)
        val tok = token(context)
        if (server.isBlank() || tok.isBlank()) return false to "Sem sessão salva."
        return try {
            val conn = URL("$server/api/ncast/validate").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form("username" to user, "token" to tok)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                val premium = json.optBoolean("premium", false)
                prefs.edit().putBoolean(KEY_PREMIUM, premium).putLong(KEY_LAST, System.currentTimeMillis()).apply()
                premium to (json.optString("message").ifBlank { if (premium) "Premium ativo." else "Sem premium ativo." })
            } else {
                prefs.edit().putBoolean(KEY_PREMIUM, false).apply()
                false to "Servidor recusou a validação (HTTP $code)."
            }
        } catch (e: Exception) {
            false to "Falha ao validar com o Nserver: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Login no Nserver: salva servidor+usuário+token+premium nos prefs em caso de sucesso. */
    fun login(context: Context, rawServer: String, username: String, password: String): Pair<Boolean, String> {
        val server = normalizeServer(rawServer)
        if (server.isBlank()) return false to "Endereço do Nserver inválido."
        return try {
            val conn = URL("$server/api/ncast/login").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form("username" to username, "password" to password)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) {
                val json = JSONObject(text)
                val token = json.optString("token")
                val premium = json.optBoolean("premium", false)
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SERVER, server)
                    .putString(KEY_USER, username)
                    .putString(KEY_TOKEN, token)
                    .putBoolean(KEY_PREMIUM, premium)
                    .putLong(KEY_LAST, System.currentTimeMillis())
                    .apply()
                true to (json.optString("message").ifBlank { "Login OK." })
            } else {
                false to "Login recusado pelo servidor (HTTP $code)."
            }
        } catch (e: Exception) {
            false to "Falha ao logar no Nserver: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun ping(rawServer: String): Boolean {
        val server = normalizeServer(rawServer)
        if (server.isBlank()) return false
        return try {
            val conn = URL("$server/api/ncast/ping").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 450
            conn.readTimeout = 450
            val code = conn.responseCode
            val text = if (code in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            text.contains("nserver", ignoreCase = true) && text.contains("ncast", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun localIp(context: Context): String? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                return listOf(ip and 255, (ip shr 8) and 255, (ip shr 16) and 255, (ip shr 24) and 255).joinToString(".")
            }
        } catch (e: Exception) {
            // cai pro fallback via NetworkInterface
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isUp && !ni.isLoopback) {
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeServer(value: String): String {
        val s = value.trim().trimEnd('/')
        return if (s.isBlank()) "" else if (!s.contains("://")) "http://$s" else s
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
}
