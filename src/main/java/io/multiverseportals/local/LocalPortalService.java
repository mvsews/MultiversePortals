package io.multiverseportals.local;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local wool portals (ColorPortals-compatible behaviour, own implementation).
 */
public final class LocalPortalService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Long> noTeleportUntil = new ConcurrentHashMap<>();
    private final Map<String, LocalPortal> bySignKey = new ConcurrentHashMap<>();

    public LocalPortalService(MultiversePortalsPlugin plugin, Database db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    public void loadCache() {
        bySignKey.clear();
        for (LocalPortal p : db.listLocalPortals()) {
            bySignKey.put(p.key(), p);
        }
        plugin.getLogger().info("Local portals loaded: " + bySignKey.size());
    }

    public boolean enabled() {
        return config.localPortalsEnabled();
    }

    public Optional<LocalPortal> findBySign(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySignKey.get(key(loc)));
    }

    public Optional<LocalPortal> findByKeyBlock(Block keyBlock) {
        if (keyBlock == null) {
            return Optional.empty();
        }
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block rel = keyBlock.getRelative(face);
            if (rel.getBlockData() instanceof WallSign) {
                Optional<LocalPortal> p = findBySign(rel.getLocation());
                if (p.isPresent()) {
                    return p;
                }
            }
        }
        return Optional.empty();
    }

    public Optional<LocalPortal> findByOccupied(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return Optional.empty();
        }
        Block origin = loc.getBlock();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block b = origin.getRelative(x, y, z);
                    if (!(b.getBlockData() instanceof WallSign)) {
                        continue;
                    }
                    Optional<LocalPortal> opt = findBySign(b.getLocation());
                    if (opt.isEmpty()) {
                        continue;
                    }
                    for (Block occ : WoolFrame.occupiedBlocks(b)) {
                        if (occ.getX() == origin.getX() && occ.getY() == origin.getY() && occ.getZ() == origin.getZ()
                                && occ.getWorld().equals(origin.getWorld())) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public List<LocalPortal> family(DyeColor color, int channel) {
        return db.listLocalPortalsByColorChannel(color.name(), channel);
    }

    public int countByCreator(UUID creator) {
        return db.countLocalPortalsByCreator(creator);
    }

    public CreateResult tryCreateFromSign(Player player, Block signBlock, String name, int channel) {
        if (!enabled()) {
            return CreateResult.fail(config.message(player, "local-disabled"));
        }
        if (!(signBlock.getBlockData() instanceof WallSign)) {
            return CreateResult.fail(config.message(player, "local-need-wallsign"));
        }
        if (!WoolFrame.frameIsComplete(signBlock)) {
            return CreateResult.fail(config.message(player, "local-frame-incomplete"));
        }
        DyeColor color = WoolFrame.colorOfFrame(signBlock).orElse(null);
        if (color == null) {
            return CreateResult.fail(config.message(player, "local-frame-incomplete"));
        }
        if (name == null || name.isBlank()) {
            return CreateResult.fail(config.message(player, "local-need-name"));
        }
        if (channel < 0 || channel > 9999) {
            return CreateResult.fail(config.message(player, "local-bad-channel"));
        }
        if (config.localUsePermissions()) {
            String ck = WoolFrame.colorPermKey(color);
            if (!player.hasPermission("multiverseportals.local.create.*")
                    && !player.hasPermission("multiverseportals.local.create." + ck)
                    && !player.hasPermission("multiverseportals.create")) {
                return CreateResult.fail(config.message(player, "local-no-perm-create").replace("%color%", ck));
            }
        }
        int maxPlayer = maxPortalsPlayerCanBuild(player);
        if (maxPlayer >= 0 && countByCreator(player.getUniqueId()) >= maxPlayer) {
            return CreateResult.fail(config.message(player, "local-player-limit").replace("%n%", String.valueOf(maxPlayer)));
        }
        if (!checkDistance(player, signBlock.getLocation(), channel, color)) {
            return CreateResult.fail(config.message(player, "local-distance"));
        }
        int maxGroup = config.localMaxPortalsPerGroup();
        List<LocalPortal> fam = family(color, channel);
        if (maxGroup > 0 && fam.size() >= maxGroup) {
            return CreateResult.fail(config.message(player, "local-group-full")
                    .replace("%n%", String.valueOf(maxGroup))
                    .replace("%color%", color.name().toLowerCase())
                    .replace("%channel%", String.valueOf(channel)));
        }
        if (findBySign(signBlock.getLocation()).isPresent()) {
            return CreateResult.fail(config.message(player, "local-already"));
        }

        int node = fam.size() + 1;
        LocalPortal portal = new LocalPortal(
                UUID.randomUUID().toString(),
                name.trim(),
                color,
                channel,
                node,
                signBlock.getWorld().getName(),
                signBlock.getX(),
                signBlock.getY(),
                signBlock.getZ(),
                player.getUniqueId(),
                null
        );
        registerAndLink(portal);
        updateSign(portal);
        return CreateResult.ok(portal);
    }

    public void destroy(LocalPortal portal, boolean markSign) {
        List<LocalPortal> fam = new ArrayList<>(family(portal.color(), portal.channel()));
        int idx = -1;
        for (int i = 0; i < fam.size(); i++) {
            if (fam.get(i).id().equals(portal.id())) {
                idx = i;
                break;
            }
        }
        bySignKey.remove(portal.key());
        db.deleteLocalPortal(portal.id());

        if (markSign) {
            World w = Bukkit.getWorld(portal.world());
            if (w != null) {
                Block b = w.getBlockAt(portal.x(), portal.y(), portal.z());
                if (b.getState() instanceof Sign sign) {
                    sign.line(0, Component.text("PORTAL").color(NamedTextColor.RED));
                    sign.line(1, Component.text("DESTROYED").color(NamedTextColor.RED));
                    sign.line(2, Component.empty());
                    sign.line(3, Component.empty());
                    sign.update(true);
                }
            }
        }

        if (idx < 0) {
            return;
        }
        fam.remove(idx);
        if (fam.isEmpty()) {
            return;
        }
        relinkFamily(fam);
    }

    private void registerAndLink(LocalPortal portal) {
        List<LocalPortal> fam = new ArrayList<>(family(portal.color(), portal.channel()));
        fam.add(portal);
        relinkFamily(fam);
    }

    private void relinkFamily(List<LocalPortal> fam) {
        if (fam.isEmpty()) {
            return;
        }
        for (int i = 0; i < fam.size(); i++) {
            LocalPortal p = fam.get(i);
            p.setNode(i + 1);
            if (fam.size() == 1) {
                p.setLinkedPortalId(null);
            } else {
                LocalPortal next = fam.get((i + 1) % fam.size());
                p.setLinkedPortalId(next.id());
            }
            db.saveLocalPortal(p);
            bySignKey.put(p.key(), p);
            updateSign(p);
        }
    }

    public void updateSign(LocalPortal portal) {
        World w = Bukkit.getWorld(portal.world());
        if (w == null) {
            return;
        }
        Block b = w.getBlockAt(portal.x(), portal.y(), portal.z());
        if (!(b.getState() instanceof Sign sign)) {
            return;
        }
        sign.line(0, Component.text(portal.name()));
        sign.line(1, Component.text(portal.channel() + "." + portal.node()));
        Optional<LocalPortal> linked = resolveLinked(portal);
        if (linked.isPresent()) {
            sign.line(2, Component.text("Warps To:").color(NamedTextColor.GREEN));
            sign.line(3, Component.text(linked.get().name()));
        } else {
            sign.line(2, Component.empty());
            sign.line(3, Component.text("INACTIVE").color(NamedTextColor.GRAY));
        }
        sign.update(true);
    }

    public Optional<LocalPortal> resolveLinked(LocalPortal portal) {
        if (portal.linkedPortalId() == null || portal.linkedPortalId().isBlank()) {
            return Optional.empty();
        }
        return db.findLocalPortal(portal.linkedPortalId());
    }

    public void teleportFrom(LocalPortal from) {
        Optional<LocalPortal> toOpt = resolveLinked(from);
        if (toOpt.isEmpty()) {
            return;
        }
        LocalPortal to = toOpt.get();
        World fromWorld = Bukkit.getWorld(from.world());
        World toWorld = Bukkit.getWorld(to.world());
        if (fromWorld == null || toWorld == null) {
            return;
        }
        Block fromSign = fromWorld.getBlockAt(from.x(), from.y(), from.z());
        Block toSign = toWorld.getBlockAt(to.x(), to.y(), to.z());
        Location warpFrom = WoolFrame.warpLocation(fromSign);
        Location warpTo = WoolFrame.warpLocation(toSign);

        for (Entity e : fromWorld.getNearbyEntities(warpFrom, 0.85, 0.85, 0.85)) {
            teleportEntity(e, from, to, toSign, false);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                fromWorld.playSound(warpFrom, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.5F), 2L);
        toWorld.playSound(warpTo, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.5F);
    }

    public void teleportMinecart(Minecart cart, LocalPortal from) {
        Optional<LocalPortal> toOpt = resolveLinked(from);
        if (toOpt.isEmpty() || !config.localAllowMinecarts()) {
            return;
        }
        LocalPortal to = toOpt.get();
        World toWorld = Bukkit.getWorld(to.world());
        if (toWorld == null) {
            return;
        }
        Block toSign = toWorld.getBlockAt(to.x(), to.y(), to.z());
        double velocityLength = cart.getVelocity().length();
        List<Entity> passengers = new ArrayList<>(cart.getPassengers());
        for (Entity p : passengers) {
            p.leaveVehicle();
        }
        teleportEntity(cart, from, to, toSign, true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (cart.isValid()) {
                Vector direction = WoolFrame.warpLocation(toSign).getDirection().clone().multiply(velocityLength);
                cart.setVelocity(direction);
            }
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Entity p : passengers) {
                if (p.isValid()) {
                    p.teleport(WoolFrame.warpLocation(toSign));
                    cart.addPassenger(p);
                }
            }
        }, 4L);
    }

    private boolean teleportEntity(Entity entity, LocalPortal from, LocalPortal to, Block toSign, boolean cart) {
        if (!canTeleport(entity, from)) {
            return false;
        }
        markNoTeleport(entity);
        Location dest = cart ? WoolFrame.cartWarpLocation(toSign) : WoolFrame.warpLocation(toSign);
        entity.teleport(dest);
        return true;
    }

    public boolean canTeleport(Entity entity, LocalPortal from) {
        Long until = noTeleportUntil.get(entity.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            return false;
        }
        if (!config.localAllowMobs() && entity instanceof Mob) {
            return false;
        }
        if (!config.localAllowItems() && entity instanceof Item) {
            return false;
        }
        if (entity instanceof Player player) {
            if (config.localUsePermissions()) {
                String ck = WoolFrame.colorPermKey(from.color());
                if (!player.hasPermission("multiverseportals.local.use.*")
                        && !player.hasPermission("multiverseportals.local.use." + ck)
                        && !player.hasPermission("multiverseportals.travel")) {
                    player.sendMessage(mm.deserialize(config.prefix(player)
                            + config.message(player, "local-no-perm-use").replace("%color%", ck)));
                    return false;
                }
            }
        }
        return true;
    }

    public void markNoTeleport(Entity entity) {
        noTeleportUntil.put(entity.getUniqueId(), System.currentTimeMillis() + 250);
        Bukkit.getScheduler().runTaskLater(plugin, () -> noTeleportUntil.remove(entity.getUniqueId()), 5L);
    }

    public void printInfo(Player player, LocalPortal portal) {
        player.sendMessage(ChatColor.GOLD + "Portal: " + portal.name());
        player.sendMessage(ChatColor.GRAY + " - Color: " + portal.color() + ", Channel: " + portal.channel());
        int size = family(portal.color(), portal.channel()).size();
        player.sendMessage(ChatColor.GRAY + " - Node: " + portal.node() + " out of " + size);
        String creatorName = Bukkit.getOfflinePlayer(portal.creator()).getName();
        player.sendMessage(ChatColor.GRAY + " - Creator: " + (creatorName == null ? portal.creator() : creatorName));
        player.sendMessage(ChatColor.GREEN + " - Warps To:");
        Optional<LocalPortal> linked = resolveLinked(portal);
        if (linked.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " - No warp location set");
            return;
        }
        LocalPortal dest = linked.get();
        player.sendMessage(ChatColor.GRAY + " - Name: " + dest.name());
        World aw = Bukkit.getWorld(portal.world());
        World bw = Bukkit.getWorld(dest.world());
        if (aw != null && bw != null && aw.equals(bw)) {
            Location a = new Location(aw, portal.x(), portal.y(), portal.z());
            Location b = new Location(bw, dest.x(), dest.y(), dest.z());
            int dx = b.getBlockX() - a.getBlockX();
            int dz = b.getBlockZ() - a.getBlockZ();
            String ns = dz >= 0 ? "SOUTH: " + dz : "NORTH: " + (-dz);
            String ew = dx >= 0 ? "EAST: " + dx : "WEST: " + (-dx);
            player.sendMessage(ChatColor.GRAY + " - " + ns + " blocks, " + ew + " blocks");
            player.sendMessage(ChatColor.GRAY + " - Biome: " + bw.getBiome(dest.x(), dest.y(), dest.z()));
        } else {
            player.sendMessage(ChatColor.GRAY + " - Location: " + dest.world() + " (a different world)");
        }
    }

    public boolean canDestroy(Player player, LocalPortal portal) {
        if (config.localUsePermissions()) {
            String ck = WoolFrame.colorPermKey(portal.color());
            if (!player.hasPermission("multiverseportals.local.destroy.*")
                    && !player.hasPermission("multiverseportals.local.destroy." + ck)
                    && !player.isOp()) {
                return false;
            }
        }
        if (!config.localPortalProtection()) {
            return true;
        }
        return portal.creator().equals(player.getUniqueId()) || player.isOp()
                || player.hasPermission("multiverseportals.admin");
    }

    private boolean checkDistance(Player player, Location current, int channel, DyeColor color) {
        int min = config.localMinDistance();
        int max = config.localMaxDistance();
        if (min == 0 && max == 0) {
            return true;
        }
        if (config.localUsePermissions() && player.hasPermission("multiverseportals.local.nodistance")) {
            return true;
        }
        List<LocalPortal> fam = family(color, channel);
        if (fam.isEmpty()) {
            return true;
        }
        LocalPortal last = fam.get(fam.size() - 1);
        World w = Bukkit.getWorld(last.world());
        if (w == null) {
            return max == 0;
        }
        Location connect = new Location(w, last.x(), last.y(), last.z());
        if (!connect.getWorld().equals(current.getWorld())) {
            if (max != 0) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-distance-world")
                        .replace("%max%", String.valueOf(max))));
                return false;
            }
            return true;
        }
        int distance = (int) current.distance(connect);
        if (min != 0 && distance < min) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-distance-close")
                    .replace("%n%", String.valueOf(min - distance))));
            return false;
        }
        if (max != 0 && distance > max) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-distance-far")
                    .replace("%n%", String.valueOf(distance - max))));
            return false;
        }
        return true;
    }

    private int maxPortalsPlayerCanBuild(Player player) {
        if (!config.localUsePermissions()) {
            return -1;
        }
        if (player.hasPermission("multiverseportals.local.max.*")) {
            return -1;
        }
        int best = -1;
        for (var perm : player.getEffectivePermissions()) {
            String p = perm.getPermission();
            if (p.startsWith("multiverseportals.local.max.")) {
                try {
                    int n = Integer.parseInt(p.substring("multiverseportals.local.max.".length()));
                    if (n > best) {
                        best = n;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    /** Import ColorPortals Data/portals.yml into SQLite. */
    public int importColorPortalsYaml(java.io.File file) {
        if (!file.isFile()) {
            return -1;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var root = yaml.getConfigurationSection("portals");
        if (root == null) {
            return 0;
        }
        int imported = 0;
        // Collect then link per family
        Map<String, List<LocalPortal>> families = new java.util.LinkedHashMap<>();
        for (String colorName : root.getKeys(false)) {
            DyeColor color;
            try {
                color = DyeColor.valueOf(colorName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            var channels = root.getConfigurationSection(colorName);
            if (channels == null) {
                continue;
            }
            for (String chNode : channels.getKeys(false)) {
                String[] split = chNode.split("-");
                if (split.length < 2) {
                    continue;
                }
                int channel;
                int node;
                try {
                    channel = Integer.parseInt(split[0]);
                    node = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String path = colorName + "." + chNode;
                String name = channels.getString(chNode + ".name", "Portal");
                String loc = channels.getString(chNode + ".location", "");
                String creatorStr = channels.getString(chNode + ".creator", "");
                String[] parts = loc.split(",");
                if (parts.length < 4) {
                    continue;
                }
                UUID creator;
                try {
                    creator = UUID.fromString(creatorStr);
                } catch (Exception e) {
                    creator = new UUID(0, 0);
                }
                LocalPortal p = new LocalPortal(
                        UUID.randomUUID().toString(),
                        name,
                        color,
                        channel,
                        node,
                        parts[0],
                        (int) Double.parseDouble(parts[1]),
                        (int) Double.parseDouble(parts[2]),
                        (int) Double.parseDouble(parts[3]),
                        creator,
                        null
                );
                families.computeIfAbsent(color.name() + ":" + channel, k -> new ArrayList<>()).add(p);
                imported++;
            }
        }
        for (List<LocalPortal> fam : families.values()) {
            fam.sort(java.util.Comparator.comparingInt(LocalPortal::node));
            // replace any existing same color/channel
            if (!fam.isEmpty()) {
                for (LocalPortal old : new ArrayList<>(family(fam.get(0).color(), fam.get(0).channel()))) {
                    bySignKey.remove(old.key());
                    db.deleteLocalPortal(old.id());
                }
            }
            relinkFamily(fam);
        }
        return imported;
    }

    public List<LocalPortal> listAll() {
        return db.listLocalPortals();
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public record CreateResult(boolean ok, String message, LocalPortal portal) {
        static CreateResult ok(LocalPortal p) {
            return new CreateResult(true, null, p);
        }

        static CreateResult fail(String msg) {
            return new CreateResult(false, msg, null);
        }
    }
}
