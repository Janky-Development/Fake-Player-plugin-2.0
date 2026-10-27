package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.BotNavUtil;
import me.bill.fakePlayerPlugin.fakeplayer.BotPathfinder;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavPath.Move;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain.Stance;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The bot navigation engine behind {@link PathfindingService}: one time-sliced {@link NavSearch} (A*
 * over real block collision, costed in ticks) plus a per-tick executor that drives the bot along the
 * result like a player at a keyboard - aiming, sprint/jump timing per move type, climbing, swimming,
 * opening doors, breaking and bridging, and replanning whenever the world, the target or the bot's own
 * position invalidates the plan.
 *
 * <p>Everything runs on the bot's own scheduler thread, so no world data is ever read off-thread (safe
 * on Folia) and a search can never stall a tick: it is given a small slice every tick until it finishes.
 */
public final class NavController implements PathfindingService.Controller {

    /** Wall-clock budget per bot per tick for searching. */
    private static final long SEARCH_BUDGET_NANOS = 1_500_000L;

    /** How often (ticks) the planned route is re-validated against the live world. */
    private static final int VALIDATE_INTERVAL = 5;

    /** How often (ticks) a moving destination is re-sampled. */
    private static final int TARGET_SAMPLE_INTERVAL = 4;

    private static final int MAX_PARTIAL_HOPS = 24;

    /** Ticks one obstacle (a dig or a placement) may take before the walk gives up on it. */
    private static final int WORK_TIMEOUT_TICKS = 20 * 20;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final Map<UUID, NavState> states = new ConcurrentHashMap<>();

    public NavController(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ── Controller: bookkeeping ─────────────────────────────────────────────

    @Override
    public boolean isNavigating(@NotNull UUID botUuid) {
        return states.containsKey(botUuid);
    }

    @Override
    public boolean isNavigating(@NotNull UUID botUuid, @NotNull PathfindingService.Owner owner) {
        NavState state = states.get(botUuid);
        return state != null && state.owner == owner;
    }

    @Override
    public PathfindingService.Owner getOwner(@NotNull UUID botUuid) {
        NavState state = states.get(botUuid);
        return state != null ? state.owner : null;
    }

    @Override
    public void cancel(@NotNull UUID botUuid) {
        NavState state = states.remove(botUuid);
        if (state == null) return;
        stop(botUuid, state);
        if (state.request.onCancel() != null) state.request.onCancel().run();
    }

    @Override
    public void cancelAll() {
        for (UUID uuid : new ArrayList<>(states.keySet())) cancel(uuid);
    }

    @Override
    public void cancelAll(@NotNull PathfindingService.Owner owner) {
        for (Map.Entry<UUID, NavState> entry : new ArrayList<>(states.entrySet())) {
            if (entry.getValue().owner == owner) cancel(entry.getKey());
        }
    }

    /**
     * A bot has one body, so only one navigation drives it at a time. The slot is arbitrated by
     * priority rather than "last caller wins": an explicit {@code /fpp move} always relocates the bot, a
     * manual hand-action's walk-to-reach outranks background movement (PVE chase, auto-deposit), and
     * background movement never interrupts anything. Same-owner requests replace (retargeting).
     */
    private static int priority(PathfindingService.Owner owner) {
        return switch (owner) {
            case MOVE -> 3;
            case MINE, USE, FIND -> 2;
            case ATTACK, SYSTEM, PLACE -> 1;
        };
    }

    // ── Controller: starting a navigation ───────────────────────────────────

    @Override
    public void navigate(@NotNull FakePlayer fp, @NotNull PathfindingService.NavigationRequest request) {
        UUID uuid = fp.getUuid();
        NavState existing = states.get(uuid);
        if (existing != null
                && existing.owner != request.owner()
                && priority(existing.owner) >= priority(request.owner())) {
            failImmediately(request);
            return;
        }

        NavState previous = states.remove(uuid);
        if (previous != null) stop(uuid, previous); // superseded, not an external cancel

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            failImmediately(request);
            return;
        }
        Location target = request.destinationSupplier().get();
        if (target == null || target.getWorld() != bot.getWorld()) {
            failImmediately(request);
            return;
        }

        NavState state = new NavState(request);
        state.lastTarget = target.clone();
        states.put(uuid, state);
        manager.lockForNavigation(uuid);
        state.taskId = FppScheduler.runSyncRepeatingWithId(plugin, bot, () -> tick(fp, state), 0L, 1L);
    }

