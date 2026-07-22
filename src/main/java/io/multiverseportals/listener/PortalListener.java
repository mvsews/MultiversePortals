package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.local.LocalPortalListener;
import io.multiverseportals.model.Portal;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.travel.TravelService;
import io.multiverseportals.util.ShapeHasher;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final PortalService portalService;
    private final TravelService travelService;
    private final PortalEffects effects;
    private final LocalPortalListener localPortals;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    /** Skip portal plates right after join (spawn-on-plate loops). */
    private final Map<UUID, Long> joinGraceUntil = new ConcurrentHashMap<>();
    private static final long JOIN_GRACE_MS = 10_000L;

    public PortalListener(
            MultiversePortalsPlugin plugin,
            PortalService portalService,
            TravelService travelService,
            PortalEffects effects,
            LocalPortalListener localPortals
    ) {
        this.plugin = plugin;
        this.portalService = portalService;
        this.travelService = travelService;
        this.effects = effects;
        this.localPortals = localPortals;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        joinGraceUntil.put(event.getPlayer().getUniqueId(),
                System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        // PHYSICAL on Paper often has null hand; ignore OFF_HAND only when set.
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !ShapeHasher.isPressurePlate(block.getType())) {
            return;
        }
        if (localPortals != null && localPortals.isLocalPlate(block)) {
            return; // handled by LocalPortalListener
        }
        tryActivate(event.getPlayer(), block.getLocation(), "physical");
    }

    /**
     * Bedrock/Geyser often never fires PHYSICAL for pressure plates — detect by standing on one.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Block plate = findPlateUnder(to);
        if (plate == null) {
            return;
        }
        if (localPortals != null && localPortals.isLocalPlate(plate)) {
            return;
        }
        tryActivate(event.getPlayer(), plate.getLocation(), "move");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        effects.cancel(event.getPlayer().getUniqueId());
        travelService.clearSession(event.getPlayer().getUniqueId());
        cooldown.remove(event.getPlayer().getUniqueId());
        joinGraceUntil.remove(event.getPlayer().getUniqueId());
    }

    private void tryActivate(Player player, Location plateLoc, String source) {
        if (effects.isCharging(player.getUniqueId()) || travelService.isTravelPending(player.getUniqueId())) {
            return;
        }
        Long grace = joinGraceUntil.get(player.getUniqueId());
        if (grace != null && System.currentTimeMillis() < grace) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && now - last < 4000) {
            return;
        }

        Optional<Portal> found = portalService.findNear(plateLoc);
        if (found.isEmpty()) {
            return;
        }

        cooldown.put(player.getUniqueId(), now);
        Portal portal = found.get();

        plugin.getLogger().info("Portal plate trigger (" + source + ") by "
                + player.getName() + " → " + portal.id() + " @"
                + plateLoc.getBlockX() + "," + plateLoc.getBlockY() + "," + plateLoc.getBlockZ());

        plugin.getServer().getScheduler().runTask(plugin, () ->
                travelService.beginTravel(player, portal, plateLoc));
    }

    private static Block findPlateUnder(Location feet) {
        Block at = feet.getBlock();
        if (ShapeHasher.isPressurePlate(at.getType())) {
            return at;
        }
        Block below = at.getRelative(BlockFace.DOWN);
        if (ShapeHasher.isPressurePlate(below.getType())) {
            return below;
        }
        return null;
    }
}
