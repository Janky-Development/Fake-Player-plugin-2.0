package me.bill.fakePlayerPlugin;

import java.io.File;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.impl.FppApiImpl;
import me.bill.fakePlayerPlugin.auth.BotAuthManager;
import me.bill.fakePlayerPlugin.command.AttackCommand;
import me.bill.fakePlayerPlugin.command.AuthCommand;
import me.bill.fakePlayerPlugin.command.CheckCommand;
import me.bill.fakePlayerPlugin.command.CommandManager;
import me.bill.fakePlayerPlugin.command.DeleteCommand;
import me.bill.fakePlayerPlugin.command.FindCommand;
import me.bill.fakePlayerPlugin.command.FreezeCommand;
import me.bill.fakePlayerPlugin.command.InfoCommand;
import me.bill.fakePlayerPlugin.command.InventoryCommand;
import me.bill.fakePlayerPlugin.command.LeftClickCommand;
import me.bill.fakePlayerPlugin.command.ListCommand;
import me.bill.fakePlayerPlugin.command.MoveCommand;
import me.bill.fakePlayerPlugin.command.PerfCommand;
import me.bill.fakePlayerPlugin.command.ReloadCommand;
import me.bill.fakePlayerPlugin.command.RenameCommand;
import me.bill.fakePlayerPlugin.command.RentCommand;
import me.bill.fakePlayerPlugin.command.RightClickCommand;
import me.bill.fakePlayerPlugin.command.SaveCommand;
import me.bill.fakePlayerPlugin.command.SetOwnerCommand;
import me.bill.fakePlayerPlugin.command.SettingCommand;
import me.bill.fakePlayerPlugin.command.SneakCommand;
import me.bill.fakePlayerPlugin.command.SpawnCommand;
import me.bill.fakePlayerPlugin.command.StopCommand;
import me.bill.fakePlayerPlugin.command.StorageCommand;
import me.bill.fakePlayerPlugin.command.StorageStore;
import me.bill.fakePlayerPlugin.command.TpCommand;
import me.bill.fakePlayerPlugin.command.TphCommand;
import me.bill.fakePlayerPlugin.command.XpCommand;
import me.bill.fakePlayerPlugin.config.BotNameConfig;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.DatabaseManager;
import me.bill.fakePlayerPlugin.economy.EconomyManager;
import me.bill.fakePlayerPlugin.fakeplayer.BotPersistence;
import me.bill.fakePlayerPlugin.fakeplayer.ChunkLoader;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.PveController;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotCache;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotEntry;
import me.bill.fakePlayerPlugin.fakeplayer.RentalService;
import me.bill.fakePlayerPlugin.fakeplayer.SkinManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinPoolService;
import me.bill.fakePlayerPlugin.gui.BotListGui;
import me.bill.fakePlayerPlugin.gui.BotSettingGui;
import me.bill.fakePlayerPlugin.gui.HelpGui;
import me.bill.fakePlayerPlugin.gui.SettingGui;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.listener.BotAdvancementBlocker;
import me.bill.fakePlayerPlugin.listener.BotCollisionListener;
import me.bill.fakePlayerPlugin.listener.BotLoginOverrideListener;
import me.bill.fakePlayerPlugin.listener.BotXpPickupListener;
import me.bill.fakePlayerPlugin.listener.FakePlayerEntityListener;
import me.bill.fakePlayerPlugin.listener.FakePlayerKickListener;
import me.bill.fakePlayerPlugin.listener.PlayerJoinListener;
import me.bill.fakePlayerPlugin.listener.PlayerWorldChangeListener;
import me.bill.fakePlayerPlugin.listener.ServerListPingListener;
import me.bill.fakePlayerPlugin.messaging.VelocityChannel;
import me.bill.fakePlayerPlugin.network.NetworkHeartbeatManager;
import me.bill.fakePlayerPlugin.perf.BuiltinFppProfiler;
import me.bill.fakePlayerPlugin.perf.FppProfiler;
import me.bill.fakePlayerPlugin.perf.PerformanceMonitor;
import me.bill.fakePlayerPlugin.perf.PerformanceReportExporter;
import me.bill.fakePlayerPlugin.sync.ConfigSyncManager;
import me.bill.fakePlayerPlugin.util.AttributionApiManager;
import me.bill.fakePlayerPlugin.util.AttributionManager;
import me.bill.fakePlayerPlugin.util.BackupManager;
import me.bill.fakePlayerPlugin.util.BadwordFilter;
import me.bill.fakePlayerPlugin.util.CompatibilityChecker;
import me.bill.fakePlayerPlugin.util.ConfigMigrator;
import me.bill.fakePlayerPlugin.util.ConfigValidator;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppMetrics;
import me.bill.fakePlayerPlugin.util.FppPlaceholderExpansion;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.HeartbeatSender;
import me.bill.fakePlayerPlugin.util.UpdateChecker;

