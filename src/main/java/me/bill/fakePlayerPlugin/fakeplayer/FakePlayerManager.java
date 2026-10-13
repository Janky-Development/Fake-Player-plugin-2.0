package me.bill.fakePlayerPlugin.fakeplayer;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.PlayerProfile;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotSpawnEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotTeleportEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.auth.BotAuthManager;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.BotRecord;
import me.bill.fakePlayerPlugin.database.DatabaseManager;
import me.bill.fakePlayerPlugin.listener.PlayerJoinListener;
import me.bill.fakePlayerPlugin.util.AttributeCompat;
import me.bill.fakePlayerPlugin.util.AttributionApiManager;
import me.bill.fakePlayerPlugin.util.AttributionManager;
import me.bill.fakePlayerPlugin.util.BadwordFilter;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.TextUtil;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerPlayer;

public class FakePlayerManager {

    public static NamespacedKey FAKE_PLAYER_KEY;

    private static final double MIN_FALL_DAMAGE_DISTANCE = 4.0;

    private final FakePlayerPlugin plugin;
    private final Map<UUID, FakePlayer> activePlayers = new ConcurrentHashMap<>();

    private final Map<Integer, FakePlayer> entityIdIndex = new ConcurrentHashMap<>();

    private static final Vector ZERO_VELOCITY = new Vector(0, 0, 0);

    /**
     * Cosine of the maximum gaze angle for head-AI player tracking.
     * A player must be looking toward the bot within this cone for the bot to
     * react. cos(15°) ≈ 0.9659 produces natural-feeling eye-contact without
     * bots mechanically staring at every nearby player.
     */
    private static final double HEAD_AI_GAZE_COS = Math.cos(Math.toRadians(15.0));

    private final Map<String, FakePlayer> nameIndex = new ConcurrentHashMap<>();
    private final Set<String> usedNames = new HashSet<>();

    private final Map<UUID, Long> spawnCooldowns = new ConcurrentHashMap<>();

    private final Map<UUID, float[]> botHeadRotation = new ConcurrentHashMap<>();

    private final Map<UUID, float[]> botSpawnRotation = new ConcurrentHashMap<>();

    private final Map<UUID, float[]> lastSentVisualRotation = new ConcurrentHashMap<>();

    private volatile boolean restorationInProgress = false;

    private volatile List<String> cleanNamePool = Collections.emptyList();


    private int headAiTickCounter = 0;

    private volatile long visualSyncTickCounter = 0L;

    private final Set<UUID> bodyTransitionBots = new HashSet<>();


    private final Map<UUID, Integer> navJumpHolding = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Location> actionLockedBots = new ConcurrentHashMap<>();

    private final Set<UUID> navLockedBots = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<UUID, String> despawningBotIds = new ConcurrentHashMap<>();

    private final Set<UUID> syntheticQuitBotIds = ConcurrentHashMap.newKeySet();

    private final Set<UUID> renamingBotIds = ConcurrentHashMap.newKeySet();

    private final Set<UUID> suppressDespawnSnapshotIds = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Double> trackedFallDistance = new ConcurrentHashMap<>();

    private final Map<UUID, Double> lastFallY = new ConcurrentHashMap<>();

    private final Set<UUID> wasOnGround = ConcurrentHashMap.newKeySet();

    /**
     * Inventory + XP snapshot saved when a bot is manually despawned (only when
     * {@code dropItemsOnDespawn=false}). Keyed by lowercase bot name. Consumed on the next
     * same-name spawn so inventory and XP survive a manual despawn → spawn cycle.
     */
    private record DespawnSnapshot(
            ItemStack[] mainContents,
            ItemStack[] armorContents,
            ItemStack[] extraContents,
            int xpTotal,
            int xpLevel,
            float xpProgress,
            String skinTexture,
            String skinSignature) {}

    private final ConcurrentHashMap<String, DespawnSnapshot> despawnSnapshots = new ConcurrentHashMap<>();

    /**
     * YAML fallback file for despawn snapshots (used when DB is disabled).
     */
    private volatile File despawnSnapshotFile = null;

    private ChunkLoader chunkLoader;
    private DatabaseManager db;
    private BotPersistence persistence;
    private BotAuthManager authManager;
    private final AtomicBoolean dbLocationFlushRunning = new AtomicBoolean(false);

    public void setChunkLoader(ChunkLoader cl) {
        this.chunkLoader = cl;
    }

    public void setDatabaseManager(DatabaseManager db) {
        this.db = db;
    }

    public void setBotPersistence(BotPersistence p) {
        this.persistence = p;
    }

    public void setBotAuthManager(BotAuthManager authManager) {
        this.authManager = authManager;
    }

    public void refreshCleanNamePool() {
        List<String> raw = Config.namePool();
        List<String> clean = new ArrayList<>(raw.size());
        for (String n : raw) {
            if (n == null || n.isEmpty() || n.length() > 16 || !n.matches("[a-zA-Z0-9_]+")) continue;
            if (!BadwordFilter.isAllowed(n)) continue;
            clean.add(n);
        }
        cleanNamePool = Collections.unmodifiableList(clean);
        Config.debugStartup(
                "Clean name pool refreshed: " + clean.size() + "/" + raw.size() + " names pass the badword filter.");
    }

