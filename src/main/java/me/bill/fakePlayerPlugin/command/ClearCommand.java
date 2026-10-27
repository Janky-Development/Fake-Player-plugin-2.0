package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotTaskEvent;
import me.bill.fakePlayerPlugin.api.impl.FppApiImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.BotPathfinder;
import me.bill.fakePlayerPlugin.fakeplayer.BotToolSelector;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.StorageInteractionHelper;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavBlocks;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.mining.ClearRegion;
import me.bill.fakePlayerPlugin.mining.MaterialSelector;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.WorldEditHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /fpp clear}: a fully self-contained excavation job. The bot clears a chunk or an area one
 * horizontal layer at a time, top to bottom, the way a person strip-excavates:
 *
 * <ul>
 *   <li>it works from <em>inside</em> the layer - mining outward from where it stands and stepping into
 *       the space it opens - so it never needs a clear path from outside;</li>
 *   <li>when a layer is done it digs straight down into the next one and carries on;</li>
 *   <li>every block comes out through the vanilla client's dig logic (aim, hold attack, real packets), so
 *       tool choice, break time, protection plugins and events behave exactly as for a player;</li>
 *   <li>it only ever digs inside the region, seals water/lava it would otherwise release, collects its
 *       drops, deposits at a registered storage (or discards chosen junk) when full, and recovers from
 *       being stuck - re-anchoring, and <b>building up</b> with scaffold blocks when it is below the layer
 *       it needs to reach.</li>
 * </ul>
 *
 * No player needs to watch or help: it runs until the region is clear (or it genuinely cannot continue).
 */
public final class ClearCommand implements FppCommand {

    private static final int REACH_SLACK_TICKS = 40;
    private static final int STUCK_TICKS = 20 * 25;
    private static final int PROGRESS_SAMPLE_TICKS = 20;
    private static final int LOW_FREE_SLOTS = 1;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final PathfindingService pathfinding;
    private final StorageStore storageStore;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

    public ClearCommand(
            FakePlayerPlugin plugin,
            FakePlayerManager manager,
            PathfindingService pathfinding,
            StorageStore storageStore) {
        this.plugin = plugin;
        this.manager = manager;
        this.pathfinding = pathfinding;
        this.storageStore = storageStore;
    }

