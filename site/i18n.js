window.MVP_I18N = {
  en: {
    docTitle: "Multiverse Portals — visit other Minecraft worlds",
    docDesc:
      "A free plugin for Paper. Put up a sign, walk into the portal, and travel to another server — no shared proxy needed.",
    brand: "Multiverse Portals",
    headline: "Many servers.<br>One portal network.",
    ledeShort:
      "No shared proxy, extra programs, or complex setup — just one plugin, and portals between real Minecraft servers work.",
    ledeStory:
      "Imagine: you build a portal in your world, step through, and you’re visiting friends on another server. Then a third world to explore — and home again, all through portals inside the game. <strong>From a portal you can go to another server, and players from other servers can arrive on yours!</strong>",
    download: "Download the plugin",
    source: "Source code",
    releases: "Releases",
    jarMetaDefault: "Free plugin · works with the map on this site",
    ideaTitle: "How it works",
    ideaBody:
      "Your server stays yours. You don’t need to move into someone else’s network. A portal works like a Nether portal: walk in, and the game sends you to another server. Line 1 is always <code>Portal</code>. Line 2 is where it goes: <code>Random</code> for a surprise visit, or a friend’s IP and port for a fixed destination. Read below for the details.",
    bullet1:
      "Play from a PC (Java) or phone / console (Bedrock via Geyser) — the plugin routes players by their client and respects the destination you set",
    bullet2:
      "If people can reach you from the internet, your world can appear on the map below — and you’ll see your neighbors!",
    bullet3:
      "Need portals only inside your own server? Wool between places, or an Away frame to another biome — both stay on this world",
    buildTitle: "How to build and use a portal",
    buildLead: "Three kinds: to another server, to another biome (Away), or wool between spots on this world. Walk into the purple opening — no pressure plate.",
    buildCrossTitle: "To another server",
    buildCrossIntro:
      "Build a closed frame like a Nether portal (a <strong>1×2</strong> peephole is enough; vanilla is <strong>2×3</strong>, giant rings work too). Hang the <strong>sign on the right jamb</strong> (looking at the portal). The hole lights up purple. Walk in to travel — a pressure plate is not needed.",
    schematicFront: "Front — sign on the right, purple sheet inside",
    schematicFrontKey:
      '<span class="key key--stone"></span> any solid block &nbsp; <span class="key key--sheet"></span> walk in here &nbsp; <span class="key key--sign"></span> sign',
    schematicTop: "Plate inside the portal",
    schematicPlateKey:
      '<span class="key key--plate"></span> pressure plate inside',
    buildSignTypes: "On the sign: <strong>line 1</strong> always <code>Portal</code>, <strong>line 2</strong> = where to go:",
    signMultiTitle: "Random server",
    signMultiBody:
      "Finds an open world once and <strong>remembers</strong> it. Want another? Put a <strong>button</strong> by the sign (Howl’s dial) or break the sign and place a new one.",
    signToTitle: "To a friend by IP",
    signToBody:
      "Line 2 = <code>IP:port</code> (or just the address if the port is the default <code>25565</code>). On their side they write your address the same way.",
    signAwayCardTitle: "To another biome",
    signAwayCardBody:
      "Same ring, but the frame is <strong>this biome’s block</strong>. Line 2 empty or <code>Away</code>. Stays on this server — details below.",
    signPairTitle: "Optional: link with a code",
    signPairBody:
      "Links two specific portals by server and portal code. On A leave line 3 empty — you get a code; on B write <code>Portal</code> / <code>Pair</code> / that code. For most people IP + port is easier.",
    activateTitle: "How to go through",
    activate1: "Walk into the purple sheet and wait a moment. Standing on the top of the frame does not send you.",
    activate2:
      'Only if the server requires it: type <code>/mvp ready</code> once in chat (allows one-way travel to a public world that has no portal back to yours). Off by default in config (<code>ready.confirm: false</code>) — you can turn confirmation on so players don’t get lost and know there is no return portal. If the other side has a return portal, this confirm is not needed.',
    activate3: "",
    buildAwayTitle: "Away — another biome on this server",
    buildAwayIntro:
      "Same closed ring, but the frame must be <strong>this biome’s block</strong>: oak logs in a forest, sandstone in a desert, packed ice on a glacier, and so on. Line 1 is <code>Portal</code>. Line 2 may be empty or <code>Away</code>. If line 2 is <code>Random</code>, it is a cross-server portal even on oak.",
    schematicAway: "Away frame (biome material)",
    schematicAwayKey:
      '<span class="key key--wood"></span> this biome’s block &nbsp; <span class="key key--sheet"></span> walk in &nbsp; <span class="key key--sign"></span> sign',
    buildAwayBody:
      "The first walk binds to one overworld biome (oceans and caves included; not Nether/End) and stays there, like a Nether portal. The plugin can build a small return ring from <strong>your</strong> biome’s material. Away portals are <strong>not</strong> shown on the public map. If the frame is the wrong block, chat tells you which one this biome needs.",
    buildLocalTitle: "Inside this server (wool)",
    buildLocalIntro:
      "Use this when you need portals between places on the <strong>same server</strong> (villages, bases). Frame of <strong>one wool color</strong>, any closed ring — the 3×4 doorway below is just an example. Hang the <strong>sign on the right jamb</strong> (looking at the portal). Walk into the purple sheet — no plate.",
    schematicLocal: "Wool portal (any size — 3×4 shown)",
    schematicLocalKey:
      '<span class="key key--wool"></span> same color wool &nbsp; <span class="key key--sign"></span> sign &nbsp; <span class="key key--sheet"></span> walk in',
    buildLocalSign:
      'Sign: <strong>line 1</strong> = name, <strong>line 2</strong> = channel number (<code>0</code>–<code>9999</code>). Same color + same channel = linked (A→B→C→A). Walk in (a button under the sign still works).',
    graphTitle: "Portal map",
    graphSub: "Live map · updates about once a minute",
    searchTitle: "Search by name or address",
    searchPlaceholder: "Name or address…",
    listLink: "Server list",
    searchNone: "Nothing found",
    loading: "Loading…",
    zoomOut: "Zoom out",
    zoomIn: "Zoom in",
    zoomReset: "Reset",
    fold: "Hide",
    unfold: "Show",
    foldTitle: "Hide portal links",
    unfoldTitle: "Show portal links",
    resetTitle: "Reset the map view",
    expandMapTitle: "Expand map",
    collapseMapTitle: "Exit expanded map",
    graphControls: "Map controls",
    graphAria: "Map of linked servers",
    empty: "Nobody on the map yet — your server could be the first.",
    legendPeer: "MVP server",
    legendExt: "World without the plugin",
    legendHint: "Scroll to zoom · drag empty space to move · drag a circle · click a server to hide or show its links",
    getTitle: "Join the map in three steps",
    dockerTitle: "Install in 1 minute (Docker)",
    dockerLead:
      "One command — Paper + Multiverse Portals + Geyser/Floodgate, then you’re on the map.",
    dockerNote:
      "Needs Docker on Linux (host network). Prefer a manual Paper install? Steps below.",
    copyCmd: "Copy",
    copiedCmd: "Copied",
    getLead:
      "Or install Paper yourself (1.21+), drop in this plugin, and allow guests to arrive from other worlds.",
    get1Title: "1. Get Paper and put the plugin in",
    get1Body:
      '<p>The server must be <strong>Paper</strong> 1.21+ (plain Vanilla won’t work).</p>'
      + '<p>Download Paper: <a href="https://papermc.io/downloads/paper" target="_blank" rel="noopener">papermc.io/downloads/paper</a></p>'
      + '<p>Download the plugin here (file <code>MultiversePortals.jar</code>):</p>'
      + '<p class="step-dl"><a class="btn btn--download" href="/download/MultiversePortals.jar?v=20260722x"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M5 21h14"/></svg><span>Download <code>MultiversePortals.jar</code></span></a></p>'
      + '<p>Copy it into the server’s <code>plugins</code> folder (create the folder if it’s missing):</p>'
      + '<pre class="path-tree">my-server/\n  paper.jar\n  server.properties\n  plugins/\n    MultiversePortals.jar   ← here</pre>',
    get2Title: "2. Allow players to arrive from other worlds",
    get2Body:
      '<p>Open <code>server.properties</code> in the <strong>root</strong> of the server (next to <code>paper.jar</code>), not inside <code>plugins</code>.</p>'
      + '<p>The plugin usually sets this for you on first start:</p>'
      + '<pre class="path-tree">accepts-transfers=true</pre>'
      + '<p>Then <strong>restart once</strong>. Without that line, other worlds can’t send players to you.</p>'
      + '<p>You usually don’t edit the plugin config. The address comes from <code>server-ip</code> and <code>server-port</code> in the same <code>server.properties</code>. Only if friends join you via a different domain: after the first start open <code>plugins/MultiversePortals/config.yml</code> and set <code>server.public-host</code>.</p>',
    get3Title: "3. Restart and check",
    get3Body:
      '<p>Stop the server completely, then start it again so the plugin loads.</p>'
      + '<p>In game type <code>/mvp settings</code> — you should see your address and whether the catalog is public or local-only.</p>'
      + '<p>If people can already join you from the internet, you’ll appear on the map below in a few minutes. Then build a portal (next section).</p>',
    getExtraTitle: "Want phone / console players too? (Geyser)",
    getExtraBody:
      '<p>Optional — only for Bedrock (phone, console, Windows Bedrock) joining your Java world.</p>'
      + '<p>Download <strong>Geyser</strong> and <strong>Floodgate</strong> from the official site: <a href="https://geysermc.org/download" target="_blank" rel="noopener">geysermc.org/download</a></p>'
      + '<p>Put both <code>.jar</code> files into the same <code>plugins/</code> folder and restart. Open UDP port <code>19132</code> on your host/router if Bedrock players connect from outside.</p>',
    fine:
      'Playing only at home or on a closed LAN? Portals still work — you just won’t be on this public map. To stay off on purpose, in <code>plugins/MultiversePortals/config.yml</code> set <code>server.list-publicly: never</code>.',
    footerApi: "For developers",
    footerNote: "Open worlds · map updates by itself",
    feedbackTitle: "Found a bug or have an idea?",
    feedbackLead:
      'Tell us on <strong>GitHub Issues</strong> — bug reports and suggestions are welcome. Leave a proposal if you want something new in the plugin.',
    feedbackBtn: "Open a GitHub Issue",
    feedbackBrowse: "Browse issues & ideas",
    supportTitle: "Support the plugin",
    supportLead:
      "Leave us a star on GitHub and on plugin platforms — and connect your servers with friends’ worlds. Let’s build a global portal network together.",
    supportHangar: "Hangar",
    supportModrinth: "Modrinth",
    supportCurseForge: "CurseForge",
    supportStar: "★ Star on GitHub",
    kindPeer: "MVP server",
    kindExt: "no plugin",
    collapsed: "links hidden · {n}",
    expanded: "links open · {n}",
    noPlugin: "no plugin",
    copyAddr: "Copy IP:port",
    copiedAddr: "Copied",
    stat: "{peers} MVP servers · {ext} other worlds · {live} fresh · {edges} portal links",
    playersUnit: "players",
    catalogDown: "The map is unavailable right now — try again in a moment.",
    jarUpdated: "updated",
    listDocTitle: "Server list — Multiverse Portals",
    listDocDesc: "Public portal servers: reputation, hops received and sent. Search by name or IP.",
    listHeading: "Servers",
    listLead: "Current reputation, how many hops a world accepted, and how many it sent. Search by name or address — case does not matter.",
    listBack: "← Map",
    listSearch: "Find by name or IP",
    listColName: "Server",
    listColAddr: "Address",
    listColRep: "Reputation",
    listColIn: "Received",
    listColOut: "Sent",
    listEmpty: "No servers in the catalog yet.",
    listNone: "Nothing matches this search.",
    listCount: "{n} servers",
    listOnline: "online",
    listOffline: "offline",
    catalogApi: "map data",
    langLabel: "Language",
  },
  ru: {
    docTitle: "Multiverse Portals — ходи в другие миры Minecraft",
    docDesc:
      "Бесплатный плагин для Paper. Поставь табличку, зайди в портал — и ты на другом сервере. Общий прокси не нужен.",
    brand: "Multiverse Portals",
    headline: "Много серверов.<br>Одна сеть порталов.",
    ledeShort:
      "Без общей прокси, программ и сложных настроек — только один плагин, и порталы между настоящими серверами Minecraft работают.",
    ledeStory:
      "Представь: в своём мире строишь портал, переходишь в него и попадаешь в другой мир — и ты уже в гостях у друзей на другом сервере. Потом — в третий, погуляться, и потом возвращаешься домой обратно на свой сервер — и всё это через порталы внутри игрового мира. <strong>Из портала ты можешь перейти в другой сервер, и из другого сервера могут прийти к тебе на сервер новые игроки!</strong>",
    download: "Скачать плагин",
    source: "Исходники",
    releases: "Релизы",
    jarMetaDefault: "Бесплатно · работает с картой на этом сайте",
    ideaTitle: "Как это устроено",
    ideaBody:
      "Твой сервер остаётся твоим. Никуда «в чужую сеть» переезжать не нужно. Портал — это как портал в ад: вошёл — и игра переносит тебя на другой сервер. Первая строка всегда <code>Portal</code>. Вторая — куда открыть портал: <code>Random</code> — наугад в гости; если укажешь IP и порт друга, портал будет вести на его сервер. Читай ниже, чтобы узнать подробнее.",
    bullet1:
      "Можно играть с компьютера (Java) и с телефона / консоли (Bedrock, через Geyser): плагин переводит игроков с учётом их клиента и пожеланий по точке назначения",
    bullet2:
      "Если к тебе заходят из интернета — твой мир может появиться на карте ниже, и ты увидишь своих соседей!",
    bullet3:
      "Нужны порталы только внутри своего сервера? Шерсть между местами, или рамка Away в другой биом — оба остаются в этом мире",
    buildTitle: "Как построить портал и пройти",
    buildLead: "Три вида: на другой сервер, в другой биом (Away) или шерсть между точками в этом мире. Зайди в фиолетовый проём — нажимная плита не нужна.",
    buildCrossTitle: "На другой сервер",
    buildCrossIntro:
      "Собери замкнутую рамку как у портала в Ад (хватит глазка <strong>1×2</strong>; обычный проём — <strong>2×3</strong>, большое кольцо тоже можно). Повесь <strong>табличку на правую стойку</strong> (если смотреть на портал). Дыра загорается фиолетовым. Чтобы уйти — зайди внутрь, плита не нужна.",
    schematicFront: "Спереди — табличка справа, фиолетовый лист в проёме",
    schematicFrontKey:
      '<span class="key key--stone"></span> любой твёрдый блок &nbsp; <span class="key key--sheet"></span> зайди сюда &nbsp; <span class="key key--sign"></span> табличка',
    schematicTop: "Плита внутри портала",
    schematicPlateKey:
      '<span class="key key--plate"></span> нажимная плита внутри',
    buildSignTypes: "На табличке: <strong>1-я строка</strong> всегда <code>Portal</code>, <strong>2-я</strong> — куда идти:",
    signMultiTitle: "Случайный сервер",
    signMultiBody:
      "Один раз находит открытый мир и <strong>запоминает</strong> его. Хочешь другой — поставь <strong>кнопку</strong> у таблички (ручка, как в Ходячем замке) или сломай табличку и поставь новую.",
    signToTitle: "К другу по IP",
    signToBody:
      "2-я строка — <code>IP:порт</code> (или просто адрес, если порт стандартный <code>25565</code>). У друга напиши свой адрес так же.",
    signAwayCardTitle: "В другой биом",
    signAwayCardBody:
      "Та же рамка, но из <strong>блока этого биома</strong>. 2-я строка пустая или <code>Away</code> / <code>Авей</code>. Остаёшься на этом сервере — подробности ниже.",
    signPairTitle: "По желанию: связка кодом",
    signPairBody:
      "Связка между двумя конкретными порталами по коду сервера и портала. На A 3-ю строку оставь пустой — получишь код; на B: <code>Portal</code> / <code>Pair</code> / этот код. Большинству проще IP и порт.",
    activateTitle: "Как пройти",
    activate1: "Зайди в фиолетовый лист и подожди пару секунд. Стоять на верхней перекладине рамки нельзя — так не уйдёшь.",
    activate2:
      'Только если сервер это требует: один раз в чат напиши <code>/mvp ready</code> (разрешает путешествия в один конец — one-way — на публичный мир, откуда нет портала на твой сервер). В конфиге по умолчанию выключено (<code>ready.confirm: false</code>) — в такие порталы можно переходить без подтверждения; можешь включить подтверждение, чтобы игроки не терялись и знали, что портала на обратной стороне не будет. Если на сервере есть обратный портал, такое подтверждение не требуется.',
    activate3: "",
    buildAwayTitle: "Away — другой биом на этом сервере",
    buildAwayIntro:
      "Такое же замкнутое кольцо, но рамка из <strong>блока этого биома</strong>: дуб в лесу, песчаник в пустыне, плотный лёд на леднике и так далее. 1-я строка — <code>Portal</code> / <code>Портал</code>. 2-я пустая или <code>Away</code> / <code>Авей</code>. Если на 2-й строке <code>Random</code> — это межсерверный портал, даже на дубе.",
    schematicAway: "Рамка Away (материал биома)",
    schematicAwayKey:
      '<span class="key key--wood"></span> блок этого биома &nbsp; <span class="key key--sheet"></span> зайди внутрь &nbsp; <span class="key key--sign"></span> табличка',
    buildAwayBody:
      "Первый проход привязывает к одному биому верхнего мира (океан и пещеры тоже; Ад и Край нет) и держит связь, как портал в Ад. Плагин может сам поставить маленькую обратную рамку из материала <strong>твоего</strong> биома. Away <strong>не</strong> показывается на публичной карте. Если рамка из не того блока — в чате напишет, какой нужен здесь.",
    buildLocalTitle: "Внутри этого сервера (шерсть)",
    buildLocalIntro:
      "Если нужны порталы между местами на <strong>этом же сервере</strong> (деревни, базы). Рамка из <strong>шерсти одного цвета</strong>, любое замкнутое кольцо — схема 3×4 ниже только пример. Повесь <strong>табличку на правую стойку</strong> (если смотреть на портал). Зайди в фиолетовый лист — плита не нужна.",
    schematicLocal: "Шерстяной портал (любой размер — на схеме 3×4)",
    schematicLocalKey:
      '<span class="key key--wool"></span> шерсть одного цвета &nbsp; <span class="key key--sign"></span> табличка &nbsp; <span class="key key--sheet"></span> зайди внутрь',
    buildLocalSign:
      'Табличка: <strong>1-я строка</strong> — имя, <strong>2-я</strong> — канал (<code>0</code>–<code>9999</code>). Один цвет + один канал = связь (A→B→C→A). Зайди в проём (кнопка под табличкой по желанию).',
    graphTitle: "Карта порталов",
    graphSub: "Живая карта · обновляется примерно раз в минуту",
    searchTitle: "Поиск по имени или адресу",
    searchPlaceholder: "Имя или адрес…",
    listLink: "Список серверов",
    searchNone: "Ничего не найдено",
    loading: "Загрузка…",
    zoomOut: "Отдалить",
    zoomIn: "Приблизить",
    zoomReset: "Сброс",
    fold: "Скрыть",
    unfold: "Показать",
    foldTitle: "Скрыть связи порталов",
    unfoldTitle: "Показать связи порталов",
    resetTitle: "Сбросить вид карты",
    expandMapTitle: "Развернуть карту",
    collapseMapTitle: "Свернуть карту",
    graphControls: "Управление картой",
    graphAria: "Карта связанных серверов",
    empty: "На карте пока никого — твой сервер может стать первым.",
    legendPeer: "Сервер с MVP",
    legendExt: "Мир без плагина",
    legendHint: "Колёсико — масштаб · пустое место — двигать карту · кружок — перетащить · клик по серверу — скрыть или показать связи",
    getTitle: "Как попасть на карту — три шага",
    dockerTitle: "Установка за 1 минуту (Docker)",
    dockerLead:
      "Одна команда — Paper + Multiverse Portals + Geyser/Floodgate, и ты уже на карте.",
    dockerNote:
      "Нужен Docker на Linux (host network). Хочешь поставить Paper вручную — шаги ниже.",
    copyCmd: "Копировать",
    copiedCmd: "Скопировано",
    getLead:
      "Или поставь Paper сам (1.21+), положи этот плагин и разреши гостям приходить из других миров.",
    get1Title: "1. Скачай Paper и положи плагин",
    get1Body:
      '<p>Сервер должен быть на <strong>Paper</strong> 1.21+ (обычный Vanilla не подойдёт).</p>'
      + '<p>Paper скачай здесь: <a href="https://papermc.io/downloads/paper" target="_blank" rel="noopener">papermc.io/downloads/paper</a></p>'
      + '<p>Плагин скачай здесь (файл <code>MultiversePortals.jar</code>):</p>'
      + '<p class="step-dl"><a class="btn btn--download" href="/download/MultiversePortals.jar?v=20260722x"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M5 21h14"/></svg><span>Скачать <code>MultiversePortals.jar</code></span></a></p>'
      + '<p>Скопируй его в папку <code>plugins</code> на сервере (если папки нет — создай):</p>'
      + '<pre class="path-tree">my-server/\n  paper.jar\n  server.properties\n  plugins/\n    MultiversePortals.jar   ← сюда</pre>',
    get2Title: "2. Разреши приходить из других миров",
    get2Body:
      '<p>Файл <code>server.properties</code> в <strong>корне</strong> сервера (рядом с <code>paper.jar</code>), не внутри <code>plugins</code>.</p>'
      + '<p>При первом запуске плагин сам обычно дописывает:</p>'
      + '<pre class="path-tree">accepts-transfers=true</pre>'
      + '<p>Потом <strong>перезапусти сервер один раз</strong>. Без этой строки к тебе нельзя будет прийти из другого мира.</p>'
      + '<p>Конфиг плагина обычно трогать не нужно: адрес берётся из <code>server-ip</code> и <code>server-port</code> в том же <code>server.properties</code>. Если друзья заходят к тебе по другому домену — после первого запуска открой <code>plugins/MultiversePortals/config.yml</code> и пропиши <code>server.public-host</code>.</p>',
    get3Title: "3. Перезапусти и проверь",
    get3Body:
      '<p>Полностью останови сервер и запусти снова — плагин подхватится.</p>'
      + '<p>В игре напиши <code>/mvp settings</code> — увидишь адрес и статус (публичный каталог или только локально).</p>'
      + '<p>Если к тебе уже заходят из интернета — через несколько минут появишься на карте ниже. Дальше строй портал (следующий раздел).</p>',
    getExtraTitle: "Нужны игроки с телефона / консоли? (Geyser)",
    getExtraBody:
      '<p>По желанию — если хочешь, чтобы с Bedrock (телефон, консоль, Windows Bedrock) заходили на твой Java-сервер.</p>'
      + '<p>Скачай <strong>Geyser</strong> и <strong>Floodgate</strong> с официального сайта: <a href="https://geysermc.org/download" target="_blank" rel="noopener">geysermc.org/download</a></p>'
      + '<p>Оба <code>.jar</code> положи в ту же папку <code>plugins/</code> и перезапусти. Для входа снаружи открой UDP-порт <code>19132</code> на роутере/хостинге.</p>',
    fine:
      'Играете только дома или в закрытой сети? Порталы всё равно работают — просто на эту публичную карту не попадёте. Чтобы специально не светиться, в <code>plugins/MultiversePortals/config.yml</code> поставь <code>server.list-publicly: never</code>.',
    footerApi: "Для разработчиков",
    footerNote: "Открытые миры · карта обновляется сама",
    feedbackTitle: "Нашли ошибку или есть идея?",
    feedbackLead:
      'Пишите в <strong>GitHub Issues</strong> — баги и предложения приветствуются. Оставьте предложение, если хотите что‑то новое в плагине.',
    feedbackBtn: "Открыть Issue на GitHub",
    feedbackBrowse: "Смотреть Issues и идеи",
    supportTitle: "Поддержите плагин",
    supportLead:
      "Оставьте нам звезду на GitHub и на площадках с плагинами — и соединяйте свои сервера с серверами друзей. Построим глобальную сеть вместе.",
    supportHangar: "Hangar",
    supportModrinth: "Modrinth",
    supportCurseForge: "CurseForge",
    supportStar: "★ Звезда на GitHub",
    kindPeer: "Сервер с MVP",
    kindExt: "без плагина",
    collapsed: "связи скрыты · {n}",
    expanded: "связи открыты · {n}",
    noPlugin: "без плагина",
    copyAddr: "Копировать IP:порт",
    copiedAddr: "Скопировано",
    stat: "{peers} серверов с MVP · {ext} других миров · {live} свежих · {edges} связей",
    playersUnit: "игроков",
    catalogDown: "Карта сейчас недоступна — загляни чуть позже.",
    jarUpdated: "обновлён",
    listDocTitle: "Список серверов — Multiverse Portals",
    listDocDesc: "Серверы сети порталов: репутация, сколько приняли и отправили переходов. Поиск по имени или IP.",
    listHeading: "Серверы",
    listLead: "Текущая репутация, сколько мир принял гостей и сколько отправил. Ищи по имени или адресу — регистр не важен.",
    listBack: "← Карта",
    listSearch: "Найти по имени или IP",
    listColName: "Сервер",
    listColAddr: "Адрес",
    listColRep: "Репутация",
    listColIn: "Принял",
    listColOut: "Отправил",
    listEmpty: "В каталоге пока нет серверов.",
    listNone: "По этому запросу ничего нет.",
    listCount: "{n} серверов",
    listOnline: "в сети",
    listOffline: "офлайн",
    catalogApi: "данные карты",
    langLabel: "Язык",
  },
  zh: {
    docTitle: "Multiverse Portals — 走进其他 Minecraft 世界",
    docDesc:
      "免费的 Paper 插件。立告示牌、走进传送门，即可前往另一台服务器——不需要共用代理。",
    brand: "Multiverse Portals",
    headline: "许多服务器。<br>一张传送门网络。",
    ledeShort:
      "不用共用代理、额外程序或复杂设置——只要一个插件，真实 Minecraft 服务器之间的传送门就能用。",
    ledeStory:
      "想象一下：在自己的世界建一座传送门，走进去就到了另一个世界——已经在朋友的服务器上做客。再到第三个世界逛逛，然后回到自己的服务器——全程都靠游戏里的传送门。<strong>你可以从传送门去别的服务器，别的服务器上的玩家也可以通过传送门来到你的服！</strong>",
    download: "下载插件",
    source: "源代码",
    releases: "发布",
    jarMetaDefault: "免费 · 与本站地图联动",
    ideaTitle: "怎么运作",
    ideaBody:
      "你的服务器还是你的，不用搬进别人的大网络。传送门就像下界门：走进去，游戏就把你送到另一台服务器。第一行永远写 <code>Portal</code>。第二行写目的地：<code>Random</code> 随机串门；写上朋友的 IP 和端口，就会固定连到他的服。详情见下方说明。",
    bullet1:
      "可用电脑（Java）或手机 / 主机（基岩版，经 Geyser）：插件会按客户端类型传送，并遵循你设定的目的地",
    bullet2:
      "如果别人能从互联网连到你——你的世界可能出现在下方地图上，你也能看到邻居！",
    bullet3:
      "只想在本服内传送？用羊毛连接地点，或用 Away 框架去另一个群系——都留在这个世界",
    buildTitle: "怎么搭建并使用传送门",
    buildLead: "三种：去别的服务器、去本服另一个群系（Away），或用羊毛连接本服地点。走进紫色门洞即可，不需要压力板。",
    buildCrossTitle: "去别的服务器",
    buildCrossIntro:
      "搭一个像下界门一样的闭合框架（<strong>1×2</strong> 窥孔就够；常见是 <strong>2×3</strong>，大圆环也可以）。把<strong>告示牌挂在右侧立柱</strong>（面向传送门时）。洞会亮起紫色。走进去即可传送——不需要压力板。",
    schematicFront: "正面 — 告示牌在右侧，紫色门面在开口里",
    schematicFrontKey:
      '<span class="key key--stone"></span> 任意实心方块 &nbsp; <span class="key key--sheet"></span> 从这里走进去 &nbsp; <span class="key key--sign"></span> 告示牌',
    schematicTop: "门内的压力板",
    schematicPlateKey:
      '<span class="key key--plate"></span> 门内压力板',
    buildSignTypes: "告示牌：<strong>第一行</strong>永远 <code>Portal</code>，<strong>第二行</strong>写去哪里：",
    signMultiTitle: "随机服务器",
    signMultiBody:
      "会找一个开放世界并<strong>记住</strong>。想换目标？在告示牌旁放<strong>按钮</strong>（拨盘），或拆掉告示牌重放一块。",
    signToTitle: "用 IP 去朋友的服",
    signToBody:
      "第二行写 <code>IP:端口</code>（默认端口 <code>25565</code> 时可只写地址）。对方同样写你的地址即可互访。",
    signAwayCardTitle: "去另一个群系",
    signAwayCardBody:
      "同样的圆环，但框架用<strong>本群系方块</strong>。第二行留空或写 <code>Away</code>。仍在本服——详见下方。",
    signPairTitle: "可选：用代码配对",
    signPairBody:
      "用服务器与传送门代码把两座具体传送门连在一起。A 服第三行留空会得到代码；B 服写 <code>Portal</code> / <code>Pair</code> / 该代码。对大多数人 IP + 端口更简单。",
    activateTitle: "怎么穿过",
    activate1: "走进紫色门面，稍等片刻。站在框架顶上不会传送。",
    activate2:
      '仅当服务器要求时：在聊天里输入一次 <code>/mvp ready</code>（允许单向前往没有返回门的公共世界）。默认关闭（配置 <code>ready.confirm: false</code>）——可不经确认进入；也可打开确认，避免玩家迷路并明确知道对面没有回程门。若对面有返回门，则不需要确认。',
    activate3: "",
    buildAwayTitle: "Away — 本服另一个群系",
    buildAwayIntro:
      "同样是闭合圆环，但框架必须是<strong>本群系的方块</strong>：森林用橡木原木，沙漠用砂岩，冰川用浮冰，以此类推。第一行写 <code>Portal</code> / <code>传送门</code>。第二行可空或写 <code>Away</code> / <code>异界</code>。若第二行是 <code>Random</code>，即使是橡木框也是跨服门。",
    schematicAway: "Away 框架（群系材料）",
    schematicAwayKey:
      '<span class="key key--wood"></span> 本群系方块 &nbsp; <span class="key key--sheet"></span> 走进去 &nbsp; <span class="key key--sign"></span> 告示牌',
    buildAwayBody:
      "第一次走进去会绑定到一个主世界群系（含海洋和洞穴，不含下界/末地），并像下界门一样记住。插件可用<strong>你出发群系</strong>的材料自动搭一座回程小环。Away <strong>不会</strong>出现在公开地图上。框架材料不对时，聊天会提示本群系需要哪种方块。",
    buildLocalTitle: "本服内（羊毛）",
    buildLocalIntro:
      "若需要在<strong>同一服务器</strong>的地点之间传送（村庄、基地）。用<strong>同色羊毛</strong>搭任意闭合圆环——下图 3×4 只是示例。把<strong>告示牌挂在右侧立柱</strong>（面向传送门时）。走进紫色门面即可——不需要压力板。",
    schematicLocal: "羊毛传送门（任意大小，图示 3×4）",
    schematicLocalKey:
      '<span class="key key--wool"></span> 同色羊毛 &nbsp; <span class="key key--sign"></span> 告示牌 &nbsp; <span class="key key--sheet"></span> 走进去',
    buildLocalSign:
      '告示牌：<strong>第一行</strong>名字，<strong>第二行</strong>频道数字（<code>0</code>–<code>9999</code>）。同色+同频道即相连（A→B→C→A）。走进去即可（告示牌下的按钮仍可用）。',
    graphTitle: "传送门地图",
    graphSub: "实时地图 · 大约每分钟刷新",
    searchTitle: "按名称或地址搜索",
    searchPlaceholder: "名称或地址…",
    listLink: "服务器列表",
    searchNone: "未找到",
    loading: "加载中…",
    zoomOut: "缩小",
    zoomIn: "放大",
    zoomReset: "重置",
    fold: "隐藏",
    unfold: "显示",
    foldTitle: "隐藏传送门连线",
    unfoldTitle: "显示传送门连线",
    resetTitle: "重置地图视图",
    expandMapTitle: "展开地图",
    collapseMapTitle: "退出全屏地图",
    graphControls: "地图控件",
    graphAria: "已连接服务器地图",
    empty: "地图上还没有人——你的服务器可以成为第一个。",
    legendPeer: "已装 MVP 的服",
    legendExt: "没有本插件的世界",
    legendHint: "滚轮缩放 · 拖空白移动 · 拖圆点 · 点击服务器隐藏/显示连线",
    getTitle: "三步上地图",
    dockerTitle: "一分钟安装（Docker）",
    dockerLead:
      "一条命令 — Paper + Multiverse Portals + Geyser/Floodgate，即可上地图。",
    dockerNote:
      "需要 Linux 上的 Docker（host network）。想手动装 Paper？看下面的步骤。",
    copyCmd: "复制",
    copiedCmd: "已复制",
    getLead:
      "或者自己安装 Paper（1.21+），放入本插件，并允许其他世界的客人进来。",
    get1Title: "1. 下载 Paper 并放入插件",
    get1Body:
      '<p>服务器必须是 <strong>Paper</strong> 1.21+（原版 Vanilla 不行）。</p>'
      + '<p>Paper 下载：<a href="https://papermc.io/downloads/paper" target="_blank" rel="noopener">papermc.io/downloads/paper</a></p>'
      + '<p>在此下载插件（文件 <code>MultiversePortals.jar</code>）：</p>'
      + '<p class="step-dl"><a class="btn btn--download" href="/download/MultiversePortals.jar?v=20260722x"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M5 21h14"/></svg><span>下载 <code>MultiversePortals.jar</code></span></a></p>'
      + '<p>复制到服务器的 <code>plugins</code> 文件夹（没有就新建）：</p>'
      + '<pre class="path-tree">my-server/\n  paper.jar\n  server.properties\n  plugins/\n    MultiversePortals.jar   ← 放这里</pre>',
    get2Title: "2. 允许从其他世界到达",
    get2Body:
      '<p>服务器<strong>根目录</strong>里的 <code>server.properties</code>（和 <code>paper.jar</code> 同级），不要进 <code>plugins</code>。</p>'
      + '<p>首次启动时，插件通常会自动写入：</p>'
      + '<pre class="path-tree">accepts-transfers=true</pre>'
      + '<p>然后<strong>重启一次</strong>。没有这一行，别的世界无法把玩家送来。</p>'
      + '<p>一般不用改插件配置。地址来自同一个 <code>server.properties</code> 里的 <code>server-ip</code> 和 <code>server-port</code>。只有朋友用另一个域名进你服时：首次启动后打开 <code>plugins/MultiversePortals/config.yml</code>，设置 <code>server.public-host</code>。</p>',
    get3Title: "3. 重启并检查",
    get3Body:
      '<p>完全关闭服务器再启动，让插件加载。</p>'
      + '<p>游戏里输入 <code>/mvp settings</code>——能看到地址，以及是公共目录还是仅本地。</p>'
      + '<p>如果互联网上的玩家本来就能进你的服，几分钟后会出现在下方地图。然后去搭传送门（下一节）。</p>',
    getExtraTitle: "也要手机 / 主机玩家？（Geyser）",
    getExtraBody:
      '<p>可选——只有当你希望基岩版（手机、主机、Windows 基岩）加入 Java 服时才需要。</p>'
      + '<p>从官网下载 <strong>Geyser</strong> 和 <strong>Floodgate</strong>：<a href="https://geysermc.org/download" target="_blank" rel="noopener">geysermc.org/download</a></p>'
      + '<p>两个 <code>.jar</code> 都放进同一个 <code>plugins/</code>，然后重启。若基岩玩家从外网进，请在主机/路由器上开放 UDP <code>19132</code>。</p>',
    fine:
      '只在家里或封闭局域网玩？传送门照常可用——只是不上这张公共地图。若想刻意不上榜，在 <code>plugins/MultiversePortals/config.yml</code> 设 <code>server.list-publicly: never</code>。',
    footerApi: "开发者",
    footerNote: "开放世界 · 地图自动更新",
    feedbackTitle: "发现了问题，或有改进想法？",
    feedbackLead:
      '欢迎到 <strong>GitHub Issues</strong> 反馈——报错与建议都欢迎。想加新功能也可以直接提提案。',
    feedbackBtn: "打开 GitHub Issue",
    feedbackBrowse: "浏览 Issues 与想法",
    supportTitle: "支持这个插件",
    supportLead:
      "请在 GitHub 和插件平台给我们点个星——也把你的服务器和朋友的世界连起来。一起建一张全球传送门网络。",
    supportHangar: "Hangar",
    supportModrinth: "Modrinth",
    supportCurseForge: "CurseForge",
    supportStar: "★ 在 GitHub 点星",
    kindPeer: "已装 MVP 的服",
    kindExt: "无插件",
    collapsed: "已隐藏连线 · {n}",
    expanded: "已显示连线 · {n}",
    noPlugin: "无插件",
    copyAddr: "复制 IP:端口",
    copiedAddr: "已复制",
    stat: "{peers} 台 MVP 服 · {ext} 个其他世界 · {live} 个较新 · {edges} 条传送门连线",
    playersUnit: "玩家",
    catalogDown: "地图暂时打不开——稍后再试。",
    jarUpdated: "更新于",
    listDocTitle: "服务器列表 — Multiverse Portals",
    listDocDesc: "传送门网络服务器：声誉、接收与发送的跳转次数。可按名称或 IP 搜索。",
    listHeading: "服务器",
    listLead: "当前声誉、该世界接收了多少次传送、发出了多少次。按名称或地址搜索，不区分大小写。",
    listBack: "← 地图",
    listSearch: "按名称或 IP 查找",
    listColName: "服务器",
    listColAddr: "地址",
    listColRep: "声誉",
    listColIn: "接收",
    listColOut: "发送",
    listEmpty: "目录里还没有服务器。",
    listNone: "没有匹配的结果。",
    listCount: "{n} 台服务器",
    listOnline: "在线",
    listOffline: "离线",
    catalogApi: "地图数据",
    langLabel: "语言",
  },
};