    public FakePlayerManager(FakePlayerPlugin plugin) {
        // Tab-list policy for every viewer connection: bots are never listed under a bare name.
        me.bill.fakePlayerPlugin.fakeplayer.network.ViewerPacketGuard.configureTab(
                (uuid, name) -> {
                    FakePlayer fp = uuid != null ? activePlayers.get(uuid) : null;
                    if (fp == null && name != null) fp = getByName(name);
                    return fp != null ? fp.getName() : null;
                },
                Config::tabListEnabled,
                FakePlayer::tabListName);

        this.plugin = plugin;
        FAKE_PLAYER_KEY = new NamespacedKey(plugin, "fake_player_name");

        if (!AttributionManager.quickAuthorCheck() || !AttributionApiManager.quickEndpointCheck()) {
            // Attribution integrity check failed - silently continuing.
        }

        long flushTicks = Math.max(20L, Config.dbLocationFlushInterval() * 20L);
        FppScheduler.runSyncRepeating(
                plugin,
                () -> {
                    if (activePlayers.isEmpty()) return;
                    for (FakePlayer fp : activePlayers.values()) {

                        Location loc = fp.getLiveLocation();
                        if (loc == null || loc.getWorld() == null) continue;
                        String world = loc.getWorld().getName();

                        fp.setSpawnLocation(loc.clone());
                        if (db != null) {
                            db.updateLastLocation(
                                    fp.getUuid(),
                                    world,
                                    loc.getX(),
                                    loc.getY(),
                                    loc.getZ(),
                                    loc.getYaw(),
                                    loc.getPitch());
                        }
                    }
                    if (db != null && dbLocationFlushRunning.compareAndSet(false, true)) {
                        FppScheduler.runAsync(plugin, () -> {
                            try {
                                db.flushPendingLocations();
                            } finally {
                                dbLocationFlushRunning.set(false);
                            }
                        });
                    }
                },
                flushTicks,
                flushTicks);

        FppScheduler.runSyncRepeating(
                plugin,
                () -> {
                    if (!Config.tabListEnabled()) return;
                    if (activePlayers.isEmpty()) return;
                    Collection<? extends Player> online = Bukkit.getOnlinePlayers();
                    if (online.isEmpty()) return;
                    for (FakePlayer fp : activePlayers.values()) {
                        if (!fp.isTabListDirty()) continue;
                        fp.clearTabListDirty();
                        for (Player p : online) {
                            if (activePlayers.containsKey(p.getUniqueId())) continue; // bots have no client
                            PacketHelper.sendTabListDisplayNameUpdate(p, fp);
                        }
                    }
                },
                20L,
                20L);

        FppScheduler.runSyncRepeating(
                plugin,
                () -> {
                    if (activePlayers.isEmpty()) return;
                    final boolean foliaServer = NmsPlayerSpawner.isFoliaServer();
                    for (FakePlayer fp : activePlayers.values()) {
                        // Per-bot guard: one bad bot must never kill this repeating task - a dead
                        // refresh loop freezes every nametag's activity row on its last value.
                        Runnable refreshTick = () -> {
                            try {
                                // Unconditional, every sweep (not gated on label change): a bot that
                                // gets put on some OTHER scoreboard team (another plugin's own
                                // prefix/color system, an admin's /scoreboard teams join, …) is
                                // silently evicted from our hide-team by the client - Minecraft only
                                // allows one team per player - bringing the real name back without
                                // ever touching the text_display. A bot whose activity label never
                                // changes would otherwise never get re-hidden. Cheap (one tiny
                                // packet), so doing it every 10 ticks for every bot is fine.
                                FakePlayerBody.reassertVanillaNameHidden(fp);

                                // Self-heal: the nametag is a real world entity (a TextDisplay), so
                                // anything that can remove an entity - /kill, WorldEdit, another
                                // plugin's cleanup pass - can remove it too. getNametagEntity() only
                                // ever goes null when WE clear it; an external removal leaves a stale
                                // non-null reference behind, so check isValid(), not just null, and
                                // respawn immediately when it's gone. This is what makes the tag
                                // effectively unremovable instead of merely "usually present".
                                if (!FakePlayerBody.nametagIntact(fp)) {
                                    if (!Config.nametagSecondLineEnabled()) return;
                                    Player body = fp.getPlayer();
                                    if (body == null || !body.isOnline()) return;
                                    FakePlayerBody.spawnNametag(fp, body);
                                    fp.setLastRenderedActionLabel(BotActivity.currentLabel(fp));
                                    return;
                                }
                                String label = BotActivity.currentLabel(fp);
                                if (label.equals(fp.getLastRenderedActionLabel())) return;
                                fp.setLastRenderedActionLabel(label);
                                FakePlayerBody.refreshNametag(fp);
                            } catch (Throwable t) {
                                Config.debug("Nametag activity refresh failed for '" + fp.getName() + "': " + t);
                            }
                        };
                        // BotActivity.currentLabel() and the text-display update both touch entity
                        // state, so on Folia they must run on the bot's owning region thread rather
                        // than the global region thread this loop itself is ticking on.
                        if (foliaServer) {
                            Player bot = fp.getPlayer();
                            if (bot == null) continue;
                            FppScheduler.runAtEntity(plugin, bot, refreshTick);
                        } else {
                            refreshTick.run();
                        }
                    }
                },
                10L,
                10L);

        FppScheduler.runSyncRepeating(
                plugin,
                () -> {
                    if (activePlayers.isEmpty()) return;

                    final boolean foliaServer = NmsPlayerSpawner.isFoliaServer();
                    headAiTickCounter++;
                    final boolean headAiOn = Config.headAiEnabled();
                    final int headAiRate = Config.headAiTickRate();

                    // Head-AI's target lookup reads other online players' eye locations, and its
                    // rotation writes target the bot itself - both already happen unconditionally
                    // elsewhere in this same per-bot tick (position-sync snapshot above, physics
                    // below), which on Folia is dispatched onto the bot's own entity scheduler via
                    // FppScheduler.runAtEntity. So head-AI is just as Folia-safe as the rest of the
                    // tick; it no longer needs to be disabled on Folia specifically.
                    final boolean doHeadAi = headAiOn && (headAiTickCounter % headAiRate == 0);
                    final double rangeSq = doHeadAi ? Config.headAiLookRange() * Config.headAiLookRange() : 0;
                    final float speed = doHeadAi ? Config.headAiTurnSpeed() : 0f;

                    final double psd = Config.positionSyncDistance();
                    final double posSyncDistSq = psd > 0 ? psd * psd : -1;
                    visualSyncTickCounter++;

                    // One snapshot of the REAL viewers per tick (bots are not viewers: they have no client,
                    // so head-AI targets and position/rotation sync packets never concern them). Built once
                    // here instead of once per bot per tick - that was O(bots x players) allocations.
                    final List<Player> onlineSnapshot = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!activePlayers.containsKey(online.getUniqueId())) onlineSnapshot.add(online);
                    }
                    final int onlineCount = onlineSnapshot.size();
                    final double[] playerX = new double[onlineCount];
                    final double[] playerY = new double[onlineCount];
                    final double[] playerZ = new double[onlineCount];
                    final World[] playerWorld = new World[onlineCount];
                    for (int pi = 0; pi < onlineCount; pi++) {
                        Player p = onlineSnapshot.get(pi);
                        if (!p.isValid()) continue;
                        Location pl = p.getLocation();
                        playerX[pi] = pl.getX();
                        playerY[pi] = pl.getY();
                        playerZ[pi] = pl.getZ();
                        playerWorld[pi] = pl.getWorld();
                    }

                    for (FakePlayer fp : activePlayers.values()) {
                        Player bot = fp.getPlayer();

                        if (bot == null) continue;
                        Runnable botTick = () -> {
                            if (!activePlayers.containsKey(fp.getUuid())) return;
                            if (!bot.isValid() || bot.isDead()) return;
                            if (fp.isFrozen() || fp.isAuthPending()) return;
                            UUID uuid = fp.getUuid();
                            boolean isNavigating = plugin.getPathfindingService() != null
                                    && plugin.getPathfindingService().isNavigating(uuid);
                            boolean isActing = actingBots.containsKey(uuid);
                            boolean isNavLocked = navLockedBots.contains(uuid);
                            boolean hasMiningLock = actionLockedBots.containsKey(uuid);
                            Location before = bot.getLocation();

                            if (!fp.isFrozen()) {

                                if (fp.isSwimAiEnabled()) {
                                    boolean navJump = isNavigating && navJumpHolding.getOrDefault(uuid, 0) > 0;
                                    try (var t = PacketHelper.profile("PathfindingService.tickSwimAi")) {
                                        PathfindingService.tickSwimAi(bot, navJump, isNavigating);
                                    }
                                }
                                navJumpHolding.computeIfPresent(uuid, (k, v) -> v > 1 ? v - 1 : null);

                                if ((fp.isAutoEatEnabled() || fp.isAutoEating())
                                        && (visualSyncTickCounter & 3) == (uuid.hashCode() & 3)) {
                                    try (var t = PacketHelper.profile("FakePlayerManager.tickAutoEat")) {
                                        var autoEat = plugin.getAutoEatController();
                                        if (autoEat != null) autoEat.tick(fp, bot);
                                    }
                                }

                                // Sleeping bots: check every tick whether NMS has woken the bot
                                // (bed broken, monsters nearby, time-skip complete). Syncing here
                                // rather than waiting for the 40-tick nightWatchTick sweep means the
                                // bot stands up on the exact same tick the bed is removed.
                                if (fp.isSleeping()) {
                                    ServerPlayer nmsBot = ((CraftPlayer) bot).getHandle();
                                    if (!nmsBot.isSleeping()) {
                                        // NMS already woke the bot - clear flag and fall through to normal tick.
                                        fp.setSleeping(false);
                                        actionLockedBots.remove(uuid);
                                        // fall through - bot immediately resumes normal physics/AI below
                                    } else {
                                        // Still genuinely sleeping: zero velocity, tick physics so that
                                        // NMS sleepCounter increments (needed for time-skip to dawn),
                                        // then skip all AI/movement for this tick.
                                        bot.setVelocity(ZERO_VELOCITY);
                                        NmsPlayerSpawner.tickPhysics(bot);
                                        var fppApiTickSleep = plugin.getFppApiImpl();
                                        if (fppApiTickSleep != null) fppApiTickSleep.fireTickHandlers(fp, bot);
                                        return;
                                    }
                                }

                                try (var t = PacketHelper.profile("NmsPlayerSpawner.tickPhysics")) {
                                    NmsPlayerSpawner.tickPhysics(bot);
                                }
                                if (Config.debugHeadAi()) {
                                    if (isActing) {
                                        Config.debug(
                                                "HeadAI[" + fp.getName() + "]: SKIPPED - bot is acting (mining/using)");
                                    } else if (isNavLocked) {
                                        Config.debug("HeadAI[" + fp.getName() + "]: SKIPPED - bot is nav-locked");
                                    } else if (!fp.isHeadAiEnabled()) {
                                        Config.debug(
                                                "HeadAI[" + fp.getName() + "]: SKIPPED - head AI disabled for bot");
                                    } else if (!doHeadAi) {
                                        Config.debug("HeadAI[" + fp.getName() + "]: SKIPPED - not head AI tick (rate="
                                                + headAiRate + ")");
                                    }
                                }
                                if (doHeadAi
                                        && fp.isHeadAiEnabled()
                                        && !isNavLocked
                                        && !isActing
                                        && onlineCount > 0) {

                                    // Head-AI target selection: only track a player who is actively
                                    // looking at this bot (eye-contact model). Conditions:
                                    //   1. Player is within look range (rangeSq)
                                    //   2. Bot has line of sight to the player
                                    //   3. The player's look direction points toward the bot
                                    //      within HEAD_AI_GAZE_COS (cos of ~15 degrees).
                                    // This prevents bots from mechanically staring at whoever
                                    // walks past; they only react when a player is gazing at them.
                                    Player target = null;
                                    double bestSq = rangeSq;
                                    for (int pi2 = 0; pi2 < onlineCount; pi2++) {
                                        Player p = onlineSnapshot.get(pi2);
                                        if (playerWorld[pi2] == null) continue;
                                        if (p.getGameMode() == GameMode.SPECTATOR) continue;
                                        if (playerWorld[pi2] != before.getWorld()) continue;
                                        double ddx = playerX[pi2] - before.getX();
                                        double ddy = playerY[pi2] - before.getY();
                                        double ddz = playerZ[pi2] - before.getZ();
                                        double dSq = ddx * ddx + ddy * ddy + ddz * ddz;
                                        if (dSq > bestSq) continue;
                                        // Check that the player is looking toward this bot (gaze test).
                                        // We compare the player's look direction against the unit vector
                                        // from the player's eye to the bot's eye.
                                        Vector lookDir = p.getEyeLocation().getDirection();
                                        double botEyeX = before.getX() - playerX[pi2];
                                        double botEyeY = (before.getY() + 1.62) - (playerY[pi2] + 1.62);
                                        double botEyeZ = before.getZ() - playerZ[pi2];
                                        double dist =
                                                Math.sqrt(botEyeX * botEyeX + botEyeY * botEyeY + botEyeZ * botEyeZ);
                                        if (dist < 0.001) continue;
                                        double dot = (lookDir.getX() * botEyeX
                                                        + lookDir.getY() * botEyeY
                                                        + lookDir.getZ() * botEyeZ)
                                                / dist;
                                        // cos(15°) ≈ 0.9659 - tighter cone means more deliberate eye-contact
                                        if (dot < HEAD_AI_GAZE_COS) continue;
                                        if (!hasLineOfSightIgnoringGlass(bot, p)) continue;
                                        bestSq = dSq;
                                        target = p;
                                    }

                                    float[] rot = botHeadRotation.computeIfAbsent(
                                            uuid, k -> new float[] {before.getYaw(), before.getPitch()});

                                    float prevYaw = rot[0];
                                    float prevPitch = rot[1];

                                    if (target != null) {

                                        Location eye = bot.getEyeLocation();
                                        Location tgt = target.getEyeLocation();
                                        double dx = tgt.getX() - eye.getX();
                                        double dy = tgt.getY() - eye.getY();
                                        double dz = tgt.getZ() - eye.getZ();
                                        double horiz = Math.sqrt(dx * dx + dz * dz);
                                        float targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
                                        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
                                        rot[0] = lerpAngle(rot[0], targetYaw, speed);
                                        rot[1] = lerpAngle(rot[1], targetPitch, speed);
                                    }

                                    if (Math.abs(rot[0] - prevYaw) > 0.01f || Math.abs(rot[1] - prevPitch) > 0.01f) {
                                        bot.setRotation(rot[0], rot[1]);
                                        NmsPlayerSpawner.setHeadYaw(bot, rot[0]);
                                        {
                                            float[] lastSent = lastSentVisualRotation.get(uuid);
                                            boolean shouldBroadcast = lastSent == null
                                                    || Math.abs(rot[0] - lastSent[0]) > 0.5f
                                                    || Math.abs(rot[1] - lastSent[1]) > 0.5f;
                                            if (shouldBroadcast) {
                                                lastSentVisualRotation.put(uuid, new float[] {rot[0], rot[1]});
                                                for (int pi2 = 0; pi2 < onlineCount; pi2++) {
                                                    Player p = onlineSnapshot.get(pi2);
                                                    if (p.getUniqueId().equals(uuid)) continue;
                                                    if (playerWorld[pi2] != before.getWorld()) continue;
                                                    if (posSyncDistSq > 0) {
                                                        double ddx = playerX[pi2] - before.getX();
                                                        double ddz = playerZ[pi2] - before.getZ();
                                                        if (ddx * ddx + ddz * ddz > posSyncDistSq) continue;
                                                    }
                                                    PacketHelper.sendRotationDirect(
                                                            p, fp, rot[0], rot[1], rot[0], false);
                                                }
                                            }
                                        }
                                    }
                                }

                                Location miningLock = hasMiningLock ? actionLockedBots.get(uuid) : null;
                                if (miningLock != null) {
                                    boolean sameWorld = before.getWorld() == miningLock.getWorld();
                                    boolean outOfPlace = !sameWorld || before.distanceSquared(miningLock) > 0.0001;
                                    if (outOfPlace) {
                                        FppScheduler.teleportAsync(bot, miningLock);
                                    }
                                    bot.setVelocity(ZERO_VELOCITY);
                                }

                                var fppApiTick = plugin.getFppApiImpl();
                                if (fppApiTick != null) {
                                    try (var t = PacketHelper.profile("FppApi.fireTickHandlers")) {
                                        fppApiTick.fireTickHandlers(fp, bot);
                                    }
                                }
                            }

                            Location after = bot.getLocation();
                            if (Config.fallDamageEnabled() && (visualSyncTickCounter & 1) == 0) {
                                try (var t = PacketHelper.profile("FakePlayerManager.tickFallDamage")) {
                                    tickFallDamage(fp, bot, before, after);
                                }
                            }
                            double dxM = before.getX() - after.getX();
                            double dyM = before.getY() - after.getY();
                            double dzM = before.getZ() - after.getZ();

                            Vector vel2 = bot.getVelocity();
                            boolean moved =
                                    before.getWorld() == after.getWorld() && (dxM * dxM + dyM * dyM + dzM * dzM) > 1e-8;
                            double vx = vel2.getX(), vy = vel2.getY(), vz2 = vel2.getZ();
                            if ((moved || (vx * vx + vy * vy + vz2 * vz2) > 1e-6)) {
                                if (onlineCount > 0) {
                                    for (int pi2 = 0; pi2 < onlineCount; pi2++) {
                                        Player p = onlineSnapshot.get(pi2);
                                        if (p.equals(bot)) continue;
                                        if (playerWorld[pi2] == null) continue;

                                        if (posSyncDistSq > 0) {
                                            if (playerWorld[pi2] != after.getWorld()) continue;
                                            double ddx = playerX[pi2] - after.getX();
                                            double ddy = playerY[pi2] - after.getY();
                                            double ddz = playerZ[pi2] - after.getZ();
                                            if (ddx * ddx + ddy * ddy + ddz * ddz > posSyncDistSq) continue;
                                        }
                                        PacketHelper.sendPositionSync(p, bot, after);
                                    }
                                }
                            }

                            // Keep the name tag (a free display) glued above the bot. teleportDuration
                            // smooths the motion; only move it when the bot actually shifted.
                            var nameTag = fp.getNametagEntity();
                            if (nameTag != null && nameTag.isValid()) {
                                Location tl = nameTag.getLocation();
                                double expectedY = after.getY() + Config.nametagSecondLineYOffset();
                                boolean off = tl.getWorld() != after.getWorld()
                                        || Math.abs(tl.getX() - after.getX()) > 0.02
                                        || Math.abs(tl.getZ() - after.getZ()) > 0.02
                                        || Math.abs(tl.getY() - expectedY) > 0.02;
                                if (off) FakePlayerBody.moveNametag(fp, after);
                            }

                            // Vehicles pick up mobs that they run into; players are excluded by
                            // vanilla, so bots get the same rule applied from their side.
                            BotAutoMount.tick(FakePlayerManager.this, fp, bot);
                        };
                        if (foliaServer) {
                            FppScheduler.runAtEntity(plugin, bot, botTick);
                        } else {
                            botTick.run();
                        }
                    }
                },
                1L,
                1L);
    }

    private void tickFallDamage(FakePlayer fp, Player bot, Location before, Location after) {
        UUID uuid = fp.getUuid();
        if (!Config.fallDamageEnabled()
                || actionLockedBots.containsKey(uuid)
                || bot.isDead()
                || bot.getGameMode() == GameMode.CREATIVE
                || bot.getGameMode() == GameMode.SPECTATOR) {
            trackedFallDistance.remove(uuid);
            lastFallY.remove(uuid);
            wasOnGround.add(uuid);
            return;
        }

        boolean onGround = isBotOnGround(bot, after);
        if (!before.getWorld().equals(after.getWorld())) {
            trackedFallDistance.remove(uuid);
            lastFallY.remove(uuid);
            if (onGround) wasOnGround.add(uuid);
            else wasOnGround.remove(uuid);
            return;
        }

        if (!onGround && isFallDamageResetByCurrentBlock(bot, after)) {
            trackedFallDistance.remove(uuid);
            lastFallY.put(uuid, after.getY());
            wasOnGround.remove(uuid);
            return;
        }

        double currentY = after.getY();
        Double previousY = lastFallY.put(uuid, currentY);
        if (!onGround) {
            if (previousY != null && previousY > currentY) {
                addTrackedFallDistance(uuid, previousY - currentY, bot.getFallDistance());
            }
            wasOnGround.remove(uuid);
            return;
        }

        if (previousY != null && previousY > currentY) {
            addTrackedFallDistance(uuid, previousY - currentY, bot.getFallDistance());
        }

        double distance = Math.max(trackedFallDistance.getOrDefault(uuid, 0.0), bot.getFallDistance());
        double safeDistance = Config.fallDamageSafeDistance();
        if (!wasOnGround.contains(uuid) && distance >= MIN_FALL_DAMAGE_DISTANCE && distance > safeDistance) {
            double damage = Math.floor((distance - safeDistance)
                    * Config.fallDamageMultiplier()
                    * landingFallDamageMultiplier(bot, after));
            if (damage > 0.0) {
                bot.damage(damage);
            }
        }
        trackedFallDistance.remove(uuid);
        lastFallY.remove(uuid);
        wasOnGround.add(uuid);
    }

    private void addTrackedFallDistance(UUID uuid, double delta, double bukkitFallDistance) {
        if (delta <= 0.0) return;
        double distance = trackedFallDistance.getOrDefault(uuid, 0.0) + delta;
        trackedFallDistance.put(uuid, Math.max(distance, bukkitFallDistance));
    }

    private boolean isBotOnGround(Player bot, Location loc) {
        return !loc.clone().subtract(0, 0.08, 0).getBlock().isPassable();
    }

    private boolean isFallDamageResetByCurrentBlock(Player bot, Location loc) {
        if (bot.isInWater() || bot.isInLava()) return true;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        World world = loc.getWorld();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material below = world.getBlockAt(x, y - 1, z).getType();
        return isFallDamageResetBlock(feet) || isFallDamageResetBlock(below);
    }

    private boolean isFallDamageResetBlock(Material material) {
        if (Tag.CLIMBABLE.isTagged(material)) return true;
        return material == Material.BUBBLE_COLUMN
                || material == Material.COBWEB
                || material == Material.POWDER_SNOW
                || material == Material.SLIME_BLOCK;
    }

    private double landingFallDamageMultiplier(Player bot, Location loc) {
        if (isFallDamageResetByCurrentBlock(bot, loc)) return 0.0;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        World world = loc.getWorld();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (feet == Material.HAY_BLOCK || below == Material.HAY_BLOCK) return 0.2;
        if (feet.name().endsWith("_BED") || below.name().endsWith("_BED")) return 0.5;
        return 1.0;
    }

    public boolean physicalBodiesEnabled() {
        return true;
    }

    public boolean isRestorationInProgress() {
        return restorationInProgress;
    }

    public void setRestorationInProgress(boolean inProgress) {
        this.restorationInProgress = inProgress;
    }

    public boolean isBodyTransitioning(UUID uuid) {
        return bodyTransitionBots.contains(uuid);
    }





    public String getDespawningDisplayName(UUID uuid) {
        return despawningBotIds.get(uuid);
    }

    public boolean hasSyntheticQuit(UUID uuid) {
        return uuid != null && syntheticQuitBotIds.contains(uuid);
    }

    public void addSyntheticQuit(UUID uuid) {
        if (uuid != null) syntheticQuitBotIds.add(uuid);
    }

    public void markDespawning(UUID uuid, String displayName) {
        despawningBotIds.put(uuid, displayName);
    }

    public void clearDespawningNextTick(UUID uuid) {
        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    despawningBotIds.remove(uuid);
                    syntheticQuitBotIds.remove(uuid);
                },
                1L);
    }

    /**
     * Fires a quit event for a bot that is being removed by us. Bots never produce a leave message:
     * the event carries none and nothing is ever sent to chat or the console. The event itself is
     * still required so other plugins and the despawn handling see the departure.
     */
    public void fireSyntheticQuit(FakePlayer fp, PlayerQuitEvent.QuitReason reason) {
        if (fp == null) return;
        Player player = fp.getPlayer();
        if (player == null) player = fp.getPhysicsEntity();
        if (player == null) return;

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, (Component) null, reason);
        Bukkit.getPluginManager().callEvent(quitEvent);
        syntheticQuitBotIds.add(fp.getUuid());
    }





    public boolean isRenaming(UUID uuid) {
        return renamingBotIds.contains(uuid);
    }





    public String formatLocationForDisplay(FakePlayer fp) {
        if (!physicalBodiesEnabled()) {
            return "No Body";
        }
        var body = fp.getPhysicsEntity();
        if (body != null && body.isValid()) {
            var l = body.getLocation();
            return (l.getWorld() != null ? l.getWorld().getName() : "?")
                    + " "
                    + l.getBlockX()
                    + ","
                    + l.getBlockY()
                    + ","
                    + l.getBlockZ();
        }
        var sl = fp.getSpawnLocation();
        if (sl != null)
            return (sl.getWorld() != null ? sl.getWorld().getName() : "?")
                    + " "
                    + sl.getBlockX()
                    + ","
                    + sl.getBlockY()
                    + ","
                    + sl.getBlockZ();
        return "unknown";
    }



    public int spawn(Location location, int count, Player spawner) {
        return spawn(location, count, spawner, null, false, BotType.AFK);
    }

    public int spawn(Location location, int count, Player spawner, String customName) {
        return spawn(location, count, spawner, customName, false, BotType.AFK);
    }

    public int spawnUserBot(Location location, int count, Player spawner, boolean bypassMax) {
        return spawnUserBot(location, count, spawner, bypassMax, BotType.AFK);
    }

    public int spawnUserBot(Location location, int count, Player spawner, boolean bypassMax, BotType botType) {
        int maxBots = Config.maxBots();
        if (!bypassMax && maxBots > 0) {
            int available = maxBots - activePlayers.size();
            if (available <= 0) return -1;
            count = Math.min(count, available);
        }

        String spawnerName = spawner.getName();
        UUID spawnerUuid = spawner.getUniqueId();

        List<FakePlayer> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = nextSequentialName();
            if (name == null) break;
            UUID uuid = resolveUuid(name);
            PlayerProfile profile = Bukkit.createProfile(uuid, name);
            FakePlayer fp = new FakePlayer(uuid, name, profile);
            fp.setBotType(botType);

            fp.setSkinName(spawnerName);
            applyDespawnSnapshotSkin(fp);

            String rawUserName = Config.adminBotNameFormat().replace("{bot_name}", name);
            fp.setRawDisplayName(rawUserName);
            fp.setDisplayName(finalizeDisplayName(rawUserName, name));
            fp.setSpawnLocation(location.clone());
            fp.setSpawnedBy(spawnerName, spawnerUuid);
            fp.setSpawnTick(System.currentTimeMillis());
            activePlayers.put(uuid, fp);
            nameIndex.put(name.toLowerCase(), fp);
            batch.add(fp);
            if (authManager != null) authManager.handleBotJoin(fp);

            if (db != null) {
                BotRecord record = new BotRecord(
                        0,
                        name,
                        uuid,
                        spawnerName,
                        spawnerUuid,
                        location.getWorld() != null ? location.getWorld().getName() : "unknown",
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch(),
                        Instant.now(),
                        null,
                        null);
                fp.setDbRecord(record);
                db.recordSpawn(record, name);
                persistActiveSkin(fp);
            }
        }
        if (batch.isEmpty()) return 0;

        int total = batch.size();
        FppScheduler.runSync(plugin, () -> visualChain(batch, 0, location));
        return total;
    }

    public int spawn(Location location, int count, Player spawner, String customName, boolean bypassMax) {
        return spawn(location, count, spawner, customName, bypassMax, BotType.AFK);
    }

    public int spawn(
            Location location, int count, Player spawner, String customName, boolean bypassMax, BotType botType) {
        return spawn(location, count, spawner, customName, bypassMax, botType, (UUID) null);
    }

    public int spawn(
            Location location, int count, Player spawner, String customName, boolean bypassMax, UUID explicitUuid) {
        return spawn(location, count, spawner, customName, bypassMax, BotType.AFK, explicitUuid);
    }

    private int spawn(
            Location location,
            int count,
            Player spawner,
            String customName,
            boolean bypassMax,
            BotType botType,
            UUID explicitUuid) {
        int maxBots = Config.maxBots();
        if (!bypassMax && maxBots > 0) {
            int available = maxBots - activePlayers.size();
            if (available <= 0) return -1;
            count = Math.min(count, available);
        }

        String spawnerName = spawner != null ? spawner.getName() : "CONSOLE";
        UUID spawnerUuid = spawner != null ? spawner.getUniqueId() : new UUID(0, 0);
        UUID resolvedCustomUuid = null;

        if (customName != null) {

            String effectiveName = customName;

            if (effectiveName.isEmpty() || effectiveName.length() > 16 || !effectiveName.matches("[a-zA-Z0-9_]+"))
                return -2;

            if (usedNames.contains(effectiveName)) return 0;
            if (getByName(effectiveName) != null) return 0;
            if (explicitUuid != null && activePlayers.containsKey(explicitUuid)) return 0;

            Player realPlayer = Bukkit.getPlayerExact(effectiveName);
            if (realPlayer != null && !activePlayers.containsKey(realPlayer.getUniqueId())) return -4;
            if (explicitUuid != null) {
                Player realPlayerByUuid = Bukkit.getPlayer(explicitUuid);
                if (realPlayerByUuid != null && !activePlayers.containsKey(explicitUuid)) return -4;
            }
            // A bot may not take the name of a real account that has joined this server before, so
            // it can never be mistaken for (or impersonate) an actual player.
            if (isRealPlayerName(effectiveName)) return -4;
            resolvedCustomUuid = explicitUuid != null ? explicitUuid : resolveUuid(effectiveName);
            if (activePlayers.containsKey(resolvedCustomUuid)) return 0;

            count = 1;
        }

        List<FakePlayer> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String baseName;
            String name;

            if (customName != null) {
                baseName = customName;
                name = customName;
            } else {
                name = nextSequentialName();
                baseName = name;
            }

            if (name == null) break;
            UUID uuid = resolvedCustomUuid != null
                    ? resolvedCustomUuid
                    : explicitUuid != null ? explicitUuid : resolveUuid(name);
            PlayerProfile profile = Bukkit.createProfile(uuid, name);
            FakePlayer fp = new FakePlayer(uuid, name, profile);
            if (explicitUuid != null) {
                fp.setMetadata("fpp.explicit-uuid-spawn", true);
                fp.setMetadata(
                        "fpp.explicit-uuid-was-op",
                        Bukkit.getOfflinePlayer(explicitUuid).isOp());
                snapshotExplicitUuidPlayerData(fp);
            }
            fp.setBotType(botType);

            fp.setSkinName(baseName != null ? baseName : name);
            applyDespawnSnapshotSkin(fp);

            String rawAdminName = Config.adminBotNameFormat().replace("{bot_name}", name);
            fp.setRawDisplayName(rawAdminName);
            String displayName = finalizeDisplayName(rawAdminName, name);
            fp.setDisplayName(displayName);
            fp.setSpawnLocation(location.clone());
            fp.setSpawnedBy(spawnerName, spawnerUuid);
            fp.setSpawnTick(System.currentTimeMillis());
            usedNames.add(name);
            activePlayers.put(uuid, fp);
            nameIndex.put(name.toLowerCase(), fp);
            batch.add(fp);
            if (authManager != null) authManager.handleBotJoin(fp);

            if (db != null) {
                BotRecord record = new BotRecord(
                        0,
                        name,
                        uuid,
                        spawnerName,
                        spawnerUuid,
                        location.getWorld() != null ? location.getWorld().getName() : "unknown",
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch(),
                        Instant.now(),
                        null,
                        null);
                fp.setDbRecord(record);
                db.recordSpawn(record, name);
                persistActiveSkin(fp);
            }
        }
        if (batch.isEmpty()) return 0;

        int total = batch.size();

        if (NmsPlayerSpawner.isFoliaServer()) {
            FppLogger.debug("FakePlayerManager.spawn: Folia detected - scheduling spawn on region thread");
            FppScheduler.runAtLocation(plugin, location, () -> visualChain(batch, 0, location));
        } else {
            FppScheduler.runSync(plugin, () -> visualChain(batch, 0, location));
        }
        return total;
    }

    public int spawnBodyless(
            Location location, int count, Player spawner, String customName, boolean bypassMax, boolean spawnBodyless) {
        return spawnBodyless(location, count, spawner, customName, bypassMax, spawnBodyless, BotType.AFK);
    }

    public int spawnBodyless(
            Location location,
            int count,
            Player spawner,
            String customName,
            boolean bypassMax,
            boolean spawnBodyless,
            BotType botType) {
        int result = spawn(location, count, spawner, customName, bypassMax, botType);
        if (result > 0 && spawnBodyless) {

            String spawnerName = spawner != null ? spawner.getName() : "CONSOLE";
            UUID spawnerUuid = spawner != null ? spawner.getUniqueId() : new UUID(0, 0);

            long now = System.currentTimeMillis();
            activePlayers.values().stream()
                    .filter(fp -> fp.getSpawnedBy().equals(spawnerName)
                            && fp.getSpawnedByUuid().equals(spawnerUuid)
                            && (now - fp.getSpawnTime().toEpochMilli()) < 1000)
                    .limit(result)
                    .forEach(fp -> fp.setBodyless(true));
        }
        return result;
    }

    private void visualChain(List<FakePlayer> batch, int index, Location location) {
        if (batch == null) return;

        boolean folia = NmsPlayerSpawner.isFoliaServer();
        int batchSize = folia ? 2 : 6;
        int end = Math.min(batch.size(), index + batchSize);
        for (int i = index; i < end; i++) {
            FakePlayer fp = batch.get(i);
            if (activePlayers.containsKey(fp.getUuid())) {
                Location spawnLoc =
                        fp.getSpawnLocation() != null ? fp.getSpawnLocation().clone() : location.clone();
                finishSpawn(fp, spawnLoc);
            }
        }
        if (end < batch.size()) {
            int next = end;
            long delay = 1L;
            FppScheduler.runSyncLater(
                    plugin,
                    () -> {
                        if (folia) {
                            FppScheduler.runAtLocation(plugin, location, () -> visualChain(batch, next, location));
                        } else {
                            visualChain(batch, next, location);
                        }
                    },
                    delay);
        }
    }

    private void finishSpawn(FakePlayer fp, Location spawnLoc) {
        fp.setSpawnTime(Instant.now());
        spawnBodyAndFinish(fp, spawnLoc);
    }

    private int computeInitialPing(FakePlayer fp) {
        return fp.hasCustomPing() ? Math.max(0, fp.getPing()) : -1;
    }

    private boolean isExplicitUuidSpawn(FakePlayer fp) {
        return fp != null && Boolean.TRUE.equals(fp.getMetadata("fpp.explicit-uuid-spawn"));
    }

    public boolean isExplicitUuidBot(FakePlayer fp) {
        return isExplicitUuidSpawn(fp);
    }

    private boolean wasExplicitUuidOp(FakePlayer fp) {
        return fp != null && Boolean.TRUE.equals(fp.getMetadata("fpp.explicit-uuid-was-op"));
    }

    private void restoreExplicitUuidOperator(FakePlayer fp) {
        if (!wasExplicitUuidOp(fp)) return;
        try {
            Bukkit.getOfflinePlayer(fp.getUuid()).setOp(true);
            Config.debug("Restored operator status for explicit UUID bot source '" + fp.getName() + "'.");
        } catch (Throwable t) {
            FppLogger.warn("Failed to restore operator status for explicit UUID bot source '"
                    + fp.getName()
                    + "': "
                    + t.getMessage());
        }
    }

    private void snapshotExplicitUuidPlayerData(FakePlayer fp) {
        Path file = explicitUuidPlayerDataFile(fp.getUuid());
        if (file == null) return;
        fp.setMetadata("fpp.explicit-uuid-playerdata-path", file.toString());
        try {
            if (Files.exists(file)) {
                fp.setMetadata("fpp.explicit-uuid-playerdata-bytes", Files.readAllBytes(file));
            } else {
                fp.setMetadata("fpp.explicit-uuid-playerdata-missing", true);
            }
        } catch (Throwable t) {
            FppLogger.warn("Failed to snapshot playerdata for explicit UUID bot source '"
                    + fp.getName()
                    + "': "
                    + t.getMessage());
        }
    }

    private void restoreExplicitUuidPlayerData(FakePlayer fp) {
        if (!isExplicitUuidSpawn(fp)) return;
        Object rawPath = fp.getMetadata("fpp.explicit-uuid-playerdata-path");
        if (!(rawPath instanceof String pathString) || pathString.isBlank()) return;

        Path file = Path.of(pathString);
        try {
            Object rawBytes = fp.getMetadata("fpp.explicit-uuid-playerdata-bytes");
            if (rawBytes instanceof byte[] bytes) {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".fpp-restore.tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                Config.debug("Restored playerdata for explicit UUID bot source '" + fp.getName() + "'.");
            } else if (Boolean.TRUE.equals(fp.getMetadata("fpp.explicit-uuid-playerdata-missing"))) {
                Files.deleteIfExists(file);
            }
        } catch (Throwable t) {
            FppLogger.warn("Failed to restore playerdata for explicit UUID bot source '"
                    + fp.getName()
                    + "': "
                    + t.getMessage());
        }
    }

    public void restoreExplicitUuidSourceState(FakePlayer fp) {
        restoreExplicitUuidPlayerData(fp);
        restoreExplicitUuidOperator(fp);
    }

    private Path explicitUuidPlayerDataFile(UUID uuid) {
        if (uuid == null || Bukkit.getWorlds().isEmpty()) return null;
        File worldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
        if (worldFolder == null) return null;
        return worldFolder.toPath().resolve("playerdata").resolve(uuid + ".dat");
    }

    private void spawnBodyAndFinish(FakePlayer fp, Location spawnLoc) {

        FakePlayerBody.resolveAndFinish(
                plugin,
                fp,
                spawnLoc,
                () -> {
                    if (!activePlayers.containsKey(fp.getUuid())) {
                        Config.debug("finishSpawn aborted for '"
                                + fp.getName()
                                + "' - removed before body spawn callback fired.");
                        return;
                    }

                    int initialPing = computeInitialPing(fp);

                    if (!fp.isBodyless()) {
                        // A bot that logged out in a vehicle comes back where that vehicle was.
                        Location mountLoc = BotMountMemory.pendingLocation(fp.getUuid());
                        final Location bodyLoc = mountLoc != null ? mountLoc : spawnLoc;
                        Player body = FakePlayerBody.spawn(fp, bodyLoc, initialPing);
                        if (body != null) {
                            stampFakePlayerMarker(body, fp);
                            restoreMountOrProtectSpawn(fp, body, bodyLoc);
                            fp.setPacketProfileName(fp.getName());
                            // Ping was already set on the NMS ServerPlayer before placeNewPlayer,
                            // but update it again here in case the pre-spawn set didn't take effect.
                            NmsPlayerSpawner.setPing(body, fp.getEffectivePing());

                            final Player savedBody = body;
                            final String savedName = fp.getName();
                            final UUID savedUuid = fp.getUuid();
                            final Path playerDataPath = explicitUuidPlayerDataFile(savedUuid);
                            final boolean skipPlayerDataSave =
                                    isExplicitUuidSpawn(fp) || (playerDataPath != null && Files.exists(playerDataPath));
                            if (wasExplicitUuidOp(fp)) {
                                try {
                                    savedBody.setOp(true);
                                } catch (Throwable ignored) {
                                }
                            }
                            long initialSaveDelay = 40L + Math.floorMod(savedUuid.hashCode(), 40);
                            FppScheduler.runAtEntityLaterWithId(
                                    plugin,
                                    savedBody,
                                    () -> {
                                        if (!savedBody.isOnline()) return;
                                        if (skipPlayerDataSave) {
                                            FppLogger.debug(
                                                    "FakePlayerManager: skipped initial playerdata save for bot '"
                                                            + savedName
                                                            + "' uuid="
                                                            + savedUuid);
                                            return;
                                        }
                                        try {

                                            PlayerJoinListener.stampFirstPlayed(savedBody);
                                            savedBody.saveData();
                                            FppLogger.debug("FakePlayerManager: initial playerdata"
                                                    + " saved for '"
                                                    + savedName
                                                    + "' uuid="
                                                    + savedUuid);
                                        } catch (Exception e) {
                                            FppLogger.warn("FakePlayerManager: initial saveData"
                                                    + " failed for '"
                                                    + savedName
                                                    + "' uuid="
                                                    + savedUuid
                                                    + ": "
                                                    + e.getMessage());
                                        }
                                    },
                                    initialSaveDelay);
                        } else {
                            FppLogger.warn("finishSpawn: body spawn failed for '"
                                    + fp.getName()
                                    + "' - rolling back bot to avoid ghost keepalive connection.");
                            delete(fp.getName(), "spawn_body_failed");
                            return;
                        }
                    } else if (fp.isBodyless()) {

                        Config.debug("Bodyless spawn: skipping physical body for " + fp.getName());
                    }

                    if (Config.tabListEnabled()) {
                        boolean isNmsPlayer = fp.getPlayer() != null;
                        Config.debug("Sending tab-list for '"
                                + fp.getName()
                                + "' display='"
                                + fp.getDisplayName()
                                + "' packet='"
                                + fp.getPacketProfileName()
                                + "' nms="
                                + isNmsPlayer);

                        long tabAddDelay = 2L + Math.floorMod(fp.getUuid().hashCode(), 8);
                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    if (!activePlayers.containsKey(fp.getUuid())) return;
                                    for (Player p : Bukkit.getOnlinePlayers()) PacketHelper.sendTabListAdd(p, fp);
                                    if (fp.hasCustomPing() || fp.isPingSimulated()) {
                                        for (Player p : Bukkit.getOnlinePlayers()) {
                                            PacketHelper.sendTabListLatencyUpdate(p, fp);
                                        }
                                    }
                                },
                                tabAddDelay);

                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    if (!activePlayers.containsKey(fp.getUuid())) return;
                                    boolean nms = fp.getPlayer() != null;
                                    Config.debug("Re-sending tab-list display-name (3t) for '" + fp.getName() + "' nms="
                                            + nms);
                                    for (Player p : Bukkit.getOnlinePlayers())
                                        PacketHelper.sendTabListDisplayNameUpdate(p, fp);
                                },
                                tabAddDelay + 8L);

                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    if (!activePlayers.containsKey(fp.getUuid())) return;
                                    Config.debug("Finalising tab-list for '" + fp.getName() + "'");

                                    var vc = plugin.getVelocityChannel();
                                    if (vc != null) vc.broadcastBotSpawn(fp);
                                },
                                tabAddDelay + 20L);
                    } else {
                        Config.debug("Tab-list disabled - unlisting '" + fp.getName() + "'");

                        Player bot = fp.getPlayer();
                        if (bot != null) NmsPlayerSpawner.setListed(bot, false);

                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    if (!activePlayers.containsKey(fp.getUuid())) return;
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        PacketHelper.sendTabListUpdateListed(p, fp, false);
                                    }

                                    var vc = plugin.getVelocityChannel();
                                    if (vc != null) vc.broadcastBotSpawn(fp);
                                },
                                3L);
                    }

                    if (persistence != null && Config.persistOnRestart() && !restorationInProgress) {
                        persistence.saveActiveListAsync(activePlayers.values());
                    }

                    // Restore inventory+XP from a prior manual despawn of the same bot name.
                    DespawnSnapshot despawnSnap =
                            despawnSnapshots.remove(fp.getName().toLowerCase());
                    if (despawnSnap != null) {
                        removeDespawnSnapshotPersistent(fp.getName().toLowerCase());
                        final UUID snapBotUuid = fp.getUuid();
                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    FakePlayer restoredFp = getByUuid(snapBotUuid);
                                    if (restoredFp == null) return;
                                    Player restoredBot = restoredFp.getPlayer();
                                    if (restoredBot == null || !restoredBot.isValid()) return;
                                    applyDespawnSnapshot(restoredBot, despawnSnap);
                                    Config.debug("[DespawnSnapshot] Restored inventory+XP to '" + restoredFp.getName()
                                            + "'.");
                                },
                                5L);
                    }

                    // Fire API spawn event.
                    var fppApi = plugin.getFppApi();
                    if (fppApi != null) {
                        FppBotSpawnEvent spawnEvt = new FppBotSpawnEvent(new FppBotImpl(fp), fp.isRestoredSpawn());
                        Bukkit.getPluginManager().callEvent(spawnEvt);
                    }

                    runSpawnActions(fp);
                },
                () -> {
                    if (!activePlayers.containsKey(fp.getUuid())) return;
                    if (Config.tabListEnabled()) {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            PacketHelper.sendTabListDisplayNameUpdate(p, fp);
                        }
                    }
                });
    }

    /**
     * Runs the commands a {@code /fpp spawn … <action>} queued on this bot, once, as whoever spawned it,
     * shortly after the body is online (so every system that needs a live player sees one).
     */
    private void runSpawnActions(FakePlayer fp) {
        Object raw = fp.getMetadata(SpawnActions.KEY);
        if (!(raw instanceof SpawnActions actions)) return;
        fp.removeMetadata(SpawnActions.KEY);

        final UUID botUuid = fp.getUuid();
        long at = Math.max(1L, actions.initialDelayTicks());
        for (SpawnActions.Step step : actions.steps()) {
            at += Math.max(0L, step.delayTicks());
            final String line = step.commandLine();
            FppScheduler.runSyncLater(
                    plugin,
                    () -> {
                        if (!activePlayers.containsKey(botUuid)) return;
                        org.bukkit.command.CommandSender sender = actions.console()
                                ? Bukkit.getConsoleSender()
                                : Bukkit.getPlayer(actions.senderUuid());
                        if (sender == null) return; // the spawner left before this step came due
                        Bukkit.dispatchCommand(sender, line);
                    },
                    at);
        }
    }

    /**
     * Like a player rejoining in their boat: if this bot logged out riding a vehicle, the vehicle is
     * re-created where it was and the bot seated in it. Otherwise (and if that fails) the usual
     * spawn-point protection applies. The protection is skipped while a mount is pending because it
     * would teleport the bot - and thereby dismount it - back to the point it was asked to spawn at.
     */
    private void restoreMountOrProtectSpawn(FakePlayer fp, Player body, Location spawnLoc) {
        final UUID logical = fp.getUuid();
        if (BotMountMemory.pendingLocation(logical, body.getUniqueId()) == null) {
            protectRequestedSpawnLocation(fp, body, spawnLoc.clone());
            return;
        }
        final Location requested = spawnLoc.clone();
        FppScheduler.runAtEntityLaterWithId(
                plugin,
                body,
                () -> {
                    Player current = resolveCurrentBody(fp, body);
                    if (current == null || !current.isValid()) return;
                    BotMountMemory.restore(current, logical).thenAccept(ok -> {
                        if (Boolean.TRUE.equals(ok)) return;
                        // No vehicle to sit in: do not leave the bot buried in the block the
                        // vehicle used to occupy - lift it to free space, then protect the spawn.
                        FppScheduler.runAtEntityLaterWithId(
                                plugin,
                                current,
                                () -> {
                                    liftOutOfBlocks(current);
                                    protectRequestedSpawnLocation(fp, current, requested);
                                },
                                1L);
                    });
                },
                2L);
    }

    /** Moves {@code bot} straight up to the first position where its feet and head are free. */
    private static void liftOutOfBlocks(Player bot) {
        try {
            Location loc = bot.getLocation();
            World w = loc.getWorld();
            if (w == null) return;
            int maxY = w.getMaxHeight() - 2;
            Location test = loc.clone();
            int steps = 0;
            while (test.getBlockY() < maxY
                    && steps < 64
                    && (!test.getBlock().isPassable()
                            || !test.clone().add(0, 1, 0).getBlock().isPassable())) {
                test.add(0, 1, 0);
                steps++;
            }
            if (steps > 0) {
                test.setY(test.getBlockY());
                FppScheduler.teleportAsync(bot, test);
            }
        } catch (Throwable ignored) {
        }
    }

    private void protectRequestedSpawnLocation(FakePlayer fp, Player body, Location requested) {
        if (fp == null || body == null || requested == null || requested.getWorld() == null) return;
        for (long delay : new long[] {1L, 5L, 20L, 60L}) {
            FppScheduler.runAtEntityLaterWithId(
                    plugin, body, () -> reassertRequestedSpawnLocation(fp, body, requested), delay);
        }
    }

    private void reassertRequestedSpawnLocation(FakePlayer fp, Player body, Location requested) {
        Player currentBody = resolveCurrentBody(fp, body);
        if (!activePlayers.containsKey(fp.getUuid()) || currentBody == null || !currentBody.isValid()) return;
        Location current = currentBody.getLocation();
        if (isAtTeleportDestination(current, requested)) {
            fp.setSpawnLocation(current.clone());
            return;
        }

        Config.debugNmsDamage("spawn location reassert: bot="
                + fp.getName()
                + " current="
                + formatLocation(current)
                + " requested="
                + formatLocation(requested));
        FppScheduler.teleportAsync(currentBody, requested.clone()).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) refreshAfterTeleport(fp, currentBody, requested.clone());
        });
    }

    public void spawnRestored(
            String name, UUID uuid, String savedDisplayName, String spawnedBy, UUID spawnedByUuid, Location location) {
        spawnRestored(name, uuid, savedDisplayName, spawnedBy, spawnedByUuid, location, BotType.AFK);
    }

    public void spawnRestored(
            String name,
            UUID uuid,
            String savedDisplayName,
            String spawnedBy,
            UUID spawnedByUuid,
            Location location,
            BotType botType) {
        spawnRestored(name, uuid, savedDisplayName, spawnedBy, spawnedByUuid, location, botType, null);
    }

    public void spawnRestored(
            String name,
            UUID uuid,
            String savedDisplayName,
            String spawnedBy,
            UUID spawnedByUuid,
            Location location,
            BotType botType,
            SkinProfile restoredSkin) {

        if (usedNames.contains(name)) return;

        UUID restoredUuid = resolveUuid(name);
        if (uuid != null && !uuid.equals(restoredUuid)) {
            Config.debugDatabase(
                    "Restored bot '" + name + "' UUID corrected from stored " + uuid + " to " + restoredUuid);
        }

        PlayerProfile profile = Bukkit.createProfile(restoredUuid, name);
        FakePlayer fp = new FakePlayer(restoredUuid, name, profile);
        fp.setBotType(botType);
        fp.setRestoredSpawn(true);
        if (restoredSkin != null && restoredSkin.isValid()) {
            fp.setResolvedSkin(restoredSkin);
            Config.debugSkin("Restored persisted skin for bot '" + name + "'");
        }

        boolean isUserBot = name.startsWith("ubot_");
        if (isUserBot) {
            fp.setSkinName(spawnedBy != null && !spawnedBy.isBlank() ? spawnedBy : name);
        } else {
            fp.setSkinName(name);
        }

        String effectiveSpawner = (spawnedBy != null && !spawnedBy.isBlank()) ? spawnedBy : "Unknown";
        String displayName;
        String persistedDisplay = savedDisplayName != null && !savedDisplayName.isBlank() ? savedDisplayName : null;

        if (persistedDisplay != null) {
            fp.setRawDisplayName(persistedDisplay);
            displayName = finalizeDisplayName(persistedDisplay, name);
        } else if (isUserBot) {
            int lastUs = name.lastIndexOf('_');
            int botIdx = 1;
            if (lastUs > 0 && lastUs < name.length() - 1) {
                try {
                    botIdx = Integer.parseInt(name.substring(lastUs + 1));
                } catch (NumberFormatException ignored) {
                    botIdx = 1;
                }
            }
            String rawName = Config.userBotNameFormat()
                    .replace("{spawner}", effectiveSpawner)
                    .replace("{num}", String.valueOf(botIdx))
                    .replace("{bot_name}", name);

            Config.debug("[Restore] user-bot '"
                    + name
                    + "' type="
                    + botType
                    + " spawner='"
                    + effectiveSpawner
                    + "' num="
                    + botIdx);
            fp.setRawDisplayName(rawName);
            displayName = finalizeDisplayName(rawName, name);
        } else {
            String rawName = Config.adminBotNameFormat().replace("{bot_name}", name);
            Config.debug("[Restore] admin-bot '" + name + "' type=" + botType);
            fp.setRawDisplayName(rawName);
            displayName = finalizeDisplayName(rawName, name);
        }

        fp.setDisplayName(displayName);
        fp.setSpawnLocation(location);
        fp.setSpawnedBy(effectiveSpawner, spawnedByUuid);
        usedNames.add(name);
        fp.setSpawnTick(System.currentTimeMillis());
        activePlayers.put(restoredUuid, fp);
        nameIndex.put(name.toLowerCase(), fp);
        if (authManager != null) authManager.handleBotJoin(fp);

        if (db != null) {
            BotRecord record = new BotRecord(
                    0,
                    name,
                    restoredUuid,
                    effectiveSpawner,
                    spawnedByUuid,
                    location.getWorld() != null ? location.getWorld().getName() : "unknown",
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch(),
                    Instant.now(),
                    null,
                    null);
            fp.setDbRecord(record);
            String plainDisplay = PlainTextComponentSerializer.plainText().serialize(TextUtil.colorize(displayName));
            db.recordSpawn(record, plainDisplay);
        }

        finishSpawn(fp, location);
        Config.debug("Restored bot: " + name + " at " + location);
    }

    public void validateUserBotNames(UUID spawnerUuid, String spawnerName) {
        if (activePlayers.isEmpty()) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (FakePlayer fp : activePlayers.values()) {
            if (!spawnerUuid.equals(fp.getSpawnedByUuid())) continue;
            if (!fp.getName().startsWith("ubot_")) continue;

            String current = fp.getDisplayName();

            if (!PLACEHOLDER_PATTERN.matcher(current).find()) continue;

            String botName = fp.getName();
            int lastUs = botName.lastIndexOf('_');
            int idx = 1;
            if (lastUs > 0 && lastUs < botName.length() - 1) {
                try {
                    idx = Integer.parseInt(botName.substring(lastUs + 1));
                } catch (NumberFormatException ignored) {
                    idx = 1;
                }
            }
            String rawDisplay = Config.userBotNameFormat()
                    .replace("{spawner}", spawnerName)
                    .replace("{num}", String.valueOf(idx))
                    .replace("{bot_name}", fp.getName());
            fp.setRawDisplayName(rawDisplay);
            String newDisplay = finalizeDisplayName(rawDisplay, fp.getName());
            fp.setDisplayName(newDisplay);

            if (Config.tabListEnabled()) {
                for (Player p : online) PacketHelper.sendTabListDisplayNameUpdate(p, fp);
            }
            FppLogger.warn("[FPP] Repaired placeholder name for bot '"
                    + fp.getName()
                    + "' (owner: "
                    + spawnerName
                    + ") → '"
                    + newDisplay
                    + "'");
        }
    }

    public void removeAll() {
        removeAll("gui_reset_all");
    }

    public void removeAll(String reason) {
        if (activePlayers.isEmpty()) {
            FppLogger.debug(
                    "NMS-BOT", Config.debugNmsBot(), "removeAll() called but no active bots (reason: " + reason + ")");
            return;
        }

        FppLogger.info("Bulk despawning " + activePlayers.size() + " bot(s) - reason: " + reason);

        List<FakePlayer> toRemove = new ArrayList<>(activePlayers.values());

        // Snapshot inventory + XP for ALL bots BEFORE clearing maps or removing entities.
        // This ensures /fpp despawn all preserves items just like single-bot despawn does.
        boolean preserveInventoryOnDespawn = !Config.dropItemsOnDespawn();
        int snapshotCount = 0;
        for (FakePlayer fp : toRemove) {
            if (renamingBotIds.contains(fp.getUuid())) continue;
            if (isExplicitUuidSpawn(fp)) continue;
            if (!preserveInventoryOnDespawn && !hasResolvedSkin(fp)) continue;
            Player snapBody = fp.getPhysicsEntity();
            if (snapBody != null && snapBody.isOnline()) {
                String botName = fp.getName();
                DespawnSnapshot snap = new DespawnSnapshot(
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getContents())
                                : new ItemStack[41],
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getArmorContents())
                                : new ItemStack[4],
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getExtraContents())
                                : new ItemStack[1],
                        preserveInventoryOnDespawn ? snapBody.getTotalExperience() : 0,
                        preserveInventoryOnDespawn ? snapBody.getLevel() : 0,
                        preserveInventoryOnDespawn ? snapBody.getExp() : 0f,
                        fp.getResolvedSkin() != null ? fp.getResolvedSkin().getValue() : null,
                        fp.getResolvedSkin() != null ? fp.getResolvedSkin().getSignature() : null);
                despawnSnapshots.put(botName.toLowerCase(), snap);
                persistDespawnSnapshot(botName.toLowerCase(), snap);
                Config.debug("[DespawnSnapshot] Saved despawn state for '" + botName + "' (bulk despawn).");
                snapshotCount++;
            }
        }
        FppLogger.debug(
                "NMS-BOT",
                Config.debugNmsBot(),
                "Saved " + snapshotCount + "/" + toRemove.size() + " bot inventory snapshots");

        for (FakePlayer fp : toRemove) {
            unregisterBotState(fp, "DELETED");
        }

        for (int i = 0; i < toRemove.size(); i++) {
            FakePlayer fp = toRemove.get(i);

            final FakePlayer target = fp;
            final boolean explicitUuidSpawn = isExplicitUuidSpawn(target);
            Runnable doVisualRemove = () -> {
                if (!explicitUuidSpawn && Config.dropItemsOnDespawn()) {
                    dropBotContents(target);
                    FppLogger.debug("NMS-BOT", Config.debugNmsBot(), "Dropped contents for '" + target.getName() + "'");
                }

                String despawnName = resolveDespawnDisplayName(target);
                despawningBotIds.put(target.getUuid(), "");
                FppLogger.debug(
                        "NMS-BOT",
                        Config.debugNmsBot(),
                        "Bulk removing '" + target.getName() + "' (reason: " + reason + ")");

                if (!renamingBotIds.contains(target.getUuid())) {
                    fireSyntheticQuit(target, PlayerQuitEvent.QuitReason.DISCONNECTED);
                }
                try {
                    if (explicitUuidSpawn) {
                        FakePlayerBody.removeAllWithoutSaving(target, reason);
                    } else {
                        FakePlayerBody.removeAll(target, reason);
                    }
                } finally {
                    restoreExplicitUuidPlayerData(target);
                    restoreExplicitUuidOperator(target);
                    clearDespawningNextTick(target.getUuid());
                }
                if (chunkLoader != null) {
                    chunkLoader.releaseForBot(target);
                }

                List<Player> snapshot = new ArrayList<>(Bukkit.getOnlinePlayers());
                PacketHelper.broadcastTabListRemove(target, snapshot);

                var vc2 = plugin.getVelocityChannel();
                if (vc2 != null) vc2.broadcastBotDespawn(target.getUuid());
                FppLogger.debug(
                        "NMS-BOT",
                        Config.debugNmsBot(),
                        "Removed bot: " + target.getName() + " (reason: " + reason + ")");
            };

            Player body = target.getPlayer();
            if (body != null) FppScheduler.runAtEntity(plugin, body, doVisualRemove);
            else FppScheduler.runSync(plugin, doVisualRemove);
        }

        final long saveDelay = 20L;
        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    if (persistence != null && Config.persistOnRestart()) {
                        persistence.saveActiveListAsync(activePlayers.values());
                    }
                },
                saveDelay);

        Config.debug("Staggered visual removal of " + toRemove.size() + " fake player(s).");
    }

    private static void dropBotContents(FakePlayer target) {
        Player bot = target.getPhysicsEntity();
        if (bot == null || !bot.isOnline()) return;

        Location loc = bot.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        for (ItemStack item : bot.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                world.dropItemNaturally(loc, item);
            }
        }
        bot.getInventory().clear();

        int xp = bot.getTotalExperience();
        if (xp > 0) {
            world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(xp));
            bot.setTotalExperience(0);
            bot.setLevel(0);
            bot.setExp(0f);
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) return new ItemStack[0];
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) clone[i] = contents[i].clone();
        }
        return clone;
    }

    private static void applyDespawnSnapshot(Player bot, DespawnSnapshot snap) {
        PlayerInventory inv = bot.getInventory();
        if (snap.mainContents().length > 0) inv.setContents(snap.mainContents());
        if (snap.armorContents().length > 0) inv.setArmorContents(snap.armorContents());
        if (snap.extraContents().length > 0) inv.setExtraContents(snap.extraContents());
        bot.setTotalExperience(0);
        bot.setLevel(0);
        bot.setExp(0f);
        bot.setLevel(snap.xpLevel());
        bot.setExp(Math.max(0f, Math.min(1f, snap.xpProgress())));
        bot.setTotalExperience(snap.xpTotal());
    }

    private void applyDespawnSnapshotSkin(FakePlayer fp) {
        DespawnSnapshot snap = despawnSnapshots.get(fp.getName().toLowerCase());
        if (snap == null || snap.skinTexture() == null || snap.skinTexture().isBlank()) return;

        fp.setResolvedSkin(new SkinProfile(snap.skinTexture(), snap.skinSignature(), "despawn:" + fp.getName()));
        Config.debugSkin("Restored despawned skin for bot '" + fp.getName() + "'");
    }

    private void persistActiveSkin(FakePlayer fp) {
        if (db == null) return;
        SkinProfile skin = fp.getResolvedSkin();
        if (skin == null || !skin.isValid()) return;
        db.updateBotSkin(fp.getUuid().toString(), skin.getValue(), skin.getSignature());
    }

    private static boolean hasResolvedSkin(FakePlayer fp) {
        SkinProfile skin = fp != null ? fp.getResolvedSkin() : null;
        return skin != null && skin.isValid();
    }

    // ── Despawn snapshot persistence ─────────────────────────────────────────

    /**
     * Called once from {@code FakePlayerPlugin.onEnable()} after the DB manager is wired in.
     * Loads any persisted despawn snapshots into the in-memory map so bots whose inventory was saved
     * before a restart can still be restored when they are spawned again.
     */
    public void initDespawnSnapshots() {
        despawnSnapshotFile = new File(plugin.getDataFolder(), "data" + File.separator + "despawn-snapshots.yml");

        // 1. Try DB first (primary store)
        if (db != null) {
            try {
                List<DatabaseManager.DespawnSnapshotRow> rows = db.loadDespawnSnapshotsForServer(Config.serverId());
                for (DatabaseManager.DespawnSnapshotRow row : rows) {
                    DespawnSnapshot snap = deserializeSlots(
                            row.inventoryData(),
                            row.xpTotal(),
                            row.xpLevel(),
                            row.xpProgress(),
                            row.skinTexture(),
                            row.skinSignature());
                    if (snap != null) despawnSnapshots.put(row.botName().toLowerCase(), snap);
                }
                if (!rows.isEmpty()) {
                    Config.debugDatabase("[DespawnSnapshot] Loaded " + rows.size() + " snapshot(s) from DB.");
                }
            } catch (Exception e) {
                FppLogger.warn("[DespawnSnapshot] Failed to load from DB: " + e.getMessage());
            }
            return; // DB is authoritative - skip YAML
        }

        // 2. YAML fallback
        if (!despawnSnapshotFile.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(despawnSnapshotFile);
            ConfigurationSection sec = yaml.getConfigurationSection("snapshots");
            if (sec == null) return;
            for (String key : sec.getKeys(false)) {
                ConfigurationSection entry = sec.getConfigurationSection(key);
                if (entry == null) continue;
                String invData = entry.getString("inventory-data", "");
                int xpTotal = entry.getInt("xp-total", 0);
                int xpLevel = entry.getInt("xp-level", 0);
                float xpProgress = (float) entry.getDouble("xp-progress", 0.0);
                String skinTexture = entry.getString("skin-texture", null);
                String skinSignature = entry.getString("skin-signature", null);
                DespawnSnapshot snap =
                        deserializeSlots(invData, xpTotal, xpLevel, xpProgress, skinTexture, skinSignature);
                if (snap != null) despawnSnapshots.put(key.toLowerCase(), snap);
            }
            if (!sec.getKeys(false).isEmpty()) {
                Config.debugDatabase(
                        "[DespawnSnapshot] Loaded " + sec.getKeys(false).size() + " snapshot(s) from YAML.");
            }
        } catch (Exception e) {
            FppLogger.warn("[DespawnSnapshot] Failed to load YAML: " + e.getMessage());
        }
    }

    /**
     * Persists a snapshot to the DB (primary) or YAML fallback (async, best-effort).
     */
    private void persistDespawnSnapshot(String botNameLower, DespawnSnapshot snap) {
        String invData = serializeSlots(snap);
        String serverId = Config.serverId();

        if (db != null) {
            db.saveDespawnSnapshot(
                    botNameLower,
                    serverId,
                    invData,
                    snap.xpTotal(),
                    snap.xpLevel(),
                    snap.xpProgress(),
                    snap.skinTexture(),
                    snap.skinSignature());
            return;
        }

        // YAML fallback - write async
        final File yamlFile = despawnSnapshotFile;
        if (yamlFile == null) return;
        final String invDataFinal = invData;
        final int xpT = snap.xpTotal(), xpL = snap.xpLevel();
        final float xpP = snap.xpProgress();
        final String skinTex = snap.skinTexture(), skinSig = snap.skinSignature();
        FppScheduler.runAsync(plugin, () -> {
            try {
                YamlConfiguration yaml =
                        yamlFile.exists() ? YamlConfiguration.loadConfiguration(yamlFile) : new YamlConfiguration();
                String path = "snapshots." + botNameLower;
                yaml.set(path + ".inventory-data", invDataFinal);
                yaml.set(path + ".xp-total", xpT);
                yaml.set(path + ".xp-level", xpL);
                yaml.set(path + ".xp-progress", (double) xpP);
                yaml.set(path + ".skin-texture", skinTex);
                yaml.set(path + ".skin-signature", skinSig);
                yaml.set(path + ".saved-at", System.currentTimeMillis());
                yaml.save(yamlFile);
            } catch (Exception e) {
                FppLogger.warn("[DespawnSnapshot] YAML save failed: " + e.getMessage());
            }
        });
    }

    /**
     * Removes a snapshot from DB/YAML after it has been restored (called on respawn).
     */
    private void removeDespawnSnapshotPersistent(String botNameLower) {
        String serverId = Config.serverId();
        if (db != null) {
            db.deleteDespawnSnapshot(botNameLower, serverId);
            return;
        }

        final File yamlFile = despawnSnapshotFile;
        if (yamlFile == null || !yamlFile.exists()) return;
        FppScheduler.runAsync(plugin, () -> {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
                yaml.set("snapshots." + botNameLower, null);
                yaml.save(yamlFile);
            } catch (Exception e) {
                FppLogger.warn("[DespawnSnapshot] YAML delete failed: " + e.getMessage());
            }
        });
    }

    /**
     * Serialises a {@link DespawnSnapshot} to a compact pipe-delimited string.
     * Format: {@code slot:base64|slot:base64|…} - uses only chars safe from splitting.
     * Uses {@code mainContents} (all 41 slots) which already contains armor and offhand.
     */
    private static String serializeSlots(DespawnSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        ItemStack[] all = snap.mainContents();
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null && all[i].getType() != Material.AIR) {
                try {
                    String b64 = Base64.getEncoder().encodeToString(all[i].serializeAsBytes());
                    if (sb.length() > 0) sb.append('|');
                    sb.append(i).append(':').append(b64);
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }

    /**
     * Deserialises the pipe-delimited slot string back into a {@link DespawnSnapshot}.
     * Returns {@code null} if the data is blank or entirely corrupt.
     */
    private static DespawnSnapshot deserializeSlots(
            String data, int xpTotal, int xpLevel, float xpProgress, String skinTexture, String skinSignature) {
        ItemStack[] main = new ItemStack[41];
        if (data != null && !data.isBlank()) {
            for (String token : data.split("\\|")) {
                int colon = token.indexOf(':');
                if (colon < 1) continue;
                try {
                    int slot = Integer.parseInt(token.substring(0, colon));
                    if (slot < 0 || slot >= main.length) continue;
                    byte[] bytes = Base64.getDecoder().decode(token.substring(colon + 1));
                    main[slot] = ItemStack.deserializeBytes(bytes);
                } catch (Exception ignored) {
                }
            }
        }
        // Build armor (slots 36-39) and extra (slot 40) sub-arrays for applyDespawnSnapshot
        ItemStack[] armor = new ItemStack[] {main[36], main[37], main[38], main[39]};
        ItemStack[] extra = new ItemStack[] {main[40]};
        boolean hasContent =
                xpTotal > 0 || xpLevel > 0 || xpProgress > 0f || (skinTexture != null && !skinTexture.isBlank());
        for (ItemStack item : main) {
            if (item != null && item.getType() != Material.AIR) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) return null;
        return new DespawnSnapshot(main, armor, extra, xpTotal, xpLevel, xpProgress, skinTexture, skinSignature);
    }

    public boolean delete(String name) {
        return delete(name, "unspecified");
    }

    public boolean delete(String name, String reason) {
        return deleteInternal(name, false, false, reason, null);
    }

    public boolean deleteForLoginHandoff(String name, @Nullable Runnable onComplete) {
        return deleteInternal(name, true, true, "login_handoff", onComplete);
    }

    private boolean deleteInternal(
            String name, boolean fastVisualRemove, boolean suppressLeaveBroadcast, @Nullable Runnable onComplete) {
        FppLogger.warn("deleteInternal called without explicit reason for '" + name
                + "' - defaulting to 'unspecified'. " + "Please update the caller to pass a descriptive reason.");
        return deleteInternal(name, fastVisualRemove, suppressLeaveBroadcast, "unspecified", onComplete);
    }

    private boolean deleteInternal(
            String name,
            boolean fastVisualRemove,
            boolean suppressLeaveBroadcast,
            String reason,
            @Nullable Runnable onComplete) {
        FakePlayer fp = getByName(name);
        if (fp == null) {
            FppLogger.debug(
                    "NMS-BOT",
                    Config.debugNmsBot(),
                    "Despawn failed: bot '" + name + "' not found (reason: " + reason + ")");
            return false;
        }

        FppLogger.debug(
                "NMS-BOT",
                Config.debugNmsBot(),
                "Despawning bot '" + name + "' (uuid=" + fp.getUuid() + ") - reason: " + reason);

        // Fire API despawn event before any state is removed.
        var fppApi = plugin.getFppApi();
        if (fppApi != null) {
            FppBotDespawnEvent despawnEvt = new FppBotDespawnEvent(new FppBotImpl(fp));
            Bukkit.getPluginManager().callEvent(despawnEvt);
        }

        final FakePlayer target = fp;
        final String botName = target.getName();
        final boolean explicitUuidSpawn = isExplicitUuidSpawn(target);

        // Snapshot inventory + XP BEFORE removing from maps or despawning entity.
        // Must happen synchronously here (not in the delayed task) so bulk operations
        // like /fpp despawn all don't race - by the time the 1-tick delay fires,
        // earlier bots' entities may already be gone and snapBody.isOnline() fails.
        boolean preserveInventoryOnDespawn = !Config.dropItemsOnDespawn();
        if (!explicitUuidSpawn
                && (preserveInventoryOnDespawn || hasResolvedSkin(target))
                && !renamingBotIds.contains(target.getUuid())
                && !suppressDespawnSnapshotIds.contains(target.getUuid())) {
            Player snapBody = target.getPhysicsEntity();
            if (snapBody != null && snapBody.isOnline()) {
                DespawnSnapshot snap = new DespawnSnapshot(
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getContents())
                                : new ItemStack[41],
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getArmorContents())
                                : new ItemStack[4],
                        preserveInventoryOnDespawn
                                ? cloneContents(snapBody.getInventory().getExtraContents())
                                : new ItemStack[1],
                        preserveInventoryOnDespawn ? snapBody.getTotalExperience() : 0,
                        preserveInventoryOnDespawn ? snapBody.getLevel() : 0,
                        preserveInventoryOnDespawn ? snapBody.getExp() : 0f,
                        target.getResolvedSkin() != null
                                ? target.getResolvedSkin().getValue()
                                : null,
                        target.getResolvedSkin() != null
                                ? target.getResolvedSkin().getSignature()
                                : null);
                despawnSnapshots.put(botName.toLowerCase(), snap);
                persistDespawnSnapshot(botName.toLowerCase(), snap);
                Config.debug("[DespawnSnapshot] Saved despawn state for '" + botName + "'.");
            }
        }

        unregisterBotState(target, "DELETED");
        if (!explicitUuidSpawn) target.clearMetadata();

        FppLogger.debug(
                "NMS-BOT",
                Config.debugNmsBot(),
                "Bot state unregistered: '" + botName + "' (explicitUuid=" + explicitUuidSpawn + ")");

        Runnable doVisualRemove = () -> {
            try {
                if (!explicitUuidSpawn && Config.dropItemsOnDespawn()) {
                    dropBotContents(target);
                    FppLogger.debug("NMS-BOT", Config.debugNmsBot(), "Dropped bot contents for '" + botName + "'");
                }

                String despawnName = resolveDespawnDisplayName(target);
                despawningBotIds.put(target.getUuid(), "");

                FppLogger.debug(
                        "NMS-BOT",
                        Config.debugNmsBot(),
                        "Bot '" + botName + "' marked as despawning (reason: " + reason + ")");

                if (!renamingBotIds.contains(target.getUuid()) && !suppressLeaveBroadcast) {
                    fireSyntheticQuit(target, PlayerQuitEvent.QuitReason.DISCONNECTED);
                }

                try {
                    if (explicitUuidSpawn || fastVisualRemove) {
                        FakePlayerBody.removeAllFast(target, reason);
                    } else {
                        FakePlayerBody.removeAll(target, reason);
                    }
                    FppLogger.debug(
                            "NMS-BOT",
                            Config.debugNmsBot(),
                            "Body removed for '" + botName + "' (fast=" + fastVisualRemove + ")");
                } finally {
                    restoreExplicitUuidPlayerData(target);
                    restoreExplicitUuidOperator(target);
                    clearDespawningNextTick(target.getUuid());
                }
                if (chunkLoader != null) {
                    chunkLoader.releaseForBot(target);
                    FppLogger.debug("NMS-BOT", Config.debugNmsBot(), "Chunk loader released for '" + botName + "'");
                }

                List<Player> snapshot = new ArrayList<>(Bukkit.getOnlinePlayers());
                PacketHelper.broadcastTabListRemove(target, snapshot);
                FppLogger.debug(
                        "NMS-BOT",
                        Config.debugNmsBot(),
                        "Tab-list entry removed for '" + botName + "' from " + snapshot.size() + " player(s)");

                if (db != null) {
                    db.recordRemoval(target.getUuid(), "DELETED");
                    FppLogger.debug("NMS-BOT", Config.debugNmsBot(), "Database removal recorded for '" + botName + "'");
                }

                var vc2 = plugin.getVelocityChannel();
                if (vc2 != null) {
                    vc2.broadcastBotDespawn(target.getUuid());
                    FppLogger.debug(
                            "NMS-BOT",
                            Config.debugNmsBot(),
                            "Velocity/VelocityChannel3 broadcasted despawn for '" + botName + "'");
                }
                FppLogger.info("Despawned bot '" + botName + "' (reason: " + reason + ")");
                if (persistence != null && Config.persistOnRestart()) {
                    persistence.saveActiveListAsync(activePlayers.values());
                }
            } finally {
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        Player body = target.getPlayer();
        if (body != null) FppScheduler.runAtEntity(plugin, body, doVisualRemove);
        else FppScheduler.runSync(plugin, doVisualRemove);

        return true;
    }

    public void removeAllSync() {
        removeAllSync(false);
    }

    public void removeAllSyncFast() {
        removeAllSync(true);
    }

    private void removeAllSync(boolean fastShutdown) {
        removeAllSync(fastShutdown, "plugin_shutdown");
    }

    private void removeAllSync(boolean fastShutdown, String reason) {
        if (activePlayers.isEmpty()) {
            FppLogger.debug(
                    "NMS-BOT",
                    Config.debugNmsBot(),
                    "removeAllSync() called but no active bots (reason: " + reason + ")");
            return;
        }

        FppLogger.debug(
                "NMS-BOT",
                Config.debugNmsBot(),
                "Shutdown removing " + activePlayers.size() + " bot(s) (fast=" + fastShutdown + ", reason: " + reason
                        + ")");

        List<FakePlayer> toRemove = new ArrayList<>(activePlayers.values());
        activePlayers.clear();
        nameIndex.clear();
        usedNames.clear();
        entityIdIndex.clear();

        botHeadRotation.clear();
        botSpawnRotation.clear();
        lastSentVisualRotation.clear();

        List<Player> snapshot = new ArrayList<>(Bukkit.getOnlinePlayers());
        int removedCount = 0;

        for (FakePlayer fp : toRemove) {
            boolean explicitUuidSpawn = isExplicitUuidSpawn(fp);
            despawningBotIds.put(fp.getUuid(), "");
            fireSyntheticQuit(fp, PlayerQuitEvent.QuitReason.DISCONNECTED);

            try {
                if (explicitUuidSpawn || fastShutdown) {
                    FakePlayerBody.removeAllFast(fp, reason);
                } else {
                    FakePlayerBody.removeAll(fp, reason);
                }
                removedCount++;
            } finally {
                restoreExplicitUuidPlayerData(fp);
                restoreExplicitUuidOperator(fp);
            }
            if (chunkLoader != null) chunkLoader.releaseForBot(fp);

            PacketHelper.broadcastTabListRemove(fp, snapshot);

            despawningBotIds.remove(fp.getUuid());
            syntheticQuitBotIds.remove(fp.getUuid());
            FppLogger.debug(
                    "NMS-BOT",
                    Config.debugNmsBot(),
                    "Shutdown removed bot: " + fp.getName() + " (reason: " + reason + ")");
        }

        FppLogger.info("Shutdown: removed " + removedCount + "/" + toRemove.size() + " bot(s)."
                + (fastShutdown ? " (fast)" : "") + " reason: " + reason);
    }

    public void applyBodyConfig() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());

        if (physicalBodiesEnabled()) {

            for (FakePlayer fp : new ArrayList<>(activePlayers.values())) {
                Player body = fp.getPlayer();

                if (body != null && body.isValid()) {

                    body.setInvulnerable(false);
                    body.setCollidable(Config.bodyPushable());
                    try {
                        var attr = body.getAttribute(AttributeCompat.MAX_HEALTH);
                        if (attr != null) {
                            double hp = Config.maxHealth();
                            attr.setBaseValue(hp);
                            if (body.getHealth() > hp) body.setHealth(hp);
                        }
                    } catch (Exception ignored) {
                    }

                } else if (fp.isBodyless()) {

                    Location loc = fp.getSpawnLocation();
                    if (loc == null || loc.getWorld() == null) continue;

                    bodyTransitionBots.add(fp.getUuid());
                    fp.setBodyless(false);
                    try {
                        FakePlayerBody.resolveAndFinish(plugin, fp, loc, () -> {
                            Player newBody = FakePlayerBody.spawn(fp, loc, fp.getEffectivePing());
                            if (newBody != null) {
                                stampFakePlayerMarker(newBody, fp);
                                fp.setPacketProfileName(fp.getName());

                                if (Config.tabListEnabled()) {
                                    for (Player p : online) {
                                        PacketHelper.sendTabListRefreshEntry(p, fp);
                                    }
                                }

                                PlayerJoinListener.stampFirstPlayed(newBody);
                                FppLogger.info("BodyConfig: body shown for '" + fp.getName() + "'");
                            } else {
                                fp.setBodyless(true);
                                FppLogger.warn("BodyConfig: failed to show body for '" + fp.getName() + "'");
                            }
                        });
                    } finally {
                        bodyTransitionBots.remove(fp.getUuid());
                    }
                }
            }

            return;
        }

        for (FakePlayer fp : new ArrayList<>(activePlayers.values())) {
            Player body = fp.getPlayer();
            if (body == null || !body.isOnline()) continue;

            fp.setSpawnLocation(body.getLocation());
            int entityId = body.getEntityId();

            entityIdIndex.remove(entityId);
            fp.setPhysicsEntity(null);
            fp.setBodyless(true);

            bodyTransitionBots.add(fp.getUuid());
            boolean explicitUuidSpawn = isExplicitUuidSpawn(fp);
            FppScheduler.runAtEntity(plugin, body, () -> {
                try {
                    if (explicitUuidSpawn) NmsPlayerSpawner.removeFakePlayerFast(body);
                    else NmsPlayerSpawner.removeFakePlayer(body);
                } finally {
                    restoreExplicitUuidPlayerData(fp);
                    restoreExplicitUuidOperator(fp);
                    bodyTransitionBots.remove(fp.getUuid());
                }

                if (Config.tabListEnabled()) {
                    for (Player p : online) PacketHelper.sendTabListRefreshEntry(p, fp);
                }
                FppLogger.info("BodyConfig: body hidden for '" + fp.getName() + "', now tab-list only");
            });
        }
    }

    public void removeByName(String name) {
        removeByName(name, null);
    }

    /**
     * @param effectFallbackLoc where to play the despawn effect if the bot's entity reference is
     *     already null by the time this runs (e.g. the death-without-respawn path nulls it first).
     */
    public void removeByName(String name, @Nullable Location effectFallbackLoc) {
        FakePlayer fp = getByName(name);
        if (fp != null) {
            unregisterBotState(fp, "DIED", effectFallbackLoc);
            restoreExplicitUuidSourceState(fp);
            fp.clearMetadata();
            Config.debug("Removed from registry: " + name);
        }
        if (persistence != null && Config.persistOnRestart()) {
            persistence.saveActiveListAsync(activePlayers.values());
        }
    }

    private void unregisterBotState(FakePlayer fp, String removalReason) {
        unregisterBotState(fp, removalReason, null);
    }

    private void unregisterBotState(FakePlayer fp, String removalReason, @Nullable Location effectFallbackLoc) {
        if (fp == null) return;

        UUID uuid = fp.getUuid();
        String name = fp.getName();

        activePlayers.remove(uuid, fp);
        nameIndex.remove(name.toLowerCase(), fp);
        usedNames.remove(name);
        suppressDespawnSnapshotIds.remove(uuid);

        // Central cleanup: remove the tag on every despawn/death path (some remove the body
        // directly via NmsPlayerSpawner, bypassing FakePlayerBody.removeAll).
        FakePlayerBody.removeNametag(fp);
        BotAutoMount.forget(fp.getUuid());

        Player body = fp.getPhysicsEntity();
        if (body != null) {
            entityIdIndex.remove(body.getEntityId(), fp);
            playDespawnEffect(body.isOnline() ? body.getLocation() : effectFallbackLoc);
        } else {
            // Some despawn paths (e.g. death-without-respawn) null the entity reference before
            // reaching here - the caller-supplied fallback (captured while the entity was still
            // valid) keeps the effect playing at the right spot instead of silently no-op'ing.
            playDespawnEffect(effectFallbackLoc);
        }

        botHeadRotation.remove(uuid);
        botSpawnRotation.remove(uuid);
        lastSentVisualRotation.remove(uuid);
        actionLockedBots.remove(uuid);
        navLockedBots.remove(uuid);
        trackedFallDistance.remove(uuid);
        lastFallY.remove(uuid);
        wasOnGround.remove(uuid);
        bodyTransitionBots.remove(uuid);

        var pathfinding = plugin.getPathfindingService();
        if (pathfinding != null) pathfinding.cancel(uuid);

        var moveCmd = plugin.getMoveCommand();
        if (moveCmd != null) moveCmd.cleanupBot(uuid);
        var leftClickCmd = plugin.getLeftClickCommand();
        if (leftClickCmd != null) leftClickCmd.stopClicking(uuid);
        var rightClickCmd = plugin.getRightClickCommand();
        if (rightClickCmd != null) rightClickCmd.stopClicking(uuid);
        BotClient.forget(uuid);
        var findCmd = plugin.getFindCommand();
        if (findCmd != null) findCmd.cleanupBot(uuid);
        var clearCmd = plugin.getClearCommand();
        if (clearCmd != null) clearCmd.cleanupBot(uuid);
        var pve = plugin.getPveController();
        if (pve != null) pve.cleanupBot(uuid);
        var autoEat = plugin.getAutoEatController();
        if (autoEat != null) autoEat.cleanupBot(uuid);
        BotClickDispatcher.forget(uuid);

        if (chunkLoader != null) chunkLoader.releaseForBot(fp);
        if (db != null && removalReason != null) db.recordRemoval(uuid, removalReason);
    }

    /** Small poof cloud + sound at the bot's location - plays on every despawn/delete/death-removal path. */
    private void playDespawnEffect(@Nullable Location at) {
        if (at == null) return;
        try {
            Location loc = at.clone().add(0, 1.0, 0);
            World world = loc.getWorld();
            if (world == null) return;
            world.spawnParticle(Particle.POOF, loc, 25, 0.3, 0.5, 0.3, 0.02);
            world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.4f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Guards every real viewer's connection and, when the tab list is off, explicitly unlists every
     * bot for them. Needed beyond the join hook because viewers can already be online when the
     * plugin (re)loads, and the server's own broadcast of a new bot's entry races our other packets.
     */
    public void enforceTabPolicy() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (activePlayers.containsKey(viewer.getUniqueId())) continue;
            PacketHelper.installViewerGuard(viewer);
            unlistAllFor(viewer);
        }
    }

    /** Applies the current tab-list policy for one bot to every real viewer (any UUID it uses). */
    public void unlistBotEverywhere(FakePlayer fp) {
        if (fp == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (activePlayers.containsKey(viewer.getUniqueId())) continue;
            applyTabEntry(viewer, fp);
        }
    }

    private void unlistAllFor(Player viewer) {
        for (FakePlayer fp : activePlayers.values()) applyTabEntry(viewer, fp);
    }

    /**
     * Sends {@code viewer} this bot's tab state under the current policy: unlisted when the tab list
     * is off, otherwise listed as {@code [Bot] - <botname>}. Covers both the bot's logical UUID and
     * its live body UUID, since the client keys entries by whichever the server broadcast.
     */
    private void applyTabEntry(Player viewer, FakePlayer fp) {
        boolean show = Config.tabListEnabled();
        String name = fp.getTabListName();
        PacketHelper.sendTabEntry(viewer, fp.getUuid(), show, name);
        Player body = fp.getPlayer();
        if (body != null && !body.getUniqueId().equals(fp.getUuid())) {
            PacketHelper.sendTabEntry(viewer, body.getUniqueId(), show, name);
        }
    }

    public void syncToPlayer(Player player) {
        FakePlayerBody.syncHiddenNamesTo(player);
        PacketHelper.installViewerGuard(player);
        if (!Config.tabListEnabled()) {
            unlistAllFor(player);
            for (FakePlayer fp : activePlayers.values()) {
                PacketHelper.sendTabListUpdateListed(player, fp, false);
            }
            return;
        }
        unlistAllFor(player);
        for (FakePlayer fp : activePlayers.values()) {

            PacketHelper.sendTabListAdd(player, fp);
            PacketHelper.sendTabListLatencyUpdate(player, fp);
        }
    }

    /**
     * Sets the server-side list name (used for every tab packet the server itself generates, e.g.
     * on a viewer's join) to {@code bot - <botname>}, or clears it when the tab list is off.
     */
    public void applyTabListName(FakePlayer fp) {
        Player body = fp == null ? null : fp.getPlayer();
        if (body == null) return;
        try {
            body.playerListName(
                    Config.tabListEnabled() ? net.kyori.adventure.text.Component.text(fp.getTabListName()) : null);
        } catch (Throwable t) {
            FppLogger.debug("applyTabListName failed for " + fp.getName() + ": " + t);
        }
    }

    public void applyTabListConfig() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty() || activePlayers.isEmpty()) return;

        boolean listed = Config.tabListEnabled();

        // The NMS "listed" flag alone only affects packets the server builds later; viewers must be
        // told directly, otherwise toggling the option on changes nothing for people already online.
        for (Player p : online) {
            if (activePlayers.containsKey(p.getUniqueId())) continue;
            PacketHelper.installViewerGuard(p);
            unlistAllFor(p);
        }

        for (FakePlayer fp : activePlayers.values()) {
            Player bot = fp.getPlayer();
            applyTabListName(fp);
            if (bot != null) {
                boolean nmsOk = NmsPlayerSpawner.setListed(bot, listed);
                if (!nmsOk) {
                    for (Player p : online) {
                        PacketHelper.sendTabListUpdateListed(p, fp, listed);
                    }
                }
            }
        }

        if (listed) {
            for (FakePlayer fp : activePlayers.values()) {
                for (Player p : online) {
                    PacketHelper.sendTabListRefreshEntry(p, fp);
                }
            }
            Config.debug("applyTabListConfig: refreshed " + activePlayers.size() + " bots in tab list.");
        } else {
            for (FakePlayer fp : activePlayers.values()) {
                for (Player p : online) {
                    PacketHelper.sendTabListUpdateListed(p, fp, false);
                }
            }

            Config.debug("applyTabListConfig: unlisted " + activePlayers.size() + " bots from tab list.");
        }
    }

    public void validateEntities() {

        Set<String> activeNames =
                activePlayers.values().stream().map(FakePlayer::getName).collect(Collectors.toSet());

        for (FakePlayer fp : activePlayers.values()) {
            Entity body = fp.getPhysicsEntity();

            if (!physicalBodiesEnabled()) {
                if (body != null && body.isValid()) {
                    try {
                        body.remove();
                    } catch (Exception ignored) {
                    }
                    entityIdIndex.remove(body.getEntityId());
                    fp.setPhysicsEntity(null);
                }
                continue;
            }

            if (fp.isBodyless()) continue;

            if (body != null && body.isValid()) continue;

            Config.debug("validateEntities: body of '" + fp.getName() + "' invalid - attempting respawn.");

            fp.setPhysicsEntity(null);

            Location loc = fp.getSpawnLocation();
            if (loc == null || loc.getWorld() == null) continue;

            FakePlayerBody.resolveAndFinish(plugin, fp, loc, () -> {
                Player newBody = FakePlayerBody.spawn(fp, loc, fp.getEffectivePing());
                if (newBody == null) return;

                stampFakePlayerMarker(newBody, fp);

                final FakePlayer target = fp;
                FppScheduler.runSyncLater(
                        plugin,
                        () -> {
                            for (Player p : Bukkit.getOnlinePlayers()) PacketHelper.sendTabListRefreshEntry(p, target);
                        },
                        2L);
            });
        }
    }

    public FakePlayer getByEntity(Entity entity) {

        FakePlayer fp = entityIdIndex.get(entity.getEntityId());
        if (fp != null) return fp;

        String botName = null;
        if (FAKE_PLAYER_KEY != null) {
            String marker = entity.getPersistentDataContainer().get(FAKE_PLAYER_KEY, PersistentDataType.STRING);
            if (marker != null && !marker.isBlank()) {
                String prefix = FakePlayerBody.VISUAL_PDC_VALUE + ":";
                botName = marker.startsWith(prefix) ? marker.substring(prefix.length()) : marker;
            }
        }

        if ((botName == null || botName.isBlank()) && entity instanceof Player player) {
            FakePlayer byUuid = getByUuid(player.getUniqueId());
            if (byUuid != null) {
                stampFakePlayerMarker(player, byUuid);
                return byUuid;
            }
            FakePlayer byName = getByName(player.getName());
            if (byName != null && byName.getUuid().equals(player.getUniqueId())) {
                stampFakePlayerMarker(player, byName);
                Config.debugNms("getByEntity: recovered '"
                        + byName.getName()
                        + "' by name/uuid after entity index miss - entityId="
                        + entity.getEntityId());
                return byName;
            }
        }

        if (botName == null || botName.isBlank()) return null;

        FakePlayer candidate = getByName(botName);
        if (candidate == null) return null;

        Entity oldBody = candidate.getPhysicsEntity();
        if (oldBody != null && oldBody.getEntityId() != entity.getEntityId()) {
            entityIdIndex.remove(oldBody.getEntityId());
        }

        if (entity instanceof Player player) {
            stampFakePlayerMarker(player, candidate);
            Config.debug("getByEntity: recovered '"
                    + botName
                    + "' via PDC after world-change - new entityId="
                    + entity.getEntityId());
            return candidate;
        }

        Config.debug("getByEntity: entity is not a Player, cannot recover bot: " + botName);
        return null;
    }

    public void stampFakePlayerMarker(Player player, FakePlayer fp) {
        if (player == null || fp == null || FAKE_PLAYER_KEY == null) return;
        try {
            Player oldBody = fp.getPhysicsEntity();
            if (oldBody != null && oldBody.getEntityId() != player.getEntityId()) {
                entityIdIndex.remove(oldBody.getEntityId(), fp);
                String oldWorld =
                        oldBody.getWorld() != null ? oldBody.getWorld().getName() : "null";
                String newWorld = player.getWorld() != null ? player.getWorld().getName() : "null";
                Config.debugNmsDamage("bot body registration moved: bot="
                        + fp.getName()
                        + " oldEntityId="
                        + oldBody.getEntityId()
                        + " oldWorld="
                        + oldWorld
                        + " newEntityId="
                        + player.getEntityId()
                        + " newWorld="
                        + newWorld);
            }
            fp.setPhysicsEntity(player);
            entityIdIndex.put(player.getEntityId(), fp);
            player.getPersistentDataContainer().remove(new NamespacedKey(plugin, "npc"));
            player.getPersistentDataContainer()
                    .set(
                            FAKE_PLAYER_KEY,
                            PersistentDataType.STRING,
                            FakePlayerBody.VISUAL_PDC_VALUE + ":" + fp.getName());
        } catch (Throwable ignored) {
        }
    }

    public FakePlayer getByUuid(UUID uuid) {
        if (uuid == null) return null;
        return activePlayers.get(uuid);
    }

    public void removeFromEntityIndex(int entityId) {
        entityIdIndex.remove(entityId);
    }

    public void registerEntityIndex(int entityId, FakePlayer fp) {
        entityIdIndex.put(entityId, fp);
    }

    public FakePlayer getByEntityId(int entityId) {
        return entityIdIndex.get(entityId);
    }

    public List<String> getActiveNames() {
        return activePlayers.values().stream().map(FakePlayer::getName).collect(Collectors.toList());
    }





    public FakePlayer getByName(String name) {
        if (name == null || name.isBlank()) return null;
        return nameIndex.get(name.toLowerCase());
    }

    /**
     * Changes a bot's visible display name (the floating name-tag name row, the entity display name
     * and the tab-list entry) without touching its login name or fb07 UUID - identity is immutable.
     * The mandatory "ʙᴏᴛ ʙʏ {owner}" disclosure row is preserved. The new name is persisted so it
     * survives restarts.
     */
    public void renameBot(FakePlayer bot, String newName) {
        if (bot == null || newName == null) return;
        String oldDisplay = bot.getDisplayName();
        if (oldDisplay != null && oldDisplay.equals(newName)) return;

        bot.setDisplayName(newName);
        bot.setRawDisplayName(newName);

        // Refresh the floating name-tag (name row) and the entity's own display name.
        FakePlayerBody.refreshNametag(bot);
        Player body = bot.getPlayer();
        if (body != null) {
            try {
                body.displayName(TextUtil.colorize(newName));
            } catch (Exception ignored) {
            }
        }

        if (Config.tabListEnabled() && body != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                PacketHelper.sendTabListDisplayNameUpdate(p, bot);
            }
        }

        // Persist: the YAML active-bot snapshot captures display-name on its next save; update the
        // DB active-bots row immediately so a restart from the database also keeps the new name.
        DatabaseManager db = plugin.getDatabaseManager();
        if (Config.databaseEnabled() && db != null) {
            db.updateBotDisplay(bot.getUuid().toString(), newName);
        }
    }

    public boolean isOnCooldown(UUID playerUuid) {
        int secs = Config.spawnCooldown();
        if (secs <= 0) return false;
        Long last = spawnCooldowns.get(playerUuid);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) / 1000L < secs;
    }

    public void requestNavJump(UUID botUuid) {
        navJumpHolding.put(botUuid, 5);
    }

    public void clearNavJump(UUID botUuid) {
        navJumpHolding.remove(botUuid);
    }





    // Reference-counted rather than a plain Set: with multiple task systems now able to run
    // concurrently on the same bot (mine + PVE + auto-eat + a manual attack, etc.), more than one can
    // hold this lock at once - a boolean set would let whichever one unlocks first wipe it out from
    // under everyone else still using it. A count only clears (and Head-AI resumes) once every holder
    // has released it.
    private final Map<UUID, Integer> actingBots = new ConcurrentHashMap<>();

    public void lockForAction(UUID botUuid, Location loc) {
        lockForAction(botUuid, loc, false);
    }

    public void lockForAction(UUID botUuid, Location loc, boolean lockPosition) {
        if (lockPosition) {
            actionLockedBots.put(botUuid, loc.clone());
        }
        actingBots.merge(botUuid, 1, Integer::sum);
        botHeadRotation.put(botUuid, new float[] {loc.getYaw(), loc.getPitch()});
        botSpawnRotation.put(botUuid, new float[] {loc.getYaw(), loc.getPitch()});
    }

    public void unlockAction(UUID botUuid) {
        actionLockedBots.remove(botUuid);
        actingBots.computeIfPresent(botUuid, (k, count) -> count > 1 ? count - 1 : null);
    }

    public boolean isActionLocked(UUID botUuid) {
        return actingBots.containsKey(botUuid);
    }

    public void updateActionLockRotation(UUID botUuid, float yaw, float pitch) {
        Location loc = actionLockedBots.get(botUuid);
        if (loc != null) {
            loc.setYaw(yaw);
            loc.setPitch(pitch);
        }
    }

    public void lockForNavigation(UUID botUuid) {
        navLockedBots.add(botUuid);
    }

    public void unlockNavigation(UUID botUuid) {
        navLockedBots.remove(botUuid);
    }



    public long getRemainingCooldown(UUID playerUuid) {
        int secs = Config.spawnCooldown();
        if (secs <= 0) return 0;
        Long last = spawnCooldowns.get(playerUuid);
        if (last == null) return 0;
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, secs - elapsed);
    }

    public void recordSpawnCooldown(UUID playerUuid) {
        spawnCooldowns.put(playerUuid, System.currentTimeMillis());
    }



    public Collection<FakePlayer> getActivePlayers() {
        return Collections.unmodifiableCollection(activePlayers.values());
    }

    public int getCount() {
        return activePlayers.size();
    }

    public List<FakePlayer> getBotsOwnedBy(UUID ownerUuid) {
        return activePlayers.values().stream()
                .filter(fp -> ownerUuid.equals(fp.getSpawnedByUuid()))
                .collect(Collectors.toList());
    }

    public boolean teleportBot(FakePlayer fp, Location destination) {
        Player body = fp.getPlayer();
        if (body == null || !body.isValid()) return false;
        var event = new FppBotTeleportEvent(new FppBotImpl(fp), body.getLocation(), destination);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        Location target = event.getTo().clone();
        FppScheduler.teleportAsync(body, target).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) refreshAfterTeleport(fp, body, target);
        });
        return true;
    }

    public void refreshAfterTeleport(FakePlayer fp, Player body, Location destination) {
        if (fp == null || body == null || destination == null) return;
        refreshAfterTeleport(fp, body, destination, 0);
    }

    private void refreshAfterTeleport(FakePlayer fp, Player body, Location destination, int attempt) {
        Player scheduledBody = resolveCurrentBody(fp, body);
        FppScheduler.runAtEntityLaterWithId(
                plugin,
                scheduledBody,
                () -> {
                    Player currentBody = resolveCurrentBody(fp, scheduledBody);
                    if (!activePlayers.containsKey(fp.getUuid()) || currentBody == null || !currentBody.isValid())
                        return;

                    Location current = currentBody.getLocation();
                    if (!isAtTeleportDestination(current, destination)) {
                        if (attempt < 4) {
                            Config.debugNmsDamage("bot teleport refresh delayed: bot="
                                    + fp.getName()
                                    + " attempt="
                                    + attempt
                                    + " entityId="
                                    + currentBody.getEntityId()
                                    + " current="
                                    + formatLocation(current)
                                    + " requested="
                                    + formatLocation(destination));
                            refreshAfterTeleport(fp, currentBody, destination, attempt + 1);
                            return;
                        }

                        Config.debugNmsDamage("bot teleport correction: bot="
                                + fp.getName()
                                + " entityId="
                                + currentBody.getEntityId()
                                + " current="
                                + formatLocation(current)
                                + " requested="
                                + formatLocation(destination));
                        FppScheduler.teleportAsync(currentBody, destination.clone())
                                .thenAccept(success -> {
                                    if (Boolean.TRUE.equals(success))
                                        refreshAfterTeleport(fp, currentBody, destination, 0);
                                });
                        return;
                    }

                    stampFakePlayerMarker(currentBody, fp);
                    fp.setSpawnLocation(current.clone());
                    String currentWorld =
                            current.getWorld() != null ? current.getWorld().getName() : "null";
                    Config.debugNmsDamage("bot teleport refresh: bot="
                            + fp.getName()
                            + " entityId="
                            + currentBody.getEntityId()
                            + " world="
                            + currentWorld
                            + " x="
                            + String.format("%.3f", current.getX())
                            + " y="
                            + String.format("%.3f", current.getY())
                            + " z="
                            + String.format("%.3f", current.getZ())
                            + " requestedWorld="
                            + (destination.getWorld() != null
                                    ? destination.getWorld().getName()
                                    : "null"));
                    NmsPlayerSpawner.refreshAfterTeleport(currentBody);
                    resetTransientBodyStateAfterTeleport(fp, currentBody, current);
                },
                1L);
    }

    private Player resolveCurrentBody(FakePlayer fp, Player fallback) {
        Player current = Bukkit.getPlayer(fp.getUuid());
        if (current != null && current.isOnline()) return current;
        return fallback;
    }

    private boolean isAtTeleportDestination(Location current, Location destination) {
        if (current == null || destination == null) return false;
        if (current.getWorld() == null || destination.getWorld() == null) return false;
        if (!current.getWorld().equals(destination.getWorld())) return false;
        return current.distanceSquared(destination) <= 1.0;
    }

    private String formatLocation(Location location) {
        if (location == null) return "null";
        String world = location.getWorld() != null ? location.getWorld().getName() : "null";
        return world
                + "@"
                + String.format("%.3f", location.getX())
                + ","
                + String.format("%.3f", location.getY())
                + ","
                + String.format("%.3f", location.getZ());
    }

    private void resetTransientBodyStateAfterTeleport(FakePlayer fp, Player body, Location current) {
        UUID uuid = fp.getUuid();
        try {
            body.setInvulnerable(false);
            body.setNoDamageTicks(0);
            body.setFallDistance(0.0f);
            body.setVelocity(ZERO_VELOCITY);
            NmsPlayerSpawner.setJumping(body, false);
        } catch (Throwable ignored) {
        }

        trackedFallDistance.remove(uuid);
        lastFallY.remove(uuid);
        wasOnGround.remove(uuid);
        navJumpHolding.remove(uuid);
        botHeadRotation.put(uuid, new float[] {current.getYaw(), current.getPitch()});
        botSpawnRotation.put(uuid, new float[] {current.getYaw(), current.getPitch()});
    }

    private String finalizeDisplayName(String rawName, String botName) {
        String display = rawName;

        if (display.contains("%")) {
            try {
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    display = PlaceholderAPI.setPlaceholders(null, display);
                }
            } catch (Exception ignored) {
            }
        }

        return sanitizeDisplayName(display, botName);
    }



    public void refreshSkinForAll(FakePlayer fp) {
        if (!activePlayers.containsKey(fp.getUuid())) return;
        Player body = fp.getPlayer();
        if (body == null || !body.isValid()) return;
        boolean tabVisible = Config.tabListEnabled();

        fp.clearCachedTabListGameProfile();
        NmsPlayerSpawner.applySkinToGameProfile(body, fp.getResolvedSkin());
        NmsPlayerSpawner.refreshPaperPlayer(body);

        for (Player viewer : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (viewer.getUniqueId().equals(body.getUniqueId())) continue;
            if (activePlayers.containsKey(viewer.getUniqueId())) continue;
            if (!viewer.canSee(body)) continue;

            viewer.hidePlayer(plugin, body);
            viewer.showPlayer(plugin, body);
            if (!tabVisible) PacketHelper.sendTabListUpdateListed(viewer, fp, false);
        }
    }

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}");

    private String sanitizeDisplayName(String displayName, String context) {
        if (displayName == null || !displayName.contains("{")) {

            return (displayName == null || displayName.isBlank()) ? context : displayName;
        }
        Matcher m = PLACEHOLDER_PATTERN.matcher(displayName);
        if (!m.find()) {
            return displayName.isBlank() ? context : displayName;
        }
        String fallback = pickRandomSkinName();
        String sanitized = PLACEHOLDER_PATTERN.matcher(displayName).replaceAll(fallback);
        FppLogger.warn("Unreplaced placeholder(s) in display name for '"
                + context
                + "': '"
                + displayName
                + "' - replaced with '"
                + fallback
                + "'. Check bot-name.user-format / bot-name.admin-format in config.yml.");

        return sanitized.isBlank() ? context : sanitized;
    }

    /**
     * Clears any ping on the given bot. Bots never report a latency: neither simulated jitter nor a
     * user-supplied value is accepted, so a bot cannot present a fabricated network connection.
     * The {@code pingMs} argument is ignored and kept only for source compatibility.
     */
    public void applyPing(FakePlayer fp, int pingMs) {
        if (fp == null) return;
        fp.setUserPing(-1);
        fp.setBasePing(-1);
        fp.setPing(-1);
        NmsPlayerSpawner.setPing(fp.getPlayer(), fp.getEffectivePing());
    }

    private String resolveDespawnDisplayName(FakePlayer fp) {
        return BotBroadcast.resolveDisplayName(fp);
    }

    private String pickRandomSkinName() {
        // Skin/name pools removed; simple fallback for stray display-name placeholders.
        return "bot";
    }

    /** True if {@code name} belongs to a real (non-bot) account this server has seen. */
    private boolean isRealPlayerName(String name) {
        try {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached == null) return false;
            UUID botUuid = BotIdentityCache.deterministicBotUuid(name);
            return !cached.getUniqueId().equals(botUuid) && getByUuid(cached.getUniqueId()) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    private UUID resolveUuid(String botName) {
        // Deterministic, name-derived, never contacts Mojang: sequential names embed their number
        // (bot → fb07…-000000000001, bot2 → …-000000000002), custom names get the same fb07 prefix
        // with a name hash in the low bits - so a bot never inherits a real account's identity.
        return BotIdentityCache.deterministicBotUuid(botName);
    }



    /**
     * Returns the next available sequential bot name: {@code bot}, {@code bot2}, {@code bot3}, …
     *
     * <p>Picks the lowest-numbered name not already in use by an active bot or an online player, so
     * despawned names are reused. Replaces the old random name generator and name-pool modes.
     */
    private String nextSequentialName() {
        for (int n = 1; n <= 1_000_000; n++) {
            String candidate = n == 1 ? "bot" : "bot" + n;
            if (usedNames.contains(candidate)) continue;
            if (Bukkit.getPlayerExact(candidate) != null) continue;
            usedNames.add(candidate);
            return candidate;
        }
        return null;
    }

    public void updateAllBotPrefixes() {

        Config.debug("updateAllBotPrefixes: skipped (bots are real players, LP handles natively)");
    }

    private static boolean hasLineOfSightIgnoringGlass(Player from, Player to) {
        Location start = from.getEyeLocation();
        Location end = to.getEyeLocation();
        if (start.getWorld() == null || !start.getWorld().equals(end.getWorld())) return false;

        Vector dir = end.toVector().subtract(start.toVector());
        double distance = dir.length();
        if (distance < 1e-6) return true;
        dir.normalize();

        try {
            BlockIterator iter =
                    new BlockIterator(start.getWorld(), start.toVector(), dir, 0.0, (int) Math.ceil(distance) + 1);
            while (iter.hasNext()) {
                Block block = iter.next();
                Material type = block.getType();
                if (!type.isSolid()) continue;
                if (isGlassLike(type)) continue;

                double blockDistSq = block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(start);
                if (blockDistSq < distance * distance) return false;
            }
        } catch (Exception e) {
            return from.hasLineOfSight(to);
        }
        return true;
    }

    private static boolean isGlassLike(Material mat) {
        return mat.name().contains("GLASS");
    }

    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return from + diff * t;
    }

    public void persistBotSettings(FakePlayer fp) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || fp == null) return;
        db.updateBotAllSettings(
                fp.getUuid().toString(),
                fp.isFrozen(),
                fp.isChatEnabled(),
                fp.getChatTier(),
                fp.getRightClickCommand(),
                fp.getAiPersonality(),
                fp.isPickUpItemsEnabled(),
                fp.isPickUpXpEnabled(),
                fp.isHeadAiEnabled(),
                fp.isNavParkour(),
                fp.isNavBreakBlocks(),
                fp.isNavPlaceBlocks(),
                fp.isNavAvoidWater(),
                fp.isNavAvoidLava(),
                fp.isSwimAiEnabled(),
                fp.getChunkLoadRadius(),
                fp.getPing(),
                fp.isPveEnabled(),
                fp.getPveRange(),
                fp.getPvePriority(),
                fp.getPveMobType(),
                fp.getPveSmartAttackMode().name(),
                fp.isAutoMilkEnabled(),
                fp.isPreventBadOmen(),
                fp.isRespawnOnDeath(),
                fp.hasCustomPing(),
                fp.getLuckpermsGroup());
    }
}
