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
| `server.public-host` / `public-port` | Optional | Empty/`auto` / `public-port: 0` → use `server-ip` + `server-port`. Override when the join hostname differs. |
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

### Local-only vs public catalog

| Situation | Catalog listing |
|-----------|-----------------|
| Private/LAN host (`127.*`, `10.*`, `192.168.*`, …) | **No** (local-only) |
| `accept-transfers` false/missing | **No** |
| `server.list-publicly: never` | **No** |
| Public host + accept-transfers + reachable from hub | **Yes** |
| `server.list-publicly: always` | Force announce (hub may still drop unreachable) |

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
| **Scanners** | MineScan + Cornbread (+ optional Slowstack) → local SQLite score |
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
  public-host: ""           # empty → server-ip; or set play.example.com
  public-port: 0            # 0 → server-port
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
  avoid-duplicate-radius: 100  # nearby Random portals → different targets when possible
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

Deeper: [ARCHITECTURE.md](ARCHITECTURE.md) · hub: [REGISTRY.md](REGISTRY.md).

---

## Build

JDK **21** + Gradle:

```bash
./gradlew jar
# → build/libs/MultiversePortals-<version>.jar
```

Current release: **1.1.15** · site / jar: [https://mp.mvse.ws/](https://mp.mvse.ws/)

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
