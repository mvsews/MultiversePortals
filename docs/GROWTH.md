# Getting players from the open portal network

How a quiet server can use Multiverse Portals for **inbound traffic** — and what that costs.

[English](#english) · [Русский](#русский) · [Deutsch](#deutsch)

Intended behavior of portals themselves: [CONCEPTS.md](CONCEPTS.md). Install: [README.md](../README.md).

---

## English

### The idea

You need **people in the world**, not another proxy network.

Install the plugin, allow vanilla **Transfer**, and list the server on the public catalog. Other worlds run **random `[Multi]`** portals. Those portals search for a **live, compatible** host and **stick** to it. If they stick to **you**, a player on *their* server walks through a portal and appears on **yours**.

You are not buying ads. You are a **possible door** in a shared network of Paper servers ([map](https://mp.mvse.ws/)). Flow-balance on the origin prefers **quieter destinations** when the origin is busy, so a small world can be chosen *because* it is not a mega-lobby.

This is **inbound discovery**. It is not a guarantee of regulars. Many arrivals are curious one-way hops.

### What the admin actually turns on

Without these, nobody can land:

1. **Paper 1.21+** and the plugin jar.
2. **`accept-transfers=true`** in `server.properties` (vanilla gate — the plugin cannot bypass it).
3. A **public** Java address (`server.public-host` / `public-port` if Docker/NAT). LAN IPs are not listed.
4. **Guests on:** `/mvp settings guests on` (`open-network.accept-inbound`).
5. **Map listing:** `server.list-publicly: auto` (default) or `always`. Check with `/mvp settings`.

Useful extras:

- **ViaVersion** — more client versions can join you.
- **Geyser + Floodgate** — Bedrock phones/consoles can arrive (and other servers prefer Geyser hosts when the traveler is Bedrock).
- A **spawn / rules / claim** so a stranger does not walk into an unprotected chest.
- At least one **local network portal** (Random `[Multi]` or an opening the plugin can use) so arrivals and “guest home” have somewhere to stand.

You do **not** need Bungee or Velocity.

### How to want *inflow* more than *outflow*

The network is two-way. If your own players build `[Multi]`, they will leave.

Typical policy for a server that **wants guests** but **does not want a drain**:

| Goal | Setting |
|------|---------|
| Receive travelers | Guests **on**, listed publicly |
| Players cannot open random exits | `portals.create: admin` (`/mvp settings create admin`) |
| Local fun without leaving | Keep **wool** and **Away**; you can turn **`multi` / `to` / `pair` off** for *creation* while still **receiving** from other servers |
| Soft cap on strangers | `ingress.max-arrivals-per-hour`, `reserve-slots`, `/mvp deny` |
| Economy | Inventory transfer stays **off** by default; still use claims — Transfer can bring whatever is in the player’s inventory |

Guests with **no return portal** on your side get a **guest-home** overlay on a local Random portal for one hour (`travel.guest-home-seconds`). That is a door **home**, not a new bind for everyone else.

### Advantages

- **Discovery without marketing** — other servers’ random doors can land on you.
- **Bias toward quieter worlds** — busy origins tend not to dump everyone onto the same giant.
- **No proxy** — each server stays independent; you can leave the network by turning guests off or `list-publicly: never`.
- **Java and Bedrock** (with Geyser) from the same public host.
- **Knobs** — hourly arrival cap, reserve slots, score/deny, guests toggle.
- **Guest home** — one-way visitors are not dumped only at spawn with no way back.
- **You keep sovereignty** — whitelist, claims, gamemode, plugins stay yours.

### Disadvantages

- **Strangers** — grief, spam, stolen builds, unless spawn is protected. This is public internet traffic, not a whitelist application form.
- **Your players can leave** if they are allowed to build `[Multi]` / `[To]`. Inflow and outflow are the same mechanic.
- **Many hops are tourists** — they look around and go home (or bounce). Do not expect every arrival to stay.
- **Your IP/domain is public** on the catalog while you list.
- **Must be reachable** — closed LAN, wrong `public-port`, or `accept-transfers` off means **zero** inbound from the network.
- **Version / auth** — if you are a niche fork, no Via, or online-mode mismatch vs what travelers can join, random portals will **skip** you.
- **Load spikes** — a popular origin binding to you can send bursts; use `ingress` or you hit `max-players`.
- **World first impression** — an ugly or empty spawn makes people leave; bounces can hurt how often you are chosen again.
- **Items and PvP culture** travel with the player. Import/export defaults do not replace local anti-cheat or claims.
- **Moderation cost** — more join events, more tickets, more “who is this”.

### Honest summary

Turn on Transfer + guests + a public address if you want **a chance** at players who already play on other portal worlds. Turn **create** to **admin** if you want that chance **without** turning your own population into an exit. Protect spawn. Cap ingress. Expect curiosity, not a full server overnight.

---

## Русский

### Задумка

Нужны **люди в мире**, а не ещё один прокси.

Ставишь плагин, разрешаешь ванильный **Transfer**, попадаешь в публичный каталог. На других серверах стоят случайные **`[Multi]`**. Они ищут **живой совместимый** хост и **прилипают** к нему. Если прилипли к **тебе**, игрок *у них* заходит в портал и оказывается **у тебя**.

Это не реклама. Это **дверь** в общей сети Paper-серверов ([карта](https://mp.mvse.ws/)). На загруженном сервере-источнике flow-balance чаще выбирает **более тихие** миры — маленький сервер могут выбрать *именно потому*, что он не мега-лобби.

Это **входящее обнаружение**. Не обещание онлайна каждый вечер. Много приходов — разовые «посмотришь и уйдёшь».

### Что админ включает на самом деле

Без этого никто не приземлится:

1. **Paper 1.21+** и jar плагина.
2. **`accept-transfers=true`** в `server.properties` (ванильный замок — плагин его не обходит).
3. **Публичный** Java-адрес (`server.public-host` / `public-port` при Docker/NAT). LAN в каталог не попадает.
4. **Гости вкл.:** `/mvp settings guests on` (`open-network.accept-inbound`).
5. **Карта:** `server.list-publicly: auto` (по умолчанию) или `always`. Проверка: `/mvp settings`.

Полезно:

- **ViaVersion** — больше версий клиента к тебе зайдут.
- **Geyser + Floodgate** — приход с телефона/консоли; другие серверы для Bedrock предпочитают хосты с Geyser.
- **Спавн / правила / приваты** — чужой человек не должен выйти в незакрытый сундук.
- Хотя бы один **местный сетевой портал**, чтобы было куда ставить прибывших и «дом гостя».

Bungee и Velocity **не нужны**.

### Как хотеть *приток*, а не *утечку*

Сеть двусторонняя. Если свои игроки строят `[Multi]`, они уйдут.

Типичная политика сервера, которому **нужны гости**, но **не дыра в онлайне**:

| Цель | Настройка |
|------|-----------|
| Принимать путников | Гости **вкл.**, сервер на карте |
| Свои не открывают случайные выходы | `portals.create: admin` (`/mvp settings create admin`) |
| Локальные развлечения без ухода | Оставь **шерсть** и **Away**; **`multi` / `to` / `pair`** можно выключить для *создания* — **приём с чужих серверов всё равно работает** |
| Мягкий потолок чужаков | `ingress.max-arrivals-per-hour`, `reserve-slots`, `/mvp deny` |
| Экономика | Перенос инвентаря по умолчанию **выкл.**; приваты всё равно нужны — в инвентаре игрока может приехать что угодно |

Если на твоей стороне **нет портала назад**, для этого UUID случайный местный MULTI час будет **домом** (`travel.guest-home-seconds`). Это дверь **домой**, а не новая привязка для всех.

### Плюсы

- **Находят без рекламы** — чужие random-двери могут привести к тебе.
- **Перекос в сторону тихих миров** — загруженные серверы не обязаны сбрасывать всех на один гигант.
- **Без прокси** — сервер независимый; сеть можно покинуть (`guests off` или `list-publicly: never`).
- **Java и Bedrock** (с Geyser) на одном публичном хосте.
- **Ручки** — лимит входов в час, запас слотов, score/бан, тумблер гостей.
- **Дом гостя** — one-way не только спавн без пути назад.
- **Свои правила** — вайтлист, приват, режим, плагины остаются твоими.

### Минусы

- **Чужаки** — гриф, спам, сломанные постройки, если спавн не защищён. Это интернет, не анкета в вайтлист.
- **Свои могут уйти**, если им можно строить `[Multi]` / `[To]`. Приток и отток — один механизм.
- **Много туристов** — посмотрели и ушли. Не жди, что каждый приход останется.
- **IP/домен публичны**, пока ты на карте.
- **Должен быть доступен снаружи** — LAN, неверный `public-port` или выключенный `accept-transfers` = **ноль** входящих из сети.
- **Версия / режим** — редкий форк, нет Via, несовместимый online-mode — random-порталы тебя **пропустят**.
- **Пики онлайна** — популярный источник, привязанный к тебе, может прислать пачку; без `ingress` упрёшься в `max-players`.
- **Первое впечатление** — уродливый пустой спавн, люди сразу уходят; откаты могут реже выбирать тебя снова.
- **Вещи и культура PvP** едут с игроком. Дефолты export/import не заменяют античит и приваты.
- **Модерация** — больше входов, больше «кто это».

### Честно

Включи Transfer + гостей + публичный адрес, если хочешь **шанс** на игроков, которые уже ходят по чужим порталам. Поставь **create: admin**, если шанс нужен **без** превращения своего онлайна в выход. Защити спавн. Ограничь ingress. Жди любопытства, а не полный сервер за ночь.

---

## Deutsch

### Die Idee

Du brauchst **Leute in der Welt**, kein weiteres Proxy-Netz.

Plugin installieren, Vanilla-**Transfer** erlauben, Server in den öffentlichen Katalog. Andere Welten haben zufällige **`[Multi]`**-Portale. Die suchen einen **lebenden, kompatiblen** Host und **kleben** daran. Kleben sie an **dir**, tritt ein Spieler *bei denen* durch das Portal und steht **bei dir**.

Das ist keine Werbung. Das ist eine **Tür** in einem gemeinsamen Netz von Paper-Servern ([Karte](https://mp.mvse.ws/)). Flow-Balance auf vollen Origins bevorzugt eher **ruhigere** Ziele — eine kleine Welt kann gewählt werden, *weil* sie keine Mega-Lobby ist.

Das ist **eingehende Entdeckung**. Kein Versprechen auf Stammspieler. Viele Ankünfte sind einmalige Neugier-Hops.

### Was der Admin wirklich einschaltet

Ohne das landet niemand:

1. **Paper 1.21+** und das Plugin-Jar.
2. **`accept-transfers=true`** in `server.properties` (Vanilla-Riegel — das Plugin umgeht ihn nicht).
3. Eine **öffentliche** Java-Adresse (`server.public-host` / `public-port` bei Docker/NAT). LAN-IPs stehen nicht im Katalog.
4. **Gäste an:** `/mvp settings guests on` (`open-network.accept-inbound`).
5. **Karten-Eintrag:** `server.list-publicly: auto` (Standard) oder `always`. Prüfen mit `/mvp settings`.

Sinnvoll:

- **ViaVersion** — mehr Client-Versionen kommen bei dir an.
- **Geyser + Floodgate** — Bedrock von Handy/Konsole; andere Server bevorzugen für Bedrock Geyser-Hosts.
- **Spawn / Regeln / Claims** — ein Fremder soll nicht in eine ungeschützte Kiste laufen.
- Mindestens ein **lokales Netz-Portal**, damit Ankünfte und „Gäste-Zuhause“ einen Standplatz haben.

Bungee und Velocity sind **nicht nötig**.

### Zufluss wollen, nicht Abfluss

Das Netz ist zweiseitig. Bauen deine Spieler `[Multi]`, gehen sie weg.

Typische Politik, wenn du **Gäste willst**, aber **kein Loch in der Spielerzahl**:

| Ziel | Einstellung |
|------|-------------|
| Reisende annehmen | Gäste **an**, öffentlich gelistet |
| Eigene bauen keine Zufalls-Ausgänge | `portals.create: admin` (`/mvp settings create admin`) |
| Spaß vor Ort ohne Weggehen | **Wolle** und **Away** behalten; **`multi` / `to` / `pair`** kannst du fürs *Bauen* ausmachen — **Empfang von anderen Servern bleibt** |
| Weiche Obergrenze | `ingress.max-arrivals-per-hour`, `reserve-slots`, `/mvp deny` |
| Wirtschaft | Inventar-Transfer ist standardmäßig **aus**; Claims trotzdem — im Inventar des Spielers kann alles ankommen |

Gibt es bei dir **kein Rückportal**, ist für diese UUID ein lokales Random-MULTI eine Stunde lang **Zuhause** (`travel.guest-home-seconds`). Das ist die Tür **zurück**, kein neues Bind für alle.

### Vorteile

- **Entdeckung ohne Werbung** — fremde Zufallstüren können bei dir landen.
- **Hang zu ruhigeren Welten** — volle Origins müssen nicht alle auf denselben Riesen kippen.
- **Kein Proxy** — der Server bleibt eigenständig; Netz verlassen geht (`guests off` oder `list-publicly: never`).
- **Java und Bedrock** (mit Geyser) auf demselben öffentlichen Host.
- **Regler** — Ankünfte pro Stunde, Reserve-Slots, Score/Sperre, Gäste-Schalter.
- **Gäste-Zuhause** — One-Way nicht nur Spawn ohne Rückweg.
- **Deine Hoheit** — Whitelist, Claims, Modus, Plugins bleiben deine.

### Nachteile

- **Fremde** — Grief, Spam, kaputte Bauten, wenn der Spawn ungeschützt ist. Das ist Internet, kein Whitelist-Formular.
- **Deine Spieler können gehen**, wenn sie `[Multi]` / `[To]` bauen dürfen. Zufluss und Abfluss sind dieselbe Mechanik.
- **Viele Touristen** — schauen und weg. Nicht jede Ankunft bleibt.
- **IP/Domain sind öffentlich**, solange du gelistet bist.
- **Von außen erreichbar** — LAN, falscher `public-port` oder `accept-transfers` aus = **null** eingehend aus dem Netz.
- **Version / Auth** — exotischer Fork, kein Via, unpassender online-mode — Zufallsportale **überspringen** dich.
- **Lastspitzen** — ein beliebter Origin, der an dir klebt, kann Schübe schicken; ohne `ingress` knallst du an `max-players`.
- **Erster Eindruck** — hässlicher leerer Spawn, Leute gehen sofort; Bounces können dich seltener wählen.
- **Items und PvP-Kultur** reisen mit. Export/Import-Defaults ersetzen kein Anti-Cheat und keine Claims.
- **Moderation** — mehr Joins, mehr „wer ist das“.

### Ehrlich

Transfer + Gäste + öffentliche Adresse, wenn du eine **Chance** auf Spieler willst, die schon auf anderen Portalwelten unterwegs sind. **`create: admin`**, wenn die Chance **ohne** Abfluss der eigenen Leute sein soll. Spawn schützen. Ingress begrenzen. Neugier erwarten, nicht über Nacht einen vollen Server.
