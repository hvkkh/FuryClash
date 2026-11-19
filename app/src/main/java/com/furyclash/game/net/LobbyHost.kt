package com.furyclash.game.net

/**
 * Room/lobby authority for LAN sessions (the host device is the server).
 * The online relay (server/relay.js) implements the same semantics so both
 * transports speak one message vocabulary.
 */
class LobbyHost(
    val hostToken: String,
    val hostName: String,
    val roundTime: Float
) {
    var toGuest: (ByteArray) -> Unit = {}
    var toHost: (ByteArray) -> Unit = {}

    var code = "LAN"
    var phase = Proto.PH_LOBBY
    var arena = 0
    var hostReady = false
    var hostTeam: IntArray? = null
    var guest: NetPlayer? = null
        private set
    var guestTeam: IntArray? = null
        private set
    var guestReady = false
        private set
    var guestConnected = false
        private set

    fun roomState(): RoomState {
        val players = ArrayList<NetPlayer>(2)
        players.add(NetPlayer(hostToken, hostName, hostReady, true, true, hostTeam))
        guest?.let { players.add(it.copy(ready = guestReady, connected = guestConnected, team = guestTeam)) }
        return RoomState(code, phase, arena, players, roundTime)
    }

    fun broadcast() {
        val blob = RoomCodec.encode(roomState())
        toHost(blob)
        if (guestConnected) toGuest(blob)
    }

    fun attachGuest(token: String, name: String): Boolean {
        if (phase == Proto.PH_MATCH && guest?.token != token) {
            toGuest(Proto.encode(Proto.ERROR) { it.writeUTF("Match already in progress.") })
            return false
        }
        if (guest != null && guest!!.token != token && guestConnected) {
            toGuest(Proto.encode(Proto.ERROR) { it.writeUTF("Room is full (2 players max).") })
            return false
        }
        val reconnect = guest?.token == token
        guest = NetPlayer(token, name, guestReady, false, true, guestTeam)
        guestConnected = true
        if (!reconnect) guestReady = false
        broadcast()
        toHost(Proto.encode(Proto.PEER_STATUS) { it.writeBoolean(true); it.writeUTF(token) })
        return true
    }

    fun detachGuest(permanent: Boolean) {
        guestConnected = false
        if (permanent) {
            guest = null
            guestReady = false
            guestTeam = null
        }
        broadcast()
        toHost(Proto.encode(Proto.PEER_STATUS) { it.writeBoolean(false); it.writeUTF(guest?.token ?: "") })
    }

    /** Guest packet handling. Returns true if consumed. */
    fun process(blob: ByteArray): Boolean {
        val d = Proto.reader(blob)
        when (Proto.typeOf(blob)) {
            Proto.HELLO -> {
                val token = d.readUTF(); val name = d.readUTF()
                attachGuest(token, name)
            }
            Proto.READY -> {
                guestReady = d.readBoolean()
                broadcast()
            }
            Proto.TEAM -> {
                guestTeam = IntArray(3) { d.readByte().toInt() }
                broadcast()
            }
            Proto.LEAVE -> detachGuest(permanent = true)
            Proto.LOBBY_RETURN -> returnToLobby()
            else -> return false
        }
        return true
    }

    fun markHostReady(v: Boolean) { hostReady = v; broadcast() }
    fun assignHostTeam(ids: IntArray) { hostTeam = ids; broadcast() }
    fun chooseArena(id: Int) {
        arena = id
        broadcast()
        if (guestConnected) toGuest(Proto.encode(Proto.ARENA_PICK) { it.writeByte(id) })
    }

    fun canStart(): Boolean =
        phase == Proto.PH_LOBBY && guestConnected && guest != null &&
            hostReady && guestReady && hostTeam != null && guestTeam != null

    fun startMatch(): ByteArray? {
        if (!canStart()) return null
        phase = Proto.PH_MATCH
        broadcast()
        val start = Proto.encode(Proto.START_MATCH) { o ->
            o.writeByte(arena)
            o.writeFloat(roundTime)
            for (i in 0..2) o.writeByte(hostTeam!![i])
            for (i in 0..2) o.writeByte(guestTeam!![i])
        }
        toGuest(start)
        return start
    }

    fun returnToLobby() {
        phase = Proto.PH_LOBBY
        hostReady = false
        guestReady = false
        broadcast()
        if (guestConnected) toGuest(Proto.encode(Proto.LOBBY_RETURN) {})
    }
}
