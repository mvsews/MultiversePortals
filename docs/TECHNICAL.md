# Multiverse Portals — technical reference

Deep notes for operators who already finished the [main README](../README.md).  
中文概览见 [README.zh.md](../README.zh.md)；русская версия: [README.ru.md](../README.ru.md)；каталог/хаб: [REGISTRY.md](REGISTRY.md).

---

## Requirements (why)

| Requirement | Required? | Why |
|-------------|-----------|-----|
| **Paper 1.21+** | Yes | Modern Paper API; cross-server hops use vanilla **Transfer** (1.20.5+) |
| Jar in `plugins/` | Yes | Soft-depends are optional |
| `accept-transfers=true` | **Yes to receive guests / public list** | Vanilla `server.properties` gate. Peers can *send* Transfer without it, but **your** server rejects the join. Plugin treats missing/`false` as **local-only** (no public catalog announce). |
| `server.public-host` / `public-port` | Optional | Empty/`auto` / `public-port: 0` → use `server-ip` + `server-port`. **Must** override when the join host **or external port** differs (Docker `-p`, NAT, reverse proxy). |
| Open TCP/UDP | Yes for public play | Transfer only retargets the client; NAT/firewall must allow joins |
| ViaVersion (+ Backwards/Rewind) | Recommended | Version bridge + compatibility checks |
| Geyser + Floodgate | Bedrock only | Bedrock uses Geyser transfer |
| Universal DB / JDBC | Hub catalog store | Open network catalog at `mp.mvse.ws` — [REGISTRY.md](REGISTRY.md) |
| `catalog-share` (default on) | Pull OK always | **Push/list** only when `server.list-publicly: auto` passes (public address + accept-transfers) |
| Bungee / Velocity | **Not required** | Transfer / Geyser, not proxy plugin channels |

### `accept-transfers` (vanilla)

```properties
# server.properties — required for inbound Multiverse Portals travel
accept-transfers=true
```

This is **not** a plugin option. Minecraft 1.20.5+ refuses Transfer joins when it is false/absent. Check live state with `/mvp settings` (`accept-transfers` + `catalog: public|local-only`).

### Public address resolve

Order for host: config `server.public-host` → `server-ip` / `Bukkit.getIp()` → local guess.  
Optional `server.discover-public-ip: true` queries ipify once if the host still looks LAN-only (off by default).  
Port: config `public-port` if &gt; 0, else `server-port` / `Bukkit.getPort()`.

**Docker / NAT:** Minecraft inside the container usually binds `25565`, while the host publishes another port (`docker run -p 25566:25565` → players use **25566**). Auto resolve advertises **25565** and a bridge IP like `172.17.0.x` → catalog stays **local-only** (private) or lists a dead port. Fix:

```yaml
server:
  public-host: "203.0.113.10"   # or your domain — not 172.17.*
  public-port: 25566            # host/NAT port players connect to
```

Same idea for router port-forward (WAN `25570` → LAN `25565`): set `public-port: 25570`.

### Local-only vs public catalog

| Situation | Catalog listing |
|-----------|-----------------|
| Private/LAN host (`127.*`, `10.*`, `192.168.*`, `172.16–31.*`, …) | **No** (local-only) |
| `accept-transfers` false/missing | **No** |
| `server.list-publicly: never` | **No** |
| Public host + accept-transfers + reachable from hub | **Yes** |
| `server.list-publicly: always` | Force announce (hub may still drop unreachable / wrong port) |

Local wool portals, `[Pair]`, and `[To]` by IP keep working in local-only mode.

---

## Feature matrix

| | |
|---|---|
| **Sign portals** | `[Multi]` / `[mvp]` / `[portal]` / `[Random]`, `[To]`, `[Pair]` |
| **Bind on create** | `[Multi]` sticky until sign break; survives restart |
| **Dial button** | Button by a random `[Multi]` sign rebinds destination (club peers first) |
| **Landing** | Cross-server arrivals stand on the return portal plate when known |
| **Travel claim** | Leaf servers without local federation claim pending landings from the hub over HTTPS |
| **Local wool portals** | ColorPortals-style ring TP on the same server |
| **Scanners** | MineScan + Cornbread + Slowstack (hub API key) → local SQLite score — [SCANNERS.md](SCANNERS.md) |
| **Public catalog** | Auto-join `https://mp.mvse.ws/mvp/v1` (HTTPS) — [REGISTRY.md](REGISTRY.md) |
| **Catalog share** | Gossip on by default (`catalog-share.enabled`) |
| **Bedrock** | Geyser UDP + protocol match from RakNet pong |
| **Inventory** | Off by default; enable per server via `/mvp settings export|import` |
| **Ingress** | Online cap, min-score, denylist, rate limit |
| **i18n** | `en` / `de` / `ru` / `zh` |

---

## Compatibility checks (before transfer)

