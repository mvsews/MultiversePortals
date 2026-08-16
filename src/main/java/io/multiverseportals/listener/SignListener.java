package io.multiverseportals.listener;

import io.multiverseportals.away.BiomeFrames;
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
import org.bukkit.Material;
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
        if (portal.type() != PortalType.MULTI && portal.type() != PortalType.AWAY) {
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
        String line0 = plain(event.line(0));
        String type = ShapeHasher.parseType(line0);
        if (type == null) {
            return;
        }
        if (!config.canCreatePortals(player)) {
            msg(player, config.message(player, "no-permission-create"));
            return;
        }

        String line1 = plain(event.line(1)).trim();
        String line2 = plain(event.line(2)).trim();

        Block block = event.getBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(player, block, type, line1, line2, event));
    }

    private void handle(Player player, Block block, String type, String line1, String line2, SignChangeEvent event) {
        try {
            switch (type) {
                case "multi" -> {
                    // Portal / Random on line 1; optional line 2 = where (IP, Pair, …)
                    String dest = line1 == null ? "" : line1.trim();
                    String destKind = ShapeHasher.parseDestKind(dest);
                    if ((destKind != null && destKind.isEmpty()) || "away".equals(destKind)) {
                        if (config.awayEnabled()) {
                            if (tryCreateAway(player, block)) {
                                return;
                            }
                            if ("away".equals(destKind)) {
                                tellAwayFrame(player, block);
                                return;
                            }
                        } else if ("away".equals(destKind)) {
                            typeOff(player, "away");
                            return;
                        }
                        if (typeOff(player, "multi")) {
                            return;
                        }
                        Portal portal = portals.createFromSign(player, block, PortalType.MULTI, "multi");
                        msg(player, config.message(player, "created-multi"));
                        msg(player, config.message(player, "bind-searching"));
                        if (plugin.portalBindService() != null) {
                            plugin.portalBindService().startBind(portal, player);
                        }
                    } else if ("multi".equals(destKind)) {
                        if (typeOff(player, "multi")) {
                            return;
                        }
                        Portal portal = portals.createFromSign(player, block, PortalType.MULTI, "multi");
                        msg(player, config.message(player, "created-multi"));
                        msg(player, config.message(player, "bind-searching"));
                        if (plugin.portalBindService() != null) {
                            plugin.portalBindService().startBind(portal, player);
                        }
                    } else if ("pair".equals(destKind)) {
                        handlePair(player, block, line2);
                    } else if ("to".equals(destKind)) {
                        if (typeOff(player, "to")) {
                            return;
                        }
                        if (line2.isBlank()) {
                            msg(player, config.message(player, "need-to-line"));
                            return;
                        }
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
                        if (typeOff(player, "to")) {
                            return;
                        }
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
                    if (typeOff(player, "to")) {
                        return;
                    }
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
                case "pair" -> {
                    if (typeOff(player, "pair")) {
                        return;
                    }
                    handlePair(player, block, line1);
                }
                case "away" -> {
                    if (typeOff(player, "away")) {
                        return;
                    }
                    if (!tryCreateAway(player, block)) {
                        tellAwayFrame(player, block);
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Sign portal create failed: " + e.getMessage());
            msg(player, "<red>Не удалось создать портал.</red>");
        }
    }

    private boolean tryCreateAway(Player player, Block block) {
        if (!config.awayEnabled()) {
            return false;
        }
        org.bukkit.block.Biome biome = block.getBiome();
        if (io.multiverseportals.away.BiomeFrames.isNetherOrEnd(
                io.multiverseportals.away.BiomeFrames.keyOf(biome))) {
            return false;
        }
        if (config.awayRequireBiomeFrame()
                && !io.multiverseportals.away.BiomeFrames.frameLooksLikeBiome(block, biome)) {
            return false;
        }
        Portal portal = portals.createFromSign(player, block, PortalType.AWAY, "away");
        portal.setAwayOriginBiome(io.multiverseportals.away.BiomeFrames.keyOf(biome));
        plugin.database().savePortal(portal);
        msg(player, config.message(player, "created-away"));
        msg(player, config.message(player, "away-searching"));
        if (plugin.biomePortalService() != null) {
            plugin.biomePortalService().startBind(portal, player);
        }
        return true;
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
        if (typeOff(player, "pair")) {
            return;
        }
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

    private boolean typeOff(Player player, String type) {
        if (config.portalTypeEnabled(type)) {
            return false;
        }
        msg(player, config.message(player, "type-disabled").replace("%type%", type));
        return true;
    }

    private void tellAwayFrame(Player player, Block block) {
        org.bukkit.block.Biome biome = block.getBiome();
        if (BiomeFrames.isNetherOrEnd(BiomeFrames.keyOf(biome))) {
            sendHint(player, config.message(player, "away-overworld-only"),
                    "<red>Away portals only work in the Overworld.</red>");
            return;
        }
        Material want = BiomeFrames.materialFor(biome);
        Material have = BiomeFrames.majorityFrameMaterial(block);
        String wantKey = BiomeFrames.blockLangKey(want);
        String haveKey = BiomeFrames.blockLangKey(have);
        String biomeKey = BiomeFrames.biomeLangKey(biome);
        String wantId = idOf(want);
        String haveId = idOf(have);
        String mini;
        String fallback;
        if (have != null && !BiomeFrames.matches(have, biome)) {
            mini = fillAway(config.message(player, "away-need-block"), wantKey, haveKey, biomeKey, wantId, haveId);
            fallback = "<red>Away:</red> <gold><lang:" + wantKey + "></gold> <gray>(" + wantId + ")</gray>"
                    + " <gray>≠</gray> <yellow><lang:" + haveKey + "></yellow> <gray>(" + haveId + ")</gray>";
        } else {
            mini = fillAway(config.message(player, "away-need-block-simple"), wantKey, haveKey, biomeKey, wantId, haveId);
            fallback = "<red>Away:</red> <gold><lang:" + wantKey + "></gold> <gray>(" + wantId + ")</gray>";
        }
        if (mini == null || mini.isBlank() || mini.equals("away-need-block") || mini.equals("away-need-block-simple")) {
            mini = fallback;
        }
        sendHint(player, mini, fallback);
    }

    private static String fillAway(String template, String wantKey, String haveKey, String biomeKey,
                                   String wantId, String haveId) {
        if (template == null) {
            return "";
        }
        return template
                .replace("%want_key%", wantKey)
                .replace("%have_key%", haveKey)
                .replace("%biome_key%", biomeKey)
                .replace("%want_id%", wantId)
                .replace("%have_id%", haveId);
    }

    private static String idOf(Material material) {
        if (material == null) {
            return "?";
        }
        try {
            return material.getKey().getKey().replace('_', ' ');
        } catch (Throwable ignored) {
            return material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        }
    }

    private void sendHint(Player player, String mini, String fallback) {
        try {
            msg(player, mini);
            player.sendActionBar(mm.deserialize(mini));
        } catch (RuntimeException e) {
            msg(player, fallback);
            player.sendActionBar(mm.deserialize(fallback));
        }
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
