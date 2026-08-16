# How to use portals (Multiverse Portals)

Short guide for players.

There are **three modes**: local (wool), cross-server (`[Multi]` / `[To]` / `[Pair]`), and **Away** (another biome on this server).

**Languages:** [English](portal_guide.en.md) · [中文](portal_guide.zh.md) · [Русский](portal_guide.md)

---

## Local portals (wool)

Like ColorPortals — teleport **on this server** (other worlds on the same host are fine).

1. Frame of **one wool color** — any closed ring (a small 3×4 doorway is only an example)
2. **Wall sign** on the right jamb (looking at the portal)
3. **Line 1** = name, **line 2** = channel (`0`–`9999`)

Portals with the **same color and channel** form a ring: A→B→C→A. Two portals = round trip.

Walk into the **purple opening**. No plate needed (an old plate still works). Optional button under the sign. Right-click the sign for info. List: `/mvp local list`.

---

## Away (another biome, same server)

Like a Nether portal, but between **overworld biomes** on this server.

1. Frame from **this biome’s block**: oak in a forest, sandstone in a desert, packed ice on a glacier, and so on. Any closed ring; **wall sign** on the right jamb
2. **Line 1** = `Portal` / `Портал` / `传送门`. **Line 2** empty or `Away` / `Авей` / `异界`
3. Walk into the **purple opening**

The first trip binds to one overworld biome (ocean and caves included; not Nether/End) and stays there, like a Nether portal. The plugin may build a small return ring from **your** biome’s material, sign on the right. If the frame is the wrong block, chat names the one this biome needs.

If line 2 is `Random`, it is a **cross-server** portal even on oak. Away is **not** shown on the public map.

---

## Cross-server portals

### Build a portal

1. Frame like a Nether portal — **2×3** opening (air), any solid blocks around. A **giant closed circle** also works: while the ring is closed the opening shows the vanilla Nether portal sheet; open a gap and the matter vanishes.

```
O O O O
O . . O
O . . S
O . . O
O O O O
```

`O` = frame, `.` = air, `S` = sign on the right jamb.

2. Hang a **sign on the right jamb** (looking at the portal).
3. On **line 1** of the sign write the type (case does not matter). No plate needed — walk into the purple sheet.

The ring must be **closed**. Breaking the sign on Random/Away deletes the portal.

### Types on line 1

| Line 1 | What it does |
|--------|----------------|
| `[Multi]` / `[portal]` / `Портал` / `传送门` | Random server (link **sticks**) |
| `[To]` / `К` / `前往` | Specific server: **IP + port** (lines 2 / 3) or a catalog id |
| `[Pair]` / `Пара` / `配对` | Paired portal for round-trip |
| `Portal` / `Портал` / `传送门` on a frame of **this biome's material** | Away — another biome on this server (or write `Away` / `Авей` / `异界`) |

Away: see [Away](#away-another-biome-same-server) above.

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

Walk into the **purple opening** (do not stand on the top of the frame). A plate next to it is optional and still works. Wait for the charge — you will transfer.

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

The plugin is **not heavy all the time**, but not zero either. Most work is async; **peaks** are when creating `[Multi]` (Scan… + probes), charging in the opening, or many portals with visuals.

| What | When | Impact |
|------|------|--------|
| Scanners (MineScan + Cornbread) | ~every 1–2 min | HTTP + SQLite — **almost no TPS hit** |
| Bind on `[Multi]` create | once | background probes — **up to ~90 s** |
| Travel after bind | standing in the opening | ~2 s charge + transfer, **no long search** |
| Matter particles | every 0.5 s | only if a player is **within ~20 blocks** |
| Local wool portals | walk in | **very light** |

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

Download the jar from [mp.mvse.ws](https://mp.mvse.ws/) — the filename includes the version (`MultiversePortals-1.2.0.jar`).

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
| Could not find a live server | Walk into the opening again |
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

Admin toggles: `/mvp settings` (map / guests / inventory / who may create / portal types).

All types (`away`, `wool`, `multi`, `pair`, `to`) are **on** by default. Disable one with `portals.types.multi: false` or `/mvp settings type multi off`. Who may build: `portals.create: everyone` (all players) or `admin` (OP / `multiverseportals.admin` only).

Full admin docs: [README.md](README.md) · [README.ru.md](README.ru.md) · [README.zh.md](README.zh.md).

## Feedback

If in-game behavior **does not match** this guide or [docs/CONCEPTS.md](docs/CONCEPTS.md), please [open an Issue](https://github.com/mvsews/MultiversePortals/issues) or a [Pull Request](https://github.com/mvsews/MultiversePortals/pulls). Both are welcome.

**MIT** license — free to distribute and modify. See [LICENSE](LICENSE).
