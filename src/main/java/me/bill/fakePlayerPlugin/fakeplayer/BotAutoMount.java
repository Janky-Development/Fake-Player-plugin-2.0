package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

/**
 * Boards a bot onto a boat or minecart that runs into it - the same way vanilla mobs (villagers,
 * zombies, ...) get "picked up" by vehicles.
 *
 * <p>Vanilla does this from the vehicle's side, and explicitly skips players:
 * <ul>
 *   <li>{@code AbstractBoat#tickBoat}: every entity in the boat's box inflated by (0.2, -0.01, 0.2)
 *       that is a {@code LivingEntity}, not already a passenger, not tagged
 *       {@code cannot_be_pushed_onto_boats}, and fits ({@code hasEnoughSpaceFor}) boards via
 *       {@code startRiding}, as long as there is room and no real player is steering it.</li>
 *   <li>{@code OldMinecartBehavior#pushAndPickupEntities}: a rideable minecart moving faster than
 *       {@code horizontalDistanceSqr > 0.01} boards any non-player, non-iron-golem, non-minecart entity
 *       in its box inflated by (0.2, 0, 0.2) while it has no passenger.</li>
 * </ul>
 * Bots are {@code ServerPlayer}s, so vanilla never picks them up. This class re-applies the same
 * rules from the bot's side, on a throttled cadence, and uses the same {@code startRiding} call.
 */
public final class BotAutoMount implements Listener {

    /** How often (ms) each bot is checked; 4 Hz is plenty for a vehicle drifting into a bot. */
    private static final long CHECK_INTERVAL_MS = 250L;

    /** After a bot leaves a vehicle it will not be auto-boarded again for this long. */
    private static final long REBOARD_COOLDOWN_MS = 3000L;

    private static final double MINECART_MIN_SPEED_SQ = 0.01;

    private static final Map<UUID, Long> nextCheck = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    /** Bots whose dismount is deliberate (seat reshuffle) and must not be blocked by the keep-seated rule. */
    private static final Set<UUID> allowExit = ConcurrentHashMap.newKeySet();

    private final FakePlayerManager manager;

    public BotAutoMount(FakePlayerManager manager) {
        this.manager = manager;
    }

    /** Viewer range for the visual resync, matching the position-sync default. */
    private static final double RESYNC_RANGE_SQ = 128.0 * 128.0;

    /**
     * Re-sends the vehicle's passenger list (and each seated bot's position) to nearby real players.
     * Bots are driven by FPP's own packets rather than a real client connection, so after a seat
     * change - auto-board, or a reshuffle when a player takes the first seat - viewers can be left
     * with the bot's body standing where it was dismounted while the server (and the name tag, which
     * follows the server position) already has it in the boat.
     */
    public static void resync(Entity vehicle) {
        try {
            if (vehicle == null || !vehicle.isValid()) return;
            var nmsVehicle = ((CraftEntity) vehicle).getHandle();
            var packet = new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(nmsVehicle);
            Location at = vehicle.getLocation();
            for (Player viewer : vehicle.getWorld().getPlayers()) {
                if (viewer.getLocation().distanceSquared(at) > RESYNC_RANGE_SQ) continue;
                try {
                    var conn = ((CraftPlayer) viewer).getHandle().connection;
                    if (conn != null) conn.send(packet);
                } catch (Throwable ignored) {
                }
            }
            for (Entity rider : vehicle.getPassengers()) {
                if (!(rider instanceof Player bot)) continue;
                for (Player viewer : vehicle.getWorld().getPlayers()) {
                    if (viewer.equals(bot)) continue;
                    if (viewer.getLocation().distanceSquared(at) > RESYNC_RANGE_SQ) continue;
                    PacketHelper.sendPositionSync(viewer, bot, bot.getLocation());
                }
            }
        } catch (Throwable t) {
            FppLogger.debug("BotAutoMount.resync failed: " + t);
        }
    }

    /** Immediately, then again shortly after, so it lands after the server's own tracker update. */
    private static void resyncSoon(Entity vehicle) {
        resync(vehicle);
        FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
        if (plugin != null && vehicle != null) {
            FppScheduler.runAtEntityLaterWithId(plugin, vehicle, () -> resync(vehicle), 2L);
        }
    }

    private boolean isBot(Entity e) {
        return e instanceof Player p && manager.getByUuid(p.getUniqueId()) != null;
    }

