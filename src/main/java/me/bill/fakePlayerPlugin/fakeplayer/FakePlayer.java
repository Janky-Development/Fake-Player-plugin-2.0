package me.bill.fakePlayerPlugin.fakeplayer;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.PlayerProfile;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.BotRecord;

@SuppressWarnings("unused")
public final class FakePlayer {

    private final String name;
    private final PlayerProfile profile;
    private final UUID uuid;

    private Location spawnLocation;

    private Player player;

    private String spawnedBy = "UNKNOWN";
    private UUID spawnedByUuid = new UUID(0, 0);
    private BotRecord dbRecord;
    private Instant spawnTime = Instant.now();

    /**
     * Epoch-millis timestamp this bot's paid rental time runs out, or {@code null} for a bot that
     * isn't rented (spawned normally / admin bot / unlimited-rental permission) and never expires.
     */
    private volatile Long rentalExpiresAt = null;

    /** True once the low-rental-time warning has fired for the current rental period (reset on extend). */
    private volatile boolean rentalWarningSent = false;

    private String displayName = null;

    private String rawDisplayName = null;

    private String skinName = null;

    private SkinProfile resolvedSkin = null;

    private double totalDamageTaken = 0.0;

    private int deathCount = 0;

    private Entity nametagEntity = null;

    private String packetProfileName = null;

    private boolean alive = true;

    private boolean respawning = false;
    private boolean respawnOnDeath = Config.respawnOnDeath();

    private transient volatile Object cachedNmsDisplayComponent;

    private transient volatile String cachedNmsDisplaySource;

    private transient volatile Object cachedTabListGameProfile;

    private boolean frozen = false;

    // Set by BotAuthManager the instant a join needs a register/login attempt, cleared once that
    // resolves (success detected, a failure was logged, or auth.pending-timeout-ticks elapses) -
    // see BotAuthManager's own class doc for the full flow.
    private volatile boolean authPending = false;

    private boolean bodyless = false;

    private boolean inventoryOpen = false;

    /**
     * True when this bot was spawned via {@code spawnRestored} (server restart persistence).
     */
    private boolean restoredSpawn = false;

    private String luckpermsGroup = null;

    private BotType botType = BotType.AFK;

    // Inert persistence fields: the chat system was removed, but these per-bot
    // values are retained as dormant DB/YAML state to avoid risky schema migration.
    // They are intentionally NOT exposed through the public FppBot API.
    private boolean chatEnabled = true;

    private String chatTier = null;

    private String aiPersonality = null;

    private String rightClickCommand = null;

    private boolean headAiEnabled = true;

    private boolean pickUpItemsEnabled = Config.bodyPickUpItems();

    private boolean pickUpXpEnabled = Config.bodyPickUpXp();

    private boolean navParkour = Config.pathfindingParkour();

    private boolean navBreakBlocks = Config.pathfindingBreakBlocks();

    private boolean navPlaceBlocks = Config.pathfindingPlaceBlocks();

    private boolean navAvoidWater = false;

    private boolean navAvoidLava = false;

    private boolean defaultWaterPathAvoidanceEnabled = true;

    private boolean swimAiEnabled = Config.swimAiEnabled();

    private int chunkLoadRadius = -1;

    /** Ticks between block breaks for held left-click mining. -1 = use config's left-click.interval-ticks. */
    private volatile int leftClickIntervalTicks = -1;

    /** Ticks between held right-click pulses. -1 = use config's right-click.interval-ticks. */
    private volatile int rightClickIntervalTicks = -1;

    public enum PveSmartAttackMode {
        OFF,
        ON_NO_MOVE,
        ON_MOVE;

        public boolean isEnabled() {
            return this != OFF;
        }

        public boolean movesWhileAttacking() {
            return this == ON_MOVE;
        }

        public PveSmartAttackMode next() {
            return switch (this) {
                case OFF -> ON_NO_MOVE;
                case ON_NO_MOVE -> ON_MOVE;
                case ON_MOVE -> OFF;
            };
        }

