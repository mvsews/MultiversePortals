<p align="center">
  <img src="assets/banner.png" alt="Multiverse Portals" width="100%">
</p>

<h1 align="center">Multiverse Portals</h1>

<p align="center">
  <strong>Много серверов.<br>Одна сеть порталов.</strong>
</p>

<p align="center">
  <a href="https://mp.mvse.ws/"><strong>Сайт и jar → mp.mvse.ws</strong></a><br>
  <a href="https://hangar.papermc.io/mvse/MultiversePortals">Hangar</a> ·
  <a href="https://modrinth.com/project/multiverseportals">Modrinth</a> ·
  <a href="https://www.curseforge.com/minecraft/bukkit-plugins/multiverse-portals-mvse">CurseForge</a> ·
  <a href="https://github.com/mvsews/MultiversePortals/releases">GitHub</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh.md">简体中文</a> ·
  <a href="portal_guide.md">Гайд игрока</a> ·
  <a href="docs/TECHNICAL.md">Техническое</a>
</p>

---

## Что это?

Плагин для **Paper 1.21+**, который связывает независимые Minecraft-серверы порталами — **без Bungee и Velocity**.

Игроки строят портал из **таблички + нажимной плиты**. Игра использует ванильный пакет **Transfer** (и Geyser для Bedrock). Мир может и отпускать игроков, и принимать гостей из публичной сети.

**Язык в игре:** `/mvp lang en|de|ru|zh`

---

## Установка за 5 минут (свой Paper)

