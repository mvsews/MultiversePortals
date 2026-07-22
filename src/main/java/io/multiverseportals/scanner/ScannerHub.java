package io.multiverseportals.scanner;

import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges public scanner sources (MineScan + Cornbread + Slowstack) and always writes them
 * into the local SQLite catalog. On hub, also mirrors into central MySQL scanner pool.
 * Portals pick from that catalog by score.
 */
public final class ScannerHub {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final MineScanClient minescan;
    private final CornbreadScanClient cornbread;
    private final SlowstackScanClient slowstack;
    private ScannerPoolService scannerPool;

    public ScannerHub(JavaPlugin plugin, PluginConfig config, Database db) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.minescan = new MineScanClient(plugin, config, db);
        this.cornbread = new CornbreadScanClient(plugin, config, db);
        this.slowstack = new SlowstackScanClient(plugin, config, db);
    }

    public void setScannerPool(ScannerPoolService scannerPool) {
        this.scannerPool = scannerPool;
    }

    public void start() {
        if (!config.scannerEnabled()) {
            plugin.getLogger().info("Public scanner disabled");
            return;
        }
        minescan.start();
        cornbread.start();
        slowstack.start();
        StringBuilder sb = new StringBuilder("Scanner hub: MineScan");
        if (config.cornbreadEnabled()) {
            sb.append(" + Cornbread");
        }
        if (config.slowstackEnabled() && !config.slowstackApiKey().isBlank()) {
            sb.append(" + Slowstack");
        }
        sb.append(" → local catalog");
        if (config.catalogShareHub()) {
            sb.append(" + MySQL pool");
        }
        plugin.getLogger().info(sb.toString());
        // After first wave, mirror to central pool
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::publishCachedToPool, 20L * 45L);
    }

    public void stop() {
        minescan.stop();
        cornbread.stop();
        slowstack.stop();
    }

    public MineScanClient minescan() {
        return minescan;
    }

    public CornbreadScanClient cornbread() {
        return cornbread;
    }

    public SlowstackScanClient slowstack() {
        return slowstack;
    }

    public Database database() {
        return db;
    }

    /** Deduped merge — fresher lastSeen wins. */
    public List<ScannedServer> cached() {
        Map<String, ScannedServer> map = new HashMap<>();
        for (ScannedServer s : minescan.cached()) {
            map.put(key(s), s);
        }
        for (ScannedServer s : cornbread.cached()) {
            merge(map, s);
        }
        for (ScannedServer s : slowstack.cached()) {
            merge(map, s);
        }
        return new ArrayList<>(map.values());
    }

    private static void merge(Map<String, ScannedServer> map, ScannedServer s) {
        String k = key(s);
        ScannedServer prev = map.get(k);
        if (prev == null || s.lastSeenMs() >= prev.lastSeenMs()) {
            map.put(k, s);
        }
    }

    public int cacheSize() {
        return cached().size();
    }

    public int minescanSize() {
        return minescan.cacheSize();
    }

    public int cornbreadSize() {
        return cornbread.cacheSize();
    }

    public int slowstackSize() {
        return slowstack.cacheSize();
    }

    public long lastRefreshMs() {
        return Math.max(minescan.lastRefreshMs(),
                Math.max(cornbread.lastRefreshMs(), slowstack.lastRefreshMs()));
    }

    public String lastError() {
        List<String> parts = new ArrayList<>();
        appendErr(parts, "minescan", minescan.lastError());
        appendErr(parts, "cornbread", cornbread.lastError());
        appendErr(parts, "slowstack", slowstack.lastError());
        return String.join("; ", parts);
    }

    private static void appendErr(List<String> parts, String name, String err) {
        if (err != null && !err.isBlank()) {
            parts.add(name + "=" + err);
        }
    }

    public void refreshNow() {
        if (shouldSkipPublic()) {
            publishCachedToPool();
            return;
        }
        minescan.refreshNow();
        if (config.cornbreadEnabled()) {
            cornbread.refreshNow();
        }
        if (config.slowstackEnabled() && !config.slowstackApiKey().isBlank()) {
            slowstack.refreshNow();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::publishCachedToPool);
    }

    public void refreshBlocking() throws Exception {
        if (shouldSkipPublic()) {
            publishCachedToPool();
            return;
        }
        Exception first = null;
        try {
            minescan.refreshBlocking();
        } catch (Exception e) {
            first = e;
        }
        if (config.cornbreadEnabled()) {
            try {
                cornbread.refreshBlocking();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (config.slowstackEnabled() && !config.slowstackApiKey().isBlank()) {
            try {
                slowstack.refreshBlocking();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        publishCachedToPool();
        if (first != null && cacheSize() == 0 && catalogSize() == 0) {
            throw first;
        }
    }

    private boolean shouldSkipPublic() {
        // Hub always vacuums; leaves may skip when hub pool is warm
        if (config.catalogShareHub()) {
            return false;
        }
        return scannerPool != null && scannerPool.preferSkipPublicScanners();
    }

    private void publishCachedToPool() {
        if (scannerPool == null) {
            return;
        }
        try {
            scannerPool.publishScannedToHub(cached());
        } catch (Exception e) {
            plugin.getLogger().warning("Scanner hub-pool publish: " + e.getMessage());
        }
    }

    public int catalogSize() {
        if (db == null) {
            return 0;
        }
        return db.probeCacheStats()[0];
    }

    private static String key(ScannedServer s) {
        return s.host().toLowerCase(java.util.Locale.ROOT) + ":" + s.port();
    }
}
