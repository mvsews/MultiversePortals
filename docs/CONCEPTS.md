# Intended behavior

Design contract for portal types, frames, fill, and admin gates added around **1.2.0**.  
If the plugin **does not match** this document, [open an Issue](https://github.com/mvsews/MultiversePortals/issues/new/choose) or a Pull Request — both are welcome.

Related: [ARCHITECTURE.md](ARCHITECTURE.md) (how pieces fit), [TECHNICAL.md](TECHNICAL.md) (config), [GROWTH.md](GROWTH.md) (inbound players for quiet servers), [CHANGELOG.md](../CHANGELOG.md) (what shipped).

---

## Closed frames

A portal is **lit** only while its ring is a **closed opening** in the plane of the wall sign.

**Intent**

- Any solid frame material is valid (obsidian, wood, wool, ice, sandstone, …). Hang the control sign on the **right jamb** when looking at the portal.
- Size is not limited to vanilla 2×3. A giant closed circle is valid as long as the hole does not leak past `portals.max-frame-radius` (default **24**, Chebyshev from the sign).
- The interior is the flood-filled hole **attached to this sign**: air, water, cave air, and the plugin’s own portal sheet all count as **passable**. A pressure plate in the opening is **optional** (stays solid and is not filled). Two rings that share a 1-block pillar stay two portals — a neighbour’s hole is not filled, and search sparkles stay in this opening.
- **Open the ring** (break one frame block, explosion, place a block that punches a gap) → status `BROKEN_LOCAL`, matter removed **immediately**. Close the ring again → MULTI/AWAY return to `ACTIVE` if they already have a destination.
- **Break the control sign** on MULTI or AWAY → the portal is **deleted** (place a new `[Multi]` for a new random dest). PAIR is not deleted this way; it stays until `/mvp delete`.
- Breaking the **purple sheet** must **not** deactivate the portal. Only an open **frame** does.

---

## Nether portal fill (“matter”)

The opening should look like a **vanilla Nether portal**, not a wall of purple cubes.

**Intent** (`effects.matter.style: nether`, default)

- Fill every interior cell with a real `NETHER_PORTAL` block, oriented as a **thin animated sheet** (axis X or Z from the sign’s plane).
- The sheet must **not** send anyone to the Nether. Physics that would unform a non-obsidian ring must be cancelled. Water, buckets, and explosions must not wipe the sheet while the frame is closed.
- Vanilla portal particles come from the block itself; extra plugin swirls are skipped for this style.
- On plugin disable, every owned sheet block is set back to air so leftover vanilla portals do not remain.
- Styles `end` and `gateway` stay as BlockDisplay cubes. Real `END_PORTAL` would teleport instantly and is never placed.

Config: `effects.matter.enabled`, `effects.matter.style`, `effects.matter.particles`.

---

## Away (biome portals)

Same-server travel that **feels like vanilla Nether linking**, but between **overworld biomes**.

**How you build it**

- Line 1 of the sign is `Portal` / `Портал` / `传送门` (same keywords as other network portals; EN/RU/ZH).
- The **frame is this biome’s material** (oak logs on plains, sandstone in desert, packed ice on glaciers, pale oak in pale garden, …). Falling sand is not used as a frame.
- Line 2 blank or `Away` / `Авей` / `异界` → Away. The word is optional.
- Line 2 `Random` / `Случайный` / `随机` → **cross-server MULTI**, even if the frame is oak. Away must not steal that.

**Destination**

- Any **overworld** biome, including ocean and caves. Nether and End are excluded.
- First bind **rolls** a dest biome and may **auto-build** a small **4×5** return ring (`away.auto-build-return: true`) with the control sign on the **right** jamb.
- The **exit frame is built from the origin biome’s material** (an oak ring sitting in a pale garden is correct).
- Later uses of the **same origin portal** always go to the **same paired exit** (sticky, like a Nether portal). If that exit ring is broken, the next traveler **rebuilds it in the same place**.
- A **new** portal in biome B rolls again (may land in A, C, or a fresh biome G).
- If the dest biome already has an Away portal **linked back to the origin biome**, **reuse that one**. Do not spawn extra rings.

**After bind**

- Sign text becomes `Portal` / biome name with **vanilla biome colors**, not a hash color.
- Away is **not** published to the public catalog or map graph.

**If locate fails**

- Keep the sticky slot; land on the surface / an air pocket rather than unbinding.

**Wrong frame**

- Chat and action bar name the **required block** for this biome (and the one you used, if different).

**Config:** `away.*` and `portals.types.away` (the type flag wins when set).

---

## Guest home

Cross-server hops are often **one-way**. The dest world may have no portal pointing home.

**Intent**

- If the destination has **no return portal** for the origin, the arriving player is placed at a **local Random MULTI** (not spawn-only).
- For that player’s UUID, that portal is **home** for `travel.guest-home-seconds` (default **3600**). Stepping on it sends them back to the origin for that window.
- This is a **per-player overlay**. It must **not** rewrite the portal’s real sticky destination in the database (the overlay name is `"home"` and must not be persisted as the dest).
- Do not spawn a new portal if a usable Random MULTI already exists.

---

## Sticky Random MULTI and offline rebind

**Intent**

- `[Multi]` / `[Random]` / blank `Portal` (when Away does not apply) binds **once** to a live host and **stays** there until the player breaks the sign (or uses the dial button).
- `[To]` (IP or named server) and `[Pair]` are **never** rebound because the dest went offline.
- Optional `scanner.offline-rebind-days` (default **0** = off): only **sticky Random MULTI** may pick a new host after that many days unreachable.

The dial button next to a Random `[Multi]` is the **intentional** way to change dest (club peers first). It does not apply to `[To]` or `[Pair]`.

---

## Portal types you can turn off

All types are **on by default**. An operator can disable each one without removing the plugin.

| Key | What it is |
|-----|------------|
| `away` | Biome Away |
| `wool` | Local color wool (same server) |
| `multi` | Random other servers (catalog / scanners) |
| `pair` | Two-server pair |
| `to` | Fixed `[To]` IP or named server |

**Intent**

- Disabled type: cannot **create**, cannot **travel** (existing portals included).
- `portals.types.*` overrides legacy `away.enabled` / `local-portals.enabled` when the key is present.
- In-game: `/mvp settings type <name> on|off`.

---

## Who may create portals

**Intent**

- `portals.create: everyone` (default) — any player may build signs and wool.
- `portals.create: admin` — only OP / `multiverseportals.admin`.
- In-game: `/mvp settings create everyone|admin`.
- Legacy `open-network.everyone-can-create` is used only when `portals.create` is unset.

This is a **server policy**, not a per-type LuckPerms matrix. Wool still has optional `local-portals.use-permissions` for color keys **on top of** the everyone/admin gate.

---

## Local wool (same server)

ColorPortals-style idea: wall sign on the **right jamb** of a **closed wool ring of one color** (any size up to `max-frame-radius`; a small 3×4 doorway is only an example), name + channel, same color+channel form a loop. Walk into the purple sheet — no plate required.  
It is a **type** (`wool`) so an operator can run a public Multi network **without** local color portals, or the reverse.

---

## Quick “this is wrong if…”

| You see | Intended |
|---------|----------|
| Solid purple cubes in the hole | Thin vanilla Nether sheet |
| Walking through the sheet dumps you in the Nether | Only plugin travel (walk in / optional plate) |
| Breaking one purple block kills the portal | Only an **open frame** kills it |
| Giant closed circle never lights | Any closed ring within `max-frame-radius` lights |
| Oak `Portal` sign always becomes Away | Away only if the frame matches this biome; `Random` on line 2 is always MULTI |
| Away exit is pale oak in a plains origin | Exit uses **origin** material |
| Second Away in the same dest biome for the same origin | Reuse the existing paired ring |
| Guest overwrites a Random portal’s bound server | Overlay is per-player only, not saved as dest |
| Offline `[To]` silently points somewhere else | `[To]` / `[Pair]` stay; only Random may rebind if days &gt; 0 |
| Packed 2×3 rings with a shared pillar leak / purple floats above the sign | Each sign lights only its own hole |
