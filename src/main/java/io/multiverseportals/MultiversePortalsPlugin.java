package io.multiverseportals;

import io.multiverseportals.compat.VersionCompat;
import io.multiverseportals.command.MvpCommand;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.federation.CatalogShareService;
import io.multiverseportals.federation.FederationServer;
import io.multiverseportals.federation.PeerClient;
import io.multiverseportals.listener.ChatReadyListener;
import io.multiverseportals.listener.PlayerListener;
import io.multiverseportals.listener.PortalListener;
import io.multiverseportals.listener.SignListener;
import io.multiverseportals.local.LocalPortalListener;
import io.multiverseportals.local.LocalPortalService;
import io.multiverseportals.portal.PortalBindService;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.portal.PortalMatter;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.scanner.MineScanClient;
import io.multiverseportals.scanner.ScannerHub;
import io.multiverseportals.score.ScoreService;
import io.multiverseportals.travel.ConsentService;
import io.multiverseportals.travel.TravelService;
import io.multiverseportals.update.UpdateChecker;
import io.multiverseportals.util.PublicEndpoint;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;

public final class MultiversePortalsPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Database database;
    private RegistryDatabase registry;
    private PeerClient peerClient;
    private FederationServer federationServer;
    private CatalogShareService catalogShareService;
    private PortalService portalService;
    private PortalBindService portalBindService;
    private PortalEffects portalEffects;
    private PortalMatter portalMatter;
    private VersionCompat versionCompat;
    private TravelService travelService;
    private ScoreService scoreService;
    private ConsentService consentService;
    private ScannerHub scannerHub;
    private io.multiverseportals.scanner.ScannerPoolService scannerPool;
    private io.multiverseportals.travel.IngressPolicy ingressPolicy;
    private LocalPortalService localPortalService;
    private LocalPortalListener localPortalListener;
    private UpdateChecker updateChecker;
    /** True when server.properties was patched / already true but JVM needs restart. */
    private volatile boolean acceptTransfersRestartNeeded;
    /** Lazy: do not init in constructor — Spigot lacks MiniMessage and would fail before Paper check. */
    private MiniMessage mm;

    @Override
    public void onEnable() {
        if (!isPaperServer()) {
            String brand = Bukkit.getName() + " / " + Bukkit.getVersion();
            getLogger().severe("======================================================");
            getLogger().severe(" MultiversePortals requires Paper 1.21+");
            getLogger().severe(" Download: https://papermc.io/downloads/paper");
            getLogger().severe(" Current server: " + brand);
            getLogger().severe(" Install Paper, put this jar in plugins/, then restart.");
            getLogger().severe(" Spigot / CraftBukkit / Vanilla are not supported.");
            getLogger().severe("======================================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.mm = MiniMessage.miniMessage();
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        var messages = new io.multiverseportals.i18n.Messages(this);
        messages.load(pluginConfig.language(), pluginConfig.languagePerPlayer());
        this.pluginConfig.setMessages(messages);

        String guessedHost = guessPublicHost();
        if (pluginConfig.ensureBootstrapDefaults(guessedHost)) {
            getLogger().info("First-run defaults written (server.id / secret). public-host/port resolve from server.properties when empty.");
        }

        var patch = PublicEndpoint.ensureAcceptTransfers(
                getDataFolder().toPath(),
                pluginConfig.autoEnableAcceptTransfers(),
                getLogger());
        acceptTransfersRestartNeeded = patch == PublicEndpoint.AcceptTransfersPatch.WROTE_RESTART
                || patch == PublicEndpoint.AcceptTransfersPatch.RESTART_PENDING;
        if (patch == PublicEndpoint.AcceptTransfersPatch.WROTE_RESTART) {
            getLogger().warning("Включил приём гостей (accepts-transfers=true) — перезапусти сервер один раз.");
        } else if (patch == PublicEndpoint.AcceptTransfersPatch.RESTART_PENDING) {
            getLogger().warning("accepts-transfers=true уже в server.properties — перезапусти сервер один раз, чтобы гости могли приходить.");
        }

        this.database = new Database(this, pluginConfig);
        this.database.init();

        this.registry = new RegistryDatabase(this, pluginConfig);
        try {
            this.registry.init();
        } catch (Exception e) {
            getLogger().severe("Central MySQL registry unavailable: " + e.getMessage());
            getLogger().severe("Hub only: fix registry.jdbc-url — normal servers keep registry.enabled: false (HTTPS catalog).");
        }

        this.consentService = new ConsentService(database);
        this.scannerHub = new ScannerHub(this, pluginConfig, database);
        this.ingressPolicy = new io.multiverseportals.travel.IngressPolicy(pluginConfig, database);

        this.peerClient = new PeerClient(pluginConfig);
        this.scannerPool = new io.multiverseportals.scanner.ScannerPoolService(
                this, pluginConfig, database, registry, peerClient);
        this.scannerHub.setScannerPool(scannerPool);
        this.portalService = new PortalService(this, database, registry, pluginConfig);
        this.portalBindService = new PortalBindService(this, database, pluginConfig);
        this.portalEffects = new PortalEffects(this, pluginConfig);
        // Remove leftover charge-icon ItemDisplays from older builds (dark square bug).
        getServer().getScheduler().runTask(this, () -> {
            for (var world : getServer().getWorlds()) {
                for (var e : world.getEntities()) {
                    if (e.getScoreboardTags().contains("mvp_charge_icon")) {
                        e.remove();
                    }
                }
            }
        });
        this.portalMatter = new PortalMatter(this, pluginConfig);
        this.versionCompat = null;
        this.travelService = new TravelService(this, database, pluginConfig, peerClient, portalService, consentService, ingressPolicy);
        this.scoreService = new ScoreService(this, database, pluginConfig);
        this.localPortalService = new LocalPortalService(this, database, pluginConfig);
        this.localPortalService.loadCache();
        this.localPortalListener = new LocalPortalListener(this, localPortalService, pluginConfig);
        this.catalogShareService = new CatalogShareService(this, pluginConfig, database, registry, peerClient);
        this.updateChecker = new UpdateChecker(this, pluginConfig);

        if (pluginConfig.federationShouldListen()) {
            this.federationServer = new FederationServer(
                    this, pluginConfig, database, travelService, portalService, catalogShareService);
            try {
                this.federationServer.start();
            } catch (Exception e) {
                getLogger().warning("Federation HTTP disabled: " + e.getMessage());
            }
        }

        getServer().getPluginManager().registerEvents(localPortalListener, this);
        getServer().getPluginManager().registerEvents(new PortalListener(this, portalService, travelService, portalEffects, localPortalListener), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, travelService, portalEffects), this);
        getServer().getPluginManager().registerEvents(new SignListener(this, pluginConfig, portalService, registry), this);
        getServer().getPluginManager().registerEvents(new ChatReadyListener(this, pluginConfig, consentService), this);

        var cmd = getCommand("mvp");
        if (cmd != null) {
            var executor = new MvpCommand(this, pluginConfig, database, registry, portalService, peerClient);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        scoreService.startScheduler();
        portalService.startHeartbeat();
        scannerHub.start();
        scannerPool.start();
        if (pluginConfig.discoverPublicIp()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> pluginConfig.tryDiscoverPublicIp());
        }
        if (registry.enabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                long hist = registry.countArrivalsToThisServer();
                if (hist > 0) {
                    database.ensureTotalArrivalsAtLeast(hist);
                    getLogger().info("Portal arrivals seeded from registry history: " + hist);
                }
            });
        }
        catalogShareService.start();
        updateChecker.start();

        // Immediate central-DB / hub presence — map should see us online within ~1s of enable
        if (registry.enabled() && pluginConfig.shouldListPublicly()) {
            String motd0 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(Bukkit.motd());
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    registry.announceSelf(
                            Bukkit.getOnlinePlayers().size(),
                            Bukkit.getMaxPlayers(),
                            motd0,
                            Bukkit.getMinecraftVersion(),
                            0,
                            false, false, false, 0, 0,
                            io.multiverseportals.compat.ServerCaps.detect(pluginConfig)
                    );
                } catch (Exception e) {
                    getLogger().warning("Startup registry announce failed: " + e.getMessage());
                }
            });
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (var p : database.listPortals()) {
                io.multiverseportals.portal.PortalSigns.update(p);
            }
            portalMatter.refreshAll(database.listPortals());
        }, 60L);
        // Wait for MineScan/Cornbread first refresh (~few seconds) before MULTI rebinds
        Bukkit.getScheduler().runTaskLater(this, () -> portalBindService.resumePending(), 20L * 25L);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            this.versionCompat = new VersionCompat(getLogger());
            boolean listed = pluginConfig.shouldListPublicly();
            boolean accept = pluginConfig.acceptTransfersEnabled();
            getLogger().info("Multiverse Portals online as '" + pluginConfig.serverId()
                    + "' @ " + pluginConfig.publicHost() + ":" + pluginConfig.publicPort()
                    + " mc=" + versionCompat.mcVersion() + " proto=" + versionCompat.nativeProtocol()
                    + " accept-transfers=" + accept
                    + " catalog=" + (listed ? "public" : "local-only")
                    + " — signs=[Multi|mvp|portal]/[To]/[Pair] + local wool portals");
            if (!accept) {
                if (acceptTransfersRestartNeeded) {
                    getLogger().warning("Включил приём гостей — перезапусти сервер один раз "
                            + "(accepts-transfers=true уже в server.properties).");
                } else {
                    getLogger().warning("server.properties accept-transfers is false/missing — inbound Transfer joins are rejected. "
                            + "Set accepts-transfers=true to receive guests (and to list on the public catalog in auto mode).");
                }
            } else {
                acceptTransfersRestartNeeded = false;
            }
            if (!listed) {
                getLogger().info("Local-only mode: not announcing to the public catalog "
                        + "(private/LAN address, accept-transfers off, or server.list-publicly: never). "
                        + "Local wool portals and Pair/[To] by IP still work.");
            }
            if (registry.enabled() && listed) {
                String motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(Bukkit.motd());
                Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                        registry.announceSelf(
                                Bukkit.getOnlinePlayers().size(),
                                Bukkit.getMaxPlayers(),
                                motd,
                                versionCompat.mcVersion(),
                                versionCompat.nativeProtocol(),
                                versionCompat.viaVersion(),
                                versionCompat.viaBackwards(),
                                versionCompat.viaRewind(),
                                versionCompat.viaMin(),
                                versionCompat.viaMax(),
                                io.multiverseportals.compat.ServerCaps.detect(pluginConfig)
                        ));
            }
            if (catalogShareService != null && listed) {
                catalogShareService.pushNowAsync();
            }
        }, 40L);
    }

    private String guessPublicHost() {
        try {
            String ip = Bukkit.getIp();
            if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0")) {
                return ip;
            }
        } catch (Exception ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    @Override
    public void onDisable() {
        // Tell the hub we're going offline before tearing down HTTP/DB.
        if (catalogShareService != null) {
            try {
                catalogShareService.notifyShutdownBlocking();
            } catch (Exception ignored) {
            }
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (scannerPool != null) {
            scannerPool.stop();
        }
        if (scannerHub != null) {
            scannerHub.stop();
        }
        if (catalogShareService != null) {
            catalogShareService.stop();
        }
        if (portalEffects != null) {
            portalEffects.cancelAll();
        }
        if (scoreService != null) {
            scoreService.stopScheduler();
        }
        if (portalService != null) {
            portalService.stopHeartbeat();
        }
        if (federationServer != null) {
            federationServer.stop();
        }
        if (registry != null) {
            try {
                registry.markSelfOffline();
            } catch (Exception ignored) {
            }
            registry.close();
        }
        if (database != null) {
            database.close();
        }
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public Database database() {
        return database;
    }

    public RegistryDatabase registry() {
        return registry;
    }

    public PortalService portalService() {
        return portalService;
    }

    public PortalBindService portalBindService() {
        return portalBindService;
    }

    public TravelService travelService() {
        return travelService;
    }

    public PeerClient peerClient() {
        return peerClient;
    }

    public CatalogShareService catalogShareService() {
        return catalogShareService;
    }

    public PortalEffects portalEffects() {
        return portalEffects;
    }

    public PortalMatter portalMatter() {
        return portalMatter;
    }

    public VersionCompat versionCompat() {
        return versionCompat;
    }

    public ConsentService consentService() {
        return consentService;
    }

    public ScannerHub scannerHub() {
        return scannerHub;
    }

    public io.multiverseportals.scanner.ScannerPoolService scannerPool() {
        return scannerPool;
    }

    /** @deprecated use {@link #scannerHub()} */
    @Deprecated
    public MineScanClient mineScanClient() {
        return scannerHub == null ? null : scannerHub.minescan();
    }

    public io.multiverseportals.travel.IngressPolicy ingressPolicy() {
        return ingressPolicy;
    }

    public LocalPortalService localPortalService() {
        return localPortalService;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public boolean acceptTransfersRestartNeeded() {
        return acceptTransfersRestartNeeded && !pluginConfig.acceptTransfersEnabled();
    }

    public void setAcceptTransfersRestartNeeded(boolean needed) {
        this.acceptTransfersRestartNeeded = needed;
    }

    /** Remind ops that one restart is required after auto-enabling transfers. */
    public void notifyAcceptTransfersRestartIfNeeded(Player player) {
        if (!acceptTransfersRestartNeeded() || player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("multiverseportals.admin") && !player.isOp()) {
            return;
        }
        player.sendMessage(mm.deserialize(pluginConfig.prefix() + pluginConfig.message("accept-transfers-restart")));
    }

    /**
     * Paper (and forks: Purpur, Pufferfish, Folia) expose the Transfer API we need.
     * Spigot / CraftBukkit / Vanilla jar → refuse to load with a clear install hint.
     * Do not treat {@code Player#transfer} alone as Paper — Spigot may expose it without Adventure/Paper APIs.
     */
    private static boolean isPaperServer() {
        String name = Bukkit.getName();
        if (name != null) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (n.contains("paper") || n.contains("purpur") || n.contains("folia")
                    || n.contains("pufferfish") || n.contains("leaves")) {
                return true;
            }
        }
        String ver = Bukkit.getVersion();
        if (ver != null) {
            String v = ver.toLowerCase(java.util.Locale.ROOT);
            if (v.contains("paper") || v.contains("purpur") || v.contains("folia")
                    || v.contains("pufferfish") || v.contains("leaves")) {
                return true;
            }
        }
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }
}
