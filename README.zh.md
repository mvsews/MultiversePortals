<h1 align="center">Multiverse Portals</h1>

<p align="center">
  <strong>许多服务器。<br>一张传送门网络。</strong>
</p>

<p align="center">
  <a href="https://mp.mvse.ws/"><strong>网站与 jar → mp.mvse.ws</strong></a><br>
  <a href="https://hangar.papermc.io/mvse/MultiversePortals">Hangar</a> ·
  <a href="https://modrinth.com/project/multiverseportals">Modrinth</a> ·
  <a href="https://www.curseforge.com/minecraft/bukkit-plugins/multiverse-portals-mvse">CurseForge</a> ·
  <a href="https://github.com/mvsews/MultiversePortals/releases">GitHub</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh.md">简体中文</a> ·
  <a href="portal_guide.zh.md">玩家指南</a> ·
  <a href="docs/TECHNICAL.md">技术文档</a>
</p>

---

## 这是什么？

面向 **Paper 1.21+** 的跨服传送门插件——**不需要** Bungee / Velocity。

玩家用**告示牌 + 压力板**搭建传送门。游戏使用原版 **Transfer**（Bedrock 走 Geyser）。你的世界既能送玩家出去，也能从公共网络接待来客。

**游戏内语言：** `/mvp lang en|de|ru|zh`

---

<p align="center">
  <img src="assets/banner.png" alt="Multiverse Portals" width="100%">
</p>

<p align="center">
  <a href="https://mp.mvse.ws/"><img alt="Multiverse Portals network" src="https://mp.mvse.ws/mvp/v1/badge/network.svg?v=neon4"></a><br>
  <a href="https://mp.mvse.ws/"><img alt="players online" src="https://mp.mvse.ws/mvp/v1/badge/players.svg?v=neon4"></a>
  <a href="https://mp.mvse.ws/"><img alt="servers" src="https://mp.mvse.ws/mvp/v1/badge/servers.svg?v=neon4"></a>
</p>

<p align="center">
  <a href="https://hangar.papermc.io/mvse/MultiversePortals"><img alt="Hangar" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/hangar_vector.svg"></a>
  <a href="https://modrinth.com/project/multiverseportals"><img alt="Modrinth" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a>
  <a href="https://www.curseforge.com/minecraft/bukkit-plugins/multiverse-portals-mvse"><img alt="CurseForge" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg"></a>
  <a href="https://github.com/mvsews/MultiversePortals/releases"><img alt="GitHub" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg"></a>
</p>

## 5 分钟安装（自己的 Paper）

