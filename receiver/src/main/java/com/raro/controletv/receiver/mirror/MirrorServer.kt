package com.raro.controletv.receiver.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

data class MirrorHandshake(val width: Int, val height: Int, val orientation: String, val fps: Int)

/**
 * Recebe um H.264 Annex-B cru via TCP (protocolo próprio, não Miracast/Cast) e decodifica
 * direto numa Surface. Sem MPEG-TS/HTTP no meio - existem só pra baixar latência: o objetivo
 * aqui é espelho de tela ao vivo, não um arquivo/VOD.
 *
 * Handshake: 1 linha ASCII terminada em '\n' antes do vídeo: "LARGURAxALTURA:ORIENTACAO:FPS".
 * Ex.: "1920x1080:landscape:30". Depois disso é stream H.264 puro (start codes 00 00 01 / 00 00 00 01).
 */
class MirrorServer(
    private val port: Int,
    private val onHandshake: (MirrorHandshake) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val NAL_BUFFER_INITIAL = 512 * 1024
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var decoder: MediaCodec? = null

    /** Roda em loop bloqueante - chamar numa thread/coroutine IO dedicada. */
    fun start(surfaceProvider: () -> Surface?) {
        running.set(true)
        try {
            serverSocket = ServerSocket(port)
            onLog("Aguardando conexão na porta $port")
            while (running.get()) {
                val socket = serverSocket?.accept() ?: break
                onLog("Conectado: ${socket.inetAddress?.hostAddress}")
                try {
                    handleConnection(socket, surfaceProvider)
                } catch (e: Exception) {
                    onLog("Sessão encerrada: ${e.message}")
                } finally {
                    releaseDecoder()
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (running.get()) onLog("Erro no servidor de espelhamento: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        releaseDecoder()
    }

    private fun handleConnection(socket: Socket, surfaceProvider: () -> Surface?) {
        val input = socket.getInputStream()
        val handshake = readHandshake(input) ?: run {
            onLog("Handshake inválido, encerrando conexão")
            return
        }
        onLog("Handshake: ${handshake.width}x${handshake.height} ${handshake.orientation} ${handshake.fps}fps")
        onHandshake(handshake)

        val surface = surfaceProvider() ?: run {
            onLog("Sem Surface disponível ainda")
            return
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, handshake.width, handshake.height)
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(format, surface, null, 0)
        dec.start()
        decoder = dec

        pumpNalUnits(input, dec)
    }

    private fun readHandshake(input: InputStream): MirrorHandshake? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            line.append(b.toChar())
            if (line.length > 256) return null
        }
        return parseHandshake(line.toString().trim())
    }

    private fun parseHandshake(text: String): MirrorHandshake? {
        // formato: "1920x1080:landscape:30"
        val parts = text.split(":")
        if (parts.size < 2) return null
        val res = parts[0].split("x")
        if (res.size != 2) return null
        val width = res[0].toIntOrNull() ?: return null
        val height = res[1].toIntOrNull() ?: return null
        val orientation = parts.getOrNull(1) ?: "landscape"
        val fps = parts.getOrNull(2)?.toIntOrNull() ?: 30
        return MirrorHandshake(width, height, orientation, fps)
    }

    /**
     * Separa o stream Annex-B em NAL units pelos start codes e alimenta o decoder um a um.
     * Renderiza IMEDIATAMENTE (releaseOutputBuffer com render=true assim que sai do decoder) -
     * é espelho ao vivo, não faz sentido esperar por timing de PTS original.
     */
    private fun pumpNalUnits(input: InputStream, dec: MediaCodec) {
        val buffer = ByteArray(NAL_BUFFER_INITIAL)
        var length = 0
        val readChunk = ByteArray(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainOutput() {
            var again = true
            while (again) {
                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                if (outIndex >= 0) {
                    dec.releaseOutputBuffer(outIndex, true)
                } else {
                    again = false
                }
            }
        }

        fun feedNal(start: Int, end: Int) {
            if (end <= start) return
            val inIndex = dec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = dec.getInputBuffer(inIndex) ?: return
                inBuf.clear()
                inBuf.put(buffer, start, end - start)
                dec.queueInputBuffer(inIndex, 0, end - start, System.nanoTime() / 1000, 0)
            }
            drainOutput()
        }

        var lastStart = -1
        while (running.get()) {
            val n = input.read(readChunk)
            if (n < 0) break
            if (length + n > buffer.size) {
                // buffer cheio sem achar start code seguinte - descarta pra não crescer sem limite
                length = 0
                lastStart = -1
                continue
            }
            System.arraycopy(readChunk, 0, buffer, length, n)
            length += n

            var i = 0
            while (i + 3 <= length) {
                val isStart3 = buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()
                if (isStart3) {
                    if (lastStart >= 0) feedNal(lastStart, i)
                    lastStart = i
                    i += 3
                } else {
                    i += 1
                }
            }
            // compacta o que já foi processado, mantém a partir do último NAL em aberto
            if (lastStart > 0) {
                val remaining = length - lastStart
                System.arraycopy(buffer, lastStart, buffer, 0, remaining)
                length = remaining
                lastStart = 0
            }
        }
        drainOutput()
    }

    private fun releaseDecoder() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }
}
