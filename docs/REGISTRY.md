# Multiverse Portals — Registry & catalog hub

## TL;DR for server admins

Install the jar, set vanilla `accept-transfers=true`, leave plugin defaults:

```yaml
registry:
  enabled: false          # leave OFF

server:
  public-host: ""         # auto from server-ip; set public IP/domain behind Docker/NAT
  public-port: 0          # auto from server-port; set host publish port if mapped (e.g. 25566)
  list-publicly: auto     # LAN / Docker bridge / no accept-transfers → local-only (not listed)

catalog-share:
  enabled: true
  auto-bootstrap: true    # talks to https://mp.mvse.ws/mvp/v1
```

Public listing happens over **HTTPS** only when the address looks public and Transfer inbound is allowed. Otherwise the plugin stays **local-only** (wool / Pair / `[To]` by IP). The hub also skips private or unreachable announces.

If you publish Docker as `-p 25566:25565`, set `public-port: 25566` and a non-private `public-host` — auto resolve would advertise container `25565` / `172.17.*` and stay off the map. Details: [TECHNICAL.md](TECHNICAL.md#public-address-resolve).

When your portals bind or change, their edges are pushed to the hub so the [live map](https://mp.mvse.ws/) shows connectivity.

Player install: [README.md](../README.md) · config notes: [TECHNICAL.md](TECHNICAL.md).

Opt out of the public hub: `catalog-share.auto-bootstrap: false`, `bootstrap-urls: [none]`, or `server.list-publicly: never`.

---

## Layers

| Layer | What | Who runs it |
|-------|------|-------------|
| **Universal database** | Shared open directory of the portal network | Operated at `mp.mvse.ws`; everyone joins via HTTPS |
| **HTTP catalog** | `POST /mvp/v1/catalog/export` (+ announce) | Public — domain → federation API |
| **catalog-share** | Pull/push club list + portal edges over HTTPS | Every install (default **on**) |

The Universal database is the network’s shared catalog store. Access is over the public HTTPS API.

```
Your server  --HTTPS-->  https://mp.mvse.ws/mvp/v1
Hub edge     --proxy-->  local federation API
Hub plugin   --------->  Universal database
```---

## Public hub

- Landing (idea + jar + live graph): [https://mp.mvse.ws/](https://mp.mvse.ws/)
- Versioned jar: [https://mp.mvse.ws/download/MultiversePortals-1.1.15.jar](https://mp.mvse.ws/download/MultiversePortals-1.1.15.jar)  
  (stable alias: `/download/MultiversePortals.jar`)
- Version metadata: [https://mp.mvse.ws/version.json](https://mp.mvse.ws/version.json)
- API: `https://mp.mvse.ws/mvp/v1`

In-game: `/mvp version` shows installed vs latest.

Typical edge TLS: reverse proxy / CDN in front of the origin. Origin should not redirect HTTP→HTTPS incorrectly for the API path.

On normal servers use the website graph / catalog API. Hub operators have in-game `/mvp registry …` for local diagnostics.

In JSON `/catalog/export` each server has presence fields (`lastSeenAt`, `lastPingAgeSec`, `lastPingAgo`), a `caps` block (no full plugin list), and a `portals` array (graph edges). Offline peers on the map are shown muted when the last ping is stale (or after a graceful shutdown notify).

---

## Portal comments (signs)

Near a federation portal, players can hang extra signs (not `[Multi]`/`Portal`).  
Within 3 blocks those texts are published as `signs: ["…"]` in the catalog / API.

```bash
curl -s https://mp.mvse.ws/mvp/v1/portals
curl -s 'https://mp.mvse.ws/mvp/v1/portals?serverId=YOUR_SERVER_ID'
```

On every public server, portal edges are announced to the hub when portals bind/create/delete (and on the catalog-share interval).

---

## Hub operators (optional)

To run your own catalog hub: enable `registry.enabled: true` on one host, put a reverse proxy in front for HTTPS, and point peers at your API. Do **not** put real DB passwords in git.
---

## What is shared (via HTTPS catalog)

- `serverId`, `displayName`, `publicHost`, `publicPort`, `federationUrl`, `mcVersion`, scores/timestamps  
- Presence timestamps and online/offline for the map  
- Branding: MOTD → name/description, `server-icon.png` → icon (`GET /mvp/v1/icon/{id}`)  
- Portal graph: world/xyz, type, dest host/serverId, `returnCapable` (no secrets)  
- **Not** shared: peer secrets, inventory contents, local scanner probe cache, database credentials

Pull runs every `interval-seconds` (default **900** / ~15 min). Extra announce also fires when a portal is created or bound. Bind / `[Multi]` prefer: **hub pool → known club → scanners**.

Servers without a hub ping for about **1.5 hours** (configurable) are treated as offline on the map.

## Toggle

- `catalog-share.enabled: false` — no hub / P2P pull (scanners still work if enabled)  
- Default is **share on**