1. **Java:** destination answers SLP and is not full  
2. **Bedrock:** Geyser UDP (`19132` / `19133` by default)  
3. **Bedrock protocol:** client == advertised MCPE protocol  
4. **Java versions:** native protocol + Via* range when known  

```yaml
scanner:
  enabled: true
  require-geyser-for-bedrock: true
  require-bedrock-protocol-match: true
  bedrock-ports: [19132, 19133]
  search-max-seconds: 45
  max-attempts: 12
```

---

## Commands

```text
/mvp help
/mvp version                         # installed vs latest (mp.mvse.ws)
/mvp ready [off]                     # one-way consent
/mvp lang en|de|ru|zh                # server fallback language
/mvp local list | import-colorportals
/mvp settings                        # map / guests / inventory summary
/mvp settings map on|off|auto
/mvp settings guests on|off
/mvp settings export|import on|off   # inventory transfer (alias: /mvp items …)
/mvp update                          # download jar to plugins/update/ (admin)
/mvp scanner                         # public pool status
/mvp ingress | deny | rep            # inbound limits
/mvp create | pair | multi | list | delete
/mvp info | peers | policy | trust
/mvp registry …                      # hub diagnostics only
```

**Player UX (no command):** pressure plate travels; a **button** next to a random `[Multi]` sign turns the dial (rebind).

---

## Config highlights

```yaml
language: en   # en | de | ru | zh

server:
  id: my-server
  display-name: ""          # empty / auto → Minecraft MOTD line 1
  public-host: ""           # empty → server-ip; set public IP/domain behind Docker/NAT
  public-port: 0            # 0 → server-port; set mapped host port if different (e.g. 25566)
  list-publicly: auto       # auto | always | never
  discover-public-ip: false

registry:
  enabled: false            # keep false — join the open catalog over HTTPS
  # federation-url: "https://mp.mvse.ws/mvp/v1"

federation:
  enabled: false            # true only if you run a catalog hub

catalog-share:
  enabled: true
  auto-bootstrap: true      # HTTPS → https://mp.mvse.ws/mvp/v1
  interval-seconds: 900     # ~15 min presence ping (also on portal create/bind)
  bootstrap-urls: []

scanner:
  bind-search-seconds: 90
  bind-require-geyser: true # Random portals prefer Geyser-capable hosts
  dial-recent-exclude-seconds: 300  # dial/Multi: skip hosts chosen in last 5 min
  avoid-duplicate-radius: 100  # nearby Random portals → different targets when possible
  flow-balance:
    live-ok-band: 2         # OK probes before flow-pick (lower = faster bind)
  hub-pool:
    enabled: true           # pull candidates from the public hub before scanners

open-network:
  everyone-can-create: true
  accept-inbound: true               # /mvp settings guests
  default-export-inventory: false    # /mvp settings export
  default-import-inventory: false    # /mvp settings import

ready:
  confirm: false            # if true, players need /mvp ready for one-way hops

effects:
  charge-ticks: 40
```

---

## Performance & weak servers

| Component | Thread | Impact |
|-----------|--------|--------|
| MineScan / Cornbread | async | TPS barely affected |
| Hub pool / catalog share | async | HTTPS only |
| `[Multi]` bind | async | Background probes once per portal |
| Travel after bind | async + short charge | ~2 s charge + transfer |
| Matter particles | main | Only near players (~20 blocks) |
| Local wool portals | main | Very light |
| Local SQLite | read pool + single writer | WAL — scanners don't block lookups |

**Light config:**

```yaml
scanner:
  refresh-seconds: 180
  sample-count: 20
  max-attempts: 10
  bind-search-seconds: 45
effects:
  matter:
    enabled: false
    particles: false
registry:
  enabled: false            # always false except central hub
```

Lightest: `[Pair]` only or local wool only. Heaviest: many `[Multi]` + matter + frequent rebind.

---

## Architecture (short)

- Local SQLite — portals / sessions / flags / MVP cache (read/write pools)  
- Public network — HTTPS catalog at `mp.mvse.ws` (Universal database behind the open API)  
- Portal edges pushed to the hub when portals bind/change (live map)  
- Soft APIs: Via*, Geyser, Floodgate (reflection)
- **Hub optional:** `mp.mvse.ws` outages do not disable the plugin. Catalog push/pull cools down; `[Multi]` binds fall back to public scanners + local SQLite; sticky Transfer still works. When the hub returns, share resumes automatically.

Deeper: [ARCHITECTURE.md](ARCHITECTURE.md) · hub: [REGISTRY.md](REGISTRY.md).

---

## Build

JDK **21** + Gradle:

```bash
./gradlew jar
# → build/libs/MultiversePortals-<version>.jar
```