    private void failImmediately(PathfindingService.NavigationRequest request) {
        if (request.onPathFailure() != null) request.onPathFailure().run();
        else if (request.onCancel() != null) request.onCancel().run();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Per-tick driver
    // ═════════════════════════════════════════════════════════════════════════

    private void tick(FakePlayer fp, NavState state) {
        UUID uuid = fp.getUuid();
        if (states.get(uuid) != state) return;

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return;
        if (fp.isFrozen() || fp.isAuthPending() || fp.isActionsPaused()) return;

        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        Location current = bot.getLocation();
        Location target = state.lastTarget;
        if (target == null || target.getWorld() != current.getWorld()) {
            fail(fp, state, "NO_TARGET");
            return;
        }
        state.tickCount++;

        if (arrived(current, target, state.request.arrivalDistance())) {
            arrive(fp, state);
            return;
        }

        // Moving destination: re-sample and re-plan when it drifted far enough.
        if (state.request.recalcDistance() > 0 && state.tickCount % TARGET_SAMPLE_INTERVAL == 0) {
            Location fresh = state.request.destinationSupplier().get();
            if (fresh == null || fresh.getWorld() != current.getWorld()) {
                fail(fp, state, "TARGET_LOST");
                return;
            }
            if (fresh.distance(target) > state.request.recalcDistance()) {
                state.lastTarget = fresh.clone();
                target = state.lastTarget;
                beginSearch(fp, state, nms, current, "TARGET_MOVED");
            }
        }

        // Plan on first tick.
        if (state.path == null && state.search == null) {
            beginSearch(fp, state, nms, current, "INITIAL");
        }

        // Advance the search by one slice.
        if (state.search != null) {
            NavSearch.Status s = state.search.step(System.nanoTime() + SEARCH_BUDGET_NANOS);
            if (s != NavSearch.Status.RUNNING) {
                NavSearch done = state.search;
                state.search = null;
                if (!finishSearch(fp, state, current, done, s)) return;
            }
        }

        if (state.path == null) {
            // Still planning - stand still rather than wander.
            stopInput(bot);
            return;
        }

        followPath(fp, state, bot, nms, current);
    }

    private void beginSearch(FakePlayer fp, NavState state, ServerPlayer nms, Location from, String reason) {
        if (state.terrain == null || state.terrain.level() != nms.level()) {
            state.terrain = new NavTerrain(nms.level());
        } else {
            state.terrain.refresh();
        }
        BotPathfinder.PathOptions opts = PathfindingService.resolvePathOptions(fp, state.request.overrideOpts());
        // Placing needs blocks in the inventory; digging is priced by the bot's real tool.
        if (opts.placeBlocks() && !NavBlocks.hasPlaceable(fp.getPlayer())) {
            opts = opts.withPlaceBlocks(false);
        }
        state.terrain.setBreakFilter(opts.breakAllowed());
        if (opts.breakBlocks()) state.terrain.setDigger(nms);
        NavGoal goal = resolveGoal(state.terrain, state.lastTarget, state.request.arrivalDistance());

        int sx = from.getBlockX();
        int sy = (int) Math.floor(from.getY() + 0.05);
        int sz = from.getBlockZ();

        double horiz = Math.sqrt(Math.pow(goal.x() - sx, 2) + Math.pow(goal.z() - sz, 2));
        int range = (int) Math.min(256, Math.max(Config.pathfindingMaxRange(), horiz + 24));
        int nodeLimit = Math.max(4000, Config.pathfindingMaxNodesExtended() * 4);

        state.search =
                new NavSearch(state.terrain, opts, goal, sx, sy, sz, Config.pathfindingMaxFall(), nodeLimit, range);
        state.searchStartDist = horiz;
        Config.debugPathfinding("event=SEARCH bot='" + fp.getName() + "' reason=" + reason + " from=" + sx + "," + sy
                + "," + sz + " goal=" + goal.x() + "," + goal.y() + "," + goal.z() + " r=" + goal.radius());
    }

    /** Snaps the requested location to a node the search can actually aim for. */
    private static NavGoal resolveGoal(NavTerrain terrain, Location target, double arrival) {
        int tx = target.getBlockX();
        int tz = target.getBlockZ();
        int ty = (int) Math.floor(target.getY() + 0.001);

        // Inside solid ground (e.g. a chest centre): lift out of it.
        for (int up = 0; up < 3 && terrain.cell(tx, ty, tz).solid; up++) ty++;

        // Hovering (a jumping player, a flying mob): settle onto the nearest footing below.
        for (int down = 0; down <= 8; down++) {
            Stance s = terrain.stance(tx, ty - down, tz, false);
            if (s == Stance.GROUND || s == Stance.WATER || s == Stance.CLIMB) {
                ty -= down;
                break;
            }
            if (terrain.cell(tx, ty - down - 1, tz).solid) break;
        }
        double radius = Math.max(0.0, arrival - 0.75);
        return new NavGoal(tx, ty, tz, radius);
    }

    /** @return false when the navigation was terminated. */
    private boolean finishSearch(
            FakePlayer fp, NavState state, Location current, NavSearch search, NavSearch.Status s) {
        if (s == NavSearch.Status.FAILED) {
            state.failedSearches++;
            Config.debugPathfinding(diagnostic(fp, state, "SEARCH_FAILED expanded=" + search.expanded()));
            // Mid-walk: keep following what we have for a few failures before giving up.
            if (state.path != null && state.failedSearches <= maxNullRecalcs(state)) return true;
            fail(fp, state, "NO_PATH");
            return false;
        }

        NavPath path = search.path();
        if (path == null || path.size() < 1) {
            fail(fp, state, "EMPTY_PATH");
            return false;
        }

        if (path.isPartial()) {
            // Progress guard: partial hops that make no headway toward the goal mean it is unreachable.
            double newDist = Math.sqrt(Math.pow(path.last().x() - state.lastTarget.getBlockX(), 2)
                    + Math.pow(path.last().z() - state.lastTarget.getBlockZ(), 2));
            if (state.lastPartialDist >= 0 && newDist >= state.lastPartialDist - 1.0) {
                state.noProgressHops++;
            } else {
                state.noProgressHops = 0;
            }
            state.lastPartialDist = newDist;
            state.partialHops++;
            if (state.noProgressHops >= 3 || state.partialHops > MAX_PARTIAL_HOPS) {
                fail(fp, state, "NO_PROGRESS");
                return false;
            }
        } else {
            state.lastPartialDist = -1;
            state.noProgressHops = 0;
        }

        state.failedSearches = 0;
        state.path = path;
        state.index = path.size() > 1 ? 1 : 0;
        state.stuckTicks = 0;
        state.lastPos = current.clone();
        Config.debugPathfinding("event=PATH bot='" + fp.getName() + "' nodes=" + path.size() + " partial="
                + path.isPartial() + " expanded=" + search.expanded());
        return true;
    }

    private int maxNullRecalcs(NavState state) {
        int max = state.request.maxNullPathRecalculations();
        return max <= 0 ? Integer.MAX_VALUE : max;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Path following
    // ═════════════════════════════════════════════════════════════════════════

    private void followPath(FakePlayer fp, NavState state, Player bot, ServerPlayer nms, Location cur) {
        NavPath path = state.path;
        int size = path.size();
        double px = cur.getX(), py = cur.getY(), pz = cur.getZ();

        // Periodic validation against the live world: a door closed, a block was placed in the way…
        if (state.search == null && state.tickCount % VALIDATE_INTERVAL == 0 && !validAhead(state)) {
            beginSearch(fp, state, nms, cur, "PATH_INVALID");
        }

        // Advance past nodes already reached (also skips ahead when the bot cut a corner).
        int idx = state.index;
        while (idx < size - 1 && reached(path.get(idx), px, py, pz)) idx++;
        while (idx < size - 1 && reached(path.get(idx + 1), px, py, pz)) idx++;
        state.index = idx;

        NavPath.Node t = path.get(idx);

        // End of route.
        if (idx == size - 1 && reached(t, px, py, pz)) {
            if (path.isPartial()) {
                if (state.search == null) beginSearch(fp, state, nms, cur, "PARTIAL_END");
                stopInput(bot);
                return;
            }
            arrive(fp, state);
            return;
        }

        // Fell off the route / got shoved far away: re-plan from where we are.
        double dev = Math.min(
                horizontal(px, pz, t.cx(), t.cz()),
                idx > 0
                        ? horizontal(
                                px,
                                pz,
                                path.get(idx - 1).cx(),
                                path.get(idx - 1).cz())
                        : Double.MAX_VALUE);
        if (state.search == null && (dev > 3.5 || py < t.y() - 3.5)) {
            beginSearch(fp, state, nms, cur, "OFF_PATH");
        }

        renderDebug(fp.getUuid(), state, cur);

        // ── Obstacle work: doors, breaking, bridging ──
        if (t.needsWork() && horizontal(px, pz, t.cx(), t.cz()) <= 2.4) {
            if (doWork(fp, state, bot, nms, t, cur)) {
                stopInput(bot);
                return;
            }
        } else {
            state.workTicks = 0;
            if (state.digging) {
                BotClient.of(nms).stopDestroyBlock(nms);
                state.digging = false;
            }
        }

        drive(fp, state, bot, nms, cur, path, idx, t);
        trackStuck(fp, state, bot, nms, cur);
    }

    private static boolean reached(NavPath.Node n, double px, double py, double pz) {
        double hd = horizontal(px, pz, n.cx(), n.cz());
        double dy = Math.abs(py - n.y());
        double wp = Config.pathfindingWaypointArrivalDistance();
        return switch (n.move()) {
            case CLIMB_UP, CLIMB_DOWN -> hd <= 0.6 && dy < 0.75;
            case SWIM -> hd <= Math.max(0.7, wp) && dy < 0.95;
            case FALL, DESCEND -> hd <= Math.max(0.55, wp * 0.85) && dy < 1.2;
            case PARKOUR -> hd <= 0.45 && dy < 0.9;
            case PILLAR -> hd <= 0.45 && py >= n.y() - 0.1 && dy < 0.6;
            case ASCEND -> hd <= Math.max(0.5, wp * 0.8) && py >= n.y() - 0.35 && dy < 1.1;
            default -> hd <= Math.max(0.5, wp * 0.8) && dy < 1.1;
        };
    }

    /** Drives the movement keys for this tick - the executor's "hands on the keyboard". */
    private void drive(
            FakePlayer fp,
            NavState state,
            Player bot,
            ServerPlayer nms,
            Location cur,
            NavPath path,
            int idx,
            NavPath.Node t) {
        double px = cur.getX(), py = cur.getY(), pz = cur.getZ();
        double dx = t.cx() - px;
        double dz = t.cz() - pz;
        double hd = Math.sqrt(dx * dx + dz * dz);
        boolean onGround = nms.onGround();
        boolean inWater = nms.isInWater();
        Move move = t.move();
        NavPath.Node prev = idx > 0 ? path.get(idx - 1) : null;
        NavPath.Node next = idx + 1 < path.size() ? path.get(idx + 1) : null;

        // Aim: straight at the node, pitch level (so the head isn't tilted at the floor while walking).
        if (hd > 0.05) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            nms.absSnapRotationTo(yaw, 0f);
            NmsPlayerSpawner.setHeadYaw(bot, yaw);
        }

        float forward = 1.0f;
        boolean jump = false;

        if (move == Move.PILLAR) {
            pillar(fp, state, bot, nms, py, hd, prev, t);
            return;
        }

        switch (move) {
            case ASCEND -> jump = onGround && hd < 1.45 && t.y() > py + 0.3;
            case PARKOUR -> {
                // Take off at the lip of the starting block, not before.
                if (prev != null && onGround) {
                    double dirX = Integer.signum(t.x() - prev.x());
                    double dirZ = Integer.signum(t.z() - prev.z());
                    double along = (px - prev.cx()) * dirX + (pz - prev.cz()) * dirZ;
                    jump = along >= 0.32;
                }
            }
            case CLIMB_UP -> jump = true;
            case CLIMB_DOWN -> {
                forward = hd > 0.35 ? 0.3f : 0f;
                jump = false;
            }
            case SWIM -> jump = t.y() >= (int) Math.floor(py);
            default -> jump = onGround && t.y() > py + 0.45 && hd < 1.4;
        }
        if (inWater && move != Move.SWIM && t.y() >= py - 0.2) jump = true;

        // Sprint when there is a long, simple run ahead.
        boolean sprint = false;
        if (!inWater && !t.needsWork()) {
            boolean straightMove =
                    move == Move.WALK || move == Move.DIAGONAL || move == Move.PARKOUR || move == Move.ASCEND;
            boolean nextSafe = next == null
                    || (next.move() != Move.FALL
                            && next.move() != Move.DESCEND
                            && next.move() != Move.CLIMB_UP
                            && next.move() != Move.CLIMB_DOWN
                            && !next.needsWork());
            double remaining = remaining(path, idx, px, pz);
            sprint = straightMove
                    && nextSafe
                    && (move == Move.PARKOUR || remaining > Config.pathfindingSprintDistance());
        }

        bot.setSprinting(sprint);
        NmsPlayerSpawner.setMovementForward(bot, forward);
        NmsPlayerSpawner.setMovementStrafe(bot, 0f);
        NmsPlayerSpawner.setJumping(bot, jump);
        if (jump) manager.requestNavJump(fp.getUuid());
    }

    /**
     * Builds up one block like a player pillar-jumping: centred on the column, looking straight down,
     * jump, and once the feet clear the cell below place a block into it with a real use-item-on packet.
     */
    private void pillar(
            FakePlayer fp,
            NavState state,
            Player bot,
            ServerPlayer nms,
            double py,
            double hd,
            NavPath.Node prev,
            NavPath.Node t) {
        double cx = t.cx(), cz = t.cz();
        Location cur = bot.getLocation();
        double dx = cx - cur.getX(), dz = cz - cur.getZ();
        double off = Math.sqrt(dx * dx + dz * dz);

        // Look straight down (the place target is the block we just left).
        nms.absSnapRotationTo(cur.getYaw(), 90f);

        // Re-centre first; sprinting/jumping off-centre makes the placement miss its support.
        if (off > 0.25) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            nms.absSnapRotationTo(yaw, 90f);
            NmsPlayerSpawner.setMovementForward(bot, 0.6f);
            NmsPlayerSpawner.setJumping(bot, false);
            bot.setSprinting(false);
            return;
        }
        NmsPlayerSpawner.setMovementForward(bot, 0f);
        bot.setSprinting(false);

        int baseY = t.y() - 1; // the cell we are leaving (its floor is the support)
        BlockPos cell = new BlockPos(t.x(), baseY, t.z());
        boolean filled = !nms.level().getBlockState(cell).isAir()
                && !nms.level().getBlockState(cell).canBeReplaced();

        if (!filled) {
            if (!NavBlocks.equipPlaceable(bot)) {
                fail(fp, state, "NO_BLOCKS");
                return;
            }
            if (nms.onGround()) {
                NmsPlayerSpawner.setJumping(bot, true);
                manager.requestNavJump(fp.getUuid());
            } else if (py >= baseY + 1.0 - 0.02) {
                NmsPlayerSpawner.setJumping(bot, false);
                NavBlocks.place(bot, cell);
            }
        } else {
            NmsPlayerSpawner.setJumping(bot, false);
        }
        state.workTicks++;
        if (state.workTicks > 20 * 6) fail(fp, state, "PILLAR_TIMEOUT");
    }