(function () {
  const KEY = "mvp-lang";
  const supported = ["en", "ru", "zh"];

  function detect() {
    const saved = localStorage.getItem(KEY);
    if (saved && supported.includes(saved)) return saved;
    const nav = (navigator.language || "en").toLowerCase();
    if (nav.startsWith("ru")) return "ru";
    if (nav.startsWith("zh")) return "zh";
    return "en";
  }

  function t(lang, key, vars) {
    const pack = window.MVP_I18N[lang] || window.MVP_I18N.en;
    let s = pack[key] != null ? pack[key] : (window.MVP_I18N.en[key] || key);
    if (vars) {
      s = s.replace(/\{(\w+)\}/g, (_, k) => (vars[k] != null ? String(vars[k]) : ""));
    }
    return s;
  }

  function apply(lang) {
    if (!supported.includes(lang)) lang = "en";
    localStorage.setItem(KEY, lang);
    document.documentElement.lang = lang === "zh" ? "zh-CN" : lang;
    document.body.dataset.lang = lang;
    const pack = window.MVP_I18N[lang];

    document.title = pack[document.documentElement.getAttribute("data-title-key") || "docTitle"] || pack.docTitle;
    const meta = document.querySelector('meta[name="description"]');
    if (meta) {
      const descKey = document.documentElement.getAttribute("data-desc-key") || "docDesc";
      meta.setAttribute("content", pack[descKey] || pack.docDesc);
    }

    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.getAttribute("data-i18n");
      if (!key) return;
      el.textContent = t(lang, key);
    });
    document.querySelectorAll("[data-i18n-html]").forEach((el) => {
      const key = el.getAttribute("data-i18n-html");
      if (!key) return;
      el.innerHTML = t(lang, key);
    });
    document.querySelectorAll("[data-i18n-title]").forEach((el) => {
      const key = el.getAttribute("data-i18n-title");
      if (!key) return;
      el.setAttribute("title", t(lang, key));
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
      const key = el.getAttribute("data-i18n-placeholder");
      if (!key) return;
      el.setAttribute("placeholder", t(lang, key));
    });
    document.querySelectorAll("[data-i18n-aria]").forEach((el) => {
      const key = el.getAttribute("data-i18n-aria");
      if (!key) return;
      el.setAttribute("aria-label", t(lang, key));
    });

    document.querySelectorAll(".lang-switch [data-lang]").forEach((btn) => {
      btn.setAttribute("aria-pressed", btn.getAttribute("data-lang") === lang ? "true" : "false");
      btn.classList.toggle("is-active", btn.getAttribute("data-lang") === lang);
    });

    window.dispatchEvent(new CustomEvent("mvp:lang", { detail: { lang } }));
  }

  window.mvpI18n = {
    lang: detect(),
    t(key, vars) {
      return t(this.lang, key, vars);
    },
    set(lang) {
      this.lang = lang;
      apply(lang);
    },
    apply() {
      apply(this.lang);
    },
  };

  function boot() {
    document.querySelectorAll(".lang-switch [data-lang]").forEach((btn) => {
      btn.addEventListener("click", () => window.mvpI18n.set(btn.getAttribute("data-lang")));
    });
    window.mvpI18n.apply();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