Сервер должен быть на **Paper 1.21+** (обычный Vanilla не подойдёт). Если Paper ещё нет — скачай здесь: [papermc.io/downloads/paper](https://papermc.io/downloads/paper), положи `paper.jar` в папку сервера и один раз запусти (создадутся `server.properties`, мир и папка `plugins`).

1. **Скачай плагин** с [mp.mvse.ws](https://mp.mvse.ws/) — файл вида `MultiversePortals-….jar` (на сайте кнопка скачивания).
2. **Положи jar в папку `plugins`** рядом с `paper.jar` (если папки нет — создай):
   ```text
   my-server/
     paper.jar
     server.properties
     plugins/
       MultiversePortals.jar   ← сюда
   ```
3. Открой **`server.properties` в корне сервера** (не внутри `plugins`) и убедись, что есть строка:
   ```properties
   accept-transfers=true
   ```
   Без неё другие миры не смогут присылать к тебе игроков. При первом запуске плагин часто дописывает это сам — но после правки всё равно нужен полный перезапуск.
4. **Полностью останови сервер и запусти снова**, чтобы плагин загрузился.
5. Зайди в игру и напиши **`/mvp settings`** — там видно адрес сервера и, видит ли тебя публичная карта.

Адрес для карты и Transfer берётся из `server-ip` / `server-port` в том же `server.properties`. Менять что-то в `plugins/MultiversePortals/config.yml` нужно только если друзья заходят к тебе **по другому домену или порту** (NAT, прокси, Docker с пробросом портов):

```yaml
server:
  public-host: "play.example.com"   # пусто = как server-ip
  public-port: 25566                # 0 = как server-port; иначе порт, который реально набирают игроки
```

**Когда сервер не появится на [mp.mvse.ws](https://mp.mvse.ws/):** закрытый LAN, приватный IP, выключенный `accept-transfers`, или снаружи указан неверный порт. Это нормально для домашней игры — порталы между своими мирами всё равно работают. Чтобы сознательно не светиться на карте: в `config.yml` поставь `server.list-publicly: never`.

---

## Установка за 1 минуту (Docker)

Если Paper ещё нет и на машине есть **Docker на Linux**, можно поднять готовый мир одной командой: Paper + Multiverse Portals + Geyser (телефон / консоль) + ViaVersion. Используется `--network host` — порты слушаются прямо на хосте: Java **25565**, Bedrock **19132**.

```bash
docker run -d --name minecraft_mvp --network host -e EULA=TRUE -v mvp-data:/data mvsews/mvp && IP=$(curl -fsS https://api.ipify.org) && echo "Server ready — connect Java $IP:25565 | Bedrock $IP:19132"
```

После первого запуска подожди пару минут (скачивается Paper и генерируется мир). В конце команда напечатает IP, по которому заходить. Имя сервера, если не задать, выберется само (например, «Peppery Bridge»).

**Полезные переменные** — добавляй рядом с `-e EULA=TRUE`:

| Переменная | По умолчанию | Зачем |
|------------|--------------|--------|
| `SERVER_NAME` | забавное авто-имя | название в MOTD и на карте |
| `MOTD` | как `SERVER_NAME` | то же, если хочешь отдельно |
| `PUBLIC_HOST` | внешний IP (ipify) | домен или IP, который видят другие серверы / карта |
| `PUBLIC_PORT` | `25565` | Java-порт для игроков |
| `BEDROCK_PORT` | `19132` | UDP-порт Geyser |
| `MEMORY` | `1G` | память JVM, например `-e MEMORY=2G` |
| `FLOODGATE_KEY_B64` | (нет) | общий Floodgate `key.pem` в base64 — если Bedrock-игроки прыгают между «своими» серверами |

Пример со своим именем и доменом:

```bash
docker run -d --name minecraft_mvp --network host \
  -e EULA=TRUE \
  -e SERVER_NAME="Rainbow Forest" \
  -e PUBLIC_HOST=play.example.com \
  -v mvp-data:/data \
  mvsews/mvp
```

Мир и данные лежат в Docker-томе `mvp-data` — контейнер можно пересоздавать, сохранения останутся. Логи: `docker logs -f minecraft_mvp`. Остановить: `docker stop minecraft_mvp`.

---

## Типы порталов (таблички)

Первая строка таблички = тип. Рядом — **нажимная плита**.

| Пишешь | Что происходит |
|--------|----------------|
| `[Multi]` или `[mvp]` или `[portal]` | Один раз находит живой сервер и **держится** за него, пока не сломаешь табличку |
| `[To]` + IP / порт (или `server-id`) | Всегда идёт на этот адрес |
| `[Pair]` | Создаёт код — тот же код на другом сервере = туда-обратно |

**Ручка:** кнопка у случайного `[Multi]` перебиндивает цель (сначала пиры клуба MVP). Не работает на `[To]` / `[Pair]`.

Статус на табличке: `Portal` → `Scan...` → короткое имя цели + `->` (односторонний) или `<->` (пара).

Для публичных односторонних прыжков игроки один раз пишут **`/mvp ready`**.

---

## Связать два своих сервера (туда-обратно)

Лучший вариант: **`[Pair]`**.

**Сервер A**

1. Рамка + табличка + плита  
2. Строка 1: `[Pair]` (строка 2 пустая)  
3. Скопируй **код** из чата / с таблички  

**Сервер B**

1. То же самое  
2. Строка 1: `[Pair]`  
3. Строка 2: этот **код**  

Когда с обеих сторон `<->`, наступил на любую сторону — попадаешь к другому порталу и можешь вернуться.

На обоих серверах нужны `accept-transfers=true` и реальный `public-host`. В одну сеть они попадают через [mp.mvse.ws](https://mp.mvse.ws/).

<details>
<summary>Альтернатива: IP на табличке</summary>

| Строка | Текст |
|--------|-------|
| 1 | `Portal` |
| 2 | `1.2.3.4:25565` |

Или только адрес во 2-й строке, если порт по умолчанию `25565`. Старый вариант: хост во 2-й, порт в 3-й — тоже работает. Для настоящего туда-обратно на другой стороне тоже нужен обратный портал.
</details>

---

## Локальные порталы (тот же сервер)

Рамка из шерсти + настенная табличка (как ColorPortals):

1. **3×4 шерсти** одного цвета, табличка на верхнем среднем блоке  
2. Строка 1 = **имя**, строка 2 = **канал** (`0`–`9999`)  
3. Плита внутри — тот же цвет + канал = кольцо варпов на **этом** сервере  

`/mvp local list` · админы могут импортировать старые ColorPortals: `/mvp local import-colorportals`.

---

## Полезные команды

| Команда | Для чего |
|---------|----------|
| `/mvp version` | Установленная vs свежая на mp.mvse.ws |
| `/mvp ready` | Разрешить публичные односторонние переходы |
| `/mvp lang …` | Язык интерфейса (fallback сервера) |
| `/mvp settings` | Карта, гости, перенос инвентаря |
| `/mvp help` | Справка в игре |
| `/mvp update` | Скачать обновление jar (админ) |
| `/mvp scanner` | Статус публичного пула (админ) |

**Инвентарь:** по умолчанию не переносится. Включить: `/mvp settings export on` / `import on` (или `/mvp items …`).

---

## Документация

| Документ | Для кого |
|----------|----------|
| **[portal_guide.md](portal_guide.md)** | Игроки (RU) |
| **[portal_guide.en.md](portal_guide.en.md)** | Игроки (EN) |
| **[portal_guide.zh.md](portal_guide.zh.md)** | Игроки (中文) |
| **[docs/TECHNICAL.md](docs/TECHNICAL.md)** | Конфиг, производительность, сканеры, сборка |
| **[docs/SCANNERS.md](docs/SCANNERS.md)** | Публичные сканеры для `[Multi]` · сотрудничество |
| **[docs/REGISTRY.md](docs/REGISTRY.md)** | Публичный каталог (HTTPS) · только хаб |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | Как устроены части |

---

## Обратная связь

Идея, баг или предложение — [Issue на GitHub](https://github.com/mvsews/MultiversePortals/issues/new/choose).

---

## Лицензия

**MIT** — можно использовать, распространять и менять. См. [LICENSE](LICENSE).

---

<p align="center">
  <sub>Нужны игроки? Поставь плагин — твой мир станет частью большой межсерверной семьи.</sub>
</p>