    // ── metadata ────────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public String getUsage() {
        return "<bot> [--chunk [<cx> <cz>] | --area <x1> <y1> <z1> <x2> <y2> <z2> | --selection | --radius <n>]"
                + " [--top <y>] [--bottom <y> | --depth <n>] [--keep <blocks>] [--trash [<blocks>]]"
                + " [--containers] [--tunnel] [--no-build] [--no-seal]  |  <bot> --status  |  <bot> --stop  |  --stop";
    }

    @Override
    public String getDescription() {
        return "Excavate a chunk or area layer by layer, top to bottom, on its own: digs down through the"
                + " layers, seals fluids, deposits/discards loot, recovers when stuck and builds up if needed.";
    }

    @Override
    public String getPermission() {
        return Perm.CLEAR;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.CLEAR);
    }

    // ── execution ───────────────────────────────────────────────────────────

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.get("clear-usage"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("--stop")) {
            stopAll();
            sender.sendMessage(Lang.get("clear-stopped-all"));
            return true;
        }

        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("clear-not-found", "name", args[0]));
            return true;
        }
        if (sender instanceof Player player && !Perm.has(sender, Perm.ADMIN) && !BotAccess.canAdminister(player, fp)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            sender.sendMessage(Lang.get("clear-bot-offline", "name", fp.getDisplayName()));
            return true;
        }

        Set<String> flags = new HashSet<>();
        for (int i = 1; i < args.length; i++) flags.add(args[i].toLowerCase(Locale.ROOT));
        if (flags.contains("--stop")) {
            cleanupBot(fp.getUuid());
            sender.sendMessage(Lang.get("clear-stopped", "name", fp.getDisplayName()));
            return true;
        }
        if (flags.contains("--status")) {
            sendStatus(sender, fp);
            return true;
        }

        World world = bot.getWorld();
        Location loc = bot.getLocation();
        Integer top = null;
        Integer bottom = null;
        Integer depth = null;
        ClearRegion region = null;
        Set<Material> keep = new java.util.HashSet<>();
        Set<Material> trash = new java.util.HashSet<>();
        boolean containers = false;
        boolean tunnel = false;
        boolean build = true;
        boolean seal = true;
        boolean useSelection = false;
        Integer radius = null;
        int[] chunkCoords = null;

        try {
            for (int i = 1; i < args.length; i++) {
                String a = args[i].toLowerCase(Locale.ROOT);
                switch (a) {
                    case "--chunk" -> {
                        if (i + 2 < args.length && isInt(args[i + 1]) && isInt(args[i + 2])) {
                            chunkCoords = new int[] {Integer.parseInt(args[i + 1]), Integer.parseInt(args[i + 2])};
                            i += 2;
                        } else {
                            chunkCoords = new int[] {loc.getBlockX() >> 4, loc.getBlockZ() >> 4};
                        }
                    }
                    case "--area" -> {
                        if (i + 6 >= args.length) throw new IllegalArgumentException();
                        region = new ClearRegion(
                                Integer.parseInt(args[i + 1]), Integer.parseInt(args[i + 2]),
                                Integer.parseInt(args[i + 3]), Integer.parseInt(args[i + 4]),
                                Integer.parseInt(args[i + 5]), Integer.parseInt(args[i + 6]));
                        i += 6;
                    }
                    case "--selection", "--sel" -> useSelection = true;
                    case "--radius" -> radius = Math.max(1, Integer.parseInt(args[++i]));
                    case "--top" -> top = Integer.parseInt(args[++i]);
                    case "--bottom" -> bottom = Integer.parseInt(args[++i]);
                    case "--depth" -> depth = Math.max(1, Integer.parseInt(args[++i]));
                    case "--keep" -> {
                        Set<Material> m = MaterialSelector.parse(args[++i]);
                        if (m == null) {
                            sender.sendMessage(
                                    Lang.get("find-invalid-block", "block", MaterialSelector.firstUnknown(args[i])));
                            return true;
                        }
                        keep.addAll(m);
                    }
                    case "--trash" -> {
                        if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            Set<Material> m = MaterialSelector.parse(args[++i]);
                            if (m == null) {
                                sender.sendMessage(Lang.get(
                                        "find-invalid-block", "block", MaterialSelector.firstUnknown(args[i])));
                                return true;
                            }
                            trash.addAll(m);
                        } else {
                            trash.addAll(defaultTrash());
                        }
                    }
                    case "--containers" -> containers = true;
                    case "--tunnel" -> tunnel = true;
                    case "--no-build" -> build = false;
                    case "--no-seal" -> seal = false;
                    default -> {
                        sender.sendMessage(Lang.get("clear-usage"));
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            sender.sendMessage(Lang.get("clear-usage"));
            return true;
        }

        // ── resolve the region ──
        if (useSelection) {
            if (!(sender instanceof Player p) || !Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                sender.sendMessage(Lang.get("clear-no-selection"));
                return true;
            }
            Location[] sel = WorldEditHelper.getSelection(p);
            if (sel == null || sel[0].getWorld() != world) {
                sender.sendMessage(Lang.get("clear-no-selection"));
                return true;
            }
            region = new ClearRegion(
                    sel[0].getBlockX(),
                    sel[0].getBlockY(),
                    sel[0].getBlockZ(),
                    sel[1].getBlockX(),
                    sel[1].getBlockY(),
                    sel[1].getBlockZ());
        } else if (region == null) {
            if (radius != null) {
                int cx = loc.getBlockX(), cz = loc.getBlockZ();
                region = new ClearRegion(
                        cx - radius,
                        world.getMinHeight(),
                        cz - radius,
                        cx + radius,
                        world.getMaxHeight() - 1,
                        cz + radius);
            } else {
                int[] c = chunkCoords != null ? chunkCoords : new int[] {loc.getBlockX() >> 4, loc.getBlockZ() >> 4};
                region = ClearRegion.ofChunk(c[0], c[1], world.getMinHeight(), world.getMaxHeight() - 1);
            }
            // Fit the vertical extent to the terrain unless an explicit one was given.
            region = region.withY(world.getMinHeight(), highestSolid(world, region));
        }
        int minY = bottom != null ? bottom : region.minY();
        int maxY = top != null ? top : region.maxY();
        if (depth != null && top == null) {
            maxY = region.maxY();
            minY = Math.max(minY, maxY - depth + 1);
        } else if (depth != null) {
            minY = Math.max(minY, maxY - depth + 1);
        }
        minY = Math.max(minY, world.getMinHeight());
        maxY = Math.min(maxY, world.getMaxHeight() - 1);
        if (minY > maxY) {
            sender.sendMessage(Lang.get("clear-invalid-area"));
            return true;
        }
        region = region.withY(minY, maxY);

        if (region.totalCells() > Config.clearMaxCells()) {
            sender.sendMessage(Lang.get(
                    "clear-too-large",
                    "cells",
                    String.valueOf(region.totalCells()),
                    "max",
                    String.valueOf(Config.clearMaxCells())));
            return true;
        }
        for (int[] c : region.chunks()) {
            if (!world.isChunkLoaded(c[0], c[1])) {
                sender.sendMessage(Lang.get("clear-not-loaded", "x", String.valueOf(c[0]), "z", String.valueOf(c[1])));
                return true;
            }
        }

        cleanupBot(fp.getUuid());
        Job job = new Job(
                fp.getUuid(),
                world,
                region,
                keep,
                trash,
                containers,
                tunnel,
                build,
                seal,
                sender instanceof Player p ? p.getUniqueId() : null);
        jobs.put(fp.getUuid(), job);
        FppApiImpl.fireTaskEvent(fp, "clear", FppBotTaskEvent.Action.START);

        sender.sendMessage(Lang.get(
                "clear-started",
                "name",
                fp.getDisplayName(),
                "cells",
                String.valueOf(region.totalCells()),
                "layers",
                String.valueOf(region.layers()),
                "x1",
                String.valueOf(region.minX()),
                "z1",
                String.valueOf(region.minZ()),
                "x2",
                String.valueOf(region.maxX()),
                "z2",
                String.valueOf(region.maxZ()),
                "top",
                String.valueOf(region.maxY()),
                "bottom",
                String.valueOf(region.minY())));

        job.taskId = FppScheduler.runAtEntityRepeatingWithId(plugin, bot, () -> tick(fp, job), 1L, 1L);
        return true;
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<Material> defaultTrash() {
        List<Material> out = new ArrayList<>();
        for (String n : Config.clearDefaultTrash()) {
            Material m = Material.matchMaterial(n);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Highest non-air block across the region's columns (so a chunk clear starts at the actual surface). */
    private static int highestSolid(World world, ClearRegion r) {
        int max = world.getMinHeight();
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                max = Math.max(max, world.getHighestBlockYAt(x, z));
            }
        }
        return Math.min(max, world.getMaxHeight() - 1);
    }

    private void sendStatus(CommandSender sender, FakePlayer fp) {
        Job j = jobs.get(fp.getUuid());
        if (j == null) {
            sender.sendMessage(Lang.get("clear-status-idle", "name", fp.getDisplayName()));
            return;
        }
        int total = j.region.layers();
        int done = Math.max(0, j.region.maxY() - j.layer + (j.layerScanned ? 0 : 1));
        sender.sendMessage(Lang.get(
                "clear-status",
                "name",
                fp.getDisplayName(),
                "layer",
                String.valueOf(j.layer),
                "done",
                String.valueOf(Math.min(done, total)),
                "total",
                String.valueOf(total),
                "mined",
                String.valueOf(j.mined),
                "left",
                String.valueOf(j.remaining.size()),
                "skipped",
                String.valueOf(j.skipped.size())));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  The excavation loop
    // ═════════════════════════════════════════════════════════════════════════

    private void tick(FakePlayer fp, Job j) {
        UUID uuid = fp.getUuid();
        if (jobs.get(uuid) != j) return;
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            cleanupBot(uuid);
            return;
        }
        if (fp.isInventoryOpen() || fp.isActionsPaused() || fp.isFrozen() || fp.isAuthPending()) return;

        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        ServerLevel level = nms.level();
        j.tick++;
        nms.resetLastActionTime();
        collectDrops(bot);
        sampleProgress(bot, j);

        if (j.tick - j.lastProgressTick > STUCK_TICKS) {
            recover(fp, bot, nms, j);
            return;
        }

        // Another system's walk, or ours: let the pathfinder drive.
        if (j.navigating) {
            if (pathfinding.isNavigating(uuid)) return;
            j.navigating = false;
        }
        if (j.depositing) return;

        // ── inventory ──
        if (freeSlots(bot) <= LOW_FREE_SLOTS && handleFullInventory(fp, bot, j)) return;

        // ── pick the layer ──
        if (!j.layerScanned && !scanNextLayer(level, j)) {
            finish(fp, j, true);
            return;
        }
        if (j.remaining.isEmpty()) {
            j.layersDone++;
            j.layerScanned = false;
            j.lastProgressTick = j.tick;
            if (j.layersDone % Math.max(1, Config.clearProgressEveryLayers()) == 0) {
                notify(
                        j,
                        "clear-layer-done",
                        "name",
                        fp.getDisplayName(),
                        "layer",
                        String.valueOf(j.layer),
                        "mined",
                        String.valueOf(j.mined));
            }
            return;
        }

        // ── be at the right height inside the footprint ──
        int fx = bot.getLocation().getBlockX();
        int fz = bot.getLocation().getBlockZ();
        int fy = (int) Math.floor(bot.getLocation().getY() + 0.05);
        boolean positioned = j.region.containsColumn(fx, fz) && (fy == j.layer || fy == j.layer + 1);
        if (!positioned) {
            anchor(fp, bot, nms, j, false);
            return;
        }

        // ── choose and work a target ──
        work(fp, bot, nms, level, j);
    }

    /** Scans down from the current layer to the next one that still holds something to mine. */
    private boolean scanNextLayer(ServerLevel level, Job j) {
        for (int y = j.layer - 1; y >= j.region.minY(); y--) {
            Set<Long> cells = new HashSet<>();
            for (int x = j.region.minX(); x <= j.region.maxX(); x++) {
                for (int z = j.region.minZ(); z <= j.region.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    long key = p.asLong();
                    if (!j.skipped.contains(key) && mineable(level, p, j)) cells.add(key);
                }
            }
            if (!cells.isEmpty()) {
                j.layer = y;
                j.remaining = cells;
                j.layerScanned = true;
                j.targetKey = Long.MIN_VALUE;
                return true;
            }
        }
        return false;
    }

    private boolean mineable(ServerLevel level, BlockPos p, Job j) {
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.getBlock() instanceof LiquidBlock) return false;
        if (st.getDestroySpeed(level, p) < 0f) return false;
        if (st.hasBlockEntity() && !j.containers) return false;
        Material mat = CraftMagicNumbers.getMaterial(st.getBlock());
        if (j.keep.contains(mat)) return false;
        return mat != Material.SPAWNER
                && mat != Material.BEACON
                && mat != Material.END_PORTAL_FRAME
                && mat != Material.REINFORCED_DEEPSLATE;
    }

    // ── working a cell ──────────────────────────────────────────────────────

    private void work(FakePlayer fp, Player bot, ServerPlayer nms, ServerLevel level, Job j) {
        Vec3 eye = nms.getEyePosition(1.0F);
        double reach = nms.blockInteractionRange() - 0.35;

        // Nearest remaining cell (keep the current one until it is done, so digs are not abandoned).
        long best = j.targetKey;
        if (best == Long.MIN_VALUE || !j.remaining.contains(best)) {
            best = Long.MIN_VALUE;
            double bd = Double.MAX_VALUE;
            // Every cell of a layer shares one Y, so probe the neighbourhood around the bot first
            // (a few dozen set lookups) and only fall back to scanning the whole layer when nothing
            // is near - keeps big regions O(1) per pick instead of O(layer size).
            int ex = (int) Math.floor(eye.x), ez = (int) Math.floor(eye.z);
            int span = (int) Math.ceil(reach) + 2;
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    long key = BlockPos.asLong(ex + dx, j.layer, ez + dz);
                    if (!j.remaining.contains(key)) continue;
                    double d = eye.distanceToSqr(ex + dx + 0.5, j.layer + 0.5, ez + dz + 0.5);
                    if (d < bd) {
                        bd = d;
                        best = key;
                    }
                }
            }
            if (best == Long.MIN_VALUE) {
                for (long key : j.remaining) {
                    double d = eye.distanceToSqr(
                            BlockPos.getX(key) + 0.5, BlockPos.getY(key) + 0.5, BlockPos.getZ(key) + 0.5);
                    if (d < bd) {
                        bd = d;
                        best = key;
                    }
                }
            }
            j.targetKey = best;
            j.targetTicks = 0;
        }
        if (best == Long.MIN_VALUE) return;

        BlockPos pos = BlockPos.of(best);
        if (!mineable(level, pos, j)) {
            j.remaining.remove(best);
            j.targetKey = Long.MIN_VALUE;
            return;
        }

        double dist = eye.distanceTo(Vec3.atCenterOf(pos));
        if (dist > reach) {
            moveCloser(fp, bot, nms, j, pos);
            return;
        }

        // Release liquid before breaking: cover any water/lava touching the cell from where we stand.
        if (j.seal && sealNeighbours(bot, nms, level, pos, j)) return;
        if (j.sealFailed) {
            j.sealFailed = false;
            skip(j, best);
            return;
        }

        // Look at it; the crosshair decides what is really in front.
        face(bot, nms, Vec3.atCenterOf(pos));
        HitResult hit = BotClient.pick(nms);
        if (hit.getType() != HitResult.Type.BLOCK) {
            moveCloser(fp, bot, nms, j, pos); // no line of sight from here
            return;
        }
        BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
        if (!j.region.contains(hitPos.getX(), hitPos.getY(), hitPos.getZ()) || !mineable(level, hitPos, j)) {
            // Something we must not (or cannot) dig is in the way of this cell.
            if (!hitPos.equals(pos)) {
                skip(j, best);
                return;
            }
        }

        BotClient client = BotClient.of(nms);
        if (!client.isDestroying()) BotToolSelector.equipBestTool(bot, hitPos);
        client.holdAttack(nms, hit, 0);

        BlockState now = level.getBlockState(hitPos);
        if (now.isAir()) {
            if (j.remaining.remove(hitPos.asLong())) j.mined++;
            if (hitPos.asLong() == best) j.targetKey = Long.MIN_VALUE;
            j.lastProgressTick = j.tick;
            j.recoveries = 0;
            return;
        }

        // A dig that never finishes (protection cancelling it, an unbreakable surprise) is dropped.
        j.targetTicks++;
        float p = level.getBlockState(hitPos).getDestroyProgress(nms, level, hitPos);
        int expected = p <= 0f ? 600 : (int) Math.min(2400, Math.ceil(1.0 / p));
        if (j.targetTicks > expected * 2 + REACH_SLACK_TICKS) {
            client.stopDestroyBlock(nms);
            skip(j, hitPos.asLong() == best ? best : hitPos.asLong());
            if (hitPos.asLong() != best) j.targetKey = Long.MIN_VALUE;
        }
    }

    private void skip(Job j, long key) {
        j.remaining.remove(key);
        j.skipped.add(key);
        j.targetKey = Long.MIN_VALUE;
    }

    /** @return true while a seal placement is still in progress (the caller should wait a tick). */
    private boolean sealNeighbours(Player bot, ServerPlayer nms, ServerLevel level, BlockPos pos, Job j) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getFluidState(n).isEmpty() && level.getBlockState(n).getBlock() instanceof LiquidBlock) {
                if (!NavBlocks.hasPlaceable(bot)) {
                    j.sealFailed = true; // cannot cover it - leave this cell shut
                    return false;
                }
                face(bot, nms, Vec3.atCenterOf(n));
                if (NavBlocks.place(bot, n)) {
                    j.sealed++;
                    return true;
                }
                j.sealFailed = true;
                return false;
            }
        }
        return false;
    }

    // ── movement ────────────────────────────────────────────────────────────

    private void moveCloser(FakePlayer fp, Player bot, ServerPlayer nms, Job j, BlockPos target) {
        NavTerrain terrain = new NavTerrain(nms.level());
        double reach = nms.blockInteractionRange() - 0.8;
        Location here = bot.getLocation();

        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    int x = target.getX() + dx, y = target.getY() + dy, z = target.getZ() + dz;
                    if (!j.region.containsColumn(x, z)) continue;
                    if (y != j.layer && y != j.layer + 1) continue;
                    if (terrain.stance(x, y, z, false) != NavTerrain.Stance.GROUND) continue;
                    double ex = x + 0.5 - (target.getX() + 0.5);
                    double ey = y + 1.62 - (target.getY() + 0.5);
                    double ez = z + 0.5 - (target.getZ() + 0.5);
                    if (Math.sqrt(ex * ex + ey * ey + ez * ez) > reach) continue;
                    double score = Math.sqrt(Math.pow(x + 0.5 - here.getX(), 2)
                            + Math.pow(y - here.getY(), 2) * 2
                            + Math.pow(z + 0.5 - here.getZ(), 2));
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Location(bot.getWorld(), x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        if (best == null) {
            // Nowhere to stand within reach of it from cleared space: leave this one for later.
            skip(j, target.asLong());
            return;
        }
        navigate(fp, bot, j, best, false);
    }

    /** Gets the bot into the footprint at the working height, building up/digging within the region if needed. */
    private void anchor(FakePlayer fp, Player bot, ServerPlayer nms, Job j, boolean forceBuild) {
        NavTerrain terrain = new NavTerrain(nms.level());
        Location here = bot.getLocation();

        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = j.region.minX(); x <= j.region.maxX(); x++) {
            for (int z = j.region.minZ(); z <= j.region.maxZ(); z++) {
                for (int y = j.layer; y <= j.layer + 1; y++) {
                    if (terrain.stance(x, y, z, false) != NavTerrain.Stance.GROUND) continue;
                    double score = Math.abs(x + 0.5 - here.getX())
                            + Math.abs(z + 0.5 - here.getZ())
                            + Math.abs(y - here.getY()) * 2;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Location(bot.getWorld(), x + 0.5, y, z + 0.5);
                    }
                }
            }
        }

        if (best == null) {
            if (j.tunnel && !j.tunnelled) {
                // No way in from outside: dig down to the top of the region through the overburden.
                j.tunnelled = true;
                Location centre = new Location(
                        bot.getWorld(),
                        (j.region.minX() + j.region.maxX()) / 2.0 + 0.5,
                        j.layer + 1,
                        (j.region.minZ() + j.region.maxZ()) / 2.0 + 0.5);
                navigate(fp, bot, j, centre, true);
                return;
            }
            notify(j, "clear-no-entry", "name", fp.getDisplayName());
            finish(fp, j, false);
            return;
        }
        navigate(fp, bot, j, best, false);
    }

    private void navigate(FakePlayer fp, Player bot, Job j, Location goal, boolean unrestrictedDig) {
        UUID uuid = fp.getUuid();
        boolean place = j.build && NavBlocks.hasPlaceable(bot);
        final ClearRegion region = j.region;
        BotPathfinder.PathOptions opts = new BotPathfinder.PathOptions(
                false,
                true,
                place,
                true,
                true,
                unrestrictedDig
                        ? null
                        : key -> region.contains(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)));
        j.navigating = true;
        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        PathfindingService.Owner.MINE,
                        () -> goal,
                        0.5,
                        0.0,
                        8,
                        () -> {
                            j.navigating = false;
                            j.lastProgressTick = j.tick;
                        },
                        () -> j.navigating = false,
                        () -> {
                            j.navigating = false;
                            if (++j.navFailures >= 3) {
                                // The pathfinder cannot route us: climb/dig out by building up next recovery.
                                j.lastProgressTick = Long.MIN_VALUE / 2;
                                j.navFailures = 0;
                            }
                        },
                        null,
                        opts));
    }

    // ── stuck recovery ──────────────────────────────────────────────────────

    private void recover(FakePlayer fp, Player bot, ServerPlayer nms, Job j) {
        j.recoveries++;
        j.lastProgressTick = j.tick;
        BotClient.of(nms).stopDestroyBlock(nms);
        if (pathfinding.isNavigating(fp.getUuid(), PathfindingService.Owner.MINE)) pathfinding.cancel(fp.getUuid());
        j.navigating = false;
        Config.debugPathfinding(
                "event=CLEAR_RECOVER bot='" + fp.getName() + "' attempt=" + j.recoveries + " layer=" + j.layer);

        switch (j.recoveries) {
            case 1 -> {
                // Probably fixated on one cell: drop it and look again.
                if (j.targetKey != Long.MIN_VALUE) skip(j, j.targetKey);
            }
            case 2 -> {
                // Re-anchor, allowed to pillar up out of whatever pit we are in.
                notify(j, "clear-stuck", "name", fp.getDisplayName());
                anchor(fp, bot, nms, j, true);
            }
            default -> {
                notify(j, "clear-gave-up", "name", fp.getDisplayName());
                finish(fp, j, false);
            }
        }
    }

    private void sampleProgress(Player bot, Job j) {
        if (j.tick % PROGRESS_SAMPLE_TICKS != 0) return;
        Location l = bot.getLocation();
        if (j.lastSample == null || j.lastSample.distanceSquared(l.toVector()) >= 1.0) {
            j.lastSample = l.toVector();
            j.lastProgressTick = j.tick;
        }
    }

    // ── loot & inventory ────────────────────────────────────────────────────

    private void collectDrops(Player bot) {
        for (Entity e : bot.getNearbyEntities(3.0, 2.5, 3.0)) {
            if (!(e instanceof Item item) || item.isDead() || item.getPickupDelay() > 0) continue;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir()) {
                item.remove();
                continue;
            }
            Map<Integer, ItemStack> leftovers = bot.getInventory().addItem(stack.clone());
            if (leftovers.isEmpty()) item.remove();
            else item.setItemStack(leftovers.values().iterator().next());
        }
    }

    private static int freeSlots(Player bot) {
        int free = 0;
        for (ItemStack it : bot.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) free++;
        }
        return free;
    }

    private static boolean isKeeper(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL")
                || n.endsWith("_AXE")
                || n.endsWith("_SWORD")
                || n.endsWith("_HOE")
                || m.isEdible()
                || m == Material.SHEARS;
    }

    /** @return true when this tick was consumed handling a full inventory. */
    private boolean handleFullInventory(FakePlayer fp, Player bot, Job j) {
        // 1) Discard the junk the user said was junk (always keeps one stack of scaffold blocks).
        if (!j.trash.isEmpty()) {
            int keptScaffold = 0;
            int removed = 0;
            Inventory inv = bot.getInventory();
            int scaffoldSlot = NavBlocks.bestSlot(bot);
            for (int slot = 0; slot < 36; slot++) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir() || !j.trash.contains(it.getType())) continue;
                if (slot == scaffoldSlot && keptScaffold == 0) {
                    keptScaffold++;
                    continue;
                }
                removed += it.getAmount();
                inv.setItem(slot, null);
            }
            j.trashed += removed;
            if (freeSlots(bot) > LOW_FREE_SLOTS) return false;
        }

        // 2) A registered storage: walk there and deposit everything that is not a tool/food/scaffold.
        StorageStore.StoragePoint point = nearestStorage(fp, bot);
        if (point != null && !j.depositFailed) {
            Block block = point.location().getBlock();
            Location stand = block.getLocation().add(0.5, 0, 0.5);
            Location face = stand.clone();
            j.depositing = true;
            notify(j, "find-depositing", "name", fp.getDisplayName(), "storage", point.name());
            UUID uuid = fp.getUuid();
            Runnable done = () -> {
                j.depositing = false;
                j.lastProgressTick = j.tick;
            };
            pathfinding.navigate(
                    fp,
                    new PathfindingService.NavigationRequest(
                            PathfindingService.Owner.SYSTEM,
                            () -> face,
                            2.5,
                            0.0,
                            10,
                            () -> StorageInteractionHelper.interact(
                                    fp,
                                    face,
                                    block,
                                    plugin,
                                    manager,
                                    (holder, liveBot) -> deposit(liveBot.getInventory(), holder.getInventory()),
                                    done),
                            () -> {
                                done.run();
                                j.depositFailed = true;
                            },
                            () -> {
                                done.run();
                                j.depositFailed = true;
                            }));
            return true;
        }

        // 3) Nothing to do about it: stop cleanly rather than grinding on with no room.
        if (!j.fullNotified) {
            j.fullNotified = true;
            notify(j, "clear-inventory-full", "name", fp.getDisplayName());
        }
        finish(fp, j, false);
        return true;
    }

    private StorageStore.StoragePoint nearestStorage(FakePlayer fp, Player bot) {
        if (storageStore == null) return null;
        StorageStore.StoragePoint best = null;
        double bd = Double.MAX_VALUE;
        for (StorageStore.StoragePoint p : storageStore.getStorages(fp.getName())) {
            if (p.location().getWorld() != bot.getWorld()) continue;
            if (!(p.location().getBlock().getState() instanceof InventoryHolder)) continue;
            double d = p.location().distanceSquared(bot.getLocation());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private void deposit(Inventory from, Inventory to) {
        boolean keptScaffold = false;
        for (int i = 0; i < 36; i++) {
            ItemStack it = from.getItem(i);
            if (it == null || it.getType().isAir() || isKeeper(it.getType())) continue;
            if (!keptScaffold && NavBlocks.isScaffold(it.getType())) {
                keptScaffold = true; // one stack stays for pillaring / sealing
                continue;
            }
            Map<Integer, ItemStack> left = to.addItem(it.clone());
            from.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void face(Player bot, ServerPlayer nms, Vec3 target) {
        Vec3 eye = nms.getEyePosition(1.0F);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        nms.absSnapRotationTo(yaw, pitch);
        NmsPlayerSpawner.setHeadYaw(bot, yaw);
    }

    private void notify(Job j, String key, String... args) {
        if (j.starter == null) return;
        Player p = Bukkit.getPlayer(j.starter);
        if (p != null && p.isOnline()) p.sendMessage(Lang.get(key, args));
    }

    private void finish(FakePlayer fp, Job j, boolean complete) {
        notify(
                j,
                complete ? "clear-finished" : "clear-ended",
                "name",
                fp.getDisplayName(),
                "mined",
                String.valueOf(j.mined),
                "layers",
                String.valueOf(j.layersDone),
                "skipped",
                String.valueOf(j.skipped.size()),
                "sealed",
                String.valueOf(j.sealed),
                "trashed",
                String.valueOf(j.trashed));
        cleanupBot(fp.getUuid());
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public void cleanupBot(UUID botUuid) {
        Job j = jobs.remove(botUuid);
        if (j == null) return;
        if (j.taskId >= 0) FppScheduler.cancelTask(j.taskId);
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) {
            Player bot = fp.getPlayer();
            if (pathfinding.isNavigating(botUuid, PathfindingService.Owner.MINE)) pathfinding.cancel(botUuid);
            if (bot != null && bot.isOnline()) {
                ServerPlayer nms = ((CraftPlayer) bot).getHandle();
                BotClient.of(nms).stopDestroyBlock(nms);
            }
            FppApiImpl.fireTaskEvent(fp, "clear", FppBotTaskEvent.Action.STOP);
        }
    }

    public void stopAll() {
        new ArrayList<>(jobs.keySet()).forEach(this::cleanupBot);
    }

    public boolean isClearing(UUID botUuid) {
        return jobs.containsKey(botUuid);
    }

    // ── tab completion ──────────────────────────────────────────────────────

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!canUse(sender)) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("--stop".startsWith(prefix)) out.add("--stop");
            for (FakePlayer fp : manager.getActivePlayers()) {
                if (fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
            }
            return out;
        }
        String prev = args[args.length - 2].toLowerCase(Locale.ROOT);
        if (prev.equals("--keep") || prev.equals("--trash")) {
            for (String a : MaterialSelector.ALIASES) if (a.startsWith(prefix)) out.add(a);
            for (String m : List.of("cobblestone", "dirt", "gravel", "stone", "deepslate", "netherrack", "sand")) {
                if (m.startsWith(prefix)) out.add(m);
            }
            return out;
        }
        if (prev.equals("--depth") || prev.equals("--radius")) return List.of("8", "16", "32");
        for (String f : List.of(
                "--chunk",
                "--area",
                "--selection",
                "--radius",
                "--top",
                "--bottom",
                "--depth",
                "--keep",
                "--trash",
                "--containers",
                "--tunnel",
                "--no-build",
                "--no-seal",
                "--status",
                "--stop")) {
            if (f.startsWith(prefix)) out.add(f);
        }
        return out;
    }

    // ── job state ───────────────────────────────────────────────────────────

    private static final class Job {
        final UUID botUuid;
        final World world;
        final ClearRegion region;
        final Set<Material> keep;
        final Set<Material> trash;
        final boolean containers;
        final boolean tunnel;
        final boolean build;
        final boolean seal;
        final UUID starter;

        int taskId = -1;
        long tick;
        long lastProgressTick;
        int recoveries;
        int navFailures;
        org.bukkit.util.Vector lastSample;

        int layer;
        boolean layerScanned;
        Set<Long> remaining = new HashSet<>();
        final Set<Long> skipped = new HashSet<>();
        long targetKey = Long.MIN_VALUE;
        int targetTicks;

        boolean navigating;
        boolean depositing;
        boolean depositFailed;
        boolean fullNotified;
        boolean tunnelled;
        boolean sealFailed;

        long mined;
        long sealed;
        long trashed;
        int layersDone;

        Job(
                UUID botUuid,
                World world,
                ClearRegion region,
                Set<Material> keep,
                Set<Material> trash,
                boolean containers,
                boolean tunnel,
                boolean build,
                boolean seal,
                UUID starter) {
            this.botUuid = botUuid;
            this.world = world;
            this.region = region;
            this.keep = keep;
            this.trash = trash;
            this.containers = containers;
            this.tunnel = tunnel;
            this.build = build;
            this.seal = seal;
            this.starter = starter;
            this.layer = region.maxY() + 1;
        }
    }
}
