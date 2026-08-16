# 如何使用传送门（Multiverse Portals）

给玩家的简短指南。

有三种模式：**本地**（羊毛）、**跨服**（`[Multi]` / `[To]` / `[Pair]`）和 **Away**（本服另一个生物群系）。

**语言：** [English](portal_guide.en.md) · [中文](portal_guide.zh.md) · [Русский](portal_guide.md)

---

## 本地传送门（羊毛）

类似 ColorPortals — 在**本服内**传送（可到同一主机的其他世界）。

1. **同色羊毛**搭任意闭合圆环（小的 3×4 门只是示例）
2. 把**墙面告示牌**挂在右侧立柱（面向传送门时）
3. **第 1 行** = 名称，**第 2 行** = 频道（`0`–`9999`）

**同色 + 同频道**的门连成环：A→B→C→A。两扇门 = 往返。

走进**紫色门面**即可。不必放压力板（旧板仍可用）。告示牌下可加按钮。右键告示牌可看信息。列表：`/mvp local list`。

---

## Away — 本服另一个群系

类似下界门，但在**本服主世界群系**之间传送。

1. 用**本群系方块**搭框：森林用橡木原木，沙漠用砂岩，冰川用浮冰，以此类推。任意闭合圆环；**墙告示牌**挂在右侧立柱
2. **第 1 行** = `Portal` / `传送门`。**第 2 行**留空或写 `Away` / `异界`
3. 走进**紫色门面**

第一次走进去会绑定到一个主世界群系（含海洋和洞穴，不含下界/末地），并像下界门一样记住。插件可用**你出发群系**的材料自动搭一座回程小环，告示牌在右侧。框架材料不对时，聊天会提示本群系需要哪种方块。

若第二行是 `Random`，即使是橡木框也是**跨服**门。Away **不会**出现在公开地图上。

---

## 跨服传送门

### 搭建

1. 类似下界门 — 中间 **2×3** 空气，周围任意实心方块。也可以做**大闭合圆环**：圆环完整则门亮着，缺口则传送门消散。

```
O O O O
O . . O
O . . S
O . . O
O O O O
```

`O` = 边框，`.` = 空气，`S` = 右侧立柱上的告示牌。

2. 把**告示牌挂在右侧立柱**（面向传送门时）。
3. 告示牌**第 1 行**写类型（大小写不限）。不必放压力板——走进紫色门面即可。

边框必须**闭合**。拆掉 Random/Away 的告示牌会删除传送门。

### 第 1 行类型

| 第 1 行 | 作用 |
|---------|------|
| `[Multi]` / `[portal]` / `Портал` / `传送门` | 随机服务器（链接会**固定**） |
| `[To]` / `К` / `前往` | 指定服务器：**IP + 端口**（第 2 / 3 行）或目录里的 id |
| `[Pair]` / `Пара` / `配对` | 配对门，可往返 |
| 本群系材料围成的 `Portal` / `传送门` | Away — 本服另一个生物群系（也可写 `Away` / `Авей` / `异界`） |

