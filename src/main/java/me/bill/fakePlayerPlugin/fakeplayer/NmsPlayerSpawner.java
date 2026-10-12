package me.bill.fakePlayerPlugin.fakeplayer;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.util.Vector;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.network.FakeConnection;
import me.bill.fakePlayerPlugin.fakeplayer.network.FakeServerGamePacketListenerImpl;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class NmsPlayerSpawner {

    private static volatile boolean initialized = false;
    private static volatile boolean failed = false;
    private static final Map<UUID, Boolean> lastJumpingState = new ConcurrentHashMap<>();
    private static final Map<UUID, Location> pendingSpawnLocations = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> latencyFieldCache = new ConcurrentHashMap<>();

    private static Method craftPlayerGetHandleMethod;
    private static Method craftPlayerRefreshPlayerMethod;
    private static Method craftServerGetServerMethod;
    private static Method craftWorldGetHandleMethod;

    private static Class<?> minecraftServerClass;
    private static Class<?> serverLevelClass;
    private static Class<?> serverPlayerClass;
    private static Class<?> clientInformationClass;
    private static Class<?> connectionClass;
    private static Class<?> commonListenerCookieClass;
    private static Class<?> serverGamePacketListenerClass;
    private static Class<?> packetFlowClass;

    private static Constructor<?> gameProfileConstructor;
    private static Class<?> gameProfileClass;
    private static Method setPosMethod;
    private static Method getPlayerListMethod;
    private static volatile Method serverPlayerGetBukkitEntityMethod;
    private static volatile Method playerListPlaceNewPlayerMethod;

    private static Field xoField;
    private static Field yoField;
    private static Field zoField;

    private static Field jumpingField;

    private static Field listedField;

    private static Field yHeadRotField;

    private static Field zzaField;

    private static Field xxaField;

    private static Field connectionFieldInPlayer;

    private static Method attackMethod;
    private static Field attackStrengthTickerField;

    private static Method playerListRemoveMethod;

    private static Field playerDataStorageField;

    private static Method playerDataSaveMethod;

    private static Method getPlayerDirMethod;

    private static Object clientInfoDefault;

    private static final Set<UUID> firstTickSet = Collections.synchronizedSet(new HashSet<>());

    private static Object skinPartsDataAccessor = null;

    private static Method synchedEntityDataSetMethod = null;

    private static Field entityDataFieldForSkinParts = null;

    private NmsPlayerSpawner() {}

    public static synchronized void init() {
        if (initialized || failed) return;
        try {

            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String ver = packageName.substring(packageName.lastIndexOf('.') + 1);
            String cbPkg = ver.equals("craftbukkit") ? "org.bukkit.craftbukkit" : "org.bukkit.craftbukkit." + ver;
            FppLogger.debug("NmsPlayerSpawner: CraftBukkit package = " + cbPkg);

            Class<?> craftServerClass = Class.forName(cbPkg + ".CraftServer");
            Class<?> craftWorldClass = Class.forName(cbPkg + ".CraftWorld");
            Class<?> craftPlayerClass = Class.forName(cbPkg + ".entity.CraftPlayer");
            ClassLoader nmsLoader = craftServerClass.getClassLoader();

            craftServerGetServerMethod = craftServerClass.getMethod("getServer");
            craftWorldGetHandleMethod = craftWorldClass.getMethod("getHandle");
            craftPlayerGetHandleMethod = craftPlayerClass.getMethod("getHandle");
            try {
                craftPlayerRefreshPlayerMethod = craftPlayerClass.getDeclaredMethod("refreshPlayer");
                craftPlayerRefreshPlayerMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                craftPlayerRefreshPlayerMethod = null;
            }

            minecraftServerClass = nmsLoader.loadClass("net.minecraft.server.MinecraftServer");
            try {
                serverLevelClass = nmsLoader.loadClass("net.minecraft.server.level.ServerLevel");
                serverPlayerClass = nmsLoader.loadClass("net.minecraft.server.level.ServerPlayer");
                FppLogger.debug("NmsPlayerSpawner: using Mojang-mapped NMS names");
            } catch (ClassNotFoundException e) {
                serverLevelClass = nmsLoader.loadClass("net.minecraft.server.level.WorldServer");
                serverPlayerClass = nmsLoader.loadClass("net.minecraft.server.level.EntityPlayer");
                FppLogger.debug("NmsPlayerSpawner: using Spigot-mapped NMS names");
            }

            try {
                connectionClass = nmsLoader.loadClass("net.minecraft.network.Connection");
            } catch (ClassNotFoundException e) {
                connectionClass = nmsLoader.loadClass("net.minecraft.network.NetworkManager");
            }
            try {
                commonListenerCookieClass = nmsLoader.loadClass("net.minecraft.server.network.CommonListenerCookie");
            } catch (ClassNotFoundException ignored) {
            }
            try {
                serverGamePacketListenerClass =
                        nmsLoader.loadClass("net.minecraft.server.network.ServerGamePacketListenerImpl");
            } catch (ClassNotFoundException e) {
                try {
                    serverGamePacketListenerClass =
                            nmsLoader.loadClass("net.minecraft.server.network.PlayerConnection");
                } catch (ClassNotFoundException ignored) {
                }
            }
            try {
                packetFlowClass = nmsLoader.loadClass("net.minecraft.network.protocol.PacketFlow");
            } catch (ClassNotFoundException ignored) {
            }

            try {
                clientInformationClass = nmsLoader.loadClass("net.minecraft.server.level.ClientInformation");
                try {
                    clientInfoDefault =
                            clientInformationClass.getMethod("createDefault").invoke(null);
                    FppLogger.debug("NmsPlayerSpawner: ClientInformation.createDefault() cached");
                } catch (Exception ignored) {
                }
            } catch (ClassNotFoundException ignored) {
            }

            gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            gameProfileConstructor = gameProfileClass.getConstructor(UUID.class, String.class);

            getPlayerListMethod = minecraftServerClass.getMethod("getPlayerList");

            for (Method m : serverPlayerClass.getMethods()) {
                if ("setPos".equals(m.getName()) && m.getParameterCount() == 3) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == double.class && p[1] == double.class && p[2] == double.class) {
                        setPosMethod = m;
                        break;
                    }
                }
            }
            if (setPosMethod == null)
                setPosMethod = findMethodBySignature(serverPlayerClass, 3, double.class, double.class, double.class);

            // doTick() used to be resolved reflectively here and invoked once per bot per tick - by
            // far the hottest call in the plugin (~70% of profiled self-time). ServerPlayer is a
            // direct compile-time import (this project targets one pinned Paper/Folia version via
            // paperweight-userdev), so tickPhysicsInternal() now calls nmsPlayer.doTick() directly.

            Class<?> entityClass;
            try {
                entityClass = nmsLoader.loadClass("net.minecraft.world.entity.Entity");
            } catch (ClassNotFoundException e) {
                entityClass = serverPlayerClass;
            }
            xoField = findFieldByName(entityClass, "xo");
            yoField = findFieldByName(entityClass, "yo");
            zoField = findFieldByName(entityClass, "zo");
            FppLogger.debug("NmsPlayerSpawner: xo/yo/zo fields " + (xoField != null ? "cached" : "not found"));

            try {
                Class<?> livingEntityClass = nmsLoader.loadClass("net.minecraft.world.entity.LivingEntity");
                jumpingField = findFieldByName(livingEntityClass, "jumping");
            } catch (ClassNotFoundException ignored) {

                jumpingField = findFieldByName(serverPlayerClass, "jumping");
            }
            FppLogger.debug("NmsPlayerSpawner: jumping field "
                    + (jumpingField != null ? "cached" : "not found - swim AI inactive"));

            listedField = findFieldByName(serverPlayerClass, "listed");
            FppLogger.debug("NmsPlayerSpawner: listed field "
                    + (listedField != null ? "cached" : "not found - tab unlist will use packet fallback"));

            yHeadRotField = findFieldByName(serverPlayerClass, "yHeadRot");
            FppLogger.debug("NmsPlayerSpawner: yHeadRot field "
                    + (yHeadRotField != null ? "cached" : "not found - head AI will rely on setRotation only"));

            zzaField = findFieldByName(serverPlayerClass, "zza");

            xxaField = findFieldByName(serverPlayerClass, "xxa");
            FppLogger.debug("NmsPlayerSpawner: movement input fields "
                    + (zzaField != null && xxaField != null ? "cached" : "not found - move command inactive"));

            if (serverGamePacketListenerClass != null) {
                connectionFieldInPlayer = findFieldByName(serverPlayerClass, "connection");
                if (connectionFieldInPlayer == null)
                    connectionFieldInPlayer = findFieldByName(serverPlayerClass, "playerConnection");
                if (connectionFieldInPlayer == null)
                    connectionFieldInPlayer = findFieldByName(serverPlayerClass, "playerGameConnection");
                if (connectionFieldInPlayer == null) {
                    for (Field f : getAllDeclaredFields(serverPlayerClass)) {
                        if (serverGamePacketListenerClass.isAssignableFrom(f.getType())
                                || f.getType().isAssignableFrom(serverGamePacketListenerClass)) {
                            f.setAccessible(true);
                            connectionFieldInPlayer = f;
                            break;
                        }
                    }
                }
                if (connectionFieldInPlayer != null) {
                    FppLogger.debug("NmsPlayerSpawner: connection field = " + connectionFieldInPlayer.getName());
                } else {
                    FppLogger.warn("NmsPlayerSpawner: ServerPlayer.connection field not found"
                            + " - fake listener injection will be skipped");
                }
            }

            try {
                Class<?> entityClassForAttack = nmsLoader.loadClass("net.minecraft.world.entity.Entity");
                attackMethod = findMethod(serverPlayerClass, "attack", 1, entityClassForAttack);
                if (attackMethod != null) {
                    FppLogger.debug("NmsPlayerSpawner: attack(Entity) method cached");
                } else {
                    FppLogger.warn(
                            "NmsPlayerSpawner: attack(Entity) method not found - bots will use" + " fallback damage");
                }
            } catch (Exception e) {
                FppLogger.warn("NmsPlayerSpawner: Failed to cache attack method: " + e.getMessage());
            }

            try {
                attackStrengthTickerField = findFieldByName(serverPlayerClass, "attackStrengthTicker");
                FppLogger.debug("NmsPlayerSpawner: attackStrengthTicker field "
                        + (attackStrengthTickerField != null ? "cached" : "not found"));
            } catch (Exception e) {
                FppLogger.warn("NmsPlayerSpawner: Failed to cache attackStrengthTicker field: " + e.getMessage());
            }

            try {
                Class<?> playerListClass = getPlayerListMethod.getReturnType();
                playerListRemoveMethod = findMethod(playerListClass, "remove", 1);

                for (Field f : playerListClass.getDeclaredFields()) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.contains("WorldNBTStorage") || typeName.contains("PlayerDataStorage")) {
                        f.setAccessible(true);
                        playerDataStorageField = f;
                        break;
                    }
                }
                if (playerDataStorageField != null) {
                    Class<?> storageClass = playerDataStorageField.getType();

                    try {
                        getPlayerDirMethod = storageClass.getMethod("getPlayerDir");
                    } catch (Exception ignored) {
                    }

                    for (Method m : storageClass.getDeclaredMethods()) {
                        if ("a".equals(m.getName()) && m.getParameterCount() == 1 && m.getReturnType() == void.class) {
                            m.setAccessible(true);
                            playerDataSaveMethod = m;
                            break;
                        }
                    }
                }
                FppLogger.debug("NmsPlayerSpawner: PlayerList lifecycle - remove="
                        + (playerListRemoveMethod != null ? "ok" : "missing")
                        + " storage="
                        + (playerDataStorageField != null ? "ok" : "missing")
                        + " save="
                        + (playerDataSaveMethod != null ? "ok" : "missing")
                        + " getPlayerDir="
                        + (getPlayerDirMethod != null ? "ok" : "missing"));
            } catch (Exception e) {
                FppLogger.debug("NmsPlayerSpawner: PlayerList lifecycle init failed: " + e.getMessage());
            }

            initialized = true;
            FppLogger.debug("NmsPlayerSpawner initialised (connectionField="
                    + (connectionFieldInPlayer != null)
                    + ", attack="
                    + (attackMethod != null)
                    + ", playerDataDir="
                    + (getPlayerDirMethod != null)
                    + ")");

            try {
                Class<?> playerNmsClass;
                try {
                    playerNmsClass = nmsLoader.loadClass("net.minecraft.world.entity.player.Player");
                } catch (ClassNotFoundException ignored) {
                    playerNmsClass = serverPlayerClass;
                }

                Field spField = findFieldByName(playerNmsClass, "DATA_PLAYER_MODE_CUSTOMISATION");
                if (spField != null && Modifier.isStatic(spField.getModifiers())) {
                    spField.setAccessible(true);
                    skinPartsDataAccessor = spField.get(null);
                }

                Class<?> syncDataClass = nmsLoader.loadClass("net.minecraft.network.syncher.SynchedEntityData");
                for (Method m : syncDataClass.getDeclaredMethods()) {
                    if ("set".equals(m.getName()) && m.getParameterCount() == 2) {
                        m.setAccessible(true);
                        synchedEntityDataSetMethod = m;
                        break;
                    }
                }

                entityDataFieldForSkinParts = findFieldByName(serverPlayerClass, "entityData");

                FppLogger.debug("NmsPlayerSpawner: skin-parts init - accessor="
                        + (skinPartsDataAccessor != null)
                        + " entityData="
                        + (entityDataFieldForSkinParts != null)
                        + " setMethod="
                        + (synchedEntityDataSetMethod != null));
            } catch (Exception e) {
                FppLogger.debug("NmsPlayerSpawner: skin-parts init failed (non-fatal): " + e.getMessage());
            }

        } catch (Exception e) {
            failed = true;
            FppLogger.error("NmsPlayerSpawner.init() failed: " + e.getMessage());
        }
    }

    public static boolean isAvailable() {
        if (!initialized && !failed) init();
        return initialized;
    }

    public static Player spawnFakePlayer(UUID uuid, String name, World world, double x, double y, double z) {
        return spawnFakePlayer(uuid, name, null, world, x, y, z, -1);
    }

    public static Player spawnFakePlayer(
            UUID uuid, String name, SkinProfile skin, World world, double x, double y, double z) {
        return spawnFakePlayer(uuid, name, skin, world, x, y, z, -1);
    }

    public static Player spawnFakePlayer(
            UUID uuid, String name, SkinProfile skin, World world, double x, double y, double z, int initialPing) {
        return spawnFakePlayer(uuid, name, skin, world, x, y, z, 0.0f, 0.0f, initialPing);
    }

    public static Player spawnFakePlayer(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int initialPing) {
        return spawnFakePlayer(uuid, name, skin, world, x, y, z, yaw, pitch, initialPing, true);
    }

    public static Player spawnFakePlayer(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int initialPing,
            boolean loadSavedPlayerData) {
        if (!isAvailable()) {
            FppLogger.warn("NmsPlayerSpawner not available - cannot spawn " + name);
            return null;
        }
        try {

            Object gameProfile = gameProfileConstructor.newInstance(uuid, name);
            if (skin != null && skin.isValid()) {
                try {
                    gameProfile = SkinProfileInjector.createGameProfile(gameProfileClass, uuid, name, skin);
                    FppLogger.debug("NmsPlayerSpawner: injected skin for '" + name + "'");
                } catch (Exception e) {
                    FppLogger.warn("NmsPlayerSpawner: skin injection failed: " + e.getMessage());
                }
            }

            Object minecraftServer = craftServerGetServerMethod.invoke(Bukkit.getServer());
            Object serverLevel = craftWorldGetHandleMethod.invoke(world);
            Object clientInfo = getClientInformation();

            Object serverPlayer = createServerPlayer(minecraftServer, serverLevel, gameProfile, clientInfo);
            if (serverPlayer == null) {
                FppLogger.warn("NmsPlayerSpawner: failed to create ServerPlayer for " + name);
                return null;
            }

            if (setPosMethod != null) setPosMethod.invoke(serverPlayer, x, y, z);
            initPreviousPosition(serverPlayer, x, y, z);

            if (initialPing >= 0) {
                setPingNms(serverPlayer, initialPing);
            }

            Location requested = new Location(world, x, y, z, yaw, pitch);
            Object conn = createFakeConnection();
            if (conn == null) {
                FppLogger.warn("NmsPlayerSpawner: failed to create fake connection for " + name);
                return null;
            }
            prepareJoinCompatibility(conn, serverPlayer, uuid, name);

            FppLogger.debug("NmsPlayerSpawner: spawning '" + name + "' uuid=" + uuid);

            PlayerDataQuarantine playerDataQuarantine =
                    loadSavedPlayerData ? PlayerDataQuarantine.NONE : quarantinePlayerData(minecraftServer, uuid, name);
            boolean placed;
            try {
                pendingSpawnLocations.put(uuid, requested.clone());
                placed = placePlayer(minecraftServer, conn, serverPlayer, gameProfile, clientInfo, uuid);
            } finally {
                pendingSpawnLocations.remove(uuid);
                restoreQuarantinedPlayerData(playerDataQuarantine);
            }
            if (!placed) {
                cleanupFailedSpawn(minecraftServer, serverPlayer, name);
                FppLogger.warn("NmsPlayerSpawner: placeNewPlayer failed for " + name);
                return null;
            }

            if (setPosMethod != null) setPosMethod.invoke(serverPlayer, x, y, z);
            initPreviousPosition(serverPlayer, x, y, z);

            // The bot's connection never goes through a real handshake, so ViaVersion never learns its
            // protocol version on its own - register it as running the server's own native version so
            // Via (and anything querying it) doesn't treat the bot as an unrecognized connection.
            if (conn instanceof FakeConnection fakeConn) {
                ViaVersionCompat.registerBot(fakeConn, uuid, name);
            }

            injectFakeListener(minecraftServer, conn, serverPlayer, gameProfile, clientInfo);

            Method getBukkitEntity = getServerPlayerGetBukkitEntityMethod();
            Object entity = getBukkitEntity.invoke(serverPlayer);
            if (entity instanceof Player result) {
                correctSpawnLocation(serverPlayer, result, requested);
                applyRotation(result, yaw, pitch);
                result.setGameMode(GameMode.SURVIVAL);
                // Respect the tab-list setting at spawn (default hidden) so bots don't flicker into tab.
                setListed(result, Config.tabListEnabled());

                forceAllSkinParts(result);
                firstTickSet.add(uuid);
                refreshAfterTeleport(result);
                FppLogger.debug("NmsPlayerSpawner: spawned " + name + " (" + uuid + ")");
                return result;
            }

            FppLogger.warn("NmsPlayerSpawner: getBukkitEntity did not return a Player for " + name);
            return null;

        } catch (Exception e) {
            FppLogger.error("NmsPlayerSpawner.spawnFakePlayer failed for " + name + ": " + e.getMessage());
            FppLogger.debug(Arrays.toString(e.getStackTrace()));
            return null;
        }
    }

    public static void tickPhysics(Player bot) {
        if (!initialized) return;
        if (!bot.isOnline() || !bot.isValid() || bot.isDead()) return;

        if (dispatchTickPhysicsToRegionThread(bot)) {
            return;
        }

        tickPhysicsInternal(bot);
    }

    private static void tickPhysicsInternal(Player bot) {
        if (!initialized) return;
        if (!bot.isOnline() || !bot.isValid() || bot.isDead()) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();

            if (firstTickSet.remove(bot.getUniqueId())) {

                Location loc = bot.getLocation();
                double x = loc.getX(), y = loc.getY(), z = loc.getZ();

                initPreviousPosition(nmsPlayer, x, y, z);
                nmsPlayer.doTick();

                if (setPosMethod != null) setPosMethod.invoke(nmsPlayer, x, y, z);
                initPreviousPosition(nmsPlayer, x, y, z);

            } else {

                nmsPlayer.doTick();
            }

        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.tickPhysics failed for " + bot.getName() + ": " + e.getMessage());
        }
    }

    private static boolean dispatchTickPhysicsToRegionThread(Player bot) {
        return false;
    }

    private record PlayerDataQuarantine(File original, File backup) {
        private static final PlayerDataQuarantine NONE = new PlayerDataQuarantine(null, null);

        private boolean active() {
            return original != null && backup != null;
        }
    }

    private static PlayerDataQuarantine quarantinePlayerData(Object minecraftServer, UUID uuid, String name) {
        if (playerDataStorageField == null || getPlayerDirMethod == null || uuid == null)
            return PlayerDataQuarantine.NONE;
        try {
            Object playerList = getPlayerListMethod.invoke(minecraftServer);
            Object playerDataStorage = playerDataStorageField.get(playerList);
            File playerDir = (File) getPlayerDirMethod.invoke(playerDataStorage);
            if (playerDir == null) return PlayerDataQuarantine.NONE;
            File original = new File(playerDir, uuid + ".dat");
            if (!original.exists()) return PlayerDataQuarantine.NONE;

            File backup = new File(playerDir, uuid + ".dat.fpp-spawn-tmp");
            int suffix = 0;
            while (backup.exists()) {
                backup = new File(playerDir, uuid + ".dat.fpp-spawn-tmp." + (++suffix));
            }
            if (!original.renameTo(backup)) {
                Config.debugNms("NmsPlayerSpawner: could not quarantine playerdata for " + name);
                return PlayerDataQuarantine.NONE;
            }
            Config.debugNms("NmsPlayerSpawner: quarantined stale playerdata for normal spawn of " + name);
            return new PlayerDataQuarantine(original, backup);
        } catch (Throwable t) {
            Config.debugNms("NmsPlayerSpawner: playerdata quarantine skipped for " + name + ": " + t.getMessage());
            return PlayerDataQuarantine.NONE;
        }
    }

    private static void restoreQuarantinedPlayerData(PlayerDataQuarantine quarantine) {
        if (quarantine == null || !quarantine.active() || !quarantine.backup().exists()) return;
        try {
            if (quarantine.original().exists() && !quarantine.original().delete()) {
                Config.debugNms("NmsPlayerSpawner: could not remove fresh temporary playerdata before restore: "
                        + quarantine.original().getName());
                return;
            }
            if (!quarantine.backup().renameTo(quarantine.original())) {
                Config.debugNms("NmsPlayerSpawner: could not restore quarantined playerdata: "
                        + quarantine.original().getName());
            }
        } catch (Throwable t) {
            Config.debugNms("NmsPlayerSpawner: playerdata restore failed: " + t.getMessage());
        }
    }

    private static void correctSpawnLocation(Object serverPlayer, Player player, Location requested) {
        if (player == null || requested == null || requested.getWorld() == null) return;
        try {
            Location current = player.getLocation();
            boolean wrongWorld =
                    current.getWorld() == null || !current.getWorld().equals(requested.getWorld());
            boolean wrongPosition = wrongWorld || current.distanceSquared(requested) > 0.0001;
            if (wrongPosition) {
                player.teleport(requested, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            if (setPosMethod != null) {
                setPosMethod.invoke(serverPlayer, requested.getX(), requested.getY(), requested.getZ());
            }
            initPreviousPosition(serverPlayer, requested.getX(), requested.getY(), requested.getZ());
        } catch (Throwable t) {
            FppLogger.debug(
                    "NmsPlayerSpawner.correctSpawnLocation failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    public static Location consumePendingSpawnLocation(UUID uuid) {
        if (uuid == null) return null;
        Location location = pendingSpawnLocations.remove(uuid);
        return location == null ? null : location.clone();
    }

    public static void correctPendingSpawnLocation(Player player, Location requested) {
        if (player == null || requested == null || requested.getWorld() == null) return;
        try {
            ServerPlayer serverPlayer = player instanceof CraftPlayer craft ? craft.getHandle() : null;
            if (serverPlayer != null) {
                correctSpawnLocation(serverPlayer, player, requested);
            } else {
                player.teleport(requested, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            applyRotation(player, requested.getYaw(), requested.getPitch());
        } catch (Throwable t) {
            FppLogger.debug("NmsPlayerSpawner.correctPendingSpawnLocation failed for "
                    + player.getName()
                    + ": "
                    + t.getMessage());
        }
    }

    public static void setPosition(Player bot, double x, double y, double z) {
        if (!initialized || setPosMethod == null || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            setPosMethod.invoke(nmsPlayer, x, y, z);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setPosition failed: " + e.getMessage());
        }
    }

    public static void setJumping(Player bot, boolean jumping) {
        if (!initialized || jumpingField == null || craftPlayerGetHandleMethod == null) return;
        Boolean previous = lastJumpingState.put(bot.getUniqueId(), jumping);
        if (previous != null && previous == jumping) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            jumpingField.setBoolean(nmsPlayer, jumping);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setJumping failed: " + e.getMessage());
        }
    }

    public static boolean setListed(Player bot, boolean listed) {
        if (!initialized || listedField == null || craftPlayerGetHandleMethod == null) return false;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            listedField.setBoolean(nmsPlayer, listed);
            return true;
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setListed failed: " + e.getMessage());
            return false;
        }
    }

    public static void setHeadYaw(Player bot, float yaw) {
        if (!initialized || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            if (yHeadRotField != null) {
                yHeadRotField.setFloat(nmsPlayer, yaw);
            }
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setHeadYaw failed: " + e.getMessage());
        }
    }

    private static void applyRotation(Player bot, float yaw, float pitch) {
        try {
            bot.setRotation(yaw, pitch);
            setHeadYaw(bot, yaw);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.applyRotation failed: " + e.getMessage());
        }
    }

    /**
     * Tops up the bot's attack-strength ticker so its next attack lands at full strength. Bots aren't
     * driven by the normal player tick path that advances the ticker, so without this every hit is
     * scaled to the ~20% "spam-click" minimum (a sword deals ~1 damage). Callers are responsible for
     * pacing attacks at the real weapon cooldown.
     */
    public static void primeAttackStrength(Player bot) {
        if (!initialized || attackStrengthTickerField == null || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsBot = ((CraftPlayer) bot).getHandle();
            attackStrengthTickerField.set(nmsBot, 999);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.primeAttackStrength failed: " + e.getMessage());
        }
    }

    public static void performAttack(Player bot, org.bukkit.entity.Entity target, double damage) {
        if (!initialized || craftPlayerGetHandleMethod == null) {

            if (target instanceof Damageable damageable) {
                damageable.damage(damage, bot);
            }
            return;
        }

        try {
            ServerPlayer nmsBot = ((CraftPlayer) bot).getHandle();

            Object nmsTarget = target.getClass().getMethod("getHandle").invoke(target);

            if (attackMethod != null && nmsTarget != null) {
                if (attackStrengthTickerField != null) {
                    attackStrengthTickerField.set(nmsBot, 999);
                }
                attackMethod.invoke(nmsBot, nmsTarget);
            } else {

                if (target instanceof Damageable damageable) {
                    damageable.damage(damage, bot);
                }
            }
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.performAttack failed: " + e.getMessage());

            if (target instanceof Damageable damageable) {
                damageable.damage(damage, bot);
            }
        }
    }

    public static void setMovementForward(Player bot, float forward) {
        if (!initialized || zzaField == null || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            zzaField.setFloat(nmsPlayer, forward);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setMovementForward failed: " + e.getMessage());
        }
    }

    public static void setMovementStrafe(Player bot, float strafe) {
        if (!initialized || xxaField == null || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            xxaField.setFloat(nmsPlayer, strafe);
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.setMovementStrafe failed: " + e.getMessage());
        }
    }

    public static void applyServerVelocity(Player bot, Vector velocity) {
        if (!initialized || craftPlayerGetHandleMethod == null || bot == null || velocity == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            Object vec3 = vec3Class
                    .getConstructor(double.class, double.class, double.class)
                    .newInstance(velocity.getX(), velocity.getY(), velocity.getZ());
            Method setDelta = findMethod(nmsPlayer.getClass(), "setDeltaMovement", 1, vec3Class);
            if (setDelta != null) {
                setDelta.invoke(nmsPlayer, vec3);
            } else {
                bot.setVelocity(velocity);
            }

            Field hurtMarked = findFieldInHierarchy(nmsPlayer.getClass(), "hurtMarked");
            if (hurtMarked != null && hurtMarked.getType() == boolean.class) {
                hurtMarked.setBoolean(nmsPlayer, true);
            }
            Field hasImpulse = findFieldInHierarchy(nmsPlayer.getClass(), "hasImpulse");
            if (hasImpulse != null && hasImpulse.getType() == boolean.class) {
                hasImpulse.setBoolean(nmsPlayer, true);
            }
        } catch (Exception e) {
            try {
                bot.setVelocity(velocity);
            } catch (Exception ignored) {
            }
            FppLogger.debug("NmsPlayerSpawner.applyServerVelocity failed: " + e.getMessage());
        }
    }

    public static void removeFakePlayer(Player player) {
        removeFakePlayer(player, true, "unspecified");
    }

    public static void removeFakePlayer(Player player, String reason) {
        removeFakePlayer(player, true, reason);
    }

    public static void removeFakePlayerFast(Player player) {
        removeFakePlayer(player, false, "unspecified");
    }

    public static void removeFakePlayerFast(Player player, String reason) {
        removeFakePlayer(player, false, reason);
    }

    private static void removeFakePlayer(Player player, boolean saveData) {
        removeFakePlayer(player, saveData, "unspecified");
    }

    private static void removeFakePlayer(Player player, boolean saveData, String reason) {
        if (player == null) return;
        try {
            firstTickSet.remove(player.getUniqueId());
            ViaVersionCompat.unregisterBot(player.getUniqueId());
            if (player.isOnline()) {
                final String name = player.getName();
                final UUID uuid = player.getUniqueId();

                FppLogger.debug(
                        "NMS-BOT",
                        Config.debugNmsBot(),
                        "Removing bot '" + name + "' (uuid=" + uuid + ") - reason: " + reason);

                // Exactly as for a real player: the vehicle the bot is alone in goes with it
                // (PlayerList.remove does that) and is remembered so it can be restored on respawn.
                BotMountMemory.capture(player);
                BotAutoMount.beginRemoval(uuid);

                if (saveData) {
                    try {
                        player.saveData();
                        FppLogger.debug(
                                "NMS-BOT",
                                Config.debugNmsBot(),
                                "Saved playerdata for '" + name + "' (uuid=" + uuid + ")");
                    } catch (Exception e) {
                        FppLogger.warn("NmsPlayerSpawner: saveData failed for '"
                                + name
                                + "' uuid="
                                + uuid
                                + ": "
                                + e.getMessage());
                    }
                } else {
                    FppLogger.debug(
                            "NMS-BOT",
                            Config.debugNmsBot(),
                            "Skipping playerdata save for '" + name + "' (uuid=" + uuid + ") - fast remove");
                }

                boolean removedViaPlayerList = false;
                if (initialized
                        && craftPlayerGetHandleMethod != null
                        && craftServerGetServerMethod != null
                        && getPlayerListMethod != null
                        && playerListRemoveMethod != null) {
                    try {
                        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
                        Object minecraftServer = craftServerGetServerMethod.invoke(Bukkit.getServer());
                        Object playerList = getPlayerListMethod.invoke(minecraftServer);
                        playerListRemoveMethod.invoke(playerList, nmsPlayer);
                        removedViaPlayerList = true;
                        FppLogger.debug(
                                "NMS-BOT",
                                Config.debugNmsBot(),
                                "Removed '" + name + "' via PlayerList.remove() - reason: " + reason);
                    } catch (Exception e) {
                        FppLogger.debug(
                                "NMS-BOT",
                                Config.debugNmsBot(),
                                "PlayerList.remove failed for '"
                                        + name
                                        + "': "
                                        + e.getMessage()
                                        + " - falling back to kick");
                    }
                } else {
                    FppLogger.debug(
                            "NMS-BOT",
                            Config.debugNmsBot(),
                            "NMS methods not initialized for '" + name + "' - using kick fallback");
                }

                if (!removedViaPlayerList && player.isOnline()) {
                    FppLogger.debug(
                            "NMS-BOT",
                            Config.debugNmsBot(),
                            "Kicking '" + name + "' (uuid=" + uuid + ") - reason: " + reason);
                    player.kick(Component.empty());
                }
            }
        } catch (Exception e) {
            FppLogger.error("NmsPlayerSpawner.removeFakePlayer failed for " + player.getName() + " (reason: " + reason
                    + "): " + e.getMessage());
            FppLogger.debug(Arrays.toString(e.getStackTrace()));
        } finally {
            BotAutoMount.endRemoval(player.getUniqueId());
        }
    }

    private static void ensurePlayerDataExists(Object minecraftServer, Object serverPlayer, String name, UUID uuid) {
        if (playerDataStorageField == null) {
            FppLogger.debug("NmsPlayerSpawner: ensurePlayerDataExists skipped"
                    + " - WorldNBTStorage field not cached (name="
                    + name
                    + " uuid="
                    + uuid
                    + ")");
            return;
        }
        try {
            Object playerList = getPlayerListMethod.invoke(minecraftServer);
            Object playerDataStorage = playerDataStorageField.get(playerList);

            if (getPlayerDirMethod != null) {
                File playerDir = (File) getPlayerDirMethod.invoke(playerDataStorage);
                File playerFile = new File(playerDir, uuid + ".dat");
                if (playerFile.exists()) {
                    FppLogger.debug("NmsPlayerSpawner: playerdata found for '"
                            + name
                            + "' uuid="
                            + uuid
                            + " - returning player");
                    return;
                }
            }

            if (playerDataSaveMethod != null) {
                playerDataSaveMethod.invoke(playerDataStorage, serverPlayer);
                FppLogger.debug("NmsPlayerSpawner: created initial playerdata for '"
                        + name
                        + "' uuid="
                        + uuid
                        + " - will be treated as returning player on next spawn");
            } else {
                FppLogger.debug("NmsPlayerSpawner: playerdata file missing but save method"
                        + " not cached - first-join message may appear (name="
                        + name
                        + ")");
            }
        } catch (Exception e) {

            FppLogger.warn("NmsPlayerSpawner: ensurePlayerDataExists failed for '"
                    + name
                    + "' uuid="
                    + uuid
                    + ": "
                    + e.getMessage());
        }
    }

    public static void reInjectFakeListener(Player bot) {
        if (!initialized || craftPlayerGetHandleMethod == null || connectionFieldInPlayer == null) {
            FppLogger.debug("NmsPlayerSpawner.reInjectFakeListener: not available");
            return;
        }
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            if (nmsPlayer == null) return;

            Object currentConn = connectionFieldInPlayer.get(nmsPlayer);
            if (currentConn instanceof FakeServerGamePacketListenerImpl) {

                FppLogger.debug("NmsPlayerSpawner.reInjectFakeListener: "
                        + bot.getName()
                        + " already has FakeServerGamePacketListenerImpl");
                return;
            }

            Object networkConn = null;
            if (currentConn != null && connectionClass != null) {
                for (Field f : getAllDeclaredFields(currentConn.getClass())) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (connectionClass.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        networkConn = f.get(currentConn);
                        break;
                    }
                }
            }
            if (networkConn == null) {

                networkConn = createFakeConnection();
            }
            if (networkConn == null) {
                FppLogger.warn("NmsPlayerSpawner.reInjectFakeListener: cannot get connection for " + bot.getName());
                return;
            }

            Object gameProfile = null;
            try {
                Method gpMethod = findMethodByName(nmsPlayer.getClass(), "getGameProfile", 0);
                if (gpMethod != null) {
                    gpMethod.setAccessible(true);
                    gameProfile = gpMethod.invoke(nmsPlayer);
                }
            } catch (Exception ignored) {
            }
            if (gameProfile == null) {

                for (Field f : getAllDeclaredFields(nmsPlayer.getClass())) {
                    if (f.getType().getSimpleName().equals("GameProfile")) {
                        f.setAccessible(true);
                        gameProfile = f.get(nmsPlayer);
                        break;
                    }
                }
            }

            Object clientInfo = getClientInformation();
            injectFakeListener(
                    craftServerGetServerMethod.invoke(Bukkit.getServer()),
                    networkConn,
                    nmsPlayer,
                    gameProfile,
                    clientInfo);

            clearAwaitingPosition(nmsPlayer);

            firstTickSet.add(bot.getUniqueId());

            FppLogger.debug("NmsPlayerSpawner.reInjectFakeListener: success for " + bot.getName());
        } catch (Exception e) {
            FppLogger.warn("NmsPlayerSpawner.reInjectFakeListener failed for " + bot.getName() + ": " + e.getMessage());
        }
    }

    public static void refreshAfterTeleport(Player bot) {
        if (bot == null || !initialized || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            if (nmsPlayer == null) return;

            reInjectFakeListener(bot);
            clearAwaitingPosition(nmsPlayer);
            clearChangingDimension(nmsPlayer, bot.getName());
            firstTickSet.add(bot.getUniqueId());

            Config.debugNmsDamage("NMS post-teleport refresh completed for "
                    + bot.getName()
                    + " entityId="
                    + bot.getEntityId()
                    + " world="
                    + bot.getWorld().getName());
        } catch (Throwable t) {
            FppLogger.warn("NmsPlayerSpawner.refreshAfterTeleport failed for " + bot.getName() + ": " + t.getMessage());
        }
    }

    private static void clearChangingDimension(Object nmsPlayer, String name) {
        try {
            Method hasChangedDimension = findMethodByName(nmsPlayer.getClass(), "hasChangedDimension", 0);
            if (hasChangedDimension == null) return;
            hasChangedDimension.setAccessible(true);

            Method isChangingDimension = findMethodByName(nmsPlayer.getClass(), "isChangingDimension", 0);
            if (isChangingDimension != null) {
                isChangingDimension.setAccessible(true);
                Object changing = isChangingDimension.invoke(nmsPlayer);
                if (changing instanceof Boolean value && !value) return;
            }

            hasChangedDimension.invoke(nmsPlayer);
            Config.debugNmsDamage("cleared changing-dimension state for bot=" + name);
        } catch (Throwable t) {
            Config.debugNmsDamage("clearChangingDimension skipped for bot=" + name + ": " + t.getMessage());
        }
    }

    private static void clearAwaitingPosition(Object nmsPlayer) {
        try {

            Object sgpl = connectionFieldInPlayer.get(nmsPlayer);
            if (sgpl == null) return;
            for (Field f : getAllDeclaredFields(sgpl.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;

                if (f.getName().equals("awaitingPositionFromClient")
                        || f.getName().contains("awaiting")) {
                    f.setAccessible(true);
                    if (!f.getType().isPrimitive()) {
                        f.set(sgpl, null);
                        FppLogger.debug("NmsPlayerSpawner: cleared " + f.getName() + " on SGPL");
                        return;
                    }
                }
            }

            for (Field f : getAllDeclaredFields(sgpl.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getSimpleName().equals("Vec3")) {
                    f.setAccessible(true);
                    Object val = f.get(sgpl);
                    if (val != null) {
                        f.set(sgpl, null);
                        FppLogger.debug("NmsPlayerSpawner: cleared Vec3 field '"
                                + f.getName()
                                + "' on SGPL (likely awaitingPositionFromClient)");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.clearAwaitingPosition failed: " + e.getMessage());
        }
    }

    private static Method findMethodByName(Class<?> clazz, String name, int paramCount) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    public static void setPing(Player bot, int pingMs) {
        if (bot == null || !initialized) return;
        try {
            Object nmsPlayer = craftBukkitGetHandle(bot);
            if (nmsPlayer == null) return;
            setPingNms(nmsPlayer, pingMs);
        } catch (Exception e) {
            Config.debugNms("setPing failed: " + e.getMessage());
        }
    }

    private static void setPingNms(Object nmsPlayer, int pingMs) {
        int safePing = Math.max(0, pingMs);
        try {
            setLatencyField(nmsPlayer, safePing);
            if (connectionFieldInPlayer != null) {
                connectionFieldInPlayer.setAccessible(true);
                Object listener = connectionFieldInPlayer.get(nmsPlayer);
                setLatencyField(listener, safePing);
            }
        } catch (Exception e) {
            Config.debugNms("setPing failed: " + e.getMessage());
        }
    }

    private static void setLatencyField(Object target, int pingMs) {
        if (target == null) return;
        Field latencyField = findLatencyField(target.getClass());
        if (latencyField == null) return;
        try {
            latencyField.setAccessible(true);
            latencyField.setInt(target, Math.max(0, pingMs));
        } catch (Exception e) {
            Config.debugNms("setLatencyField failed on " + target.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object craftBukkitGetHandle(Player player) {
        try {
            return player.getClass().getMethod("getHandle").invoke(player);
        } catch (Exception e) {
            return null;
        }
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> type, String preferredName) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type && f.getName().equals(preferredName)) {
                    return f;
                }
            }
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type
                        && (f.getName().contains("latency")
                                || f.getName().contains("ping")
                                || f.getName().contains("ping")
                                || f.getName().contains("Latency")
                                || f.getName().contains("Ping"))) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Field findLatencyField(Class<?> clazz) {
        Field cached = latencyFieldCache.get(clazz);
        if (cached != null) return cached;

        Field direct = findFieldByType(clazz, int.class, "latency");
        if (direct != null) {
            direct.setAccessible(true);
            latencyFieldCache.put(clazz, direct);
            return direct;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.equals("ping") || name.equals("latency")) {
                    f.setAccessible(true);
                    latencyFieldCache.put(clazz, f);
                    return f;
                }
            }
        }
        return null;
    }

    public static void startUsingMainHandItem(Player bot) {
        if (!initialized || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            ClassLoader cl = nmsPlayer.getClass().getClassLoader();

            Class<?> interactionHandClass = cl.loadClass("net.minecraft.world.InteractionHand");
            Object[] hands = interactionHandClass.getEnumConstants();
            if (hands == null || hands.length == 0) return;
            Object mainHand = hands[0];

            for (Method m : nmsPlayer.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == interactionHandClass) {
                    String name = m.getName();
                    if (name.equals("startUsingItem") || name.equals("c")) {
                        m.setAccessible(true);
                        m.invoke(nmsPlayer, mainHand);
                        return;
                    }
                }
            }

            for (Method m : nmsPlayer.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == interactionHandClass) {
                    m.setAccessible(true);
                    m.invoke(nmsPlayer, mainHand);
                    return;
                }
            }
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.startUsingMainHandItem failed: " + e.getMessage());
        }
    }

    public static void interactBlock(Player bot, Block block) {
        if (!initialized || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            ClassLoader cl = nmsPlayer.getClass().getClassLoader();

            Class<?> interactionHandClass = cl.loadClass("net.minecraft.world.InteractionHand");
            Object[] hands = interactionHandClass.getEnumConstants();
            if (hands == null || hands.length == 0) return;
            Object mainHand = hands[0];

            Class<?> blockPosClass = cl.loadClass("net.minecraft.core.BlockPos");
            Class<?> directionClass = cl.loadClass("net.minecraft.core.Direction");
            Class<?> blockHitResultClass = cl.loadClass("net.minecraft.world.phys.BlockHitResult");

            Object blockPos = blockPosClass
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(block.getX(), block.getY(), block.getZ());

            Object direction = directionClass
                    .getMethod("getNearest", float.class, float.class, float.class)
                    .invoke(null, 0f, -1f, 0f);

            Object blockHit = blockHitResultClass
                    .getConstructor(Vector.class, directionClass, blockPosClass, boolean.class)
                    .newInstance(new Vector(0.5, 0.5, 0.5), direction, blockPos, false);

            Object gameMode = nmsPlayer.getClass().getMethod("gameMode").invoke(nmsPlayer);
            Object level = nmsPlayer.getClass().getMethod("level").invoke(nmsPlayer);
            Object itemStack = nmsPlayer
                    .getClass()
                    .getMethod("getItemInHand", interactionHandClass)
                    .invoke(nmsPlayer, mainHand);

            Object result = gameMode.getClass()
                    .getMethod(
                            "useItemOn",
                            nmsPlayer.getClass(),
                            level.getClass(),
                            cl.loadClass("net.minecraft.world.item.ItemStack"),
                            interactionHandClass,
                            blockHitResultClass)
                    .invoke(gameMode, nmsPlayer, level, itemStack, mainHand, blockHit);

            if (result != null) {
                Method consumesAction = result.getClass().getMethod("consumesAction");
                if ((boolean) consumesAction.invoke(result)) {
                    Method swing = nmsPlayer.getClass().getMethod("swing", interactionHandClass);
                    swing.invoke(nmsPlayer, mainHand);
                }
            }
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.interactBlock failed: " + e.getMessage());
        }
    }

    public static void forceAllSkinParts(Player bot) {
        if (!initialized
                || skinPartsDataAccessor == null
                || entityDataFieldForSkinParts == null
                || synchedEntityDataSetMethod == null
                || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) bot).getHandle();
            Object entityData = entityDataFieldForSkinParts.get(nmsPlayer);

            synchedEntityDataSetMethod.invoke(entityData, skinPartsDataAccessor, (byte) 0x7F);
            FppLogger.debug("NmsPlayerSpawner: skin-parts forced to 0x7F for " + bot.getName());
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner.forceAllSkinParts failed for " + bot.getName() + ": " + e.getMessage());
        }
    }

    public static void applySkinToGameProfile(Player player, SkinProfile skin) {
        if (player == null || !isAvailable() || craftPlayerGetHandleMethod == null) return;
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            Object gameProfile = resolveGameProfile(nmsPlayer);
            if (gameProfile == null) return;
            if (skin != null && skin.isValid()) SkinProfileInjector.apply(gameProfile, skin);
            else SkinProfileInjector.clear(gameProfile);
            forceAllSkinParts(player);
        } catch (Exception e) {
            FppLogger.debug(
                    "NmsPlayerSpawner.applySkinToGameProfile failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    public static boolean refreshPaperPlayer(Player player) {
        if (player == null || craftPlayerRefreshPlayerMethod == null) return false;
        try {
            craftPlayerRefreshPlayerMethod.invoke(player);
            try {
                ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
                Method triggerHealthUpdate = findMethodByName(nmsPlayer.getClass(), "triggerHealthUpdate", 0);
                if (triggerHealthUpdate != null) {
                    triggerHealthUpdate.setAccessible(true);
                    triggerHealthUpdate.invoke(nmsPlayer);
                }
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            FppLogger.debug(
                    "NmsPlayerSpawner.refreshPaperPlayer failed for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private static Object resolveGameProfile(Object nmsPlayer) {
        if (nmsPlayer == null) return null;
        for (Method method : nmsPlayer.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            String name = method.getName();
            if (!name.equals("getGameProfile") && !name.equals("getProfile") && !name.equals("gameProfile")) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object profile = method.invoke(nmsPlayer);
                if (profile != null && profile.getClass().getName().endsWith("GameProfile")) return profile;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void injectFakeListener(
            Object minecraftServer, Object conn, Object serverPlayer, Object gameProfile, Object clientInfo) {
        if (connectionFieldInPlayer == null) {
            FppLogger.warn("NmsPlayerSpawner: cannot inject fake listener - connection field not found");
            return;
        }
        try {
            Object cookie = createCookieDynamic(gameProfile, clientInfo);
            if (cookie == null) {
                FppLogger.warn("NmsPlayerSpawner: cannot inject fake listener - cookie creation failed");
                return;
            }

            FakeServerGamePacketListenerImpl fakeListener =
                    FakeServerGamePacketListenerImpl.create(minecraftServer, conn, serverPlayer, cookie);

            connectionFieldInPlayer.set(serverPlayer, fakeListener);
            FppLogger.debug(
                    "NmsPlayerSpawner: FakeServerGamePacketListenerImpl injected into" + " serverPlayer.connection");

            injectPacketListenerIntoConnection(conn, fakeListener);

        } catch (Exception e) {
            FppLogger.warn("NmsPlayerSpawner: fake listener injection failed: " + e.getMessage());
            FppLogger.debug(Arrays.toString(e.getStackTrace()));
        }
    }

    private static void injectPacketListenerIntoConnection(Object conn, FakeServerGamePacketListenerImpl fakeListener) {
        if (conn == null || serverGamePacketListenerClass == null) return;
        try {
            for (Field f : getAllDeclaredFields(conn.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(conn);
                    if (val != null && serverGamePacketListenerClass.isInstance(val)) {
                        f.set(conn, fakeListener);
                        FppLogger.debug("NmsPlayerSpawner: Connection."
                                + f.getName()
                                + " updated to FakeServerGamePacketListenerImpl"
                                + " (was "
                                + val.getClass().getSimpleName()
                                + ")");
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            FppLogger.debug("NmsPlayerSpawner: Connection packetListener field not found"
                    + " - onDisconnect override may not fire on double-disconnect");
        } catch (Exception e) {
            FppLogger.debug("NmsPlayerSpawner: injectPacketListenerIntoConnection failed: " + e.getMessage());
        }
    }

    private static Object createFakeConnection() {
        try {
            FakeConnection conn = new FakeConnection(InetAddress.getLoopbackAddress());
            FppLogger.debug("NmsPlayerSpawner: FakeConnection created (direct Connection subclass)");
            return conn;

        } catch (Exception e) {
            FppLogger.warn("NmsPlayerSpawner.createFakeConnection failed: " + e.getMessage());
            return null;
        }
    }

    private static Object getClientInformation() {
        if (clientInfoDefault != null) return clientInfoDefault;
        if (clientInformationClass == null) return null;
        try {
            return clientInformationClass.getMethod("createDefault").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object createServerPlayer(
            Object minecraftServer, Object serverLevel, Object gameProfile, Object clientInfo) {

        if (clientInfo != null && clientInformationClass != null) {
            try {
                Constructor<?> ctor = serverPlayerClass.getConstructor(
                        minecraftServerClass, serverLevelClass, gameProfile.getClass(), clientInformationClass);
                return ctor.newInstance(minecraftServer, serverLevel, gameProfile, clientInfo);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                FppLogger.debug("4-arg ServerPlayer ctor failed: " + e.getMessage());
            }
        }

        try {
            Constructor<?> ctor =
                    serverPlayerClass.getConstructor(minecraftServerClass, serverLevelClass, gameProfile.getClass());
            return ctor.newInstance(minecraftServer, serverLevel, gameProfile);
        } catch (Exception e) {
            FppLogger.error("NmsPlayerSpawner: no ServerPlayer constructor matched: " + e.getMessage());
            return null;
        }
    }

    private static boolean placePlayer(
            Object minecraftServer,
            Object conn,
            Object serverPlayer,
            Object gameProfile,
            Object clientInfo,
            UUID uuid) {
        try {
            Object playerList = getPlayerListMethod.invoke(minecraftServer);
            if (conn == null || commonListenerCookieClass == null) {
                FppLogger.debug("placeNewPlayer skipped (conn=" + conn + ")");
                return false;
            }
            Object cookie = createCookieDynamic(gameProfile, clientInfo);
            if (cookie == null) return false;

            Method placeMethod = getPlaceNewPlayerMethod(playerList.getClass());
            if (placeMethod == null) {
                FppLogger.warn("NmsPlayerSpawner: placeNewPlayer(3-arg) not found on PlayerList");
                return false;
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    List<RegisteredListener> suppressedJoinListeners = suppressHeavyFakePlayerJoinListeners();
                    try {
                        placeMethod.invoke(playerList, conn, serverPlayer, cookie);
                    } finally {
                        restoreSuppressedJoinListeners(suppressedJoinListeners);
                    }
                    if (attempt > 0) {
                        FppLogger.debug("NmsPlayerSpawner.placePlayer succeeded on attempt " + (attempt + 1));
                    }
                    return true;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (isWorldDataNotReadyFailure(cause) && attempt < 4) {
                        FppLogger.debug("NmsPlayerSpawner.placePlayer: world data not ready, retrying (attempt "
                                + (attempt + 1) + "/5) ...");
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;
                    }
                    FppLogger.warn("NmsPlayerSpawner.placePlayer failed: "
                            + cause.getClass().getSimpleName()
                            + ": "
                            + cause.getMessage());
                    return false;
                }
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            FppLogger.warn("NmsPlayerSpawner.placePlayer failed: "
                    + cause.getClass().getSimpleName()
                    + ": "
                    + cause.getMessage());
        }
        return false;
    }

    private static Method getServerPlayerGetBukkitEntityMethod() throws NoSuchMethodException {
        Method method = serverPlayerGetBukkitEntityMethod;
        if (method != null) return method;
        synchronized (NmsPlayerSpawner.class) {
            method = serverPlayerGetBukkitEntityMethod;
            if (method == null) {
                method = serverPlayerClass.getMethod("getBukkitEntity");
                method.setAccessible(true);
                serverPlayerGetBukkitEntityMethod = method;
            }
            return method;
        }
    }

    private static Method getPlaceNewPlayerMethod(Class<?> playerListClass) {
        Method method = playerListPlaceNewPlayerMethod;
        if (method != null) return method;
        synchronized (NmsPlayerSpawner.class) {
            method = playerListPlaceNewPlayerMethod;
            if (method == null) {
                method = findMethod(playerListClass, "placeNewPlayer", 3);
                if (method != null) {
                    method.setAccessible(true);
                    playerListPlaceNewPlayerMethod = method;
                }
            }
            return method;
        }
    }

    private static List<RegisteredListener> suppressHeavyFakePlayerJoinListeners() {
        List<RegisteredListener> suppressed = new ArrayList<>();
        try {
            for (RegisteredListener registered :
                    PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
                if (!isHeavyFakePlayerJoinListener(registered)) continue;
                PlayerJoinEvent.getHandlerList().unregister(registered);
                suppressed.add(registered);
            }
            if (!suppressed.isEmpty()) {
                FppLogger.debug("NmsPlayerSpawner: suppressed "
                        + suppressed.size()
                        + " heavy join listener(s) for fake-player placement.");
            }
        } catch (Throwable t) {
            FppLogger.debug("NmsPlayerSpawner: heavy join suppression failed: " + t.getMessage());
        }
        return suppressed;
    }

    private static void restoreSuppressedJoinListeners(List<RegisteredListener> suppressed) {
        if (suppressed == null || suppressed.isEmpty()) return;
        try {
            for (RegisteredListener registered : suppressed) {
                PlayerJoinEvent.getHandlerList().register(registered);
            }
        } catch (Throwable t) {
            FppLogger.warn("NmsPlayerSpawner: failed to restore suppressed join listener(s): " + t.getMessage());
        }
    }

    private static boolean isHeavyFakePlayerJoinListener(RegisteredListener registered) {
        if (registered == null || registered.getPlugin() == null || registered.getListener() == null) return false;
        String pluginName = registered.getPlugin().getName();
        String listenerClass = registered.getListener().getClass().getName();
        return "voicechat".equalsIgnoreCase(pluginName)
                || "SimpleVoiceChat".equalsIgnoreCase(pluginName)
                || listenerClass.startsWith("de.maxhenkel.voicechat.")
                || "nLogin".equalsIgnoreCase(pluginName)
                || listenerClass.startsWith("com.nickuc.");
    }

    private static boolean isWorldDataNotReadyFailure(Throwable cause) {
        if (cause == null) return false;
        if (!(cause instanceof NullPointerException)) return false;
        String msg = cause.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        boolean matches = lower.contains("getcurrentworlddata")
                || lower.contains("connections")
                || lower.contains("serverlevel")
                || lower.contains("worlddata");
        if (!matches) {
            FppLogger.debug("NmsPlayerSpawner: NPE not matched as world-data failure: " + msg);
        }
        return matches;
    }

    private static final Boolean FOLIA = initializeFolia();

    private static boolean initializeFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFoliaServer() {
        return FOLIA;
    }

    private static void cleanupFailedSpawn(Object minecraftServer, Object serverPlayer, String name) {
        try {
            Method getBukkitEntity = serverPlayerClass.getMethod("getBukkitEntity");
            Object entity = getBukkitEntity.invoke(serverPlayer);
            if (entity instanceof Player player) {
                FppLogger.warn("NmsPlayerSpawner: cleaning up partial failed spawn for " + name);
                removeFakePlayer(player);
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            if (minecraftServer != null && getPlayerListMethod != null && playerListRemoveMethod != null) {
                Object playerList = getPlayerListMethod.invoke(minecraftServer);
                playerListRemoveMethod.invoke(playerList, serverPlayer);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Compatibility entry point for callers that expect a callback-based spawn API.
     */
    public static void spawnFakePlayerAsync(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            Consumer<Player> callback) {
        spawnFakePlayerAsync(uuid, name, skin, world, x, y, z, -1, callback);
    }

    public static void spawnFakePlayerAsync(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            int initialPing,
            Consumer<Player> callback) {
        spawnFakePlayerAsync(uuid, name, skin, world, x, y, z, 0.0f, 0.0f, initialPing, callback);
    }

    public static void spawnFakePlayerAsync(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int initialPing,
            Consumer<Player> callback) {
        spawnFakePlayerAsync(uuid, name, skin, world, x, y, z, yaw, pitch, initialPing, true, callback);
    }

    public static void spawnFakePlayerAsync(
            UUID uuid,
            String name,
            SkinProfile skin,
            World world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int initialPing,
            boolean loadSavedPlayerData,
            Consumer<Player> callback) {
        if (!isAvailable()) {
            FppLogger.warn("NmsPlayerSpawner not available - cannot spawn " + name);
            callback.accept(null);
            return;
        }
        callback.accept(
                spawnFakePlayer(uuid, name, skin, world, x, y, z, yaw, pitch, initialPing, loadSavedPlayerData));
    }

    private static Object createCookieDynamic(Object gameProfile, Object clientInfo) {
        if (commonListenerCookieClass == null) return null;

        try {
            Method factory =
                    commonListenerCookieClass.getMethod("createInitial", gameProfile.getClass(), boolean.class);
            return factory.invoke(null, gameProfile, false);
        } catch (Exception ignored) {
        }

        for (Constructor<?> c : commonListenerCookieClass.getDeclaredConstructors()) {
            c.setAccessible(true);
            Class<?>[] p = c.getParameterTypes();
            if (p.length > 0 && p[p.length - 1].getSimpleName().contains("DefaultConstructorMarker")) {
                continue;
            }
            try {
                Object result =
                        switch (p.length) {
                            case 1 -> c.newInstance(gameProfile);
                            case 2 -> c.newInstance(gameProfile, 0);
                            case 3 -> c.newInstance(gameProfile, 0, clientInfo);
                            case 4 -> c.newInstance(gameProfile, 0, clientInfo, false);
                            case 5 -> c.newInstance(gameProfile, 0, clientInfo, false, false);
                            case 7 -> c.newInstance(
                                    gameProfile, 0, clientInfo, false, null, Collections.emptySet(), null);
                            default -> null;
                        };
                if (result != null) return result;
            } catch (Exception ignored) {
            }
        }
        FppLogger.debug("NmsPlayerSpawner: no CommonListenerCookie constructor succeeded");
        return null;
    }

    private static void prepareJoinCompatibility(Object conn, Object serverPlayer, UUID uuid, String name) {
        Player bukkitPlayer = resolveBukkitPlayer(serverPlayer);
        markFakePlayerMetadata(bukkitPlayer);
        registerPacketEventsUser(conn, bukkitPlayer, uuid, name);
    }

    private static Player resolveBukkitPlayer(Object serverPlayer) {
        if (serverPlayer == null || serverPlayerClass == null) return null;
        try {
            Method getBukkitEntity = serverPlayerClass.getMethod("getBukkitEntity");
            Object entity = getBukkitEntity.invoke(serverPlayer);
            return entity instanceof Player player ? player : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void markFakePlayerMetadata(Player player) {
        Plugin plugin = FakePlayerPlugin.getInstance();
        if (plugin == null || player == null) return;
        try {
            player.getPersistentDataContainer().remove(new NamespacedKey(plugin, "npc"));
            player.getPersistentDataContainer()
                    .set(new NamespacedKey(plugin, "fpp"), PersistentDataType.BYTE, (byte) 1);
            player.getPersistentDataContainer()
                    .set(new NamespacedKey(plugin, "fakeplayerplugin"), PersistentDataType.BYTE, (byte) 1);
        } catch (Throwable t) {
            FppLogger.debug("NmsPlayerSpawner: fake-player metadata failed: " + t.getMessage());
        }
    }

    private static void registerPacketEventsUser(Object conn, Player bukkitPlayer, UUID uuid, String name) {
        Object channel = resolveConnectionChannel(conn);
        if (channel == null || bukkitPlayer == null || uuid == null || name == null) return;

        Set<ClassLoader> tried = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String pluginName = plugin.getName().toLowerCase(Locale.ROOT);
            if (!pluginName.contains("grim") && !pluginName.contains("packetevents")) continue;
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (!tried.add(loader)) continue;
            try {
                if (tryRegisterPacketEventsUser(loader, channel, bukkitPlayer, uuid, name)) return;
            } catch (Throwable t) {
                FppLogger.debug("NmsPlayerSpawner: PacketEvents registration failed for "
                        + name
                        + ": "
                        + t.getClass().getSimpleName()
                        + " - "
                        + t.getMessage());
            }
        }
    }

    private static Object resolveConnectionChannel(Object conn) {
        if (conn == null) return null;
        try {
            Field channelField = findFieldByName(conn.getClass(), "channel");
            return channelField != null ? channelField.get(conn) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean tryRegisterPacketEventsUser(
            ClassLoader loader, Object channel, Player bukkitPlayer, UUID uuid, String name) {
        for (String prefix : new String[] {
            "com.github.retrooper.packetevents", "ac.grim.grimac.shaded.com.github.retrooper.packetevents"
        }) {
            try {
                Class<?> packetEventsClass = Class.forName(prefix + ".PacketEvents", false, loader);
                Object api = invokeNoArg(packetEventsClass, null, "getAPI");
                if (api == null) continue;

                try {
                    if (!Boolean.TRUE.equals(invokeNoArg(api.getClass(), api, "isInitialized"))) continue;
                } catch (NoSuchMethodException ignored) {
                }

                Object injector = invokeNoArg(api.getClass(), api, "getInjector");
                if (injector == null) continue;

                Class<?> connectionStateClass = Class.forName(prefix + ".protocol.ConnectionState", false, loader);
                Class<?> clientVersionClass = Class.forName(prefix + ".protocol.player.ClientVersion", false, loader);
                Class<?> userProfileClass = Class.forName(prefix + ".protocol.player.UserProfile", false, loader);
                Class<?> userClass = Class.forName(prefix + ".protocol.player.User", false, loader);

                Object playState = Enum.valueOf(connectionStateClass.asSubclass(Enum.class), "PLAY");
                Object clientVersion = resolvePacketEventsClientVersion(api, clientVersionClass);
                Object profile = userProfileClass
                        .getConstructor(UUID.class, String.class)
                        .newInstance(uuid, name);

                tryInitialisePacketEventsChannel(loader, prefix, channel, playState, connectionStateClass);

                Object user = userClass
                        .getConstructor(Object.class, connectionStateClass, clientVersionClass, userProfileClass)
                        .newInstance(channel, playState, clientVersion, profile);

                try {
                    userClass.getMethod("setEntityId", int.class).invoke(user, bukkitPlayer.getEntityId());
                } catch (NoSuchMethodException ignored) {
                }

                Object protocolManager = invokeNoArg(api.getClass(), api, "getProtocolManager");
                invokeMethod(protocolManager, "setChannel", new Class<?>[] {UUID.class, Object.class}, uuid, channel);
                invokeMethod(protocolManager, "setUser", new Class<?>[] {Object.class, userClass}, channel, user);
                putPacketEventsProtocolMaps(loader, prefix, channel, uuid, user);
                patchPacketEventsHandlerUser(loader, prefix, channel, user, bukkitPlayer);

                try {
                    invokeMethod(injector, "updateUser", new Class<?>[] {Object.class, userClass}, channel, user);
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    invokeMethod(
                            injector, "setPlayer", new Class<?>[] {Object.class, Object.class}, channel, bukkitPlayer);
                } catch (NoSuchMethodException ignored) {
                }

                FppLogger.debug("NmsPlayerSpawner: PacketEvents fake user registered for " + name);
                return true;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                FppLogger.warn("NmsPlayerSpawner: PacketEvents fake user registration skipped: " + t.getMessage());
            }
        }
        return false;
    }

    private static Object invokeNoArg(Class<?> owner, Object target, String name) throws Exception {
        Method method = owner.getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invokeMethod(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    private static void putPacketEventsProtocolMaps(
            ClassLoader loader, String prefix, Object channel, UUID uuid, Object user) throws Exception {
        Class<?> protocolManagerClass = Class.forName(prefix + ".manager.protocol.ProtocolManager", false, loader);
        Field channelsField = protocolManagerClass.getField("CHANNELS");
        Field usersField = protocolManagerClass.getField("USERS");
        Object channels = channelsField.get(null);
        Object users = usersField.get(null);
        if (channels instanceof Map channelsMap) channelsMap.put(uuid, channel);
        if (users instanceof Map usersMap) usersMap.put(channel, user);
    }

    private static void patchPacketEventsHandlerUser(
            ClassLoader loader, String prefix, Object channel, Object user, Player bukkitPlayer) {
        String injectorPrefix = prefix.startsWith("ac.grim.grimac.shaded")
                ? "ac.grim.grimac.shaded.io.github.retrooper.packetevents"
                : "io.github.retrooper.packetevents";
        try {
            Class<?> encoderClass =
                    Class.forName(injectorPrefix + ".injector.handlers.PacketEventsEncoder", false, loader);
            Class<?> decoderClass =
                    Class.forName(injectorPrefix + ".injector.handlers.PacketEventsDecoder", false, loader);
            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            patchPacketEventsHandler(pipeline, encoderClass, user, bukkitPlayer);
            patchPacketEventsHandler(pipeline, decoderClass, user, bukkitPlayer);
        } catch (Throwable ignored) {
        }
    }

    private static void patchPacketEventsHandler(
            Object pipeline, Class<?> handlerClass, Object user, Player bukkitPlayer) {
        try {
            Object handler = pipeline.getClass().getMethod("get", Class.class).invoke(pipeline, handlerClass);
            if (handler == null) return;
            Field userField = findFieldByName(handler.getClass(), "user");
            if (userField != null) userField.set(handler, user);
            Field playerField = findFieldByName(handler.getClass(), "player");
            if (playerField != null) playerField.set(handler, bukkitPlayer);
        } catch (Throwable ignored) {
        }
    }

    private static void tryInitialisePacketEventsChannel(
            ClassLoader loader,
            String packetEventsPrefix,
            Object channel,
            Object playState,
            Class<?> connectionStateClass) {
        String injectorPrefix = packetEventsPrefix.startsWith("ac.grim.grimac.shaded")
                ? "ac.grim.grimac.shaded.io.github.retrooper.packetevents"
                : "io.github.retrooper.packetevents";
        try {
            Class<?> initializerClass =
                    Class.forName(injectorPrefix + ".injector.connection.ServerConnectionInitializer", false, loader);
            initializerClass
                    .getMethod("initChannel", Object.class, connectionStateClass)
                    .invoke(null, channel, playState);
        } catch (Throwable t) {
            FppLogger.debug("NmsPlayerSpawner: PacketEvents channel init skipped: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolvePacketEventsClientVersion(Object api, Class<?> clientVersionClass) {
        try {
            Object serverManager = invokeNoArg(api.getClass(), api, "getServerManager");
            Object serverVersion = invokeNoArg(serverManager.getClass(), serverManager, "getVersion");
            Object clientVersion = invokeNoArg(serverVersion.getClass(), serverVersion, "toClientVersion");
            if (clientVersionClass.isInstance(clientVersion)) return clientVersion;
        } catch (Throwable ignored) {
        }

        try {
            return clientVersionClass.getMethod("getLatest").invoke(null);
        } catch (Throwable ignored) {
        }
        return Enum.valueOf(clientVersionClass.asSubclass(Enum.class), "UNKNOWN");
    }

    private static void initPreviousPosition(Object nmsPlayer, double x, double y, double z) {
        try {
            if (xoField != null) xoField.setDouble(nmsPlayer, x);
            if (yoField != null) yoField.setDouble(nmsPlayer, y);
            if (zoField != null) zoField.setDouble(nmsPlayer, z);
        } catch (Exception ignored) {
        }
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount, Class<?>... paramTypes) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != paramCount) continue;
                if (paramTypes.length == 0) {
                    m.setAccessible(true);
                    return m;
                }

                Class<?>[] mParams = m.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < paramTypes.length && i < mParams.length; i++) {
                    if (!mParams[i].isAssignableFrom(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethodBySignature(Class<?> clazz, int paramCount, Class<?>... paramTypes) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getParameterCount() == paramCount && Arrays.equals(m.getParameterTypes(), paramTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Field findFieldByName(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static List<Field> getAllDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            Collections.addAll(fields, cur.getDeclaredFields());
            cur = cur.getSuperclass();
        }
        return fields;
    }

    // ── Version-safe NMS helpers (Paper 26.1.x / MC 1.21.2+ compatibility) ──
    // Each helper tries the direct NMS call first (fast path, zero overhead after
    // first success). On NoSuchMethodError it falls back to ClassLoader-based
    // reflection that works regardless of method renames or signature changes.

    // ── useItemOn ──

    private static volatile int useItemOnProbeState; // 0=untried, 1=direct-works, 2=need-reflection
    private static volatile Method useItemOnReflect;

    public static Object useItemOn(ServerPlayer nms, InteractionHand hand, BlockHitResult blockHit) {
        if (useItemOnProbeState == 0) {
            try {
                var r = nms.gameMode.useItemOn(nms, nms.level(), nms.getItemInHand(hand), hand, blockHit);
                useItemOnProbeState = 1;
                return r;
            } catch (NoSuchMethodError e) {
                useItemOnProbeState = 2;
            }
        } else if (useItemOnProbeState == 1) {
            return nms.gameMode.useItemOn(nms, nms.level(), nms.getItemInHand(hand), hand, blockHit);
        }
        // reflection path
        if (useItemOnReflect == null) {
            synchronized (NmsPlayerSpawner.class) {
                if (useItemOnReflect == null) {
                    Class<?> gmClass = nms.gameMode.getClass();
                    ClassLoader cl = gmClass.getClassLoader();
                    try {
                        Class<?> itemStackClass = cl.loadClass("net.minecraft.world.item.ItemStack");
                        Class<?> handClass = cl.loadClass("net.minecraft.world.InteractionHand");
                        Class<?> hitClass = cl.loadClass("net.minecraft.world.phys.BlockHitResult");
                        Class<?> playerClass = cl.loadClass("net.minecraft.server.level.ServerPlayer");
                        Class<?> levelClass = cl.loadClass("net.minecraft.server.level.ServerLevel");
                        // try 5-arg (MC < 1.21.2)
                        for (Method m : gmClass.getMethods()) {
                            if (m.getParameterCount() == 5
                                    && m.getParameterTypes()[0] == playerClass
                                    && m.getParameterTypes()[2] == itemStackClass
                                    && m.getParameterTypes()[3] == handClass
                                    && m.getParameterTypes()[4] == hitClass) {
                                m.setAccessible(true);
                                useItemOnReflect = m;
                                break;
                            }
                        }
                        // try 3-arg (MC 1.21.2+)
                        if (useItemOnReflect == null) {
                            for (Method m : gmClass.getMethods()) {
                                if (m.getParameterCount() == 3
                                        && m.getParameterTypes()[0] == itemStackClass
                                        && m.getParameterTypes()[1] == handClass
                                        && m.getParameterTypes()[2] == hitClass) {
                                    m.setAccessible(true);
                                    useItemOnReflect = m;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        if (useItemOnReflect != null) {
            try {
                Object itemStack = nms.getItemInHand(hand);
                if (useItemOnReflect.getParameterCount() == 5) {
                    return useItemOnReflect.invoke(nms.gameMode, nms, nms.level(), itemStack, hand, blockHit);
                } else {
                    return useItemOnReflect.invoke(nms.gameMode, itemStack, hand, blockHit);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ── useItem ──

    private static volatile int useItemProbeState;
    private static volatile Method useItemReflect;

    public static Object useItem(ServerPlayer nms, InteractionHand hand) {
        if (useItemProbeState == 0) {
            try {
                var r = nms.gameMode.useItem(nms, nms.level(), nms.getItemInHand(hand), hand);
                useItemProbeState = 1;
                return r;
            } catch (NoSuchMethodError e) {
                useItemProbeState = 2;
            }
        } else if (useItemProbeState == 1) {
            return nms.gameMode.useItem(nms, nms.level(), nms.getItemInHand(hand), hand);
        }
        if (useItemReflect == null) {
            synchronized (NmsPlayerSpawner.class) {
                if (useItemReflect == null) {
                    Class<?> gmClass = nms.gameMode.getClass();
                    ClassLoader cl = gmClass.getClassLoader();
                    try {
                        Class<?> itemStackClass = cl.loadClass("net.minecraft.world.item.ItemStack");
                        Class<?> handClass = cl.loadClass("net.minecraft.world.InteractionHand");
                        Class<?> playerClass = cl.loadClass("net.minecraft.server.level.ServerPlayer");
                        Class<?> levelClass = cl.loadClass("net.minecraft.server.level.ServerLevel");
                        // try 4-arg (MC < 1.21.2)
                        for (Method m : gmClass.getMethods()) {
                            if (m.getParameterCount() == 4
                                    && m.getParameterTypes()[0] == playerClass
                                    && m.getParameterTypes()[1] == levelClass
                                    && m.getParameterTypes()[2] == itemStackClass
                                    && m.getParameterTypes()[3] == handClass) {
                                m.setAccessible(true);
                                useItemReflect = m;
                                break;
                            }
                        }
                        // try 2-arg (MC 1.21.2+)
                        if (useItemReflect == null) {
                            for (Method m : gmClass.getMethods()) {
                                if (m.getParameterCount() == 2
                                        && m.getParameterTypes()[0] == itemStackClass
                                        && m.getParameterTypes()[1] == handClass) {
                                    m.setAccessible(true);
                                    useItemReflect = m;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        if (useItemReflect != null) {
            try {
                Object itemStack = nms.getItemInHand(hand);
                if (useItemReflect.getParameterCount() == 4) {
                    return useItemReflect.invoke(nms.gameMode, nms, nms.level(), itemStack, hand);
                } else {
                    return useItemReflect.invoke(nms.gameMode, itemStack, hand);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ── handleBlockBreakAction ──

    private static volatile int breakActionProbeState;
    private static volatile Method breakActionReflect;

    public static void handleBlockBreakAction(
            ServerPlayer nms,
            BlockPos pos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction,
            int maxY,
            int sequence) {
        if (breakActionProbeState == 0) {
            try {
                nms.gameMode.handleBlockBreakAction(pos, action, direction, maxY, sequence);
                breakActionProbeState = 1;
                return;
            } catch (NoSuchMethodError e) {
                breakActionProbeState = 2;
            }
        } else if (breakActionProbeState == 1) {
            nms.gameMode.handleBlockBreakAction(pos, action, direction, maxY, sequence);
            return;
        }
        if (breakActionReflect == null) {
            synchronized (NmsPlayerSpawner.class) {
                if (breakActionReflect == null) {
                    Class<?> gmClass = nms.gameMode.getClass();
                    ClassLoader cl = gmClass.getClassLoader();
                    try {
                        Class<?> bpClass = cl.loadClass("net.minecraft.core.BlockPos");
                        Class<?> actionClass = cl.loadClass(
                                "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action");
                        // try 5-arg, then 3-arg, then scan by param count
                        for (Method m : gmClass.getMethods()) {
                            if (m.getParameterCount() == 5
                                    && m.getParameterTypes()[0] == bpClass
                                    && m.getParameterTypes()[1] == actionClass) {
                                m.setAccessible(true);
                                breakActionReflect = m;
                                break;
                            }
                        }
                        if (breakActionReflect == null) {
                            for (Method m : gmClass.getMethods()) {
                                if (m.getParameterCount() == 3
                                        && m.getParameterTypes()[0] == bpClass
                                        && m.getParameterTypes()[1] == actionClass) {
                                    m.setAccessible(true);
                                    breakActionReflect = m;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        if (breakActionReflect != null) {
            try {
                if (breakActionReflect.getParameterCount() == 5) {
                    breakActionReflect.invoke(nms.gameMode, pos, action, direction, maxY, sequence);
                } else {
                    breakActionReflect.invoke(nms.gameMode, pos, action, sequence);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ── destroyBlockProgress ──

    private static volatile int destroyProgressProbeState;
    private static volatile Method destroyProgressReflect;

    public static void destroyBlockProgress(ServerPlayer nms, int breakerId, BlockPos pos, int progress) {
        if (destroyProgressProbeState == 0) {
            try {
                nms.level().destroyBlockProgress(breakerId, pos, progress);
                destroyProgressProbeState = 1;
                return;
            } catch (NoSuchMethodError e) {
                destroyProgressProbeState = 2;
            }
        } else if (destroyProgressProbeState == 1) {
            nms.level().destroyBlockProgress(breakerId, pos, progress);
            return;
        }
        if (destroyProgressReflect == null) {
            synchronized (NmsPlayerSpawner.class) {
                if (destroyProgressReflect == null) {
                    Class<?> levelClass = nms.level().getClass();
                    ClassLoader cl = levelClass.getClassLoader();
                    try {
                        Class<?> bpClass = cl.loadClass("net.minecraft.core.BlockPos");
                        Class<?> entityClass = cl.loadClass("net.minecraft.world.entity.Entity");
                        // try 3-arg (int, BlockPos, int)
                        for (Method m : levelClass.getMethods()) {
                            if (m.getParameterCount() == 3
                                    && m.getParameterTypes()[1] == bpClass
                                    && m.getParameterTypes()[0] == int.class) {
                                m.setAccessible(true);
                                destroyProgressReflect = m;
                                break;
                            }
                        }
                        // try 3-arg (Entity, BlockPos, int) - MC 1.21.2+
                        if (destroyProgressReflect == null) {
                            for (Method m : levelClass.getMethods()) {
                                if (m.getParameterCount() == 3
                                        && m.getParameterTypes()[0] == entityClass
                                        && m.getParameterTypes()[1] == bpClass) {
                                    m.setAccessible(true);
                                    destroyProgressReflect = m;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        if (destroyProgressReflect != null) {
            try {
                Object firstArg = destroyProgressReflect.getParameterTypes()[0] == int.class ? breakerId : nms;
                destroyProgressReflect.invoke(nms.level(), firstArg, pos, progress);
            } catch (Exception ignored) {
            }
        }
    }

    // ── startSleepInBed ──

    private static volatile int sleepProbeState;
    private static volatile Method sleepReflect;

    public static void startSleepInBed(ServerPlayer nms, BlockPos pos, boolean force) {
        if (sleepProbeState == 0) {
            try {
                nms.startSleepInBed(pos, force);
                sleepProbeState = 1;
                return;
            } catch (NoSuchMethodError e) {
                sleepProbeState = 2;
            }
        } else if (sleepProbeState == 1) {
            nms.startSleepInBed(pos, force);
            return;
        }
        if (sleepReflect == null) {
            synchronized (NmsPlayerSpawner.class) {
                if (sleepReflect == null) {
                    ClassLoader cl = nms.getClass().getClassLoader();
                    try {
                        Class<?> bpClass = cl.loadClass("net.minecraft.core.BlockPos");
                        // walk class hierarchy for startSleepInBed(BlockPos, ...) or startSleepInBed(BlockPos)
                        Class<?> cur = nms.getClass();
                        while (cur != null && cur != Object.class) {
                            for (Method m : cur.getDeclaredMethods()) {
                                if (m.getName().equals("startSleepInBed")
                                        && m.getParameterCount() >= 1
                                        && m.getParameterTypes()[0] == bpClass) {
                                    m.setAccessible(true);
                                    sleepReflect = m;
                                    break;
                                }
                            }
                            if (sleepReflect != null) break;
                            cur = cur.getSuperclass();
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
        }
        if (sleepReflect != null) {
            try {
                if (sleepReflect.getParameterCount() >= 2) {
                    sleepReflect.invoke(nms, pos, force);
                } else {
                    sleepReflect.invoke(nms, pos);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ── interactOn ──

    /**
     * Right-click-on-entity simulation: routes through the same {@code Player#interactOn} entry
     * point a real client's interact packet uses, so feeding/taming/breeding, shearing, milking,
     * boarding vehicles, leashing, name-tagging, villager trading, etc. all behave exactly as they
     * would for a real player.
     */
    public static boolean interactOnEntity(
            ServerPlayer nms, net.minecraft.world.entity.Entity entity, InteractionHand hand, Vec3 hitLocation) {
        try {
            var result = nms.interactOn(entity, hand, hitLocation);
            boolean consumed = result != null && result.consumesAction();

            // Fake players have no real client to drive a container/trade screen any further - if
            // this interaction opened one (villager trade, chest minecart, etc.) close it immediately
            // so the entity isn't left "busy" and blocking real players from interacting with it.
            if (nms.containerMenu != null && nms.containerMenu != nms.inventoryMenu) {
                try {
                    nms.closeContainer();
                } catch (Throwable ignored) {
                }
            }

            return consumed;
        } catch (Throwable e) {
            Config.debugNms("NmsPlayerSpawner.interactOnEntity failed: " + e.getMessage());
            return false;
        }
    }

    // ── consumesAction helper ──

    public static boolean consumesAction(Object result) {
        if (result == null) return false;
        try {
            Method m = result.getClass().getMethod("consumesAction");
            return (boolean) m.invoke(result);
        } catch (Exception e) {
            return false;
        }
    }
}
