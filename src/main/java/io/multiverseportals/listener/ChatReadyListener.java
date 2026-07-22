package io.multiverseportals.listener;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.travel.ConsentService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Accept "mvp ready" / "mvp ready off" in chat without slash. */
public final class ChatReadyListener implements Listener {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final ConsentService consent;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ChatReadyListener(MultiversePortalsPlugin plugin, PluginConfig config, ConsentService consent) {
        this.plugin = plugin;
        this.config = config;
        this.consent = consent;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("mvp ready")) {
            return;
        }
        Player player = event.getPlayer();
        boolean off = lower.equals("mvp ready off") || lower.equals("mvp ready false")
                || lower.endsWith(" off") || lower.endsWith(" false");
        boolean on = lower.equals("mvp ready") || lower.equals("mvp ready on") || lower.equals("mvp ready true");
        if (!on && !off) {
            return;
        }
        if (config.readyHideChat()) {
            event.setCancelled(true);
        }
        boolean ready = on && !off;
        consent.setReady(player, ready);
        String msg = ready ? config.message(player, "ready-on") : config.message(player, "ready-off");
        // chat is async — bounce to main for Adventure if needed; sendMessage is usually fine
        player.sendMessage(mm.deserialize(config.prefix(player) + msg));
    }
}