    private static double remaining(NavPath path, int idx, double px, double pz) {
        double total = 0;
        double lx = px, lz = pz;
        for (int i = idx; i < path.size(); i++) {
            NavPath.Node n = path.get(i);
            total += horizontal(lx, lz, n.cx(), n.cz());
            lx = n.cx();
            lz = n.cz();
            if (total > 64) break;
        }
        return total;
    }

    // ── Obstacle clearing ────────────────────────────────────────────────────

    /** @return true while work is still in progress (movement must hold). */
    private boolean doWork(FakePlayer fp, NavState state, Player bot, ServerPlayer nms, NavPath.Node t, Location cur) {
        var world = bot.getWorld();

        for (long packed : t.doors()) {
            BlockPos p = BlockPos.of(packed);
            Block block = world.getBlockAt(p.getX(), p.getY(), p.getZ());
            if (!block.getType().isAir() && !terrainOpen(state, nms, p)) {
                faceBlock(bot, nms, p);
                if (state.tickCount % 6 == 0) BotNavUtil.useStorageBlock(bot, block);
                return true;
            }
        }

        BotClient client = BotClient.of(nms);
        for (long packed : t.breaks()) {
            BlockPos p = BlockPos.of(packed);
            if (nms.level().getBlockState(p).isAir()
                    || nms.level()
                            .getBlockState(p)
                            .getCollisionShape(nms.level(), p)
                            .isEmpty()) {
                continue;
            }
            // Look at it and hold the attack key - the same dig a player's mouse produces.
            faceBlock(bot, nms, p);
            HitResult hit = BotClient.pick(nms);
            if (hit instanceof BlockHitResult bh
                    && hit.getType() == HitResult.Type.BLOCK
                    && !bh.getBlockPos().equals(p)) {
                // Something else is in front of it; only dig that if the pathfinder may break it too.
                state.terrain.refresh();
                var c = state.terrain.cell(
                        bh.getBlockPos().getX(),
                        bh.getBlockPos().getY(),
                        bh.getBlockPos().getZ());
                if (!c.breakable) {
                    client.stopDestroyBlock(nms);
                    fail(fp, state, "WORK_BLOCKED");
                    return true;
                }
            }
            client.holdAttack(nms, hit, 0);
            state.digging = true;
            if (++state.workTicks > WORK_TIMEOUT_TICKS) {
                client.stopDestroyBlock(nms);
                fail(fp, state, "WORK_TIMEOUT");
            }
            return true;
        }
        if (state.digging) {
            client.stopDestroyBlock(nms);
            state.digging = false;
        }

        if (t.place() != NavPath.Node.NONE && t.move() != Move.PILLAR) {
            BlockPos p = BlockPos.of(t.place());
            var st = nms.level().getBlockState(p);
            if (st.isAir() || st.canBeReplaced()) {
                if (!NavBlocks.equipPlaceable(bot)) {
                    fail(fp, state, "NO_BLOCKS");
                    return true;
                }
                faceBlock(bot, nms, p);
                NavBlocks.place(bot, p);
                if (++state.workTicks > WORK_TIMEOUT_TICKS) fail(fp, state, "WORK_TIMEOUT");
                return true;
            }
        }
        state.workTicks = 0;
        return false;
    }

