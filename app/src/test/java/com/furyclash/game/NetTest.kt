package com.furyclash.game

import com.furyclash.game.engine.FightCamera
import com.furyclash.game.engine.ParticleSystem
import com.furyclash.game.game.BattleMode
import com.furyclash.game.game.Characters
import com.furyclash.game.game.CombatWorld
import com.furyclash.game.game.Difficulty
import com.furyclash.game.game.FState
import com.furyclash.game.game.TeamBattle
import com.furyclash.game.input.InputState
import com.furyclash.game.net.InputCodec
import com.furyclash.game.net.LobbyHost
import com.furyclash.game.net.Proto
import com.furyclash.game.net.RoomCodec
import com.furyclash.game.net.RoomState
import com.furyclash.game.net.NetPlayer
import com.furyclash.game.net.Snapshot
import com.furyclash.game.net.TcpFrameSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** JVM tests for the multiplayer layer: framing, sync fidelity, lobby rules. */
class NetTest {
    private val dt = 1f / 60f

    private fun battle(mode: BattleMode): TeamBattle {
        val w = CombatWorld(ParticleSystem(64), FightCamera())
        return TeamBattle(
            w,
            listOf(Characters.byId(9), Characters.byId(1), Characters.byId(12)),
            listOf(Characters.byId(13), Characters.byId(10), Characters.byId(3)),
            mode, Difficulty.NORMAL, 99f
        )
    }

    private fun step(b: TeamBattle, seconds: Float, i0: InputState = InputState(), i1: InputState = InputState()) {
        var t = 0f
        val inputs = arrayOf(i0, i1)
        while (t < seconds) {
            i0.update(dt); i1.update(dt)
            b.update(dt, inputs)
            t += dt
        }
    }

    @Test
    fun roomStateRoundTrip() {
        val s = RoomState("AB12C", Proto.PH_MATCH, 2, listOf(
            NetPlayer("tok1", "Host", true, true, true, intArrayOf(1, 2, 3)),
            NetPlayer("tok2", "Guest", false, false, false, null)
        ), 60f)
        val d = RoomCodec.decode(RoomCodec.encode(s))
        assertEquals(s.code, d.code)
        assertEquals(s.phase, d.phase)
        assertEquals(s.arena, d.arena)
        assertEquals(s.roundTime, d.roundTime, 0f)
        assertEquals(2, d.players.size)
        assertArrayEquals(intArrayOf(1, 2, 3), d.players[0].team)
        assertNull(d.players[1].team)
        assertFalse(d.players[1].connected)
    }

    @Test
    fun inputPacketRoundTrip() {
        val q = InputCodec.decode(InputCodec.encode(0.75f, -1f, true, 0b10100101))
        assertEquals(0.75f, q.mx, 0f)
        assertEquals(-1f, q.my, 0f)
        assertTrue(q.block)
        assertEquals(0b10100101, q.mask)
    }

    @Test
    fun tcpFramingRoundTrip() {
        ServerSocket(0).use { server ->
            val accepted = arrayOfNulls<TcpFrameSocket>(1)
            val t = Thread {
                val sock = server.accept()
                accepted[0] = TcpFrameSocket(sock)
            }
            t.start()
            val client = TcpFrameSocket.connect("127.0.0.1", server.localPort, 3000)
            t.join(3000)
            val srv = accepted[0]!!
            val a = Proto.encode(Proto.PING) { it.writeLong(123456789L) }
            val b = ByteArray(70000) { (it % 251).toByte() } // > 64k frame
            client.send(a); client.send(b)
            assertArrayEquals(a, srv.receive())
            assertArrayEquals(b, srv.receive())
            srv.send(a)
            assertArrayEquals(a, client.receive())
            client.close(); srv.close()
        }
    }

    @Test
    fun snapshotReplicatesHostStateExactly() {
        val host = battle(BattleMode.ONLINE_HOST)
        val replica = battle(BattleMode.ONLINE_CLIENT)
        val i0 = InputState(); val i1 = InputState()
        i0.press(InputState.PUNCH)
        step(host, 2.2f, i0, i1)
        i1.press(InputState.KICK)
        step(host, 0.5f, i0, i1)
        Snapshot.apply(replica, Snapshot.capture(host))
        assertEquals(host.phase, replica.phase)
        assertEquals(host.round, replica.round)
        assertEquals(host.timer, replica.timer, 0f)
        for (t in 0..1) {
            assertEquals(host.teams[t].active, replica.teams[t].active)
            assertEquals(host.teams[t].roundsWon, replica.teams[t].roundsWon)
            for (k in 0..2) {
                val a = host.teams[t].fighters[k]; val b = replica.teams[t].fighters[k]
                assertEquals(a.hp, b.hp, 0f)
                assertEquals(a.x, b.x, 0f)
                assertEquals(a.y, b.y, 0f)
                assertEquals(a.state, b.state)
                assertEquals(a.alive, b.alive)
                assertEquals(a.energy, b.energy, 0f)
                assertEquals(a.comboTaken, b.comboTaken)
            }
        }
        assertEquals(host.world.projectiles.size, replica.world.projectiles.size)
    }

