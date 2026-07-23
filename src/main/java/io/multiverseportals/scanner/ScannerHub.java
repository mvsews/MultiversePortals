package io.multiverseportals.scanner;

import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Merges public scanner sources (MineScan + Cornbread + Slowstack) and always writes them
 * into the local SQLite catalog. On hub, also mirrors into central MySQL scanner pool.
 * Portals pick from that catalog by score.
 * <p>
 * Each source is fail-open: one scanner throwing must never abort start/refresh/bind search.
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
        safeRun("minescan.start", minescan::start);
        safeRun("cornbread.start", cornbread::start);
        safeRun("slowstack.start", slowstack::start);
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
        safeRun("minescan.stop", minescan::stop);
        safeRun("cornbread.stop", cornbread::stop);
        safeRun("slowstack.stop", slowstack::stop);
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

    /** Deduped merge — fresher lastSeen wins. Never throws; bad sources are skipped. */
    public List<ScannedServer> cached() {
        Map<String, ScannedServer> map = new HashMap<>();
        for (ScannedServer s : safeCached("minescan", minescan::cached)) {
            map.put(key(s), s);
        }
        for (ScannedServer s : safeCached("cornbread", cornbread::cached)) {
            merge(map, s);
        }
        for (ScannedServer s : safeCached("slowstack", slowstack::cached)) {
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
        return safeInt("minescan.size", minescan::cacheSize);
    }

    public int cornbreadSize() {
        return safeInt("cornbread.size", cornbread::cacheSize);
    }

    public int slowstackSize() {
        return safeInt("slowstack.size", slowstack::cacheSize);
    }

    public long lastRefreshMs() {
        long a = safeLong("minescan.lastRefresh", minescan::lastRefreshMs);
        long b = safeLong("cornbread.lastRefresh", cornbread::lastRefreshMs);
        long c = safeLong("slowstack.lastRefresh", slowstack::lastRefreshMs);
        return Math.max(a, Math.max(b, c));
    }

    public String lastError() {
        List<String> parts = new ArrayList<>();
        appendErr(parts, "minescan", safeString("minescan.lastError", minescan::lastError));
        appendErr(parts, "cornbread", safeString("cornbread.lastError", cornbread::lastError));
        appendErr(parts, "slowstack", safeString("slowstack.lastError", slowstack::lastError));
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
        safeRun("minescan.refreshNow", minescan::refreshNow);
        if (config.cornbreadEnabled()) {
            safeRun("cornbread.refreshNow", cornbread::refreshNow);
        }
        if (config.slowstackEnabled() && !config.slowstackApiKey().isBlank()) {
            safeRun("slowstack.refreshNow", slowstack::refreshNow);
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::publishCachedToPool);
    }

    /**
     * Best-effort sync refresh for bind/travel. Never throws — callers keep searching
     * with whatever cache / SQLite catalog remains.
     */
    public void refreshBlocking() {
        if (shouldSkipPublic()) {
            publishCachedToPool();
            return;
        }
        try {
            minescan.refreshBlocking();
        } catch (Throwable t) {
            warn("minescan.refreshBlocking", t);
        }
        if (config.cornbreadEnabled()) {
            try {
                cornbread.refreshBlocking();
            } catch (Throwable t) {
                warn("cornbread.refreshBlocking", t);
            }
        }
        if (config.slowstackEnabled() && !config.slowstackApiKey().isBlank()) {
            try {
                slowstack.refreshBlocking();
            } catch (Throwable t) {
                warn("slowstack.refreshBlocking", t);
            }
        }
        publishCachedToPool();
    }

    private boolean shouldSkipPublic() {
        // Hub always vacuums; leaves may skip when hub pool is warm
        if (config.catalogShareHub()) {
            return false;
        }
        try {
            if (scannerPool == null || !scannerPool.preferSkipPublicScanners()) {
                return false;
            }
        } catch (Throwable t) {
            warn("hub-pool.preferSkip", t);
            return false;
        }
        // Fail-open: never skip public APIs when we have no live cache / thin SQLite
        if (cacheSize() == 0 && catalogSize() < 8) {
            return false;
        }
        return true;
    }

    private void publishCachedToPool() {
        if (scannerPool == null) {
            return;
        }
        try {
            scannerPool.publishScannedToHub(cached());
        } catch (Throwable t) {
            warn("hub-pool.publish", t);
        }
    }

    public int catalogSize() {
        if (db == null) {
            return 0;
        }
        try {
            return db.probeCacheStats()[0];
        } catch (Throwable t) {
            warn("catalog.size", t);
            return 0;
        }
    }

    private void safeRun(String label, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            warn(label, t);
        }
    }

    private List<ScannedServer> safeCached(String label, Supplier<List<ScannedServer>> supplier) {
        try {
            List<ScannedServer> list = supplier.get();
            return list == null ? Collections.emptyList() : list;
        } catch (Throwable t) {
            warn(label + ".cached", t);
            return Collections.emptyList();
        }
    }

    private int safeInt(String label, Supplier<Integer> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            warn(label, t);
            return 0;
        }
    }

    private long safeLong(String label, Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            warn(label, t);
            return 0L;
        }
    }

    private String safeString(String label, Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            warn(label, t);
            return null;
        }
    }

    private void warn(String label, Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        plugin.getLogger().warning("Scanner " + label + " failed (ignored): " + msg);
    }

    private static String key(ScannedServer s) {
        return s.host().toLowerCase(java.util.Locale.ROOT) + ":" + s.port();
    }
}
