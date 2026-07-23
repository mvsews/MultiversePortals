
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


<p align="center">
  <img src="assets/banner.png" alt="Multiverse Portals" width="100%">
</p>

## Install in 5 minutes (your own Paper)

The server must be **Paper 1.21+** (plain Vanilla will not work). If you don’t have Paper yet, download it here: [papermc.io/downloads/paper](https://papermc.io/downloads/paper), put `paper.jar` in a server folder, and run it once (it creates `server.properties`, the world, and a `plugins` folder).

1. **Download the plugin** from [mp.mvse.ws](https://mp.mvse.ws/) — a file like `MultiversePortals-….jar` (use the download button on the site).
2. **Put the jar in the `plugins` folder** next to `paper.jar` (create the folder if it is missing):
   ```text
   my-server/
     paper.jar
     server.properties
     plugins/
       MultiversePortals.jar   ← here
   ```
3. Open **`server.properties` in the server root** (not inside `plugins`) and make sure you have:
   ```properties
   accept-transfers=true
   ```
   Without that line, other worlds cannot send players to you. The plugin often writes it on first start — still do a full restart after changing it.
4. **Stop the server completely and start it again** so the plugin loads.
5. Join the game and run **`/mvp settings`** — you’ll see your address and whether the public map can see you.

The address used for the map and Transfer comes from `server-ip` / `server-port` in the same `server.properties`. Edit `plugins/MultiversePortals/config.yml` only if friends join you on a **different domain or port** (NAT, proxy, Docker port publish):

```yaml
server:
  public-host: "play.example.com"   # empty = use server-ip
  public-port: 25566                # 0 = use server-port; otherwise the port players actually type
```

**Why you might not appear on [mp.mvse.ws](https://mp.mvse.ws/):** closed LAN, private IP, `accept-transfers` off, or the wrong external port. That’s fine for home play — portals between your own worlds still work. To stay off the map on purpose, set `server.list-publicly: never` in `config.yml`.

---

## Install in 1 minute (Docker)

If you don’t have Paper yet and the machine has **Docker on Linux**, one command brings up Paper + Multiverse Portals + Geyser (phone / console) + ViaVersion. It uses `--network host`, so ports bind on the host: Java **25565**, Bedrock **19132**.

```bash
docker run -d --name minecraft_mvp --network host -e EULA=TRUE -v mvp-data:/data mvsews/mvp && IP=$(curl -fsS https://api.ipify.org) && echo "Server ready — connect Java $IP:25565 | Bedrock $IP:19132"
```

Wait a couple of minutes on first boot (Paper download + world gen). The command prints the IP to connect to. If you don’t set a name, one is generated for you (e.g. “Peppery Bridge”).

**Useful env vars** — add next to `-e EULA=TRUE`:

| Variable | Default | Meaning |
|----------|---------|---------|
| `SERVER_NAME` | funny auto name | MOTD + label on the map |
| `MOTD` | same as `SERVER_NAME` | override if you want them different |
| `PUBLIC_HOST` | WAN IP via ipify | domain or IP other servers / the map should use |
| `PUBLIC_PORT` | `25565` | Java port for players |
| `BEDROCK_PORT` | `19132` | Geyser UDP port |
| `MEMORY` | `1G` | JVM heap, e.g. `-e MEMORY=2G` |
| `FLOODGATE_KEY_B64` | (none) | shared Floodgate `key.pem` as base64 — for Bedrock hops between your own servers |

Example with a custom name and domain:

```bash
docker run -d --name minecraft_mvp --network host \
  -e EULA=TRUE \
  -e SERVER_NAME="Rainbow Forest" \
  -e PUBLIC_HOST=play.example.com \
  -v mvp-data:/data \
  mvsews/mvp
```

World and data live in the Docker volume `mvp-data` — you can recreate the container without losing saves. Logs: `docker logs -f minecraft_mvp`. Stop: `docker stop minecraft_mvp`.

---



## Portal types (signs)

First line of the sign = type. Put a **pressure plate** next to it.


| You write                           | What happens                                                            |
| ----------------------------------- | ----------------------------------------------------------------------- |
| `[Multi]` or `[mvp]` or `[portal]`  | Finds a live server once and **sticks** to it until you break the sign  |
| `[To]` + IP / port (or `server-id`) | Always goes to that destination                                         |
| `[Pair]`                            | Creates a code — use the same code on the other server for a round-trip |


**Dial:** a button next to a random `[Multi]` sign rebinds the sticky destination (club MVP peers first). Does not apply to `[To]` / `[Pair]`.

Sign status while working: `Portal` → `Scan...` → short destination name + `->` (one-way) or `<->` (pair).

For public one-way hops, players run `/mvp ready` once.

---



## Link two of your servers (round-trip)

Best option: `[Pair]`.

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

Alternative: by IP on the sign


| Line | Text            |
| ---- | --------------- |
| 1    | `Portal`        |
| 2    | `1.2.3.4:25565` |


Or just the address on line 2 if the port is the default `25565`. Legacy: host on line 2 and port on line 3 also works. For a true round-trip, build a return portal on the other side too.



---



## Local portals (same server)

Wool frame + wall sign (ColorPortals-style):

1. **3×4 wool** of one color, sign on the top middle block
2. Line 1 = **name**, line 2 = **channel** (`0`–`9999`)
3. Plate inside — same color + channel = a ring of warps on **this** server

`/mvp local list` · admins can import old ColorPortals data with `/mvp local import-colorportals`.

---



## Useful commands


| Command         | For                                     |
| --------------- | --------------------------------------- |
| `/mvp version`  | Installed vs latest on mp.mvse.ws       |
| `/mvp ready`    | Allow public one-way travel             |
| `/mvp lang …`   | Server fallback language                |
| `/mvp settings` | Map listing, guests, inventory transfer |
| `/mvp help`     | In-game help                            |
| `/mvp update`   | Download update jar (admin)             |
| `/mvp scanner`  | Public pool status (admin)              |


**Inventory:** off by default on the open network. Enable with `/mvp settings export on` / `import on` (or `/mvp items …`).

---



## Docs


| Doc                                              | Audience                                             |
| ------------------------------------------------ | ---------------------------------------------------- |
| **[portal_guide.en.md](portal_guide.en.md)**     | Players (EN)                                         |
| **[portal_guide.zh.md](portal_guide.zh.md)**     | Players (中文)                                         |
| **[portal_guide.md](portal_guide.md)**           | Players (RU)                                         |
| **[docs/TECHNICAL.md](docs/TECHNICAL.md)**       | Config, performance, scanners, build                 |
| **[docs/SCANNERS.md](docs/SCANNERS.md)**         | Public scanner sources for `[Multi]` · collaboration |
| **[docs/REGISTRY.md](docs/REGISTRY.md)**         | Public catalog (HTTPS) · hub ops only                |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | How the pieces fit                                   |


---



## Feedback

Ideas, bugs, or suggestions? Please [open a GitHub Issue](https://github.com/mvsews/MultiversePortals/issues/new/choose).

---



## License

**MIT** — free to use, share, and modify. See [LICENSE](LICENSE).

---

Need players? Install the plugin — your world becomes part of a large cross-server family.