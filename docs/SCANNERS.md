# Minecraft server scanners for MultiversePortals

Review date: **2026-07-23** (live API re-check)  
Purpose: **discovery** sources (host:port lists) → local `probe_cache` → Java SLP + Bedrock RakNet (Geyser).

## Used by the plugin today

`ScannerHub` merges these three public sources (fail-open; order is merge, not strict priority):


| Source        | Client                | Config                                  | Notes                                   |
| ------------- | --------------------- | --------------------------------------- | --------------------------------------- |
| **MineScan**  | `MineScanClient`      | `scanner.base-url` + shared `scanner.`* | No API key                              |
| **Cornbread** | `CornbreadScanClient` | `scanner.cornbread.`*                   | No API key; strong on non-default ports |
| **Slowstack** | `SlowstackScanClient` | `scanner.slowstack.`*                   | Requires `scanner.slowstack.api-key`    |


The public catalog at [`mp.mvse.ws`](https://mp.mvse.ws/) is an optional accelerator. If it is unreachable, servers still use these scanners + local SQLite (fail-open since **1.1.16**).

Slowstack needs `scanner.slowstack.api-key` on any host that should pull it directly. Catalog operators may set the key on the hub; other servers can leave it empty and still use MineScan + Cornbread (and the hub pool when healthy).

---

## How MultiversePortals picks a destination

Public scanners only give **Java** `host:port` lists. We always:

1. **Java SLP** — is the TCP port alive, version branch OK, not full / dead  
2. **Bedrock RakNet** (optional) — probe Geyser UDP on the same host (`19132` / `19133` by default, plus any known bedrock port)  

There is no separate “Bedrock-only” feed: dual-stack means **Java TCP + Geyser UDP on one IP**.

### Bind (`[Multi]` create — who built the portal)

Candidate order: **club peers (servers with MultiversePortals) → hub pool / scanners**.  
Within each tier, **confirmed Geyser (Java + Bedrock)** ranks above Java-only when dual-stack is preferred.

| Creator client | What we lock the portal to |
|----------------|----------------------------|
| **Bedrock** | Host **must** have Geyser (stable UDP + matching Bedrock protocol). Pure Java is skipped. |
| **Java** | Prefer **Java + Geyser**. Club peers may bind **Java-only**. With default `bind-require-geyser: true`, public scanner hosts must be dual-stack (no fallback to pure Java from scanners). |

Sticky bind stores both ports when Geyser was found (`javaPort` + Bedrock transfer port).

### Travel (who stands on the plate later)

Platform is decided by the **traveler**, not the creator:

| Traveler | Transfer target |
|----------|-----------------|
| **Java** | Java TCP of the bound host |
| **Bedrock** | Geyser UDP of the same host |

No cross-routing (Java never goes to the Bedrock port; Bedrock never joins via Java TCP alone).  
If a Bedrock player steps on a portal bound to a **Java-only** host → in-game message that the destination does not support their client (`bedrock-dest-java-only`).  
If a Java player uses a portal opened by Bedrock → normally fine (that bind already required dual-stack).

### What scanner APIs should expose (for us)

Useful fields beyond a raw IP list: exact **version** (not only `1.7+`), **onlineMode** / whitelist, **lastSeen**, player counts, and — if known — **Geyser / Bedrock UDP port** (and Bedrock protocol), plus **mod loader / mod list / modpack** when the ping exposes it. Random/discovery samples should not be dominated by marketing mega-networks. Optional **latency / region** hints help us score nearer destinations (planned: origin→dest ping scoring at bind time).

---



## Scoring criteria (our use case)


| Criterion                                     | Weight | Why                                                 |
| --------------------------------------------- | ------ | --------------------------------------------------- |
| Public HTTP API + **discovery** (list/random) | ★★★★★  | Need a pool, not single-host lookup                 |
| **version** / protocol filter                 | ★★★★★  | Paper 1.21+ / Via compatibility                     |
| players / online-mode / country filters       | ★★★★   | Less junk before probe                              |
| Freshness (rescans)                           | ★★★★   | Otherwise DEAD in catalog                           |
| Rate limits / stability                       | ★★★★   | Refresh ~every 90s                                  |
| Non-default ports                             | ★★★    | Cornbread is strong here                            |
| Bedrock / Geyser in response                  | ★★     | Almost nobody exposes this — we probe UDP ourselves |
| Free / simple ToS                             | ★★★★   | Open-network plugin                                 |
| Not a marketing listing                       | ★★★★   | Hypixel-class targets are useless for Random Multi  |


**Do not confuse:**

- **Scanner / DB** — found servers on the public IPv4 space → returns a list  
- **Listing** — user-submitted tops (few “raw” IPs)  
- **Status API** (mcsrvstat, mcstatus.io) — ping a **known** host → almost useless for discovery

---



## Ranking (summary)


| #   | Source                                                        | Type                     | Version filter                               | Live API (2026-07-23)                                                                     | Score      | Status                                   |
| --- | ------------------------------------------------------------- | ------------------------ | -------------------------------------------- | ----------------------------------------------------------------------------------------- | ---------- | ---------------------------------------- |
| 1   | **Cornbread**                                                 | Masscan + rescan         | yes (`version` on random)                    | `200` `/v1` and `/v2/servers/random`                                                      | **9/10**   | **in plugin** (uses `/v1`)               |
| 2   | **Slowstack**                                                 | IPv4 scan + REST         | yes (`version` + players/country/onlineMode) | Bearer, [docs](https://slowstack.tv/docs/api-reference/servers/)                          | **9/10**   | **in plugin** (needs `api-key`)          |
| 3   | **MineScan**                                                  | Public DB + random       | yes (`version`)                              | `200` `/servers/random` (`count` ≤ 20)                                                    | **7.5/10** | **in plugin**                            |
| 4   | **BreakBlocks** ([breakblocks.com](https://www.breakblocks.com/)) | Scanner + REST        | yes (`version`, country, …)                  | Docs: `/api/v0.1/servers/find`; root `200`; find often **timeout/429** after migration    | **8/10***  | **next candidate** — watch stability     |
| 5   | **MCScans** ([mcscans.fi](https://mcscans.fi/))               | Large DB (~195k)         | UI + docs: version/software                  | Docs exist; **search endpoints currently 404**; landing shows lookup `GET /api/v1/server` | **6/10***  | wait for working list/search             |
| 6   | **ServerBuddy** ([serverbuddy.net](https://serverbuddy.net/)) | Listing ~3k              | weak (Via ranges `1.7+`)                     | `200` `GET /api/servers`                                                                  | **4/10**   | low — tops are Hypixel/SunRealms         |
| 7   | **Minecraft Servers HQ**                                      | Listing ~35k             | on site                                      | no public discovery API found                                                             | **3.5/10** | do not integrate                         |
| 8   | **McScan.org**                                                | Paid SaaS                | strong UI filters                            | trial / paywall                                                                           | **3/10**   | skip (paid)                              |
| 9   | **KittyScan**                                                 | Scan + honeypot/security | UI stats                                     | **no** discovery API                                                                      | **2/10**   | not for Multi pool                       |
| 10  | **ServerSeeker V2**                                           | Self-host / Discord      | strong DSL filters                           | `serverseeker.net` timed out (2026-07-23); V2 is self-host                                | **7/10**   | self-host / later                        |
| 11  | **nmcscan**                                                   | Self-host Java+Bedrock   | DSL + `type:bedrock`                         | own instance                                                                              | **8/10***  | self-host only                           |
| 12  | Status / closed nets                                          | ping or Minehut-only     | —                                            | mcsrvstat / mcstatus.io / Minehut API                                                     | **2–3/10** | not open IPv4 discovery                  |


 Score assumes a working discovery endpoint; without it, lower.

---



## In the plugin (verified)



### Cornbread — [https://www.cornbread2100.com/server-scanner](https://www.cornbread2100.com/server-scanner)

- Endpoint: `GET https://api.cornbread2100.com/v1/servers/random?limit=&minPlayers=&version=`
- Live: **200** (probe 2026-07-23)
- Pros: volume, **non-default ports**, version, lastSeen, MOTD  
- Cons: some stale rows → we cut with probe; Geyser not marked  
- Config: `scanner.cornbread.*` (default `enabled: true`)



### MineScan — [https://data.minescan.xyz](https://data.minescan.xyz)

- Endpoint: `GET https://data.minescan.xyz/servers/random?count=&minPlayers=&version=`
- Live: **200**; `count` max **20** (else 422)
- Pros: simple random + version  
- Cons: small batch, occasional 429  
- Config: `scanner.base-url` / shared `scanner.*`



### Slowstack — [https://slowstack.tv/](https://slowstack.tv/)

- Endpoint: `GET https://slowstack.tv/api/v1/servers`  
Query: `limit`, `offset`, `version`, `onlineOnly`, `minPlayers`, `maxPlayers`, `countryCodes`, `onlineMode`, `whitelisted`, `search`…  
Auth: `Authorization: Bearer <KEY>` · **60 req / 60 s**  
Docs: [https://slowstack.tv/docs/getting-started/](https://slowstack.tv/docs/getting-started/) · [https://slowstack.tv/docs/api-reference/servers/](https://slowstack.tv/docs/api-reference/servers/)
- Pros: best filter set among ready-made APIs; IP as int → client converts  
- Cons: empty `api-key` → client **skips** startup  
- Config:

```yaml
scanner:
  slowstack:
    enabled: true
    base-url: "https://slowstack.tv/api/v1"
    api-key: "YOUR_KEY"   # never commit secrets
    online-only: true
    country-codes: ""     # optional e.g. US,DE,NL
```

Put the key only in the live server `config.yml` (or a secrets file outside git). Do not publish API keys in the repository.

---



## Other scanners reviewed

### BreakBlocks — [https://www.breakblocks.com/](https://www.breakblocks.com/)

Closest thing to “missed gold” in the 2026-07-23 sweep.

- Product: **Minecraft Server Seeker v2.0** (scanner index + web UI)  
- API root live: `GET https://api.breakblocks.com/` → `phantom-crawler` **1.0.2**  
- Docs: [overview](https://www.breakblocks.com/docs/overview) · [endpoints](https://www.breakblocks.com/docs/endpoints) · [rate limits](https://www.breakblocks.com/docs/rate-limiting)  
- Discovery: `GET https://api.breakblocks.com/api/v0.1/servers/find?version=&country=&limit=`  
  Optional `Authorization: Bearer <API_KEY>` (higher limits); anonymous ~20 req/min  
- Also: `/status/ping/:ip/:port`, `/servers/versions`, `/servers/countries`, `/servers/regions`  
- Site note (2026-03): recovering after hosting migrations  

**Live probe (2026-07-23):** API root **200**; documented `/servers/find` often **timeout** (0 bytes) or generic paths **429**. Docs and response shape look right for Multi; runtime stability is the gate.

**Verdict:** **#1 next integration candidate** once `servers/find` returns reliably with `version` + non-mega-network samples. Worth a short collaboration ping (see Collaboration).

### MCScans — [https://mcscans.fi/](https://mcscans.fi/)

- UI scale: ~195k servers; filters version / Java|Bedrock / auth / software  
- Docs: [https://mcscans.fi/docs](https://mcscans.fi/docs) — public API claimed (60/min, 1000/h), version/software filters, regex search  
- Landing shows **lookup** `GET /api/v1/server` (single host)  
- Live probe: `mcscans.fi/api/v1/servers`, `/search`, `api.mcscans.fi/v1/*` → **404**

**Verdict:** high potential, but **discovery list/search is not available** right now (or path differs / needs auth).  
Integrate only after a working `list|search|random` with `version=`.

### ServerBuddy — [https://serverbuddy.net/](https://serverbuddy.net/)

- Live: `GET https://serverbuddy.net/api/servers` → **200**, ~3k servers  
- Fields: host/port, edition, playersOnline, version labels (`1.7+ supported`, `versionLine`)  
- Filter `?version=1.21` → empty; `versionLine=1-21` still returns mega-networks with Via `1.7+`  
- Typical tops: Hypixel, SunRealms, DonutSMP — **bad Random Multi targets**

**Verdict:** low priority. If ever — only with large-network blacklist + hard `playersOnline` cap.

### Minecraft Servers HQ — [https://minecraftservershq.com/](https://minecraftservershq.com/)

- Listing / analytics (~35k), refresh ~10 min  
- No public discovery API found

**Verdict:** not for `ScannerHub`.

### McScan.org — [https://mcscan.org/](https://mcscan.org/)

- Strong UI (version, protocol, country, cracked, proxy…)  
- Model: **paid** access / trial

**Verdict:** do not integrate into an open-network plugin without explicit payment and ToS review.

### KittyScan — [https://kittyscan.com/](https://kittyscan.com/)

- Full-IPv4 scan + honeypot + security tooling (blocklist, KittyPaper)  
- **No** public discovery API for a portal pool  
- Useful to admins as a security resource, not as a Multi source

**Verdict:** do not integrate.

### ServerSeeker / kgurchiek / Cornbread v2 (ecosystem notes)

- Classic **serverseeker.net** public API: **timed out** (2026-07-23) — treat as gone for plugin use  
- **ServerSeeker V2** ([Funtimes909](https://github.com/Funtimes909/ServerSeekerV2)): self-host / Discord — same as before  
- **kgurchiek** scanner Discord bot / API: open-source self-host; the public bot template now points at **Cornbread** `https://api.cornbread2100.com/v2` (same DB family we already use via `/v1`)  
- Cornbread **`/v2/servers/random`** and `/v2/servers` are live (`200`) — same source, not a fourth independent feed

### Also checked (no Multi win)

| Source | Why skip |
|--------|----------|
| **Shodan / Censys / FOFA** | Paid keys; not free discovery for an open plugin |
| **Apify Minecraft Server Scanner** | Pay-per-subnet masscan actor — expensive, not a shared DB |
| **Minehut** `api.minehut.com/servers` | Closed hosted network (~1k), not public IPv4 |
| **minecraft-mp.com / craftlist / vote lists** | Marketing listings / vote APIs, not scanners |
| **matdoes “Scanning Inc”** | Blog / honeypot story — no public discovery API |
| **mc-radar, pebnn/mcscan, SieBRUM discovery** | Self-host tools only |
| **Status-only** (mcsrvstat / mcstatus.io) | Ping known host — not discovery |

### ServerSeeker V2 / nmcscan / mc-radar

Still: powerful, but **self-host** (or Discord). Worth it only if we run our own index (especially nmcscan with `type:bedrock`).

### Status-only (mcsrvstat / mcstatus.io)

Ping known hosts only — not discovery. Not used by the plugin for Multi.

---



## Recommendations



### Do now

1. Keep **Cornbread + MineScan** always on  
2. Enable **Slowstack** with a real `api-key` on hosts that should query it (typically the catalog hub at `mp.mvse.ws`)  
3. Do not add clients “for the list” — fail-open is already covered by three sources + local SQLite



### Next candidate (one)

- **BreakBlocks** — documented `/servers/find` with `version` / country; integrate when the endpoint is stable (today: timeouts after their hosting migration)  
- Fallback: **MCScans** — when search/list with version comes back


### Do not

- KittyScan / MSHQ / McScan.org (no API / listing / paywall)  
- ServerBuddy without hard filtering (large-network noise)  
- Shodan / Censys / Apify / Minehut as Multi discovery  
- Status-only APIs as discovery



### Filters that actually cut junk

```yaml
scanner:
  enabled: true
  sample-count: 40
  min-players: 1
  version-prefix: ""          # empty → this server's branch (1.21)
  exclude-whitelist: true
  auth-mode: online
  require-geyser-for-bedrock: true
  bedrock-ports: [19132, 19133]
```

**Important:** almost no public scanner guarantees Geyser.  
Discovery = Java list → **our** `ServerProbe` / RakNet filters.

---



## Collaboration

We are **open to collaborating with scanner / server-index projects**.

**What hurts MultiversePortals search most:** stale or dead hosts in random feeds, Via-style mega-networks (`1.7+`) that look “compatible” but are useless for Transfer (and dump everyone onto the same giants), missing or weak filters for exact version branch / online-mode / whitelist, and almost no signal for Geyser/Bedrock — so we must re-probe every candidate ourselves and burn time before a player lands. Scanner authors who expose fresh, filterable discovery (`version`, `onlineMode`, players, lastSeen, optional Geyser/UDP ports) and keep marketing tops out of “random” samples help the open network pick better destinations with less waste — including more even player flow between small and large servers.

If you run a Minecraft scanner, directory, or discovery API and want better cross-server connectivity, we would like to work with you on:

- richer, fresher discovery feeds for MultiversePortals `[Multi]` destinations  
- better destination matching for players (version, auth, region, activity, Bedrock/Geyser signals)  
- mutual attribution / docs links where useful

Contact via the project site or repository (see README). Goal: denser, fairer random travel across the open Minecraft network — not marketing tops of mega-servers.

---



## Roadmap


| Step                              | Status                                         |
| --------------------------------- | ---------------------------------------------- |
| Cornbread + MineScan              | ✅                                              |
| Slowstack client                  | ✅ code; key set via live `config.yml` (not in git) |
| Hub fail-open → scanners          | ✅ 1.1.16                                       |
| BreakBlocks client                | ⏸ wait for stable `/servers/find`              |
| MCScans client                    | ⏸ wait for working discovery                   |
| **Inter-server ping scoring**     | ⏸ measure origin→dest RTT (SLP/TCP); prefer low-latency binds for player comfort |
| **Mod / modpack matching**        | ⏸ if this server runs mods (Forge/Fabric/…), prefer destinations with a compatible mod set / same modpack signal |
| **Balanced player flow**          | ✅ soft score by online (`scanner.flow-balance`); `/mvp bindpreview` shows flowScore |
| **Local catalog cap**             | ✅ prune SQLite `probe_cache` (`scanner.catalog`); hub MySQL uncapped |
| ServerBuddy (optional)            | ⏸ low priority                                 |
| Self-host nmcscan (Bedrock index) | ⏸ only if needed                               |


---



## Links


| Project              | Link                                                                                                                                                                             |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| BreakBlocks | https://www.breakblocks.com/ · [API overview](https://www.breakblocks.com/docs/overview) · `api.breakblocks.com` |
| Slowstack            | [https://slowstack.tv/](https://slowstack.tv/) · [getting started](https://slowstack.tv/docs/getting-started/) · [servers API](https://slowstack.tv/docs/api-reference/servers/) |
| MineScan data        | [https://data.minescan.xyz](https://data.minescan.xyz)                                                                                                                           |
| Cornbread scanner    | [https://www.cornbread2100.com/server-scanner](https://www.cornbread2100.com/server-scanner)                                                                                     |
| Cornbread API        | [https://api.cornbread2100.com/v1](https://api.cornbread2100.com/v1)                                                                                                             |
| MCScans              | [https://mcscans.fi/](https://mcscans.fi/) · [docs](https://mcscans.fi/docs)                                                                                                     |
| ServerBuddy          | [https://serverbuddy.net/](https://serverbuddy.net/) · API `/api/servers`                                                                                                        |
| Minecraft Servers HQ | [https://minecraftservershq.com/](https://minecraftservershq.com/)                                                                                                               |
| McScan.org           | [https://mcscan.org/](https://mcscan.org/)                                                                                                                                       |
| KittyScan            | [https://kittyscan.com/](https://kittyscan.com/)                                                                                                                                 |
| ServerSeekerV2       | [https://github.com/Funtimes909/ServerSeekerV2](https://github.com/Funtimes909/ServerSeekerV2)                                                                                   |
| nmcscan              | [https://github.com/ntech-org/nmcscan](https://github.com/ntech-org/nmcscan)                                                                                                     |
| mcsrvstat            | [https://api.mcsrvstat.us](https://api.mcsrvstat.us)                                                                                                                             |
| mcstatus.io          | [https://mcstatus.io/docs](https://mcstatus.io/docs)                                                                                                                             |


---



## Changelog (this file)


| Date       | Change                                                                                                                                                  |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-07-22 | First ranking; #1 = Slowstack                                                                                                                           |
| 2026-07-22 | Slowstack integrated (`SlowstackScanClient`)                                                                                                            |
| 2026-07-23 | Live API probes: Cornbread/MineScan/ServerBuddy OK; MCScans search 404; updated KittyScan / MSHQ / McScan.org / ServerBuddy; Slowstack marked in-plugin |
| 2026-07-23 | Extra sweep: **BreakBlocks** documented discovery API (next candidate); Cornbread `/v2` live (same DB); serverseeker.net down; Shodan/Censys/Apify/Minehut checked & skipped |
| 2026-07-23 | Roadmap: inter-server ping scoring for low-latency `[Multi]` binds |
| 2026-07-23 | Roadmap: mod/modpack matching for modded origin servers |
| 2026-07-23 | Roadmap: balanced player flow (busy→quiet, quiet→moderate, avoid giants) |
| 2026-07-23 | Balanced player flow + local `probe_cache` catalog cap shipped (`flow-balance` / `catalog`) |