import net.kyori.adventure.text.Component;

public final class FakePlayerPlugin extends JavaPlugin {

    private static FakePlayerPlugin instance;

    @SuppressWarnings("unused")
    public static FakePlayerPlugin getInstance() {
        return instance;
    }

    private CommandManager commandManager;
    private FakePlayerManager fakePlayerManager;
    private ChunkLoader chunkLoader;
    private DatabaseManager databaseManager;
    private BotAuthManager botAuthManager;
    private BotPersistence botPersistence;
    private FppMetrics fppMetrics;
    private VelocityChannel velocityChannel;
    private RemoteBotCache remoteBotCache;
    private ConfigSyncManager configSyncManager;
    private SettingGui settingGui;
    private BotSettingGui botSettingGui;
    private BotListGui botListGui;
    private HelpGui helpGui;
    private NetworkHeartbeatManager networkHeartbeat;
    private XpCommand xpCommand;
    private MoveCommand moveCommand;
    private AttackCommand attackCommand;
    private LeftClickCommand leftClickCommand;
    private RightClickCommand rightClickCommand;
    private FindCommand findCommand;
    private me.bill.fakePlayerPlugin.command.ClearCommand clearCommand;
    private StopCommand stopCommand;
    private PathfindingService pathfindingService;
    private StorageStore storageStore;
    private InventoryCommand inventoryCommand;
    private SkinManager skinManager;
    private SkinPoolService skinPoolService;
    private PveController pveController;
    private me.bill.fakePlayerPlugin.fakeplayer.AutoEatController autoEatController;
    private EconomyManager economyManager;
    private RentalService rentalService;
    private me.bill.fakePlayerPlugin.gui.RentGui rentGui;
    private HeartbeatSender heartbeatSender;
    private PerformanceMonitor performanceMonitor;

    private FppApiImpl fppApi;
    private BuiltinFppProfiler profiler;

    private Component updateNotificationMessage = null;

    private boolean worldEditAvailable = false;

    public boolean isWorldEditAvailable() {
        return worldEditAvailable;
    }

    public BuiltinFppProfiler getBuiltinProfiler() {
        return profiler;
    }

    private boolean versionUnsupported = false;

    private String detectedMcVersion = "unknown";

    public boolean isVersionUnsupported() {
        return versionUnsupported;
    }

    public String getDetectedMcVersion() {
        return detectedMcVersion;
    }

    private long enabledAt;

    public long getEnabledAt() {
        return enabledAt;
    }

