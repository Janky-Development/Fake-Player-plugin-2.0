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
import org.bukkit.ChunkSnapshot;
import org.bukkit.FluidCollisionMode;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppBotBlockBreakEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.BotNavUtil;
import me.bill.fakePlayerPlugin.fakeplayer.BotToolSelector;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.fakeplayer.StorageInteractionHelper;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavController;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain;
import me.bill.fakePlayerPlugin.mining.MaterialSelector;
import me.bill.fakePlayerPlugin.mining.OreScanner;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

/**
 * /fpp find <bot> <block> [--radius <n>] [--count <n>] [--prefer-visible]
 *
 */
public final class FindCommand implements FppCommand {

    /**
     * Default search radius if --radius is not specified.
     */
    private static final int DEFAULT_RADIUS = 32;

    /**
     * Hard cap on search radius to prevent freezing the server.
     */
    private static final int MAX_RADIUS = 128;

    /**
     * Ticks to wait after a block is broken before trying to mine the next one.
     */
    private static final int POST_MINE_PAUSE_TICKS = 10;

    /**
     * How long a find-job navigation attempt can run with no arrival/cancel/failure before we give up
     * on that block ourselves and move to the next one (anti-stuck: the shared pathfinder will keep
     * recalculating against a genuinely unreachable block forever otherwise).
     */
    private static final long NAV_WATCHDOG_TICKS = 45L * 20L;

    /**
     * Ticks of zero mining-progress before a block is abandoned as stuck (wrong/no tool, block
     * regenerating, etc.) instead of being ground on forever.
     */
    private static final int MINE_STALL_TICKS = 100;

    /** Auto-deposit trip triggers once free main-inventory slots drop to/below this. */
    private static final int LOW_INVENTORY_FREE_SLOTS = 2;

    /** How long a scan's leftover candidates stay trusted before a fresh scan is forced. */
    private static final long CACHE_TTL_MS = 15_000L;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final PathfindingService pathfinding;
    private final StorageStore storageStore;

    private final Map<UUID, FindJob> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> miningTasks = new ConcurrentHashMap<>();
    private final Map<String, UUID> reservedBlocks = new ConcurrentHashMap<>();

    private final Map<UUID, SimpleMiningState> miningStates = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> navWatchdogTasks = new ConcurrentHashMap<>();

    public FindCommand(
            FakePlayerPlugin plugin,
            FakePlayerManager manager,
            PathfindingService pathfinding,
            StorageStore storageStore) {
        this.plugin = plugin;
        this.manager = manager;
        this.pathfinding = pathfinding;
        this.storageStore = storageStore;
    }

    @Override
    public String getName() {
        return "find";
    }

    @Override
    public String getUsage() {
        return "<bot> <block[,block…]|ores|logs|…> [--radius|-r <n>] [--count|-c <n>] [--prefer-visible]"
                + " [--hazards] [--scan]  |  <bot> --stop  |  --stop";
    }

    @Override
    public String getDescription() {
        return "Find and mine nearby blocks the bot could actually see (exposed faces only - no X-ray),"
                + " follow veins as they open up, auto-equip the right tool and auto-deposit at registered"
                + " storage when full. --scan only reports what is visible.";
    }

