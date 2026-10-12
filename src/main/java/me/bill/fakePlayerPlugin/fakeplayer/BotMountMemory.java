package me.bill.fakePlayerPlugin.fakeplayer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

/**
 * Gives a bot the "log out in a boat, log back in still in the boat" behaviour of a real player.
 *
 * <p>What vanilla does (26.3 {@code PlayerList#remove}, {@code ServerPlayer#addAdditionalSaveData},
 * {@code PlayerList#placeNewPlayer}): a player leaving while riding is dismounted; if they were the
 * <em>only</em> player on the root vehicle ({@code Entity#hasExactlyOnePlayerPassenger}) the vehicle
 * and its non-player passengers are removed with them, and the vehicle tree is stored in the player
 * data ({@code RootVehicle}) so the next join re-creates it and seats the player on the entity they
 * sat on. If other players are aboard, the vehicle simply stays where it is.
 *
 * <p>Bots are removed by the same {@code PlayerList#remove} but their player data is not loaded on
 * spawn, so this class does the other half:
 * <ul>
 *   <li><b>Sole rider:</b> the vehicle is stored just before it is removed - full NBT where the
 *       server can serialise it, and always its type and pose so it can at least be re-created - and
 *       is put back where it was, with the bot seated in it, when the bot spawns again.</li>
 *   <li><b>Shared vehicle</b> (a real player is aboard): the vehicle is not touched. Its UUID is
 *       remembered, and the bot boards that same vehicle again if it still exists and has a free
 *       seat, behind whoever is already controlling it.</li>
 * </ul>
 */
public final class BotMountMemory {

