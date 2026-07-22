package io.multiverseportals.travel;

import io.multiverseportals.db.Database;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One-time consent for one-way (no-return) portal transfers. */
public final class ConsentService {

    private final Database db;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    public ConsentService(Database db) {
        this.db = db;
    }

    public boolean isReady(Player player) {
        return isReady(player.getUniqueId());
    }

    public boolean isReady(UUID uuid) {
        return cache.computeIfAbsent(uuid, db::isPlayerReady);
    }

    public void setReady(UUID uuid, boolean ready) {
        cache.put(uuid, ready);
        db.setPlayerReady(uuid, ready);
    }

    public void setReady(Player player, boolean ready) {
        setReady(player.getUniqueId(), ready);
    }
}