    @Override
    public void onEnable() {
        instance = this;
        enabledAt = System.currentTimeMillis();
        FppLogger.init(getLogger());
        // Hot-reload safety (PlugManX etc. swap the jar before disabling us): define every class now
        // so onDisable never needs to read from a jar that has since been replaced.
        me.bill.fakePlayerPlugin.util.ClassPreloader.preload(getFile(), getClassLoader());
        me.bill.fakePlayerPlugin.util.BotLoginLogFilter.install(this);

        // ── Folia Detection ─────────────────────────────────────────────────────
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {
        }

        ConfigMigrator.migrateIfNeeded(this);

        Config.init(this);
        Config.debugStartup("config.yml loaded.");

        profiler = new BuiltinFppProfiler(Config.performanceSelfProfilerMethodLevel());
        if (Config.performanceSelfProfilerEnabled()) profiler.start();

        AttributionManager.validate(this);
        AttributionApiManager.init(this);

        BadwordFilter.reload(this);
        if (Config.isBadwordFilterEnabled() && BadwordFilter.getBadwordCount() == 0) {
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
            FppLogger.warn("  ⚠  BADWORD FILTER IS ENABLED BUT NO SOURCES ARE ACTIVE  ⚠");
            FppLogger.warn("  Enable 'badword-filter.use-global-list' or add words to");
            FppLogger.warn("  'badword-filter.words' / 'bad-words.yml', then run /fpp reload");
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
        }

        Lang.init(this);
        Config.debugStartup("Language file loaded (lang=" + Config.getLanguage() + ").");

        detectedMcVersion = CompatibilityChecker.extractMcVersion();
        if (!CompatibilityChecker.isSupportedVersion(detectedMcVersion)) {
            versionUnsupported = true;
            String pv = getPluginMeta().getVersion();
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
            FppLogger.warn("  ⚠  FakePlayerPlugin - UNSUPPORTED MINECRAFT VERSION  ⚠");
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
            FppLogger.warn("  Plugin    : FakePlayerPlugin v" + pv);
            FppLogger.warn("  Server MC : " + detectedMcVersion + "  (NOT supported)");
            FppLogger.warn("  Supported : up to MC 1.21.11, and 26.1.x–26.3.x");
            FppLogger.warn("  Action    : All /fpp commands have been DISABLED.");
            FppLogger.warn("  Support   : If you think this is a bug, contact us:");
            FppLogger.warn("              Discord → https://discord.gg/Q9cd9frzRt");
            FppLogger.warn("═══════════════════════════════════════════════════════════════════");
        }

        BotNameConfig.init(this);
        Config.debugStartup("Bot name pool: " + BotNameConfig.getNames().size() + " names.");

        ensureDataDirectories();

        boolean dbOk = false;
        if (Config.databaseEnabled()) {
            databaseManager = new DatabaseManager();
            dbOk = databaseManager.init(this);
            if (!dbOk) {
                FppLogger.warn("Database could not be initialised - session tracking disabled.");
                databaseManager = null;
            } else {

                String mode = Config.databaseMode();
                String serverId = Config.serverId();
                Config.debugDatabase("Database mode: " + mode + " | server-id=" + serverId);
            }
        } else {
            Config.debugDatabase("Database disabled in config - skipping database initialisation.");
            databaseManager = null;
        }

        skinPoolService = new SkinPoolService(this);
        skinManager = new SkinManager(this);

        remoteBotCache = new RemoteBotCache();
        if (Config.isNetworkMode() && databaseManager != null) {
            var remoteRows = databaseManager.getNetworkBotsFromOtherServers();
            for (var row : remoteRows) {
                try {
                    UUID uuid = UUID.fromString(row.botUuid());
                    String display =
                            (row.botDisplay() != null && !row.botDisplay().isBlank())
                                    ? row.botDisplay()
                                    : row.botName();
                    remoteBotCache.add(new RemoteBotEntry(
                            row.serverId(), uuid, row.botName(), display, row.botName(), "", "", -1));
                } catch (Exception ignored) {
                }
            }
            Config.debugNetwork("Remote bot cache pre-populated from DB: " + remoteBotCache.count() + " bot(s).");
        }

        if (Config.isNetworkMode() && databaseManager != null) {
            configSyncManager = new ConfigSyncManager(this, databaseManager);
            configSyncManager.init();
            Config.debugConfigSync("Config sync manager initialized (mode=" + Config.configSyncMode() + ").");
        } else {
            configSyncManager = null;
        }

        fakePlayerManager = new FakePlayerManager(this);
        if (databaseManager != null) fakePlayerManager.setDatabaseManager(databaseManager);

        botAuthManager = new BotAuthManager(this, databaseManager);
        fakePlayerManager.setBotAuthManager(botAuthManager);

        fakePlayerManager.refreshCleanNamePool();

        fppApi = new FppApiImpl(this, fakePlayerManager);

        // Load persisted despawn snapshots (DB primary, YAML fallback) so bots that were manually
        // despawned before the restart can have their inventory/XP restored on next spawn.
        fakePlayerManager.initDespawnSnapshots();

        chunkLoader = new ChunkLoader(this, fakePlayerManager);
        fakePlayerManager.setChunkLoader(chunkLoader);

        botPersistence = new BotPersistence(this);
        fakePlayerManager.setBotPersistence(botPersistence);

        networkHeartbeat = new NetworkHeartbeatManager(this, fakePlayerManager);
        if (Config.isNetworkMode() && databaseManager != null) {
            networkHeartbeat.start();
        }

        pathfindingService = new PathfindingService(this, fakePlayerManager);
        pathfindingService.setController(
                new me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavController(this, fakePlayerManager));

        pveController = new PveController(this, fakePlayerManager, pathfindingService);
        pveController.start();

        autoEatController = new me.bill.fakePlayerPlugin.fakeplayer.AutoEatController(fakePlayerManager);

        economyManager = new EconomyManager();
        rentalService = new RentalService(this, fakePlayerManager);
        rentalService.start();

        commandManager = new CommandManager(this);
        rentGui = new me.bill.fakePlayerPlugin.gui.RentGui(this, fakePlayerManager);
        getServer().getPluginManager().registerEvents(rentGui, this);
        commandManager.register(new RentCommand(this, fakePlayerManager));
        commandManager.register(new SpawnCommand(fakePlayerManager, commandManager));
        commandManager.register(new DeleteCommand(fakePlayerManager));
        commandManager.register(new ListCommand(this, fakePlayerManager));
        commandManager.register(new TphCommand(fakePlayerManager));
        commandManager.register(new TpCommand(fakePlayerManager));
        xpCommand = new XpCommand(this, fakePlayerManager);
        commandManager.register(xpCommand);
        commandManager.register(new ReloadCommand(this));
        commandManager.register(new InfoCommand(databaseManager, fakePlayerManager));
        commandManager.register(new CheckCommand(this, fakePlayerManager));
        commandManager.register(new AuthCommand(fakePlayerManager, botAuthManager));
        commandManager.register(new FreezeCommand(fakePlayerManager));
        commandManager.register(new SneakCommand(fakePlayerManager));
        commandManager.register(new RenameCommand(fakePlayerManager));
        commandManager.register(new PerfCommand(this, fakePlayerManager));
        moveCommand = new MoveCommand(fakePlayerManager, pathfindingService);
        storageStore = new StorageStore(this);
        storageStore.load();
        commandManager.register(moveCommand);
        findCommand = new FindCommand(this, fakePlayerManager, pathfindingService, storageStore);
        commandManager.register(findCommand);
        clearCommand = new me.bill.fakePlayerPlugin.command.ClearCommand(
                this, fakePlayerManager, pathfindingService, storageStore);
        commandManager.register(clearCommand);
        commandManager.register(new StorageCommand(this, fakePlayerManager, storageStore, pathfindingService));
        attackCommand = new AttackCommand(this, fakePlayerManager);
        commandManager.register(attackCommand);
        leftClickCommand = new LeftClickCommand(this, fakePlayerManager, pathfindingService);
        commandManager.register(leftClickCommand);
        rightClickCommand = new RightClickCommand(this, fakePlayerManager, pathfindingService);
        commandManager.register(rightClickCommand);
        botSettingGui = new BotSettingGui(this, fakePlayerManager);
        inventoryCommand = new InventoryCommand(fakePlayerManager, this, botSettingGui);
        commandManager.register(inventoryCommand);
        commandManager.register(new SetOwnerCommand(this, fakePlayerManager));
        commandManager.register(new SaveCommand(this));

        settingGui = new SettingGui(this);
        commandManager.register(new SettingCommand(settingGui, botSettingGui, fakePlayerManager));
        Config.debugStartup(
                "Commands registered: " + commandManager.getCommands().size() + " total.");

        stopCommand = new StopCommand(fakePlayerManager);
        stopCommand.setMoveCommand(moveCommand);
        stopCommand.setLeftClickCommand(leftClickCommand);
        stopCommand.setRightClickCommand(rightClickCommand);
        stopCommand.setAttackCommand(attackCommand);
        stopCommand.setFindCommand(findCommand);
        stopCommand.setClearCommand(clearCommand);
        commandManager.register(stopCommand);

        var fppCmd = getCommand("fpp");
        if (fppCmd != null) {
            fppCmd.setExecutor(commandManager);
            fppCmd.setTabCompleter(commandManager);
        }

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, fakePlayerManager), this);
        getServer().getPluginManager().registerEvents(new PlayerWorldChangeListener(this, fakePlayerManager), this);
        getServer()
                .getPluginManager()
                .registerEvents(new FakePlayerEntityListener(this, fakePlayerManager, chunkLoader), this);
        getServer().getPluginManager().registerEvents(new BotCollisionListener(this, fakePlayerManager), this);

        getServer().getPluginManager().registerEvents(new FakePlayerKickListener(fakePlayerManager), this);
        getServer().getPluginManager().registerEvents(new ServerListPingListener(fakePlayerManager), this);
        getServer().getPluginManager().registerEvents(new BotAdvancementBlocker(fakePlayerManager), this);

        me.bill.fakePlayerPlugin.gui.GuiKit.registerChatCapture(this);
        getServer().getPluginManager().registerEvents(settingGui, this);
        getServer().getPluginManager().registerEvents(botSettingGui, this);
        getServer().getPluginManager().registerEvents(inventoryCommand, this);
        getServer().getPluginManager().registerEvents(new BotLoginOverrideListener(this, fakePlayerManager), this);
        getServer().getPluginManager().registerEvents(new BotXpPickupListener(this, fakePlayerManager), this);
        getServer()
                .getPluginManager()
                .registerEvents(new me.bill.fakePlayerPlugin.fakeplayer.BotAutoMount(fakePlayerManager), this);

        helpGui = new HelpGui(this, commandManager);
        getServer().getPluginManager().registerEvents(helpGui, this);
        commandManager.setHelpGui(helpGui);

        botListGui = new BotListGui(this, fakePlayerManager);
        getServer().getPluginManager().registerEvents(botListGui, this);

        me.bill.fakePlayerPlugin.gui.MainMenuGui mainMenuGui =
                new me.bill.fakePlayerPlugin.gui.MainMenuGui(this, fakePlayerManager, botListGui, settingGui, helpGui);
        getServer().getPluginManager().registerEvents(mainMenuGui, this);
        commandManager.register(new me.bill.fakePlayerPlugin.command.MenuCommand(mainMenuGui));

        velocityChannel = new VelocityChannel(this, fakePlayerManager);
        getServer().getMessenger().registerOutgoingPluginChannel(this, VelocityChannel.CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, VelocityChannel.PROXY_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, VelocityChannel.CHANNEL, velocityChannel);
        getServer().getMessenger().registerIncomingPluginChannel(this, VelocityChannel.PROXY_CHANNEL, velocityChannel);
        Config.debugNetwork("Plugin messaging channels registered: " + VelocityChannel.CHANNEL + " + "
                + VelocityChannel.PROXY_CHANNEL + " + BungeeCord.");

        FppScheduler.runSyncRepeating(
                this,
                () -> {
                    if (fakePlayerManager.getCount() > 0) {
                        fakePlayerManager.validateEntities();
                    }
                },
                6000L,
                6000L);

        int configIssues = ConfigValidator.validate();
        if (configIssues > 0) {
            FppLogger.warn("Config validation found " + configIssues + " issue(s) - see above.");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new FppPlaceholderExpansion(this, fakePlayerManager).register();
                Config.debugStartup("PlaceholderAPI detected - placeholders registered.");
            } catch (Exception e) {
                FppLogger.warn("PlaceholderAPI: failed to register expansion - " + e.getMessage());
            }
        }

        worldEditAvailable = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        if (worldEditAvailable) {
            Config.debugStartup("WorldEdit detected - --wesel flag enabled for /fpp mine and /fpp place.");
        }

        UpdateChecker.check(this);

        heartbeatSender = new HeartbeatSender(this, fakePlayerManager);
        heartbeatSender.start();

        if (Config.metricsEnabled()) {
            try {
                fppMetrics = new FppMetrics();
                fppMetrics.init(this);
            } catch (Throwable t) {
                fppMetrics = null;
                FppLogger.warn("Metrics disabled because FastStats is unavailable: " + t.getMessage());
            }
        } else {
            Config.debugStartup("Metrics disabled in config.yml - skipping FastStats init.");
        }

        performanceMonitor = new PerformanceMonitor(this, fakePlayerManager);
        performanceMonitor.start();

        if (Config.performanceSelfProfilerExportOnWarning()) {
            performanceMonitor.setReportExporter(new PerformanceReportExporter(this, performanceMonitor, profiler));
        }

        String dbLabel = databaseManager == null ? "none" : Config.mysqlEnabled() ? "MySQL" : "SQLite (local)";
        String dbState = !Config.databaseEnabled() ? "disabled" : (dbOk ? dbLabel : dbLabel + " (failed)");
        int dbSchemaVersion = databaseManager != null ? DatabaseManager.getCurrentSchemaVersion() : 0;

        boolean effectiveChunkLoading = Config.chunkLoadingEnabled() && Config.chunkLoadingRadius() != 0;
        boolean effectiveTaskPersist = Config.persistOnRestart() && databaseManager != null;

        long startupMs = System.currentTimeMillis() - enabledAt;
        int cfgVer = Config.configVersion();
        String configVersion = "v" + cfgVer + (cfgVer >= ConfigMigrator.CURRENT_VERSION ? " ✔" : " (migrated)");
        int backupCount = BackupManager.listBackups(this).size();

        FppLogger.printStartupBanner(
                getPluginMeta().getVersion(),
                String.join(", ", getPluginMeta().getAuthors()),
                BotNameConfig.getNames().size(),
                dbState,
                dbSchemaVersion,
                Config.persistOnRestart(),
                effectiveTaskPersist,
                false, // LuckPerms integration removed
                effectiveChunkLoading,
                Config.maxBots(),
                fppMetrics != null && fppMetrics.isActive(),
                configVersion,
                backupCount,
                startupMs);

        botPersistence.purgeOrphanedBodiesAndRestore(fakePlayerManager);

        // Viewers that were online before this (re)load never went through the join hook.
        fakePlayerManager.enforceTabPolicy();
        FppScheduler.runSyncLater(this, () -> fakePlayerManager.enforceTabPolicy(), 40L);

        if (velocityChannel != null) {
            FppScheduler.runSyncLater(this, () -> velocityChannel.broadcastResyncRequest(), 10L);
        }

        Config.debugStartup("onEnable complete.");
    }

    @Override
    public void onDisable() {
        Config.debugStartup("onDisable called.");
        shutdownStep("log filter", me.bill.fakePlayerPlugin.util.BotLoginLogFilter::uninstall);

        int botsRemoved = fakePlayerManager != null ? fakePlayerManager.getCount() : 0;

        shutdownStep("pve", () -> {
            if (pveController != null) pveController.shutdown();
        });
        shutdownStep("auto-eat", () -> {
            if (autoEatController != null) autoEatController.shutdown();
        });
        shutdownStep("rental", () -> {
            if (rentalService != null) rentalService.shutdown();
        });
        shutdownStep("pathfinding", () -> {
            if (pathfindingService != null) pathfindingService.cancelAll();
        });
        shutdownStep("chunk loader", () -> {
            if (chunkLoader != null) chunkLoader.releaseAll();
        });

        shutdownStep("persistence", () -> {
            if (botPersistence != null && fakePlayerManager != null && Config.persistOnRestart()) {
                Config.debugStartup("Saving " + fakePlayerManager.getCount() + " bot(s) for persistence...");
                botPersistence.saveForShutdown(fakePlayerManager.getActivePlayers());
            }
        });

        shutdownStep("network offline", () -> {
            if (velocityChannel != null) velocityChannel.broadcastServerOffline();
        });

        // Always runs, even if saving failed above - otherwise the bots would be left online as
        // ghost players that outlive this plugin instance (the cause of orphans after a reload).
        shutdownStep("remove bots", () -> {
            if (fakePlayerManager != null) fakePlayerManager.removeAllSyncFast();
        });

        shutdownStep("database", () -> {
            if (databaseManager != null) {
                databaseManager.recordAllShutdown();
                databaseManager.close();
            }
        });

        shutdownStep("heartbeat", () -> {
            if (heartbeatSender != null) heartbeatSender.stop();
            if (networkHeartbeat != null) networkHeartbeat.stop();
        });
        shutdownStep("metrics/perf", () -> {
            if (fppMetrics != null) fppMetrics.shutdown();
            if (performanceMonitor != null) performanceMonitor.stop();
            if (profiler != null) profiler.stop();
        });

        shutdownStep("plugin channels", () -> {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, VelocityChannel.CHANNEL);
            getServer().getMessenger().unregisterIncomingPluginChannel(this, VelocityChannel.PROXY_CHANNEL);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, VelocityChannel.CHANNEL);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, VelocityChannel.PROXY_CHANNEL);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
        });

        long uptimeMs = System.currentTimeMillis() - enabledAt;
        final int removed = botsRemoved;
        shutdownStep("banner", () -> FppLogger.printShutdownBanner(removed, uptimeMs));
    }

    /** Runs one shutdown stage; a failure is logged and never prevents the stages after it. */
    private void shutdownStep(String stage, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            getLogger().warning("Shutdown step '" + stage + "' failed: " + t);
        }
    }

    /** The jar this plugin was loaded from (used to stage a same-named file in the update folder). */
    public File getPluginJar() {
        return getFile();
    }

    @SuppressWarnings("unused")
    public FppProfiler getProfiler() {
        return profiler;
    }

    @SuppressWarnings("unused")
    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    @SuppressWarnings("unused")
    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * Returns the public API entry point for other plugins. Available after {@code onEnable} completes.
     */
    @SuppressWarnings("unused")
    public FppApi getFppApi() {
        return fppApi;
    }

    /**
     * Internal accessor for subsystems that need the concrete impl (e.g. fireTickHandlers).
     */
    public FppApiImpl getFppApiImpl() {
        return fppApi;
    }

    @SuppressWarnings("unused")
    public FakePlayerManager getFakePlayerManager() {
        return fakePlayerManager;
    }

    public BotPersistence getBotPersistence() {
        return botPersistence;
    }



    public BotSettingGui getBotSettingGui() {
        return botSettingGui;
    }

    public BotListGui getBotListGui() {
        return botListGui;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }



    public VelocityChannel getVelocityChannel() {
        return velocityChannel;
    }

    public RemoteBotCache getRemoteBotCache() {
        return remoteBotCache;
    }

    public ConfigSyncManager getConfigSyncManager() {
        return configSyncManager;
    }

    public PveController getPveController() {
        return pveController;
    }

    public me.bill.fakePlayerPlugin.fakeplayer.AutoEatController getAutoEatController() {
        return autoEatController;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public me.bill.fakePlayerPlugin.gui.RentGui getRentGui() {
        return rentGui;
    }

    public RentalService getRentalService() {
        return rentalService;
    }

    public XpCommand getXpCommand() {
        return xpCommand;
    }

    public MoveCommand getMoveCommand() {
        return moveCommand;
    }

    public LeftClickCommand getLeftClickCommand() {
        return leftClickCommand;
    }

    public RightClickCommand getRightClickCommand() {
        return rightClickCommand;
    }

    public AttackCommand getAttackCommand() {
        return attackCommand;
    }

    public FindCommand getFindCommand() {
        return findCommand;
    }
    public me.bill.fakePlayerPlugin.command.ClearCommand getClearCommand() {
        return clearCommand;
    }


    public PathfindingService getPathfindingService() {
        return pathfindingService;
    }





    public SkinManager getSkinManager() {
        return skinManager;
    }

    public SkinPoolService getSkinPoolService() {
        return skinPoolService;
    }





    public Component getUpdateNotification() {
        return updateNotificationMessage;
    }

    public void setUpdateNotification(Component c) {
        this.updateNotificationMessage = c;
    }

    private volatile String latestKnownVersion = null;

    private volatile boolean runningBeta = false;

    public String getLatestKnownVersion() {
        return latestKnownVersion;
    }

    public void setLatestKnownVersion(String v) {
        this.latestKnownVersion = v;
    }

    public boolean isRunningBeta() {
        return runningBeta;
    }

    public void setRunningBeta(boolean b) {
        this.runningBeta = b;
    }

    private void ensureDataDirectories() {
        File root = getDataFolder();
        String[] dirs = {"data", "language"};
        for (String dir : dirs) {
            File d = new File(root, dir);
            if (!d.exists()) {
                boolean ok = d.mkdirs();
                Config.debugStartup("Created directory: " + d.getPath() + (ok ? " ✔" : " (already exists or failed)"));
            }
        }
    }
}
