package io.multiverseportals.local;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.util.ShapeHasher;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Iterator;
import java.util.Optional;

public final class LocalPortalListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final LocalPortalService local;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public LocalPortalListener(MultiversePortalsPlugin plugin, LocalPortalService local, PluginConfig config) {
        this.plugin = plugin;
        this.local = local;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignCreate(SignChangeEvent event) {
        if (!local.enabled() || !config.signsAutoCreate()) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof WallSign)) {
            return;
        }
        if (!WoolFrame.looksLikeColorPortalSign(block)) {
            return;
        }
        // Cross-server signs take precedence
        String line0 = plain(event.line(0));
        if (ShapeHasher.parseType(line0) != null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("multiverseportals.create")
                && !player.hasPermission("multiverseportals.local.create.*")) {
            return;
        }
        String name = line0.trim();
        String chan = plain(event.line(1)).trim();
        int channel;
        try {
            channel = Integer.parseInt(chan);
        } catch (NumberFormatException e) {
            if (WoolFrame.frameIsComplete(block)) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-bad-channel")));
            }
            return;
        }

        // Defer so wall-sign block data is committed
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            var result = local.tryCreateFromSign(player, block, name, channel);
            if (!result.ok()) {
                player.sendMessage(mm.deserialize(config.prefix(player) + result.message()));
                return;
            }
            LocalPortal p = result.portal();
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-created")
                    .replace("%color%", p.color().name().toLowerCase())
                    .replace("%channel%", String.valueOf(p.channel()))));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!local.enabled()) {
            return;
        }
        Block block = event.getBlock();
        Optional<LocalPortal> opt;
        if (block.getBlockData() instanceof WallSign) {
            opt = local.findBySign(block.getLocation());
        } else if (WoolFrame.isWool(block.getType())) {
            opt = local.findByOccupied(block.getLocation());
        } else {
            return;
        }
        if (opt.isEmpty()) {
            return;
        }
        LocalPortal portal = opt.get();
        Player player = event.getPlayer();
        if (!local.canDestroy(player, portal)) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-no-perm-destroy")));
            event.setCancelled(true);
            return;
        }
        local.destroy(portal, true);
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "local-destroyed")));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignDetach(BlockPhysicsEvent event) {
        if (!local.enabled() || !config.localPortalProtection()) {
            return;
        }
        Block b = event.getBlock();
        if (!(b.getBlockData() instanceof WallSign sign)) {
            return;
        }
        Optional<LocalPortal> portal = local.findBySign(b.getLocation());
        if (portal.isEmpty()) {
            return;
        }
        BlockFace face = sign.getFacing().getOppositeFace();
        if (!b.getRelative(face).getType().isSolid()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlate(PlayerInteractEvent event) {
        if (!local.enabled() || !config.localWalkOnActivation()) {
            return;
        }
        if (event.getAction() != Action.PHYSICAL) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block plate = event.getClickedBlock();
        if (plate == null || !ShapeHasher.isPressurePlate(plate.getType())) {
            return;
        }
        Block key = plate.getRelative(BlockFace.UP).getRelative(BlockFace.UP);
        Optional<LocalPortal> portal = local.findByKeyBlock(key);
        if (portal.isEmpty() || portal.get().linkedPortalId() == null) {
            return;
        }
        local.teleportFrom(portal.get());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlate(EntityInteractEvent event) {
        if (!local.enabled() || !config.localWalkOnActivation()) {
            return;
        }
        Block plate = event.getBlock();
        if (!ShapeHasher.isPressurePlate(plate.getType())) {
            return;
        }
        Block key = plate.getRelative(BlockFace.UP).getRelative(BlockFace.UP);
        Optional<LocalPortal> portal = local.findByKeyBlock(key);
        if (portal.isEmpty() || portal.get().linkedPortalId() == null) {
            return;
        }
        local.teleportFrom(portal.get());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onButton(PlayerInteractEvent event) {
        if (!local.enabled()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (clicked.getBlockData() instanceof WallSign) {
            Optional<LocalPortal> info = local.findBySign(clicked.getLocation());
            if (info.isPresent()) {
                local.printInfo(event.getPlayer(), info.get());
                event.setCancelled(true);
            }
            return;
        }
        if (!Tag.BUTTONS.isTagged(clicked.getType())) {
            return;
        }
        Block key = clicked.getRelative(BlockFace.UP);
        Optional<LocalPortal> portal = local.findByKeyBlock(key);
        if (portal.isEmpty() || portal.get().linkedPortalId() == null) {
            return;
        }
        local.teleportFrom(portal.get());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowButton(EntityInteractEvent event) {
        if (!local.enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getShooter() instanceof Player)) {
            return;
        }
        Block button = event.getBlock();
        if (!Tag.BUTTONS.isTagged(button.getType())) {
            return;
        }
        Block key = button.getRelative(BlockFace.UP);
        Optional<LocalPortal> portal = local.findByKeyBlock(key);
        if (portal.isEmpty() || portal.get().linkedPortalId() == null) {
            return;
        }
        local.teleportFrom(portal.get());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDetectorRail(BlockRedstoneEvent event) {
        if (!local.enabled() || !config.localWalkOnActivation() || !config.localAllowMinecarts()) {
            return;
        }
        Block b = event.getBlock();
        if (b.getType() != Material.DETECTOR_RAIL) {
            return;
        }
        if (event.getOldCurrent() != 0) {
            return;
        }
        Block key = b.getRelative(BlockFace.UP).getRelative(BlockFace.UP);
        Optional<LocalPortal> portal = local.findByKeyBlock(key);
        if (portal.isEmpty() || portal.get().linkedPortalId() == null) {
            return;
        }
        for (var e : b.getWorld().getNearbyEntities(b.getLocation(), 1.5, 1.5, 1.5)) {
            if (e instanceof Minecart cart) {
                local.teleportMinecart(cart, portal.get());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!local.enabled()) {
            return;
        }
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            Optional<LocalPortal> portal = Optional.empty();
            if (block.getBlockData() instanceof WallSign) {
                portal = local.findBySign(block.getLocation());
            } else if (WoolFrame.isWool(block.getType())
                    || Tag.BUTTONS.isTagged(block.getType())
                    || ShapeHasher.isPressurePlate(block.getType())) {
                portal = local.findByOccupied(block.getLocation());
            }
            if (portal.isEmpty()) {
                continue;
            }
            if (config.localPortalProtection()) {
                it.remove();
            } else {
                local.destroy(portal.get(), true);
            }
        }
    }

    /** Used by cross-server PortalListener to avoid double-handling. */
    public boolean isLocalPlate(Block plate) {
        if (!local.enabled() || plate == null) {
            return false;
        }
        Block key = plate.getRelative(BlockFace.UP).getRelative(BlockFace.UP);
        return local.findByKeyBlock(key).isPresent();
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        if (c == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
