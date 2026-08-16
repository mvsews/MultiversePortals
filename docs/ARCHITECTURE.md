# Multiverse Portals — Architecture

Intended behavior of frames, Away, guest home, and type gates: **[CONCEPTS.md](CONCEPTS.md)**.  
Using the open network to attract players to a quiet server: **[GROWTH.md](GROWTH.md)**.

## Open network (default)

Admin installs the plugin and sets a public address. The open catalog is at `https://mp.mvse.ws/mvp/v1`.  
Players create portals with signs. The public directory lives on the central hub (`https://mp.mvse.ws/mvp/v1`).

| Store | What | Where |
|-------|------|--------|
| **Local SQLite** | Portals, travel state, local club cache | Every server (WAL + separate read/write pools) |
| **Universal database** | Open directory + map graph for the whole network | Hosted at `mp.mvse.ws` |
| **HTTP catalog** | `/mvp/v1/catalog/*` — list + portal edges without exposing the DB | Public HTTPS |
| **Peer reputation** | `/mvp/v1/reputation` — directed hop opinions the leaves pushed | Public HTTPS |

Flow:

1. Server boots → auto `server.id` → announce/pull over HTTPS hub (catalog-share)
2. Catalog share (default on) fills the local club list from the hub (+ optional gossip)
3. Player writes `[Multi]` / `[To]` / `[Pair]` on a sign → portal saved locally (Pair invite via hub/peers as needed)
4. When a portal **binds** (or is created/deleted), the server pushes its portal edges to the hub so the [live map](https://mp.mvse.ws/) updates
5. Walk into the opening (optional plate) → travel session → `player.transfer(host, port)` (Java→Java / Bedrock→Bedrock on the same host when Geyser is present)
6. Destination lands the player **in front of** the return portal when known (else any network portal, else spawn). Leaf hosts without local federation **claim** pending landings from the hub (`POST /travel/claim`) when reachable; otherwise join continues normally (fail-open)

Bind / MULTI target order: **network club (MVP peers with the plugin) → public scanners**; with Bedrock, confirmed-Geyser hosts rise within each tier. Within a tier, **flow-balance** soft-ranks by origin vs dest online (busy→quiet; quiet→moderate band; mega soft penalty). *(Still planned: inter-server ping scoring and **mod/modpack matching** — see [TECHNICAL.md](TECHNICAL.md#planned).)*  
A **button** next to a random `[Multi]` sign cycles the sticky bind (club first). Nearby Random portals avoid the same destination when another target exists (`scanner.avoid-duplicate-radius`).  
If the official catalog hub is down, club gossip pauses and **public scanners + local SQLite** supply Multi targets; hub cooldown does not block Travel.

Inventory defaults OFF for open network (`open-network.default-*-inventory`).

See [REGISTRY.md](REGISTRY.md) — catalog / hub.

## Portal types

Design intent (frames, Nether fill, Away linking, guest home, type switches): **[CONCEPTS.md](CONCEPTS.md)**.

### PAIR
- Exactly two endpoints (server A point ↔ server B point).
- Enter → exit at the paired portal coordinates.
- Return always available while pair status is `ACTIVE`.
- If frame/sign breaks on one side → both sides become inactive.

### MULTI
- One local portal → sticky destination (Random or fixed `[To]`).
- Target may be **pluginless** (`accept-transfers=true` only).
- Return portal not required.
- Items off by default for pluginless targets.
- Optional `scanner.offline-rebind-days` (default **0** / off): if a Random dest stays unreachable that long, rebind to a more active host. `[To]` / `[Pair]` are never rebound.

### AWAY
- Same-server biome travel (Nether-like sticky pair). Sign line 1 `Portal` on a **closed frame of this biome's material** (oak in plains, sandstone in desert, pale oak in pale garden…).
- Line 2 `Random` still makes a cross-server Multi even on wood.
- Destination: any overworld biome (ocean/caves included; Nether/End excluded).
- First use rolls a dest biome; plugin may place a small 4×5 exit frame from the **origin** material, sign on the **right** jamb. Later uses always exit at the same paired portal. If dest already has a portal linked to the origin biome, **reuse it**.
- Sign after bind: biome name with vanilla biome colors.
- Not published to the public catalog.

### Local wool
- Same-server ColorPortals-style rings (color + channel). Any closed wool ring of one color — not only 3×4. Sign on the **right jamb**. Walk into the purple opening; no plate. Independent of Transfer.

A closed ring of any size (up to `portals.max-frame-radius`) is required. The opening is filled with the vanilla Nether portal **sheet** (`effects.matter.style: nether`). Opening the ring drops matter immediately (`BROKEN_LOCAL`); breaking the sign deletes MULTI/AWAY. Breaking the purple sheet does **not** deactivate the portal.

Each type can be turned off (`portals.types.*`). Who may build: `portals.create: everyone` (default) or `admin`.

### Guest home
If the destination has no portal back to origin, the player lands at a local Random portal that acts as **home for that player** for `travel.guest-home-seconds` (default 1 hour). The overlay must not overwrite the portal’s real sticky dest.

## Travel session

1. Source saves local `player_state` (always).
2. Source asks destination `/travel/offer` when reachable; otherwise the hub may store a pending registry travel for claim.
3. Destination (or hub) records accept + landing (+ whether landing is a true return).
4. Source clears/carries inventory per policy, marks session `PENDING`.
5. Transfer packet to destination public host:port (+ cookie with session id).
6. Destination on join: local pending session and/or hub `/travel/claim` → stand in front of the portal → restore inventory blob if allowed → `ARRIVED`.

## Security

- Travel/pair: HMAC-SHA256 over body + timestamp + server id; reject unknown peers.
- Catalog gossip: shared `catalog-share.network-secret` (open-network default value — same on every public peer so the hub can verify announces). Hub may set `public-read: true` for unsigned `/catalog/export`.
- One-time session ids, short TTL.
- Never trust client for inventory blobs — only peer HTTP + local DB.
- The Universal database backs the **open** public catalog (HTTPS).

## Performance notes

See [TECHNICAL.md](TECHNICAL.md#performance--weak-servers) for the full table and weak-server `config.yml` preset.

Summary for operators:

- **Background:** scanner HTTP + SQLite catalog upserts run **async** (default refresh ~90 s).
- **Bind-on-create:** one async probe burst per new `[Multi]`; travel afterward uses the **fixed** bound host.
- **Main thread:** matter particle tick every 10 ticks per portal, **skipped** when no player is within ~20 blocks; charge FX only while someone stands in the opening (or on a plate).
- Keep `registry.enabled: false` on normal hosts; use **PAIR** or **local wool** for minimum load; turn off `effects.matter` or `effects.matter.particles` on weak CPUs.
