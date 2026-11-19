#!/usr/bin/env node
/**
 * Fury Clash online relay — zero-dependency Node.js WebSocket room server.
 *
 * Rooms hold exactly 2 players (host + guest). The relay understands lobby
 * packets (create/join/ready/team/arena/start/leave) and forwards match
 * packets (INPUT / SNAPSHOT / EVT / RESUME) opaquely, so all gameplay stays
 * host-authoritative between the two devices.
 *
 * Run:   node server/relay.js            (port 8080, or $PORT)
 * Deploy: any free host that supports WebSocket upgrades (Render / Fly.io /
 *         Railway / a free VPS). See server/README.md.
 *
 * Wire format matches app/.../net/Protocol.kt (binary, type byte first).
 */
'use strict';
const http = require('http');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const GRACE_MS = 30000;
const IDLE_MS = 25000;

// ---- packet types (mirror Protocol.kt) ----
const T = {
  HELLO: 1, ROOM_STATE: 3, READY: 4, START_MATCH: 5, INPUT: 6, SNAPSHOT: 7,
  EVT: 8, LEAVE: 9, PING: 10, PONG: 11, ERROR: 12, RESUME: 13, LOBBY_RETURN: 14,
  PEER_STATUS: 15, CREATE_ROOM: 16, JOIN_ROOM: 17, REJOIN: 18, TEAM: 19, ARENA_PICK: 20,
};
const PH_LOBBY = 0, PH_MATCH = 1;

// ---- binary helpers ----
function reader(buf) {
  let o = 1; // skip type byte
  return {
    byte() { return buf[o++]; },
    bool() { return buf[o++] !== 0; },
    float() { const v = buf.readFloatBE(o); o += 4; return v; },
    int() { const v = buf.readInt32BE(o); o += 4; return v; },
    long() { const v = buf.readBigInt64BE(o); o += 8; return v; },
    utf() { const n = buf.readUInt16BE(o); o += 2; const s = buf.toString('utf8', o, o + n); o += n; return s; },
  };
}
function writer(type, fn) {
  const chunks = [Buffer.from([type])];
  const w = {
    byte(v) { chunks.push(Buffer.from([v & 0xff])); },
    bool(v) { chunks.push(Buffer.from([v ? 1 : 0])); },
    float(v) { const b = Buffer.alloc(4); b.writeFloatBE(v); chunks.push(b); },
    int(v) { const b = Buffer.alloc(4); b.writeInt32BE(v | 0); chunks.push(b); },
    long(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); chunks.push(b); },
    utf(s) { const b = Buffer.from(s, 'utf8'); const n = Buffer.alloc(2); n.writeUInt16BE(b.length); chunks.push(n, b); },
  };
  fn(w);
  return Buffer.concat(chunks);
}

