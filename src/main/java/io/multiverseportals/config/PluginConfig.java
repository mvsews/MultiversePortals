package io.multiverseportals.config;

import io.multiverseportals.i18n.Messages;
import io.multiverseportals.model.PeerPolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PluginConfig {

    private final JavaPlugin plugin;
    private Messages messages;
    /** Cached result of optional WAN IP discovery (null = not attempted yet). */
    private volatile String discoveredWanHost;
    private volatile boolean discoverAttempted;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public Messages messages() {
        return messages;
    }

    public void reload() {
        plugin.reloadConfig();
        if (messages != null) {
            messages.load(language(), languagePerPlayer());
        }
    }

    /** Server fallback language (en / de / ru / zh). */
    public String language() {
        return plugin.getConfig().getString("language", "en");
    }

    /** Prefer each player's client locale when we ship that language. Default true. */
    public boolean languagePerPlayer() {
        return plugin.getConfig().getBoolean("language-per-player", true);
    }

    public void setLanguage(String code) {
        plugin.getConfig().set("language", code);
        plugin.saveConfig();
        if (messages != null) {
            messages.load(code, languagePerPlayer());
        }
    }

    public String message(String key) {
        if (messages != null) {
            return messages.get(key);
        }
        return plugin.getConfig().getString("messages." + key, key);
    }

    public String message(org.bukkit.entity.Player player, String key) {
        if (messages != null) {
            return player == null ? messages.get(key) : messages.get(player, key);
        }
        return message(key);
    }

    public String message(org.bukkit.command.CommandSender sender, String key) {
        if (messages != null) {
            return messages.get(sender, key);
        }
        return message(key);
    }

    public String prefix() {
        return message("prefix");
    }

    public String prefix(org.bukkit.entity.Player player) {
        return message(player, "prefix");
    }

    public String prefix(org.bukkit.command.CommandSender sender) {
        return message(sender, "prefix");
    }

    /** Ensures id/host/secret exist; returns true if config was written. */
    public boolean ensureBootstrapDefaults(String guessedHost) {
        boolean changed = false;
        String id = plugin.getConfig().getString("server.id", "");
        if (id == null || id.isBlank()) {
            id = "srv-" + UUID.randomUUID().toString().substring(0, 8);
            plugin.getConfig().set("server.id", id);
            changed = true;
        }
        // Empty / "auto" → use Minecraft MOTD as the public label (not server.id)
        if (!plugin.getConfig().isSet("server.display-name")) {
            plugin.getConfig().set("server.display-name", "");
            changed = true;
        }
        // Empty / "auto" → resolve at runtime from server-ip / Bukkit (do not bake 127.0.0.1)
        String host = plugin.getConfig().getString("server.public-host", "");
        if (host == null) {
            plugin.getConfig().set("server.public-host", "");
            changed = true;
        } else if ("auto".equalsIgnoreCase(host.trim())) {
            // normalize
            plugin.getConfig().set("server.public-host", "");
            changed = true;
        }
        if (!plugin.getConfig().isSet("server.public-port")) {
            // 0 = use Bukkit.getPort() / server-port
            plugin.getConfig().set("server.public-port", 0);
            changed = true;
        }
        // guessedHost kept for API compatibility (runtime resolve uses server-ip instead)
        // keep legacy signing-secret for optional federation
        String secret = plugin.getConfig().getString("server.signing-secret", "");
        if (secret == null || secret.isBlank()) {
            plugin.getConfig().set("server.signing-secret", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
            reload();
        }
        return changed;
    }

    public String serverId() {
        return plugin.getConfig().getString("server.id", "unnamed");
    }

    /**
     * Public label for catalogs / website.
     * Prefer configured {@code server.display-name}; if blank, {@code auto}, or equal to
     * {@code server.id} (legacy bootstrap), use the live Minecraft MOTD.
     */
    public String displayName() {
        String configured = plugin.getConfig().getString("server.display-name", "");
        if (configured != null) {
            configured = configured.trim();
        }
        if (configured != null && !configured.isBlank()
                && !"auto".equalsIgnoreCase(configured)
                && !configured.equalsIgnoreCase(serverId())) {
            return configured;
        }
        return liveServerLabel();
    }

    /** MOTD line 1 (list name), or server id if empty. */
    public String liveServerLabel() {
        try {
            String name = io.multiverseportals.util.ServerBranding.local().name();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return serverId();
    }

    /** Configured override, or empty/`auto` for runtime resolve. */
    public String publicHostConfigured() {
        String h = plugin.getConfig().getString("server.public-host", "");
        return h == null ? "" : h.trim();
    }

    /**
     * Address peers use for Transfer here.
     * Override in config, else cached WAN discover, else {@code server-ip} / local guess.
     */
    public String publicHost() {
        String configured = publicHostConfigured();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            return configured;
        }
        if (discoveredWanHost != null && !discoveredWanHost.isBlank()) {
            return discoveredWanHost;
        }
        return io.multiverseportals.util.PublicEndpoint.resolveHost(configured);
    }

    /** Async-safe: call off main thread when {@link #discoverPublicIp()} is true. */
    public void tryDiscoverPublicIp() {
        if (!discoverPublicIp() || discoverAttempted) {
            return;
        }
        discoverAttempted = true;
        String host = io.multiverseportals.util.PublicEndpoint.resolveHost(publicHostConfigured());
        String wan = io.multiverseportals.util.PublicEndpoint.maybeDiscoverWan(host, true, plugin.getLogger());
        if (wan != null && !wan.isBlank() && !io.multiverseportals.util.PublicEndpoint.isPrivateOrLocal(wan)) {
            discoveredWanHost = wan;
        }
    }

    /** 0 or unset → live {@code server-port}. */
    public int publicPort() {
        return io.multiverseportals.util.PublicEndpoint.resolvePort(
                plugin.getConfig().getInt("server.public-port", 0));
    }

    /** True if {@code host} is this server's public address (never Random-bind to self). */
    public boolean isOwnPublicHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.trim();
        String pub = publicHost();
        if (pub != null && !pub.isBlank() && pub.equalsIgnoreCase(h)) {
            return true;
        }
        String configured = publicHostConfigured();
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)
                && configured.equalsIgnoreCase(h)) {
            return true;
        }
        String sid = serverId();
        return sid != null && !sid.isBlank() && sid.equalsIgnoreCase(h);
    }

    /** When public-host is still private, try HTTPS WAN lookup once. Default off. */
    public boolean discoverPublicIp() {
        return plugin.getConfig().getBoolean("server.discover-public-ip", false);
    }

    /**
     * Public catalog listing: {@code auto} (default) | {@code always} | {@code never}.
     * Auto = accept-transfers + non-private host.
     */
    public String listPubliclyMode() {
        return plugin.getConfig().getString("server.list-publicly", "auto");
    }

    public boolean acceptTransfersEnabled() {
        return io.multiverseportals.util.PublicEndpoint.acceptTransfersEnabled();
    }

    /** First run: write accepts-transfers=true into server.properties if off. */
    public boolean autoEnableAcceptTransfers() {
        return plugin.getConfig().getBoolean("server.auto-enable-accept-transfers", true);
    }

    /** Whether this node should announce itself to the public hub catalog. */
    public boolean shouldListPublicly() {
        return io.multiverseportals.util.PublicEndpoint.shouldListPublicly(
                listPubliclyMode(), publicHost(), acceptTransfersEnabled());
    }

    public boolean isLocalOnlyMode() {
        return !shouldListPublicly();
    }

    public String signingSecret() {
        return plugin.getConfig().getString("server.signing-secret", "change-me");
    }

    public boolean federationEnabled() {
        // Optional P2P; open-network uses registry sessions by default
        return plugin.getConfig().getBoolean("federation.enabled", false);
    }

    public String federationBind() {
        return plugin.getConfig().getString("federation.bind", "0.0.0.0");
    }

    public int federationPort() {
        return plugin.getConfig().getInt("federation.port", 25765);
    }

    public String federationPath() {
        return plugin.getConfig().getString("federation.path", "/mvp/v1");
    }

    public int heartbeatSeconds() {
        return Math.max(60, plugin.getConfig().getInt("federation.heartbeat-seconds", 900));
    }

    public File sqliteFile() {
        String name = plugin.getConfig().getString("database.sqlite-file", "mvp.db");
        return new File(plugin.getDataFolder(), name);
    }

    public boolean registryEnabled() {
        // Default off — friends join the network via HTTPS hub, only the central host needs JDBC.
        return plugin.getConfig().getBoolean("registry.enabled", false);
    }

    public String registryJdbcUrl() {
        return plugin.getConfig().getString("registry.jdbc-url", "");
    }

    public String registryUser() {
        return plugin.getConfig().getString("registry.user", "");
    }

    public String registryPassword() {
        return plugin.getConfig().getString("registry.password", "");
    }

    public boolean registryMultiOptIn() {
        return plugin.getConfig().getBoolean("registry.multi-opt-in", true);
    }

    public void setRegistryMultiOptIn(boolean value) {
        plugin.getConfig().set("registry.multi-opt-in", value);
        plugin.saveConfig();
        reload();
    }

    public String registryTags() {
        return plugin.getConfig().getString("registry.tags", "");
    }

    public Optional<String> registryFederationUrlOverride() {
        String v = plugin.getConfig().getString("registry.federation-url", "");
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    public boolean registryPublishPortals() {
        return plugin.getConfig().getBoolean("registry.publish-portals", true);
    }

    public int registryHeartbeatSeconds() {
        return Math.max(60, plugin.getConfig().getInt("registry.heartbeat-seconds", 900));
    }

    public long registryStaleMs() {
        // Default 1.5 hours — map shows offline after this without a hub ping
        return Math.max(60, plugin.getConfig().getLong("registry.stale-seconds", 5400)) * 1000L;
    }

    public boolean catalogShareEnabled() {
        return plugin.getConfig().getBoolean("catalog-share.enabled", true);
    }

    public boolean catalogShareHub() {
        return plugin.getConfig().getBoolean("catalog-share.hub", false);
    }

    public boolean catalogSharePush() {
        return plugin.getConfig().getBoolean("catalog-share.push", true);
    }

    public boolean catalogSharePull() {
        return plugin.getConfig().getBoolean("catalog-share.pull", true);
    }

    public int catalogShareIntervalSeconds() {
        return Math.max(60, plugin.getConfig().getInt("catalog-share.interval-seconds", 900));
    }

    public int catalogShareMaxEntries() {
        return Math.max(1, plugin.getConfig().getInt("catalog-share.max-entries", 100));
    }

    public String catalogShareNetworkSecret() {
        String v = plugin.getConfig().getString("catalog-share.network-secret", "mvp-open-catalog");
        return v == null || v.isBlank() ? "mvp-open-catalog" : v;
    }

    public boolean catalogSharePublicRead() {
        return plugin.getConfig().getBoolean("catalog-share.public-read", true);
    }

    /** Official public catalog hub — used when bootstrap-urls is empty and auto-bootstrap is on. */
    public static final String DEFAULT_CATALOG_HUB = "https://mp.mvse.ws/mvp/v1";

    public boolean catalogShareAutoBootstrap() {
        return plugin.getConfig().getBoolean("catalog-share.auto-bootstrap", true);
    }

    public List<String> catalogShareBootstrapUrls() {
        List<String> raw = plugin.getConfig().getStringList("catalog-share.bootstrap-urls");
        List<String> list = raw == null
                ? List.of()
                : raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
        if (list.isEmpty()) {
            return catalogShareAutoBootstrap() ? List.of(DEFAULT_CATALOG_HUB) : List.of();
        }
        if (list.size() == 1) {
            String only = list.get(0);
            if ("none".equalsIgnoreCase(only) || "off".equalsIgnoreCase(only) || "-".equals(only)) {
                return List.of();
            }
        }
        return list;
    }

    /** Listen for federation HTTP when explicitly enabled or acting as catalog hub. */
    public boolean federationShouldListen() {
        return federationEnabled() || (catalogShareEnabled() && catalogShareHub());
    }

    public boolean openNetwork() {
        return plugin.getConfig().getBoolean("open-network.enabled", true);
    }

    public boolean everyoneCanCreate() {
        return plugin.getConfig().getBoolean("open-network.everyone-can-create", true);
    }

    public boolean requireTrust() {
        return plugin.getConfig().getBoolean("open-network.require-trust", false);
    }

    public boolean defaultExportInventory() {
        return plugin.getConfig().getBoolean("open-network.default-export-inventory", true);
    }

    public boolean defaultImportInventory() {
        return plugin.getConfig().getBoolean("open-network.default-import-inventory", true);
    }

    public void setDefaultExportInventory(boolean value) {
        plugin.getConfig().set("open-network.default-export-inventory", value);
        plugin.saveConfig();
        reload();
    }

    public void setDefaultImportInventory(boolean value) {
        plugin.getConfig().set("open-network.default-import-inventory", value);
        plugin.saveConfig();
        reload();
    }

    public void setPeerPolicyFlag(String peerId, String yamlKey, boolean value) {
        plugin.getConfig().set("peers." + peerId + "." + yamlKey, value);
        plugin.saveConfig();
        reload();
    }

    public boolean signsAutoCreate() {
        return plugin.getConfig().getBoolean("signs.auto-create", true);
    }

    public boolean pairRequireBothSides() {
        return true;
    }

    public boolean multiAllowPluginless() {
        return true;
    }

    public boolean multiCarryRequiresPlugin() {
        return true;
    }

    public boolean scoreEnabled() {
        return plugin.getConfig().getBoolean("score.enabled", true);
    }

    public int scorePushIntervalSeconds() {
        return plugin.getConfig().getInt("score.push-interval-seconds", 300);
    }

    public double weightInventoryStack() {
        return 1.0;
    }

    public double weightXpLevel() {
        return 2.0;
    }

    public double weightPlaytimeMinutes() {
        return 0.1;
    }

    public int sessionTtlSeconds() {
        return plugin.getConfig().getInt("travel.session-ttl-seconds", 180);
    }

    public boolean useTransferPacket() {
        return plugin.getConfig().getBoolean("travel.use-transfer-packet", true);
    }

    public boolean effectsEnabled() {
        return plugin.getConfig().getBoolean("effects.enabled", true);
    }

    /** Charge duration before transfer (ticks, 20 = 1s). Default ~5s. */
    public int effectsChargeTicks() {
        return plugin.getConfig().getInt("effects.charge-ticks", 100);
    }

    public boolean effectsIdle() {
        return plugin.getConfig().getBoolean("effects.idle-particles", true);
    }

    public String effectsTitle() {
        String fromCfg = plugin.getConfig().getString("effects.title", "");
        if (fromCfg != null && !fromCfg.isBlank()) {
            return fromCfg;
        }
        return message("effects-title");
    }

    public String effectsSubtitle() {
        String fromCfg = plugin.getConfig().getString("effects.subtitle", "");
        if (fromCfg != null && !fromCfg.isBlank()) {
            return fromCfg;
        }
        return message("effects-subtitle");
    }

    /** Fill portal opening with nether/end-like matter (BlockDisplay). */
    public boolean matterEnabled() {
        return plugin.getConfig().getBoolean("effects.matter.enabled", true);
    }

    /** nether | end | gateway */
    public String matterStyle() {
        return plugin.getConfig().getString("effects.matter.style", "nether");
    }

    public boolean matterParticles() {
        return plugin.getConfig().getBoolean("effects.matter.particles", true);
    }

    public boolean compatibilityEnabled() {
        return plugin.getConfig().getBoolean("compatibility.enabled", true);
    }

    public boolean scannerEnabled() {
        return plugin.getConfig().getBoolean("scanner.enabled", true);
    }

    public String scannerBaseUrl() {
        return plugin.getConfig().getString("scanner.base-url", "https://data.minescan.xyz");
    }

    public boolean cornbreadEnabled() {
        return plugin.getConfig().getBoolean("scanner.cornbread.enabled", true);
    }

    public String cornbreadBaseUrl() {
        return plugin.getConfig().getString("scanner.cornbread.base-url", "https://api.cornbread2100.com/v1");
    }

    /** 0 = do not drop by lastSeen (Cornbread timestamps are often weeks old). */
    public int cornbreadMaxAgeHours() {
        return Math.max(0, plugin.getConfig().getInt("scanner.cornbread.max-age-hours", 0));
    }

    public boolean slowstackEnabled() {
        return plugin.getConfig().getBoolean("scanner.slowstack.enabled", false);
    }

    public String slowstackBaseUrl() {
        return plugin.getConfig().getString("scanner.slowstack.base-url", "https://slowstack.tv/api/v1");
    }

    public String slowstackApiKey() {
        String k = plugin.getConfig().getString("scanner.slowstack.api-key", "");
        return k == null ? "" : k.trim();
    }

    public boolean slowstackOnlineOnly() {
        return plugin.getConfig().getBoolean("scanner.slowstack.online-only", true);
    }

    /** Comma-separated ISO country codes, e.g. {@code US,DE,BR}. Empty = any. */
    public String slowstackCountryCodes() {
        return plugin.getConfig().getString("scanner.slowstack.country-codes", "");
    }

    public int scannerRefreshSeconds() {
        return plugin.getConfig().getInt("scanner.refresh-seconds", 120);
    }

    public int scannerSampleCount() {
        return plugin.getConfig().getInt("scanner.sample-count", 40);
    }

    public int scannerMinPlayers() {
        return plugin.getConfig().getInt("scanner.min-players", 1);
    }

    public String scannerAuthMode() {
        return plugin.getConfig().getString("scanner.auth-mode", "online");
    }

    public boolean scannerExcludeWhitelist() {
        return plugin.getConfig().getBoolean("scanner.exclude-whitelist", true);
    }

    public int scannerMaxAgeHours() {
        return plugin.getConfig().getInt("scanner.max-age-hours", 168);
    }

    public String scannerVersionPrefix() {
        return plugin.getConfig().getString("scanner.version-prefix", "");
    }

    public int scannerProbeTimeoutMs() {
        return plugin.getConfig().getInt("scanner.probe-timeout-ms", 2500);
    }

    public int scannerMaxAttempts() {
        return plugin.getConfig().getInt("scanner.max-attempts", 20);
    }

    public boolean scannerRefreshOnFail() {
        return plugin.getConfig().getBoolean("scanner.refresh-on-fail", true);
    }

    public int scannerSearchMaxSeconds() {
        return plugin.getConfig().getInt("scanner.search-max-seconds", 45);
    }

    /** UDP ports to probe for Geyser/Bedrock before transferring Floodgate players. */
    public java.util.List<Integer> scannerBedrockPorts() {
        java.util.List<Integer> list = plugin.getConfig().getIntegerList("scanner.bedrock-ports");
        if (list == null || list.isEmpty()) {
            return java.util.List.of(19132, 19133);
        }
        return list;
    }

    public boolean scannerRequireGeyserForBedrock() {
        return plugin.getConfig().getBoolean("scanner.require-geyser-for-bedrock", true);
    }

    /** Bedrock protocol must match dest Geyser advertisement exactly. */
    public boolean scannerRequireBedrockProtocolMatch() {
        return plugin.getConfig().getBoolean("scanner.require-bedrock-protocol-match", true);
    }

    /** Soft: same major.minor Bedrock family (26.32 ≠ 26.30). Empty client/dest version still allowed. */
    public boolean scannerRequireBedrockVersionMatch() {
        return plugin.getConfig().getBoolean("scanner.require-bedrock-version-match", false);
    }

    /**
     * Reject hosts whose MOTD/name looks closed (whitelist / private…).
     * online=0 is still allowed.
     */
    public java.util.List<String> scannerRejectMotdKeywords() {
        java.util.List<String> list = plugin.getConfig().getStringList("scanner.reject-motd-keywords");
        if (list == null || list.isEmpty()) {
            return java.util.List.of(
                    "whitelist", "white-list", "white list",
                    "private", "closed", "invite only", "invite-only",
                    "not public", "members only", "staff only"
            );
        }
        return list;
    }

    /** How many Bedrock pongs must agree before bind (1–3). */
    public int scannerBindConfirmProbes() {
        return Math.max(1, Math.min(3, plugin.getConfig().getInt("scanner.bind-confirm-probes", 2)));
    }

    /** Re-probe host immediately before transfer. */
    public boolean scannerConfirmBeforeTransfer() {
        return plugin.getConfig().getBoolean("scanner.confirm-before-transfer", true);
    }

    /** If player returns within this many seconds after transfer — blacklist dest. */
    public int scannerBounceBackSeconds() {
        return Math.max(30, plugin.getConfig().getInt("scanner.bounce-back-seconds", 120));
    }

    /** Skip re-probing hosts that failed recently (ms). */
    public long scannerDeadTtlMs() {
        return Math.max(0, plugin.getConfig().getInt("scanner.dead-ttl-seconds", 900)) * 1000L;
    }

    /** How long a verified OK host stays in the fallback pool. */
    public long scannerVerifiedMaxAgeMs() {
        return Math.max(60, plugin.getConfig().getInt("scanner.verified-max-age-hours", 72)) * 3600_000L;
    }

    public int scannerVerifiedMinSuccess() {
        return Math.max(1, plugin.getConfig().getInt("scanner.verified-min-success", 1));
    }

    public int scannerVerifiedLimit() {
        return Math.max(1, plugin.getConfig().getInt("scanner.verified-limit", 40));
    }

    public boolean scannerMemoryEnabled() {
        return plugin.getConfig().getBoolean("scanner.memory-enabled", true);
    }

    public boolean readyRequiredForOneWay() {
        // Preferred: ready.confirm (default off)
        if (plugin.getConfig().isSet("ready.confirm")) {
            return plugin.getConfig().getBoolean("ready.confirm");
        }
        if (plugin.getConfig().isSet("ready-confirm")) {
            return plugin.getConfig().getBoolean("ready-confirm");
        }
        // Legacy key
        if (plugin.getConfig().isSet("ready.required-for-one-way")) {
            return plugin.getConfig().getBoolean("ready.required-for-one-way");
        }
        return false;
    }

    public boolean readyHideChat() {
        return plugin.getConfig().getBoolean("ready.hide-chat", true);
    }

    public boolean ingressEnabled() {
        return plugin.getConfig().getBoolean("ingress.enabled", true);
    }

    public int ingressMaxOnline() {
        return plugin.getConfig().getInt("ingress.max-online", 0);
    }

    public int ingressReserveSlots() {
        return plugin.getConfig().getInt("ingress.reserve-slots", 2);
    }

    public double ingressMinScore() {
        return plugin.getConfig().getDouble("ingress.min-score", 0);
    }

    public boolean ingressDenyUnknownScore() {
        return plugin.getConfig().getBoolean("ingress.deny-unknown-score", false);
    }

    public int ingressMaxArrivalsPerHour() {
        return plugin.getConfig().getInt("ingress.max-arrivals-per-hour", 0);
    }

    public boolean updateCheckEnabled() {
        return plugin.getConfig().getBoolean("update.check", true);
    }

    public String updateCheckUrl() {
        String u = plugin.getConfig().getString("update.check-url", "https://mp.mvse.ws/version.json");
        return u == null || u.isBlank() ? "https://mp.mvse.ws/version.json" : u.trim();
    }

    /** Minutes between background version polls. */
    public int updateCheckIntervalMinutes() {
        return Math.max(15, plugin.getConfig().getInt("update.check-interval-minutes", 180));
    }

    /**
     * When true, automatically download the new jar into plugins/update/
     * (Paper applies it on the next restart). Safe default: false — admins use /mvp update.
     */
    public boolean updateAutoDownload() {
        return plugin.getConfig().getBoolean("update.auto-download", true);
    }

    /**
     * Policy toward a peer. Uses peers.&lt;id&gt; overrides if present, else open-network defaults.
     * Trust is optional — overrides can exist without full trust handshake.
     */
    public PeerPolicy policyFor(String peerId) {
        Map<String, PeerPolicy> overrides = peerOverrides();
        if (peerId != null && overrides.containsKey(peerId)) {
            return overrides.get(peerId);
        }
        return defaults();
    }

    public Map<String, PeerPolicy> peerOverrides() {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("peers");
        if (root == null) {
            return Collections.emptyMap();
        }
        Map<String, PeerPolicy> map = new HashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            map.put(key, new PeerPolicy(
                    s.getBoolean("allow-travel", true),
                    s.getBoolean("export-inventory", defaultExportInventory()),
                    s.getBoolean("import-inventory", defaultImportInventory()),
                    s.getBoolean("export-score", true),
                    s.getBoolean("import-score", true)
            ));
        }
        return map;
    }

    public PeerPolicy defaults() {
        return new PeerPolicy(
                true,
                defaultExportInventory(),
                defaultImportInventory(),
                true,
                true
        );
    }

    // --- local ColorPortals-style portals ---

    public boolean localPortalsEnabled() {
        return plugin.getConfig().getBoolean("local-portals.enabled", true);
    }

    public boolean localWalkOnActivation() {
        return plugin.getConfig().getBoolean("local-portals.walk-on-activation", true);
    }

    public boolean localAllowMobs() {
        return plugin.getConfig().getBoolean("local-portals.allow-mobs", true);
    }

    public boolean localAllowItems() {
        return plugin.getConfig().getBoolean("local-portals.allow-items", true);
    }

    public boolean localAllowMinecarts() {
        return plugin.getConfig().getBoolean("local-portals.allow-minecarts", true);
    }

    public boolean localPortalProtection() {
        return plugin.getConfig().getBoolean("local-portals.portal-protection", true);
    }

    public boolean localUsePermissions() {
        return plugin.getConfig().getBoolean("local-portals.use-permissions", false);
    }

    /** 0 = unlimited. */
    public int localMaxPortalsPerGroup() {
        return Math.max(0, plugin.getConfig().getInt("local-portals.max-portals-per-group", 0));
    }

    public int localMinDistance() {
        return Math.max(0, plugin.getConfig().getInt("local-portals.min-distance", 0));
    }

    public int localMaxDistance() {
        return Math.max(0, plugin.getConfig().getInt("local-portals.max-distance", 0));
    }

    // --- MULTI bind-on-create ---

    public int bindSearchSeconds() {
        return Math.max(10, plugin.getConfig().getInt("scanner.bind-search-seconds", 90));
    }

    public int bindRetrySeconds() {
        return Math.max(15, plugin.getConfig().getInt("scanner.bind-retry-seconds", 60));
    }

    public boolean bindPreferGeyser() {
        return plugin.getConfig().getBoolean("scanner.bind-prefer-geyser", true);
    }

    public boolean bindRequireGeyser() {
        return plugin.getConfig().getBoolean("scanner.bind-require-geyser", true);
    }

    /**
     * When a Random portal binds, avoid picking a server that another portal within this radius
     * (blocks, same world) already points to. 0 disables. Falls back to a duplicate only if no
     * other target exists.
     */
    public double avoidDuplicateRadius() {
        return Math.max(0, plugin.getConfig().getDouble("scanner.avoid-duplicate-radius", 100.0));
    }

    public boolean scannerHubPoolEnabled() {
        return plugin.getConfig().getBoolean("scanner.hub-pool.enabled", true);
    }

    public boolean scannerHubPullBeforeBind() {
        return plugin.getConfig().getBoolean("scanner.hub-pool.pull-before-bind", true);
    }

    public int scannerHubPullIntervalSeconds() {
        return Math.max(30, plugin.getConfig().getInt("scanner.hub-pool.pull-interval-seconds", 120));
    }

    public boolean scannerHubReportFinds() {
        return plugin.getConfig().getBoolean("scanner.hub-pool.report-finds", true);
    }

    public boolean scannerHubPreferOverPublic() {
        return plugin.getConfig().getBoolean("scanner.hub-pool.prefer-hub-over-public", true);
    }

    public int scannerHubMinCandidates() {
        return Math.max(1, plugin.getConfig().getInt("scanner.hub-pool.min-hub-candidates", 15));
    }
}