        public static PveSmartAttackMode fromStoredValue(String raw) {
            if (raw == null || raw.isBlank()) return OFF;
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "ON", "ON_WITHOUT_MOVEMENT", "ON_NO_MOVE", "NO_MOVE", "WITHOUT_MOVEMENT" -> ON_NO_MOVE;
                case "ON_MOVE", "MOVE", "ON_WITH_MOVEMENT", "WITH_MOVEMENT" -> ON_MOVE;
                default -> OFF;
            };
        }
    }

    private PveSmartAttackMode pveSmartAttackMode = PveSmartAttackMode.OFF;
    private double pveRange = Config.attackMobDefaultRange();
    private String pvePriority = Config.attackMobDefaultPriority();
    private Set<String> pveMobTypes = new LinkedHashSet<>();
    private final Set<UUID> sharedControllers = ConcurrentHashMap.newKeySet();
    private boolean autoEatEnabled = Config.autoEatEnabled();
    private int autoEatHungerThreshold = Config.autoEatHungerThreshold();
    private Set<org.bukkit.Material> autoEatFoods = new LinkedHashSet<>();
    private volatile boolean autoEating = false;
    private volatile boolean actionsPaused = false;
    private boolean autoPlaceBedEnabled = Config.autoPlaceBedEnabled();
    private boolean autoMilkEnabled = Config.autoMilkEnabled();
    private boolean preventBadOmen = Config.preventBadOmen();

    private volatile String nameTagNick = null;

    private int ping = -1;

    private int basePing = -1;

    private boolean pingUserSet = false;

    private long spawnTimeMs = 0;

    private volatile boolean tabListDirty = true;

    /** Last action label rendered on the nametag's activity row - used to skip no-op refreshes. */
    private volatile String lastRenderedActionLabel = null;

    /**
     * Plugin-attached metadata - transient, cleared on despawn.
     */
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    // ── Sleep system ──────────────────────────────────────────────────────────
    /**
     * Station location used as the center for bed searching. null = not configured.
     */
    private Location sleepOrigin = null;
    /**
     * Bed-search radius in blocks. 0 = sleep disabled for this bot.
     */
    private double sleepRadius = 0.0;
    /**
     * True while the bot is currently in the sleeping state.
     */
    private boolean sleeping = false;

    public FakePlayer(UUID uuid, String name, PlayerProfile profile) {
        this.uuid = uuid;
        this.name = name;
        this.profile = profile;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public PlayerProfile getProfile() {
        return profile;
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }

    public Player getPhysicsEntity() {
        return player;
    }

    public Player getPlayer() {
        return player;
    }

    public int getEntityId() {
        return player != null ? player.getEntityId() : -1;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    public String getSkinName() {
        return skinName != null ? skinName : name;
    }

    public Location getLiveLocation() {
        if (player != null && player.isOnline() && !player.isDead()) return player.getLocation();
        return spawnLocation;
    }

    public Duration getUptime() {
        return Duration.between(spawnTime, Instant.now());
    }

    public double getTotalDamageTaken() {
        return totalDamageTaken;
    }

    public int getDeathCount() {
        return deathCount;
    }

    public boolean isAlive() {
        return alive;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void addDamageTaken(double amount) {
        totalDamageTaken += amount;
    }

    public void incrementDeathCount() {
        deathCount++;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /** True while this bot is mid register/login against an installed auth plugin - see {@code BotAuthManager}. Also gates the tick loop's freeze check (alongside {@link #isFrozen}), so a bot stands still and doesn't turn its head until this clears, exactly like a real not-yet-logged-in player. */
    public boolean isAuthPending() {
        return authPending;
    }

    public void setAuthPending(boolean authPending) {
        this.authPending = authPending;
    }

    public Object getCachedNmsDisplayComponent() {
        return cachedNmsDisplayComponent;
    }

    public String getCachedNmsDisplaySource() {
        return cachedNmsDisplaySource;
    }

    public void setCachedNmsDisplay(Object comp, String source) {
        this.cachedNmsDisplayComponent = comp;
        this.cachedNmsDisplaySource = source;
    }

    public Object getCachedTabListGameProfile() {
        return cachedTabListGameProfile;
    }

    public void setCachedTabListGameProfile(Object profile) {
        this.cachedTabListGameProfile = profile;
    }

    public void clearCachedTabListGameProfile() {
        this.cachedTabListGameProfile = null;
    }

    public void setSpawnLocation(Location loc) {
        this.spawnLocation = loc;
    }

    public void setPlayer(Player p) {
        this.player = p;
    }

    public void setPhysicsEntity(Entity e) {
        this.player = e instanceof Player ? (Player) e : null;
    }

    public void setDisplayName(String name) {
        this.displayName = name;
        this.tabListDirty = true;
        this.cachedNmsDisplayComponent = null;
        this.cachedNmsDisplaySource = null;
    }

    public String getRawDisplayName() {
        return rawDisplayName;
    }

    public void setRawDisplayName(String name) {
        this.rawDisplayName = name;
    }

    public String getLuckpermsGroup() {
        return luckpermsGroup;
    }

    public void setLuckpermsGroup(String group) {
        this.luckpermsGroup = group;
    }

    public boolean isInventoryOpen() {
        return inventoryOpen;
    }

    public void setInventoryOpen(boolean open) {
        this.inventoryOpen = open;
    }

    public BotType getBotType() {
        return botType != null ? botType : BotType.AFK;
    }

    public void setBotType(BotType type) {
        this.botType = type;
    }

    public boolean isRespawning() {
        return respawning;
    }

    public void setRespawning(boolean v) {
        this.respawning = v;
    }

    public boolean isRespawnOnDeath() {
        return respawnOnDeath;
    }

    public void setRespawnOnDeath(boolean v) {
        this.respawnOnDeath = v;
    }

    // Inert persistence accessors for the removed chat system (internal use only).
    public boolean isChatEnabled() {
        return chatEnabled;
    }

    public void setChatEnabled(boolean v) {
        this.chatEnabled = v;
    }

    public String getChatTier() {
        return chatTier;
    }

    public void setChatTier(String tier) {
        this.chatTier = tier;
    }

    public String getAiPersonality() {
        return aiPersonality;
    }

    public void setAiPersonality(String personality) {
        this.aiPersonality = personality;
    }

    public void setSkinName(String name) {
        this.skinName = name;
    }

    public void setResolvedSkin(SkinProfile skin) {
        this.resolvedSkin = skin;
        this.cachedTabListGameProfile = null;
    }

    public String getRightClickCommand() {
        return rightClickCommand;
    }

    public void setRightClickCommand(String cmd) {
        this.rightClickCommand =
                (cmd == null || cmd.isBlank()) ? null : cmd.stripLeading().replaceFirst("^/", "");
    }

    public boolean hasRightClickCommand() {
        return rightClickCommand != null;
    }

    public boolean isHeadAiEnabled() {
        return headAiEnabled;
    }

    public void setHeadAiEnabled(boolean v) {
        this.headAiEnabled = v;
    }

    public boolean isPickUpItemsEnabled() {
        return pickUpItemsEnabled;
    }

    public void setPickUpItemsEnabled(boolean v) {
        this.pickUpItemsEnabled = v;
    }

    public boolean isPickUpXpEnabled() {
        return pickUpXpEnabled;
    }

    public void setPickUpXpEnabled(boolean v) {
        this.pickUpXpEnabled = v;
    }

    public boolean isNavParkour() {
        return navParkour;
    }

    public void setNavParkour(boolean v) {
        this.navParkour = v;
    }

    public boolean isNavBreakBlocks() {
        return navBreakBlocks;
    }

    public void setNavBreakBlocks(boolean v) {
        this.navBreakBlocks = v;
    }

    public boolean isNavPlaceBlocks() {
        return navPlaceBlocks;
    }

    public void setNavPlaceBlocks(boolean v) {
        this.navPlaceBlocks = v;
    }

    public boolean isNavAvoidWater() {
        return navAvoidWater;
    }

    public void setNavAvoidWater(boolean v) {
        this.navAvoidWater = v;
    }

    public boolean isNavAvoidLava() {
        return navAvoidLava;
    }

    public void setNavAvoidLava(boolean v) {
        this.navAvoidLava = v;
    }

    public boolean isDefaultWaterPathAvoidanceEnabled() {
        return defaultWaterPathAvoidanceEnabled;
    }

    public void setDefaultWaterPathAvoidanceEnabled(boolean v) {
        this.defaultWaterPathAvoidanceEnabled = v;
    }

    public boolean isSwimAiEnabled() {
        return swimAiEnabled;
    }

    public void setSwimAiEnabled(boolean v) {
        this.swimAiEnabled = v;
    }

    public int getChunkLoadRadius() {
        return chunkLoadRadius;
    }

    public void setChunkLoadRadius(int r) {
        this.chunkLoadRadius = r;
    }

    public int getLeftClickIntervalTicks() {
        return leftClickIntervalTicks;
    }

    public void setLeftClickIntervalTicks(int ticks) {
        this.leftClickIntervalTicks = ticks;
    }

    /** The interval this bot's left-click loop should actually use - its own override, or the config default. */
    public int resolveLeftClickIntervalTicks() {
        return leftClickIntervalTicks > 0 ? leftClickIntervalTicks : Config.leftClickIntervalTicks();
    }

    public int getRightClickIntervalTicks() {
        return rightClickIntervalTicks;
    }

    public void setRightClickIntervalTicks(int ticks) {
        this.rightClickIntervalTicks = ticks;
    }

    /** The interval this bot's right-click loop should actually use - its own override, or the config default. */
    public int resolveRightClickIntervalTicks() {
        return rightClickIntervalTicks > 0 ? rightClickIntervalTicks : Config.rightClickIntervalTicks();
    }

    @Nullable
    public String getNameTagNick() {
        return nameTagNick;
    }

    public int getPing() {
        return ping;
    }

    public void setPing(int ping) {
        int newPing = (ping < 0) ? -1 : Math.min(ping, 9999);
        if (this.ping != newPing) {
            this.ping = newPing;
            this.tabListDirty = true;
        }
    }

    /** The only name a bot is ever listed under in the tab list: {@code [Bot] - <botname>}. */
    public static String tabListName(String botName) {
        return "[Bot] - " + botName;
    }

    public String getTabListName() {
        return tabListName(getName());
    }

    public void setBasePing(int basePing) {
        this.basePing = basePing;
    }

    public boolean hasCustomPing() {
        return pingUserSet;
    }

    public boolean isPingSimulated() {
        return !pingUserSet && basePing >= 0;
    }

    public int getEffectivePing() {
        if (ping >= 0) return ping;
        if (basePing >= 0) return basePing;
        return 0;
    }

    public void setUserPing(int pingMs) {
        if (pingMs < 0) {
            this.pingUserSet = false;
            this.ping = -1;
        } else {
            this.pingUserSet = true;
            setPing(pingMs);
        }
        this.tabListDirty = true;
    }

    public void setSpawnTick(long ms) {
        this.spawnTimeMs = ms;
    }

    public boolean isTabListDirty() {
        return tabListDirty;
    }

    public void clearTabListDirty() {
        tabListDirty = false;
    }

    public String getLastRenderedActionLabel() {
        return lastRenderedActionLabel;
    }

    public void setLastRenderedActionLabel(String label) {
        this.lastRenderedActionLabel = label;
    }

    public SkinProfile getResolvedSkin() {
        return resolvedSkin;
    }

    public String getSpawnedBy() {
        return spawnedBy;
    }

    public UUID getSpawnedByUuid() {
        return spawnedByUuid;
    }

    public Instant getSpawnTime() {
        return spawnTime;
    }

    /** Null means this bot isn't rented and never expires from time running out. */
    @Nullable
    public Long getRentalExpiresAt() {
        return rentalExpiresAt;
    }

    public void setRentalExpiresAt(@Nullable Long epochMillis) {
        this.rentalExpiresAt = epochMillis;
        this.rentalWarningSent = false;
    }

    public boolean isRented() {
        return rentalExpiresAt != null;
    }

    public boolean isRentalWarningSent() {
        return rentalWarningSent;
    }

    public void setRentalWarningSent(boolean sent) {
        this.rentalWarningSent = sent;
    }

    public void setSpawnedBy(String name, UUID uuid) {
        this.spawnedBy = name != null && !name.isBlank() ? name : "UNKNOWN";
        this.spawnedByUuid = uuid != null ? uuid : new UUID(0, 0);
        sharedControllers.remove(this.spawnedByUuid);
    }

    public void setDbRecord(BotRecord record) {
        this.dbRecord = record;
    }

    public void setSpawnTime(Instant t) {
        this.spawnTime = t;
    }

    public boolean isBodyless() {
        return bodyless;
    }

    public void setBodyless(boolean bodyless) {
        this.bodyless = bodyless;
    }

    public boolean isRestoredSpawn() {
        return restoredSpawn;
    }

    public void setRestoredSpawn(boolean restoredSpawn) {
        this.restoredSpawn = restoredSpawn;
    }

    /** The activity (top) and owner (bottom) rows; the primary entity is the name (middle) row. */
    private volatile java.util.List<Entity> nametagExtraEntities = java.util.List.of();

    public java.util.List<Entity> getNametagExtraEntities() {
        return nametagExtraEntities;
    }

    public void setNametagExtraEntities(java.util.List<Entity> entities) {
        this.nametagExtraEntities = entities == null ? java.util.List.of() : java.util.List.copyOf(entities);
    }

    public Entity getNametagEntity() {
        return nametagEntity;
    }

    public void setNametagEntity(Entity entity) {
        this.nametagEntity = entity;
    }

    public String getPacketProfileName() {
        String p = packetProfileName != null ? packetProfileName : name;
        return (p == null || p.isBlank()) ? name : p;
    }

    public void setPacketProfileName(String n) {
        this.packetProfileName = (n == null || n.isBlank()) ? this.name : n;
    }

    public boolean isPveEnabled() {
        return pveSmartAttackMode.isEnabled();
    }

    public void setPveEnabled(boolean v) {
        if (!v) {
            this.pveSmartAttackMode = PveSmartAttackMode.OFF;
        } else if (this.pveSmartAttackMode == PveSmartAttackMode.OFF) {
            this.pveSmartAttackMode = PveSmartAttackMode.ON_NO_MOVE;
        }
    }

    public double getPveRange() {
        return pveRange;
    }

    public void setPveRange(double v) {
        this.pveRange = v;
    }

    public String getPvePriority() {
        return pvePriority;
    }

    public void setPvePriority(String v) {
        this.pvePriority = v;
    }

    public PveSmartAttackMode getPveSmartAttackMode() {
        return pveSmartAttackMode;
    }

    public void setPveSmartAttackMode(PveSmartAttackMode mode) {
        this.pveSmartAttackMode = mode != null ? mode : PveSmartAttackMode.OFF;
    }

    public void setPveSmartAttackMode(String mode) {
        this.pveSmartAttackMode = PveSmartAttackMode.fromStoredValue(mode);
    }

    public boolean isPveMoveToTarget() {
        return pveSmartAttackMode.movesWhileAttacking();
    }

    public String getPveMobType() {
        return pveMobTypes.isEmpty() ? null : String.join(",", pveMobTypes);
    }

    public void setPveMobType(String v) {
        pveMobTypes.clear();
        if (v != null && !v.isBlank()) {
            for (String part : v.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) pveMobTypes.add(trimmed);
            }
        }
    }

    public Set<String> getPveMobTypes() {
        return pveMobTypes;
    }

    public void setPveMobTypes(Set<String> types) {
        this.pveMobTypes = types != null ? new LinkedHashSet<>(types) : new LinkedHashSet<>();
    }

    public boolean togglePveMobType(String type) {
        if (pveMobTypes.contains(type)) {
            pveMobTypes.remove(type);
            return false;
        } else {
            pveMobTypes.add(type);
            return true;
        }
    }

    public Set<UUID> getSharedControllers() {
        return Set.copyOf(sharedControllers);
    }

    public boolean hasSharedControllers() {
        return !sharedControllers.isEmpty();
    }

    public boolean hasSharedController(UUID uuid) {
        return uuid != null && sharedControllers.contains(uuid);
    }

    public boolean addSharedController(UUID uuid) {
        return uuid != null && !uuid.equals(spawnedByUuid) && sharedControllers.add(uuid);
    }

    public boolean removeSharedController(UUID uuid) {
        return uuid != null && sharedControllers.remove(uuid);
    }

    public void clearSharedControllers() {
        sharedControllers.clear();
    }

    public boolean isAutoEatEnabled() {
        return autoEatEnabled;
    }

    public void setAutoEatEnabled(boolean autoEatEnabled) {
        this.autoEatEnabled = autoEatEnabled;
    }

    /** Hunger level (0-19) at or below which the bot will auto-eat. */
    public int getAutoEatHungerThreshold() {
        return autoEatHungerThreshold;
    }

    public void setAutoEatHungerThreshold(int threshold) {
        this.autoEatHungerThreshold = Math.max(0, Math.min(19, threshold));
    }

    /** Allowed auto-eat foods; an empty set means "eat any food". */
    public Set<org.bukkit.Material> getAutoEatFoods() {
        return autoEatFoods;
    }

    public void setAutoEatFoods(Set<org.bukkit.Material> foods) {
        this.autoEatFoods = foods != null ? foods : new LinkedHashSet<>();
    }

    /** Toggles a single food in the allowed set; returns true if it is now allowed. */
    public boolean toggleAutoEatFood(org.bukkit.Material food) {
        if (food == null) return false;
        if (autoEatFoods.contains(food)) {
            autoEatFoods.remove(food);
            return false;
        }
        autoEatFoods.add(food);
        return true;
    }

    /** Transient: true while the auto-eat controller is mid-eat (drives the nametag activity line). */
    public boolean isAutoEating() {
        return autoEating;
    }

    public void setAutoEating(boolean autoEating) {
        this.autoEating = autoEating;
    }

    /**
     * Transient: true while an interrupt (e.g. auto-eat) has paused this bot's active task. Every
     * per-tick action loop checks this and no-ops while set, preserving its state so the task resumes
     * automatically when the flag clears.
     */
    public boolean isActionsPaused() {
        return actionsPaused;
    }

    public void setActionsPaused(boolean actionsPaused) {
        this.actionsPaused = actionsPaused;
    }

    public boolean isAutoPlaceBedEnabled() {
        return autoPlaceBedEnabled;
    }

    public void setAutoPlaceBedEnabled(boolean autoPlaceBedEnabled) {
        this.autoPlaceBedEnabled = autoPlaceBedEnabled;
    }

    public boolean isAutoMilkEnabled() {
        return autoMilkEnabled;
    }

    public void setAutoMilkEnabled(boolean autoMilkEnabled) {
        this.autoMilkEnabled = autoMilkEnabled;
    }

    public boolean isPreventBadOmen() {
        return preventBadOmen;
    }

    public void setPreventBadOmen(boolean preventBadOmen) {
        this.preventBadOmen = preventBadOmen;
    }

    // ── Sleep system accessors ────────────────────────────────────────────────

    @Nullable
    public Location getSleepOrigin() {
        return sleepOrigin;
    }

    public void setSleepOrigin(@Nullable Location origin) {
        this.sleepOrigin = origin != null ? origin.clone() : null;
    }

    public double getSleepRadius() {
        return sleepRadius;
    }

    public void setSleepRadius(double radius) {
        this.sleepRadius = radius;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public void setSleeping(boolean sleeping) {
        this.sleeping = sleeping;
    }

    // ── Plugin metadata ────────────────────────────────────────────────────────

    @Nullable
    public Object getMetadata(String key) {
        return key != null ? metadata.get(key) : null;
    }

    public void setMetadata(String key, Object value) {
        if (key == null) return;
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    public boolean hasMetadata(String key) {
        return key != null && metadata.containsKey(key);
    }

    public void removeMetadata(String key) {
        if (key != null) metadata.remove(key);
    }

    @NotNull
    public Map<String, Object> getMetadataMap() {
        return Map.copyOf(metadata);
    }

    public void clearMetadata() {
        metadata.clear();
    }
}
