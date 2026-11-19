package com.furyclash.game.net

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Async packet link: reader + writer threads, heartbeat/timeout, RTT estimate.
 * Incoming packets are queued and drained on the game tick thread so all
 * session/battle mutation stays single-threaded (under synchronized(game)).
 */
class Link(private val socket: FrameSocket) {
    val inbox = ConcurrentLinkedQueue<ByteArray>()
    private val outbox = ConcurrentLinkedQueue<ByteArray>()

    @Volatile var alive = true
        private set
    @Volatile var rttMs = -1
    @Volatile var lastRecvMs = System.currentTimeMillis()
        private set

    private var lastPingMs = 0L
    private var pingSentAt = 0L

    fun start() {
        Thread({
            try {
                while (alive) {
                    val blob = socket.receive() ?: break
                    lastRecvMs = System.currentTimeMillis()
                    when (Proto.typeOf(blob)) {
                        Proto.PING -> {
                            val echo = Proto.reader(blob).readLong()
                            send(Proto.encode(Proto.PONG) { it.writeLong(echo) })
                        }
                        Proto.PONG -> {
                            val d = Proto.reader(blob)
                            val echo = d.readLong()
                            rttMs = (System.currentTimeMillis() - echo).toInt().coerceAtLeast(0)
                        }
                        else -> inbox.add(blob)
                    }
                }
            } catch (_: IOException) {
            } catch (_: Exception) {
            } finally {
                alive = false
                socket.close()
            }
        }, "fury-link-read").apply { isDaemon = true }.start()
        Thread({
            try {
                while (alive) {
                    val blob = outbox.poll()
                    if (blob == null) { Thread.sleep(2); continue }
                    socket.send(blob)
                }
            } catch (_: IOException) {
            } catch (_: Exception) {
            } finally {
                alive = false
                socket.close()
            }
        }, "fury-link-write").apply { isDaemon = true }.start()
    }

    fun send(blob: ByteArray) {
        if (alive) outbox.add(blob)
    }

    /** Called from the tick thread: heartbeat + dead-peer detection. */
    fun tick(nowMs: Long) {
        if (!alive) return
        if (nowMs - lastPingMs > 2000) {
            lastPingMs = nowMs
            pingSentAt = nowMs
            send(Proto.encode(Proto.PING) { it.writeLong(nowMs) })
        }
        if (nowMs - lastRecvMs > 9000) {
            alive = false
            socket.close()
        }
    }

    fun close() {
        alive = false
        socket.close()
    }
}
