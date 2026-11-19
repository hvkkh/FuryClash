# Fury Clash online relay

Zero-dependency Node.js WebSocket room relay for **online multiplayer** (players on
different networks / mobile data). LAN play needs **no server** — the host device
runs an embedded room server inside the app.

## Run locally

```bash
node server/relay.js            # listens on :8080  (or PORT=9000 node server/relay.js)
```

Health check: `curl http://localhost:8080/` → `Fury Clash relay OK`.

In the game: **Multiplayer → Online → set relay server URL** to
`ws://<your-host>:8080` (Android emulator on the same machine: `ws://10.0.2.2:8080`).

## Deploy for free (internet play)

The relay speaks plain WebSocket (`ws://` or `wss://`), so any free tier that
supports WebSocket upgrades works:

| Host | Notes |
|---|---|
| **Render.com** (free web service) | Build: none, Start: `node server/relay.js`. Uses `$PORT` automatically. Gives you `wss://<name>.onrender.com` (443, TLS terminated for you). |
| **Fly.io** | `fly launch` with this folder, `internal_port = 8080`. |
| **Railway.app** | Start command `node server/relay.js`, uses `$PORT`. |
| **Oracle Cloud / GCP free VPS** | `node server/relay.js` behind any port; open the firewall port. |

No database, no accounts, no paid APIs. Rooms live in memory; a restart simply
drops open rooms (players create/join again).

Then in-game set the relay URL to your deployment, e.g.
`wss://fury-clash-relay.onrender.com`.

## Protocol / behavior

- Binary packets, one type byte first — identical to `app/src/main/java/com/furyclash/game/net/Protocol.kt`.
- Rooms: 5-char code, **max 2 players** (host + guest), each fields a team of 3.
- Lobby packets handled by the relay: `CREATE_ROOM`, `JOIN_ROOM`, `REJOIN`,
  `READY`, `TEAM`, `ARENA_PICK`, `START_MATCH`, `LOBBY_RETURN`, `LEAVE`.
- Match packets forwarded opaquely: `INPUT` (guest→host), `SNAPSHOT`/`EVT`/`RESUME`
  (host→guest). **All gameplay simulation stays on the host device**; the relay
  never interprets game state, so bandwidth stays ~10 KB/s per match.
- Joining a room whose match already started is rejected (no mid-match join).
- Disconnects: the slot is held for a **30 s reconnect grace** (`REJOIN` with the
  same player token). The opponent sees a countdown and wins by forfeit if the
  grace expires. Host leaving permanently dissolves the room.
- Keepalive: WebSocket ping every 10 s; idle sockets closed after 25 s.

## Test without devices

`node server/relay.js` + two Android emulators (both reach the host machine via
`10.0.2.2`) exercise the full online path: create → join by code → ready →
start → play → disconnect → reconnect → rematch.
