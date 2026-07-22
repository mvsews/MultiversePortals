<p align="center">
  <img src="assets/banner.png" alt="Multiverse Portals" width="100%">
</p>

<h1 align="center">Multiverse Portals</h1>

<p align="center">
  <strong>许多服务器。<br>一张传送门网络。</strong>
</p>

<p align="center">
  <a href="https://mp.mvse.ws/"><strong>网站与 jar → mp.mvse.ws</strong></a>
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

## 5 分钟安装

1. 把 **`MultiversePortals-*.jar`** 放进 `plugins/`（文件名带版本号）  
   → 下载：[mp.mvse.ws](https://mp.mvse.ws/)
2. 在 **`server.properties`**（原版设置，不是插件配置）中：
   ```properties
   accept-transfers=true
   ```
   **原因：** 别的服用 Transfer 把玩家送来时，你的服必须允许入站传送。  
   关掉则客人进不来，插件进入 **仅本地** 模式（不上公共目录）。
3. 重启。插件会自动读 **`server-ip`** / **`server-port`**。  
   仅当玩家用的域名和 `server-ip` 不同时才覆盖：
   ```yaml
   # plugins/MultiversePortals/config.yml — 可选
   server:
     public-host: "play.example.com"   # 空 = 自动用 server-ip
     public-port: 0                    # 0 = server-port
   ```

即可用羊毛本地门、`[Pair]` / `[To]`（IP）。公网可达且 `accept-transfers=true` 时才会出现在 [mp.mvse.ws](https://mp.mvse.ws/)。

**仅本地：** 内网 IP 或未开 `accept-transfers` → 不上公共目录。也可设 `server.list-publicly: never`。

可选：ViaVersion、Geyser + Floodgate。

> **提示：** `server.display-name` 留空时，会用 Minecraft MOTD 第一行作为公开名称。

---

## 传送门类型（告示牌）

第一行 = 类型。旁边放**压力板**。

| 你写 | 效果 |
|------|------|
| `[Multi]` / `[mvp]` / `[portal]` | 找一台在线服并**固定**，拆牌前不会换目标 |
| `[To]` + IP/端口（或 `server-id`） | 始终去指定目的地 |
| `[Pair]` | 生成配对码——另一台服填同一码即可往返 |

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
| `/mvp lang …` | 切换界面语言 |
| `/mvp help` | 游戏内帮助 |
| `/mvp update` | 下载更新 jar（管理） |
| `/mvp scanner` | 公共池状态（管理） |

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

有想法、发现 bug 或想提建议？请到 GitHub [创建 Issue](https://github.com/mvsews/MultiversePortals/issues)。

---

## 许可证

**MIT** — 可自由使用、传播与修改。见 [LICENSE](LICENSE)。

---

<p align="center">
  <sub>需要玩家？安装插件——你的世界就会成为大型跨服大家庭的一员。</sub>
</p>
