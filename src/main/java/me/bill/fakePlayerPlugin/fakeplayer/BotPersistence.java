package me.bill.fakePlayerPlugin.fakeplayer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppBotSaveEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.command.SavedClickTask;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.DatabaseManager;
import me.bill.fakePlayerPlugin.util.BotDataYaml;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

public final class BotPersistence {

    private static final String FILE_NAME = "active-bots.yml";
    private static final String INV_FILE_NAME = "bot-inventories.yml";
    private static final String TASKS_FILE_NAME = "bot-tasks.yml";
    private static final String XP_FILE_NAME = "bot-xp.yml";
    private static final String ROOT_BOTS = "persistence.active-bots";
    private static final String ROOT_INVENTORIES = "persistence.inventories";
    private static final String ROOT_XP = "persistence.xp";
    private static final String ROOT_TASKS = "persistence.tasks";
    private static final String EMPTY_INVENTORY_MARKER = "__empty";

    private final File dataFile;
    private final File inventoryFile;
    private final File tasksFile;
    private final File xpFile;
    private final File unifiedFile;
    private final FakePlayerPlugin plugin;
    private final AtomicBoolean asyncSaveQueued = new AtomicBoolean(false);
    private final AtomicBoolean activeListSaveScheduled = new AtomicBoolean(false);
    private volatile boolean activeListSaveDirty = false;
    private volatile Iterable<FakePlayer> activeListSavePlayers = List.of();
    private volatile boolean activeListSavesDisabled = false;

    private Map<String, Map<String, String>> loadedInventories = null;

    private Map<String, XpEntry> loadedXp = null;

    private Map<String, TaskEntry> loadedTasks = null;

