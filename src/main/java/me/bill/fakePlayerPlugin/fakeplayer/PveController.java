package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotAttackEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The bot's fighting brain. Combat itself is the vanilla client's: the bot looks at its target, the
 * crosshair pick ({@link BotClient#pick}) decides what is really in reach and in sight, and the attack
 * goes out through {@link BotClient#startAttack} - the same attack-charge gating, miss delay, swing and
 * {@code ServerboundAttackPacket} a player's mouse click produces. Nothing here damages an entity
 * directly, so reach, cooldown, weapon enchantments, shields, armor and every Bukkit event are the
 * server's own.
 *
 * <p>What this class adds is the <em>decision</em> layer a human would supply: which mob to fight
 * (hostile by default, an explicit type list, or anything), nearest or weakest first, whether to
 * stand and hold a farm position or chase through the shared pathfinder and return to post afterwards,
 * which weapon to hold, and an optional falling-strike critical.
 */
public final class PveController {

    private static final long SUPERVISOR_PERIOD_TICKS = 20L;

    /** Re-path when the chased target strayed this far from where we planned to go. */
    private static final double CHASE_RECALC_DISTANCE = 2.5;

    /** How long a target the pathfinder could not reach is ignored. */
    private static final int UNREACHABLE_IGNORE_TICKS = 100;

    private static final int WEAPON_CHECK_TICKS = 20;

    /** Sentinel entry in a bot's mob-type list meaning "any living, non-player, non-bot mob". */
    public static final String ANY_MOB = "ALL";

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final PathfindingService pathfinding;

    private final Map<UUID, Integer> combatTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Fight> fights = new ConcurrentHashMap<>();
    /** Bots that should disable themselves after their first landed attack ({@code /fpp attack --once}). */
    private final Set<UUID> once = ConcurrentHashMap.newKeySet();
    private int supervisorTaskId = -1;

    /** Per-bot fight state. */
    private static final class Fight {
        UUID target;
        Location post;
        int scanIn;
        int idleTicks;
        int weaponIn;
        long tick;
        final Map<UUID, Long> ignoredUntil = new ConcurrentHashMap<>();
        boolean engaged;
    }

    public PveController(FakePlayerPlugin plugin, FakePlayerManager manager, PathfindingService pathfinding) {
        this.plugin = plugin;
        this.manager = manager;
        this.pathfinding = pathfinding;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Starts the supervisor that attaches/detaches combat tasks as bots toggle PVE. */
    public void start() {
        if (supervisorTaskId >= 0) return;
        supervisorTaskId = FppScheduler.runSyncRepeatingWithId(
                plugin, this::superviseAll, SUPERVISOR_PERIOD_TICKS, SUPERVISOR_PERIOD_TICKS);
    }

    public void shutdown() {
        if (supervisorTaskId >= 0) {
            FppScheduler.cancelTask(supervisorTaskId);
            supervisorTaskId = -1;
        }
        combatTasks.keySet().forEach(this::stopCombat);
    }

    /** Immediate reaction to a toggle/option change instead of waiting for the next supervisor sweep. */
    public void refresh(@NotNull FakePlayer fp) {
        if (fp.isPveEnabled()) {
            Fight f = fights.get(fp.getUuid());
            if (f != null) f.scanIn = 0; // rescan with the new options right away
            startCombat(fp);
        } else {
            stopCombat(fp.getUuid());
        }
    }

    /** Despawn cleanup - called from the shared bot-removal path. */
    public void cleanupBot(@NotNull UUID botUuid) {
        stopCombat(botUuid);
        once.remove(botUuid);
    }

    /** Arms {@code --once}: the bot turns PVE off right after its next landed attack. */
    public void attackOnce(@NotNull FakePlayer fp) {
        once.add(fp.getUuid());
        refresh(fp);
    }

    /** True while this bot has an acquired PVE target (used by the nametag activity line). */
    public boolean isEngaged(@NotNull UUID botUuid) {
        Fight f = fights.get(botUuid);
        return f != null && f.engaged;
    }

    public boolean isActive(@NotNull UUID botUuid) {
        return combatTasks.containsKey(botUuid);
    }

    /** The living target currently being fought, if any. */
    public @Nullable LivingEntity currentTarget(@NotNull UUID botUuid) {
        Fight f = fights.get(botUuid);
        if (f == null || f.target == null) return null;
        return Bukkit.getEntity(f.target) instanceof LivingEntity le && le.isValid() ? le : null;
    }

    private void superviseAll() {
        for (FakePlayer fp : manager.getActivePlayers()) {
            if (fp.isPveEnabled() && !combatTasks.containsKey(fp.getUuid())) {
                startCombat(fp);
            }
        }
    }

    private void startCombat(FakePlayer fp) {
        UUID uuid = fp.getUuid();
        if (combatTasks.containsKey(uuid)) return;
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return;

        Fight fight = new Fight();
        fight.post = bot.getLocation().clone();
        fights.put(uuid, fight);

        Config.debugPathfinding("event=PVE_START bot='" + fp.getName() + "' mode=" + fp.getPveSmartAttackMode()
                + " range=" + fp.getPveRange());
        int taskId = FppScheduler.runAtEntityRepeatingWithId(plugin, bot, () -> tick(fp), 0L, 1L);
        combatTasks.put(uuid, taskId);
    }

    private void stopCombat(UUID uuid) {
        Integer taskId = combatTasks.remove(uuid);
        if (taskId != null) FppScheduler.cancelTask(taskId);
        fights.remove(uuid);
        if (pathfinding.isNavigating(uuid, PathfindingService.Owner.ATTACK)) {
            pathfinding.cancel(uuid);
        }
    }

    // ── Per-tick brain ──────────────────────────────────────────────────────

    private void tick(FakePlayer fp) {
        UUID uuid = fp.getUuid();
        Player bot = fp.getPlayer();
        Fight f = fights.get(uuid);
        if (bot == null || !bot.isOnline() || !fp.isPveEnabled() || f == null) {
            stopCombat(uuid);
            return;
        }
        if (fp.isFrozen() || fp.isAuthPending() || fp.isInventoryOpen() || fp.isActionsPaused()) return;
        if (!bot.isValid() || bot.isDead()) return;

        f.tick++;
        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        BotClient client = BotClient.of(nms);
        client.endTick();

        // ── target acquisition ──
        LivingEntity target = validTarget(fp, bot, f);
        if (target == null && --f.scanIn <= 0) {
            f.scanIn = Config.attackScanIntervalTicks();
            target = scan(fp, bot, f);
            f.target = target != null ? target.getUniqueId() : null;
        }

        if (target == null) {
            f.engaged = false;
            idle(fp, bot, f);
            return;
        }
        f.engaged = true;
        f.idleTicks = 0;

        // ── weapon ──
        if (Config.attackAutoWeapon() && --f.weaponIn <= 0) {
            f.weaponIn = WEAPON_CHECK_TICKS;
            if (!nms.isUsingItem()) BotWeaponSelector.equipBestWeapon(bot);
        }

        // ── look at it, then let the client pick decide what is really there ──
        aimAt(bot, nms, target);
        client.continueAttack(nms, null, false, 5); // attack key is a click, never held
        HitResult hit = BotClient.pick(nms);
        boolean inSight = hit instanceof EntityHitResult ehr && isHostileTarget(fp, ehr.getEntity().getBukkitEntity());

        if (inSight) {
            stopChase(uuid);
            strike(fp, bot, nms, client, f, hit);
            return;
        }

        // Not hittable from here: walk to it in chase mode, otherwise hold the post and wait.
        if (fp.getPveSmartAttackMode().movesWhileAttacking()) {
            chase(fp, bot, target, f);
        }
    }

    /** The current target if it is still worth fighting, else null. */
    private @Nullable LivingEntity validTarget(FakePlayer fp, Player bot, Fight f) {
        if (f.target == null) return null;
        Entity e = Bukkit.getEntity(f.target);
        if (!(e instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            f.target = null;
            return null;
        }
        double range = Math.max(1.0, fp.getPveRange());
        if (living.getWorld() != bot.getWorld()
                // Grace factor so a target stepping just outside range isn't dropped mid-fight.
                || living.getLocation().distanceSquared(bot.getLocation()) > range * range * 2.25) {
            f.target = null;
            return null;
        }
        return living;
    }

    private @Nullable LivingEntity scan(FakePlayer fp, Player bot, Fight f) {
        double range = Math.max(1.0, fp.getPveRange());
        boolean nearest = !"lowest-health".equalsIgnoreCase(fp.getPvePriority());
        double rangeSq = range * range;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof LivingEntity living)) continue;
            Long until = f.ignoredUntil.get(living.getUniqueId());
            if (until != null && until > f.tick) continue;
            if (!isHostileTarget(fp, living)) continue;
            double distSq = living.getLocation().distanceSquared(bot.getLocation());
            if (distSq > rangeSq) continue;
            if (Config.attackRequireLineOfSight()
                    && !fp.getPveSmartAttackMode().movesWhileAttacking()
                    && !bot.hasLineOfSight(living)) {
                continue; // a standing bot only wakes for what it can actually see
            }
            double score = nearest ? distSq : living.getHealth() + distSq * 1e-6;
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    /** Whether this entity is something the bot's configuration says to fight (and is safe to hit). */
    private boolean isHostileTarget(FakePlayer fp, Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) return false;
        if (living instanceof Player) return false; // PVE only - players and bots are never targets
        if (manager.getByUuid(living.getUniqueId()) != null) return false;
        if (living.isInvulnerable()) return false;
        if (living.getType() == org.bukkit.entity.EntityType.ARMOR_STAND) return false;

        Set<String> wanted = fp.getPveMobTypes();
        boolean any = wanted.contains(ANY_MOB);
        boolean explicit = !wanted.isEmpty() && !any;

        if (explicit) return wanted.contains(living.getType().name());

        if (Config.attackSpareFriendlies()) {
            if (living instanceof Tameable tame && tame.isTamed()) return false;
            if (living instanceof org.bukkit.entity.Villager || living instanceof org.bukkit.entity.WanderingTrader) {
                return false;
            }
        }
        return any || living instanceof Enemy;
    }

    // ── Attacking (the client's own click) ──────────────────────────────────

    private void strike(FakePlayer fp, Player bot, ServerPlayer nms, BotClient client, Fight f, HitResult hit) {
        // A client cannot swing while it is eating/drinking/blocking.
        if (nms.isUsingItem()) return;

        boolean charged = nms.getAttackStrengthScale(0.0F) >= 1.0F
                && !nms.cannotAttackWithItem(nms.getMainHandItem(), 0);

        if (Config.attackCriticals()) {
            // Vanilla critical: falling, not on ground, not climbing/swimming, full charge.
            boolean falling = !nms.onGround() && nms.fallDistance > 0.0 && !nms.isInWater() && !nms.onClimbable();
            if (charged && !falling) {
                if (nms.onGround()) {
                    NmsPlayerSpawner.setJumping(bot, true);
                    manager.requestNavJump(fp.getUuid());
                }
                return; // wait for the apex to pass
            }
        } else {
            NmsPlayerSpawner.setJumping(bot, false);
        }
        if (!charged) return;

        Entity targetEntity = ((EntityHitResult) hit).getEntity().getBukkitEntity();
        if (targetEntity instanceof LivingEntity living) {
            FppBotAttackEvent event = new FppBotAttackEvent(new FppBotImpl(fp), living, 1.0);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;
        }

        client.startAttack(nms, hit, 5);
        if (once.remove(fp.getUuid())) {
            fp.setPveSmartAttackMode(FakePlayer.PveSmartAttackMode.OFF);
            // Defer: stopping from inside our own tick would cancel the task mid-run.
            FppScheduler.runSync(plugin, () -> refresh(fp));
        }
    }

    private static void aimAt(Player bot, ServerPlayer nms, LivingEntity target) {
        Vector center = target.getBoundingBox().getCenter();
        Location eye = bot.getEyeLocation();
        double dx = center.getX() - eye.getX();
        double dy = center.getY() - eye.getY();
        double dz = center.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        nms.absSnapRotationTo(yaw, pitch);
        NmsPlayerSpawner.setHeadYaw(bot, yaw);
    }

    // ── Movement ────────────────────────────────────────────────────────────

    private void chase(FakePlayer fp, Player bot, LivingEntity target, Fight f) {
        UUID uuid = fp.getUuid();
        boolean chasing = pathfinding.isNavigating(uuid, PathfindingService.Owner.ATTACK);
        if (pathfinding.isNavigating(uuid) && !chasing) return; // never hijack another task's walk
        if (chasing && target.getUniqueId().equals(f.target) && f.tick % 20 != 0) return;

        final UUID targetUuid = target.getUniqueId();
        final ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        double reach = nms.entityInteractionRange();
        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        PathfindingService.Owner.ATTACK,
                        () -> {
                            Entity live = Bukkit.getEntity(targetUuid);
                            return live != null && live.isValid() && live.getWorld() == bot.getWorld()
                                    ? live.getLocation()
                                    : null;
                        },
                        Math.max(1.2, reach - 0.8),
                        CHASE_RECALC_DISTANCE,
                        10,
                        null,
                        null,
                        // Unreachable (caged, across water, in a wall): leave it alone for a while.
                        () -> {
                            f.ignoredUntil.put(targetUuid, f.tick + UNREACHABLE_IGNORE_TICKS);
                            if (targetUuid.equals(f.target)) f.target = null;
                        },
                        null));
    }

    private void stopChase(UUID uuid) {
        if (pathfinding.isNavigating(uuid, PathfindingService.Owner.ATTACK)) {
            pathfinding.cancel(uuid);
        }
    }

    /** No target: hold position, or (chase mode) walk back to where the fight started. */
    private void idle(FakePlayer fp, Player bot, Fight f) {
        UUID uuid = fp.getUuid();
        f.idleTicks++;
        f.ignoredUntil.values().removeIf(until -> until <= f.tick);

        boolean chasing = pathfinding.isNavigating(uuid, PathfindingService.Owner.ATTACK);
        if (!fp.getPveSmartAttackMode().movesWhileAttacking() || !Config.attackReturnToPost() || f.post == null) {
            if (chasing) pathfinding.cancel(uuid);
            return;
        }
        if (f.idleTicks < Config.attackReturnDelayTicks()) return;
        if (f.post.getWorld() != bot.getWorld()) return;
        if (bot.getLocation().distanceSquared(f.post) < 2.5 * 2.5) {
            if (chasing) pathfinding.cancel(uuid);
            return;
        }
        if (pathfinding.isNavigating(uuid)) return; // already walking (to the post or for another task)

        final Location post = f.post;
        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        PathfindingService.Owner.ATTACK, () -> post, 1.5, 0.0, 5, null, null, null, null));
    }
}