// ---- WebSocket server framing (RFC6455, server side) ----
function wsAccept(key) {
  return crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
}
function wsEncode(payload, opcode = 2) {
  const len = payload.length;
  let head;
  if (len < 126) head = Buffer.from([0x80 | opcode, len]);
  else if (len < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | opcode; head[1] = 126; head.writeUInt16BE(len, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x80 | opcode; head[1] = 127; head.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([head, payload]);
}
class Conn {
  constructor(sock) {
    this.sock = sock; this.buf = Buffer.alloc(0);
    this.token = null; this.name = '?'; this.room = null; this.isHost = false;
    this.connected = true; this.lastSeen = Date.now();
    sock.on('data', (d) => this.onData(d));
    sock.on('close', () => this.onClose());
    sock.on('error', () => this.onClose());
  }
  send(blob) { if (this.connected) { try { this.sock.write(wsEncode(blob)); } catch (e) { this.onClose(); } } }
  ping() { if (this.connected) { try { this.sock.write(wsEncode(Buffer.alloc(0), 9)); } catch (e) { this.onClose(); } } }
  onData(d) {
    this.lastSeen = Date.now();
    this.buf = Buffer.concat([this.buf, d]);
    while (true) {
      if (this.buf.length < 2) return;
      const b0 = this.buf[0], b1 = this.buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      if (len > (1 << 20)) { this.sock.destroy(); return; }
      let mask = null;
      if (masked) { if (this.buf.length < off + 4) return; mask = this.buf.slice(off, off + 4); off += 4; }
      if (this.buf.length < off + len) return;
      let payload = this.buf.slice(off, off + len);
      this.buf = this.buf.slice(off + len);
      if (mask) { payload = Buffer.from(payload); for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]; }
      if (opcode === 8) { this.sock.end(); return; }
      if (opcode === 9) { this.sock.write(wsEncode(payload, 10)); continue; }
      if (opcode === 10) continue;
      if (opcode === 1 || opcode === 2) { try { handle(this, payload); } catch (e) { console.error('packet error', e.message); } }
    }
  }
  onClose() {
    if (!this.connected) return;
    this.connected = false;
    detach(this, false);
  }
}

// ---- rooms ----
const rooms = new Map();
function newCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  for (;;) {
    let c = '';
    for (let i = 0; i < 5; i++) c += chars[crypto.randomInt(chars.length)];
    if (!rooms.has(c)) return c;
  }
}
function member(room, conn) {
  if (room.host && room.host.conn === conn) return room.host;
  if (room.guest && room.guest.conn === conn) return room.guest;
  return null;
}
function other(room, conn) {
  if (room.host && room.host.conn === conn) return room.guest;
  if (room.guest && room.guest.conn === conn) return room.host;
  return null;
}
function roomStateBlob(room) {
  return writer(T.ROOM_STATE, (w) => {
    w.utf(room.code); w.byte(room.phase); w.byte(room.arena); w.float(room.roundTime);
    const ms = [room.host, room.guest].filter(Boolean);
    w.byte(ms.length);
    for (const m of ms) {
      w.utf(m.token); w.utf(m.name); w.bool(m.ready); w.bool(m.isHost);
      w.bool(m.conn ? m.conn.connected : false);
      w.bool(!!m.team);
      if (m.team) for (let i = 0; i < 3; i++) w.byte(m.team[i]);
    }
  });
}
function broadcast(room) {
  const blob = roomStateBlob(room);
  if (room.host && room.host.conn) room.host.conn.send(blob);
  if (room.guest && room.guest.conn) room.guest.conn.send(blob);
}
function peerStatus(room, conn, connected) {
  const o = other(room, conn);
  if (o && o.conn) o.conn.send(writer(T.PEER_STATUS, (w) => { w.bool(connected); w.utf(conn.token || ''); }));
}
function detach(conn, permanent) {
  const room = conn.room;
  if (!room) return;
  const m = member(room, conn);
  if (!m) return;
  const o = other(room, conn);
  if (permanent && m.isHost) {
    // host left for good -> room dissolves
    if (o && o.conn) { o.conn.send(writer(T.LEAVE, () => {})); o.conn.send(writer(T.ERROR, (w) => w.utf('Host left the room.'))); o.conn.room = null; }
    rooms.delete(room.code);
    conn.room = null;
    return;
  }
  if (permanent) {
    room.guest = null;
    conn.room = null;
    broadcast(room);
    if (o && o.conn) o.conn.send(writer(T.PEER_STATUS, (w) => { w.bool(false); w.utf(conn.token || ''); }));
    return;
  }
  // transient disconnect: keep the slot for the reconnect grace window
  m.conn = null;
  broadcast(room);
  if (o && o.conn) o.conn.send(writer(T.PEER_STATUS, (w) => { w.bool(false); w.utf(conn.token || ''); }));
  setTimeout(() => {
    if (m.conn) return;
    if (m.isHost) {
      if (room.guest && room.guest.conn) {
        room.guest.conn.send(writer(T.LEAVE, () => {}));
        room.guest.conn.send(writer(T.ERROR, (w) => w.utf('Host did not return.')));
        room.guest.conn.room = null;
      }
      rooms.delete(room.code);
    } else if (room.guest === m) {
      room.guest = null;
      broadcast(room);
    }
  }, GRACE_MS);
}

