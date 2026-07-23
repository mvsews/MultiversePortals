# How to use portals (Multiverse Portals)

Short guide for players.

There are **two modes**: local (wool, same server) and cross-server (`[Multi]` / `[To]` / `[Pair]`).

**Languages:** [English](portal_guide.en.md) · [中文](portal_guide.zh.md) · [Русский](portal_guide.md)

---

## Local portals (wool)

Like ColorPortals — teleport **on this server** (other worlds on the same host are fine).

1. Frame of **one wool color**, roughly a **3×4** outline
2. **Wall sign** on the top-middle wool block
3. Inside: **pressure plate** (optional button under the sign)
4. **Line 1** = name, **line 2** = channel (`0`–`9999`)

Portals with the **same color and channel** form a ring: A→B→C→A. Two portals = round trip.

Stand on the plate or press the button. Right-click the sign for info. List: `/mvp local list`.

---

## Cross-server portals

### Build a portal

1. Frame like a Nether portal — **2×3** opening (air), any solid blocks around:

```
O O O O
O . . O
O . . O
O . . O
O O O O
```

`O` = frame, `.` = air.

2. Hang a **sign** on the frame.
3. Place a **pressure plate** next to it (1–2 blocks).
4. On **line 1** of the sign write the type (case does not matter).

The frame is for the visual “matter” inside. A sign + plate is enough to work.

### Types on line 1

| Line 1 | What it does |
|--------|----------------|
| `[Multi]` / `[mvp]` / `[MVP]` / `[portal]` / `[Random]` | Random server (link **sticks**) |
| `[To]` | Specific server: **IP + port** (lines 2 / 3) or a catalog id |
| `[Pair]` | Paired portal for round-trip |

Brackets are optional: `mvp`, `portal`, `Multi` also work.

### Link two of your servers (admin)

Full steps: [README.md](README.md#link-two-of-your-servers-round-trip). Short version:

1. **Best:** on Alpha put `[Pair]` → copy the code → on Beta `[Pair]` + that code on line 2.
2. **By IP:** `Portal` / `1.2.3.4:25565` (or address only if port is `25565`) — no catalog needed.
3. **By id:** Alpha `[To]` / `beta`, Beta `[To]` / `alpha` (ids from `/mvp info`).
4. For ids both servers must be on the public catalog [mp.mvse.ws](https://mp.mvse.ws/) (`accept-transfers=true` and a public address; `public-host` usually comes from `server-ip`).

English: [README.md](README.md#link-two-of-your-servers-round-trip) · Русский: [README.ru.md](README.ru.md#связать-два-своих-сервера-туда-обратно) · 中文: [README.zh.md](README.zh.md#两台服互通往返)

### Random = sticky

1. Place `[Multi]` → white particles → the portal **binds to one server**.
2. While the sign stays up, it always goes there (including after a restart).
3. Want another destination — put a **button by the sign** and press it: that’s the **door dial**, like in *Howl’s Moving Castle* — it switches where the portal leads (club MVP peers first, otherwise a new random).
4. Or **break the sign** and place `[Multi]` again.
5. The dial does **not** work on `[To]` (fixed address) or `[Pair]`.
6. If the target is temporarily down, the link **does not change**; try later, turn the dial, or rebuild the sign.

### How to travel

Stand on the **plate** by the sign. Wait for the charge — you will transfer.

### One-way consent (`/mvp ready`)

Two kinds of hops:

- **With return** — paired `[Pair]` or a server with the same plugin. You go **freely**, no consent needed.
- **One-way** — a public random server from the scanner. You cannot come back through that portal.

Before a one-way hop, allow once:

```
/mvp ready
```

or in chat:

```
mvp ready
```

Revoke:

```
/mvp ready off
```

or chat: `mvp ready off`.

---

## Incoming traffic (admin)

On a receiving server you can filter guests:

```
/mvp ingress           — limits
/mvp deny add Nick …   — portal ban
/mvp rep Nick -50      — reputation penalty
```

Details: [README.md](README.md).

## Server load (admin)

The plugin is **not heavy all the time**, but not zero either. Most work is async; **peaks** are when creating `[Multi]` (Scan… + probes), charging on a plate, or many portals with visuals.

| What | When | Impact |
|------|------|--------|
| Scanners (MineScan + Cornbread) | ~every 1–2 min | HTTP + SQLite — **almost no TPS hit** |
| Bind on `[Multi]` create | once | background probes — **up to ~90 s** |
| Travel after bind | standing on plate | ~2 s charge + transfer, **no long search** |
| Matter particles | every 0.5 s | only if a player is **within ~20 blocks** |
| Local wool portals | plate press | **very light** |

**Weak VPS (1–2 GB RAM):** fine as a **destination** with 1–2 sticky `[Multi]`. Don’t place dozens of effect-heavy portals or rebuild signs constantly.

**Lighten load** in `config.yml`:

```yaml
scanner:
  refresh-seconds: 180
  sample-count: 20
  max-attempts: 10
  bind-search-seconds: 45

effects:
  matter:
    enabled: false      # or particles: false

registry:
  enabled: false   # leave false — join the open catalog over HTTPS
```

Lightest mode: **PAIR** only or **local wool** only. Full table: [docs/TECHNICAL.md](docs/TECHNICAL.md#performance--weak-servers).

## Items

- Inventory transfer is **off by default**. Admins enable with `/mvp settings export on` / `import on` (alias `/mvp items …`).
- On a **public one-way** hop — items **stay in your inventory** (they are not stripped).

## Versions

In-game: **`/mvp version`** — installed vs latest on mp.mvse.ws.

The plugin will not send you to a server your client cannot join. If there are no targets — “no compatible servers”.

Download the jar from [mp.mvse.ws](https://mp.mvse.ws/) — the filename includes the version (`MultiversePortals-1.1.15.jar`).

## Admin: `accept-transfers` and the catalog

In **`server.properties`** (not the plugin config):

```properties
accept-transfers=true
```

Without this, Minecraft **rejects** Transfer joins — guests from other worlds cannot arrive. The plugin then stays **local-only** (wool, Pair / `[To]` by IP) and is **not listed** on mp.mvse.ws.

Transfer address comes from `server-ip` / `server-port` automatically. Set `public-host` / `public-port` in `config.yml` if players join via a **different domain or port** (Docker `-p 25566:25565`, NAT, proxy) — otherwise the catalog gets the internal `25565` / LAN IP and you stay local-only. Check: `/mvp settings`.

## Common issues

| Message | What to do |
|---------|------------|
| Need `/mvp ready` | Run `/mvp ready` or type `mvp ready` in chat |
| No compatible servers | Wait / other client version; admin: `/mvp scanner` |
| Could not find a live server | Step on the plate again |
| Pair portal broken | Relink with a `[Pair]` code |
| Server not in catalog | `accept-transfers=true`, public IP/domain (not Docker `172.*`), **`public-port` = external port**; see `/mvp settings` → catalog |

## Player commands

```
/mvp ready          — allow one-way
/mvp ready off      — disallow one-way
/mvp local list     — local wool portals
/mvp version        — installed vs latest
/mvp help           — help
/mvp scanner        — pool size (info)
```

Admin toggles: `/mvp settings` (map / guests / inventory).

Full admin docs: [README.md](README.md) · [README.ru.md](README.ru.md) · [README.zh.md](README.zh.md).

## Feedback

Ideas, bugs, or suggestions? Please [open a GitHub Issue](https://github.com/mvsews/MultiversePortals/issues).

**MIT** license — free to distribute and modify. See [LICENSE](LICENSE).
