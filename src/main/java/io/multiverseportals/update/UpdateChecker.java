package io.multiverseportals.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks https://mp.mvse.ws/version.json (or config URL).
 * Notifies admins when major.minor is behind (patch-only bumps are silent).
 * Optional: download jar into plugins/update/ for Paper to apply on next restart.
 */
public final class UpdateChecker {

    public record LatestInfo(
            PluginVersion version,
            String channel,
            Map<String, String> whatsNew,
            String downloadUrl,
            String pageUrl
    ) {}

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    private final AtomicReference<LatestInfo> latest = new AtomicReference<>();
    private final Set<UUID> notifiedThisSession = ConcurrentHashMap.newKeySet();
    private BukkitTask pollTask;
    private volatile boolean updateJarReady;

    public UpdateChecker(MultiversePortalsPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!config.updateCheckEnabled()) {
            plugin.getLogger().info("Update check disabled");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refreshSafe);
        long period = Math.max(60L, config.updateCheckIntervalMinutes()) * 60L * 20L;
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshSafe, period, period);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    public Optional<LatestInfo> latest() {
        return Optional.ofNullable(latest.get());
    }

    public PluginVersion localVersion() {
        return PluginVersion.parse(plugin.getPluginMeta().getVersion())
                .orElseGet(() -> new PluginVersion(0, 0, 0, plugin.getPluginMeta().getVersion()));
    }

    public boolean isOutdatedMajorMinor() {
        LatestInfo info = latest.get();
        if (info == null) {
            return false;
        }
        return localVersion().isMajorMinorBehind(info.version());
    }

    public void refreshSafe() {
        try {
            refresh();
        } catch (Exception e) {
            plugin.getLogger().fine("Update check failed: " + e.getMessage());
        }
    }

    public void refresh() throws Exception {
        String url = config.updateCheckUrl();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "MultiversePortals/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return;
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!o.has("version")) {
            return;
        }
        Optional<PluginVersion> ver = PluginVersion.parse(o.get("version").getAsString());
        if (ver.isEmpty()) {
            return;
        }
        Map<String, String> news = new java.util.LinkedHashMap<>();
        if (o.has("whatsNew") && o.get("whatsNew").isJsonObject()) {
            JsonObject wn = o.getAsJsonObject("whatsNew");
            for (String k : wn.keySet()) {
                news.put(k.toLowerCase(java.util.Locale.ROOT), wn.get(k).getAsString());
            }
        }
        String channel = o.has("channel") ? o.get("channel").getAsString() : "release";
        String download = o.has("downloadUrl") ? o.get("downloadUrl").getAsString()
                : "https://mp.mvse.ws/download/MultiversePortals.jar";
        String page = o.has("pageUrl") ? o.get("pageUrl").getAsString() : "https://mp.mvse.ws/";
        LatestInfo info = new LatestInfo(ver.get(), channel, news, download, page);
        latest.set(info);

        if (localVersion().isMajorMinorBehind(info.version())) {
            plugin.getLogger().info("Update available: " + localVersion().normalizeDisplay()
                    + " → " + info.version().normalizeDisplay()
                    + " (" + info.channel() + ")");
            if (config.updateAutoDownload()) {
                if (downloadToUpdateFolder(info)) {
                    plugin.getLogger().info("Update jar staged — restart the server to apply "
                            + info.version().normalizeDisplay());
                }
            } else {
                plugin.getLogger().info("Run /mvp update (or set update.auto-download: true) then restart");
            }
        }
    }

    /** True if plugins/update/MultiversePortals.jar is already waiting for restart. */
    public boolean isUpdateStaged() {
        if (updateJarReady) {
            return true;
        }
        try {
            Path target = plugin.getDataFolder().getParentFile().toPath()
                    .resolve("update").resolve("MultiversePortals.jar");
            if (Files.isRegularFile(target) && Files.size(target) > 100_000L) {
                updateJarReady = true;
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Notify admin once per session when major.minor is behind. */
    public void notifyAdminIfNeeded(Player player) {
        if (!config.updateCheckEnabled() || player == null) {
            return;
        }
        if (!player.hasPermission("multiverseportals.admin") && !player.isOp()) {
            return;
        }
        if (!notifiedThisSession.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (latest.get() == null) {
                refreshSafe();
            }
            LatestInfo info = latest.get();
            if (info == null || !localVersion().isMajorMinorBehind(info.version())) {
                return;
            }
            if (config.updateAutoDownload() && !isUpdateStaged()) {
                downloadToUpdateFolder(info);
            }
            Bukkit.getScheduler().runTask(plugin, () -> sendUpdateMessage(player, info));
        });
    }

    public void sendUpdateMessage(Player player, LatestInfo info) {
        if (player == null || !player.isOnline() || info == null) {
            return;
        }
        String lang = config.messages() != null
                ? config.messages().langFor(player)
                : config.language();
        String whats = info.whatsNew().getOrDefault(lang,
                info.whatsNew().getOrDefault("en", ""));
        String current = localVersion().normalizeDisplay();
        String newest = info.version().normalizeDisplay();
        String page = info.pageUrl() == null ? "mp.mvse.ws" : info.pageUrl()
                .replace("https://", "").replace("http://", "").replaceAll("/$", "");

        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-available")
                .replace("%current%", current)
                .replace("%latest%", newest)));
        if (whats != null && !whats.isBlank()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-whats-new")
                    .replace("%whats_new%", escapeMm(whats))));
        }
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-download")
                .replace("%url%", page)));
        if (isUpdateStaged()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-restart-ready")));
        } else if (config.updateAutoDownload()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-auto-hint")));
        } else {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-cmd-hint")));
        }
    }

    /**
     * Download latest jar into plugins/update/ so Paper applies it on next restart.
     * @return true if file written
     */
    public boolean downloadToUpdateFolder(LatestInfo info) {
        if (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            return false;
        }
        try {
            Path updateDir = plugin.getDataFolder().getParentFile().toPath().resolve("update");
            Files.createDirectories(updateDir);
            Path target = updateDir.resolve("MultiversePortals.jar");
            Path tmp = updateDir.resolve("MultiversePortals.jar.part");

            HttpRequest req = HttpRequest.newBuilder(URI.create(info.downloadUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "MultiversePortals/" + plugin.getPluginMeta().getVersion())
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                plugin.getLogger().warning("Update download HTTP " + resp.statusCode());
                return false;
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(tmp);
            if (size < 100_000L) {
                Files.deleteIfExists(tmp);
                plugin.getLogger().warning("Update download too small (" + size + " bytes) — aborted");
                return false;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            updateJarReady = true;
            plugin.getLogger().info("Update jar ready for next restart: " + target
                    + " (" + size + " bytes, " + info.version().normalizeDisplay() + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Update download failed: " + e.getMessage());
            return false;
        }
    }

    public boolean downloadLatestForAdmin(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            refreshSafe();
            LatestInfo info = latest.get();
            if (info == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-check-failed"))));
                return;
            }
            if (!localVersion().isMajorMinorBehind(info.version())
                    && localVersion().compareTo(info.version()) >= 0) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-up-to-date")
                                .replace("%current%", localVersion().normalizeDisplay()))));
                return;
            }
            boolean ok = downloadToUpdateFolder(info);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-downloaded")
                            .replace("%latest%", info.version().normalizeDisplay())));
                    player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-restart-ready")));
                } else {
                    player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "update-download-failed")
                            .replace("%url%", info.pageUrl() == null ? "https://mp.mvse.ws/" : info.pageUrl())));
                }
            });
        });
        return true;
    }

    private static String escapeMm(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }
}
