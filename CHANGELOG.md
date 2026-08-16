# Changelog

All notable changes to Multiverse Portals are listed here.

## 1.2.17 — 2026-08-16

- Local **wool** portals accept any closed ring of one color (not only 3×4). A small 3×4 doorway is just an example. Hang the sign on the **right jamb**, same as cross-server portals. Site and guides updated.

## 1.2.16 — 2026-08-16

- Local **wool** portals work like the others: closed 3×4 ring lights the purple sheet, walk in to travel. Pressure plate is optional (old ColorPortals plates still work). Arrival lands in front of the destination, not in the hole.

## 1.2.15 — 2026-08-16

- Standing on the **frame** (lintel / top of the ring) no longer starts a hop — only the purple opening, or a plate if you keep one. Arrival lands **in front of** the portal, not in the sheet.
- Site [mp.mvse.ws](https://mp.mvse.ws/): download line shows the current plugin version; [server list](https://mp.mvse.ws/list) with reputation, hops in/out, and search by name or IP. Build notes: sign on the **right jamb** (top OK for a one-person doorway); destinations **Random → friend IP → another biome**.

## 1.2.14 — 2026-08-16

- Hop **records**: who went from where to where, `OK` / `BOUNCED` / `REFUSED`. Hub stores the rows (`GET /mvp/v1/reputation` → `events`). Bind uses hop stats as a soft rank. Hub down: short pull, cooldown, local scanners — travel still works.

## 1.2.13 — 2026-08-16

- Directed peer reputation: successful hops **+1**, bounce/reject **−1**, plus in/out/fail counts per peer.
- Leaves push their opinions in catalog announce. Hub reads `GET /mvp/v1/reputation?from=A&about=B`. Ops: `/mvp hops`, `/mvp registry hops`.

## 1.2.12 — 2026-08-16

- Multi bind: stock MOTD **A Minecraft Server** is treated like ordinary survival (same priority as SMP/skyblock).

## 1.2.11 — 2026-08-16

- After the portal countdown, show the destination and transfer — no more "Finding a world" / "don't move" / "stay on the plate". Bound portals skip a second search and go.

## 1.2.10 — 2026-08-16

- Multi bind treats **skyblock** (and oneblock) as ordinary survival, same as SMP. Minigames still go later.

## 1.2.9 — 2026-08-16

- Multi bind "vanilla" means **ordinary survival worlds** (Paper/Spigot OK). Fabric/Forge and minigames/skyblock go later — not "Vanilla jar instead of Paper".

## 1.2.8 — 2026-08-16

- Searching copy: chat and the Multi sign say **Finding a world...** instead of Finding a server / `Scan...`.
- Away auto-exit: after locate, walk biome extents and build near the **center**, not the nearest border.
- Multi bind: public pool prefers **vanilla-like** hosts (plain `1.x` / Vanilla brand, not Paper/Fabric/Forge). Club MVP peers still go first.

## 1.2.7 — 2026-08-16

- Multi bind: chat said «Привязан!» but signs stayed on `Scan...`. Dest name is written (and re-sent) when the link sticks; a stale search after recreating the sign cannot overwrite a newer portal.

## 1.2.6 — 2026-08-16

- Away exit: if the return frame is broken, the next traveler **rebuilds it at the same coordinates** (biome link stays).
- Leaving a cross-server portal no longer saves you inside the purple sheet — a later manual join will not vanilla-teleport you to the Nether.

## 1.2.5 — 2026-08-16

- Auto-built Away return frame: control sign on the **right** jamb (looking at the portal), not on the lintel.

## 1.2.4 — 2026-08-16

- Sign keywords in **English, Russian, Chinese** (and German): `Portal` / `Портал` / `传送门`, `Away` / `Авей` / `异界`, `To` / `К` / `前往`, `Pair` / `Пара` / `配对`.

## 1.2.3 — 2026-08-16

- Pressure plate is **optional**. Walk into the closed opening (purple sheet) to travel. Plate still works if you keep one.
- Away / wool: villagers, zombies, and other living entities can walk through (same world). Cross-server hops stay **players only** (Transfer). Config: `away.mobs`.

## 1.2.2 — 2026-08-16

- New lang keys (Away block hint) are merged into existing `plugins/MultiversePortals/lang/*.yml` so the chat no longer prints the raw key `away-need-block`.

## 1.2.1 — 2026-08-16

- Packed frames that **share a 1-block pillar** stay separate: each sign fills only its own hole. Search sparkles and the purple sheet no longer spill into the neighbour (or float above the lintel).
- Away creation tells you **which block** this biome needs when the frame material does not match.

## 1.2.0 — 2026-08-16

### Away (biome portals)

- Sign `Portal` on a **closed frame of this biome’s material** (logs, sandstone, packed ice, …) opens a sticky biome portal, like vanilla Nether linking.
- Destination is **any overworld biome** (ocean and caves included; Nether/End excluded).
- The exit ring is a small 4×5 frame built from the **origin** biome material (`away.auto-build-return`).
- After bind, the sign shows the destination biome name with vanilla biome colors.
- Away portals are **not** published to the public catalog.

### Frames and look

- A **closed ring of any size** (up to `portals.max-frame-radius`, default 24) stays lit. Open a gap → matter drops immediately (`BROKEN_LOCAL`). Breaking the sign deletes MULTI and Away.
- Portal fill uses the **real Nether portal sheet** (`NETHER_PORTAL` blocks, animated purple plane) instead of solid purple cubes. Vanilla Nether transfer is cancelled, so any frame material works.
- Styles `end` / `gateway` still use BlockDisplay (real end portals would teleport instantly).

### Travel

- If the destination has **no return portal**, the player lands at a random local MULTI. For that player the portal is **home** for `travel.guest-home-seconds` (default 1 hour).
- Sticky Random MULTI no longer auto-rebinds when the dest is offline (`scanner.offline-rebind-days: 0` by default). `[To]` / `[Pair]` are never rebound this way.

### Admin

- Every portal type is **on by default** and can be turned off in config or in-game:
  - `portals.types.away` / `wool` / `multi` / `pair` / `to`
  - `/mvp settings type <name> on|off`
- Who may build portals: `portals.create: everyone` (default) or `admin` (OP / `multiverseportals.admin`).
  - `/mvp settings create everyone|admin`
- Disabled types cannot be created and cannot be used.

### Config keys (new)

```yaml
portals:
  max-frame-radius: 24
  create: everyone
  types:
    away: true
    wool: true
    multi: true
    pair: true
    to: true
away:
  enabled: true
  require-biome-frame: true
  auto-build-return: true
  locate-radius: 6400
  min-distance: 256
travel:
  guest-home-seconds: 3600
scanner:
  offline-rebind-days: 0
```
