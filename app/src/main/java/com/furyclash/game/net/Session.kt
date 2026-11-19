package com.furyclash.game.net

import com.furyclash.game.Game
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class NetKind { LAN, ONLINE }

/**
 * One multiplayer session (lobby + match) over either transport.
 * Host-authoritative: the host simulates and broadcasts snapshots; the guest
 * forwards inputs and applies snapshots. All mutation happens on the game
 * tick thread via drained packet queues.
 */
class Session(
    val game: Game,
    val kind: NetKind,
    val isHost: Boolean,
    val myName: String,
    token: String? = null
) {
    val myToken: String = token ?: UUID.randomUUID().toString().replace("-", "").substring(0, 10)

    var link: Link? = null
    var lobby: LobbyHost? = null
    var lanServer: LanHostServer? = null
    var room: RoomState? = null
    var serverUrl: String = ""
    var roomCode: String = ""
    var lanAddress: String = ""
    var lanPort: Int = LanHostServer.DEFAULT_PORT
    var connecting = false
    var statusMsg: String = ""
    var errorMsg: String? = null
    var peerConnected = false
    var inMatch = false
    var lostTimer = -1f
    var myTeam: IntArray? = null
    var guestTeam: IntArray? = null
    var arena = 0
    var roundTime = 99f
    var netMenuOpen = false

    private val localInbox = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var pendingLink: Link? = null
    @Volatile private var pendingError: String? = null
    private var snapAcc = 0f
    private var inputAcc = 0f
    private var reconnectAt = 0L
    private var startedConnect = false

    val graceSec = 30f
    val rttMs: Int get() = link?.rttMs ?: -1

    // ---------------- setup ----------------

    fun beginHostLan() {
        val l = LobbyHost(myToken, myName, game.roundTime)
        l.toGuest = { link?.send(it) }
        l.toHost = { localInbox.add(it) }
        lobby = l
        val srv = LanHostServer()
        srv.listener = object : LanHostServer.Listener {
            override fun onGuest(link: Link) {
                this@Session.link = link
            }
            override fun onGuestClosed(link: Link) {
                if (this@Session.link === link) l.detachGuest(permanent = false)
            }
        }
        lanServer = srv
        srv.start(myName, "LAN")
        roundTime = game.roundTime
        room = l.roomState()
        statusMsg = "Hosting on LAN — waiting for a player…"
    }

    fun beginJoinLan(address: String, port: Int) {
        lanAddress = address; lanPort = port
        connecting = true
        statusMsg = "Connecting to $address:$port…"
        Thread({
            try {
                val l = Link(TcpFrameSocket.connect(address, port, 4000))
                l.start()
                l.send(Proto.encode(Proto.HELLO) { it.writeUTF(myToken); it.writeUTF(myName) })
                pendingLink = l
            } catch (e: Exception) {
                pendingError = "Could not connect: ${e.message ?: "unreachable"}"
            }
        }, "fury-lan-connect").apply { isDaemon = true }.start()
    }

    /** Re-attach to an existing room after an app restart (same player token). */
    fun resumeOnline(url: String, code: String) {
        serverUrl = url
        roomCode = code
        startedConnect = true
        statusMsg = "Rejoining room $code…"
        connectOnline(rejoin = true)
    }

    fun beginOnline(host: Boolean, url: String, code: String) {
        serverUrl = url
        roomCode = code
        connecting = true
        startedConnect = true
        statusMsg = if (host) "Creating room…" else "Joining room $code…"
        connectOnline(rejoin = false)
    }

    private fun connectOnline(rejoin: Boolean) {
        connecting = true
        Thread({
            try {
                val l = Link(WsFrameSocket.connect(serverUrl, 6000))
                l.start()
                when {
                    isHost && !rejoin -> l.send(Proto.encode(Proto.CREATE_ROOM) { it.writeUTF(myToken); it.writeUTF(myName) })
                    rejoin -> l.send(Proto.encode(Proto.REJOIN) { it.writeUTF(roomCode); it.writeUTF(myToken) })
                    else -> l.send(Proto.encode(Proto.JOIN_ROOM) { it.writeUTF(roomCode); it.writeUTF(myToken); it.writeUTF(myName) })
                }
                pendingLink = l
            } catch (e: Exception) {
                pendingError = "Relay unreachable: ${e.message ?: "connection failed"}"
            }
        }, "fury-relay-connect").apply { isDaemon = true }.start()
    }

    // ---------------- lobby actions ----------------

    fun setReady(v: Boolean) {
        if (kind == NetKind.LAN && isHost) lobby?.markHostReady(v)
        else link?.send(Proto.encode(Proto.READY) { it.writeBoolean(v) })
    }

    fun setTeam(ids: IntArray) {
        myTeam = ids
        if (kind == NetKind.LAN && isHost) lobby?.assignHostTeam(ids)
        else link?.send(Proto.encode(Proto.TEAM) { for (i in 0..2) it.writeByte(ids[i]) })
    }

    fun pickArena(id: Int) {
        if (!isHost) return
        arena = id
        if (kind == NetKind.LAN) lobby?.chooseArena(id)
        else link?.send(Proto.encode(Proto.ARENA_PICK) { it.writeByte(id) })
    }

    fun canStart(): Boolean {
        if (!isHost || inMatch) return false
        if (kind == NetKind.LAN) return lobby?.canStart() == true
        val r = room ?: return false
        return r.phase == Proto.PH_LOBBY && r.players.size == 2 &&
            r.players.all { it.ready && it.connected && it.team != null }
    }

    fun startMatch() {
        if (!canStart()) return
        val t0 = myTeam ?: return
        val t1 = if (kind == NetKind.LAN) lobby?.guestTeam else guestTeam
        if (t1 == null) return
        val rt = if (kind == NetKind.LAN) game.roundTime else roundTime
        if (kind == NetKind.LAN) {
            lobby?.startMatch() ?: return
        } else {
            link?.send(Proto.encode(Proto.START_MATCH) { o ->
                o.writeByte(arena); o.writeFloat(rt)
                for (i in 0..2) o.writeByte(t0[i])
                for (i in 0..2) o.writeByte(t1[i])
            })
        }
        game.startNetBattle(t0, t1, arena, rt, asClient = false)
        inMatch = true
        peerConnected = true
        lostTimer = -1f
    }

    fun returnToLobby() {
        if (kind == NetKind.LAN && isHost) lobby?.returnToLobby()
        else link?.send(Proto.encode(Proto.LOBBY_RETURN) {})
        doReturnToLobby()
    }

    private fun doReturnToLobby() {
        inMatch = false
        lostTimer = -1f
        netMenuOpen = false
        game.endNetMatchToLobby()
    }

    fun leave() {
        link?.send(Proto.encode(Proto.LEAVE) {})
        link?.close()
        lanServer?.stop()
        inMatch = false
        game.session = null
        game.clearNetResume()
    }

    // ---------------- tick ----------------

    fun tick(dt: Float) {
        val now = System.currentTimeMillis()
        link?.tick(now)

        pendingError?.let {
            errorMsg = it
            pendingError = null
            connecting = false
            startedConnect = false
        }
        pendingLink?.let {
            link = it
            pendingLink = null
            connecting = false
            errorMsg = null
            if (kind == NetKind.LAN && !isHost) statusMsg = "Connected — waiting for lobby…"
            if (inMatch && isHost) {
                // we re-attached mid-match: push authoritative state immediately
                it.send(Proto.encode(Proto.RESUME) {})
                game.battle?.let { b -> it.send(Snapshot.capture(b)) }
            }
        }

        while (true) {
            val blob = localInbox.poll() ?: break
            process(blob)
        }
        val l = link
        if (l != null) {
            while (true) {
                val blob = l.inbox.poll() ?: break
                if (kind == NetKind.LAN && isHost && lobby?.process(blob) == true) continue
                process(blob)
            }
            if (!l.alive && !connecting) onLinkLost(now)
        }

        // reconnect grace / forfeit
        if (inMatch && !peerConnected) {
            if (lostTimer < 0f) lostTimer = 0f
            lostTimer += dt
            if (lostTimer >= graceSec) {
                lostTimer = -1f
                game.battle?.forceEnd(if (isHost) 0 else 1)
                statusMsg = "Opponent did not return — win by forfeit."
            }
            if (now >= reconnectAt && !connecting && roomCode.isNotEmpty()) {
                reconnectAt = now + 2500
                if (kind == NetKind.ONLINE) connectOnline(rejoin = true)
                else if (!isHost) beginJoinLan(lanAddress, lanPort)
            }
        }

        // match traffic
        val b = game.battle
        if (inMatch && b != null && peerConnected) {
            if (isHost) {
                snapAcc += dt
                if (snapAcc >= 1f / 20f) {
                    snapAcc = 0f
                    link?.send(Snapshot.capture(b))
                }
            } else {
                inputAcc += dt
                if (inputAcc >= 1f / 30f) {
                    inputAcc = 0f
                    val inp = game.inputs[0]
                    var mask = 0
                    for (btn in 0..7) if (inp.consume(btn)) mask = mask or (1 shl btn)
                    link?.send(InputCodec.encode(inp.moveX, inp.moveY, inp.blockHeld, mask))
                }
            }
        }
    }

    private fun onLinkLost(now: Long) {
        if (kind == NetKind.LAN && isHost) return // handled by LanHostServer watcher
        link = null
        peerConnected = false
        if (inMatch) {
            lostTimer = 0f
            statusMsg = "Connection lost — reconnecting…"
            reconnectAt = now
        } else if (room != null) {
            statusMsg = "Disconnected from relay — reconnecting…"
            reconnectAt = now
            if (kind == NetKind.ONLINE && !connecting) connectOnline(rejoin = room != null)
        } else {
            errorMsg = "Connection closed."
        }
    }

    // ---------------- packet handling ----------------

    private fun process(blob: ByteArray) {
        val d = Proto.reader(blob)
        when (Proto.typeOf(blob)) {
            Proto.ROOM_STATE -> {
                val r = RoomCodec.decode(blob)
                room = r
                roomCode = r.code
                roundTime = r.roundTime
                if (!isHost) arena = r.arena
                for (p in r.players) {
                    if (p.token == myToken) myTeam = p.team ?: myTeam
                    else guestTeam = p.team ?: guestTeam
                }
                peerConnected = r.players.any { it.token != myToken && it.connected }
                if (r.phase == Proto.PH_MATCH && !inMatch && !isHost) {
                    // reconnected mid-match: rebuild replica, await RESUME snapshot
                    val host = r.players.firstOrNull { it.host }?.team
                    val mine = r.players.firstOrNull { it.token == myToken }?.team
                    if (host != null && mine != null) {
                        game.startNetBattle(host, mine, r.arena, r.roundTime, asClient = true)
                        inMatch = true
                    }
                }
                if (r.phase == Proto.PH_LOBBY && inMatch) doReturnToLobby()
                if (!inMatch) {
                    statusMsg = if (r.players.size < 2) "Waiting for opponent… (${r.players.size}/2)"
                    else if (r.players.all { it.ready }) "All players ready."
                    else "Waiting for ready…"
                }
            }
            Proto.ERROR -> {
                errorMsg = d.readUTF()
                connecting = false
            }
            Proto.START_MATCH -> {
                if (isHost || inMatch) return
                val ar = d.readByte().toInt()
                val rt = d.readFloat()
                val t0 = IntArray(3) { d.readByte().toInt() }
                val t1 = IntArray(3) { d.readByte().toInt() }
                arena = ar
                game.startNetBattle(t0, t1, ar, rt, asClient = true)
                inMatch = true
                peerConnected = true
                lostTimer = -1f
            }
            Proto.SNAPSHOT -> {
                if (!isHost) game.battle?.let { Snapshot.apply(it, blob) }
            }
            Proto.EVT -> if (!isHost) game.applyNetEvent(blob)
            Proto.INPUT -> {
                if (!isHost) return
                val q = InputCodec.decode(blob)
                val inp = game.inputs[1]
                inp.moveX = q.mx; inp.moveY = q.my; inp.blockHeld = q.block
                for (btn in 0..7) if (q.mask and (1 shl btn) != 0) inp.press(btn)
            }
            Proto.TEAM -> if (isHost) guestTeam = IntArray(3) { d.readByte().toInt() }
            Proto.ARENA_PICK -> arena = d.readByte().toInt()
            Proto.PEER_STATUS -> {
                val connected = d.readBoolean()
                peerConnected = connected
                if (connected) {
                    lostTimer = -1f
                    if (inMatch && isHost) {
                        link?.send(Proto.encode(Proto.RESUME) {})
                        game.battle?.let { link?.send(Snapshot.capture(it)) }
                    }
                    if (inMatch) statusMsg = "Opponent reconnected."
                } else if (inMatch) {
                    lostTimer = 0f
                    statusMsg = "Opponent disconnected — waiting…"
                }
            }
            Proto.RESUME -> {
                lostTimer = -1f
                peerConnected = true
                statusMsg = "Reconnected — resuming match."
            }
            Proto.LOBBY_RETURN -> doReturnToLobby()
            Proto.LEAVE -> {
                peerConnected = false
                lostTimer = -1f
                if (inMatch) {
                    game.battle?.forceEnd(if (isHost) 0 else 1)
                    statusMsg = "Opponent left the match."
                }
            }
            else -> {}
        }
    }
}