function handle(conn, blob) {
  const type = blob[0];
  const r = reader(blob);
  const room = conn.room;
  switch (type) {
    case T.PING: conn.send(writer(T.PONG, (w) => w.long(r.long()))); return;
    case T.CREATE_ROOM: {
      if (room) return;
      const token = r.utf(), name = r.utf();
      const code = newCode();
      const rm = { code, phase: PH_LOBBY, arena: 0, roundTime: 99, host: null, guest: null };
      rm.host = { conn, token, name, ready: false, team: null, isHost: true };
      rooms.set(code, rm);
      conn.room = rm; conn.token = token; conn.name = name; conn.isHost = true;
      broadcast(rm);
      console.log(`room ${code} created by ${name}`);
      return;
    }
    case T.JOIN_ROOM:
    case T.REJOIN: {
      const code = r.utf(), token = r.utf();
      const name = type === T.JOIN_ROOM ? r.utf() : (conn.name || 'Player');
      const rm = rooms.get(code.toUpperCase());
      if (!rm) { conn.send(writer(T.ERROR, (w) => w.utf('Room not found: ' + code))); return; }
      const existing = (rm.host && rm.host.token === token) ? rm.host : (rm.guest && rm.guest.token === token) ? rm.guest : null;
      if (!existing && rm.phase === PH_MATCH) { conn.send(writer(T.ERROR, (w) => w.utf('Match already in progress.'))); return; }
      if (!existing && rm.guest) { conn.send(writer(T.ERROR, (w) => w.utf('Room is full (2 players max).'))); return; }
      if (room && room !== rm) detach(conn, true);
      conn.room = rm; conn.token = token; conn.name = name;
      if (existing) {
        existing.conn = conn;
        conn.name = existing.name;
        conn.isHost = existing.isHost;
      } else {
        rm.guest = { conn, token, name, ready: false, team: null, isHost: false };
        conn.isHost = false;
      }
      broadcast(rm);
      peerStatus(rm, conn, true);
      console.log(`room ${code}: ${name} ${existing ? 'reconnected' : 'joined'}`);
      return;
    }
    case T.READY: {
      if (!room) return;
      const m = member(room, conn); if (!m) return;
      m.ready = r.bool();
      broadcast(room);
      return;
    }
    case T.TEAM: {
      if (!room) return;
      const m = member(room, conn); if (!m) return;
      m.team = [r.byte(), r.byte(), r.byte()];
      broadcast(room);
      const o = other(room, conn);
      if (o && o.conn) o.conn.send(blob);
      return;
    }
    case T.ARENA_PICK: {
      if (!room || !conn.isHost) return;
      room.arena = r.byte();
      broadcast(room);
      const o = other(room, conn);
      if (o && o.conn) o.conn.send(blob);
      return;
    }
    case T.START_MATCH: {
      if (!room || !conn.isHost || room.phase !== PH_LOBBY) return;
      if (!room.guest || !room.host.ready || !room.guest.ready || !room.host.team || !room.guest.team) return;
      // roundTime + arena come from the host payload
      const ar = r.byte(); const rt = r.float();
      room.arena = ar; room.roundTime = rt;
      room.phase = PH_MATCH;
      broadcast(room);
      if (room.guest.conn) room.guest.conn.send(blob);
      console.log(`room ${room.code}: match started`);
      return;
    }
    case T.LOBBY_RETURN: {
      if (!room) return;
      room.phase = PH_LOBBY;
      if (room.host) room.host.ready = false;
      if (room.guest) room.guest.ready = false;
      broadcast(room);
      const o = other(room, conn);
      if (o && o.conn) o.conn.send(blob);
      return;
    }
    case T.LEAVE: {
      if (!room) return;
      const o = other(room, conn);
      if (o && o.conn) o.conn.send(blob);
      detach(conn, true);
      console.log(`room ${room.code}: ${conn.name} left`);
      return;
    }
    case T.INPUT: case T.SNAPSHOT: case T.EVT: case T.RESUME: {
      if (!room) return;
      const o = other(room, conn);
      if (o && o.conn) o.conn.send(blob);
      return;
    }
    default: return;
  }
}

// ---- HTTP + upgrade ----
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end(`Fury Clash relay OK\nrooms: ${rooms.size}\n`);
});
server.on('upgrade', (req, sock) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) { sock.destroy(); return; }
  sock.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${wsAccept(key)}\r\n\r\n`
  );
  sock.setNoDelay(true);
  new Conn(sock);
});
setInterval(() => {
  const now = Date.now();
  for (const rm of rooms.values()) {
    for (const m of [rm.host, rm.guest]) {
      if (m && m.conn) {
        if (now - m.conn.lastSeen > IDLE_MS) m.conn.sock.destroy();
        else m.conn.ping();
      }
    }
  }
}, 10000);
server.listen(PORT, () => console.log(`Fury Clash relay listening on :${PORT} (WebSocket)`));