    private record Entry(
            UUID worldUid,
            String type,
            String data, // null when the server could not serialise it
            boolean shared,
            UUID rootUuid,
            UUID attachUuid,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {}

    private static final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private BotMountMemory() {}

    // ── capture (on removal) ──────────────────────────────────────────────────────────────

    /**
     * Remembers the vehicle {@code bot} is riding. Must run before the bot is removed, because the
     * removal deletes a vehicle the bot was alone in.
     */
    public static void capture(Player bot) {
        if (bot == null) return;
        try {
            var nms = ((CraftPlayer) bot).getHandle();
            var root = nms.getRootVehicle();
            Entity attach = bot.getVehicle();
            if (root == null || root == nms || attach == null) {
                entries.remove(bot.getUniqueId());
                return;
            }
            Entity rootBukkit = root.getBukkitEntity();
            boolean shared = !root.hasExactlyOnePlayerPassenger();
            Location at = rootBukkit.getLocation();

            String data = null;
            if (!shared) {
                try {
                    // Public snapshot API (SNBT string) - no UnsafeValues needed.
                    EntitySnapshot snapshot = rootBukkit.createSnapshot();
                    if (snapshot != null) data = snapshot.getAsString();
                } catch (Throwable t) {
                    // Not fatal: the vehicle is re-created from its type and pose instead.
                    FppLogger.warn("BotMountMemory: could not serialise " + rootBukkit.getType() + " of "
                            + bot.getName() + " (" + t + ") - it will be re-created without its saved data.");
                }
            }

            entries.put(
                    bot.getUniqueId(),
                    new Entry(
                            at.getWorld().getUID(),
                            rootBukkit.getType().name(),
                            data,
                            shared,
                            rootBukkit.getUniqueId(),
                            attach.getUniqueId(),
                            at.getX(),
                            at.getY(),
                            at.getZ(),
                            at.getYaw(),
                            at.getPitch()));
            save();
            FppLogger.info("BotMountMemory: " + bot.getName() + " leaves " + (shared ? "shared " : "")
                    + rootBukkit.getType() + " at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
        } catch (Throwable t) {
            FppLogger.warn("BotMountMemory.capture failed for " + bot.getName() + ": " + t);
        }
    }

    // ── restore (on spawn) ─────────────────────────────────────────────────────────────────

    /**
     * Where {@code ids} (logical or live UUID of the bot) should reappear: the vehicle itself if it is
     * still in the world (it may have been driven away by another player), else where it was left.
     * {@code null} if nothing is remembered.
     */
    public static Location pendingLocation(UUID... ids) {
        ensureLoaded();
        for (UUID id : ids) {
            Entry e = id == null ? null : entries.get(id);
            if (e == null) continue;
            Entity live = Bukkit.getEntity(e.rootUuid);
            if (live != null && live.isValid()) return live.getLocation();
            if (e.shared) continue; // the shared vehicle is gone - nothing to rejoin
            World w = Bukkit.getWorld(e.worldUid);
            if (w != null) return new Location(w, e.x, e.y, e.z, e.yaw, e.pitch);
        }
        return null;
    }

    /** Drops a remembered mount without restoring it. */
    public static void forget(UUID botUuid) {
        if (botUuid != null && entries.remove(botUuid) != null) save();
    }

    /**
     * Brings the vehicle back (or finds the one that stayed) and seats the bot in it. Completes with
     * {@code false} when that was not possible; the reason is logged. A vehicle created here is
     * discarded again in that case, like vanilla does when it cannot reattach the player.
     */
    public static CompletableFuture<Boolean> restore(Player bot, UUID logicalUuid) {
        ensureLoaded();
        Entry e = entries.remove(logicalUuid);
        if (e == null) e = entries.remove(bot.getUniqueId());
        if (e == null) return CompletableFuture.completedFuture(false);
        save();
        final Entry entry = e;
        final String name = bot.getName();
        try {
            World world = Bukkit.getWorld(entry.worldUid);
            if (world == null) {
                FppLogger.warn("BotMountMemory: world of " + name + "'s vehicle is not loaded.");
                return CompletableFuture.completedFuture(false);
            }
            Location at = new Location(world, entry.x, entry.y, entry.z, entry.yaw, entry.pitch);

            // A vehicle that is still there (another real player kept it alive, or it was never
            // removed) is simply boarded again.
            Entity live = Bukkit.getEntity(entry.rootUuid);
            Entity root = live != null && live.isValid() ? live : null;
            boolean created = false;
            if (root == null) {
                if (entry.shared) {
                    FppLogger.info("BotMountMemory: " + name + "'s shared vehicle no longer exists.");
                    return CompletableFuture.completedFuture(false);
                }
                root = recreate(entry, world, at, name);
                if (root == null) return CompletableFuture.completedFuture(false);
                created = true;
            }

            final Entity vehicleRoot = root;
            final boolean wasCreated = created;
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            bot.teleportAsync(vehicleRoot.getLocation()).thenAccept(ok -> {
                // One tick later: the vehicle is then fully part of the world and the teleport has
                // settled, so seating cannot race either of them.
                FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
                Runnable seat = () -> result.complete(seatBot(bot, vehicleRoot, entry, wasCreated, name));
                if (plugin != null) FppScheduler.runAtEntityLaterWithId(plugin, vehicleRoot, seat, 1L);
                else seat.run();
            });
            return result;
        } catch (Throwable t) {
            FppLogger.warn("BotMountMemory: restore failed for " + name + ": " + t);
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Re-creates the vehicle from its saved data, falling back to a fresh entity of the same type. */
    private static Entity recreate(Entry entry, World world, Location at, String name) {
        if (entry.data != null) {
            try {
                EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(entry.data);
                Entity e = snapshot.createEntity(at); // spawns it at 'at'
                if (e != null) return e;
                FppLogger.warn("BotMountMemory: saved " + entry.type + " of " + name + " could not be placed;"
                        + " creating a new one.");
            } catch (Throwable t) {
                FppLogger.warn("BotMountMemory: saved " + entry.type + " of " + name + " could not be loaded ("
                        + t + "); creating a new one.");
            }
        }
        try {
            EntityType type = EntityType.valueOf(entry.type);
            Entity fresh = world.spawnEntity(at, type);
            fresh.setRotation(at.getYaw(), at.getPitch());
            return fresh;
        } catch (Throwable t) {
            FppLogger.warn("BotMountMemory: could not create a " + entry.type + " for " + name + ": " + t);
            return null;
        }
    }

    private static boolean seatBot(Player bot, Entity vehicleRoot, Entry entry, boolean created, String name) {
        try {
            // The exact entity the bot sat on if it survived the round trip, else the root vehicle.
            Entity attach = findByUuid(vehicleRoot, entry.attachUuid);
            if (attach == null) attach = vehicleRoot;

            String why = null;
            if (!vehicleRoot.isValid()) why = "vehicle is not valid";
            else if (!bot.isValid()) why = "bot is not valid";
            else if (attach.getPassengers().size() >= capacity(attach)) why = "vehicle is full";
            else {
                // The force-attach vanilla uses for a rejoining player: startRiding(vehicle, true, false).
                var nmsBot = ((CraftPlayer) bot).getHandle();
                var nmsAttach = ((CraftEntity) attach).getHandle();
                boolean ok = nmsBot.startRiding(nmsAttach, true, false);
                if (!ok && !bot.isInsideVehicle()) why = "startRiding refused";
            }
            if (why != null) {
                FppLogger.warn("BotMountMemory: could not seat " + name + " in its vehicle: " + why);
                if (created) discardTree(vehicleRoot);
                return false;
            }
            FppLogger.info("BotMountMemory: " + name + " is back in " + attach.getType());
            BotAutoMount.resync(vehicleRoot);
            return true;
        } catch (Throwable t) {
            FppLogger.warn("BotMountMemory: seating " + name + " failed: " + t);
            if (created) discardTree(vehicleRoot);
            return false;
        }
    }

    private static int capacity(Entity vehicle) {
        if (vehicle instanceof org.bukkit.entity.Boat) return vehicle instanceof ChestBoat ? 1 : 2;
        return 1;
    }

    private static Entity findByUuid(Entity root, UUID uuid) {
        if (root.getUniqueId().equals(uuid)) return root;
        List<Entity> passengers = root.getPassengers();
        for (Entity p : passengers) {
            Entity hit = findByUuid(p, uuid);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void discardTree(Entity root) {
        try {
            for (Entity p : root.getPassengers()) {
                if (!(p instanceof Player)) p.remove();
            }
            root.remove();
        } catch (Throwable ignored) {
        }
    }

    // ── persistence (survives restarts, like the .dat file does for players) ─────────────

    private static File file() {
        FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
        File dir = new File(plugin.getDataFolder(), "data");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "bot-mounts.yml");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (!f.isFile()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection root = y.getConfigurationSection("mounts");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                entries.put(
                        UUID.fromString(key),
                        new Entry(
                                UUID.fromString(s.getString("world")),
                                s.getString("type", "OAK_BOAT"),
                                s.getString("data"),
                                s.getBoolean("shared", false),
                                UUID.fromString(s.getString("root")),
                                UUID.fromString(s.getString("attach")),
                                s.getDouble("x"),
                                s.getDouble("y"),
                                s.getDouble("z"),
                                (float) s.getDouble("yaw"),
                                (float) s.getDouble("pitch")));
            }
        } catch (Throwable t) {
            FppLogger.warn("BotMountMemory.load failed: " + t);
        }
    }

    private static synchronized void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<UUID, Entry> en : entries.entrySet()) {
                String p = "mounts." + en.getKey();
                Entry e = en.getValue();
                y.set(p + ".world", e.worldUid.toString());
                y.set(p + ".type", e.type);
                y.set(p + ".data", e.data);
                y.set(p + ".shared", e.shared);
                y.set(p + ".root", e.rootUuid.toString());
                y.set(p + ".attach", e.attachUuid.toString());
                y.set(p + ".x", e.x);
                y.set(p + ".y", e.y);
                y.set(p + ".z", e.z);
                y.set(p + ".yaw", e.yaw);
                y.set(p + ".pitch", e.pitch);
            }
            y.save(file());
        } catch (IOException | RuntimeException t) {
            FppLogger.warn("BotMountMemory.save failed: " + t);
        }
    }
}