    @Override
    public String getPermission() {
        return Perm.FIND;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.FIND);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.get("find-usage"));
            return true;
        }

        // /fpp find --stop  (stop all bots)
        if (isStop(args[0]) && args.length == 1) {
            stopAll();
            sender.sendMessage(Lang.get("find-stopped-all"));
            return true;
        }

        String botName = args[0];
        FakePlayer fp = manager.getByName(botName);
        if (fp == null) {
            sender.sendMessage(Lang.get("find-not-found", "name", botName));
            return true;
        }

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            sender.sendMessage(Lang.get("find-bot-offline", "name", fp.getDisplayName()));
            return true;
        }

        // /fpp find <bot> --stop
        if (args.length >= 2 && isStop(args[1])) {
            cleanupBot(fp.getUuid());
            sender.sendMessage(Lang.get("find-stopped", "name", fp.getDisplayName()));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Lang.get("find-usage"));
            return true;
        }

        // Parse the block selection: a name, a comma list, or a group alias (ores, logs, …).
        Set<Material> materials = MaterialSelector.parse(args[1]);
        if (materials == null) {
            String bad = MaterialSelector.firstUnknown(args[1]);
            sender.sendMessage(Lang.get("find-invalid-block", "block", bad != null ? bad : args[1]));
            return true;
        }
        String label = args[1].toLowerCase(Locale.ROOT).replace('_', ' ');

        int radius = DEFAULT_RADIUS;
        int count = -1; // -1 = unlimited
        boolean preferVisible = false;
        boolean allowHazards = false;
        boolean scanOnly = false;

        for (int i = 2; i < args.length; i++) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            switch (flag) {
                case "--radius", "-r" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage(Lang.get("find-usage"));
                        return true;
                    }
                    try {
                        radius = Math.min(MAX_RADIUS, Math.max(1, Integer.parseInt(args[++i])));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Lang.get("find-invalid-radius", "value", args[i]));
                        return true;
                    }
                }
                case "--count", "-c" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage(Lang.get("find-usage"));
                        return true;
                    }
                    try {
                        count = Math.max(1, Integer.parseInt(args[++i]));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Lang.get("find-invalid-count", "value", args[i]));
                        return true;
                    }
                }
                case "--prefer-visible", "--prefervisible", "--visible" -> preferVisible = true;
                case "--hazards", "--allow-hazards" -> allowHazards = true;
                case "--scan" -> scanOnly = true;
                default -> {
                    // ignore unknown flags gracefully
                }
            }
        }

        // Stop any existing find job for this bot before starting a new one.
        cleanupBot(fp.getUuid());

        UUID starterUuid = sender instanceof Player p ? p.getUniqueId() : null;
        FindJob job = new FindJob(
                materials, label, radius, count, preferVisible, starterUuid, sender instanceof Player);
        job.allowHazards = allowHazards;

        if (scanOnly) {
            runScan(sender, fp, job);
            return true;
        }
        jobs.put(fp.getUuid(), job);

        String countDisplay = count < 0 ? "∞" : String.valueOf(count);
        sender.sendMessage(Lang.get(
                "find-started",
                "name",
                fp.getDisplayName(),
                "block",
                label,
                "radius",
                String.valueOf(radius),
                "count",
                countDisplay));

        // Begin first search cycle
        findAndMineNext(fp, job);
        return true;
    }



    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
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

        if (args.length == 2) {
            String prefix = args[1].toUpperCase(Locale.ROOT);
            if (isStop(args[0])) return List.of();
            // Suggest --stop or block names
            List<String> out = new ArrayList<>();
            if ("--STOP".startsWith(prefix)) out.add("--stop");
            for (Material m : Material.values()) {
                if (!m.isAir() && m.isBlock() && m.name().startsWith(prefix)) {
                    out.add(m.name().toLowerCase(Locale.ROOT));
                    if (out.size() >= 50) break; // cap for UX
                }
            }
            return out;
        }

        if (args.length >= 3) {
            String prev = args[args.length - 2].toLowerCase(Locale.ROOT);
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (prev.equals("--radius") || prev.equals("-r")) {
                return List.of("16", "32", "64");
            }
            if (prev.equals("--count") || prev.equals("-c")) {
                return List.of("1", "5", "10", "32", "64");
            }
            List<String> flags = new ArrayList<>();
            Set<String> usedFlags = new HashSet<>();
            for (int i = 2; i < args.length - 1; i++) {
                usedFlags.add(args[i].toLowerCase(Locale.ROOT));
            }
            if (!usedFlags.contains("--radius") && "--radius".startsWith(prefix)) flags.add("--radius");
            if (!usedFlags.contains("--count") && "--count".startsWith(prefix)) flags.add("--count");
            if (!usedFlags.contains("--prefer-visible") && "--prefer-visible".startsWith(prefix))
                flags.add("--prefer-visible");
            return flags;
        }

        return List.of();
    }

    private void findAndMineNext(FakePlayer fp, FindJob job) {
        if (!jobs.containsKey(fp.getUuid())) return; // stopped

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            cleanupBot(fp.getUuid());
            return;
        }

        // Inventory-aware: detour to the bot's nearest registered storage when running low on space,
        // then resume the search from wherever the bot ends up. Skipped once right after a deposit
        // trip finishes so an unreachable/full storage can't cause a tight retry loop; falls back to
        // the existing passive drop-collection behavior if no storage is registered.
        if (!job.skipInventoryCheckOnce && isInventoryLow(bot)) {
            job.skipInventoryCheckOnce = true;
            if (tryStartDepositTrip(fp, job)) return;
        } else {
            job.skipInventoryCheckOnce = false;
        }

        Location origin = bot.getLocation().clone();
        World world = origin.getWorld();
        if (world == null) {
            cleanupBot(fp.getUuid());
            return;
        }

        // Cheap path: re-use the previous scan's ranked leftovers (plus freshly exposed vein neighbours)
        // instead of snapshotting every chunk in range after every single block. Each candidate is
        // re-validated against the live world when picked, so a stale entry just gets dropped.
        if (!job.cache.isEmpty() && System.currentTimeMillis() - job.lastScanMs < CACHE_TTL_MS) {
            List<OreScanner.Hit> hits = rescore(origin, job.cache);
            job.cache.clear();
            handleFindResult(fp, job, hits);
            return;
        }

        List<ChunkSnapshot> snapshots = snapshotChunks(world, origin, job.radius);
        final Set<Long> alreadyMined = new HashSet<>(job.mined);
        final UUID worldId = world.getUID();
        job.lastScanMs = System.currentTimeMillis();
        FppScheduler.runAsync(plugin, () -> {
            List<OreScanner.Hit> hits = scanExposed(origin, snapshots, job, alreadyMined, worldId, fp.getUuid(), 48);
            FppScheduler.runAtEntity(plugin, bot, () -> handleFindResult(fp, job, hits));
        });
    }

    /** Re-ranks cached hits by their walking effort from where the bot is now. */
    private static List<OreScanner.Hit> rescore(Location origin, List<OreScanner.Hit> hits) {
        List<OreScanner.Hit> out = new ArrayList<>(hits.size());
        for (OreScanner.Hit h : hits) {
            double dx = h.x() + 0.5 - origin.getX();
            double dz = h.z() + 0.5 - origin.getZ();
            double dy = h.y() - origin.getY();
            double score = Math.sqrt(dx * dx + dz * dz) + OreScanner.VERTICAL_WEIGHT * Math.abs(dy)
                    + (h.hazard() ? 25 : 0);
            out.add(new OreScanner.Hit(h.x(), h.y(), h.z(), score, h.hazard()));
        }
        return out;
    }

    /**
     * A block was just mined: its six neighbours now have an open face. Matching ones become candidates
     * straight away (the vein keeps opening up) with no rescan.
     */
    private void cacheExposedNeighbours(FindJob job, World world, int x, int y, int z) {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            int nx = x + f[0], ny = y + f[1], nz = z + f[2];
            if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue;
            if (!world.isChunkLoaded(nx >> 4, nz >> 4)) continue;
            if (job.mined.contains(packBlockKey(nx, ny, nz))) continue;
            if (!job.materials.contains(world.getType(nx, ny, nz))) continue;
            boolean hazard = false;
            for (int[] g : faces) {
                if (world.getType(nx + g[0], ny + g[1], nz + g[2]) == Material.LAVA) {
                    hazard = true;
                    break;
                }
            }
            if (hazard && !job.allowHazards) continue;
            boolean dup = false;
            for (OreScanner.Hit h : job.cache) {
                if (h.x() == nx && h.y() == ny && h.z() == nz) {
                    dup = true;
                    break;
                }
            }
            if (!dup) job.cache.add(new OreScanner.Hit(nx, ny, nz, 0.0, hazard));
        }
    }

    /**
     * Off-thread detection over chunk snapshots: only blocks with an exposed face are reported (see
     * {@link OreScanner}). Reserved-by-another-bot and already-mined blocks are excluded.
     */
    private List<OreScanner.Hit> scanExposed(
            Location origin,
            List<ChunkSnapshot> snapshots,
            FindJob job,
            Set<Long> alreadyMined,
            UUID worldId,
            UUID botUuid,
            int limit) {
        World world = origin.getWorld();
        if (world == null) return List.of();
        Map<Long, ChunkSnapshot> byChunk = new java.util.HashMap<>();
        for (ChunkSnapshot s : snapshots) byChunk.put(chunkKey(s.getX(), s.getZ()), s);
        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight() - 1;

        OreScanner.BlockView view = (x, y, z) -> {
            if (y < minY || y > maxY) return null;
            ChunkSnapshot s = byChunk.get(chunkKey(x >> 4, z >> 4));
            return s == null ? null : s.getBlockType(x & 15, y, z & 15);
        };
        return OreScanner.scan(
                view,
                origin.getBlockX(),
                origin.getBlockY(),
                origin.getBlockZ(),
                job.radius,
                minY,
                maxY,
                job.materials,
                t -> {
                    Material m = (Material) t;
                    return m.isAir() || !m.isOccluding();
                },
                t -> t == Material.LAVA,
                key -> alreadyMined.contains(key) || isReservedByOther(worldId, key, botUuid),
                job.allowHazards,
                limit);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private boolean isReservedByOther(UUID worldId, long key, UUID botUuid) {
        UUID holder = reservedBlocks.get(worldId + ":" + key);
        return holder != null && !holder.equals(botUuid);
    }

    /** {@code --scan}: report what is visible without moving or mining. */
    private void runScan(CommandSender sender, FakePlayer fp, FindJob job) {
        Player bot = fp.getPlayer();
        if (bot == null) return;
        Location origin = bot.getLocation().clone();
        World world = origin.getWorld();
        if (world == null) return;
        List<ChunkSnapshot> snapshots = snapshotChunks(world, origin, job.radius);
        FppScheduler.runAsync(plugin, () -> {
            List<OreScanner.Hit> hits =
                    scanExposed(origin, snapshots, job, Set.of(), world.getUID(), fp.getUuid(), 100_000);
            FppScheduler.runAtEntity(plugin, bot, () -> {
                if (hits.isEmpty()) {
                    sender.sendMessage(Lang.get(
                            "find-scan-none", "name", fp.getDisplayName(), "block", job.label,
                            "radius", String.valueOf(job.radius)));
                    return;
                }
                OreScanner.Hit nearest = hits.get(0);
                long hazards = hits.stream().filter(OreScanner.Hit::hazard).count();
                sender.sendMessage(Lang.get(
                        "find-scan-result",
                        "name", fp.getDisplayName(),
                        "count", String.valueOf(hits.size()),
                        "block", job.label,
                        "radius", String.valueOf(job.radius),
                        "x", String.valueOf(nearest.x()),
                        "y", String.valueOf(nearest.y()),
                        "z", String.valueOf(nearest.z()),
                        "hazards", String.valueOf(hazards)));
            });
        });
    }

    private void handleFindResult(FakePlayer fp, FindJob job, List<OreScanner.Hit> hits) {
        if (!jobs.containsKey(fp.getUuid())) return;

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            cleanupBot(fp.getUuid());
            return;
        }

        if (hits.isEmpty()) {
            // Nothing visible left (buried blocks are deliberately not detected).
            cleanupBot(fp.getUuid());
            notifySender(
                    job,
                    job.minedCount > 0 ? "find-finished" : "find-none-found",
                    "name",
                    fp.getDisplayName(),
                    "block",
                    job.label,
                    "count",
                    String.valueOf(job.minedCount));
            return;
        }

        World world = bot.getWorld();

        // Re-rank on the main thread: keep following the vein we are in, prefer what is in plain sight.
        List<OreScanner.Hit> ranked = new ArrayList<>(hits);
        ranked.sort(java.util.Comparator.comparingDouble(h -> adjustedScore(bot, job, h)));

        for (int hi = 0; hi < ranked.size(); hi++) {
            OreScanner.Hit hit = ranked.get(hi);
            long key = packBlockKey(hit.x(), hit.y(), hit.z());
            Block target = world.getBlockAt(hit.x(), hit.y(), hit.z());
            if (!job.materials.contains(target.getType()) || !isMineable(target)) {
                job.mined.add(key);
                continue;
            }
            Location standLoc = findStand(bot, target);
            if (standLoc == null) {
                job.mined.add(key); // no reachable footing for it - leave it be
                continue;
            }
            String reservation = reservationKey(world, key);
            UUID existing = reservedBlocks.putIfAbsent(reservation, fp.getUuid());
            if (existing != null && !existing.equals(fp.getUuid())) continue;

            job.mined.add(key); // mark before nav so it won't be re-targeted during travel
            job.cache.clear();
            for (int k = hi + 1; k < ranked.size(); k++) {
                OreScanner.Hit rest = ranked.get(k);
                if (!job.mined.contains(packBlockKey(rest.x(), rest.y(), rest.z()))) job.cache.add(rest);
            }
            beginApproach(fp, job, target, key, standLoc);
            return;
        }

        // Every candidate was unusable (all now marked) - rescan; the set is finite so this converges.
        findAndMineNext(fp, job);
    }

    /** Lower is better: scan score, minus a bonus for continuing the vein and for line of sight. */
    private double adjustedScore(Player bot, FindJob job, OreScanner.Hit h) {
        double score = h.score();
        if (job.hasLast) {
            int dx = h.x() - job.lastX, dy = h.y() - job.lastY, dz = h.z() - job.lastZ;
            if (dx * dx + dy * dy + dz * dz <= 9) score -= 6.0; // same vein: keep digging it
        }
        if (job.preferVisible && isBlockVisible(bot, bot.getWorld().getBlockAt(h.x(), h.y(), h.z()))) {
            score -= 8.0;
        }
        return score;
    }

    /**
     * A footing cell for mining {@code target}: standable per the pathfinder's terrain model, within the
     * bot's real block-interaction range of the block, nearest to the bot. Searches a small cube so a
     * block in a wall, floor or ceiling gets a sensible approach instead of just the 4 side cells.
     */
    private @Nullable Location findStand(Player bot, Block target) {
        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        NavTerrain terrain = new NavTerrain(nms.level());
        double reach = Math.max(2.0, nms.blockInteractionRange() - 0.5);
        Location botLoc = bot.getLocation();

        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    int x = target.getX() + dx, y = target.getY() + dy, z = target.getZ() + dz;
                    if (x == target.getX() && z == target.getZ() && y == target.getY()) continue;
                    if (terrain.stance(x, y, z, false) != NavTerrain.Stance.GROUND) continue;
                    double ex = x + 0.5 - (target.getX() + 0.5);
                    double ey = y + 1.62 - (target.getY() + 0.5);
                    double ez = z + 0.5 - (target.getZ() + 0.5);
                    double toBlock = Math.sqrt(ex * ex + ey * ey + ez * ez);
                    if (toBlock > reach) continue;
                    double toBot = Math.sqrt(
                            Math.pow(x + 0.5 - botLoc.getX(), 2)
                                    + Math.pow(y - botLoc.getY(), 2) * 1.5
                                    + Math.pow(z + 0.5 - botLoc.getZ(), 2));
                    double score = toBot + toBlock * 0.3;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Location(bot.getWorld(), x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        if (best != null) {
            return BotNavUtil.faceToward(best, target.getLocation().add(0.5, 0.5, 0.5));
        }
        Location legacy =
                BotNavUtil.findStandLocation(bot.getWorld(), null, target.getX(), target.getY(), target.getZ());
        return legacy == null ? null : BotNavUtil.faceToward(legacy, target.getLocation().add(0.5, 0.5, 0.5));
    }

    /** Walks to {@code faceLoc} (or starts mining at once when already there) for the chosen block. */
    private void beginApproach(FakePlayer fp, FindJob job, Block target, long key, Location faceLoc) {
        Player bot = fp.getPlayer();
        if (bot == null) return;
        double xzDist = PathfindingService.xzDist(bot.getLocation(), faceLoc);
        if (xzDist <= Config.pathfindingArrivalDistance()
                && Math.abs(bot.getLocation().getY() - faceLoc.getY()) < 1.25) {
            lockAndMineTarget(fp, job, target, faceLoc);
            return;
        }
        UUID uuid = fp.getUuid();
        startNavWatchdog(fp, target, () -> {
            // Stuck too long chasing this block - give up on it and try the next one.
            if (pathfinding.isNavigating(uuid, PathfindingService.Owner.FIND)) {
                pathfinding.cancel(uuid);
            }
            releaseReservation(key, uuid);
            findAndMineNext(fp, job);
        });
        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        PathfindingService.Owner.FIND,
                        () -> faceLoc,
                        Config.pathfindingArrivalDistance(),
                        0.0,
                        Integer.MAX_VALUE,
                        () -> {
                            cancelNavWatchdog(uuid);
                            lockAndMineTarget(fp, job, target, faceLoc);
                        },
                        () -> {
                            // Navigation cancelled externally - stop job
                            cancelNavWatchdog(uuid);
                            cleanupBot(uuid);
                        },
                        () -> {
                            // Path failed - skip this block and try next
                            cancelNavWatchdog(uuid);
                            releaseReservation(key, uuid);
                            findAndMineNext(fp, job);
                        },
                        faceLoc));
    }

    /**
     * Lock the bot at {@code lockLoc} facing the target and start the single-block mining ticker.
     */
    private void lockAndMineTarget(FakePlayer fp, FindJob job, Block target, Location lockLoc) {
        if (!jobs.containsKey(fp.getUuid())) return;

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            cleanupBot(fp.getUuid());
            return;
        }

        // Re-verify the block still exists (someone else may have mined it)
        if (!job.materials.contains(target.getType())) {
            releaseReservation(blockKey(target), fp.getUuid());
            findAndMineNext(fp, job);
            return;
        }

        if (!isAtLockLocation(bot, lockLoc)) {
            UUID uuid = fp.getUuid();
            startNavWatchdog(fp, target, () -> {
                if (pathfinding.isNavigating(uuid, PathfindingService.Owner.FIND)) {
                    pathfinding.cancel(uuid);
                }
                releaseReservation(blockKey(target), uuid);
                findAndMineNext(fp, job);
            });
            pathfinding.navigate(
                    fp,
                    new PathfindingService.NavigationRequest(
                            PathfindingService.Owner.FIND,
                            () -> lockLoc,
                            Config.pathfindingArrivalDistance(),
                            0.0,
                            Integer.MAX_VALUE,
                            () -> {
                                cancelNavWatchdog(uuid);
                                lockAndMineTarget(fp, job, target, lockLoc);
                            },
                            () -> {
                                cancelNavWatchdog(uuid);
                                cleanupBot(uuid);
                            },
                            () -> {
                                cancelNavWatchdog(uuid);
                                releaseReservation(blockKey(target), uuid);
                                findAndMineNext(fp, job);
                            },
                            lockLoc));
            return;
        }

        BlockPos desiredPos = new BlockPos(target.getX(), target.getY(), target.getZ());
        BlockPos minePos = resolveMineTarget(bot, desiredPos, job.materials);
        if (minePos == null) {
            releaseReservation(blockKey(target), fp.getUuid());
            findAndMineNext(fp, job);
            return;
        }

        NmsPlayerSpawner.setMovementForward(bot, 0f);
        bot.setSprinting(false);
        manager.lockForAction(fp.getUuid(), lockLoc);

        SimpleMiningState state = new SimpleMiningState(minePos, desiredPos, lockLoc, minePos.equals(desiredPos));
        miningStates.put(fp.getUuid(), state);

        int taskId = FppScheduler.runAtEntityRepeatingWithId(
                plugin,
                bot,
                () -> {
                    Player b = fp.getPlayer();
                    if (b == null || !b.isOnline()) {
                        stopCurrentMine(fp.getUuid());
                        cleanupBot(fp.getUuid());
                        return;
                    }
                    tickMine(fp, job, state);
                },
                0L,
                1L);

        miningTasks.put(fp.getUuid(), taskId);
    }

    /**
     * Per-tick mining logic for a single target block. Mirrors MineCommand.tickMining logic.
     */
    private void tickMine(FakePlayer fp, FindJob job, SimpleMiningState state) {
        if (!jobs.containsKey(fp.getUuid())) {
            stopCurrentMine(fp.getUuid());
            return;
        }

        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            stopCurrentMine(fp.getUuid());
            cleanupBot(fp.getUuid());
            return;
        }

        if (fp.isInventoryOpen() || fp.isActionsPaused()) {
            return;
        }

        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        collectNearbyDrops(bot);

        if (state.freeze > 0) {
            state.freeze--;
            return;
        }

        // Post-mine pause before moving on
        if (state.done) {
            if (state.postMinePause > 0) {
                collectNearbyDrops(bot);
                state.postMinePause--;
                return;
            }
            stopCurrentMine(fp.getUuid());

            if (state.countsTowardGoal) {
                job.minedCount++;
                job.hasLast = true;
                job.lastX = state.desiredPos.getX();
                job.lastY = state.desiredPos.getY();
                job.lastZ = state.desiredPos.getZ();
                cacheExposedNeighbours(job, bot.getWorld(), job.lastX, job.lastY, job.lastZ);
                releaseReservation(
                        packBlockKey(state.desiredPos.getX(), state.desiredPos.getY(), state.desiredPos.getZ()),
                        fp.getUuid());

                if (job.count > 0 && job.minedCount >= job.count) {
                    cleanupBot(fp.getUuid());
                    notifySender(
                            job,
                            "find-finished",
                            "name",
                            fp.getDisplayName(),
                            "block",
                            job.label,
                            "count",
                            String.valueOf(job.minedCount));
                    return;
                }

                FppScheduler.runAtEntityLaterWithId(plugin, bot, () -> findAndMineNext(fp, job), 2L);
                return;
            }

            Block desiredBlock = bot.getWorld()
                    .getBlockAt(state.desiredPos.getX(), state.desiredPos.getY(), state.desiredPos.getZ());
            if (!job.materials.contains(desiredBlock.getType())) {
                releaseReservation(
                        packBlockKey(state.desiredPos.getX(), state.desiredPos.getY(), state.desiredPos.getZ()),
                        fp.getUuid());
                FppScheduler.runAtEntityLaterWithId(plugin, bot, () -> findAndMineNext(fp, job), 2L);
                return;
            }

            FppScheduler.runAtEntityLaterWithId(
                    plugin, bot, () -> lockAndMineTarget(fp, job, desiredBlock, state.lockLoc), 2L);
            return;
        }

        BlockPos targetPos = state.targetPos;
        BlockState blockState = nms.level().getBlockState(targetPos);
        faceTarget(bot, targetPos);

        // Block already gone
        if (blockState.isAir()) {
            state.done = true;
            state.postMinePause = POST_MINE_PAUSE_TICKS;
            return;
        }

        Material currentMaterial = CraftMagicNumbers.getMaterial(blockState.getBlock());
        if (state.countsTowardGoal) {
            if (!job.materials.contains(currentMaterial)) {
                state.done = true;
                state.postMinePause = 0;
                return;
            }
        } else {
            Block currentBlock = bot.getWorld().getBlockAt(targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (!isMineable(currentBlock)) {
                state.done = true;
                state.postMinePause = 0;
                return;
            }
        }

        // Anti-stuck: no destroy progress for too long (wrong/missing tool, block resisting breakage,
        // etc.) - give up on this block instead of grinding on it forever. It's already in job.mined
        // so it won't be re-targeted.
        if (state.ticksSinceProgress >= MINE_STALL_TICKS) {
            ItemStack held = bot.getInventory().getItemInMainHand();
            Config.debugPathfinding("event=MINE_STALL bot='" + fp.getName() + "'"
                    + " block=" + currentMaterial
                    + "@(" + targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ() + ")"
                    + " heldItem=" + held.getType()
                    + " lastProgress=" + state.lastObservedProgress
                    + " ticksSinceProgress=" + state.ticksSinceProgress + "/" + MINE_STALL_TICKS);
            NavController.sendDebugChat(fp.getUuid(), fp.getName(), "pathdebug-mine-stall");
            state.done = true;
            state.postMinePause = 0;
            return;
        }

        if (nms.blockActionRestricted(nms.level(), targetPos, nms.gameMode.getGameModeForPlayer())) {
            return;
        }

        // The dig itself is the vanilla client's: look at the block, hold the attack key. Tool choice,
        // break-progress prediction, the START/STOP packets and (in creative) the instant break all come
        // from BotClient - nothing here sets blocks or skips the server's own checks.
        BotClient client = BotClient.of(nms);
        if (!client.isDestroying()) BotToolSelector.equipBestTool(bot, targetPos);

        if (!state.hookFired) {
            if (!fireBlockBreakHook(fp, targetPos)) { // an API listener vetoed this block
                state.done = true;
                state.postMinePause = 0;
                return;
            }
            state.hookFired = true;
        }

        HitResult hit = BotClient.pick(nms);
        client.holdAttack(nms, hit, POST_MINE_PAUSE_TICKS);

        float progress = client.destroyProgress();
        if (progress > state.lastObservedProgress + 1e-4f) {
            state.lastObservedProgress = progress;
            state.ticksSinceProgress = 0;
        } else {
            state.ticksSinceProgress++;
        }
        if (nms.level().getBlockState(targetPos).isAir()) {
            state.done = true;
            state.postMinePause = POST_MINE_PAUSE_TICKS;
        }
        nms.resetLastActionTime();
    }

    private boolean isAtLockLocation(Player bot, Location lockLoc) {
        if (bot == null || lockLoc == null) return false;
        if (bot.getWorld() != lockLoc.getWorld()) return false;
        double xz = PathfindingService.xzDist(bot.getLocation(), lockLoc);
        double dy = Math.abs(bot.getLocation().getY() - lockLoc.getY());
        return xz <= Config.pathfindingArrivalDistance() && dy < 1.25;
    }

    private boolean fireBlockBreakHook(FakePlayer fp, BlockPos pos) {
        if (fp == null || pos == null) return false;
        Player bot = fp.getPlayer();
        if (bot == null || bot.getWorld() == null) return false;
        var event = new FppBotBlockBreakEvent(
                new FppBotImpl(fp), bot.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private BlockPos resolveMineTarget(Player bot, BlockPos desiredPos, Set<Material> desiredMaterials) {
        Block desired = bot.getWorld().getBlockAt(desiredPos.getX(), desiredPos.getY(), desiredPos.getZ());
        if (!desiredMaterials.contains(desired.getType())) return null;

        Location eye = bot.getEyeLocation();
        Location targetCenter = desired.getLocation().add(0.5, 0.5, 0.5);
        Vector dir = targetCenter.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist <= 0.001) return desiredPos;

        RayTraceResult hit = bot.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return desiredPos;

        Block first = hit.getHitBlock();
        BlockPos firstPos = new BlockPos(first.getX(), first.getY(), first.getZ());
        if (firstPos.equals(desiredPos)) return desiredPos;
        if (!isMineable(first)) return null;
        return firstPos;
    }

    private void faceTarget(Player bot, BlockPos targetPos) {
        Location eye = bot.getEyeLocation();
        Location target =
                new Location(bot.getWorld(), targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        Location faced = BotNavUtil.faceToward(eye, target);
        bot.setRotation(faced.getYaw(), faced.getPitch());
        NmsPlayerSpawner.setHeadYaw(bot, faced.getYaw());
        manager.updateActionLockRotation(bot.getUniqueId(), faced.getYaw(), faced.getPitch());
    }

    private void collectNearbyDrops(Player bot) {
        for (Entity e : bot.getNearbyEntities(2.25, 1.75, 2.25)) {
            if (!(e instanceof Item item) || item.isDead() || item.getPickupDelay() > 0) continue;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType().isAir()) {
                item.remove();
                continue;
            }
            Map<Integer, ItemStack> leftovers = bot.getInventory().addItem(stack.clone());
            if (leftovers.isEmpty()) {
                item.remove();
            } else {
                ItemStack remaining = leftovers.values().iterator().next();
                item.setItemStack(remaining);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Inventory-aware auto-deposit (mirrors StorageCommand's depositInventory/chooseStorage flow)
    // ─────────────────────────────────────────────────────────────────────────────

    private boolean isInventoryLow(Player bot) {
        int free = 0;
        for (ItemStack item : bot.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) free++;
        }
        return free <= LOW_INVENTORY_FREE_SLOTS;
    }

    /** @return true if a deposit trip was started (caller should stop and let it resume the job later). */
    private boolean tryStartDepositTrip(FakePlayer fp, FindJob job) {
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline() || storageStore == null) return false;

        StorageStore.StoragePoint point = chooseNearestStorage(fp, bot);
        if (point == null) return false;

        Block block = point.location().getBlock();
        Location faceLoc = faceLocationForStorage(bot, block);

        notifySender(job, "find-depositing", "name", fp.getDisplayName(), "storage", point.name());

        pathfinding.navigate(
                fp,
                new PathfindingService.NavigationRequest(
                        PathfindingService.Owner.SYSTEM,
                        () -> faceLoc,
                        1.25,
                        0.0,
                        10,
                        () -> StorageInteractionHelper.interact(
                                fp,
                                faceLoc,
                                block,
                                plugin,
                                manager,
                                (holder, liveBot) ->
                                        moveInventoryToStorage(liveBot.getInventory(), holder.getInventory()),
                                () -> findAndMineNext(fp, job)),
                        () -> findAndMineNext(fp, job),
                        () -> findAndMineNext(fp, job)));
        return true;
    }

    private StorageStore.StoragePoint chooseNearestStorage(FakePlayer fp, Player bot) {
        List<StorageStore.StoragePoint> points = storageStore.getStorages(fp.getName());
        StorageStore.StoragePoint best = null;
        double bestDist = Double.MAX_VALUE;
        for (StorageStore.StoragePoint point : points) {
            if (point.location().getWorld() != bot.getWorld()) continue;
            if (!(point.location().getBlock().getState() instanceof InventoryHolder)) continue;
            double dist = point.location().distanceSquared(bot.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = point;
            }
        }
        return best;
    }

    private Location faceLocationForStorage(Player bot, Block block) {
        Location loc = block.getLocation().add(0.5, 0, 0.5);
        Location stand = BotNavUtil.findStandLocation(block.getWorld(), null, block.getX(), block.getY(), block.getZ());
        if (stand == null) stand = bot.getLocation();
        Location face = stand.clone();
        face.setYaw(BotNavUtil.faceToward(face, loc).getYaw());
        face.setPitch(BotNavUtil.faceToward(face, loc).getPitch());
        return face;
    }

    private void moveInventoryToStorage(Inventory from, Inventory to) {
        for (int i = 0; i < from.getSize(); i++) {
            ItemStack item = from.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> leftover = to.addItem(item.clone());
            if (leftover.isEmpty()) {
                from.setItem(i, null);
            } else {
                from.setItem(i, leftover.values().iterator().next());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Block search
    // ─────────────────────────────────────────────────────────────────────────────

    private List<ChunkSnapshot> snapshotChunks(World world, Location origin, int radius) {
        int minChunkX = (origin.getBlockX() - radius) >> 4;
        int maxChunkX = (origin.getBlockX() + radius) >> 4;
        int minChunkZ = (origin.getBlockZ() - radius) >> 4;
        int maxChunkZ = (origin.getBlockZ() + radius) >> 4;
        List<ChunkSnapshot> out = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                out.add(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            }
        }
        return out;
    }

    private boolean isBlockVisible(Player bot, Block block) {
        Location eye = bot.getEyeLocation();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Vector dir = center.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist <= 0.001) return true;
        RayTraceResult hit = bot.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return hit != null
                && hit.getHitBlock() != null
                && hit.getHitBlock().getX() == block.getX()
                && hit.getHitBlock().getY() == block.getY()
                && hit.getHitBlock().getZ() == block.getZ();
    }

    private boolean isMineable(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        if (type.isAir() || !type.isSolid()) return false;
        if (block.getState() instanceof InventoryHolder) return false;
        return switch (type) {
            case BEDROCK,
                    BARRIER,
                    END_PORTAL,
                    END_PORTAL_FRAME,
                    NETHER_PORTAL,
                    COMMAND_BLOCK,
                    CHAIN_COMMAND_BLOCK,
                    REPEATING_COMMAND_BLOCK,
                    STRUCTURE_BLOCK,
                    JIGSAW,
                    LIGHT,
                    REINFORCED_DEEPSLATE -> false;
            default -> true;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Lifecycle helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Cancels the mining ticker and releases the action lock for this bot.
     */
    private void stopCurrentMine(UUID botUuid) {
        Integer taskId = miningTasks.remove(botUuid);
        if (taskId != null) FppScheduler.cancelTask(taskId);

        miningStates.remove(botUuid);
        manager.unlockAction(botUuid);
        manager.unlockNavigation(botUuid);

        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) {
            Player bot = fp.getPlayer();
            if (bot != null && bot.isOnline()) {
                ServerPlayer nmsBot = ((CraftPlayer) bot).getHandle();
                BotClient.of(nmsBot).stopDestroyBlock(nmsBot); // never leave a ghost dig behind
                NmsPlayerSpawner.setMovementForward(bot, 0f);
                bot.setSprinting(false);
            }
        }
    }

    /**
     * Fully stops the find job for a bot. Safe to call multiple times.
     */
    public void cleanupBot(UUID botUuid) {
        jobs.remove(botUuid);
        releaseReservations(botUuid);
        if (pathfinding.isNavigating(botUuid, PathfindingService.Owner.FIND)) {
            pathfinding.cancel(botUuid);
        }
        stopCurrentMine(botUuid);
        cancelNavWatchdog(botUuid);
    }

    /**
     * Starts a watchdog that fires {@code onStuck} if navigation to the current find-job block hasn't
     * arrived/cancelled/failed within {@link #NAV_WATCHDOG_TICKS} - the shared pathfinder will keep
     * recalculating against a genuinely unreachable block forever otherwise (anti-stuck).
     */
    private void startNavWatchdog(FakePlayer fp, Runnable onStuck) {
        startNavWatchdog(fp, null, onStuck);
    }

    private void startNavWatchdog(FakePlayer fp, @Nullable Block targetBlock, Runnable onStuck) {
        UUID uuid = fp.getUuid();
        cancelNavWatchdog(uuid);
        Player bot = fp.getPlayer();
        if (bot == null) return;
        int taskId = FppScheduler.runAtEntityLaterWithId(
                plugin,
                bot,
                () -> {
                    navWatchdogTasks.remove(uuid);
                    Location botLoc = bot.isOnline() ? bot.getLocation() : null;
                    Config.debugPathfinding("event=WATCHDOG bot='" + fp.getName() + "'"
                            + " elapsedTicks=" + NAV_WATCHDOG_TICKS
                            + " pos=" + (botLoc != null ? formatLoc(botLoc) : "offline")
                            + " target=" + (targetBlock != null ? formatBlock(targetBlock) : "n/a"));
                    NavController.sendDebugChat(uuid, fp.getName(), "pathdebug-watchdog");
                    onStuck.run();
                },
                NAV_WATCHDOG_TICKS);
        navWatchdogTasks.put(uuid, taskId);
    }

    private static String formatLoc(Location loc) {
        return loc.getWorld().getName() + String.format(":(%.1f,%.1f,%.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private static String formatBlock(Block block) {
        return block.getType() + "@" + block.getWorld().getName() + ":(" + block.getX() + "," + block.getY() + ","
                + block.getZ() + ")";
    }

    private void cancelNavWatchdog(UUID uuid) {
        Integer taskId = navWatchdogTasks.remove(uuid);
        if (taskId != null) FppScheduler.cancelTask(taskId);
    }

    /**
     * Stops all active find jobs.
     */
    public void stopAll() {
        for (UUID uuid : new HashSet<>(jobs.keySet())) {
            cleanupBot(uuid);
        }
    }

    public boolean isFinding(UUID botUuid) {
        return jobs.containsKey(botUuid);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Notification helper
    // ─────────────────────────────────────────────────────────────────────────────

    private void notifySender(FindJob job, String langKey, String... args) {
        if (job.starterUuid != null) {
            Player p = Bukkit.getPlayer(job.starterUuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(Lang.get(langKey, args));
                return;
            }
        }
        if (!job.playerStarted) {
            // console sender
            plugin.getLogger().info(Lang.raw(langKey, args));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Block key helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static long packBlockKey(int x, int y, int z) {
        // Encode x/z into 26 bits each (±33M blocks), y into 12 bits (±2048)
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static long blockKey(Block block) {
        return packBlockKey(block.getX(), block.getY(), block.getZ());
    }

    private String reservationKey(World world, long packedBlockKey) {
        return world.getUID() + ":" + packedBlockKey;
    }

    private void releaseReservation(long packedBlockKey, UUID botUuid) {
        FakePlayer fp = manager.getByUuid(botUuid);
        Player bot = fp != null ? fp.getPlayer() : null;
        if (bot == null || bot.getWorld() == null) return;
        reservedBlocks.remove(reservationKey(bot.getWorld(), packedBlockKey), botUuid);
    }

    private void releaseReservations(UUID botUuid) {
        reservedBlocks.entrySet().removeIf(entry -> entry.getValue().equals(botUuid));
    }

    private static boolean isStop(String arg) {
        return arg.equalsIgnoreCase("--stop");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Inner types
    // ─────────────────────────────────────────────────────────────────────────────

    private static final class FindJob {
        final Set<Material> materials;
        final String label;
        final int radius;
        final int count; // -1 = unlimited
        final boolean preferVisible;
        final UUID starterUuid;
        final boolean playerStarted;

        /**
         * Packed keys of blocks already targeted (so we don't revisit them).
         */
        final Set<Long> mined = new HashSet<>();

        int minedCount = 0;

        /** Skips the next inventory-full check - set right after a deposit trip finishes. */
        boolean skipInventoryCheckOnce = false;

        /** Also go for blocks touching lava (off by default). */
        boolean allowHazards = false;

        /** The block mined last, so the next pick can continue the same vein. */
        boolean hasLast = false;

        int lastX, lastY, lastZ;

        /** Ranked candidates left over from the last scan plus newly exposed vein neighbours. */
        final List<OreScanner.Hit> cache = new ArrayList<>();

        long lastScanMs;

        FindJob(
                Set<Material> materials,
                String label,
                int radius,
                int count,
                boolean preferVisible,
                UUID starterUuid,
                boolean playerStarted) {
            this.materials = materials;
            this.label = label;
            this.radius = radius;
            this.count = count;
            this.preferVisible = preferVisible;
            this.starterUuid = starterUuid;
            this.playerStarted = playerStarted;
        }
    }

    private static final class SimpleMiningState {
        final BlockPos targetPos;
        final BlockPos desiredPos;
        final Location lockLoc;
        final boolean countsTowardGoal;
        BlockPos currentPos;
        float progress;
        int freeze;
        boolean done;
        int postMinePause;
        float lastObservedProgress;
        int ticksSinceProgress;
        boolean hookFired;

        SimpleMiningState(BlockPos targetPos, BlockPos desiredPos, Location lockLoc, boolean countsTowardGoal) {
            this.targetPos = targetPos;
            this.desiredPos = desiredPos;
            this.lockLoc = lockLoc.clone();
            this.countsTowardGoal = countsTowardGoal;
        }
    }
}
