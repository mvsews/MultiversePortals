package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.travel.TravelService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final TravelService travelService;
    private final PortalEffects effects;

    public PlayerListener(
            MultiversePortalsPlugin plugin,
            TravelService travelService,
            PortalEffects effects
    ) {
        this.plugin = plugin;
        this.travelService = travelService;
        this.effects = effects;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        travelService.handleJoin(event.getPlayer());
        var player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> effects.arrival(player), 10L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.notifyAcceptTransfersRestartIfNeeded(player);
            if (plugin.updateChecker() != null) {
                plugin.updateChecker().notifyAdminIfNeeded(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        effects.cancel(event.getPlayer().getUniqueId());
        travelService.clearSession(event.getPlayer().getUniqueId());
        // Score persist is handled by ScoreService's periodic scheduler — do not encode inventory on quit.
    }
}
