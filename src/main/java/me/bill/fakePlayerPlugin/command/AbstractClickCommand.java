package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppClickMode;
import me.bill.fakePlayerPlugin.api.event.FppBotTaskEvent;
import me.bill.fakePlayerPlugin.api.impl.FppApiImpl;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.BotPathfinder;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Host for {@code /fpp left-click} and {@code /fpp right-click}. A bot's click task is a tiny virtual
 * client: every server tick the subclass runs one client tick against the shared {@link BotClient}
 * (the port of the vanilla client's attack/use/dig logic). This class owns everything around that -
 * argument parsing and permissions, resolving what to click, walking into reach, the aim-lock, the
 * task lifecycle ({@code --stop}, save/restore) and tab completion.
 *
 * <h3>Click modes</h3>
 * <ul>
 *   <li>{@code --once} - one press of the button (and, if that press started a dig or a multi-tick use,
 *       the button stays down until it finishes).</li>
 *   <li>{@code --repeat} - the press is repeated at the bot's configured interval.</li>
 *   <li>{@code --hold} - the button is held down, exactly like a player holding the mouse button.</li>
 * </ul>
 */
public abstract class AbstractClickCommand implements FppCommand {

    protected final FakePlayerPlugin plugin;
    protected final FakePlayerManager manager;
    protected final PathfindingService pathfinding;

    protected final Map<UUID, Integer> clickTasks = new ConcurrentHashMap<>();
    protected final Map<UUID, ClickState> clickStates = new ConcurrentHashMap<>();
    protected final Map<UUID, FppClickMode> clickModes = new ConcurrentHashMap<>();

    protected AbstractClickCommand(FakePlayerPlugin plugin, FakePlayerManager manager, PathfindingService pathfinding) {
        this.plugin = plugin;
        this.manager = manager;
        this.pathfinding = pathfinding;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Hooks subclasses supply
    // ─────────────────────────────────────────────────────────────────────────────

    /** Task-event / lang-key prefix / debug name, e.g. {@code "left-click"}. */
    protected abstract String taskName();

    /** The pathfinding "owner" tag used while walking to a vantage point for this action. */
    protected abstract PathfindingService.Owner navOwner();

    /** Arrival distance passed to the navigation request when walking to a target. */
    protected abstract double navArrivalDistance();

    protected abstract void dbg(String msg);

    protected abstract String stopPermission();

    protected abstract String modePermission(FppClickMode mode);

    /**
     * Runs one client tick for the bot. Returns {@code true} when a {@code --once} click has run its
     * course and the task should end.
     */
    protected abstract boolean tickClient(FakePlayer fp, ServerPlayer nms, ClickState state);

    /** Hook: cleanup before the task/state is torn down, while the bot is still known-online. */
    protected void onBeforeStop(FakePlayer fp, Player bot, @Nullable ClickState state) {}

    /** Hook: cleanup after the task/state is torn down, while the bot is still known-online. */
    protected void onAfterStop(Player bot) {}

    /** Starts (or restarts) the click loop from the bot's own view. Used by the public API and restore. */
    public final boolean click(FakePlayer fp, FppClickMode mode) {
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return false;

        cancelAll(fp.getUuid());
        if (mode == FppClickMode.STOP) return true;

        Aim aim = selfAim(bot);
        dbg("click(): bot=" + fp.getDisplayName() + " mode=" + mode + " target=" + describe(aim));
        startLoop(fp, mode, aim.target, aim.point);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Command entry
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public final String getName() {
        return taskName();
    }

    @Override
    public final String getUsage() {
        return "<bot> [--once|--repeat|--hold|--stop]  |  --stop";
    }

    @Override
    public final String getPermission() {
        return modePermission(FppClickMode.STOP);
    }

    @Override
    public final boolean execute(CommandSender sender, String[] args) {
        final String n = taskName();
        if (args.length == 0) {
            sender.sendMessage(Lang.get(n + "-usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("--stop") && args.length == 1) {
            if (!Perm.has(sender, stopPermission())) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
            stopAll();
            sender.sendMessage(Lang.get(n + "-stopped-all"));
            return true;
        }

        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get(n + "-not-found", "name", args[0]));
            return true;
        }

        if (sender instanceof Player player && !Perm.has(sender, Perm.ADMIN)) {
            if (!BotAccess.canAdminister(player, fp)) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
        }

        FppClickMode mode = FppClickMode.ONCE;
        boolean stop = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--once" -> mode = FppClickMode.ONCE;
                case "--repeat" -> mode = FppClickMode.REPEAT;
                case "--hold" -> mode = FppClickMode.HOLD;
                case "--stop" -> stop = true;
                default -> {
                    sender.sendMessage(Lang.get(n + "-usage"));
                    return true;
                }
            }
        }

        if (stop) {
            if (!Perm.has(sender, stopPermission())) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
            cleanupBot(fp.getUuid());
            sender.sendMessage(Lang.get(n + "-stopped", "name", fp.getDisplayName()));
            return true;
        }

        // Always gate on the resolved mode's permission - even a bare "<bot>" (defaults to --once).
        if (!Perm.has(sender, modePermission(mode))) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            sender.sendMessage(Lang.get(n + "-bot-offline", "name", fp.getDisplayName()));
            return true;
        }

        cancelAll(fp.getUuid());

        // The sender's crosshair designates a block to work on; otherwise the bot clicks whatever its own
        // crosshair is already on. Per tick the bot always acts on what ITS crosshair currently sees.
        Aim aim = senderAim(sender, bot);
        if (aim.target == null) aim = selfAim(bot);
        dbg("execute: bot=" + fp.getDisplayName() + " mode=" + mode + " target=" + describe(aim));

        final FppClickMode finalMode = mode;
        final Aim finalAim = aim;
        if (aim.target != null) {
            Location targetLoc = locationOf(bot, aim.target);
            double reach = reachOf(bot);
            if (targetLoc != null && bot.getEyeLocation().distance(targetLoc) > reach) {
                Location standLoc = resolveStandLocation(bot.getWorld(), sender, targetLoc, reach);
                if (standLoc == null) {
                    sender.sendMessage(Lang.get(n + "-no-path", "name", fp.getDisplayName()));
                    return true;
                }
                startNavigation(fp, standLoc, () -> startLoop(fp, finalMode, finalAim.target, finalAim.point));
                sender.sendMessage(Lang.get(n + "-walking", "name", fp.getDisplayName()));
                return true;
            }
        }

        startLoop(fp, finalMode, aim.target, aim.point);
        String key =
                switch (finalMode) {
                    case ONCE -> n + "-started-once";
                    case REPEAT -> n + "-started-repeat";
                    case HOLD -> n + "-started-hold";
                    default -> n + "-started";
                };
        sender.sendMessage(Lang.get(aim.target != null ? key : n + "-started", "name", fp.getDisplayName()));
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  The click loop
    // ─────────────────────────────────────────────────────────────────────────────

    protected final void startLoop(FakePlayer fp, FppClickMode mode, @Nullable Object target, @Nullable Vector aim) {
        FppApiImpl.fireTaskEvent(fp, taskName(), FppBotTaskEvent.Action.START);
        UUID uuid = fp.getUuid();
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return;

        if (target instanceof Entity e && e.getUniqueId().equals(uuid)) target = null;

        ClickState state = new ClickState(mode);
        state.aimTarget = target;
        state.aimPoint = aim;
        clickStates.put(uuid, state);
        clickModes.put(uuid, mode);

        // Face the commanded target before the first tick (absSnapRotationTo - NOT setRotation, whose
        // connection teleport would arm awaitingPositionFromClient and block interactions).
        refreshAim(bot, state);
        NmsPlayerSpawner.setMovementForward(bot, 0f);
        bot.setSprinting(false);
        manager.lockForAction(uuid, bot.getLocation().clone(), false);

        dbg("start: bot=" + fp.getDisplayName() + " mode=" + mode + " target="
                + (target == null ? "self-view" : "set"));

        int taskId = FppScheduler.runSyncRepeatingWithId(
                plugin,
                bot,
                () -> {
                    Player b = fp.getPlayer();
                    if (b == null || !b.isOnline()) {
                        stopClicking(uuid);
                        return;
                    }
                    if (fp.isInventoryOpen() || fp.isActionsPaused()) return;

                    ServerPlayer nms = ((CraftPlayer) b).getHandle();
                    nms.resetLastActionTime();
                    refreshAim(b, state);
                    if (tickClient(fp, nms, state)) {
                        stopClicking(uuid);
                    }
                },
                0L,
                1L);
        clickTasks.put(uuid, taskId);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Target resolution
    // ─────────────────────────────────────────────────────────────────────────────

    /** A resolved click target: the block/entity (or null = "wherever the bot is looking") + the exact point. */
    protected record Aim(@Nullable Object target, @Nullable Vector point) {}

    private static String describe(Aim aim) {
        if (aim.target instanceof Block b) return "block " + b.getType();
        if (aim.target instanceof Entity e) return "entity " + e.getType();
        return "self-view";
    }

    /** Interaction reach used for walking into range: the block range, like the client's own pick. */
    protected static double reachOf(Player bot) {
        return ((CraftPlayer) bot).getHandle().blockInteractionRange();
    }

    /** The block under the commanding player's crosshair, with the exact hit point. */
    private static Aim senderAim(CommandSender sender, Player bot) {
        if (!(sender instanceof Player player)) return new Aim(null, null);
        try {
            RayTraceResult ray = player.rayTraceBlocks(Math.max(reachOf(bot), 5.0));
            if (ray != null
                    && ray.getHitBlock() != null
                    && !ray.getHitBlock().getType().isAir()) {
                return new Aim(ray.getHitBlock(), ray.getHitPosition());
            }
        } catch (Exception ignored) {
        }
        return new Aim(null, null);
    }

    /** What the bot's own crosshair is on right now, per the real client pick. */
    private static Aim selfAim(Player bot) {
        try {
            ServerPlayer nms = ((CraftPlayer) bot).getHandle();
            HitResult hit = BotClient.pick(nms);
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                var p = bh.getBlockPos();
                Block block = bot.getWorld().getBlockAt(p.getX(), p.getY(), p.getZ());
                if (!block.getType().isAir()) {
                    var l = hit.getLocation();
                    return new Aim(block, new Vector(l.x, l.y, l.z));
                }
            } else if (hit instanceof EntityHitResult eh) {
                var l = hit.getLocation();
                return new Aim(eh.getEntity().getBukkitEntity(), new Vector(l.x, l.y, l.z));
            }
        } catch (Throwable ignored) {
        }
        return new Aim(null, null);
    }

    @Nullable
    private static Location locationOf(Player bot, Object target) {
        if (target instanceof Block b) {
            return new Location(bot.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        }
        if (target instanceof Entity e) {
            return e.getBoundingBox().getCenter().toLocation(e.getWorld());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Aim
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Holds the crosshair on the commanded target - like a player keeping their aim steady - so
     * knockback or a passing mob can't permanently steer the bot off what it was told to click. An
     * entity target is tracked (its centre), a block target uses the exact point that was aimed at.
     */
    protected static void refreshAim(Player bot, ClickState state) {
        Vector point = null;
        if (state.aimTarget instanceof Entity e && e.isValid()) {
            point = e.getBoundingBox().getCenter();
        } else if (state.aimPoint != null) {
            point = state.aimPoint;
        } else if (state.aimTarget instanceof Block b) {
            point = new Vector(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        }
        if (point == null) return;
        applyAim(bot, point);
    }

    private static void applyAim(Player bot, Vector point) {
        Location eye = bot.getEyeLocation();
        double dx = point.getX() - eye.getX();
        double dy = point.getY() - eye.getY();
        double dz = point.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        ((CraftPlayer) bot).getHandle().absSnapRotationTo(yaw, pitch);
        NmsPlayerSpawner.setHeadYaw(bot, yaw);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Stand-location search / navigation
    // ─────────────────────────────────────────────────────────────────────────────

    @Nullable
    private Location findStandLocation(World world, Location center, Location targetLoc, int radius, double maxDist) {
        // One terrain view for the whole search (cells are cached), squared distances, no per-cell allocation.
        NavTerrain terrain = new NavTerrain(((org.bukkit.craftbukkit.CraftWorld) world).getHandle());
        double maxSq = maxDist * maxDist;
        double tx = targetLoc.getX(), ty = targetLoc.getY(), tz = targetLoc.getZ();
        int ox = center.getBlockX(), oy = center.getBlockY(), oz = center.getBlockZ();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) < r && Math.abs(dz) < r) continue;
                    int cx = ox + dx, cz = oz + dz;
                    for (int dy = 0; dy < 3; dy++) {
                        int cy = oy + (dy == 0 ? 0 : dy == 1 ? -1 : 1);
                        if (terrain.stance(cx, cy, cz, false) != NavTerrain.Stance.GROUND) continue;
                        double ex = cx + 0.5 - tx, ey = cy + 1.62 - ty, ez = cz + 0.5 - tz;
                        if (ex * ex + ey * ey + ez * ez <= maxSq) return new Location(world, cx + 0.5, cy, cz + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves where the bot should stand to reach an out-of-reach target. Prefers the sender's own
     * standing location - a vantage the target is provably aim-able from - and falls back to a search
     * around the target.
     */
    @Nullable
    protected final Location resolveStandLocation(World world, CommandSender sender, Location targetLoc, double reach) {
        if (sender instanceof Player player && player.getWorld() == world) {
            Location atPlayer = findStandLocation(world, player.getLocation(), targetLoc, 4, reach - 0.5);
            if (atPlayer != null) return atPlayer;
        }
        return findStandLocation(world, targetLoc, targetLoc, 4, reach - 1.5);
    }

    protected final void startNavigation(FakePlayer fp, Location dest, Runnable onArrive) {
        BotPathfinder.PathOptions baseOpts = PathfindingService.resolvePathOptions(fp);
        BotPathfinder.PathOptions opts = new BotPathfinder.PathOptions(
                fp.isNavParkour(), true, fp.isNavPlaceBlocks(), baseOpts.avoidWater(), baseOpts.avoidLava());
        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        navOwner(),
                        () -> dest,
                        navArrivalDistance(),
                        0.0,
                        Integer.MAX_VALUE,
                        onArrive,
                        null,
                        null,
                        null,
                        opts));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Lifecycle: stop / cleanup / save-restore
    // ─────────────────────────────────────────────────────────────────────────────

    protected final void cancelAll(UUID botUuid) {
        // Only release the nav slot if this action's own walk-to-vantage currently owns it - another
        // concurrently running task (move, find, PVE) may hold it instead.
        if (pathfinding.isNavigating(botUuid, navOwner())) {
            pathfinding.cancel(botUuid);
        }
        stopClicking(botUuid);
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) {
            Player bot = fp.getPlayer();
            if (bot != null && bot.isOnline()) {
                NmsPlayerSpawner.setMovementForward(bot, 0f);
                NmsPlayerSpawner.setJumping(bot, false);
                bot.setSprinting(false);
            }
        }
    }

    public final void stopClicking(UUID botUuid) {
        stopClicking(botUuid, true);
    }

    public final void stopClicking(UUID botUuid, boolean clearState) {
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) {
            dbg("stop: bot=" + fp.getDisplayName() + " clearState=" + clearState);
            FppApiImpl.fireTaskEvent(fp, taskName(), FppBotTaskEvent.Action.STOP);
            Player bot = fp.getPlayer();
            if (bot != null && bot.isOnline()) {
                onBeforeStop(fp, bot, clickStates.get(botUuid));
            }
        }
        Integer taskId = clickTasks.remove(botUuid);
        if (taskId != null) FppScheduler.cancelTask(taskId);
        manager.unlockAction(botUuid);
        if (clearState) {
            clickStates.remove(botUuid);
            clickModes.remove(botUuid);
        }
        if (fp != null) {
            Player bot = fp.getPlayer();
            if (bot != null && bot.isOnline()) {
                onAfterStop(bot);
            }
        }
    }

    public final void stopAll() {
        pathfinding.cancelAll(navOwner());
        new HashSet<>(clickTasks.keySet()).forEach(this::cleanupBot);
    }

    public final void cleanupBot(UUID botUuid) {
        cancelAll(botUuid);
    }

    public final boolean isClicking(UUID botUuid) {
        return clickTasks.containsKey(botUuid);
    }

    /** Snapshot of the bot's active click task for persistence, or null when it isn't clicking. */
    @Nullable
    public final SavedClickTask getSavedTask(UUID botUuid) {
        FppClickMode mode = clickModes.get(botUuid);
        if (mode == null || mode == FppClickMode.STOP || !clickTasks.containsKey(botUuid)) return null;
        FakePlayer fp = manager.getByUuid(botUuid);
        Player bot = fp != null ? fp.getPlayer() : null;
        if (bot == null) return null;
        ClickState state = clickStates.get(botUuid);
        return new SavedClickTask(mode.name(), bot.getWorld().getName(), state != null ? state.aimPoint : null);
    }

    /**
     * Resumes a persisted click task after a restart: re-aims the bot at the saved point (when one was
     * locked) and restarts the click loop via the normal self-view resolution.
     */
    public final void resumeSavedTask(FakePlayer fp, String modeName, @Nullable Vector aimPoint) {
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return;
        FppClickMode mode;
        try {
            mode = FppClickMode.valueOf(modeName);
        } catch (IllegalArgumentException | NullPointerException e) {
            mode = FppClickMode.HOLD;
        }
        if (mode == FppClickMode.STOP) return;
        if (aimPoint != null) applyAim(bot, aimPoint);
        dbg("resume: bot=" + fp.getDisplayName() + " mode=" + mode + " aim=" + aimPoint);
        click(fp, mode);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Tab completion - <bot> [--once|--repeat|--hold|--stop] | --stop
    // ─────────────────────────────────────────────────────────────────────────────

    @Override
    public final List<String> tabComplete(CommandSender sender, String[] args) {
        if (!canUse(sender)) return List.of();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("--stop".startsWith(prefix)) out.add("--stop");
            for (FakePlayer fp : manager.getActivePlayers()) {
                if (fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
            }
            return out;
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("--stop")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String flag : List.of("--once", "--repeat", "--hold", "--stop")) {
                if (flag.startsWith(prefix)) out.add(flag);
            }
            return out;
        }

        return List.of();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Per-bot loop state
    // ─────────────────────────────────────────────────────────────────────────────

    /** Per-bot click-task state: the commanded target and the mode's button state machine. */
    protected static final class ClickState {
        final FppClickMode mode;

        /** The commanded target + exact aim point; the head is re-aimed at it every tick. */
        @Nullable
        Object aimTarget;

        @Nullable
        Vector aimPoint;

        /** The initial press has been issued. */
        boolean pressed;
        /** The button is currently held down (a dig in progress / a multi-tick use being held). */
        boolean keyDown;
        /** Ticks to wait before the next press (REPEAT). */
        int pause;

        ClickState(FppClickMode mode) {
            this.mode = mode;
        }
    }
}
