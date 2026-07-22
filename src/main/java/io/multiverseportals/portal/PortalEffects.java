package io.multiverseportals.portal;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Portal charge: warmup on the plate (default ~5s), then hold while searching if needed.
 */
public final class PortalEffects {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, BukkitTask> charging = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> minChargeDone = new ConcurrentHashMap<>();
    private final Map<UUID, String> chargeDestLabels = new ConcurrentHashMap<>();

    public PortalEffects(MultiversePortalsPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isCharging(UUID uuid) {
        return charging.containsKey(uuid);
    }

    public void cancel(UUID uuid) {
        BukkitTask t = charging.remove(uuid);
        if (t != null) {
            t.cancel();
        }
        minChargeDone.remove(uuid);
        chargeDestLabels.remove(uuid);
    }

    public void cancelAll() {
        charging.values().forEach(BukkitTask::cancel);
        charging.clear();
        minChargeDone.clear();
        chargeDestLabels.clear();
    }

    /**
     * Warmup charge, then keep particles while player stays on the plate.
     * {@code onMinCharge} fires once after {@code effects.charge-ticks}. Call {@link #signalDepart} when a server is found.
     */
    public void chargeAndHold(Player player, Portal portal, Location plateLoc, Consumer<Player> onMinCharge) {
        if (!config.effectsEnabled()) {
            onMinCharge.accept(player);
            return;
        }
        cancel(player.getUniqueId());

        int minTicks = Math.max(10, config.effectsChargeTicks());
        Location center = plateLoc.clone().add(0.5, 0.2, 0.5);
        World world = center.getWorld();
        if (world == null) {
            onMinCharge.accept(player);
            return;
        }

        Particle accent = portal.type() == PortalType.PAIR ? Particle.END_ROD : Particle.PORTAL;
        world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.7f, 0.6f);

        UUID uuid = player.getUniqueId();
        final String[] destLabel = { destinationLabel(portal) };
        showChargeHud(player, destLabel[0], minTicks);

        minChargeDone.put(uuid, false);

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;
            boolean minFired;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    leavePlate(player, world);
                    return;
                }
                if (player.getLocation().distanceSquared(center) > 16.0) {
                    player.sendActionBar(mm.deserialize(config.message(player, "charge-left-plate")));
                    world.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.2f);
                    leavePlate(player, world);
                    return;
                }

                // Pick up a fresher label if TravelService resolved the dest mid-charge
                String live = chargeDestLabels.get(uuid);
                if (live != null && !live.isBlank() && !live.equals(destLabel[0])) {
                    destLabel[0] = live;
                    showChargeHud(player, destLabel[0], Math.max(40, minTicks - tick));
                }

                boolean holding = tick >= minTicks;
                double progress = holding ? 1.0 : (double) tick / minTicks;
                spawnLoop(world, center, portal, accent, tick, progress);

                if (tick % 8 == 0) {
                    world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f,
                            0.8f + (float) (holding ? 1.0 : progress));
                }

                if (!holding) {
                    int left = (minTicks - tick + 19) / 20;
                    player.sendActionBar(mm.deserialize(config.message(player, "charge-countdown")
                            .replace("%dest%", escapeMm(displayDest(destLabel[0])))
                            .replace("%sec%", String.valueOf(Math.max(1, left)))));
                } else {
                    player.sendActionBar(mm.deserialize(config.message(player, "searching-hold")
                            .replace("%dest%", escapeMm(displayDest(destLabel[0])))));
                    if (tick % 40 == 0) {
                        world.playSound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.25f, 1.2f);
                    }
                }

                tick++;
                if (!minFired && tick >= minTicks) {
                    minFired = true;
                    minChargeDone.put(uuid, true);
                    onMinCharge.accept(player);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        charging.put(uuid, task);
    }

    /** Update on-screen destination name while still charging (e.g. after probe). */
    public void setChargeDestination(Player player, String dest) {
        if (player == null || dest == null || dest.isBlank()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!charging.containsKey(uuid)) {
            return;
        }
        String label = truncate(dest.trim(), 40);
        chargeDestLabels.put(uuid, label);
        showChargeHud(player, label, 60);
    }

    private void showChargeHud(Player player, String dest, int holdTicks) {
        String label = displayDest(dest);
        // Empty title + dest as subtitle → smaller text (vanilla title is huge)
        player.showTitle(Title.title(
                mm.deserialize(""),
                mm.deserialize(config.effectsSubtitle().replace("%dest%", escapeMm(label))),
                Title.Times.times(
                        Duration.ofMillis(80),
                        Duration.ofMillis(Math.max(40, holdTicks) * 50L),
                        Duration.ofMillis(200))
        ));
    }

    private static String displayDest(String dest) {
        if (dest == null || dest.isBlank()) {
            return "…";
        }
        return truncate(dest, 40);
    }

    public static String destinationLabel(Portal portal) {
        if (portal == null) {
            return "";
        }
        if (portal.type() == PortalType.PAIR && portal.pairServerId() != null && !portal.pairServerId().isBlank()) {
            return portal.pairServerId();
        }
        if (portal.boundVersion() != null && !portal.boundVersion().isBlank()) {
            return portal.boundVersion().replace('\n', ' ').trim();
        }
        if (portal.hasBoundDestination()) {
            return portal.boundHost() + ":" + portal.boundPort();
        }
        if (portal.name() != null && !portal.name().isBlank()
                && !"multi".equalsIgnoreCase(portal.name()) && !"pair".equalsIgnoreCase(portal.name())) {
            return portal.name();
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String escapeMm(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw
                .replace("\\", "\\\\")
                .replace("<", "\\<")
                .replace(">", "\\>");
    }

    /** Final burst + stop hold animation (before transfer). */
    public void signalDepart(Player player) {
        UUID uuid = player.getUniqueId();
        cancel(uuid);
        Location loc = player.getLocation().add(0, 1, 0);
        World world = loc.getWorld();
        if (world != null) {
            try {
                world.spawnParticle(Particle.REVERSE_PORTAL, loc, 80, 0.4, 0.8, 0.4, 0.15);
                world.spawnParticle(Particle.END_ROD, loc, 25, 0.35, 0.6, 0.35, 0.05);
            } catch (Throwable ignored) {
            }
            world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            world.playSound(loc, Sound.BLOCK_PORTAL_TRAVEL, 0.5f, 1.4f);
        }
        player.sendActionBar(mm.deserialize(config.message(player, "charge-go")));
        player.clearTitle();
    }

    public void abort(UUID uuid, String miniMessage) {
        cancel(uuid);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(mm.deserialize(miniMessage));
            player.sendActionBar(mm.deserialize(config.message(player, "charge-aborted")));
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 0.8f);
            player.clearTitle();
        }
    }

    private void leavePlate(Player player, World world) {
        cancel(player.getUniqueId());
        if (plugin.travelService() != null) {
            plugin.travelService().clearSession(player.getUniqueId());
        }
    }

    private void spawnLoop(World world, Location center, Portal portal, Particle accent, int tick, double progress) {
        double radius = 0.9 + 0.3 * Math.sin(progress * Math.PI);
        for (int a = 0; a < 3; a++) {
            double angle = (tick * 0.35) + (a * (Math.PI * 2 / 3));
            double y = 0.2 + progress * 2.2;
            Location p = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            try {
                world.spawnParticle(Particle.PORTAL, p, 4, 0.05, 0.05, 0.05, 0.02);
                world.spawnParticle(accent, p, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < 12; i++) {
            double ang = (Math.PI * 2 * i / 12) + tick * 0.1;
            Location ring = center.clone().add(Math.cos(ang) * (0.6 + progress * 0.4), 0.05, Math.sin(ang) * (0.6 + progress * 0.4));
            try {
                world.spawnParticle(Particle.DUST, ring, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(dustColor(portal, progress), 1.1f));
            } catch (Throwable ignored) {
            }
        }
        if (tick % 2 == 0) {
            Vector pull = new Vector(0, 0.1, 0);
            Location mote = center.clone().add(
                    (Math.random() - 0.5) * 1.5,
                    0.5 + Math.random() * 1.5,
                    (Math.random() - 0.5) * 1.5
            );
            try {
                world.spawnParticle(Particle.WITCH, mote, 0, pull.getX(), pull.getY(), pull.getZ(), 0.08);
            } catch (Throwable ignored) {
            }
        }
    }

    public void arrival(Player player) {
        if (!config.effectsEnabled()) {
            return;
        }
        Location loc = player.getLocation().add(0, 1, 0);
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.PORTAL, loc, 60, 0.4, 0.6, 0.4, 0.2);
        world.spawnParticle(Particle.END_ROD, loc, 20, 0.3, 0.5, 0.3, 0.02);
        world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
        world.playSound(loc, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.4f, 1.5f);
    }

    public void idle(Portal portal, Location signLoc) {
        if (!config.effectsEnabled() || !config.effectsIdle()) {
            return;
        }
        World world = signLoc.getWorld();
        if (world == null) {
            return;
        }
        Location c = signLoc.clone().add(0.5, 0.5, 0.5);
        double t = (System.currentTimeMillis() % 10000) / 1000.0;
        for (int i = 0; i < 6; i++) {
            double ang = t + i;
            world.spawnParticle(Particle.PORTAL,
                    c.clone().add(Math.cos(ang) * 0.7, 0.3 + (i % 3) * 0.25, Math.sin(ang) * 0.7),
                    2, 0.02, 0.02, 0.02, 0.01);
        }
    }

    private static Color dustColor(Portal portal, double progress) {
        double p = Math.max(0.0, Math.min(1.0, progress));
        if (portal.type() == PortalType.PAIR) {
            return Color.fromRGB(80, clampByte((int) (80 + 120 * p)), 255);
        }
        return Color.fromRGB(clampByte((int) (120 + 100 * p)), 60, 220);
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
