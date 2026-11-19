package com.furyclash.game.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire protocol shared by LAN (raw TCP) and online (WebSocket relay).
 * Every packet is a self-contained binary blob; transports frame it.
 * Lobby-level packets are understood by the room server (LobbyHost / relay.js);
 * match-level packets (INPUT/SNAPSHOT/EVT/TEAM/RESUME/LOBBY_RETURN/START_MATCH)
 * are forwarded opaquely between the two players.
 */
object Proto {
    const val VERSION = 1

    const val HELLO = 1          // guest->server: token, name
    const val ROOM_STATE = 3     // server->both: code, phase, arena, players[], teams[]
    const val READY = 4          // player->server: bool
    const val START_MATCH = 5    // host->guest: arena, roundTime, team0[3], team1[3]
    const val INPUT = 6          // guest->host: moveX, moveY, block, pressMask
    const val SNAPSHOT = 7       // host->guest: full battle state
    const val EVT = 8            // host->guest: gameplay event (sound/announce/hit/...)
    const val LEAVE = 9          // player->server: permanent leave
    const val PING = 10          // either: echo ms
    const val PONG = 11
    const val ERROR = 12         // server->player: message
    const val RESUME = 13        // host->guest: reconnect handshake (snapshot follows)
    const val LOBBY_RETURN = 14  // either: back to lobby after match
    const val PEER_STATUS = 15   // server->player: peerConnected bool, peerToken
    const val CREATE_ROOM = 16   // host->relay: token, name
    const val JOIN_ROOM = 17     // guest->relay: code, token, name
    const val REJOIN = 18        // guest->relay: code, token (reconnect)
    const val TEAM = 19          // player->host: 3 character ids
    const val ARENA_PICK = 20    // host->guest: arena id (lobby display)

    const val PH_LOBBY = 0
    const val PH_MATCH = 1

    const val MAX_PLAYERS = 2

    fun encode(type: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(256)
        val d = DataOutputStream(bos)
        d.writeByte(type)
        body(d)
        d.flush()
        return bos.toByteArray()
    }

    fun typeOf(blob: ByteArray): Int = blob[0].toInt() and 0xFF

    fun reader(blob: ByteArray): DataInputStream =
        DataInputStream(ByteArrayInputStream(blob, 1, blob.size - 1))
}

/** Lobby player slot as shown in the lobby UI. */
data class NetPlayer(
    val token: String,
    val name: String,
    val ready: Boolean,
    val host: Boolean,
    val connected: Boolean,
    val team: IntArray? = null
) {
    override fun equals(other: Any?): Boolean = other is NetPlayer && other.token == token
    override fun hashCode(): Int = token.hashCode()
}

data class RoomState(
    val code: String,
    val phase: Int,
    val arena: Int,
    val players: List<NetPlayer>,
    val roundTime: Float = 99f
)

object RoomCodec {
    fun encode(s: RoomState): ByteArray = Proto.encode(Proto.ROOM_STATE) { d ->
        d.writeUTF(s.code)
        d.writeByte(s.phase)
        d.writeByte(s.arena)
        d.writeFloat(s.roundTime)
        d.writeByte(s.players.size)
        for (p in s.players) {
            d.writeUTF(p.token)
            d.writeUTF(p.name)
            d.writeBoolean(p.ready)
            d.writeBoolean(p.host)
            d.writeBoolean(p.connected)
            d.writeBoolean(p.team != null)
            p.team?.let { for (i in 0 until 3) d.writeByte(it[i]) }
        }
    }

    fun decode(blob: ByteArray): RoomState {
        val d = Proto.reader(blob)
        val code = d.readUTF()
        val phase = d.readByte().toInt()
        val arena = d.readByte().toInt()
        val roundTime = d.readFloat()
        val n = d.readByte().toInt()
        val players = ArrayList<NetPlayer>(n)
        for (i in 0 until n) {
            val token = d.readUTF()
            val name = d.readUTF()
            val ready = d.readBoolean()
            val host = d.readBoolean()
            val connected = d.readBoolean()
            val hasTeam = d.readBoolean()
            val team = if (hasTeam) IntArray(3) { d.readByte().toInt() } else null
            players.add(NetPlayer(token, name, ready, host, connected, team))
        }
        return RoomState(code, phase, arena, players, roundTime)
    }
}

/** Guest -> host input packet. */
object InputCodec {
    fun encode(moveX: Float, moveY: Float, block: Boolean, mask: Int): ByteArray =
        Proto.encode(Proto.INPUT) { d ->
            d.writeFloat(moveX); d.writeFloat(moveY); d.writeBoolean(block); d.writeByte(mask)
        }

    fun decode(blob: ByteArray): Quadruple {
        val d = Proto.reader(blob)
        return Quadruple(d.readFloat(), d.readFloat(), d.readBoolean(), d.readByte().toInt() and 0xFF)
    }

    data class Quadruple(val mx: Float, val my: Float, val block: Boolean, val mask: Int)
}

/** Host -> guest gameplay events (sounds, announcements, damage numbers, KOs). */
object EvtCodec {
    const val HIT = 0; const val BLOCKED = 1; const val PARRY = 2; const val SHIELDED = 3
    const val KO = 4; const val ANNOUNCE = 5; const val SOUND = 6; const val SWITCH = 7
    const val FROZEN = 8; const val ROUND_START = 9; const val ROUND_END = 10
    const val MATCH_END = 11; const val FIGHT = 12; const val SKILL = 13

    fun hit(atkTeam: Int, vicTeam: Int, dmg: Float, combo: Int, heavy: Boolean, x: Float, y: Float) =
        Proto.encode(Proto.EVT) { d ->
            d.writeByte(HIT); d.writeByte(atkTeam); d.writeByte(vicTeam)
            d.writeFloat(dmg); d.writeByte(combo); d.writeBoolean(heavy); d.writeFloat(x); d.writeFloat(y)
        }

    fun simple(kind: Int, team: Int) = Proto.encode(Proto.EVT) { d -> d.writeByte(kind); d.writeByte(team) }

    fun announce(text: String, color: Int, dur: Float) = Proto.encode(Proto.EVT) { d ->
        d.writeByte(ANNOUNCE); d.writeUTF(text); d.writeInt(color); d.writeFloat(dur)
    }

    fun sound(name: String) = Proto.encode(Proto.EVT) { d -> d.writeByte(SOUND); d.writeUTF(name) }

    fun switch(team: Int, slot: Int) = Proto.encode(Proto.EVT) { d -> d.writeByte(SWITCH); d.writeByte(team); d.writeByte(slot) }

    fun roundStart(round: Int) = Proto.encode(Proto.EVT) { d -> d.writeByte(ROUND_START); d.writeByte(round) }

    fun roundEnd(winner: Int, reason: String) = Proto.encode(Proto.EVT) { d ->
        d.writeByte(ROUND_END); d.writeByte(winner + 1); d.writeUTF(reason)
    }

    fun matchEnd(winner: Int) = Proto.encode(Proto.EVT) { d -> d.writeByte(MATCH_END); d.writeByte(winner) }

    fun skill(team: Int, idx: Int, cinematic: Boolean) = Proto.encode(Proto.EVT) { d ->
        d.writeByte(SKILL); d.writeByte(team); d.writeByte(idx); d.writeBoolean(cinematic)
    }

    fun kindOf(blob: ByteArray): Int {
        val d = Proto.reader(blob)
        return d.readByte().toInt()
    }
}
