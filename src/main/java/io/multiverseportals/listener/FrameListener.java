package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.portal.PortalService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Instantly deactivate matter when a portal ring is opened; restore when closed again.
 */
public final class FrameListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final PortalService portals;

    public FrameListener(MultiversePortalsPlugin plugin, PortalService portals) {
        this.plugin = plugin;
        this.portals = portals;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        recheck(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        recheck(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            recheck(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        recheck(event.getBlock());
        for (Block b : event.blockList()) {
            recheck(b);
        }
    }

    private void recheck(Block block) {
        if (block == null) {
            return;
        }
        if (plugin.portalMatter() != null && plugin.portalMatter().isMatterBlock(block)) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Portal portal : portals.findAffectedByBlock(block)) {
            if (!seen.add(portal.id())) {
                continue;
            }
            // Sign break for MULTI/AWAY is owned by SignListener (delete). Recheck anyway.
            if (portal.type() == PortalType.PAIR
                    || portal.type() == PortalType.MULTI
                    || portal.type() == PortalType.AWAY) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.database().findPortal(portal.id()).ifPresent(portals::checkFrameNow);
                });
            }
        }
    }
}