服务器必须是 **Paper 1.21+**（原版 Vanilla 不行）。还没有 Paper？在这里下载：[papermc.io/downloads/paper](https://papermc.io/downloads/paper)，把 `paper.jar` 放进服务器目录并先运行一次（会生成 `server.properties`、世界和 `plugins` 文件夹）。

1. 从 [mp.mvse.ws](https://mp.mvse.ws/) **下载插件**，文件名类似 `MultiversePortals-….jar`（网站上的下载按钮）。
2. 把 jar **放进 `plugins` 文件夹**（和 `paper.jar` 同级；没有就新建）：
   ```text
   my-server/
     paper.jar
     server.properties
     plugins/
       MultiversePortals.jar   ← 放这里
   ```
3. 打开服务器根目录的 **`server.properties`**（不是 `plugins` 里面），确认有：
   ```properties
   accept-transfers=true
   ```
   没有这行时，别的世界没法用 Transfer 把玩家送来。首次启动插件常常会自动写上——改完后仍需**完整重启**。
4. **完全关掉服务器再开**，让插件加载。
5. 进游戏运行 **`/mvp settings`**，可以看到地址，以及公共地图能不能看到你。

地图和 Transfer 用的地址来自同一个 `server.properties` 里的 `server-ip` / `server-port`。只有朋友用**别的域名或端口**进服时（NAT、代理、Docker 端口映射），才需要改 `plugins/MultiversePortals/config.yml`：

```yaml
server:
  public-host: "play.example.com"   # 空 = 用 server-ip
  public-port: 25566                # 0 = 用 server-port；否则填玩家真正输入的端口
```

**为什么可能不会出现在 [mp.mvse.ws](https://mp.mvse.ws/)：** 纯局域网、内网 IP、没开 `accept-transfers`、或对外端口填错。家里自玩完全没问题——自己服之间的传送门照常工作。想故意不上地图：在 `config.yml` 设 `server.list-publicly: never`。

> 提示：`server.display-name` 留空时，公开名称用 Minecraft MOTD 第一行。

---

## 1 分钟安装（Docker）

还没有 Paper、机器上有 **Linux 上的 Docker** 时，一条命令即可：Paper + Multiverse Portals + Geyser（手机/主机）+ ViaVersion。使用 `--network host`，端口直接绑在宿主机上：Java **25565**，Bedrock **19132**。

```bash
docker run -d --name minecraft_mvp --network host -e EULA=TRUE -v mvp-data:/data mvsews/mvp && IP=$(curl -fsS https://api.ipify.org) && echo "Server ready — connect Java $IP:25565 | Bedrock $IP:19132"
```

首次启动请等几分钟（下载 Paper + 生成世界）。命令结束会打印连接用的 IP。不设置名字时会自动生成（例如 “Peppery Bridge”）。

**常用环境变量**——写在 `-e EULA=TRUE` 旁边：

| 变量 | 默认 | 作用 |
|------|------|------|
| `SERVER_NAME` | 自动趣味名 | MOTD 与地图上的名称 |
| `MOTD` | 同 `SERVER_NAME` | 想分开写时用 |
| `PUBLIC_HOST` | 公网 IP（ipify） | 其他服 / 地图应使用的域名或 IP |
| `PUBLIC_PORT` | `25565` | 玩家用的 Java 端口 |
| `BEDROCK_PORT` | `19132` | Geyser UDP 端口 |
| `MEMORY` | `1G` | JVM 内存，例如 `-e MEMORY=2G` |
| `FLOODGATE_KEY_B64` | （无） | 共享 Floodgate `key.pem` 的 base64——Bedrock 在「自己的」服之间跳转时用 |

自定义名称和域名示例：

```bash
docker run -d --name minecraft_mvp --network host \
  -e EULA=TRUE \
  -e SERVER_NAME="Rainbow Forest" \
  -e PUBLIC_HOST=play.example.com \
  -v mvp-data:/data \
  mvsews/mvp
```

世界与数据在 Docker 卷 `mvp-data` 里——重建容器不会丢档。日志：`docker logs -f minecraft_mvp`。停止：`docker stop minecraft_mvp`。

---

## 传送门类型（告示牌）

第一行 = 类型。旁边放**压力板**。

| 你写 | 效果 |
|------|------|
| `[Multi]` / `[mvp]` / `[portal]` | 找一台在线服并**固定**，拆牌前不会换目标 |
| `[To]` + IP/端口（或 `server-id`） | 始终去指定目的地 |
| `[Pair]` | 生成配对码——另一台服填同一码即可往返 |

**旋钮：** 随机 `[Multi]` 旁的按钮会切换固定目标（优先俱乐部 MVP 节点）。不适用于 `[To]` / `[Pair]`。

状态：`Portal` → `Scan...` → 短目标名 + `->`（单向）或 `<->`（配对）。

公开单向跳转前，玩家执行一次 **`/mvp ready`**。

---

## 两台服互通（往返）

推荐：**`[Pair]`**。

**服务器 A**

1. 框架 + 告示牌 + 压力板  
2. 第 1 行：`[Pair]`（第 2 行空）  
3. 记下聊天/牌子上的**配对码**  

**服务器 B**

1. 同样搭建  
2. 第 1 行：`[Pair]`  
3. 第 2 行：填入该**配对码**  

两边都显示 `<->` 后，踩一侧会落到对侧传送门，并可立刻走回。

两边都需要 `accept-transfers=true` 和真实的 `public-host`。默认会通过 [mp.mvse.ws](https://mp.mvse.ws/) 自动加入网络。

<details>
<summary>备选：告示牌写 IP</summary>

| 行 | 内容 |
|----|------|
| 1 | `Portal` |
| 2 | `1.2.3.4:25565` |

默认端口 `25565` 时可只写地址。旧写法（第 2 行主机、第 3 行端口）仍可用。要真正往返，对侧也要建回程门。
</details>

---

## 本服传送门（同世界）

羊毛框 + 告牌（ColorPortals 玩法）：

1. 单一颜色的 **3×4 羊毛**，顶中间挂墙牌  
2. 第 1 行 = **名称**，第 2 行 = **频道**（`0`–`9999`）  
3. 框内压力板 — 同色同频道组成**本服**环形传送  

`/mvp local list` · 管理员可用 `/mvp local import-colorportals` 导入旧 ColorPortals 数据。

---

## 常用命令

| 命令 | 用途 |
|------|------|
| `/mvp version` | 已安装版本 vs mp.mvse.ws 最新版 |
| `/mvp ready` | 允许公开单向旅行 |
| `/mvp lang …` | 服务器回退语言 |
| `/mvp settings` | 地图、客人、背包转移 |
| `/mvp help` | 游戏内帮助 |
| `/mvp update` | 下载更新 jar（管理） |
| `/mvp scanner` | 公共池状态（管理） |

**背包：** 默认不跨服转移。开启：`/mvp settings export on` / `import on`（或 `/mvp items …`）。

---

## 文档

| 文档 | 对象 |
|------|------|
| **[portal_guide.zh.md](portal_guide.zh.md)** | 玩家（中文） |
| **[portal_guide.en.md](portal_guide.en.md)** | 玩家（英文） |
| **[portal_guide.md](portal_guide.md)** | 玩家（俄语） |
| **[docs/TECHNICAL.md](docs/TECHNICAL.md)** | 配置、性能、扫描器、构建 |
| **[docs/REGISTRY.md](docs/REGISTRY.md)** | 公共目录（HTTPS）· 仅中心枢纽运维 |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | 架构说明 |

---

## 反馈

有想法、发现 bug 或想提建议？请到 GitHub [创建 Issue](https://github.com/mvsews/MultiversePortals/issues/new/choose)。

---

## 许可证

**MIT** — 可自由使用、传播与修改。见 [LICENSE](LICENSE)。

---

<p align="center">
  <sub>需要玩家？安装插件——你的世界就会成为大型跨服大家庭的一员。</sub>
</p>