    /**
     * Keeps a bot seated. A bot has no client, so nothing legitimately "decides" to leave a boat or
     * cart: any dismount that is not deliberate - a stray nudge, another plugin, a glitchy
     * velocity/teleport correction while the vehicle moves - would just be the bot hopping off at
     * random. Allowed exits: an explicit sneak ({@code /fpp sneak}), the vehicle being gone, the bot
     * dying or being underwater (vanilla's own submerged-boat eject), a deliberate seat reshuffle,
     * and anything Paper marks as not cancellable (teleport, removal, world change).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!Config.autoMountEnabled()) return;
        if (!(event.getEntity() instanceof Player bot) || !isBot(bot)) return;
        if (!event.isCancellable()) return;
        Entity vehicle = event.getDismounted();
        if (!isCandidateVehicle(vehicle)) return;
        if (allowExit.contains(bot.getUniqueId())) return;
        if (!vehicle.isValid() || vehicle.isDead() || bot.isDead()) return;
        if (bot.isSneaking()
                || bot.isUnderWater()
                || vehicle.isInWater()
                        && vehicle.getLocation().getBlock().isLiquid()
                        && bot.getEyeLocation().getBlock().isLiquid()) return;
        event.setCancelled(true);
        FppLogger.debug("BotAutoMount: kept " + bot.getName() + " seated on " + vehicle.getType());
    }

    /**
     * A real player entering a boat always takes the first (controlling) seat, like a villager
     * yielding to the player. A bot occupying it is lifted out just before and re-seated behind.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof org.bukkit.entity.Boat boat)) return;
        if (!(event.getEntered() instanceof Player human) || isBot(human)) return;

        List<Player> bots = new java.util.ArrayList<>();
        for (Entity r : boat.getPassengers()) if (isBot(r)) bots.add((Player) r);
        if (bots.isEmpty()) return;

        for (Player b : bots) {
            allowExit.add(b.getUniqueId());
            boat.removePassenger(b);
        }
        FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
        Runnable reseat = () -> {
            int max = boat instanceof ChestBoat ? 1 : 2;
            for (Player b : bots) {
                try {
                    if (boat.isValid()
                            && b.isValid()
                            && !b.isInsideVehicle()
                            && boat.getPassengers().size() < max) {
                        boat.addPassenger(b);
                    }
                } finally {
                    allowExit.remove(b.getUniqueId());
                    cooldownUntil.remove(b.getUniqueId());
                }
            }
            resyncSoon(boat);
        };
        // The player is added right after this event returns, so re-seat on the next tick.
        if (plugin != null) FppScheduler.runAtEntityLaterWithId(plugin, boat, reseat, 1L);
        else reseat.run();
    }

    /**
     * A real player right-clicking a boat that is full of bots takes a seat: the last bot gives it
     * up (it stays out for a few seconds rather than hopping straight back in).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractFullBoat(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Boat boat)) return;
        Player human = event.getPlayer();
        if (isBot(human) || human.isSneaking() || human.isInsideVehicle()) return;
        List<Entity> riders = boat.getPassengers();
        int max = boat instanceof ChestBoat ? 1 : 2;
        if (riders.size() < max) return;
        for (Entity r : riders) if (!isBot(r)) return; // a real player is aboard: vanilla rules apply

        Player last = (Player) riders.get(riders.size() - 1);
        allowExit.add(last.getUniqueId());
        try {
            boat.removePassenger(last);
        } finally {
            allowExit.remove(last.getUniqueId());
        }
        cooldownUntil.put(last.getUniqueId(), System.currentTimeMillis() + 5000L);
        resyncSoon(boat);
    }

    /** A bot that dismounts (command, sneak, API, vehicle broke) is not instantly re-boarded. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player p && manager.getByUuid(p.getUniqueId()) != null) {
            cooldownUntil.put(p.getUniqueId(), System.currentTimeMillis() + REBOARD_COOLDOWN_MS);
        }
    }

    /**
     * Vanilla checks from the vehicle every tick it moves. A cart at speed overlaps a bot for only a
     * couple of ticks - shorter than the bot-side poll interval - so the same per-movement check is
     * done here. The poll in {@link #tick} still covers vehicles that are stationary or the bot
     * walking into them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Entity vehicle = event.getVehicle();
        if (manager.getCount() == 0) return;
        followWithNametags(event, vehicle);
        if (!Config.autoMountEnabled()) return;
        if (!isCandidateVehicle(vehicle)) return;
        try {
            long now = System.currentTimeMillis();
            for (Entity e : vehicle.getNearbyEntities(2.0, 2.0, 2.0)) {
                if (!(e instanceof Player bot)) continue;
                FakePlayer fp = manager.getByUuid(bot.getUniqueId());
                if (fp == null || fp.isFrozen() || bot.isDead() || bot.isSleeping() || bot.isInsideVehicle()) continue;
                Long cd = cooldownUntil.get(bot.getUniqueId());
                if (cd != null && now < cd) continue;
                if (!canBoard(manager, vehicle, bot.getBoundingBox())) continue;
                if (board(bot, vehicle)) return;
            }
        } catch (Throwable t) {
            FppLogger.debug("BotAutoMount.onVehicleMove failed: " + t);
        }
    }

    /**
     * The per-bot tick that normally moves the name tag runs at the start of the server tick, before
     * vehicles move, so a seated bot's tag is always one tick (a whole 0.4 blocks on a fast cart)
     * behind. The vehicle's move event fires inside the same tick as the movement, so the tag is
     * moved there instead. Passengers are positioned only after the vehicle's move, so the bot's
     * location is still the old one here and the vehicle's own displacement is applied to it.
     */
    private void followWithNametags(VehicleMoveEvent event, Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        if (riders.isEmpty()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        for (Entity r : riders) {
            if (!(r instanceof Player bot)) continue;
            FakePlayer fp = manager.getByUuid(bot.getUniqueId());
            if (fp == null || fp.getNametagEntity() == null) continue;
            try {
                Location now = bot.getLocation();
                Location target = now;
                if (now.getWorld() == from.getWorld() && now.distanceSquared(from) <= now.distanceSquared(to)) {
                    target = now.clone().add(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
                }
                FakePlayerBody.moveNametag(fp, target);
            } catch (Throwable t) {
                FppLogger.debug("BotAutoMount.followWithNametags failed: " + t);
            }
        }
    }

    /**
     * Marks a bot as being removed. {@code PlayerList#remove} dismounts the player itself (and, as in
     * vanilla, deletes the vehicle when the bot was its only player - see {@link BotMountMemory}); that
     * dismount is deliberate and must not be blocked by the keep-seated rule.
     */
    public static void beginRemoval(UUID botUuid) {
        allowExit.add(botUuid);
    }

    public static void endRemoval(UUID botUuid) {
        allowExit.remove(botUuid);
        forget(botUuid);
    }

    public static void forget(UUID botUuid) {
        nextCheck.remove(botUuid);
        cooldownUntil.remove(botUuid);
    }

    /**
     * Called from the bot's own tick (on its region thread on Folia). Cheap when it has nothing to
     * do: a timestamp compare, then at most one nearby-entity query per {@link #CHECK_INTERVAL_MS}.
     */
    public static void tick(FakePlayerManager manager, FakePlayer fp, Player bot) {
        if (!Config.autoMountEnabled()) return;
        if (fp.isFrozen() || bot.isDead() || bot.isSleeping() || bot.isInsideVehicle()) return;

        long now = System.currentTimeMillis();
        UUID id = bot.getUniqueId();
        Long next = nextCheck.get(id);
        if (next != null && now < next) return;
        // Staggered by uuid so a hundred bots don't all scan on the same tick.
        nextCheck.put(id, now + CHECK_INTERVAL_MS + Math.floorMod(id.hashCode(), 40));

        Long cd = cooldownUntil.get(id);
        if (cd != null) {
            if (now < cd) return;
            cooldownUntil.remove(id);
        }

        try {
            BoundingBox botBox = bot.getBoundingBox();
            List<Entity> near = bot.getNearbyEntities(2.5, 2.5, 2.5);
            for (Entity e : near) {
                if (!isCandidateVehicle(e)) continue;
                if (!canBoard(manager, e, botBox)) continue;
                if (board(bot, e)) return;
            }
        } catch (Throwable t) {
            FppLogger.debug("BotAutoMount.tick failed for " + fp.getName() + ": " + t);
        }
    }

    private static boolean isCandidateVehicle(Entity e) {
        return e instanceof org.bukkit.entity.Boat || e instanceof org.bukkit.entity.Minecart;
    }

    private static boolean canBoard(FakePlayerManager manager, Entity vehicle, BoundingBox botBox) {
        if (!vehicle.isValid()) return false;
        List<Entity> riders = vehicle.getPassengers();

        if (vehicle instanceof org.bukkit.entity.Boat) {
            int max = vehicle instanceof ChestBoat ? 1 : 2;
            if (riders.size() >= max) return false;
            // Vanilla: boats only pick things up while no player is steering. Bots don't count as
            // steering players here, so several bots can share one boat.
            for (Entity r : riders) {
                if (r instanceof Player p && manager.getByUuid(p.getUniqueId()) == null) return false;
            }
            return botBox.overlaps(vehicle.getBoundingBox().expand(0.2, -0.01, 0.2));
        }

        // Rideable minecart only (chest/furnace/hopper/TNT/... are not "rideable"), empty, moving.
        if (!(vehicle instanceof org.bukkit.entity.minecart.RideableMinecart)) return false;
        if (!riders.isEmpty()) return false;
        var v = vehicle.getVelocity();
        if (v.getX() * v.getX() + v.getZ() * v.getZ() < MINECART_MIN_SPEED_SQ) return false;
        return botBox.overlaps(vehicle.getBoundingBox().expand(0.2, 0.0, 0.2));
    }

    /** Same call vanilla's vehicles make: {@code Entity#startRiding} (Paper fires VehicleEnterEvent). */
    private static boolean board(Player bot, Entity vehicle) {
        try {
            var nmsBot = ((CraftPlayer) bot).getHandle();
            var nmsVehicle = ((CraftEntity) vehicle).getHandle();
            if (nmsBot.startRiding(nmsVehicle)) {
                FppLogger.debug("BotAutoMount: " + bot.getName() + " boarded " + vehicle.getType());
                resyncSoon(vehicle);
                return true;
            }
            // Vanilla's own gates refused (sneaking, boarding cooldown, ...): try the API route,
            // which forces the mount, since the geometry/capacity rules were already checked above.
            boolean ok = vehicle.addPassenger(bot);
            if (ok) resyncSoon(vehicle);
            FppLogger.debug("BotAutoMount: startRiding refused for " + bot.getName() + " on " + vehicle.getType()
                    + ", addPassenger=" + ok);
            return ok;
        } catch (Throwable t) {
            FppLogger.debug("BotAutoMount.board failed: " + t);
            return false;
        }
    }
}