    private boolean terrainOpen(NavState state, ServerPlayer nms, BlockPos p) {
        if (state.terrain == null) return false;
        state.terrain.refresh();
        return !state.terrain.cell(p.getX(), p.getY(), p.getZ()).solid;
    }

    private static void faceBlock(Player bot, ServerPlayer nms, BlockPos p) {
        Location eye = bot.getEyeLocation();
        double dx = p.getX() + 0.5 - eye.getX();
        double dy = p.getY() + 0.5 - eye.getY();
        double dz = p.getZ() + 0.5 - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        nms.absSnapRotationTo(yaw, pitch);
        NmsPlayerSpawner.setHeadYaw(bot, yaw);
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /** Re-checks the next few nodes against the live world. */
    private boolean validAhead(NavState state) {
        if (state.terrain == null || state.path == null) return true;
        state.terrain.refresh();
        NavPath path = state.path;
        boolean avoidWater = false;
        int end = Math.min(path.size(), state.index + 4);
        for (int i = Math.max(1, state.index); i < end; i++) {
            NavPath.Node n = path.get(i);
            if (n.needsWork()) continue; // handled (and re-evaluated) by the work step
            Stance s = state.terrain.stance(n.x(), n.y(), n.z(), avoidWater);
            if (s == Stance.INVALID) return false;
            boolean needsFooting =
                    switch (n.move()) {
                        case WALK, DIAGONAL, ASCEND, PARKOUR, DESCEND, FALL -> true;
                        default -> false;
                    };
            if (needsFooting && s == Stance.AIR) return false;
        }
        return true;
    }

    // ── Stuck handling ───────────────────────────────────────────────────────

    private void trackStuck(FakePlayer fp, NavState state, Player bot, ServerPlayer nms, Location cur) {
        int stuckLimit = Config.pathfindingStuckTicks();
        if (state.lastPos != null && state.lastPos.getWorld() == cur.getWorld()) {
            double moved = Math.sqrt(Math.pow(cur.getX() - state.lastPos.getX(), 2)
                    + Math.pow(cur.getY() - state.lastPos.getY(), 2)
                    + Math.pow(cur.getZ() - state.lastPos.getZ(), 2));
            if (moved < Config.pathfindingStuckThreshold()) {
                state.stuckTicks++;
            } else {
                state.stuckTicks = 0;
                state.stuckCycles = 0;
            }
        }
        state.lastPos = cur.clone();

        // A hop is usually all it takes (lip, slab, leaf) - try that before re-planning.
        if (state.stuckTicks == Math.max(1, stuckLimit / 2)) {
            manager.requestNavJump(fp.getUuid());
            NmsPlayerSpawner.setJumping(bot, true);
        }

        if (state.stuckTicks >= stuckLimit) {
            state.stuckTicks = 0;
            state.stuckCycles++;
            if (state.stuckCycles >= Config.pathfindingMaxStuckCycles()) {
                fail(fp, state, "STUCK");
                return;
            }
            Config.debugPathfinding(diagnostic(fp, state, "STUCK"));
            sendDebugChat(fp.getUuid(), fp.getName(), "pathdebug-stuck", "cycle", String.valueOf(state.stuckCycles));
            if (state.search == null) beginSearch(fp, state, nms, cur, "STUCK");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Termination
    // ═════════════════════════════════════════════════════════════════════════

    private static boolean arrived(Location cur, Location target, double arrivalDistance) {
        double xz = horizontal(cur.getX(), cur.getZ(), target.getX(), target.getZ());
        return xz <= arrivalDistance && Math.abs(cur.getY() - target.getY()) < 1.25;
    }

    private void arrive(FakePlayer fp, NavState state) {
        UUID uuid = fp.getUuid();
        if (states.get(uuid) != state) return;
        states.remove(uuid);
        stop(uuid, state);

        Player bot = fp.getPlayer();
        Location lock = state.request.lockOnArrival();
        if (bot != null && bot.isOnline() && lock != null) {
            ((CraftPlayer) bot).getHandle().absSnapRotationTo(lock.getYaw(), lock.getPitch());
            NmsPlayerSpawner.setHeadYaw(bot, lock.getYaw());
        }
        if (state.request.onArrive() != null) state.request.onArrive().run();
    }

    private void fail(FakePlayer fp, NavState state, String reason) {
        UUID uuid = fp.getUuid();
        if (states.get(uuid) != state) return;
        states.remove(uuid);
        stop(uuid, state);
        Config.debugPathfinding(diagnostic(fp, state, reason));
        sendDebugChat(
                uuid,
                fp.getName(),
                reason.equals("STUCK") || reason.equals("NO_PROGRESS") ? "pathdebug-unreachable" : "pathdebug-no-path");
        if (state.request.onPathFailure() != null) state.request.onPathFailure().run();
        else if (state.request.onCancel() != null) state.request.onCancel().run();
    }

    private void stop(UUID uuid, NavState state) {
        if (state.taskId >= 0) {
            FppScheduler.cancelTask(state.taskId);
            state.taskId = -1;
        }
        state.search = null;
        manager.unlockNavigation(uuid);
        manager.clearNavJump(uuid);
        FakePlayer fp = manager.getByUuid(uuid);
        Player bot = fp != null ? fp.getPlayer() : null;
        if (bot != null && bot.isOnline()) stopInput(bot);
    }

    private static void stopInput(Player bot) {
        NmsPlayerSpawner.setMovementForward(bot, 0f);
        NmsPlayerSpawner.setMovementStrafe(bot, 0f);
        NmsPlayerSpawner.setJumping(bot, false);
        bot.setSprinting(false);
    }

    private static double horizontal(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Diagnostics / debug rendering
    // ═════════════════════════════════════════════════════════════════════════

    private String diagnostic(FakePlayer fp, NavState state, String event) {
        Player bot = fp.getPlayer();
        Location cur = bot != null ? bot.getLocation() : null;
        Location tgt = state.lastTarget;
        BotPathfinder.PathOptions o = PathfindingService.resolvePathOptions(fp, state.request.overrideOpts());
        return "event=" + event + " bot='" + fp.getName() + "' owner=" + state.owner + " node="
                + state.index + "/" + (state.path == null ? 0 : state.path.size()) + " pos=" + fmt(cur)
                + " target=" + fmt(tgt) + " stuckCycles=" + state.stuckCycles + " opts{parkour=" + o.parkour()
                + ",break=" + o.breakBlocks() + ",place=" + o.placeBlocks() + ",avoidWater=" + o.avoidWater()
                + ",avoidLava=" + o.avoidLava() + "}";
    }

    private static String fmt(@Nullable Location loc) {
        if (loc == null || loc.getWorld() == null) return "null";
        return loc.getWorld().getName() + String.format(":(%.1f,%.1f,%.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private static final int DEBUG_RENDER_INTERVAL_TICKS = 4;
    private static final int DEBUG_MAX_NODES_SHOWN = 40;

    private void renderDebug(UUID botUuid, NavState state, Location current) {
        Set<UUID> viewers = PathfindingDebugManager.getViewers(botUuid);
        if (viewers.isEmpty() || state.path == null) return;
        if (++state.debugRenderTick % DEBUG_RENDER_INTERVAL_TICKS != 0) return;

        NavPath path = state.path;
        int start = state.index;
        int end = Math.min(path.size(), start + DEBUG_MAX_NODES_SHOWN);
        int last = path.size() - 1;

        for (UUID viewerUuid : viewers) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            if (viewer == null || !viewer.isOnline() || viewer.getWorld() != current.getWorld()) continue;
            for (int i = start; i < end; i++) {
                NavPath.Node n = path.get(i);
                Location loc = new Location(current.getWorld(), n.cx(), n.y() + 0.15, n.cz());
                boolean isNext = i == start;
                boolean isFinal = i == last;
                Color color = isFinal ? Color.RED : n.needsWork() ? Color.YELLOW : isNext ? Color.ORANGE : Color.LIME;
                float size = isNext || isFinal ? 1.3f : 0.9f;
                viewer.spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
            }
        }
    }

    /** Chat-side counterpart of the particle debug: tells subscribed viewers why a bot is stuck/failing. */
    public static void sendDebugChat(UUID botUuid, String botName, String langKey, String... args) {
        PathfindingDebugManager.sendChat(botUuid, botName, langKey, args);
    }

    // ── State ────────────────────────────────────────────────────────────────

    private static final class NavState {
        final PathfindingService.NavigationRequest request;
        final PathfindingService.Owner owner;
        volatile int taskId = -1;

        @Nullable
        NavTerrain terrain;

        @Nullable
        NavSearch search;

        @Nullable
        NavPath path;

        int index;
        Location lastTarget;
        Location lastPos;
        int tickCount;
        int stuckTicks;
        int stuckCycles;
        int failedSearches;
        int partialHops;
        int noProgressHops;
        double lastPartialDist = -1;
        double searchStartDist;
        int debugRenderTick;
        int workTicks;
        boolean digging;

        NavState(PathfindingService.NavigationRequest request) {
            this.request = request;
            this.owner = request.owner();
        }
    }
}
