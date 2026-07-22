package io.multiverseportals.command;

import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.federation.PeerClient;
import io.multiverseportals.model.*;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.util.PublicEndpoint;
import io.multiverseportals.util.ShapeHasher;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class MvpCommand implements CommandExecutor, TabCompleter {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final RegistryDatabase registry;
    private final PortalService portals;
    private final PeerClient peers;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public MvpCommand(
            MultiversePortalsPlugin plugin,
            PluginConfig config,
            Database db,
            RegistryDatabase registry,
            PortalService portals,
            PeerClient peers
    ) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.registry = registry;
        this.portals = portals;
        this.peers = peers;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            status(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "status", "stats" -> status(sender);
            case "version", "ver", "v" -> versionCmd(sender);
            case "update" -> {
                if (!sender.hasPermission("multiverseportals.admin")) {
                    return true;
                }
                var uc = plugin.updateChecker();
                if (uc == null) {
                    msg(sender, "<red>Update checker unavailable.</red>");
                    return true;
                }
                if (sender instanceof Player p) {
                    msg(sender, "<gray>Проверяю обновления…</gray>");
                    uc.downloadLatestForAdmin(p);
                } else {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        uc.refreshSafe();
                        var info = uc.latest();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (info.isEmpty()) {
                                msg(sender, config.message(sender, "update-check-failed"));
                            } else if (uc.isOutdatedMajorMinor()) {
                                msg(sender, config.message(sender, "update-available")
                                        .replace("%current%", uc.localVersion().normalizeDisplay())
                                        .replace("%latest%", info.get().version().normalizeDisplay()));
                            } else {
                                msg(sender, config.message(sender, "update-up-to-date")
                                        .replace("%current%", uc.localVersion().normalizeDisplay()));
                            }
                        });
                    });
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("multiverseportals.admin")) {
                    return true;
                }
                config.reload();
                msg(sender, "<green>Config reloaded.</green>");
            }
            case "info" -> msg(sender, "<gray>Server</gray> <aqua>" + config.serverId()
                    + "</aqua> <gray>fed</gray> <white>" + config.federationPort() + config.federationPath() + "</white>");
            case "trust" -> trust(sender, args);
            case "peers" -> peers(sender);
            case "policy" -> policy(sender, args);
            case "create" -> create(sender, args);
            case "pair" -> pair(sender, args);
            case "multi" -> multi(sender, args);
            case "list" -> list(sender);
            case "delete" -> delete(sender, args);
            case "score" -> score(sender, args);
            case "registry" -> registryCmd(sender, args);
            case "bindpreview", "bindorder" -> bindPreviewCmd(sender, args);
            case "items" -> itemsCmd(sender, args);
            case "settings" -> settingsCmd(sender, args);
            case "ready" -> readyCmd(sender, args);
            case "scanner" -> scannerCmd(sender, args);
            case "ingress" -> ingressCmd(sender, args);
            case "deny" -> denyCmd(sender, args);
            case "rep", "reputation" -> repCmd(sender, args);
            case "lang", "language" -> langCmd(sender, args);
            case "local" -> localCmd(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void localCmd(CommandSender sender, String[] args) {
        var local = plugin.localPortalService();
        if (local == null || !local.enabled()) {
            msg(sender, config.message(sender, "local-disabled"));
            return;
        }
        if (args.length < 2) {
            msg(sender, "<yellow>/mvp local list|import-colorportals</yellow>");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                var all = local.listAll();
                if (all.isEmpty()) {
                    msg(sender, config.message(sender, "local-list-empty"));
                    return;
                }
                msg(sender, config.message(sender, "local-list-header").replace("%n%", String.valueOf(all.size())));
                for (var p : all) {
                    String link = p.linkedPortalId() == null ? "INACTIVE"
                            : local.resolveLinked(p).map(io.multiverseportals.local.LocalPortal::name).orElse("?");
                    msg(sender, "<gray>" + p.color().name().toLowerCase() + "</gray> <white>"
                            + p.channel() + "." + p.node() + "</white> <aqua>" + p.name()
                            + "</aqua> <gray>→</gray> <white>" + link + "</white> <dark_gray>@"
                            + p.world() + " " + p.x() + "," + p.y() + "," + p.z() + "</dark_gray>");
                }
            }
            case "import-colorportals", "import" -> {
                if (!sender.hasPermission("multiverseportals.admin")) {
                    return;
                }
                java.io.File file = new java.io.File(
                        plugin.getDataFolder().getParentFile(),
                        "ColorPortals/Data/portals.yml");
                int n = local.importColorPortalsYaml(file);
                if (n < 0) {
                    msg(sender, config.message(sender, "local-import-fail") + " <gray>" + file.getAbsolutePath() + "</gray>");
                    return;
                }
                msg(sender, config.message(sender, "local-imported").replace("%n%", String.valueOf(n)));
            }
            default -> msg(sender, "<yellow>/mvp local list|import-colorportals</yellow>");
        }
    }

    private void langCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "<gray>Server fallback language:</gray> <aqua>" + config.language() + "</aqua>"
                    + " <gray>(en, de, ru, zh)</gray>");
            if (config.languagePerPlayer()) {
                msg(sender, "<gray>Players see messages in their Minecraft client language when available.</gray>");
            }
            msg(sender, "<yellow>/mvp lang en|de|ru|zh</yellow> <gray>— set server fallback</gray>");
            return;
        }
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        String raw = args[1].trim().toLowerCase(Locale.ROOT);
        boolean known = raw.equals("en") || raw.equals("de") || raw.equals("ru") || raw.equals("zh")
                || raw.equals("cn") || raw.equals("chinese")
                || raw.startsWith("en") || raw.startsWith("de") || raw.startsWith("ru") || raw.startsWith("zh");
        if (!known) {
            msg(sender, config.message(sender, "lang-unknown"));
            return;
        }
        String code = io.multiverseportals.i18n.Messages.normalize(raw);
        config.setLanguage(code);
        msg(sender, config.message(sender, "lang-set").replace("%lang%", code));
    }

    private void ingressCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        msg(sender, "<aqua>Ingress</aqua> " + (config.ingressEnabled() ? "<green>ON</green>" : "<red>OFF</red>"));
        int cap = config.ingressMaxOnline() <= 0 ? Bukkit.getMaxPlayers() : config.ingressMaxOnline();
        msg(sender, "<gray>max-online:</gray> <white>" + cap + "</white> <gray>reserve:</gray> <white>"
                + config.ingressReserveSlots() + "</white>");
        msg(sender, "<gray>min-score:</gray> <white>" + config.ingressMinScore() + "</white>"
                + " <gray>deny-unknown:</gray> <white>" + config.ingressDenyUnknownScore() + "</white>");
        int maxH = config.ingressMaxArrivalsPerHour();
        var ing = plugin.ingressPolicy();
        msg(sender, "<gray>Пришло на сервер:</gray> <white>"
                + (ing == null ? db.getTotalArrivals() : ing.totalArrivals()) + "</white>"
                + " <dark_gray>(всего за историю)</dark_gray>");
        msg(sender, "<gray>arrivals/hour:</gray> <white>"
                + (ing == null ? 0 : ing.arrivalsLastHour()) + "</white>"
                + (maxH > 0 ? "/" + maxH : " <gray>(без лимита)</gray>"));
        msg(sender, "<yellow>/mvp deny add|remove|list</yellow> <gray>·</gray> <yellow>/mvp rep &lt;nick&gt; &lt;±n&gt;</yellow>");
    }

    private void denyCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        if (args.length < 2) {
            msg(sender, "<yellow>/mvp deny add &lt;nick|uuid&gt; [reason]</yellow>");
            msg(sender, "<yellow>/mvp deny remove &lt;nick|uuid&gt;</yellow>");
            msg(sender, "<yellow>/mvp deny list</yellow>");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                for (String line : db.listDenied(20)) {
                    msg(sender, "<red>•</red> " + line);
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    msg(sender, "<red>Укажи nick или uuid.</red>");
                    return;
                }
                Optional<UUID> id = resolveUuid(args[2]);
                if (id.isEmpty()) {
                    msg(sender, "<red>Игрок не найден.</red>");
                    return;
                }
                String reason = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
                String name = args[2];
                Player online = Bukkit.getPlayer(id.get());
                if (online != null) {
                    name = online.getName();
                }
                db.denyPlayer(id.get(), name, reason);
                msg(sender, "<green>В denylist:</green> <aqua>" + name + "</aqua>");
                if (online != null) {
                    online.kick(mm.deserialize(config.prefix(online) + config.message(online, "ingress-denied")));
                }
            }
            case "remove", "del", "allow" -> {
                if (args.length < 3) {
                    msg(sender, "<red>Укажи nick или uuid.</red>");
                    return;
                }
                Optional<UUID> id = resolveUuid(args[2]);
                if (id.isEmpty()) {
                    msg(sender, "<red>Игрок не найден.</red>");
                    return;
                }
                db.allowPlayer(id.get());
                msg(sender, "<green>Убран из denylist.</green>");
            }
            default -> msg(sender, "<yellow>/mvp deny add|remove|list</yellow>");
        }
    }

    private void repCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        if (args.length < 3) {
            msg(sender, "<yellow>/mvp rep &lt;nick&gt; &lt;±число&gt;</yellow> <gray>— штраф/бонус к score на входе</gray>");
            return;
        }
        Optional<UUID> id = resolveUuid(args[1]);
        if (id.isEmpty()) {
            msg(sender, "<red>Игрок не найден.</red>");
            return;
        }
        double delta;
        try {
            delta = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            msg(sender, "<red>Число, например -50 или 20</red>");
            return;
        }
        db.addModReputation(id.get(), delta);
        double mod = db.modReputation(id.get());
        msg(sender, "<green>mod_rep</green> <aqua>" + args[1] + "</aqua> = <white>"
                + String.format(Locale.US, "%+.1f", mod) + "</white>");
    }

    private Optional<UUID> resolveUuid(String nameOrUuid) {
        try {
            return Optional.of(UUID.fromString(nameOrUuid));
        } catch (IllegalArgumentException ignored) {
        }
        Player p = Bukkit.getPlayerExact(nameOrUuid);
        if (p != null) {
            return Optional.of(p.getUniqueId());
        }
        @SuppressWarnings("deprecation")
        var offline = Bukkit.getOfflinePlayer(nameOrUuid);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return Optional.ofNullable(offline.getUniqueId());
        }
        return Optional.empty();
    }

    private void readyCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Только игрок.</red>");
            return;
        }
        var consent = plugin.consentService();
        if (consent == null) {
            return;
        }
        boolean off = args.length >= 2 && (args[1].equalsIgnoreCase("off")
                || args[1].equalsIgnoreCase("false")
                || args[1].equalsIgnoreCase("no"));
        consent.setReady(player, !off);
        msg(sender, !off ? config.message(sender, "ready-on") : config.message(sender, "ready-off"));
    }

    private void scannerCmd(CommandSender sender, String[] args) {
        var scan = plugin.scannerHub();
        if (scan == null || !config.scannerEnabled()) {
            msg(sender, "<red>Сканер выключен.</red>");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("refresh")) {
            if (!sender.hasPermission("multiverseportals.admin")) {
                return;
            }
            scan.refreshNow();
            msg(sender, "<green>Сканеры refresh запрошен (MineScan + Cornbread + Slowstack).</green>");
            return;
        }
        long ago = scan.lastRefreshMs() <= 0 ? -1
                : (System.currentTimeMillis() - scan.lastRefreshMs()) / 1000L;
        msg(sender, "<gray>Пул сканеров:</gray> <aqua>" + scan.cacheSize() + "</aqua>"
                + " <gray>(MineScan</gray> <white>" + scan.minescanSize()
                + "</white><gray> + Cornbread</gray> <white>" + scan.cornbreadSize()
                + "</white><gray> + Slowstack</gray> <white>" + scan.slowstackSize() + "</white><gray>)</gray>"
                + (ago >= 0 ? " <gray>· " + ago + "с назад</gray>" : ""));
        if (config.slowstackEnabled() && config.slowstackApiKey().isBlank()) {
            msg(sender, "<yellow>Slowstack:</yellow> <gray>нужен</gray> <white>scanner.slowstack.api-key</white>");
        }
        if (scan.lastError() != null && !scan.lastError().isBlank()) {
            msg(sender, "<red>Ошибка:</red> " + scan.lastError());
        }
        int[] mem = plugin.database().probeCacheStats();
        msg(sender, "<gray>Локальный каталог:</gray> всего <aqua>" + mem[0]
                + "</aqua> · pickable <green>" + (mem.length > 3 ? mem[3] : "?")
                + "</green> · ok <white>" + mem[1]
                + "</white> · dead <red>" + mem[2] + "</red> <gray>(мёртвые остаются)</gray>");
        int shown = 0;
        for (var e : plugin.database().listTopScored(5)) {
            String label = e.displayName() != null && !e.displayName().isBlank()
                    ? e.displayName() : e.host();
            if (label.length() > 28) {
                label = label.substring(0, 28);
            }
            msg(sender, "<yellow>" + String.format(Locale.ROOT, "%.0f", e.score())
                    + "</yellow> <white>" + e.host() + ":" + e.javaPort()
                    + "</white> <gray>" + e.status() + "</gray> <aqua>" + label + "</aqua>");
            shown++;
        }
        if (shown == 0) {
            msg(sender, "<gray>Каталог пуст — дождись refresh сканеров.</gray>");
        }
        int n = 0;
        for (var s : scan.cached()) {
            if (n++ >= 3) {
                break;
            }
            msg(sender, "<dark_gray>[" + s.source() + "]</dark_gray> <white>"
                    + s.host() + ":" + s.port() + "</white> <gray>"
                    + s.version() + " " + s.authMode() + "</gray>");
        }
    }

    /** /mvp items [export|import] [on|off] — глобальные правила вещей */
    private void itemsCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        if (args.length == 1) {
            msg(sender, "<gray>export (уносить отсюда):</gray> " + onOff(config.defaultExportInventory()));
            msg(sender, "<gray>import (принимать чужие):</gray> " + onOff(config.defaultImportInventory()));
            msg(sender, "<yellow>/mvp settings export|import on|off</yellow>");
            msg(sender, "<gray>На пира:</gray> <yellow>/mvp policy &lt;id&gt; exportInv|importInv true|false</yellow>");
            return;
        }
        if (args.length < 3) {
            msg(sender, "<yellow>/mvp settings export|import on|off</yellow>");
            return;
        }
        boolean value = parseOnOff(args[2]);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "export" -> setExport(sender, value);
            case "import" -> setImport(sender, value);
            default -> msg(sender, "<yellow>/mvp settings export|import on|off</yellow>");
        }
    }

    private void settingsCmd(CommandSender sender, String[] args) {
        if (args.length == 1) {
            settingsStatus(sender);
            if (sender.hasPermission("multiverseportals.admin")) {
                msg(sender, "<yellow>/mvp settings map on|off|auto</yellow>");
                msg(sender, "<yellow>/mvp settings guests on|off</yellow>");
                msg(sender, "<yellow>/mvp settings export on|off</yellow>");
                msg(sender, "<yellow>/mvp settings import on|off</yellow>");
            }
            return;
        }
        if (!sender.hasPermission("multiverseportals.admin")) {
            msg(sender, config.message(sender, "no-permission-travel"));
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3 && !key.equals("status")) {
            msg(sender, "<yellow>/mvp settings map|guests|export|import on|off</yellow>");
            return;
        }
        switch (key) {
            case "map", "catalog", "list", "public" -> setMap(sender, args[2]);
            case "guests", "guest", "accept", "inbound", "transfers" -> setGuests(sender, parseOnOff(args[2]));
            case "export" -> setExport(sender, parseOnOff(args[2]));
            case "import" -> setImport(sender, parseOnOff(args[2]));
            case "status" -> settingsStatus(sender);
            default -> msg(sender, "<yellow>/mvp settings map|guests|export|import on|off</yellow>");
        }
    }

    private void settingsStatus(CommandSender sender) {
        msg(sender, "<aqua>" + config.serverId() + "</aqua> <white>"
                + config.publicHost() + ":" + config.publicPort() + "</white>");
        msg(sender, "<gray>map:</gray> "
                + (config.shouldListPublicly() ? "<green>ON</green>" : "<red>OFF</red>")
                + " <dark_gray>(" + config.listPubliclyMode() + ")</dark_gray>");
        msg(sender, "<gray>guests:</gray> " + onOff(config.acceptInbound())
                + "  <gray>Paper Transfer:</gray> " + onOff(config.acceptTransfersEnabled())
                + (plugin.acceptTransfersRestartNeeded()
                ? " <yellow>(restart needed)</yellow>" : ""));
        msg(sender, "<gray>export inventory:</gray> " + onOff(config.defaultExportInventory())
                + "  <gray>import inventory:</gray> " + onOff(config.defaultImportInventory()));
        msg(sender, "<gray>require-trust:</gray> " + onOff(config.requireTrust())
                + "  <gray>create:</gray> " + onOff(config.everyoneCanCreate()));
        var overrides = config.peerOverrides();
        if (!overrides.isEmpty()) {
            msg(sender, "<gray>peer policies:</gray>");
            overrides.forEach((id, p) -> msg(sender, "  <aqua>" + id + "</aqua> travel=" + p.allowTravel()
                    + " exp=" + p.exportInventory() + " imp=" + p.importInventory()));
        }
    }

    private void setMap(CommandSender sender, String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        String mode;
        if (v.equals("on") || v.equals("true") || v.equals("1") || v.equals("yes")) {
            mode = "auto";
        } else if (v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no") || v.equals("never")) {
            mode = "never";
        } else if (v.equals("auto") || v.equals("always")) {
            mode = v;
        } else {
            msg(sender, "<yellow>/mvp settings map on|off|auto|always</yellow>");
            return;
        }
        boolean wasListed = config.shouldListPublicly();
        config.setListPubliclyMode(mode);
        boolean nowListed = config.shouldListPublicly();
        msg(sender, config.message(sender, "settings-map")
                .replace("%state%", nowListed ? "ON" : "OFF")
                .replace("%mode%", mode));
        if (plugin.catalogShareService() != null) {
            if (nowListed) {
                plugin.catalogShareService().pushNowAsync();
            } else if (wasListed || mode.equals("never")) {
                plugin.catalogShareService().notifyOfflineAsync();
            }
        }
    }

    private void setGuests(CommandSender sender, boolean value) {
        config.setAcceptInbound(value);
        try {
            PublicEndpoint.writeAcceptTransfers(value);
        } catch (Exception e) {
            msg(sender, "<red>Could not write server.properties: </red>" + e.getMessage());
        }
        if (value) {
            boolean runtime = config.acceptTransfersEnabled();
            plugin.setAcceptTransfersRestartNeeded(!runtime);
            msg(sender, config.message(sender, "settings-guests-on"));
            if (!runtime) {
                msg(sender, config.message(sender, "accept-transfers-restart"));
            }
            if (plugin.catalogShareService() != null && config.shouldListPublicly()) {
                plugin.catalogShareService().pushNowAsync();
            }
        } else {
            plugin.setAcceptTransfersRestartNeeded(false);
            msg(sender, config.message(sender, "settings-guests-off"));
            if (plugin.catalogShareService() != null) {
                plugin.catalogShareService().notifyOfflineAsync();
                if (config.shouldListPublicly()) {
                    plugin.catalogShareService().pushNowAsync();
                }
            }
        }
    }

    private void setExport(CommandSender sender, boolean value) {
        config.setDefaultExportInventory(value);
        msg(sender, config.message(sender, "settings-export").replace("%state%", value ? "ON" : "OFF"));
        if (plugin.catalogShareService() != null && config.shouldListPublicly()) {
            plugin.catalogShareService().pushNowAsync();
        }
    }

    private void setImport(CommandSender sender, boolean value) {
        config.setDefaultImportInventory(value);
        msg(sender, config.message(sender, "settings-import").replace("%state%", value ? "ON" : "OFF"));
        if (plugin.catalogShareService() != null && config.shouldListPublicly()) {
            plugin.catalogShareService().pushNowAsync();
        }
    }

    private static boolean parseOnOff(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("1") || v.equals("yes");
    }

    private static String onOff(boolean v) {
        return v ? "<green>ON</green>" : "<red>OFF</red>";
    }

    private void trust(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        // /mvp trust <id> <federationUrl> <publicHost> <publicPort> <sharedSecret> [hasPlugin]
        if (args.length < 6) {
            msg(sender, "<yellow>/mvp trust <id> <fedUrl> <host> <port> <secret> [hasPlugin]</yellow>");
            return;
        }
        boolean hasPlugin = args.length < 7 || Boolean.parseBoolean(args[6]);
        TrustedPeer peer = new TrustedPeer(
                args[1],
                args[1],
                args[2],
                args[3],
                Integer.parseInt(args[4]),
                args[5],
                hasPlugin
        );
        peer.setTheirPolicy(config.defaults());
        db.upsertPeer(peer);
        peers.fetchPolicy(peer).ifPresent(p -> {
            peer.setTheirPolicy(p);
            db.upsertPeer(peer);
        });
        msg(sender, "<green>Trusted peer</green> <aqua>" + peer.serverId() + "</aqua>");
    }

    private void peers(CommandSender sender) {
        for (TrustedPeer p : db.listPeers()) {
            msg(sender, "<aqua>" + p.serverId() + "</aqua> <gray>" + p.publicHost() + ":" + p.publicPort()
                    + "</gray> plugin=" + p.hasPlugin());
        }
    }

    private void policy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        // /mvp policy <peer> <flag> <true|false>  — опционально поверх глобальных defaults
        if (args.length == 2) {
            var p = config.policyFor(args[1]);
            msg(sender, "<aqua>" + args[1] + "</aqua> travel=" + onOff(p.allowTravel())
                    + " export=" + onOff(p.exportInventory())
                    + " import=" + onOff(p.importInventory()));
            return;
        }
        if (args.length < 4) {
            msg(sender, "<yellow>/mvp policy &lt;peer&gt; &lt;travel|exportInv|importInv|exportScore|importScore&gt; true|false</yellow>");
            return;
        }
        String peer = args[1];
        boolean value = args[3].equalsIgnoreCase("true") || args[3].equalsIgnoreCase("on") || args[3].equals("1");
        String yamlKey = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "allowtravel", "travel" -> "allow-travel";
            case "exportinv", "export", "export-inventory" -> "export-inventory";
            case "importinv", "import", "import-inventory" -> "import-inventory";
            case "exportscore" -> "export-score";
            case "importscore" -> "import-score";
            default -> null;
        };
        if (yamlKey == null) {
            msg(sender, "<red>Unknown flag</red>");
            return;
        }
        config.setPeerPolicyFlag(peer, yamlKey, value);
        msg(sender, "<green>peers." + peer + "." + yamlKey + "</green> = " + onOff(value));
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.</red>");
            return;
        }
        if (!player.hasPermission("multiverseportals.create")) {
            return;
        }
        // /mvp create pair|multi [name]
        if (args.length < 2) {
            msg(sender, "<yellow>/mvp create <pair|multi> [name]</yellow>");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !ShapeHasher.looksLikePortalSign(target)) {
            msg(sender, "<red>Look at a sign with first line [Pair] or [Multi].</red>");
            return;
        }
        PortalType type = args[1].equalsIgnoreCase("multi") ? PortalType.MULTI : PortalType.PAIR;
        String name = args.length >= 3 ? args[2] : "portal";
        Portal portal = portals.createFromSign(player, target, type, name);
        if (type == PortalType.PAIR) {
            msg(player, "<green>PAIR created.</green> invite: <aqua>" + portal.pairInviteCode() + "</aqua> id=<gray>" + portal.id() + "</gray>");
        } else {
            msg(player, "<green>MULTI created.</green> id=<gray>" + portal.id() + "</gray>");
            msg(player, config.message(player, "bind-searching"));
            if (plugin.portalBindService() != null) {
                plugin.portalBindService().startBind(portal, player);
            }
        }
    }

    private void pair(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("multiverseportals.create")) {
            return;
        }
        if (args.length < 2) {
            msg(sender, "<yellow>/mvp pair invite | /mvp pair accept <code> <remoteServer></yellow>");
            return;
        }
        if (args[1].equalsIgnoreCase("invite")) {
            Block target = player.getTargetBlockExact(6);
            if (target == null) {
                msg(sender, "<red>Look at your portal sign.</red>");
                return;
            }
            Optional<Portal> opt = db.findPortalByFrame(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
            if (opt.isEmpty() || opt.get().type() != PortalType.PAIR) {
                msg(sender, "<red>No PAIR portal here. /mvp create pair</red>");
                return;
            }
            String code = portals.beginPairInvite(opt.get());
            msg(sender, "<green>Share code</green> <aqua>" + code + "</aqua> <gray>and your server id</gray> <white>" + config.serverId() + "</white>");
            return;
        }
        if (args[1].equalsIgnoreCase("accept") && args.length >= 4) {
            String code = args[2];
            String remoteServer = args[3];
            Block target = player.getTargetBlockExact(6);
            if (target == null) {
                msg(sender, "<red>Look at your portal sign.</red>");
                return;
            }
            Optional<Portal> localOpt = db.findPortalByFrame(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
            if (localOpt.isEmpty()) {
                localOpt = Optional.of(portals.createFromSign(player, target, PortalType.PAIR, "pair"));
            }
            Portal local = localOpt.get();
            Optional<TrustedPeer> peerOpt = db.findPeer(remoteServer);
            if (peerOpt.isEmpty()) {
                msg(sender, "<red>Trust the peer first: /mvp trust ...</red>");
                return;
            }
            TrustedPeer peer = peerOpt.get();
            JsonObject resolve = new JsonObject();
            resolve.addProperty("inviteCode", code);
            resolve.addProperty("acceptorPortalId", local.id());
            resolve.addProperty("acceptorServerId", config.serverId());
            Optional<JsonObject> resp = peers.post(peer, "/portal/pair/resolve", resolve);
            if (resp.isEmpty() || resp.get().has("error") || !resp.get().has("portalId")) {
                msg(sender, "<red>Remote resolve failed. Check invite code / trust / federation URL.</red>");
                return;
            }
            String remotePortalId = resp.get().get("portalId").getAsString();
            portals.acceptPairLocally(local, remoteServer, remotePortalId);
            msg(sender, "<green>PAIR linked with</green> <aqua>" + remoteServer + "</aqua> <gray>" + remotePortalId.substring(0, 8) + "</gray>");
        }
    }

    private void multi(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("multiverseportals.create")) {
            return;
        }
        // /mvp multi pool <server,server,...>
        if (args.length < 3 || !args[1].equalsIgnoreCase("pool")) {
            msg(sender, "<yellow>/mvp multi pool <id,id,...></yellow> (look at multi portal sign)");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            return;
        }
        Optional<Portal> opt = db.findPortalByFrame(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        if (opt.isEmpty() || opt.get().type() != PortalType.MULTI) {
            msg(sender, "<red>No MULTI portal here.</red>");
            return;
        }
        Portal portal = opt.get();
        portal.multiPool().clear();
        for (String id : args[2].split(",")) {
            if (!id.isBlank()) {
                portal.multiPool().add(id.trim());
            }
        }
        db.savePortal(portal);
        msg(sender, "<green>Multi pool set:</green> <white>" + String.join(", ", portal.multiPool()) + "</white>");
    }

    private void list(CommandSender sender) {
        for (Portal p : portals.list()) {
            msg(sender, "<aqua>" + p.type() + "</aqua> <white>" + p.name() + "</white> <gray>" + p.id().substring(0, 8)
                    + "</gray> " + p.status() + " @ " + p.frame().key());
        }
    }

    private void bindPreviewCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        var bind = plugin.portalBindService();
        if (bind == null) {
            msg(sender, "<red>Bind service unavailable.</red>");
            return;
        }
        boolean bedrock = false;
        String portalId = null;
        int limit = 25;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("bedrock") || a.equalsIgnoreCase("bed")) {
                bedrock = true;
            } else if (a.startsWith("limit=")) {
                try {
                    limit = Integer.parseInt(a.substring(6));
                } catch (NumberFormatException ignored) {
                }
            } else if (!a.contains("=")) {
                portalId = a;
            }
        }
        limit = Math.max(1, Math.min(80, limit));
        boolean finalBedrock = bedrock;
        String finalPortalId = portalId;
        int finalLimit = limit;
        msg(sender, "<gray>Building bind order…</gray>");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var json = bind.previewBindOrder(finalBedrock, null, finalPortalId, finalLimit, false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                msg(sender, "<aqua>Bind preview</aqua> pool=<white>" + json.get("poolSize").getAsInt()
                        + "</white> club=<white>" + json.get("clubSize").getAsInt()
                        + "</white> needBedrock=<white>" + json.get("needBedrock").getAsBoolean()
                        + "</white> requireGeyser=<white>" + json.get("requireGeyser").getAsBoolean()
                        + "</white>");
                var arr = json.getAsJsonArray("candidates");
                int shown = 0;
                for (var el : arr) {
                    var o = el.getAsJsonObject();
                    if (!o.get("wouldProbe").getAsBoolean() && shown >= 12) {
                        continue;
                    }
                    shown++;
                    String skip = o.has("skip") ? " <red>skip=" + o.get("skip").getAsString() + "</red>" : "";
                    String club = o.get("hasPlugin").getAsBoolean() ? "<green>club</green>" : "<gray>ext</gray>";
                    msg(sender, "<gray>#" + o.get("rank").getAsInt() + "</gray> "
                            + club + " <white>" + o.get("host").getAsString() + ":" + o.get("javaPort").getAsInt()
                            + "</white> <dark_gray>" + o.get("tier").getAsString() + "</dark_gray>"
                            + (o.get("wouldProbe").getAsBoolean() ? " <yellow>probe</yellow>" : skip));
                    if (shown >= 25) {
                        break;
                    }
                }
                msg(sender, "<gray>API:</gray> <white>GET "
                        + config.federationPath() + "/bind/preview?bedrock=0&limit=40</white>");
            });
        });
    }

    private void delete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin") || args.length < 2) {
            msg(sender, "<yellow>/mvp delete <portalId></yellow>");
            return;
        }
        portals.delete(args[1]);
        msg(sender, "<green>Deleted.</green>");
    }

    private void score(CommandSender sender, String[] args) {
        if (sender instanceof Player player) {
            double s = db.localScore(player.getUniqueId());
            msg(sender, "<gray>Your local wealth score:</gray> <gold>" + String.format(Locale.US, "%.1f", s) + "</gold>");
        }
    }

    private void registryCmd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        if (!registry.enabled()) {
            msg(sender, "<red>Shared registry disabled in config.</red>");
            return;
        }
        if (args.length < 2) {
            msg(sender, "<yellow>/mvp registry list|portals|graph|returns|announce|optin|optout</yellow>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                boolean includeStale = args.length >= 3 && "all".equalsIgnoreCase(args[2]);
                var rows = includeStale
                        ? registry.listAllAny(100)
                        : registry.listAll(Math.max(config.registryStaleMs(), 600_000L));
                if (rows.isEmpty()) {
                    msg(sender, "<gray>Registry empty.</gray>");
                    return;
                }
                msg(sender, "<gray>Registry</gray> <dark_gray>(" + rows.size()
                        + (includeStale ? ", incl. stale" : ", last 10m") + ")</dark_gray>"
                        + " <dark_gray>— last ping age</dark_gray>");
                long now = System.currentTimeMillis();
                for (RegistryServer rs : rows) {
                    String via = (rs.hasViaVersion() ? "V" : "")
                            + (rs.hasViaBackwards() ? "B" : "")
                            + (rs.hasViaRewind() ? "R" : "");
                    if (via.isEmpty()) {
                        via = "-";
                    }
                    long ageMs = rs.lastHeartbeat() > 0 ? Math.max(0, now - rs.lastHeartbeat()) : -1;
                    boolean fresh = ageMs >= 0 && ageMs <= config.registryStaleMs();
                    String ageTag = fresh ? "green" : (ageMs < 0 ? "dark_gray" : "yellow");
                    msg(sender, (rs.multiOptIn() ? "<green>●</green> " : "<red>○</red> ")
                            + "<aqua>" + rs.serverId() + "</aqua> "
                            + "<white>" + rs.publicHost() + ":" + rs.publicPort() + "</white> "
                            + "<gray>" + (rs.mcVersion() == null ? "?" : rs.mcVersion())
                            + " p" + rs.protocolId() + " via=" + via + "</gray> "
                            + "<" + ageTag + ">ping " + formatPingAge(ageMs) + "</" + ageTag + ">");
                    msg(sender, "  <dark_gray>" + rs.caps().shortLabel()
                            + " inv=" + (rs.caps().exportInventory() ? "E" : "-")
                            + (rs.caps().importInventory() ? "I" : "-") + "</dark_gray>");
                }
                if (!includeStale) {
                    msg(sender, "<dark_gray>/mvp registry list all — include stale</dark_gray>");
                }
            }
            case "portals" -> {
                String sid = args.length >= 3 ? args[2] : config.serverId();
                var rows = registry.listPortalsOnServer(sid);
                if (rows.isEmpty()) {
                    msg(sender, "<gray>No portals published for</gray> <aqua>" + sid + "</aqua>");
                    return;
                }
                msg(sender, "<aqua>" + sid + "</aqua> <gray>portals (" + rows.size() + ")</gray>");
                for (var rp : rows) {
                    String dest = rp.destServerId() != null && !rp.destServerId().isBlank()
                            ? rp.destServerId()
                            : (rp.destHost() == null ? "—" : rp.destHost() + ":" + rp.destPort());
                    msg(sender, "<white>" + rp.portalType() + "</white> "
                            + "<gray>" + rp.coordKey() + "</gray> "
                            + "<yellow>→ " + dest + "</yellow> "
                            + (rp.returnCapable() ? "<green>[return]</green>" : "<red>[one-way]</red>")
                            + " <dark_gray>" + rp.status() + "</dark_gray>");
                    if (rp.hasSigns()) {
                        msg(sender, "  <gray>signs:</gray> <white>" + String.join("</white><gray>, </gray><white>", rp.signs()) + "</white>");
                    }
                }
            }
            case "graph" -> {
                var rows = registry.listPortals(200);
                if (rows.isEmpty()) {
                    msg(sender, "<gray>Portal graph empty.</gray>");
                    return;
                }
                msg(sender, "<gray>Portal graph edges (" + rows.size() + ")</gray>");
                int shown = 0;
                for (var rp : rows) {
                    if (!"ACTIVE".equalsIgnoreCase(rp.status()) && !"BINDING".equalsIgnoreCase(rp.status())) {
                        continue;
                    }
                    msg(sender, "<dark_gray>•</dark_gray> <white>" + rp.edgeLabel() + "</white>");
                    if (++shown >= 40) {
                        msg(sender, "<dark_gray>… truncated</dark_gray>");
                        break;
                    }
                }
            }
            case "returns" -> {
                String sid = args.length >= 3 ? args[2] : config.serverId();
                var inbound = registry.listInboundTo(sid);
                if (inbound.isEmpty()) {
                    msg(sender, "<gray>No known return paths to</gray> <aqua>" + sid + "</aqua>"
                            + "<gray> (need other MVP servers with portals pointing here)</gray>");
                    return;
                }
                msg(sender, "<green>Return paths → " + sid + "</green> <gray>(" + inbound.size() + ")</gray>");
                for (var rp : inbound) {
                    msg(sender, "<aqua>" + rp.serverId() + "</aqua> "
                            + "<gray>" + rp.coordKey() + "</gray> "
                            + "<white>" + rp.portalType() + "</white> "
                            + (rp.returnCapable() ? "<green>marked return</green>" : "<yellow>edge exists</yellow>"));
                }
            }
            case "announce" -> {
                String motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(Bukkit.motd());
                var vc = plugin.versionCompat();
                if (vc == null) {
                    msg(sender, "<red>VersionCompat not ready.</red>");
                    return;
                }
                registry.announceSelf(
                        Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), motd,
                        vc.mcVersion(), vc.nativeProtocol(),
                        vc.viaVersion(), vc.viaBackwards(), vc.viaRewind(),
                        vc.viaMin(), vc.viaMax(),
                        io.multiverseportals.compat.ServerCaps.detect(config)
                );
                int portals = registry.syncPortals(db.listPortals(), collectCommentSignsForCmd());
                msg(sender, "<green>Announced to shared registry.</green> <gray>"
                        + vc.mcVersion() + " p" + vc.nativeProtocol() + " · "
                        + io.multiverseportals.compat.ServerCaps.detect(config).shortLabel()
                        + " · portals=" + portals + "</gray>");
            }
            case "optin" -> {
                config.setRegistryMultiOptIn(true);
                registry.setMultiOptIn(true);
                var vc = plugin.versionCompat();
                if (vc != null) {
                    registry.announceSelf(
                            Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), "",
                            vc.mcVersion(), vc.nativeProtocol(),
                            vc.viaVersion(), vc.viaBackwards(), vc.viaRewind(),
                            vc.viaMin(), vc.viaMax(),
                            io.multiverseportals.compat.ServerCaps.detect(config)
                    );
                }
                msg(sender, "<green>multi-opt-in ON — this server is in the random MULTI pool.</green>");
            }
            case "optout" -> {
                config.setRegistryMultiOptIn(false);
                registry.setMultiOptIn(false);
                msg(sender, "<yellow>multi-opt-in OFF — removed from random MULTI pool.</yellow>");
            }
            default -> msg(sender, "<yellow>/mvp registry list|portals|graph|returns|announce|optin|optout</yellow>");
        }
    }

    private void status(CommandSender sender) {
        int local = 0;
        var localSvc = plugin.localPortalService();
        if (localSvc != null && localSvc.enabled()) {
            local = localSvc.listAll().size();
        }

        List<Portal> fed = portals.list();
        int cross = fed.size();
        int bound = 0;
        int pair = 0;
        int scanning = 0;
        for (Portal p : fed) {
            if (p.type() == PortalType.PAIR) {
                pair++;
            }
            if (p.hasBoundDestination() || (p.type() == PortalType.PAIR && p.pairServerId() != null)) {
                bound++;
            }
            if (p.status() == PortalStatus.BINDING || p.status() == PortalStatus.BIND_FAILED) {
                scanning++;
            }
        }
        int total = local + cross;

        int inboundPortals = 0;
        int inboundServers = 0;
        if (registry.enabled()) {
            var inbound = registry.listInboundTo(config.serverId());
            inboundPortals = inbound.size();
            java.util.LinkedHashSet<String> servers = new java.util.LinkedHashSet<>();
            for (var rp : inbound) {
                if (rp.serverId() != null && !rp.serverId().isBlank()) {
                    servers.add(rp.serverId());
                }
            }
            inboundServers = servers.size();
        }

        msg(sender, "<aqua>Multiverse Portals</aqua> <dark_gray>·</dark_gray> <white>" + config.serverId() + "</white>");
        msg(sender, "<gray>Всего порталов:</gray> <white>" + total + "</white>"
                + " <dark_gray>(локальных "
                + local + " · на другие серверы " + cross + ")</dark_gray>");
        msg(sender, "<gray>Локальные (шерсть):</gray> <white>" + local + "</white>");
        msg(sender, "<gray>На другие серверы:</gray> <white>" + cross + "</white>"
                + (bound > 0 ? " <green>(" + bound + " с линком)</green>" : "")
                + (pair > 0 ? " <aqua>· pair " + pair + "</aqua>" : "")
                + (scanning > 0 ? " <yellow>· ищут " + scanning + "</yellow>" : ""));
        msg(sender, "<gray>Пришло на сервер:</gray> <white>" + db.getTotalArrivals() + "</white>");
        if (registry.enabled()) {
            msg(sender, "<gray>Ссылаются на нас:</gray> <white>" + inboundServers + "</white>"
                    + " <gray>сервер(ов)</gray>"
                    + (inboundPortals > 0
                    ? " <dark_gray>(" + inboundPortals + " портал(ов))</dark_gray>"
                    : " <dark_gray>(пока нет inbound в registry)</dark_gray>"));
        } else {
            msg(sender, "<gray>Ссылаются на нас:</gray> <dark_gray>registry выключен</dark_gray>");
        }
        msg(sender, "<dark_gray>/mvp help — команды</dark_gray>");
    }

    /** /mvp version — current jar vs latest on mp.mvse.ws (anyone). */
    private void versionCmd(CommandSender sender) {
        var uc = plugin.updateChecker();
        String current = uc != null
                ? uc.localVersion().normalizeDisplay()
                : plugin.getPluginMeta().getVersion();
        msg(sender, config.message(sender, "version-current").replace("%current%", current));
        if (uc == null) {
            return;
        }
        msg(sender, config.message(sender, "version-checking"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            uc.refreshSafe();
            var info = uc.latest();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (info.isEmpty()) {
                    msg(sender, config.message(sender, "update-check-failed"));
                    return;
                }
                String latest = info.get().version().normalizeDisplay();
                msg(sender, config.message(sender, "version-latest").replace("%latest%", latest));
                int cmp = uc.localVersion().compareTo(info.get().version());
                if (cmp >= 0) {
                    msg(sender, config.message(sender, "version-up-to-date"));
                } else {
                    msg(sender, config.message(sender, "version-outdated")
                            .replace("%current%", current)
                            .replace("%latest%", latest));
                    String lang = config.messages() != null && sender instanceof Player p
                            ? config.messages().langFor(p)
                            : config.language();
                    String whats = info.get().whatsNew().getOrDefault(lang,
                            info.get().whatsNew().getOrDefault("en", ""));
                    if (whats != null && !whats.isBlank()) {
                        msg(sender, config.message(sender, "update-whats-new")
                                .replace("%whats_new%", whats
                                        .replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>")));
                    }
                    if (sender.hasPermission("multiverseportals.admin")) {
                        msg(sender, config.message(sender, "update-cmd-hint"));
                    } else {
                        String page = info.get().pageUrl() == null ? "mp.mvse.ws" : info.get().pageUrl()
                                .replace("https://", "").replace("http://", "").replaceAll("/$", "");
                        msg(sender, config.message(sender, "update-download").replace("%url%", page));
                    }
                }
            });
        });
    }

    private void help(CommandSender sender) {
        msg(sender, "<aqua>Multiverse Portals</aqua> — sign portals");
        msg(sender, "<white>/mvp</white> <gray>сводка порталов</gray>");
        msg(sender, "<white>/mvp version</white> <gray>текущая и свежая версия</gray>");
        msg(sender, "<white>[Multi]</white>/<white>[mvp]</white>/<white>[portal]</white> / <white>[To]</white> / <white>[Pair]</white>");
        msg(sender, "<gray>Local wool:</gray> name + channel on wall-sign (ColorPortals-style)");
        msg(sender, "<white>/mvp local list|import-colorportals</white>");
        msg(sender, "<white>/mvp ready</white> <gray>one-way consent</gray>");
        msg(sender, "<white>/mvp lang en|de|ru|zh</white> <gray>— server fallback language</gray>");
        msg(sender, "<white>/mvp scanner</white> <gray>public server pool</gray>");
        msg(sender, "<white>/mvp bindpreview</white> <gray>порядок кандидатов [Multi]</gray>");
        msg(sender, "<white>/mvp ingress</white> <gray>inbound limits</gray>");
        msg(sender, "<white>/mvp items</white> <gray>inventory export/import</gray>");
        msg(sender, "<white>/mvp settings</white> <gray>map / guests / inventory toggles</gray>");
        msg(sender, "<white>/mvp update</white> <gray>скачать обновление (admin)</gray>");
    }

    private java.util.Map<String, java.util.List<String>> collectCommentSignsForCmd() {
        java.util.Map<String, java.util.List<String>> out = new java.util.HashMap<>();
        for (Portal p : db.listPortals()) {
            var signs = io.multiverseportals.portal.PortalCommentSigns.collect(p);
            if (!signs.isEmpty()) {
                out.put(p.id(), signs);
            }
        }
        return out;
    }

    private void msg(CommandSender sender, String mini) {
        sender.sendMessage(mm.deserialize(config.prefix(sender) + mini));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], "help", "status", "stats", "info", "version", "reload", "update", "settings", "items", "ready", "scanner",
                    "ingress", "deny", "rep", "lang", "trust", "peers", "policy", "create", "pair", "multi",
                    "list", "delete", "score", "registry", "bindpreview", "bindorder", "local");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("local")) {
            return filter(args[1], "list", "import-colorportals");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ready")) {
            return filter(args[1], "on", "off");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scanner")) {
            return filter(args[1], "status", "refresh");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deny")) {
            return filter(args[1], "add", "remove", "list");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("lang") || args[0].equalsIgnoreCase("language"))) {
            return filter(args[1], "en", "de", "ru", "zh");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(args[1], "pair", "multi");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pair")) {
            return filter(args[1], "invite", "accept");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("registry")) {
            return filter(args[1], "list", "portals", "graph", "returns", "announce", "optin", "optout");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("registry") && args[1].equalsIgnoreCase("list")) {
            return filter(args[2], "all");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("items")) {
            return filter(args[1], "export", "import");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("items")) {
            return filter(args[2], "on", "off");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("settings")) {
            return filter(args[1], "map", "guests", "export", "import", "status");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("settings")) {
            String k = args[1].toLowerCase(Locale.ROOT);
            if (k.equals("map") || k.equals("catalog") || k.equals("list") || k.equals("public")) {
                return filter(args[2], "on", "off", "auto", "always");
            }
            return filter(args[2], "on", "off");
        }
        return List.of();
    }

    private static List<String> filter(String prefix, String... options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Human age for last registry heartbeat, e.g. "12s", "3m", "2h", "never". */
    static String formatPingAge(long ageMs) {
        if (ageMs < 0) {
            return "never";
        }
        long sec = ageMs / 1000L;
        if (sec < 60) {
            return sec + "s ago";
        }
        long min = sec / 60L;
        if (min < 60) {
            return min + "m ago";
        }
        long hr = min / 60L;
        if (hr < 48) {
            return hr + "h ago";
        }
        return (hr / 24L) + "d ago";
    }
}