Away：详见上方 [Away](#away--本服另一个群系)。

### 连接你的两台服（管理员）

完整步骤见 [README.zh.md](README.zh.md#两台服互通往返)。简要：

1. **推荐：** Alpha 写 `[Pair]` → 复制代码 → Beta 写 `[Pair]`，第 2 行填该代码。
2. **按 IP：** `Portal` / `1.2.3.4:25565`（端口是 `25565` 时可只写地址）——不需要目录。
3. **按 id：** Alpha `[To]` / `beta`，Beta `[To]` / `alpha`（id 见 `/mvp info`）。
4. 用 id 时两台服都要出现在公共目录 [mp.mvse.ws](https://mp.mvse.ws/)（需 `accept-transfers=true` 和公网地址；`public-host` 通常来自 `server-ip`）。

English: [README.md](README.md#link-two-of-your-servers-round-trip) · Русский: [README.ru.md](README.ru.md#связать-два-своих-сервера-туда-обратно) · 中文: [README.zh.md](README.zh.md#两台服互通往返)

### Random = 固定目标

1. 放置 `[Multi]` → 白色粒子 → 传送门**绑定到一台服务器**。
2. 告示牌还在时，永远去那里（重启后也一样）。
3. 想换目标 — 在告示牌旁放**按钮**并按下：这是**门旋钮**（像《哈尔的移动城堡》），切换传送门去向（优先俱乐部 MVP，否则重新随机）。
4. 或**拆掉告示牌**再重新放 `[Multi]`。
5. 旋钮**不适用于** `[To]`（固定地址）和 `[Pair]`。
6. 目标暂时宕机时链接**不会变**；稍后再试、转旋钮或重建告示牌。

### 怎么走

走进**紫色门面**（不要站在门框顶梁上）。旁边的压力板可选，仍可用。等充能完成就会传送。

### 单向同意（`/mvp ready`）

两种跳转：

- **可返回** — 配对的 `[Pair]` 或装有同插件的服。可直接走，无需同意。
- **单向** — 扫描器里的公开随机服。不能从那个门原路回来。

单向跳转前先允许一次：

```
/mvp ready
```

或聊天里写：

```
mvp ready
```

取消：

```
/mvp ready off
```

或聊天：`mvp ready off`。

---

## 入站流量（管理员）

接收服可过滤客人：

```
/mvp ingress           — 限制
/mvp deny add Nick …   — 禁止经门进入
/mvp rep Nick -50      — 信誉扣分
```

详见：[README.zh.md](README.zh.md)。

## 服务器负载（管理员）

插件**不是一直很重**，但也不是完全零开销。大部分在后台异步；**峰值**在创建 `[Multi]`（Scan… + 探测）、在门洞充能、或很多带特效的门时。

| 内容 | 何时 | 影响 |
|------|------|------|
| 扫描器（MineScan + Cornbread） | 约每 1–2 分钟 | HTTP + SQLite — **几乎不影响 TPS** |
| 创建 `[Multi]` 时绑定 | 一次 | 后台探测 — **最多约 90 秒** |
| 绑定后传送 | 站在门洞里 | 约 2 秒充能 + Transfer，**无长时间搜索** |
| 「物质」粒子 | 每 0.5 秒 | 仅当玩家在**约 20 格内** |
| 本地羊毛门 | 走进门洞 | **很轻** |

**弱 VPS（1–2 GB RAM）：** 作为**目的地**放 1–2 个固定 `[Multi]` 即可。不要一次放很多特效门，也不要频繁重建告示牌。

**减负** — `config.yml`：

```yaml
scanner:
  refresh-seconds: 180
  sample-count: 20
  max-attempts: 10
  bind-search-seconds: 45

effects:
  matter:
    enabled: false      # 或 particles: false

registry:
  enabled: false   # 保持 false — 通过 HTTPS 加入开放目录
```

最轻：只用 **PAIR** 或只用**本地羊毛**。完整表：[docs/TECHNICAL.md](docs/TECHNICAL.md#performance--weak-servers)。

## 物品

- 背包跨服转移**默认关闭**。管理员开启：`/mvp settings export on` / `import on`（或 `/mvp items …`）。
- **公开单向**跳转 — 物品**留在你背包里**（不会被清空）。

## 版本

游戏内：**`/mvp version`** — 已安装版本与 mp.mvse.ws 最新版。

插件不会把你送到客户端进不去的服。没有目标时会提示「没有兼容服务器」。

从 [mp.mvse.ws](https://mp.mvse.ws/) 下载 jar — 文件名含版本号（`MultiversePortals-1.2.0.jar`）。

## 管理员：`accept-transfers` 与目录

在 **`server.properties`**（不是插件配置）中：

```properties
accept-transfers=true
```

没有这行时，Minecraft 会**拒绝** Transfer 入站 — 其他世界的客人进不来。插件进入**仅本地**模式（羊毛、Pair / 按 IP 的 `[To]`），也**不会**出现在 mp.mvse.ws。

Transfer 地址自动取自 `server-ip` / `server-port`。若玩家用的**域名或端口**不同（Docker `-p 25566:25565`、NAT、代理），在 `config.yml` 设 `public-host` / `public-port`——否则目录会拿到内部 `25565` / 局域网 IP，一直仅本地。检查：`/mvp settings`。

## 常见问题

| 提示 | 怎么办 |
|------|--------|
| 需要 `/mvp ready` | 执行 `/mvp ready` 或聊天写 `mvp ready` |
| 没有兼容服务器 | 等待 / 换客户端版本；管理员：`/mvp scanner` |
| 找不到在线服务器 | 再走进门洞一次 |
| 配对门损坏 | 用 `[Pair]` 代码重新配对 |
| 不在目录中 | `accept-transfers=true`、公网 IP/域名（非 Docker `172.*`）、**`public-port` = 外部端口**；见 `/mvp settings` → catalog |

## 玩家命令

```
/mvp ready          — 允许单向
/mvp ready off      — 取消单向
/mvp local list     — 本地羊毛门
/mvp version        — 已安装 vs 最新
/mvp help           — 帮助
/mvp scanner        — 池大小（信息）
```

管理开关：`/mvp settings`（地图 / 客人 / 背包）。

完整管理员文档：[README.zh.md](README.zh.md) · [README.md](README.md) · [README.ru.md](README.ru.md)。

## 反馈

如果游戏内行为**与本指南或 [docs/CONCEPTS.md](docs/CONCEPTS.md) 不符**，请 [开 Issue](https://github.com/mvsews/MultiversePortals/issues) 或 [Pull Request](https://github.com/mvsews/MultiversePortals/pulls)。两者都欢迎。

许可证为 **MIT**，可自由传播与修改，见 [LICENSE](LICENSE)。