Current release: **1.1.16** · site / jar: [https://mp.mvse.ws/](https://mp.mvse.ws/)

---

## Planned

| Item | Intent |
|------|--------|
| **Inter-server ping scoring** | During `[Multi]` bind / candidate ranking, measure RTT from this server to the destination (Java SLP or TCP probe timing), keep a small latency cache, and prefer lower-ping hosts so players land on nearer worlds when version/auth/Geyser still match. Club peers and dual-stack rules stay first; ping is an extra score signal, not a hard filter. |
| **Mod / modpack matching** | When this host runs a modded stack (Forge, Fabric, NeoForge, Quilt, …), detect loader + mod fingerprint (from server ping / plugin list / configured pack id) and prefer `[Multi]` destinations that advertise a compatible set — same modpack or high overlap — so players are not sent to vanilla (or a conflicting pack). Vanilla ↔ vanilla stays unconstrained; mismatch is soft-scored unless configured as hard reject. |

### Done recently

| Item | Notes |
|------|--------|
| **Balanced player flow** | Soft-rank `[Multi]` candidates by origin vs dest online (`scanner.flow-balance.*`). Busy origins prefer quieter dests; quiet origins prefer a moderate band; large soft penalty for mega online; small empty penalty. Club/Geyser tiers unchanged. Ops: `/mvp bindpreview` shows `online` + `flowScore`; A/B via `enabled: false` + `/mvp reload`. |
| **Hub transfer score** | Central `registry_scanner_hosts.score` from Transfer outcomes: ARRIVED `+transfer-success-score` (default 20), bounce `−transfer-fail-score` (default 40). SLP probes refresh status only (`probe-affects-score: false`). |
| **Dial recent exclude** | Button / Multi bind skips hosts chosen in the last `dial-recent-exclude-seconds` (default 300) so the dial does not flip hub ↔ one peer. Soft-fallback if the fresh pool is empty. Faster binds: `flow-balance.live-ok-band` (default 2), merge hub `probe_cache` into the candidate pool. |
| **Club-then-public bind** | Multi/dial always pulls central catalog + hub-pool first. Probes MVP club peers as their own phase; nearby-duplicate / recent-exclude skip a host and continue. After the club tier is exhausted, bind falls through to public scanners — duplicate reuse is last-resort only when no other live target exists. |
| **Sparse hub mesh** | Hub `listMultiTargets` / catalog export prefer MVP peers with fewer ACTIVE portal links (`hubLinks`). Score ≈ `100 − 12×degree`. Club bind order uses the same sparsity before public flow-balance. |

Scanners overview / discovery roadmap: [SCANNERS.md](SCANNERS.md).

#### Tunable weights (`scanner.flow-balance`)

| Key | Default | Role |
|-----|---------|------|
| `enabled` | `true` | Off → old shuffle-within-tier |
| `busy-at` / `quiet-at` | `12` / `4` | Origin mode thresholds |
| `mega-online` / `mega-penalty` / `mega-penalty-per-extra` | `80` / `80` / `0.5` | Soft hit on giants |
| `empty-penalty` | `8` | Soft hit when dest online is 0 |
| `mega-hard-cap` | `false` | If true, skip non-club mega hosts |
| `quiet-target-min` / `quiet-target-max` | `3` / `25` | Quiet-origin sweet band (`min: 0` + `empty-penalty: 0` to allow empties) |
| `near-tie-epsilon` | `2.0` | Shuffle near-ties inside a tier |
| `live-ok-band` | `2` | Live OK probes before flow-pick (lower = faster `[Multi]` bind) |

#### Local catalog (`scanner.catalog`)

| Key | Default | Role |
|-----|---------|------|
| `max-entries` | `2500` | Cap local `probe_cache` (`0` = unlimited) |
| `prune-batch` | `200` | Max rows deleted per prune |
| `protect-ok-min-score` | `20` | High-score OK rows evicted after DEAD/stale |

#### Hub transfer score (`scanner.hub-pool`)

| Key | Default | Role |
|-----|---------|------|
| `transfer-success-score` | `20` | Added when a hop ARRIVED on dest |
| `transfer-fail-score` | `40` | Subtracted on bounce-back (player returned to origin) |
| `probe-affects-score` | `false` | If true, SLP probe OK/DEAD also nudge score (legacy) |

Ops compare checklist: set `flow-balance.enabled: false` → `/mvp reload` → `/mvp bindpreview` → enable again → preview → compare top ranks.

---

## Publish status

| Platform | Link |
|----------|------|
| Website | [mp.mvse.ws](https://mp.mvse.ws/) |
| Hangar | [mvse/MultiversePortals](https://hangar.papermc.io/mvse/MultiversePortals) |
| Modrinth | [multiverseportals](https://modrinth.com/project/multiverseportals) |
| CurseForge | [multiverse-portals-mvse](https://www.curseforge.com/minecraft/bukkit-plugins/multiverse-portals-mvse) |
| GitHub Releases | [Releases](https://github.com/mvsews/MultiversePortals/releases) |

---

## Credits

Local wool portals reproduce the UX of **[Color Portals](https://github.com/snowgears/Color-Portals)** by SnowGears as our own implementation (not a source fork), plus cross-server `[Multi]` / `[To]` / `[Pair]`.