    @Test
    fun replicaTracksHostOverTime() {
        val host = battle(BattleMode.ONLINE_HOST)
        val replica = battle(BattleMode.ONLINE_CLIENT)
        val i0 = InputState(); val i1 = InputState()
        var maxErr = 0f
        var t = 0f
        while (t < 6f) {
            if ((t * 60).toInt() % 40 == 0) i0.press(InputState.PUNCH)
            if ((t * 60).toInt() % 55 == 0) i1.press(InputState.KICK)
            i0.update(dt); i1.update(dt)
            host.update(dt, arrayOf(i0, i1))
            if ((t * 60).toInt() % 3 == 0) {
                Snapshot.apply(replica, Snapshot.capture(host))
            } else {
                replica.remoteVisualTick(dt)
            }
            val a = host.active(0); val b = replica.active(0)
            maxErr = maxOf(maxErr, kotlin.math.abs(a.x - b.x))
            t += dt
        }
        // dead-reckoning between 20 Hz snapshots stays within a few cm
        assertTrue("replica drift too large: $maxErr", maxErr < 0.25f)
        assertEquals(host.phase, replica.phase)
    }

    // ---------------- lobby rules ----------------

    private fun lobby(): Pair<LobbyHost, MutableList<ByteArray>> {
        val toGuest = ArrayList<ByteArray>()
        val l = LobbyHost("hostTok", "Host", 99f)
        l.toGuest = { toGuest.add(it) }
        l.toHost = { }
        return Pair(l, toGuest)
    }

    private fun hello(token: String, name: String) =
        Proto.encode(Proto.HELLO) { it.writeUTF(token); it.writeUTF(name) }

    @Test
    fun lobbyRequiresTwoReadyPlayersWithTeams() {
        val (l, _) = lobby()
        l.assignHostTeam(intArrayOf(1, 2, 3))
        l.markHostReady(true)
        assertFalse("cannot start alone", l.canStart())
        assertTrue(l.process(hello("g1", "Guest")))
        assertFalse("guest not ready", l.canStart())
        l.process(Proto.encode(Proto.READY) { it.writeBoolean(true) })
        assertFalse("guest has no team", l.canStart())
        l.process(Proto.encode(Proto.TEAM) { it.writeByte(4); it.writeByte(5); it.writeByte(6) })
        assertTrue(l.canStart())
        assertNotNull(l.startMatch())
        assertEquals(Proto.PH_MATCH, l.phase)
    }

    @Test
    fun midMatchJoinRejectedAndRoomFull() {
        val (l, out) = lobby()
        l.process(hello("g1", "Guest"))
        l.process(hello("g2", "Intruder"))
        assertTrue("room full error", out.any { Proto.typeOf(it) == Proto.ERROR })
        l.assignHostTeam(intArrayOf(1, 2, 3)); l.markHostReady(true)
        l.process(Proto.encode(Proto.READY) { it.writeBoolean(true) })
        l.process(Proto.encode(Proto.TEAM) { it.writeByte(4); it.writeByte(5); it.writeByte(6) })
        l.startMatch()
        out.clear()
        l.process(hello("g3", "Late"))
        assertTrue("mid-match join rejected", out.any { Proto.typeOf(it) == Proto.ERROR })
    }

    @Test
    fun disconnectHoldsSlotAndRejoinRestores() {
        val (l, _) = lobby()
        l.process(hello("g1", "Guest"))
        l.detachGuest(permanent = false)
        assertFalse(l.guestConnected)
        assertNotNull("slot held for grace", l.guest)
        assertTrue(l.attachGuest("g1", "Guest"))
        assertTrue(l.guestConnected)
        // permanent leave frees the slot
        l.detachGuest(permanent = true)
        assertNull(l.guest)
        assertTrue(l.attachGuest("g2", "New"))
    }

    @Test
    fun returnToLobbyResetsReady() {
        val (l, _) = lobby()
        l.process(hello("g1", "Guest"))
        l.markHostReady(true)
        l.process(Proto.encode(Proto.READY) { it.writeBoolean(true) })
        l.assignHostTeam(intArrayOf(1, 2, 3))
        l.process(Proto.encode(Proto.TEAM) { it.writeByte(4); it.writeByte(5); it.writeByte(6) })
        l.startMatch()
        l.returnToLobby()
        assertEquals(Proto.PH_LOBBY, l.phase)
        assertFalse(l.hostReady)
        assertFalse(l.canStart())
    }
}
