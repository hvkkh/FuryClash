package com.furyclash.game.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LAN transport: the host device runs an embedded TCP room server plus a UDP
 * discovery beacon so guests on the same network can find it without typing
 * an IP. Guests may also join manually by IP:port.
 */
class LanHostServer(val port: Int = DEFAULT_PORT) {
    interface Listener {
        fun onGuest(link: Link)
        fun onGuestClosed(link: Link)
    }

    var listener: Listener? = null
    private var server: ServerSocket? = null
    private var beacon: DatagramSocket? = null
    @Volatile var running = false
        private set
    var guestLink: Link? = null
        private set

    fun start(hostName: String, roomCode: String) {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        Thread({
            while (running) {
                val sock: Socket = try { s.accept() } catch (_: IOException) { break }
                sock.tcpNoDelay = true
                val old = guestLink
                if (old != null && old.alive) {
                    // room full: reject politely
                    try {
                        val fs = TcpFrameSocket(sock)
                        fs.send(Proto.encode(Proto.ERROR) { it.writeUTF("Room is full (2 players max).") })
                        fs.close()
                    } catch (_: IOException) {}
                    continue
                }
                val link = Link(TcpFrameSocket(sock))
                guestLink = link
                link.start()
                listener?.onGuest(link)
                Thread({
                    // watch for socket death so the session can start reconnect grace
                    while (running && link.alive) Thread.sleep(100)
                    if (guestLink === link) listener?.onGuestClosed(link)
                }, "fury-lan-watch").apply { isDaemon = true }.start()
            }
        }, "fury-lan-accept").apply { isDaemon = true }.start()

        // UDP discovery beacon
        try {
            val b = DatagramSocket(null)
            b.reuseAddress = true
            b.bind(InetSocketAddress(port + 1))
            b.broadcast = true
            beacon = b
            Thread({
                val buf = ByteArray(256)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { b.receive(pkt) } catch (_: IOException) { break }
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (msg == DISCOVER_MAGIC) {
                        val reply = "$ROOM_MAGIC|$roomCode|$hostName|$port".toByteArray(Charsets.UTF_8)
                        try { b.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port)) } catch (_: IOException) {}
                    }
                }
            }, "fury-lan-beacon").apply { isDaemon = true }.start()
        } catch (_: IOException) { /* discovery optional; manual IP still works */ }
    }

    fun stop() {
        running = false
        guestLink?.close()
        guestLink = null
        try { server?.close() } catch (_: IOException) {}
        beacon?.close()
    }

    companion object {
        const val DEFAULT_PORT = 7777
        const val DISCOVER_MAGIC = "FURY_DISCOVER_V1"
        const val ROOM_MAGIC = "FURY_ROOM_V1"
    }
}

data class LanRoomInfo(val address: String, val port: Int, val code: String, val name: String)

/** Guest-side UDP discovery scan (background thread; results queued). */
object LanDiscovery {
    val found = ConcurrentLinkedQueue<LanRoomInfo>()

    fun scan(timeoutMs: Int = 1500) {
        found.clear()
        Thread({
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = timeoutMs
                    val msg = LanHostServer.DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
                    val targets = listOf(InetAddress.getByName("255.255.255.255"))
                    for (t in targets) {
                        try { sock.send(DatagramPacket(msg, msg.size, t, LanHostServer.DEFAULT_PORT + 1)) } catch (_: IOException) {}
                    }
                    val buf = ByteArray(512)
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try { sock.receive(pkt) } catch (_: IOException) { break }
                        val parts = String(pkt.data, 0, pkt.length, Charsets.UTF_8).split("|")
                        if (parts.size == 4 && parts[0] == LanHostServer.ROOM_MAGIC) {
                            val info = LanRoomInfo(pkt.address.hostAddress ?: continue, parts[3].toInt(), parts[1], parts[2])
                            if (found.none { it.address == info.address && it.port == info.port }) found.add(info)
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }, "fury-lan-scan").apply { isDaemon = true }.start()
    }
}
