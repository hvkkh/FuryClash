package com.furyclash.game.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * Byte-stream framing abstraction. LAN uses length-prefixed TCP frames;
 * online uses RFC6455 WebSocket binary frames (so the relay can live on any
 * free web host that supports WebSocket upgrades, including wss:// on 443).
 */
interface FrameSocket {
    val open: Boolean
    fun send(blob: ByteArray)
    /** Blocking read of one frame; null on clean close. Throws IOException on error. */
    fun receive(): ByteArray?
    fun close()
}

class TcpFrameSocket(private val socket: Socket) : FrameSocket {
    private val inp = DataInputStream(socket.getInputStream().buffered())
    private val out = DataOutputStream(socket.getOutputStream().buffered())
    override val open get() = !socket.isClosed && socket.isConnected

    override fun send(blob: ByteArray) {
        synchronized(out) {
            out.writeInt(blob.size)
            out.write(blob)
            out.flush()
        }
    }

    override fun receive(): ByteArray? {
        val len = try { inp.readInt() } catch (e: java.io.EOFException) { return null }
        if (len <= 0 || len > 1 shl 20) throw IOException("bad frame len $len")
        val buf = ByteArray(len)
        inp.readFully(buf)
        return buf
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    companion object {
        fun connect(host: String, port: Int, timeoutMs: Int): TcpFrameSocket {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = 0
            return TcpFrameSocket(s)
        }
    }
}

/** Minimal RFC6455 client (binary frames, masked writes, ping/pong, close). */
class WsFrameSocket private constructor(
    private val socket: Socket,
    private val inp: InputStream,
    private val out: OutputStream
) : FrameSocket {
    private val rng = SecureRandom()
    override val open get() = !socket.isClosed

    override fun send(blob: ByteArray) {
        synchronized(out) {
            writeFrame(0x2, blob)
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        val len = payload.size
        when {
            len < 126 -> out.write(0x80 or len)
            len < 65536 -> { out.write(0x80 or 126); out.write(len shr 8); out.write(len and 0xFF) }
            else -> {
                out.write(0x80 or 127)
                for (i in 7 downTo 0) out.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        out.flush()
    }

    override fun receive(): ByteArray? {
        while (true) {
            val b0 = inp.read()
            if (b0 < 0) return null
            val b1 = inp.read()
            if (b1 < 0) return null
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((inp.read() shl 8) or inp.read()).toLong()
            } else if (len == 127L) {
                len = 0
                for (i in 0 until 8) len = (len shl 8) or inp.read().toLong()
            }
            if (len < 0 || len > 1 shl 20) throw IOException("ws frame too large")
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(len.toInt())
            readFully(payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x1, 0x2 -> return payload
                0x8 -> { try { writeFrame(0x8, ByteArray(0)) } catch (_: IOException) {}; return null }
                0x9 -> { synchronized(out) { writeFrame(0xA, payload) } }
                0xA -> {}
                else -> {}
            }
        }
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("ws eof")
            off += n
        }
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    companion object {
        /** url: ws://host:port/path or wss://host[:port]/path */
        fun connect(url: String, timeoutMs: Int): WsFrameSocket {
            val secure = url.startsWith("wss://")
            val rest = url.substring(if (secure) 6 else 5)
            val slash = rest.indexOf('/')
            val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else "/"
            val colon = hostPort.lastIndexOf(':')
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) hostPort.substring(colon + 1).toInt() else if (secure) 443 else 80
            val socket = if (secure) {
                SSLSocketFactory.getDefault().createSocket(host, port)
            } else {
                Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
            }
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val out = socket.getOutputStream()
            val req = "GET $path HTTP/1.1\r\nHost: $hostPort\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()
            val inp = socket.getInputStream()
            // read status line + headers until CRLFCRLF
            val sb = StringBuilder()
            var state = 0
            while (state < 4) {
                val c = inp.read()
                if (c < 0) throw IOException("ws handshake eof")
                sb.append(c.toChar())
                state = when {
                    c == '\r'.code && (state == 0 || state == 2) -> state + 1
                    c == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
            }
            val head = sb.toString()
            if (!head.startsWith("HTTP/1.1 101") && !head.startsWith("HTTP/1.0 101"))
                throw IOException("ws handshake rejected: ${head.lineSequence().first()}")
            socket.soTimeout = 0
            return WsFrameSocket(socket, inp, out)
        }
    }
}
