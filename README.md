<p align="center">
  <img src="assets/banner.png" alt="Multiverse Portals" width="100%">
</p>

<h1 align="center">Multiverse Portals</h1>

<p align="center">
  <strong>Many servers.<br>One portal network.</strong>
</p>

<p align="center">
  <a href="https://mp.mvse.ws/"><strong>Website &amp; jar → mp.mvse.ws</strong></a><br>
  <a href="https://hangar.papermc.io/mvse/MultiversePortals">Hangar</a> ·
  <a href="https://modrinth.com/project/multiverseportals">Modrinth</a> ·
  <a href="https://www.curseforge.com/minecraft/bukkit-plugins/multiverse-portals-mvse">CurseForge</a> ·
  <a href="https://github.com/mvsews/MultiversePortals/releases">GitHub</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh.md">简体中文</a> ·
  <a href="portal_guide.en.md">Player guide</a> ·
  <a href="docs/TECHNICAL.md">Technical</a>
</p>

---

## What is this?

A **Paper 1.21+** plugin that links independent Minecraft servers with portals — **no Bungee or Velocity**.

Players build a portal from a **sign + pressure plate**. The game uses the vanilla **Transfer** packet (and Geyser for Bedrock). Your world can send players out **and** receive guests from the public network.

**In-game language:** `/mvp lang en|de|ru|zh`

---

## Install in 5 minutes

1. Drop **`MultiversePortals-*.jar`** into `plugins/` (filename includes the version)  
   → download: [mp.mvse.ws](https://mp.mvse.ws/)
2. In **`server.properties`** (vanilla Minecraft setting — not the plugin):
   ```properties
   accept-transfers=true
   ```
   **Why:** Transfer from another world only works if *your* server allows inbound transfers.  
   Without this flag guests are rejected at join, and the plugin stays in **local-only** mode (no public catalog listing).
3. Restart. The plugin reads **`server-ip`** / **`server-port`** automatically for your public address.  
   Override only if players join you via a different hostname:
   ```yaml
   # plugins/MultiversePortals/config.yml — optional
   server:
     public-host: "play.example.com"   # empty = auto from server-ip
     public-port: 0                    # 0 = server-port
   ```

That’s enough for **local wool portals**, `[Pair]` / `[To]` by IP, and — if the host is reachable from the internet and `accept-transfers=true` — listing on [mp.mvse.ws](https://mp.mvse.ws/).

**Local-only (default when closed):** LAN / private IP, or `accept-transfers=false` → not shown in the public catalog. Force with `server.list-publicly: never`.

Optional: ViaVersion, Geyser + Floodgate.

> **Tip:** leave `server.display-name` empty to use your Minecraft MOTD as the public name.

---

## Portal types (signs)

First line of the sign = type. Put a **pressure plate** next to it.

| You write | What happens |
|-----------|----------------|
| `[Multi]` or `[mvp]` or `[portal]` | Finds a live server once and **sticks** to it until you break the sign |
| `[To]` + IP / port (or `server-id`) | Always goes to that destination |
| `[Pair]` | Creates a code — use the same code on the other server for a round-trip |

**Dial:** a button next to a random `[Multi]` sign rebinds the sticky destination (club MVP peers first). Does not apply to `[To]` / `[Pair]`.

Sign status while working: `Portal` → `Scan...` → short destination name + `->` (one-way) or `<->` (pair).

For public one-way hops, players run **`/mvp ready`** once.

---

## Link two of your servers (round-trip)

Best option: **`[Pair]`**.

**Server A**

1. Frame + sign + plate  
2. Line 1: `[Pair]` (line 2 empty)  
3. Copy the **code** from chat / sign  

**Server B**

1. Same setup  
2. Line 1: `[Pair]`  
3. Line 2: that **code**  

When both show `<->`, stepping on either side lands at the other portal and you can walk back.

Both servers need `accept-transfers=true` and a real `public-host`. They join the same network automatically via [mp.mvse.ws](https://mp.mvse.ws/).

<details>
<summary>Alternative: by IP on the sign</summary>

| Line | Text |
|------|------|
| 1 | `Portal` |
| 2 | `1.2.3.4:25565` |

Or just the address on line 2 if the port is the default `25565`. Legacy: host on line 2 and port on line 3 also works. For a true round-trip, build a return portal on the other side too.
</details>

---

## Local portals (same server)

Wool frame + wall sign (ColorPortals-style):

1. **3×4 wool** of one color, sign on the top middle block  
2. Line 1 = **name**, line 2 = **channel** (`0`–`9999`)  
3. Plate inside — same color + channel = a ring of warps on **this** server  

`/mvp local list` · admins can import old ColorPortals data with `/mvp local import-colorportals`.

---

## Useful commands

| Command | For |
|---------|-----|
| `/mvp version` | Installed vs latest on mp.mvse.ws |
| `/mvp ready` | Allow public one-way travel |
| `/mvp lang …` | Server fallback language |
| `/mvp settings` | Map listing, guests, inventory transfer |
| `/mvp help` | In-game help |
| `/mvp update` | Download update jar (admin) |
| `/mvp scanner` | Public pool status (admin) |

**Inventory:** off by default on the open network. Enable with `/mvp settings export on` / `import on` (or `/mvp items …`).

---

## Docs

| Doc | Audience |
|-----|----------|
| **[portal_guide.en.md](portal_guide.en.md)** | Players (EN) |
| **[portal_guide.zh.md](portal_guide.zh.md)** | Players (中文) |
| **[portal_guide.md](portal_guide.md)** | Players (RU) |
| **[docs/TECHNICAL.md](docs/TECHNICAL.md)** | Config, performance, scanners, build |
| **[docs/REGISTRY.md](docs/REGISTRY.md)** | Public catalog (HTTPS) · hub ops only |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | How the pieces fit |

---

## Feedback

Ideas, bugs, or suggestions? Please [open a GitHub Issue](https://github.com/mvsews/MultiversePortals/issues/new/choose).

---

## License

**MIT** — free to use, share, and modify. See [LICENSE](LICENSE).

---

<p align="center">
  <sub>Need players? Install the plugin — your world becomes part of a large cross-server family.</sub>
</p>
