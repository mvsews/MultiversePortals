package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.local.LocalPortalListener;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.portal.PortalBindService;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.travel.TravelService;
import io.multiverseportals.util.ShapeHasher;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import io.papermc.paper.event.entity.EntityMoveEvent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /**
     * Already standing in a portal volume. Travel starts only on a fresh entry
     * (step onto the plate / walk into the sheet), not while remaining inside.
     */
    private final Set<UUID> occupying = ConcurrentHashMap.newKeySet();
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
        Player player = event.getPlayer();
        joinGraceUntil.put(player.getUniqueId(),
                System.currentTimeMillis() + JOIN_GRACE_MS);
        syncOccupancy(player);
    }

    /** After landing at a portal: if already in the volume, require stepping out first. */
    public void syncOccupancy(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (inTriggerZone(player.getLocation())) {
            occupying.add(player.getUniqueId());
        } else {
            occupying.remove(player.getUniqueId());
        }
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
     * Howl's dial: button by a random [Multi] sign switches the sticky destination.
     * Local wool portals keep their own button → teleport behaviour.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDialButton(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !Tag.BUTTONS.isTagged(block.getType())) {
            return;
        }
        // Network MULTI only — local wool portals are not in portalService.findNear().
        Optional<Portal> found = findPortalForDialButton(block);
        if (found.isEmpty()) {
            return;
        }
        Portal portal = found.get();
        if (portal.type() != PortalType.MULTI) {
            return;
        }
        if (!plugin.pluginConfig().portalTypeEnabled("multi")) {
            return;
        }
        if (io.multiverseportals.portal.PortalBindService.fixedEndpoint(portal).isPresent()) {
            return;
        }
        PortalBindService binds = plugin.portalBindService();
        if (binds == null) {
            return;
        }
        plugin.getLogger().info("Portal dial button by " + event.getPlayer().getName()
                + " → " + portal.id());
        binds.cycleBind(event.getPlayer(), portal);
    }

    private Optional<Portal> findPortalForDialButton(Block button) {
        // Prefer button under the network sign (sign = frame anchor)
        Block above = button.getRelative(BlockFace.UP);
        Optional<Portal> bySign = portalService.findNear(above.getLocation());
        if (bySign.isPresent()) {
            return bySign;
        }
        return portalService.findNear(button.getLocation());
    }

    /**
     * Bedrock/Geyser often never fires PHYSICAL for pressure plates — detect by standing on one.
     * Plate is optional: walking into the closed opening also starts travel.
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
        Player player = event.getPlayer();
        if (!inTriggerZone(to)) {
            occupying.remove(player.getUniqueId());
            return;
        }
        Block plate = findPlateUnder(to);
        if (plate != null) {
            if (localPortals != null && localPortals.isLocalPlate(plate)) {
                occupying.remove(player.getUniqueId());
                return;
            }
            tryActivate(player, plate.getLocation(), "move");
            return;
        }
        tryActivate(player, to, "walk");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlate(EntityInteractEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player || !(entity instanceof LivingEntity living)) {
            return;
        }
        Block plate = event.getBlock();
        if (plate == null || !ShapeHasher.isPressurePlate(plate.getType())) {
            return;
        }
        if (localPortals != null && localPortals.isLocalPlate(plate)) {
            return;
        }
        tryActivateEntity(living, plate.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player || !(entity instanceof LivingEntity living)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        tryActivateEntity(living, to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        effects.cancel(uuid);
        travelService.clearSession(uuid);
        cooldown.remove(uuid);
        joinGraceUntil.remove(uuid);
        occupying.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        UUID uuid = event.getPlayer().getUniqueId();
        if (to == null || !inTriggerZone(to)) {
            occupying.remove(uuid);
            return;
        }
        occupying.add(uuid);
    }

    /** From nether-portal sheet (EntityInsideBlockEvent). */
    public void tryActivateFromInside(Player player, Location loc) {
        tryActivate(player, loc, "inside");
    }

    public void tryActivateEntityFromInside(LivingEntity entity, Location loc) {
        tryActivateEntity(entity, loc);
        if (localPortals != null) {
            localPortals.tryWalkActivate(entity, loc);
        }
    }

    private void tryActivate(Player player, Location plateLoc, String source) {
        UUID uuid = player.getUniqueId();
        if (effects.isCharging(uuid) || travelService.isTravelPending(uuid)) {
            return;
        }
        if (!nearNetworkPortal(plateLoc) && !inTriggerZone(player.getLocation())) {
            occupying.remove(uuid);
            return;
        }

        Optional<Portal> found = portalService.findNear(plateLoc);
        if (found.isEmpty()) {
            occupying.remove(uuid);
            if (localPortals != null) {
                localPortals.tryWalkActivate(player, plateLoc);
            }
            return;
        }
        Portal portal = found.get();
        if (!"physical".equals(source)) {
            Location feet = player.getLocation();
            if (portalService.isStandingOnFrame(portal, feet)) {
                occupying.remove(uuid);
                return;
            }
            if (!portalService.isInsideOpening(portal, feet) && findPlateUnder(feet) == null) {
                occupying.remove(uuid);
                return;
            }
        }

        // Standing in the opening / on the plate is not a new trip.
        Long grace = joinGraceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) {
            occupying.add(uuid);
            return;
        }
        if (!occupying.add(uuid)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = cooldown.get(uuid);
        if (last != null && now - last < 4000) {
            return;
        }

        cooldown.put(uuid, now);

        plugin.getLogger().info("Portal trigger (" + source + ") by "
                + player.getName() + " → " + portal.id() + " @"
                + plateLoc.getBlockX() + "," + plateLoc.getBlockY() + "," + plateLoc.getBlockZ());

        plugin.getServer().getScheduler().runTask(plugin, () ->
                travelService.beginTravel(player, portal, plateLoc));
    }

    private void tryActivateEntity(LivingEntity entity, Location loc) {
        if (!plugin.pluginConfig().awayMobs()) {
            return;
        }
        if (!nearNetworkPortal(loc)) {
            return;
        }
        Optional<Portal> found = portalService.findNear(loc);
        if (found.isEmpty()) {
            return;
        }
        Portal portal = found.get();
        if (portal.type() != PortalType.AWAY) {
            return;
        }
        if (!portalService.isInsideOpening(portal, loc) && findPlateUnder(loc) == null) {
            return;
        }
        if (portalService.isStandingOnFrame(portal, loc)) {
            return;
        }
        if (plugin.biomePortalService() != null) {
            plugin.biomePortalService().travelEntity(entity, portal);
        }
    }

    private boolean nearNetworkPortal(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (plugin.portalMatter() != null) {
            Block b = loc.getBlock();
            if (plugin.portalMatter().isMatterBlock(b)
                    || plugin.portalMatter().isMatterBlock(b.getRelative(BlockFace.DOWN))) {
                return true;
            }
        }
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (Portal portal : plugin.database().listPortals()) {
            if (!world.equals(portal.frame().world())) {
                continue;
            }
            int dx = Math.abs(portal.frame().x() - x);
            int dy = Math.abs(portal.frame().y() - y);
            int dz = Math.abs(portal.frame().z() - z);
            if (Math.max(dx, Math.max(dy, dz)) <= 6) {
                return true;
            }
        }
        return false;
    }

    /** Plate, fake Nether sheet, or closed opening — not merely "near the sign". */
    private boolean inTriggerZone(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Block plate = findPlateUnder(loc);
        if (plate != null && (localPortals == null || !localPortals.isLocalPlate(plate))) {
            if (portalService.findNear(plate.getLocation()).isPresent()) {
                return true;
            }
        }
        if (plugin.portalMatter() != null) {
            Block b = loc.getBlock();
            if (plugin.portalMatter().isMatterBlock(b)
                    || plugin.portalMatter().isMatterBlock(b.getRelative(BlockFace.DOWN))) {
                return true;
            }
        }
        Optional<Portal> found = portalService.findNear(loc);
        return found.isPresent() && portalService.isInsideOpening(found.get(), loc);
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