    public BotPersistence(FakePlayerPlugin plugin) {
        this.plugin = plugin;
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            FppLogger.warn("BotPersistence: could not create data directory: " + dataDir.getAbsolutePath());
        }
        this.dataFile = new File(dataDir, FILE_NAME);
        this.inventoryFile = new File(dataDir, INV_FILE_NAME);
        this.tasksFile = new File(dataDir, TASKS_FILE_NAME);
        this.xpFile = new File(dataDir, XP_FILE_NAME);
        this.unifiedFile = BotDataYaml.getFile(plugin);
    }

    public void save(Iterable<FakePlayer> players) {
        List<FakePlayer> snapshot = snapshotPlayers(players);
        fireSaveEvents(snapshot);
        saveInternal(snapshot);
        saveInventoriesInternal(snapshot);
        saveXpInternal(snapshot);
        saveTasksInternal(snapshot);
    }

    public void saveForShutdown(Iterable<FakePlayer> players) {
        List<FakePlayer> snapshot = snapshotPlayers(players);
        activeListSavesDisabled = true;
        activeListSaveDirty = false;
        activeListSavePlayers = snapshot;
        if (snapshot.isEmpty()) {
            FppLogger.warn("Shutdown persistence skipped empty active-bots snapshot to avoid clearing restart state.");
            return;
        }
        save(snapshot);
    }

    public void saveAsync(Iterable<FakePlayer> players) {
        saveActiveListAsync(players);
    }

    public void saveFullAsync(Iterable<FakePlayer> players) {
        if (!asyncSaveQueued.compareAndSet(false, true)) return;

        List<FakePlayer> snapshot = snapshotPlayers(players);
        fireSaveEvents(snapshot);
        List<Object> list = buildList(snapshot);
        Map<String, Map<String, String>> invSnap = snapshotInventories(snapshot);
        Map<String, XpEntry> xpSnap = snapshotXp(snapshot);
        Map<String, TaskEntry> taskSnap = snapshotTasks(snapshot);
        FppScheduler.runAsync(plugin, () -> {
            try {
                try {
                    BotDataYaml.replaceSection(plugin, ROOT_BOTS, section -> {
                        section.set("bots", list);
                    });
                } catch (IOException e) {
                    FppLogger.error("Failed to auto-save active bots: " + e.getMessage());
                }
                writeInventorySnapshot(invSnap);
                writeXpSnapshot(xpSnap);
                writeTaskSnapshot(taskSnap);
            } finally {
                asyncSaveQueued.set(false);
            }
        });
    }

    public void saveActiveListAsync(Iterable<FakePlayer> players) {
        if (activeListSavesDisabled) return;
        activeListSavePlayers = snapshotPlayers(players);
        activeListSaveDirty = true;
        if (activeListSaveScheduled.compareAndSet(false, true)) {
            scheduleActiveListSave();
        }
    }

    private List<FakePlayer> snapshotPlayers(Iterable<FakePlayer> players) {
        if (players == null) return List.of();
        List<FakePlayer> snapshot = new ArrayList<>();
        for (FakePlayer fp : players) {
            if (fp != null) snapshot.add(fp);
        }
        return snapshot;
    }

    private void scheduleActiveListSave() {
        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    if (activeListSavesDisabled) {
                        activeListSaveDirty = false;
                        activeListSaveScheduled.set(false);
                        return;
                    }
                    activeListSaveDirty = false;
                    List<Object> list = buildActiveListLight(activeListSavePlayers);
                    FppScheduler.runAsync(plugin, () -> {
                        try {
                            if (activeListSavesDisabled) return;
                            writeActiveBotList(list);
                        } finally {
                            if (activeListSaveDirty) {
                                scheduleActiveListSave();
                            } else {
                                activeListSaveScheduled.set(false);
                                if (activeListSaveDirty && activeListSaveScheduled.compareAndSet(false, true)) {
                                    scheduleActiveListSave();
                                }
                            }
                        }
                    });
                },
                20L);
    }

    private void writeActiveBotList(List<Object> list) {
        try {
            BotDataYaml.replaceSection(plugin, ROOT_BOTS, section -> {
                section.set("bots", list);
            });
        } catch (IOException e) {
            FppLogger.error("Failed to auto-save active bots: " + e.getMessage());
        }
    }

    private void saveInternal(Iterable<FakePlayer> players) {
        try {
            BotDataYaml.replaceSection(plugin, ROOT_BOTS, section -> {
                section.set("bots", buildList(players));
            });
            deleteFile(dataFile);
            FppLogger.info("Saved bot list to " + FILE_NAME + ".");
        } catch (IOException e) {
            FppLogger.error("Failed to save active bots: " + e.getMessage());
        }
    }

    private void saveInventoriesInternal(Iterable<FakePlayer> players) {
        writeInventorySnapshot(snapshotInventories(players));
    }

    private void saveXpInternal(Iterable<FakePlayer> players) {
        writeXpSnapshot(snapshotXp(players));
    }

    private void saveTasksInternal(Iterable<FakePlayer> players) {
        writeTaskSnapshot(snapshotTasks(players));
    }

    private void fireSaveEvents(Iterable<FakePlayer> players) {
        for (FakePlayer fp : players) {
            Bukkit.getPluginManager().callEvent(new FppBotSaveEvent(new FppBotImpl(fp)));
            persistSkinCheckpoint(fp);
        }
    }

    private void persistSkinCheckpoint(FakePlayer fp) {
        if (fp == null || plugin.getDatabaseManager() == null) return;
        SkinProfile skin = fp.getResolvedSkin();
        if (skin != null && skin.isValid()) {
            plugin.getDatabaseManager().updateBotSkin(fp.getUuid().toString(), skin.getValue(), skin.getSignature());
        }
    }

    private Map<String, TaskEntry> snapshotTasks(Iterable<FakePlayer> players) {
        Map<String, TaskEntry> snap = new LinkedHashMap<>();
        var leftCmd = plugin.getLeftClickCommand();
        var rightCmd = plugin.getRightClickCommand();
        for (FakePlayer fp : players) {
            String uuidStr = fp.getUuid().toString();
            String rcc = fp.getRightClickCommand();
            ClickTaskEntry leftClick = toClickTaskEntry(leftCmd != null ? leftCmd.getSavedTask(fp.getUuid()) : null);
            ClickTaskEntry rightClick = toClickTaskEntry(rightCmd != null ? rightCmd.getSavedTask(fp.getUuid()) : null);
            if (rcc != null || leftClick != null || rightClick != null) {
                snap.put(uuidStr, new TaskEntry(rcc, leftClick, rightClick));
            }
        }
        return snap;
    }

    @Nullable
    private static ClickTaskEntry toClickTaskEntry(@Nullable SavedClickTask task) {
        if (task == null || task.mode() == null || task.world() == null) return null;
        var point = task.aimPoint();
        return new ClickTaskEntry(
                task.mode(),
                task.world(),
                point != null,
                point != null ? point.getX() : 0,
                point != null ? point.getY() : 0,
                point != null ? point.getZ() : 0);
    }

    private void writeTaskSnapshot(Map<String, TaskEntry> snap) {
        try {
            BotDataYaml.replaceSection(plugin, ROOT_TASKS, section -> {
                for (Map.Entry<String, TaskEntry> e : snap.entrySet()) {
                    String sec = e.getKey() + ".";
                    TaskEntry t = e.getValue();
                    if (t.rightClickCommand() != null) section.set(sec + "right-click-command", t.rightClickCommand());
                    writeClickTask(section, sec + "left-click.", t.leftClick());
                    writeClickTask(section, sec + "right-click.", t.rightClick());
                }
            });
            deleteFile(tasksFile);
            Config.debug("Saved task state for " + snap.size() + " bot(s) to YAML.");
        } catch (IOException ex) {
            FppLogger.error("Failed to save bot tasks: " + ex.getMessage());
        }

        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null) {
            db.saveBotTasks(buildTaskRows(snap));
        }
    }

    private static void writeClickTask(ConfigurationSection section, String prefix, @Nullable ClickTaskEntry click) {
        if (click == null) return;
        section.set(prefix + "mode", click.mode());
        section.set(prefix + "world", click.world());
        section.set(prefix + "has-point", click.hasPoint());
        if (click.hasPoint()) {
            section.set(prefix + "x", click.x());
            section.set(prefix + "y", click.y());
            section.set(prefix + "z", click.z());
        }
    }

    private List<DatabaseManager.BotTaskRow> buildTaskRows(Map<String, TaskEntry> snap) {
        List<DatabaseManager.BotTaskRow> rows = new ArrayList<>();
        String serverId = Config.serverId();
        for (Map.Entry<String, TaskEntry> e : snap.entrySet()) {
            String uuid = e.getKey();
            TaskEntry t = e.getValue();
            addClickTaskRow(rows, uuid, serverId, "LEFT_CLICK", t.leftClick());
            addClickTaskRow(rows, uuid, serverId, "RIGHT_CLICK", t.rightClick());
        }
        return rows;
    }

    // Row mapping for click tasks: x/y/z = aim point, onceFlag = has-aim-point, extraStr = click mode.
    private static void addClickTaskRow(
            List<DatabaseManager.BotTaskRow> rows,
            String uuid,
            String serverId,
            String type,
            @Nullable ClickTaskEntry click) {
        if (click == null) return;
        rows.add(new DatabaseManager.BotTaskRow(
                uuid,
                serverId,
                type,
                click.world(),
                click.x(),
                click.y(),
                click.z(),
                0f,
                0f,
                click.hasPoint(),
                click.mode(),
                false));
    }

    private Map<String, Map<String, String>> snapshotInventories(Iterable<FakePlayer> players) {
        Map<String, Map<String, String>> snap = new LinkedHashMap<>();
        for (FakePlayer fp : players) {
            Player bot = fp.getPlayer();
            if (bot == null || !bot.isValid()) continue;
            Map<String, String> slots = serializeInventory(bot.getInventory());
            snap.put(fp.getUuid().toString(), slots);
        }
        return snap;
    }

    private Map<String, XpEntry> snapshotXp(Iterable<FakePlayer> players) {
        Map<String, XpEntry> snap = new LinkedHashMap<>();
        for (FakePlayer fp : players) {
            Player bot = fp.getPlayer();
            if (bot == null || !bot.isValid()) continue;
            snap.put(fp.getUuid().toString(), new XpEntry(bot.getTotalExperience(), bot.getLevel(), bot.getExp()));
        }
        return snap;
    }

    private void writeInventorySnapshot(Map<String, Map<String, String>> snap) {
        try {
            BotDataYaml.replaceSection(plugin, ROOT_INVENTORIES, section -> {
                for (Map.Entry<String, Map<String, String>> entry : snap.entrySet()) {
                    String uuidKey = entry.getKey();
                    if (entry.getValue().isEmpty()) {
                        section.set(uuidKey + "." + EMPTY_INVENTORY_MARKER, true);
                        continue;
                    }
                    for (Map.Entry<String, String> slot : entry.getValue().entrySet()) {
                        section.set(uuidKey + "." + slot.getKey(), slot.getValue());
                    }
                }
            });
            deleteFile(inventoryFile);
            Config.debug("Saved inventories for " + snap.size() + " bot(s).");
        } catch (IOException e) {
            FppLogger.error("Failed to save bot inventories: " + e.getMessage());
        }
    }

    private void writeXpSnapshot(Map<String, XpEntry> snap) {
        try {
            BotDataYaml.replaceSection(plugin, ROOT_XP, section -> {
                for (Map.Entry<String, XpEntry> entry : snap.entrySet()) {
                    String base = entry.getKey() + ".";
                    XpEntry xp = entry.getValue();
                    section.set(base + "total", xp.totalExperience());
                    section.set(base + "level", xp.level());
                    section.set(base + "progress", (double) xp.progress());
                }
            });
            deleteFile(xpFile);
            Config.debug("Saved XP for " + snap.size() + " bot(s).");
        } catch (IOException e) {
            FppLogger.error("Failed to save bot XP: " + e.getMessage());
        }
    }

    private static Map<String, String> serializeInventory(PlayerInventory inv) {
        Map<String, String> slots = new LinkedHashMap<>();

        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                try {
                    slots.put(String.valueOf(i), Base64.getEncoder().encodeToString(contents[i].serializeAsBytes()));
                } catch (Exception ignored) {
                }
            }
        }

        ItemStack[] armour = inv.getArmorContents();
        for (int i = 0; i < armour.length; i++) {
            if (armour[i] != null && armour[i].getType() != Material.AIR) {
                try {
                    slots.put(String.valueOf(36 + i), Base64.getEncoder().encodeToString(armour[i].serializeAsBytes()));
                } catch (Exception ignored) {
                }
            }
        }

        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR) {
            try {
                slots.put("40", Base64.getEncoder().encodeToString(offhand.serializeAsBytes()));
            } catch (Exception ignored) {
            }
        }
        return slots;
    }

    private List<Object> buildList(Iterable<FakePlayer> players) {
        List<Object> list = new ArrayList<>();
        for (FakePlayer fp : players) {
            Entity body = fp.getPhysicsEntity();
            Location loc = (body != null && body.isValid()) ? body.getLocation() : fp.getSpawnLocation();
            if (loc == null || loc.getWorld() == null) continue;

            var section = new LinkedHashMap<String, Object>();
            section.put("name", fp.getName());
            section.put("uuid", fp.getUuid().toString());
            section.put("display-name", fp.getDisplayName());
            section.put("spawned-by", fp.getSpawnedBy());
            section.put("spawned-by-uuid", fp.getSpawnedByUuid().toString());
            section.put("world", loc.getWorld().getName());
            section.put("x", loc.getX());
            section.put("y", loc.getY());
            section.put("z", loc.getZ());
            section.put("yaw", (double) loc.getYaw());
            section.put("pitch", (double) loc.getPitch());
            section.put("bot-type", fp.getBotType().name());
            section.put("chat-enabled", fp.isChatEnabled());
            section.put("respawn-on-death", fp.isRespawnOnDeath());
            section.put("head-ai-enabled", fp.isHeadAiEnabled());
            section.put("pickup-items", fp.isPickUpItemsEnabled());
            section.put("pickup-xp", fp.isPickUpXpEnabled());
            section.put("frozen", fp.isFrozen());
            section.put("nav-parkour", fp.isNavParkour());
            section.put("nav-break-blocks", fp.isNavBreakBlocks());
            section.put("nav-place-blocks", fp.isNavPlaceBlocks());
            section.put("nav-avoid-water", fp.isNavAvoidWater());
            section.put("nav-avoid-lava", fp.isNavAvoidLava());
            section.put("swim-ai-enabled", fp.isSwimAiEnabled());
            section.put("auto-eat-enabled", fp.isAutoEatEnabled());
            section.put("auto-eat-threshold", fp.getAutoEatHungerThreshold());
            section.put("auto-eat-foods", BotFoods.serialize(fp.getAutoEatFoods()));
            section.put("auto-place-bed-enabled", fp.isAutoPlaceBedEnabled());
            section.put("auto-milk-enabled", fp.isAutoMilkEnabled());
            section.put("prevent-bad-omen", fp.isPreventBadOmen());
            section.put("chunk-load-radius", fp.getChunkLoadRadius());
            section.put("left-click-interval-ticks", fp.getLeftClickIntervalTicks());
            section.put("right-click-interval-ticks", fp.getRightClickIntervalTicks());
            if (fp.getRentalExpiresAt() != null) section.put("rental-expires-at", fp.getRentalExpiresAt());
            if (fp.hasSharedControllers()) {
                Set<UUID> sharedControllers = fp.getSharedControllers();
                section.put(
                        "shared-controllers",
                        sharedControllers.stream().map(UUID::toString).sorted().toList());
            }
            section.put("pve-enabled", fp.isPveEnabled());
            section.put("pve-smart-attack-mode", fp.getPveSmartAttackMode().name());
            section.put("pve-range", fp.getPveRange());
            if (fp.getPvePriority() != null) section.put("pve-priority", fp.getPvePriority());
            if (fp.getPveMobType() != null) section.put("pve-mob-type", fp.getPveMobType());
            Player bot = fp.getPlayer();
            if (bot != null) {
                section.put("xp-total", bot.getTotalExperience());
                section.put("xp-level", bot.getLevel());
                section.put("xp-progress", (double) bot.getExp());
            }
            if (fp.hasCustomPing() || fp.isPingSimulated()) {
                section.put("ping", fp.getPing());
                section.put("ping-user-set", fp.hasCustomPing());
            }
            if (fp.getChatTier() != null) {
                section.put("chat-tier", fp.getChatTier());
            }
            if (fp.getAiPersonality() != null) {
                section.put("ai-personality", fp.getAiPersonality());
            }
            if (fp.getLuckpermsGroup() != null && !fp.getLuckpermsGroup().isBlank()) {
                section.put("luckperms-group", fp.getLuckpermsGroup());
            }
            if (fp.getRightClickCommand() != null) {
                section.put("right-click-command", fp.getRightClickCommand());
            }
            SkinProfile skin = fp.getResolvedSkin();
            if (skin != null && skin.isValid()) {
                section.put("skin-texture", skin.getValue());
                if (skin.getSignature() != null) {
                    section.put("skin-signature", skin.getSignature());
                }
            }
            list.add(section);
        }
        return list;
    }

    private List<Object> buildActiveListLight(Iterable<FakePlayer> players) {
        List<Object> list = new ArrayList<>();
        for (FakePlayer fp : players) {
            Location loc = fp.getSpawnLocation();
            if (loc == null || loc.getWorld() == null) continue;

            var section = new LinkedHashMap<String, Object>();
            section.put("name", fp.getName());
            section.put("uuid", fp.getUuid().toString());
            section.put("display-name", fp.getDisplayName());
            section.put("spawned-by", fp.getSpawnedBy());
            section.put("spawned-by-uuid", fp.getSpawnedByUuid().toString());
            section.put("world", loc.getWorld().getName());
            section.put("x", loc.getX());
            section.put("y", loc.getY());
            section.put("z", loc.getZ());
            section.put("yaw", (double) loc.getYaw());
            section.put("pitch", (double) loc.getPitch());
            section.put("bot-type", fp.getBotType().name());
            section.put("chat-enabled", fp.isChatEnabled());
            section.put("respawn-on-death", fp.isRespawnOnDeath());
            section.put("head-ai-enabled", fp.isHeadAiEnabled());
            section.put("pickup-items", fp.isPickUpItemsEnabled());
            section.put("pickup-xp", fp.isPickUpXpEnabled());
            section.put("frozen", fp.isFrozen());
            section.put("nav-parkour", fp.isNavParkour());
            section.put("nav-break-blocks", fp.isNavBreakBlocks());
            section.put("nav-place-blocks", fp.isNavPlaceBlocks());
            section.put("nav-avoid-water", fp.isNavAvoidWater());
            section.put("nav-avoid-lava", fp.isNavAvoidLava());
            section.put("swim-ai-enabled", fp.isSwimAiEnabled());
            section.put("auto-eat-enabled", fp.isAutoEatEnabled());
            section.put("auto-eat-threshold", fp.getAutoEatHungerThreshold());
            section.put("auto-eat-foods", BotFoods.serialize(fp.getAutoEatFoods()));
            section.put("auto-place-bed-enabled", fp.isAutoPlaceBedEnabled());
            section.put("auto-milk-enabled", fp.isAutoMilkEnabled());
            section.put("prevent-bad-omen", fp.isPreventBadOmen());
            section.put("chunk-load-radius", fp.getChunkLoadRadius());
            section.put("left-click-interval-ticks", fp.getLeftClickIntervalTicks());
            section.put("right-click-interval-ticks", fp.getRightClickIntervalTicks());
            if (fp.getRentalExpiresAt() != null) section.put("rental-expires-at", fp.getRentalExpiresAt());
            if (fp.hasSharedControllers()) {
                section.put(
                        "shared-controllers",
                        fp.getSharedControllers().stream()
                                .map(UUID::toString)
                                .sorted()
                                .toList());
            }
            section.put("pve-enabled", fp.isPveEnabled());
            section.put("pve-smart-attack-mode", fp.getPveSmartAttackMode().name());
            section.put("pve-range", fp.getPveRange());
            if (fp.getPvePriority() != null) section.put("pve-priority", fp.getPvePriority());
            if (fp.getPveMobType() != null) section.put("pve-mob-type", fp.getPveMobType());
            if (fp.hasCustomPing() || fp.isPingSimulated()) {
                section.put("ping", fp.getPing());
                section.put("ping-user-set", fp.hasCustomPing());
            }
            if (fp.getChatTier() != null) section.put("chat-tier", fp.getChatTier());
            if (fp.getAiPersonality() != null) section.put("ai-personality", fp.getAiPersonality());
            if (fp.getLuckpermsGroup() != null && !fp.getLuckpermsGroup().isBlank()) {
                section.put("luckperms-group", fp.getLuckpermsGroup());
            }
            if (fp.getRightClickCommand() != null) section.put("right-click-command", fp.getRightClickCommand());
            SkinProfile skin = fp.getResolvedSkin();
            if (skin != null && skin.isValid()) {
                section.put("skin-texture", skin.getValue());
                if (skin.getSignature() != null) section.put("skin-signature", skin.getSignature());
            }
            list.add(section);
        }
        return list;
    }

    public void restore(FakePlayerManager manager) {
        if (!Config.persistOnRestart()) {
            clearUnifiedSection(ROOT_BOTS);
            deleteFile(dataFile);
            FppLogger.info("Bot persistence is disabled - skipping restore.");
            return;
        }

        manager.setRestorationInProgress(true);

        loadInventoryFile();
        loadXpFile();

        loadTasksFile();

        boolean skinExtensionLoaded = false;
        boolean luckPermsExtensionLoaded = false;
        boolean chatExtensionLoaded = false;
        boolean aiChatExtensionLoaded = false;
        boolean commandExtensionLoaded = false;
        boolean pingExtensionLoaded = false;

        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null) {

            List<DatabaseManager.ActiveBotRow> rows = db.getActiveBotsForThisServer();
            if (!rows.isEmpty()) {
                Map<String, SkinProfile> yamlSkinFallback = loadYamlSkinFallback();
                Map<String, BotType> yamlBotTypeFallback = loadYamlBotTypeFallback();
                FppLogger.info(
                        "Restoring " + rows.size() + " bot(s) from database (server='" + Config.serverId() + "')...");

                List<SavedBot> saved = new ArrayList<>();
                for (var row : rows) {
                    try {
                        UUID storedUuid = parseUuidOrNull(row.botUuid());
                        UUID effectiveUuid = resolveRestoredUuid(row.botName(), storedUuid);
                        if (effectiveUuid == null) continue;
                        SkinProfile fallbackSkin =
                                yamlSkinFallback.get(row.botName().toLowerCase(Locale.ROOT));
                        BotType fallbackBotType =
                                yamlBotTypeFallback.getOrDefault(row.botName().toLowerCase(Locale.ROOT), BotType.AFK);
                        String skinTexture = skinExtensionLoaded ? row.skinTexture() : null;
                        String skinSignature = skinExtensionLoaded ? row.skinSignature() : null;
                        if (skinExtensionLoaded
                                && (skinTexture == null || skinTexture.isBlank())
                                && fallbackSkin != null
                                && fallbackSkin.isValid()) {
                            skinTexture = fallbackSkin.getValue();
                            skinSignature = fallbackSkin.getSignature();
                        }
                        saved.add(new SavedBot(
                                row.botName(),
                                effectiveUuid,
                                row.botDisplay(),
                                row.spawnedBy(),
                                UUID.fromString(row.spawnedByUuid()),
                                row.world(),
                                row.x(),
                                row.y(),
                                row.z(),
                                row.yaw(),
                                row.pitch(),
                                luckPermsExtensionLoaded ? row.luckpermsGroup() : null,
                                fallbackBotType,
                                chatExtensionLoaded && row.chatEnabled(),
                                row.respawnOnDeath(),
                                chatExtensionLoaded ? row.chatTier() : null,
                                aiChatExtensionLoaded ? row.aiPersonality() : null,
                                row.headAiEnabled(),
                                row.pickUpItems(),
                                row.pickUpXp(),
                                0,
                                0,
                                0f,
                                row.frozen(),
                                row.navParkour(),
                                row.navBreakBlocks(),
                                row.navPlaceBlocks(),
                                row.navAvoidWater(),
                                row.navAvoidLava(),
                                row.swimAiEnabled(),
                                pingExtensionLoaded ? row.ping() : -1,
                                commandExtensionLoaded ? row.rightClickCmd() : null,
                                row.pveEnabled(),
                                row.pveSmartAttackMode(),
                                row.pveRange(),
                                row.pvePriority(),
                                row.pveMobType(),
                                skinTexture,
                                skinSignature,
                                Set.of(),
                                Config.autoEatEnabled(),
                                Config.autoEatHungerThreshold(),
                                "",
                                Config.autoPlaceBedEnabled(),
                                row.autoMilkEnabled(),
                                row.preventBadOmen(),
                                pingExtensionLoaded && row.pingUserSet(),
                                row.rentalExpiresAt(),
                                row.leftClickIntervalTicks(),
                                row.rightClickIntervalTicks()));
                    } catch (Exception e) {
                        FppLogger.warn("Skipping malformed DB active-bot row: " + e.getMessage());
                    }
                }
                if (!saved.isEmpty()) {
                    FppScheduler.runSyncLater(
                            plugin, () -> restoreChain(manager, saved, 0), Config.restoreDelayTicks());
                } else {

                    manager.setRestorationInProgress(false);
                }
                return;
            }
        }

        YamlConfiguration unified = BotDataYaml.load(plugin);
        List<?> raw = unified.getList(ROOT_BOTS + ".bots");
        if ((raw == null || raw.isEmpty()) && dataFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(dataFile);
            raw = legacy.getList("bots");
            if (raw != null && !raw.isEmpty()) {
                final List<?> migrated = raw;
                try {
                    BotDataYaml.replaceSection(plugin, ROOT_BOTS, section -> {
                        section.set("bots", migrated);
                    });
                    deleteFile(dataFile);
                } catch (IOException e) {
                    FppLogger.warn(
                            "Failed to migrate " + FILE_NAME + " to " + BotDataYaml.FILE_NAME + ": " + e.getMessage());
                }
            }
        }

        if (raw == null || raw.isEmpty()) {
            manager.setRestorationInProgress(false);
            return;
        }

        List<SavedBot> saved = new ArrayList<>();
        for (Object obj : raw) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            try {
                String name = (String) map.get("name");
                UUID storedUuid = parseUuidOrNull((String) map.get("uuid"));
                UUID uuid = resolveRestoredUuid(name, storedUuid);
                if (uuid == null) continue;
                String displayName = (String) map.get("display-name");
                Object sbRaw = map.get("spawned-by");
                String spawnedBy = sbRaw instanceof String s ? s : "SERVER";
                Object sbuRaw = map.get("spawned-by-uuid");
                UUID spawnedByUuid = sbuRaw instanceof String str ? UUID.fromString(str) : new UUID(0, 0);
                String worldName = (String) map.get("world");
                double x = toDouble(map.get("x"));
                double y = toDouble(map.get("y"));
                double z = toDouble(map.get("z"));
                float yaw = (float) toDouble(map.get("yaw"));
                float pitch = (float) toDouble(map.get("pitch"));
                Object btRaw = map.get("bot-type");
                BotType botType = btRaw instanceof String bts ? BotType.parse(bts) : BotType.AFK;
                Object ceRaw = map.get("chat-enabled");
                boolean chatEnabled = !(ceRaw instanceof Boolean b) || b;
                Object rodRaw = map.get("respawn-on-death");
                boolean respawnOnDeath = rodRaw instanceof Boolean rod ? rod : Config.respawnOnDeath();
                Object headAiRaw = map.get("head-ai-enabled");
                boolean headAiEnabled = !(headAiRaw instanceof Boolean hai) || hai;
                Object pickupItemsRaw = map.get("pickup-items");
                boolean pickUpItems = pickupItemsRaw instanceof Boolean pi ? pi : Config.bodyPickUpItems();
                Object pickupXpRaw = map.get("pickup-xp");
                boolean pickUpXp = pickupXpRaw instanceof Boolean px ? px : Config.bodyPickUpXp();
                Object frozenRaw = map.get("frozen");
                boolean frozen = frozenRaw instanceof Boolean fr && fr;
                Object navPkRaw = map.get("nav-parkour");
                boolean navParkour = navPkRaw instanceof Boolean npk ? npk : Config.pathfindingParkour();
                Object navBbRaw = map.get("nav-break-blocks");
                boolean navBreakBlocks = navBbRaw instanceof Boolean nbb ? nbb : Config.pathfindingBreakBlocks();
                Object navPbRaw = map.get("nav-place-blocks");
                boolean navPlaceBlocks = navPbRaw instanceof Boolean npb ? npb : Config.pathfindingPlaceBlocks();
                Object navAwRaw = map.get("nav-avoid-water");
                boolean navAvoidWater = navAwRaw instanceof Boolean naw && naw;
                Object navAlRaw = map.get("nav-avoid-lava");
                boolean navAvoidLava = navAlRaw instanceof Boolean nal && nal;
                Object swimAiRaw = map.get("swim-ai-enabled");
                boolean swimAiEnabled = !(swimAiRaw instanceof Boolean sae) || sae;
                Object autoEatRaw = map.get("auto-eat-enabled");
                boolean autoEatEnabled = autoEatRaw instanceof Boolean aee ? aee : Config.autoEatEnabled();
                Object autoEatThreshRaw = map.get("auto-eat-threshold");
                int autoEatThreshold =
                        autoEatThreshRaw instanceof Number aet ? aet.intValue() : Config.autoEatHungerThreshold();
                Object autoEatFoodsRaw = map.get("auto-eat-foods");
                String autoEatFoods = autoEatFoodsRaw instanceof String aef ? aef : "";
                Object autoBedRaw = map.get("auto-place-bed-enabled");
                boolean autoPlaceBedEnabled = autoBedRaw instanceof Boolean apb ? apb : Config.autoPlaceBedEnabled();
                Object autoMilkRaw = map.get("auto-milk-enabled");
                boolean autoMilkEnabled = autoMilkRaw instanceof Boolean amk ? amk : Config.autoMilkEnabled();
                Object preventBadOmenRaw = map.get("prevent-bad-omen");
                boolean preventBadOmen = preventBadOmenRaw instanceof Boolean pbo ? pbo : Config.preventBadOmen();
                Set<UUID> sharedControllers = new LinkedHashSet<>();
                Object sharedRaw = map.get("shared-controllers");
                if (sharedRaw instanceof Iterable<?> sharedList) {
                    for (Object entry : sharedList) {
                        if (entry instanceof String str) {
                            try {
                                sharedControllers.add(UUID.fromString(str));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
                Object clrRaw = map.get("chunk-load-radius");
                int chunkLoadRadius = clrRaw instanceof Number clrn ? clrn.intValue() : -1;
                Object lciRaw = map.get("left-click-interval-ticks");
                int leftClickIntervalTicks = lciRaw instanceof Number lcin ? lcin.intValue() : -1;
                Object rciRaw = map.get("right-click-interval-ticks");
                int rightClickIntervalTicks = rciRaw instanceof Number rcin ? rcin.intValue() : -1;
                Object pingRaw = map.get("ping");
                int ping = pingRaw instanceof Number pr ? pr.intValue() : -1;
                Object pingUserSetRaw = map.get("ping-user-set");
                boolean pingUserSet = pingUserSetRaw instanceof Boolean pus ? pus : (ping >= 0);
                Object rccRaw = map.get("right-click-command");
                String rightClickCommand = commandExtensionLoaded && rccRaw instanceof String rcc ? rcc : null;
                Object xpTotalRaw = map.get("xp-total");
                int xpTotal = xpTotalRaw instanceof Number n1 ? n1.intValue() : 0;
                Object xpLevelRaw = map.get("xp-level");
                int xpLevel = xpLevelRaw instanceof Number n2 ? n2.intValue() : 0;
                Object xpProgressRaw = map.get("xp-progress");
                float xpProgress = xpProgressRaw instanceof Number n3 ? n3.floatValue() : 0f;
                Object ctRaw = map.get("chat-tier");
                String chatTier = chatExtensionLoaded && ctRaw instanceof String s2 ? s2 : null;
                Object apRaw = map.get("ai-personality");
                String aiPersonality = aiChatExtensionLoaded && apRaw instanceof String s3 ? s3 : null;
                Object lpRaw = map.get("luckperms-group");
                String luckpermsGroup = luckPermsExtensionLoaded && lpRaw instanceof String s4 ? s4 : null;
                Object pveEnRaw = map.get("pve-enabled");
                boolean pveEnabled = pveEnRaw instanceof Boolean pve && pve;
                Object pveModeRaw = map.get("pve-smart-attack-mode");
                String pveSmartAttackMode = pveModeRaw instanceof String psm
                        ? psm
                        : (map.get("pve-move") instanceof Boolean pveMove && pveMove)
                                ? "ON_MOVE"
                                : (pveEnabled ? "ON_NO_MOVE" : "OFF");
                Object pveRgRaw = map.get("pve-range");
                double pveRange = pveRgRaw instanceof Number prn ? prn.doubleValue() : Config.attackMobDefaultRange();
                Object pvePrRaw = map.get("pve-priority");
                String pvePriority = pvePrRaw instanceof String pps ? pps : null;
                Object pveMtRaw = map.get("pve-mob-type");
                String pveMobType = pveMtRaw instanceof String pmt ? pmt : null;
                Object skinTexRaw = map.get("skin-texture");
                String skinTexture = skinExtensionLoaded && skinTexRaw instanceof String st ? st : null;
                Object skinSigRaw = map.get("skin-signature");
                String skinSignature = skinExtensionLoaded && skinSigRaw instanceof String ss ? ss : null;
                Object rentalExpRaw = map.get("rental-expires-at");
                Long rentalExpiresAt = rentalExpRaw instanceof Number ren ? ren.longValue() : null;
                if (name == null || worldName == null) continue;
                saved.add(new SavedBot(
                        name,
                        uuid,
                        displayName,
                        spawnedBy,
                        spawnedByUuid,
                        worldName,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        luckpermsGroup,
                        botType,
                        chatEnabled,
                        respawnOnDeath,
                        chatTier,
                        aiPersonality,
                        headAiEnabled,
                        pickUpItems,
                        pickUpXp,
                        xpTotal,
                        xpLevel,
                        xpProgress,
                        frozen,
                        navParkour,
                        navBreakBlocks,
                        navPlaceBlocks,
                        navAvoidWater,
                        navAvoidLava,
                        swimAiEnabled,
                        pingExtensionLoaded ? ping : -1,
                        rightClickCommand,
                        pveEnabled,
                        pveSmartAttackMode,
                        pveRange,
                        pvePriority,
                        pveMobType,
                        skinTexture,
                        skinSignature,
                        sharedControllers,
                        autoEatEnabled,
                        autoEatThreshold,
                        autoEatFoods,
                        autoPlaceBedEnabled,
                        autoMilkEnabled,
                        preventBadOmen,
                        pingExtensionLoaded && pingUserSet,
                        rentalExpiresAt,
                        leftClickIntervalTicks,
                        rightClickIntervalTicks));
            } catch (Exception e) {
                FppLogger.warn("Skipping malformed bot entry in " + FILE_NAME + ": " + e.getMessage());
            }
        }
        if (saved.isEmpty()) {
            manager.setRestorationInProgress(false);
            return;
        }

        FppLogger.info("Restoring " + saved.size() + " bot(s) from YAML fallback...");
        FppScheduler.runSyncLater(plugin, () -> restoreChain(manager, saved, 0), Config.restoreDelayTicks());
    }

    private Map<String, SkinProfile> loadYamlSkinFallback() {
        Map<String, SkinProfile> fallback = new HashMap<>();
        try {
            YamlConfiguration unified = BotDataYaml.load(plugin);
            List<?> raw = unified.getList(ROOT_BOTS + ".bots");
            if ((raw == null || raw.isEmpty()) && dataFile.exists()) {
                raw = YamlConfiguration.loadConfiguration(dataFile).getList("bots");
            }
            if (raw == null) return fallback;
            for (Object obj : raw) {
                if (!(obj instanceof Map<?, ?> map)) continue;
                Object nameRaw = map.get("name");
                Object textureRaw = map.get("skin-texture");
                if (!(nameRaw instanceof String name) || !(textureRaw instanceof String texture) || texture.isBlank()) {
                    continue;
                }
                Object signatureRaw = map.get("skin-signature");
                String signature = signatureRaw instanceof String sig ? sig : null;
                fallback.put(
                        name.toLowerCase(Locale.ROOT), new SkinProfile(texture, signature, "yaml-fallback:" + name));
            }
        } catch (Exception e) {
            FppLogger.warn("BotPersistence: failed to read YAML skin fallback: " + e.getMessage());
        }
        return fallback;
    }

    private Map<String, BotType> loadYamlBotTypeFallback() {
        Map<String, BotType> fallback = new HashMap<>();
        try {
            YamlConfiguration unified = BotDataYaml.load(plugin);
            List<?> raw = unified.getList(ROOT_BOTS + ".bots");
            if ((raw == null || raw.isEmpty()) && dataFile.exists()) {
                raw = YamlConfiguration.loadConfiguration(dataFile).getList("bots");
            }
            if (raw == null) return fallback;
            for (Object obj : raw) {
                if (!(obj instanceof Map<?, ?> map)) continue;
                Object nameRaw = map.get("name");
                Object typeRaw = map.get("bot-type");
                if (!(nameRaw instanceof String name) || !(typeRaw instanceof String type)) continue;
                fallback.put(name.toLowerCase(Locale.ROOT), BotType.parse(type));
            }
        } catch (Exception e) {
            FppLogger.warn("BotPersistence: failed to read YAML bot type fallback: " + e.getMessage());
        }
        return fallback;
    }

    private void restoreChain(FakePlayerManager manager, List<SavedBot> saved, int index) {
        int batchEnd = Math.min(saved.size(), index + Config.restoreBatchSize());
        int nextIndex = index;
        while (nextIndex < batchEnd) {
            nextIndex = restoreOne(manager, saved, nextIndex);
        }
        if (nextIndex < saved.size()) {
            final int resumeAt = nextIndex;
            FppScheduler.runSyncLater(plugin, () -> restoreChain(manager, saved, resumeAt), 1L);
            return;
        }

        finishRestore(manager, saved);
    }

    private void finishRestore(FakePlayerManager manager, List<SavedBot> saved) {
        manager.setRestorationInProgress(false);
        loadedInventories = null;
        loadedXp = null;
        loadedTasks = null;
        clearUnifiedSection(ROOT_TASKS);
        deleteFile(tasksFile);
        if (Config.persistOnRestart()) saveActiveListNow(manager.getActivePlayers());
        FppLogger.info("Bot restoration complete: " + saved.size() + " bot(s) restored.");
    }

    private void saveActiveListNow(Iterable<FakePlayer> players) {
        if (activeListSavesDisabled) return;
        writeActiveBotList(buildActiveListLight(snapshotPlayers(players)));
    }

    private int restoreOne(FakePlayerManager manager, List<SavedBot> saved, int index) {
        if (index >= saved.size()) {
            return index;
        }

        SavedBot sb = saved.get(index);

        World world = Bukkit.getWorld(sb.worldName);
        if (world == null) {
            FppLogger.warn("Cannot restore bot '" + sb.name + "' - world '" + sb.worldName + "' not found. Skipping.");
            return index + 1;
        }

        Location loc = new Location(world, sb.x, sb.y, sb.z, sb.yaw, sb.pitch);

        SkinProfile restoredSkin = sb.skinTexture != null && !sb.skinTexture.isBlank()
                ? new SkinProfile(sb.skinTexture, sb.skinSignature, "persisted:" + sb.name)
                : null;
        manager.spawnRestored(
                sb.name, sb.uuid, sb.displayName, sb.spawnedBy, sb.spawnedByUuid, loc, sb.botType, restoredSkin);

        FakePlayer fp = manager.getByName(sb.name);
        if (fp != null) {
            restoreExtensionMetadata(fp);

            if (restoredSkin != null && restoredSkin.isValid() && plugin.getDatabaseManager() != null) {
                plugin.getDatabaseManager().updateBotSkin(fp.getUuid().toString(), sb.skinTexture, sb.skinSignature);
            }
            if (sb.luckpermsGroup != null && !sb.luckpermsGroup.isBlank()) {
                fp.setLuckpermsGroup(sb.luckpermsGroup);
            }

            fp.setChatEnabled(sb.chatEnabled);
            fp.setRespawnOnDeath(sb.respawnOnDeath);
            fp.setHeadAiEnabled(sb.headAiEnabled);
            fp.setPickUpItemsEnabled(sb.pickUpItemsEnabled);
            fp.setPickUpXpEnabled(sb.pickUpXpEnabled);
            fp.setFrozen(sb.frozen);
            fp.setNavParkour(sb.navParkour);
            fp.setNavBreakBlocks(sb.navBreakBlocks);
            fp.setNavPlaceBlocks(sb.navPlaceBlocks);
            fp.setNavAvoidWater(sb.navAvoidWater);
            fp.setNavAvoidLava(sb.navAvoidLava);
            fp.setSwimAiEnabled(sb.swimAiEnabled);
            fp.setAutoEatEnabled(sb.autoEatEnabled);
            fp.setAutoEatHungerThreshold(sb.autoEatThreshold);
            fp.setAutoEatFoods(BotFoods.parse(sb.autoEatFoods));
            fp.setAutoPlaceBedEnabled(sb.autoPlaceBedEnabled);
            fp.setAutoMilkEnabled(sb.autoMilkEnabled);
            fp.setPreventBadOmen(sb.preventBadOmen);
            fp.setRentalExpiresAt(sb.rentalExpiresAt);
            fp.setLeftClickIntervalTicks(sb.leftClickIntervalTicks);
            fp.setRightClickIntervalTicks(sb.rightClickIntervalTicks);
            for (UUID shared : sb.sharedControllers) fp.addSharedController(shared);
            // Custom/fabricated ping is never restored; bots always report no latency.
            fp.setUserPing(-1);
            fp.setBasePing(-1);
            fp.setPveSmartAttackMode(sb.pveSmartAttackMode);
            fp.setPveRange(sb.pveRange);
            if (sb.pvePriority != null) fp.setPvePriority(sb.pvePriority);
            if (sb.pveMobType != null) fp.setPveMobType(sb.pveMobType);
            if (sb.chatTier != null) fp.setChatTier(sb.chatTier);

            if (sb.aiPersonality != null) fp.setAiPersonality(sb.aiPersonality);

            if (sb.rightClickCommand != null && fp.getRightClickCommand() == null) {
                fp.setRightClickCommand(sb.rightClickCommand);
            }

            manager.persistBotSettings(fp);

        }

        if (loadedTasks != null) {
            TaskEntry te = loadedTasks.get(sb.uuid.toString());
            if (te != null && fp != null && te.rightClickCommand() != null) {
                fp.setRightClickCommand(te.rightClickCommand());
            }
        }

        if (loadedInventories != null) {
            Map<String, String> invSlots = loadedInventories.get(sb.uuid.toString());
            if (invSlots != null) {
                applyWhenBotReady(manager, sb.uuid, sb.name, "inventory", 10L, bot -> {
                    applyInventory(bot.getInventory(), invSlots);
                    Config.debug("Restored inventory for bot '" + sb.name + "'.");
                });
            }
        }

        XpEntry xpEntry = loadedXp != null ? loadedXp.get(sb.uuid.toString()) : null;
        if (xpEntry == null && (sb.xpTotal > 0 || sb.xpLevel > 0 || sb.xpProgress > 0f)) {
            xpEntry = new XpEntry(sb.xpTotal, sb.xpLevel, sb.xpProgress);
        }
        final XpEntry xpToRestore = xpEntry;
        if (xpToRestore != null) {
            applyWhenBotReady(manager, sb.uuid, sb.name, "XP", 12L, bot -> {
                bot.setTotalExperience(0);
                bot.setLevel(0);
                bot.setExp(0f);
                bot.setLevel(xpToRestore.level());
                bot.setExp(xpToRestore.progress());
                bot.setTotalExperience(xpToRestore.totalExperience());
                Config.debug("Restored XP for bot '" + sb.name + "'.");
            });
        }

        if (loadedTasks != null) {
            TaskEntry te = loadedTasks.get(sb.uuid.toString());
            if (te != null && (te.leftClick() != null || te.rightClick() != null)) {
                final TaskEntry task = te;
                // Extra delay so the inventory (tools!) is restored before the task starts.
                applyWhenBotReady(manager, sb.uuid, sb.name, "click task", 30L, bot -> {
                    FakePlayer restored = manager.getByUuid(sb.uuid);
                    if (restored == null) return;
                    resumeClickTask(restored, bot, task);
                });
            }
        }

        return index + 1;
    }

    /**
     * Runs {@code action} once the restored bot is fully spawned (online + valid), retrying every
     * 5 ticks for up to 30 seconds instead of silently giving up on the first attempt - right after a
     * restart, bot bodies routinely take longer than a fixed delay to finish spawning (chunk loads).
     */
    private void applyWhenBotReady(
            FakePlayerManager manager,
            UUID botUuid,
            String botName,
            String what,
            long initialDelayTicks,
            java.util.function.Consumer<Player> action) {
        attemptApply(manager, botUuid, botName, what, action, 0, initialDelayTicks);
    }

    private void attemptApply(
            FakePlayerManager manager,
            UUID botUuid,
            String botName,
            String what,
            java.util.function.Consumer<Player> action,
            int attempt,
            long delayTicks) {
        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    FakePlayer restored = manager.getByUuid(botUuid);
                    Player bot = restored != null ? restored.getPlayer() : null;
                    if (bot != null && bot.isValid() && bot.isOnline()) {
                        action.accept(bot);
                        return;
                    }
                    if (attempt + 1 >= 120) {
                        FppLogger.warn("BotPersistence: gave up restoring " + what + " for bot '" + botName
                                + "' - bot never finished spawning.");
                        return;
                    }
                    attemptApply(manager, botUuid, botName, what, action, attempt + 1, 5L);
                },
                delayTicks);
    }

    /**
     * Resumes the persisted click task(s). Left-click and right-click are independent, concurrent
     * task systems, so both are resumed if both were saved - this used to only restore one ("wins if
     * both were saved") back when a bot could only run one task at a time.
     */
    private void resumeClickTask(FakePlayer fp, Player bot, TaskEntry te) {
        resumeOneClickTask(fp, bot, te.leftClick(), true);
        resumeOneClickTask(fp, bot, te.rightClick(), false);
    }

    private void resumeOneClickTask(FakePlayer fp, Player bot, @Nullable ClickTaskEntry click, boolean isLeft) {
        if (click == null) return;
        if (!bot.getWorld().getName().equals(click.world())) {
            Config.debug("Skipping click-task resume for '" + fp.getDisplayName() + "' - world changed.");
            return;
        }
        if (isLeft && plugin.getLeftClickCommand() != null) {
            plugin.getLeftClickCommand().resumeSavedTask(fp, click.mode(), click.toVector());
        } else if (!isLeft && plugin.getRightClickCommand() != null) {
            plugin.getRightClickCommand().resumeSavedTask(fp, click.mode(), click.toVector());
        }
        Config.debug("Resumed " + (isLeft ? "left" : "right") + "-click task (" + click.mode() + ") for bot '"
                + fp.getDisplayName() + "'.");
    }

    private void restoreExtensionMetadata(FakePlayer fp) {
        if (fp == null || plugin.getDatabaseManager() == null) return;
        Map<String, Map<String, String>> data =
                plugin.getDatabaseManager().loadAllBotExtensionData(fp.getUuid().toString());
        for (Map<String, String> values : data.values()) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                fp.setMetadata(entry.getKey(), parseStoredMetadataValue(entry.getValue()));
            }
        }
    }

    private Object parseStoredMetadataValue(String raw) {
        if (raw == null) return null;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        try {
            if (!raw.contains(".") && !raw.contains("e") && !raw.contains("E")) return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {
        }
        return raw;
    }

    private void loadInventoryFile() {
        loadedInventories = new HashMap<>();
        YamlConfiguration unified = BotDataYaml.load(plugin);
        ConfigurationSection invSection = unified.getConfigurationSection(ROOT_INVENTORIES);
        if (invSection == null && inventoryFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(inventoryFile);
            invSection = legacy.getConfigurationSection("inventories");
            if (invSection != null) {
                final ConfigurationSection migrated = invSection;
                try {
                    BotDataYaml.replaceSection(plugin, ROOT_INVENTORIES, section -> {
                        for (String uuidKey : migrated.getKeys(false)) {
                            ConfigurationSection botSection = migrated.getConfigurationSection(uuidKey);
                            if (botSection == null) continue;
                            for (String slot : botSection.getKeys(false)) {
                                String val = botSection.getString(slot);
                                if (val != null && !val.isEmpty()) section.set(uuidKey + "." + slot, val);
                            }
                        }
                    });
                    deleteFile(inventoryFile);
                } catch (IOException e) {
                    FppLogger.warn("Failed to migrate " + INV_FILE_NAME + " to " + BotDataYaml.FILE_NAME + ": "
                            + e.getMessage());
                }
            }
        }
        if (invSection == null) return;
        for (String uuidKey : invSection.getKeys(false)) {
            ConfigurationSection botSection = invSection.getConfigurationSection(uuidKey);
            if (botSection == null) continue;
            Map<String, String> slots = new LinkedHashMap<>();
            boolean emptyInventory = botSection.getBoolean(EMPTY_INVENTORY_MARKER, false);
            for (String slot : botSection.getKeys(false)) {
                if (slot.equals(EMPTY_INVENTORY_MARKER)) continue;
                String val = botSection.getString(slot);
                if (val != null && !val.isEmpty()) slots.put(slot, val);
            }
            if (emptyInventory || !slots.isEmpty()) loadedInventories.put(uuidKey, slots);
        }
        Config.debug(
                "Loaded inventories for " + loadedInventories.size() + " bot(s) from " + BotDataYaml.FILE_NAME + ".");
    }

    private void loadXpFile() {
        loadedXp = new HashMap<>();
        YamlConfiguration unified = BotDataYaml.load(plugin);
        ConfigurationSection xpSection = unified.getConfigurationSection(ROOT_XP);
        if (xpSection == null && xpFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(xpFile);
            xpSection = legacy.getConfigurationSection("xp");
            if (xpSection != null) {
                final ConfigurationSection migrated = xpSection;
                try {
                    BotDataYaml.replaceSection(plugin, ROOT_XP, section -> {
                        for (String uuidKey : migrated.getKeys(false)) {
                            ConfigurationSection sec = migrated.getConfigurationSection(uuidKey);
                            if (sec == null) continue;
                            section.set(uuidKey + ".total", sec.getInt("total", 0));
                            section.set(uuidKey + ".level", sec.getInt("level", 0));
                            section.set(uuidKey + ".progress", sec.getDouble("progress", 0.0));
                        }
                    });
                    deleteFile(xpFile);
                } catch (IOException e) {
                    FppLogger.warn("Failed to migrate " + XP_FILE_NAME + " to " + BotDataYaml.FILE_NAME + ": "
                            + e.getMessage());
                }
            }
        }
        if (xpSection == null) return;
        for (String uuidKey : xpSection.getKeys(false)) {
            ConfigurationSection sec = xpSection.getConfigurationSection(uuidKey);
            if (sec == null) continue;
            loadedXp.put(uuidKey, new XpEntry(sec.getInt("total", 0), sec.getInt("level", 0), (float)
                    sec.getDouble("progress", 0.0)));
        }
        Config.debug("Loaded XP for " + loadedXp.size() + " bot(s) from " + BotDataYaml.FILE_NAME + ".");
    }

    private void loadTasksFile() {
        loadedTasks = new HashMap<>();

        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null) {
            List<DatabaseManager.BotTaskRow> dbRows = db.loadBotTasksForThisServer();
            if (!dbRows.isEmpty()) {
                loadedTasks = buildTasksFromDbRows(dbRows);

                db.clearBotTasks();
                Config.debug("Loaded task state for " + loadedTasks.size() + " bot(s) from DB.");
                return;
            }
        }

        YamlConfiguration unified = BotDataYaml.load(plugin);
        ConfigurationSection tasksSection = unified.getConfigurationSection(ROOT_TASKS);
        if (tasksSection == null && tasksFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(tasksFile);
            tasksSection = legacy;
            if (!legacy.getKeys(false).isEmpty()) {
                final YamlConfiguration migrated = legacy;
                try {
                    BotDataYaml.replaceSection(plugin, ROOT_TASKS, section -> {
                        for (String uuidKey : migrated.getKeys(false)) {
                            ConfigurationSection src = migrated.getConfigurationSection(uuidKey);
                            if (src == null) continue;
                            for (String key : src.getKeys(false)) {
                                section.set(uuidKey + "." + key, src.get(key));
                            }
                        }
                    });
                    deleteFile(tasksFile);
                } catch (IOException e) {
                    FppLogger.warn("Failed to migrate " + TASKS_FILE_NAME + " to " + BotDataYaml.FILE_NAME + ": "
                            + e.getMessage());
                }
            }
        }
        if (tasksSection == null) return;

        for (String uuidStr : tasksSection.getKeys(false)) {
            ConfigurationSection sec = tasksSection.getConfigurationSection(uuidStr);
            if (sec == null) continue;
            String rcc = sec.getString("right-click-command");
            ClickTaskEntry leftClick = readClickTask(sec.getConfigurationSection("left-click"));
            ClickTaskEntry rightClick = readClickTask(sec.getConfigurationSection("right-click"));
            if (rcc != null || leftClick != null || rightClick != null) {
                loadedTasks.put(uuidStr, new TaskEntry(rcc, leftClick, rightClick));
            }
        }
        Config.debug("Loaded task state for " + loadedTasks.size() + " bot(s) from " + BotDataYaml.FILE_NAME + ".");
    }

    @Nullable
    private static ClickTaskEntry readClickTask(@Nullable ConfigurationSection sec) {
        if (sec == null) return null;
        String mode = sec.getString("mode");
        String world = sec.getString("world");
        if (mode == null || world == null) return null;
        boolean hasPoint = sec.getBoolean("has-point", false);
        return new ClickTaskEntry(
                mode, world, hasPoint, sec.getDouble("x", 0), sec.getDouble("y", 0), sec.getDouble("z", 0));
    }

    private Map<String, TaskEntry> buildTasksFromDbRows(List<DatabaseManager.BotTaskRow> rows) {
        Map<String, Map<String, DatabaseManager.BotTaskRow>> byUuid = new LinkedHashMap<>();
        for (var row : rows) {
            byUuid.computeIfAbsent(row.botUuid(), k -> new LinkedHashMap<>()).put(row.taskType(), row);
        }
        Map<String, TaskEntry> result = new LinkedHashMap<>();
        for (var entry : byUuid.entrySet()) {
            ClickTaskEntry leftClick = fromClickTaskRow(entry.getValue().get("LEFT_CLICK"));
            ClickTaskEntry rightClick = fromClickTaskRow(entry.getValue().get("RIGHT_CLICK"));
            if (leftClick != null || rightClick != null) {
                result.put(entry.getKey(), new TaskEntry(null, leftClick, rightClick));
            }
        }
        return result;
    }

    // Inverse of addClickTaskRow: x/y/z = aim point, onceFlag = has-aim-point, extraStr = click mode.
    @Nullable
    private static ClickTaskEntry fromClickTaskRow(@Nullable DatabaseManager.BotTaskRow row) {
        if (row == null || row.extraStr() == null || row.worldName() == null) return null;
        return new ClickTaskEntry(row.extraStr(), row.worldName(), row.onceFlag(), row.posX(), row.posY(), row.posZ());
    }

    private static void applyInventory(PlayerInventory inv, Map<String, String> slots) {
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey());
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.getValue()));
                if (slot <= 35) inv.setItem(slot, item);
                else if (slot == 36) inv.setBoots(item);
                else if (slot == 37) inv.setLeggings(item);
                else if (slot == 38) inv.setChestplate(item);
                else if (slot == 39) inv.setHelmet(item);
                else if (slot == 40) inv.setItemInOffHand(item);
            } catch (Exception e) {
                FppLogger.warn("Failed to restore item in slot " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    public void purgeOrphanedBodiesAndRestore(FakePlayerManager manager) {

        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    purgeOrphanedBodies();

                    restore(manager);
                },
                0L);
    }

    private void purgeOrphanedBodies() {
        NamespacedKey key = FakePlayerManager.FAKE_PLAYER_KEY;
        if (key == null) return;

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getPersistentDataContainer().has(key, PersistentDataType.STRING)) continue;
                String val = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);

                if (val != null) {
                    try {
                        if (entity instanceof Player orphanBot) {
                            // A bot body from a previous plugin instance (e.g. hot-reload whose
                            // disable was interrupted). Players can't be remove()d - disconnect it.
                            NmsPlayerSpawner.removeFakePlayerFast(orphanBot, "orphaned_after_reload");
                        } else {
                            entity.remove();
                        }
                        removed++;
                        Config.debug("Purged orphaned entity: " + val);
                    } catch (Throwable t) {
                        FppLogger.debug("Could not purge orphaned entity '" + val + "': " + t);
                    }
                }
            }
        }
        if (removed > 0) {
            FppLogger.info("Purged " + removed + " orphaned bot entity/entities from previous session.");
        }
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private void clearUnifiedSection(String path) {
        try {
            YamlConfiguration yaml =
                    unifiedFile.exists() ? YamlConfiguration.loadConfiguration(unifiedFile) : new YamlConfiguration();
            yaml.set(path, null);
            yaml.save(unifiedFile);
        } catch (IOException e) {
            FppLogger.warn("BotPersistence: could not clear section '" + path + "' in " + BotDataYaml.FILE_NAME + ": "
                    + e.getMessage());
        }
    }

    private static void deleteFile(File f) {
        if (f.exists() && !f.delete()) {
            FppLogger.warn("BotPersistence: could not delete " + f.getName());
        }
    }

    private record SavedBot(
            String name,
            UUID uuid,
            String displayName,
            String spawnedBy,
            UUID spawnedByUuid,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String luckpermsGroup,
            BotType botType,
            boolean chatEnabled,
            boolean respawnOnDeath,
            String chatTier,
            String aiPersonality,
            boolean headAiEnabled,
            boolean pickUpItemsEnabled,
            boolean pickUpXpEnabled,
            int xpTotal,
            int xpLevel,
            float xpProgress,
            boolean frozen,
            boolean navParkour,
            boolean navBreakBlocks,
            boolean navPlaceBlocks,
            boolean navAvoidWater,
            boolean navAvoidLava,
            boolean swimAiEnabled,
            int ping,
            String rightClickCommand,
            boolean pveEnabled,
            String pveSmartAttackMode,
            double pveRange,
            String pvePriority,
            String pveMobType,
            String skinTexture,
            String skinSignature,
            Set<UUID> sharedControllers,
            boolean autoEatEnabled,
            int autoEatThreshold,
            String autoEatFoods,
            boolean autoPlaceBedEnabled,
            boolean autoMilkEnabled,
            boolean preventBadOmen,
            boolean pingUserSet,
            @Nullable Long rentalExpiresAt,
            int leftClickIntervalTicks,
            int rightClickIntervalTicks) {}

    private UUID resolveRestoredUuid(String botName, UUID storedUuid) {
        if (botName == null || botName.isBlank()) return storedUuid;
        UUID target = BotIdentityCache.deterministicBotUuid(botName);
        if (storedUuid == null || storedUuid.equals(target)) return target;

        // One-time migration to the fb07-prefixed UUID scheme: only entries carrying the exact
        // legacy offline-mode UUID for this name are remapped (inventory/XP/task state follows the
        // bot to its new key). Anything else - e.g. an explicit-UUID API spawn - is trusted as-is.
        if (storedUuid.equals(BotIdentityCache.offlineModeUuid(botName))) {
            remapLoadedState(storedUuid, target);
            Config.debug("BotPersistence: migrated '" + botName + "' " + storedUuid + " → " + target);
            return target;
        }
        return storedUuid;
    }

    private void remapLoadedState(UUID oldUuid, UUID newUuid) {
        if (oldUuid == null || newUuid == null || oldUuid.equals(newUuid)) return;
        String oldKey = oldUuid.toString();
        String newKey = newUuid.toString();
        remapLoadedMap(loadedInventories, oldKey, newKey);
        remapLoadedMap(loadedXp, oldKey, newKey);
        remapLoadedMap(loadedTasks, oldKey, newKey);
    }

    private static <T> void remapLoadedMap(Map<String, T> map, String oldKey, String newKey) {
        if (map == null || oldKey.equals(newKey)) return;
        T value = map.remove(oldKey);
        if (value != null && !map.containsKey(newKey)) {
            map.put(newKey, value);
        }
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** A persisted click task: mode name, world, and the exact aim point (hasPoint=false → self-view). */
    private record ClickTaskEntry(String mode, String world, boolean hasPoint, double x, double y, double z) {

        @Nullable
        org.bukkit.util.Vector toVector() {
            return hasPoint ? new org.bukkit.util.Vector(x, y, z) : null;
        }
    }

    private record TaskEntry(String rightClickCommand, ClickTaskEntry leftClick, ClickTaskEntry rightClick) {}

    private record XpEntry(int totalExperience, int level, float progress) {}
}
