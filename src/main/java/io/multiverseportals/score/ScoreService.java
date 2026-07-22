package io.multiverseportals.score;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.util.InventoryCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public final class ScoreService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final PluginConfig config;
    private BukkitTask task;

    public ScoreService(MultiversePortalsPlugin plugin, Database db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    public void startScheduler() {
        if (!config.scoreEnabled()) {
            return;
        }
        long period = config.scorePushIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stopScheduler() {
        if (task != null) {
            task.cancel();
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            push(player);
        }
    }

    public void push(Player player) {
        double score = calculate(player);
        byte[] inv = InventoryCodec.encode(player.getInventory());
        db.savePlayerState(player.getUniqueId(), inv, null, player.getLevel(), score);
    }

    public double calculate(Player player) {
        double score = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                score += item.getAmount() * config.weightInventoryStack();
            }
        }
        score += player.getLevel() * config.weightXpLevel();
        int minutes = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20 / 60;
        score += minutes * config.weightPlaytimeMinutes();
        return score;
    }

    public double local(Player player) {
        return db.localScore(player.getUniqueId());
    }
}
