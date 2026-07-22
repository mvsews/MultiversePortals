package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.portal.PortalBindService;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.util.ShapeHasher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Players create portals by writing a sign — no admin commands required.
 *
 * [Multi] / [Random]           → random server from scanners / catalog
 * [To] + line2=serverId        → fixed target from registry
 * [To] + IP / host + port      → direct bind (no registry id)
 * [Pair]                       → create pair invite (code written to line 2)
 * [Pair] + line2=CODE          → accept pair invite from another server
 */
public final class SignListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final PortalService portals;
    private final RegistryDatabase registry;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SignListener(
            MultiversePortalsPlugin plugin,
            PluginConfig config,
            PortalService portals,
            RegistryDatabase registry
    ) {
        this.plugin = plugin;
        this.config = config;
        this.portals = portals;
        this.registry = registry;
    }

    /** Break MULTI sign → remove portal (place a new [Multi] for a fresh random). PAIR stays until /mvp delete. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        var found = plugin.database().findPortalByFrame(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (found.isEmpty()) {
            return;
        }
        Portal portal = found.get();
        if (portal.type() != PortalType.MULTI) {
            return;
        }
        portals.delete(portal.id());
        Player player = event.getPlayer();
        if (player != null) {
            msg(player, config.message(player, "portal-removed"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!config.signsAutoCreate()) {
            return;
        }
        Player player = event.getPlayer();
        if (!config.everyoneCanCreate() && !player.hasPermission("multiverseportals.create")) {
            return;
        }
        if (!player.hasPermission("multiverseportals.create")) {
            return;
        }

        String line0 = plain(event.line(0));
        String line1 = plain(event.line(1)).trim();
        String line2 = plain(event.line(2)).trim();
        String type = ShapeHasher.parseType(line0);
        if (type == null) {
            return;
        }

        Block block = event.getBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(player, block, type, line1, line2, event));
    }

    private void handle(Player player, Block block, String type, String line1, String line2, SignChangeEvent event) {
        try {
            switch (type) {
                case "multi" -> {
                    // Portal / Random on line 1; optional line 2 = where (IP, Pair, …)
                    String dest = line1 == null ? "" : line1.trim();
                    String destLower = dest.toLowerCase(java.util.Locale.ROOT)
                            .replace("[", "").replace("]", "");
                    if (dest.isBlank() || destLower.equals("random") || destLower.equals("multi")
                            || destLower.equals("mvp")) {
                        Portal portal = portals.createFromSign(player, block, PortalType.MULTI, "multi");
                        msg(player, config.message(player, "created-multi"));
                        msg(player, config.message(player, "bind-searching"));
                        if (plugin.portalBindService() != null) {
                            plugin.portalBindService().startBind(portal, player);
                        }
                    } else if (destLower.equals("pair") || destLower.equals("link")) {
                        handlePair(player, block, line2);
                    } else if (destLower.equals("to") || destLower.equals("goto") || destLower.equals("server")) {
                        if (line2.isBlank()) {
                            msg(player, config.message(player, "need-to-line"));
                            return;
                        }
                        // Portal / To / host — rare; treat line2 as host:port blob
                        ToTarget target = parseToTarget(line2, "");
                        if (target == null) {
                            msg(player, config.message(player, "need-to-line"));
                            return;
                        }
                        if (target.host() != null) {
                            createFixedHostTo(player, block, target.host(), target.port());
                        } else {
                            createRegistryTo(player, block, target.serverId());
                        }
                    } else {
                        // Portal + host[:port] on line 2 (port on line 3 optional)
                        ToTarget target = parseToTarget(dest, line2);
                        if (target == null) {
                            msg(player, config.message(player, "need-to-line"));
                            return;
                        }
                        if (target.host() != null) {
                            createFixedHostTo(player, block, target.host(), target.port());
                        } else {
                            createRegistryTo(player, block, target.serverId());
                        }
                    }
                }
                case "to" -> {
                    if (line1.isBlank()) {
                        msg(player, config.message(player, "need-to-line"));
                        return;
                    }
                    ToTarget target = parseToTarget(line1, line2);
                    if (target == null) {
                        msg(player, config.message(player, "need-to-line"));
                        return;
                    }
                    if (target.host() != null) {
                        createFixedHostTo(player, block, target.host(), target.port());
                    } else {
                        createRegistryTo(player, block, target.serverId());
                    }
                }
                case "pair" -> handlePair(player, block, line1);
                default -> {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Sign portal create failed: " + e.getMessage());
            msg(player, "<red>Не удалось создать портал.</red>");
        }
    }

    private void createFixedHostTo(Player player, Block block, String host, int port) {
        String display = host + ":" + port;
        Portal portal = portals.createFromSign(player, block, PortalType.MULTI, "to:" + display);
        portal.multiPool().clear();
        portal.multiPool().add("@" + display);
        plugin.database().savePortal(portal);
        msg(player, config.message(player, "created-to").replace("%target%", display));
        msg(player, config.message(player, "bind-searching"));
        if (plugin.portalBindService() != null) {
            plugin.portalBindService().bindFixedHost(portal, player, host, port);
        }
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }
    }

    private void createRegistryTo(Player player, Block block, String serverId) {
        if (registry.find(serverId).isEmpty()) {
            msg(player, config.message(player, "unknown-server"));
            return;
        }
        var target = registry.find(serverId).get();
        long age = System.currentTimeMillis() - target.lastHeartbeat();
        if (target.lastHeartbeat() > 0 && age > config.registryStaleMs()) {
            msg(player, config.message(player, "server-offline").replace("%target%", serverId));
            return;
        }
        if (plugin.portalService() != null && !plugin.portalService().isCompatible(player, target)) {
            msg(player, config.message(player, "incompatible-version")
                    .replace("%target%", serverId)
                    .replace("%version%", target.mcVersion() == null ? "?" : target.mcVersion()));
            return;
        }
        Portal portal = portals.createFromSign(player, block, PortalType.MULTI, "to:" + serverId);
        portal.multiPool().clear();
        portal.multiPool().add(serverId);
        plugin.database().savePortal(portal);
        msg(player, config.message(player, "created-to").replace("%target%", serverId));
        msg(player, config.message(player, "bind-searching"));
        if (plugin.portalBindService() != null) {
            plugin.portalBindService().startBind(portal, player);
        }
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }
    }

    /**
     * Prefer host+port when the sign looks like an address; otherwise registry server id.
     * Preferred layouts (line 1 of the sign is {@code Portal}):
     * <pre>
     * Portal               Portal              Portal
     * 1.2.3.4:25565        play.ex.com         1.2.3.4
     *                      (default :25565)    25566   (port on next line)
     * </pre>
     */
    static ToTarget parseToTarget(String line1, String line2) {
        if (line1 == null || line1.isBlank()) {
            return null;
        }
        String a = line1.trim();
        String b = line2 == null ? "" : line2.trim();

        // next line = port, this line = host / IP (legacy / long addresses)
        Integer portOnly = tryParsePort(b);
        if (portOnly != null && looksLikeHost(a) && !a.contains(":")) {
            return ToTarget.host(a, portOnly);
        }

        // host:port on one line
        var hp = PortalBindService.parseHostPort(a);
        if (hp.isPresent() && looksLikeHost(hp.get().host())) {
            return ToTarget.host(hp.get().host(), hp.get().port());
        }

        // bare IP or domain → default Java port
        if (looksLikeHost(a) && !a.contains(":")) {
            return ToTarget.host(a, 25565);
        }

        // registry id
        return ToTarget.registry(a);
    }

    private static boolean looksLikeHost(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        if (isIpv4(s)) {
            return true;
        }
        // hostname / domain — has a dot or is clearly not a short registry token alone
        if (s.indexOf('.') >= 0) {
            return true;
        }
        // bracketed IPv6
        return s.startsWith("[") && s.contains("]");
    }

    private static boolean isIpv4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) {
            return false;
        }
        for (String part : p) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static Integer tryParsePort(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            int p = Integer.parseInt(s.trim());
            if (p > 0 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private void handlePair(Player player, Block block, String codeLine) {
        String code = codeLine == null ? "" : codeLine.trim();
        if (code.isBlank()) {
            Portal portal = portals.createFromSign(player, block, PortalType.PAIR, "pair");
            String invite = portals.beginPairInvite(portal);
            registry.publishPairInvite(
                    invite,
                    portal.id(),
                    portal.frame().world(),
                    portal.frame().x(),
                    portal.frame().y(),
                    portal.frame().z(),
                    24L * 60 * 60 * 1000
            );
            io.multiverseportals.portal.PortalSigns.update(portal);
            if (plugin.portalMatter() != null) {
                plugin.portalMatter().refresh(portal);
            }
            if (plugin.portalService() != null) {
                plugin.portalService().publishPortalGraphAsync();
            }
            msg(player, config.message(player, "created-pair").replace("%code%", invite));
        } else {
            acceptPair(player, block, code.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private void acceptPair(Player player, Block block, String code) {
        Portal local = portals.createFromSign(player, block, PortalType.PAIR, "pair");
        var inviteOpt = registry.claimPairInvite(code, local.id());
        if (inviteOpt.isEmpty()) {
            msg(player, "<red>Код недействителен или уже использован.</red>");
            plugin.database().deletePortal(local.id());
            return;
        }
        var invite = inviteOpt.get();
        portals.acceptPairLocally(local, invite.hostServerId(), invite.hostPortalId());
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().refresh(local);
        }
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }
        msg(player, config.message(player, "paired").replace("%target%", invite.hostServerId()));
        player.sendMessage(mm.deserialize(config.prefix(player)
                + "<gray>На другом сервере портал с кодом должен стать активным после синка.</gray>"));
    }

    private void msg(Player player, String mini) {
        player.sendMessage(mm.deserialize(config.prefix(player) + mini));
    }

    private static String plain(Component c) {
        if (c == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Either fixed host:port or registry server id. */
    record ToTarget(String serverId, String host, int port) {
        static ToTarget registry(String id) {
            return new ToTarget(id, null, 0);
        }

        static ToTarget host(String host, int port) {
            return new ToTarget(null, host, port);
        }
    }
}
