package io.multiverseportals.command;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.model.Portal;
import io.multiverseportals.portal.FrameDetector;
import net.kyori.adventure.text.Component;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Console/RCON map dump — WorldEdit-style peek without a player selection.
 * {@code /mvp inspect} · {@code /mvp inspect &lt;portalId&gt;} ·
 * {@code /mvp inspect &lt;world&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [r]} · {@code /mvp inspect here [r]}
 */
final class WorldInspect {

    private static final int DEFAULT_R = 8;
    private static final int MAX_R = 24;

    private WorldInspect() {}

    static void run(MultiversePortalsPlugin plugin, CommandSender sender, String[] args) {
        if (!sender.hasPermission("multiverseportals.admin")) {
            return;
        }
        if (args.length < 2) {
            listPortals(plugin, sender);
            out(plugin, sender, "usage: /mvp inspect <id|here|world x y z> [r]");
            return;
        }
        if (args[1].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player)) {
                out(plugin, sender, "here is in-game only. Use: /mvp inspect <world> <x> <y> <z> [r]");
                return;
            }
            int r = args.length >= 3 ? parseRadius(args[2]) : DEFAULT_R;
            dump(plugin, sender, player.getLocation().getBlock(), r, findPortalAt(plugin, player.getLocation()));
            return;
        }
        Optional<Portal> byId = findPortal(plugin, args[1]);
        if (byId.isPresent()) {
            Portal p = byId.get();
            World world = Bukkit.getWorld(p.frame().world());
            if (world == null) {
                out(plugin, sender, "world not loaded: " + p.frame().world());
                return;
            }
            int r = args.length >= 3 ? parseRadius(args[2]) : DEFAULT_R;
            Block sign = world.getBlockAt(p.frame().x(), p.frame().y(), p.frame().z());
            dump(plugin, sender, sign, r, p);
            return;
        }
        if (args.length < 5) {
            out(plugin, sender, "unknown portal id. usage: /mvp inspect <id|here|world x y z> [r]");
            listPortals(plugin, sender);
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            out(plugin, sender, "unknown world: " + args[1]);
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[2]);
            y = Integer.parseInt(args[3]);
            z = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            out(plugin, sender, "x y z must be integers");
            return;
        }
        int r = args.length >= 6 ? parseRadius(args[5]) : DEFAULT_R;
        Block at = world.getBlockAt(x, y, z);
        dump(plugin, sender, at, r, findPortalAt(plugin, at.getLocation()));
    }

    private static void listPortals(MultiversePortalsPlugin plugin, CommandSender sender) {
        List<Portal> all = plugin.database().listPortals();
        out(plugin, sender, "portals " + all.size());
        for (Portal p : all) {
            String dest = p.hasBoundDestination() ? " → " + p.boundHost() + ":" + p.boundPort() : "";
            out(plugin, sender, p.id().substring(0, 8) + " " + p.type() + " " + p.status()
                    + " " + p.frame().key() + dest);
        }
    }

    private static void dump(MultiversePortalsPlugin plugin, CommandSender sender, Block center, int r, Portal portal) {
        World world = center.getWorld();
        world.getChunkAt(center);
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        out(plugin, sender, "inspect " + world.getName() + " " + cx + "," + cy + "," + cz
                + " r=" + r + " chunk=" + (cx >> 4) + "," + (cz >> 4)
                + " loaded=" + world.isChunkLoaded(cx >> 4, cz >> 4)
                + " dim=" + world.getEnvironment());
        BlockData data = center.getBlockData();
        String facing = "";
        if (data instanceof Directional dir) {
            facing = " facing=" + dir.getFacing();
        }
        out(plugin, sender, "center " + center.getType() + facing + " " + data.getAsString(false));

        if (portal != null) {
            String sheet = plugin.portalMatter() != null && plugin.portalMatter().hasSheet(portal.id())
                    ? "yes" : "no";
            out(plugin, sender, "portal " + portal.id().substring(0, 8) + " " + portal.type()
                    + " " + portal.status() + " sheet=" + sheet
                    + (portal.hasBoundDestination() ? " bound=" + portal.boundHost() : ""));
        }

        int scanR = plugin.pluginConfig().maxFrameRadius();
        int maxInterior = plugin.pluginConfig().maxInterior();
        FrameDetector.Scan scan = FrameDetector.scan(center, scanR, maxInterior);
        if (scan.closed()) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (Location loc : scan.interior()) {
                minX = Math.min(minX, loc.getBlockX());
                maxX = Math.max(maxX, loc.getBlockX());
                minY = Math.min(minY, loc.getBlockY());
                maxY = Math.max(maxY, loc.getBlockY());
                minZ = Math.min(minZ, loc.getBlockZ());
                maxZ = Math.max(maxZ, loc.getBlockZ());
            }
            out(plugin, sender, "scan closed axis=" + scan.axis()
                    + " interior=" + scan.interior().size()
                    + " span=" + Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1))
                    + "/" + maxInterior
                    + " frame=" + scan.frameBlocks().size()
                    + " bbox=" + minX + ".." + maxX + " " + minY + ".." + maxY + " " + minZ + ".." + maxZ);
        } else {
            out(plugin, sender, "scan open (no closed ring within r=" + scanR
                    + " and max-interior=" + maxInterior + ")");
        }

        out(plugin, sender, "neighbors");
        out(plugin, sender, "  E " + describe(center.getRelative(1, 0, 0)));
        out(plugin, sender, "  W " + describe(center.getRelative(-1, 0, 0)));
        out(plugin, sender, "  S " + describe(center.getRelative(0, 0, 1)));
        out(plugin, sender, "  N " + describe(center.getRelative(0, 0, -1)));
        out(plugin, sender, "  U " + describe(center.getRelative(0, 1, 0)));
        out(plugin, sender, "  D " + describe(center.getRelative(0, -1, 0)));

        Axis axis = scan.closed() ? scan.axis() : Axis.X;
        int planeX = cx;
        int planeZ = cz;
        if (scan.closed() && !scan.interior().isEmpty()) {
            Location hole = scan.interior().get(0);
            if (axis == Axis.X) {
                planeZ = hole.getBlockZ();
            } else {
                planeX = hole.getBlockX();
            }
        } else if (data instanceof Directional dir) {
            var f = dir.getFacing();
            if (f.getModX() != 0) {
                axis = Axis.Z;
                planeX = cx + f.getOppositeFace().getModX();
            } else if (f.getModZ() != 0) {
                axis = Axis.X;
                planeZ = cz + f.getOppositeFace().getModZ();
            }
        }
        out(plugin, sender, "slice " + (axis == Axis.X ? "z=" + planeZ : "x=" + planeX)
                + " (sign " + cx + "," + cy + "," + cz + ")");
        dumpPlane(plugin, sender, world, cx, cy, cz, planeX, planeZ, r, axis);
        dumpDistr(plugin, sender, world, cx, cy, cz, r);
    }

    private static void dumpPlane(
            MultiversePortalsPlugin plugin,
            CommandSender sender,
            World world,
            int sx, int sy, int sz,
            int planeX, int planeZ,
            int r,
            Axis axis
    ) {
        out(plugin, sender, "plane axis=" + axis + "  .=air #=solid P=nether_portal O=obsidian N=netherrack S=sign C=wool +=passable");
        for (int y = sy + r; y >= sy - r; y--) {
            StringBuilder row = new StringBuilder();
            if (axis == Axis.X) {
                for (int x = sx - r; x <= sx + r; x++) {
                    boolean mark = x == sx && y == sy;
                    row.append(letter(world.getBlockAt(x, y, planeZ), mark));
                }
            } else {
                for (int z = sz - r; z <= sz + r; z++) {
                    boolean mark = z == sz && y == sy;
                    row.append(letter(world.getBlockAt(planeX, y, z), mark));
                }
            }
            out(plugin, sender, String.format(Locale.ROOT, "%4d %s", y, row));
        }
    }

    private static void dumpDistr(
            MultiversePortalsPlugin plugin,
            CommandSender sender,
            World world,
            int cx, int cy, int cz,
            int r
    ) {
        Map<Material, Integer> counts = new HashMap<>();
        int total = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    counts.merge(m, 1, Integer::sum);
                    total++;
                }
            }
        }
        List<Map.Entry<Material, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<Material, Integer>>comparingInt(Map.Entry::getValue).reversed());
        out(plugin, sender, "distr cube r=" + r + " n=" + total);
        int shown = 0;
        for (var e : ranked) {
            out(plugin, sender, "  " + e.getValue() + " " + e.getKey());
            shown++;
            if (shown >= 16) {
                break;
            }
        }
    }

    private static String describe(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ() + " " + block.getBlockData().getAsString(false);
    }

    static char letter(Block block, boolean markCenter) {
        if (markCenter) {
            return '*';
        }
        Material m = block.getType();
        if (m.isAir() || m == Material.CAVE_AIR || m == Material.VOID_AIR) {
            return '.';
        }
        if (m == Material.NETHER_PORTAL) {
            return 'P';
        }
        if (m == Material.OBSIDIAN || m == Material.CRYING_OBSIDIAN) {
            return 'O';
        }
        if (m == Material.NETHERRACK) {
            return 'N';
        }
        String n = m.name();
        if (n.contains("SIGN")) {
            return 'S';
        }
        if (n.endsWith("_WOOL")) {
            return 'C';
        }
        if (n.contains("NYLIUM")) {
            return 'Y';
        }
        if (FrameDetector.isPassable(m)) {
            return '+';
        }
        return '#';
    }

    private static Optional<Portal> findPortal(MultiversePortalsPlugin plugin, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        Portal prefix = null;
        int prefixHits = 0;
        for (Portal p : plugin.database().listPortals()) {
            if (p.id().equalsIgnoreCase(token)) {
                return Optional.of(p);
            }
            if (p.id().toLowerCase(Locale.ROOT).startsWith(t)
                    || (p.name() != null && p.name().equalsIgnoreCase(token))) {
                prefix = p;
                prefixHits++;
            }
        }
        if (prefixHits == 1) {
            return Optional.of(prefix);
        }
        return Optional.empty();
    }

    private static Portal findPortalAt(MultiversePortalsPlugin plugin, Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Portal best = null;
        int bestD = Integer.MAX_VALUE;
        for (Portal p : plugin.database().listPortals()) {
            if (!world.equals(p.frame().world())) {
                continue;
            }
            int d = Math.max(Math.abs(p.frame().x() - x),
                    Math.max(Math.abs(p.frame().y() - y), Math.abs(p.frame().z() - z)));
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return bestD <= 8 ? best : null;
    }

    private static int parseRadius(String raw) {
        try {
            return Math.max(1, Math.min(MAX_R, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return DEFAULT_R;
        }
    }

    private static void out(MultiversePortalsPlugin plugin, CommandSender sender, String line) {
        plugin.getLogger().info("[inspect] " + line);
        sender.sendMessage(Component.text(line));
    }
}
