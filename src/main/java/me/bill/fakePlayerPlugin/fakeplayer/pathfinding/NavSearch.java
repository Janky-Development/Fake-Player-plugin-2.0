package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import me.bill.fakePlayerPlugin.fakeplayer.BotPathfinder.PathOptions;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavPath.Move;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain.Cell;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain.Stance;

import net.minecraft.core.BlockPos;

/**
 * Incremental A* over feet-block positions. Costs are in game ticks (walk ≈ 4.63 ticks/block like the
 * vanilla walking speed), so the route minimises travel <em>time</em>, not block count: sprint-less
 * walking, jumping up, falling, swimming, climbing, opening doors, breaking and bridging all carry
 * physically meaningful prices.
 *
 * <p>The search is time-sliced - {@link #step(long)} expands nodes until a deadline and can be resumed
 * next tick - so it runs on the bot's own thread (safe on Folia, no off-thread world reads) without
 * ever stalling a tick. When the node/range budget runs out before the goal is found it settles for the
 * explored node closest to the goal ({@link Status#PARTIAL}), the bot walks there, then searches again.
 */
public final class NavSearch {

    public enum Status {
        RUNNING,
        FOUND,
        PARTIAL,
        FAILED
    }

    static final double WALK = 4.63;
    static final double DIAGONAL = 6.55;
    private static final double ASCEND_EXTRA = 5.0;
    private static final double SWIM = 9.1;
    private static final double CLIMB_UP = 8.5;
    private static final double CLIMB_DOWN = 5.0;
    private static final double FALL_BASE = 4.0;
    private static final double FALL_PER_BLOCK = 3.0;
    private static final double PARKOUR_BASE = 8.0;
    private static final double PARKOUR_PER_BLOCK = 3.0;
    /** Fixed overhead of starting a dig (aim, tool swap); the tool-aware tick cost is added per cell. */
    private static final double BREAK_BASE = 6.0;

    private static final double PILLAR = 13.0;
    private static final double PLACE = 22.0;
    private static final double DOOR = 6.0;
    private static final double SLOW_MULT = 2.0;

    private static final long[] NO_CELLS = new long[0];

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final long key;
        double g;
        double h;
        double f;
        Node parent;
        Move move = Move.START;
        long[] breaks = NO_CELLS;
        long[] doors = NO_CELLS;
        long place = NavPath.Node.NONE;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = BlockPos.asLong(x, y, z);
        }

        @Override
        public int compareTo(Node o) {
            int c = Double.compare(f, o.f);
            return c != 0 ? c : Double.compare(h, o.h);
        }
    }

    private final NavTerrain terrain;
    private final PathOptions opts;
    private final NavGoal goal;
    private final int maxFall;
    private final int nodeLimit;
    private final int sx, sy, sz;
    private final int rangeSq;

    private final PriorityQueue<Node> open = new PriorityQueue<>(256);
    private final LongObjMap<Node> nodes = new LongObjMap<>(512);
    private final Node start;
    private Node best;
    private Node finish;
    private int expanded;
    private Status status = Status.RUNNING;

    public NavSearch(
            NavTerrain terrain,
            PathOptions opts,
            NavGoal goal,
            int sx,
            int sy,
            int sz,
            int maxFall,
            int nodeLimit,
            int range) {
        this.terrain = terrain;
        this.opts = opts;
        this.goal = goal;
        this.maxFall = Math.max(1, maxFall);
        this.nodeLimit = nodeLimit;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.rangeSq = range * range;

        start = new Node(sx, sy, sz);
        start.h = goal.heuristic(sx, sy, sz);
        start.f = start.h;
        nodes.put(start.key, start);
        open.add(start);
        best = start;
    }

    public Status status() {
        return status;
    }

    public int expanded() {
        return expanded;
    }

    /** Runs until {@code deadlineNanos} ({@link System#nanoTime()} base) or completion. */
    public Status step(long deadlineNanos) {
        if (status != Status.RUNNING) return status;
        int sinceClock = 0;
        while (!open.isEmpty()) {
            if (expanded >= nodeLimit) break;
            if (++sinceClock >= 24) {
                sinceClock = 0;
                if (System.nanoTime() >= deadlineNanos) return Status.RUNNING;
            }
            Node n = open.poll();
            if (n.closed) continue;
            n.closed = true;
            expanded++;

            if (goal.isGoal(n.x, n.y, n.z)) {
                finish = n;
                status = Status.FOUND;
                return status;
            }
            if (n.h < best.h - 1e-9) best = n;
            expand(n);
        }
        // Exhausted or over budget: settle for the closest explored node if it made real progress.
        double progress = start.h - best.h;
        if (best != start && progress >= 1.5) {
            finish = best;
            status = Status.PARTIAL;
        } else {
            status = Status.FAILED;
        }
        return status;
    }

    public NavPath path() {
        if (finish == null) return null;
        List<NavPath.Node> out = new ArrayList<>();
        for (Node n = finish; n != null; n = n.parent) {
            out.add(new NavPath.Node(n.x, n.y, n.z, n.move, n.breaks, n.doors, n.place));
        }
        Collections.reverse(out);
        return new NavPath(out, status == Status.PARTIAL);
    }

    // ── Expansion ───────────────────────────────────────────────────────────────────────────

    private void expand(Node n) {
        Stance here = terrain.stance(n.x, n.y, n.z, opts.avoidWater());
        boolean swimming = here == Stance.WATER;
        boolean climbing = here == Stance.CLIMB;

        for (int[] d : CARDINALS) {
            walkLike(n, d[0], d[1], swimming);
        }
        for (int[] d : DIAGONALS) {
            diagonal(n, d[0], d[1], swimming);
        }
        if (opts.parkour() && here == Stance.GROUND) {
            for (int[] d : CARDINALS) parkour(n, d[0], d[1]);
        }
        if (climbing) {
            climbVertical(n);
        }
        if (opts.placeBlocks() && here == Stance.GROUND) {
            pillar(n);
        }
        if (swimming) {
            swimVertical(n);
        }
    }

    /** Result of evaluating "can the body occupy (x,y,z) and what does entering it cost". */
    private static final class Entry {
        Stance stance;
        double extra;
        List<Long> breaks;
        List<Long> doors;
        long place = NavPath.Node.NONE;
        boolean slow;
    }

    /**
     * Evaluates the feet block {@code (x,y,z)} as a destination. Returns null when it cannot be entered.
     * Body cells that are solid but openable/breakable are accepted (with cost) per the path options.
     */
    private Entry enter(int x, int y, int z, boolean allowBreakHead) {
        if (!terrain.isLoaded(x, z)) return null;
        Entry e = new Entry();
        for (int dy = 0; dy <= 1; dy++) {
            Cell c = terrain.cell(x, y + dy, z);
            if (c.lava || c.harmful) return null;
            if (c.solid) {
                if (c.openable) {
                    if (e.doors == null) e.doors = new ArrayList<>(2);
                    e.doors.add(BlockPos.asLong(x, y + dy, z));
                    e.extra += DOOR;
                } else if (opts.breakBlocks() && c.breakable && (dy == 0 || allowBreakHead)) {
                    if (e.breaks == null) e.breaks = new ArrayList<>(2);
                    e.breaks.add(BlockPos.asLong(x, y + dy, z));
                    e.extra += BREAK_BASE + c.breakTicks;
                } else {
                    return null;
                }
            }
        }
        Cell feet = terrain.cell(x, y, z);
        Cell head = terrain.cell(x, y + 1, z);
        if (opts.avoidWater() && (feet.water || head.water)) return null;

        if (feet.climbable || head.climbable) {
            e.stance = Stance.CLIMB;
            return e;
        }
        if (feet.water) {
            e.stance = Stance.WATER;
            return e;
        }
        Cell below = terrain.cell(x, y - 1, z);
        if (below.harmful || below.lava) return null;
        if (below.floor()) {
            e.stance = Stance.GROUND;
            e.slow = below.slow;
            return e;
        }
        if (below.water) {
            e.stance = Stance.AIR; // would fall in
            return e;
        }
        if (opts.placeBlocks() && !below.solid && !below.lava && !below.water) {
            e.stance = Stance.GROUND;
            e.place = BlockPos.asLong(x, y - 1, z);
            e.extra += PLACE;
            return e;
        }
        e.stance = Stance.AIR;
        return e;
    }

    private void walkLike(Node n, int dx, int dz, boolean swimming) {
        int nx = n.x + dx, nz = n.z + dz;
        if (outOfRange(nx, nz)) return;

        // ── level walk / swim into the neighbour ──
        Entry e = enter(nx, n.y, nz, false);
        if (e != null && e.stance != Stance.AIR) {
            Move move = e.stance == Stance.WATER ? Move.SWIM : Move.WALK;
            double cost = e.stance == Stance.WATER ? SWIM : WALK * (e.slow ? SLOW_MULT : 1.0);
            relax(n, nx, n.y, nz, move, cost + e.extra, e);
        }

        // ── step up one block ──
        {
            Cell over = terrain.cell(n.x, n.y + 2, n.z);
            boolean headroom = !over.solid || (opts.breakBlocks() && over.breakable);
            if (headroom && !over.lava) {
                // the cell the body must pass through while hopping must be clear of walls too
                Cell side = terrain.cell(nx, n.y + 2, nz);
                if (!side.solid || (opts.breakBlocks() && side.breakable)) {
                    Entry up = enter(nx, n.y + 1, nz, true);
                    if (up != null && up.stance != Stance.AIR && up.stance != Stance.WATER) {
                        double extra = up.extra;
                        if (over.solid) extra += BREAK_BASE + over.breakTicks;
                        relax(n, nx, n.y + 1, nz, Move.ASCEND, WALK + ASCEND_EXTRA + extra, up);
                    }
                }
            }
        }

        // ── drop / fall off the edge ──
        if (e == null || e.stance == Stance.AIR) {
            fall(n, nx, nz);
        }
    }

    private void fall(Node n, int nx, int nz) {
        if (!terrain.isLoaded(nx, nz)) return;
        Cell f0 = terrain.cell(nx, n.y, nz);
        Cell h0 = terrain.cell(nx, n.y + 1, nz);
        if (f0.solid || h0.solid || f0.lava || h0.lava || f0.harmful || h0.harmful) return;
        if (opts.avoidWater() && (f0.water || h0.water)) return;
        for (int k = 1; k <= maxFall + 1; k++) {
            int ny = n.y - k;
            Cell c = terrain.cell(nx, ny, nz);
            if (c.solid || c.lava || c.harmful) return;
            if (opts.avoidWater() && c.water) return;
            if (c.climbable) {
                relaxFall(n, nx, ny, nz, k, Stance.CLIMB);
                return;
            }
            if (c.water) {
                relaxFall(n, nx, ny, nz, k, Stance.WATER);
                return;
            }
            Cell below = terrain.cell(nx, ny - 1, nz);
            if (below.floor()) {
                if (k <= maxFall) relaxFall(n, nx, ny, nz, k, Stance.GROUND);
                return;
            }
            if (below.solid || below.lava || below.harmful) return;
        }
    }

    private void relaxFall(Node n, int nx, int ny, int nz, int k, Stance landing) {
        double cost = k == 1 ? WALK + 1.0 : WALK + FALL_BASE + FALL_PER_BLOCK * k;
        Move move = k == 1 ? Move.DESCEND : Move.FALL;
        if (landing == Stance.WATER) cost += 2.0;
        relax(n, nx, ny, nz, move, cost, null);
    }

    private void diagonal(Node n, int dx, int dz, boolean swimming) {
        int nx = n.x + dx, nz = n.z + dz;
        if (outOfRange(nx, nz)) return;
        // Neither shoulder may clip a wall (body is 0.6 wide).
        for (int dy = 0; dy <= 1; dy++) {
            if (terrain.cell(n.x + dx, n.y + dy, n.z).solid) return;
            if (terrain.cell(n.x, n.y + dy, n.z + dz).solid) return;
            Cell a = terrain.cell(n.x + dx, n.y + dy, n.z);
            Cell b = terrain.cell(n.x, n.y + dy, n.z + dz);
            if (a.lava || a.harmful || b.lava || b.harmful) return;
        }
        Entry e = enter(nx, n.y, nz, false);
        if (e == null || e.stance == Stance.AIR) return;
        // Diagonals never combine with doors/breaks - those stay on cardinal moves.
        if (e.breaks != null || e.doors != null) return;
        double cost = e.stance == Stance.WATER ? SWIM * 1.414 : DIAGONAL * (e.slow ? SLOW_MULT : 1.0);
        relax(n, nx, n.y, nz, e.stance == Stance.WATER ? Move.SWIM : Move.DIAGONAL, cost + e.extra, e);
    }

    private void parkour(Node n, int dx, int dz) {
        // The adjacent column must NOT be walkable (otherwise a plain walk covers it).
        Entry adj = enter(n.x + dx, n.y, n.z + dz, false);
        if (adj != null && adj.stance != Stance.AIR) return;

        for (int dist = 2; dist <= 3; dist++) {
            for (int dy = (dist == 2 ? 1 : 0); dy >= -1; dy--) {
                int nx = n.x + dx * dist, nz = n.z + dz * dist;
                if (outOfRange(nx, nz)) continue;
                int ny = n.y + dy;
                if (!clearArc(n, dx, dz, dist, Math.max(n.y, ny))) continue;
                Entry e = enter(nx, ny, nz, false);
                if (e == null
                        || e.stance != Stance.GROUND
                        || e.breaks != null
                        || e.doors != null
                        || e.place != NavPath.Node.NONE) {
                    continue;
                }
                relax(n, nx, ny, nz, Move.PARKOUR, PARKOUR_BASE + PARKOUR_PER_BLOCK * dist + (dy < 0 ? 1.0 : 0.0), e);
            }
        }
    }

    /** True when the whole jump corridor (feet..head+1) is open - never "jump" through a ceiling. */
    private boolean clearArc(Node n, int dx, int dz, int dist, int topY) {
        for (int i = 0; i <= dist; i++) {
            int cx = n.x + dx * i, cz = n.z + dz * i;
            if (!terrain.isLoaded(cx, cz)) return false;
            for (int y = Math.min(n.y, topY); y <= topY + 2; y++) {
                if (i == 0 && y < n.y + 2) continue; // own column: only headroom matters
                Cell c = terrain.cell(cx, y, cz);
                if (c.solid || c.lava || c.harmful) return false;
            }
        }
        return true;
    }

    private void climbVertical(Node n) {
        // up
        Cell over = terrain.cell(n.x, n.y + 2, n.z);
        if (!over.solid && !over.lava) {
            Entry up = enter(n.x, n.y + 1, n.z, false);
            if (up != null && (up.stance == Stance.CLIMB || up.stance == Stance.GROUND) && up.breaks == null) {
                relax(n, n.x, n.y + 1, n.z, Move.CLIMB_UP, CLIMB_UP + up.extra, up);
            }
        }
        // down
        Entry down = enter(n.x, n.y - 1, n.z, false);
        if (down != null
                && (down.stance == Stance.CLIMB || down.stance == Stance.GROUND || down.stance == Stance.WATER)
                && down.breaks == null) {
            relax(n, n.x, n.y - 1, n.z, Move.CLIMB_DOWN, CLIMB_DOWN + down.extra, down);
        }
    }

    /**
     * Build straight up one block: jump and place a block into the cell just vacated. Works anywhere a
     * body fits above (breaking a ceiling block first when digging is allowed) - the way a player gets out
     * of a pit or up to a layer they can't reach.
     */
    private void pillar(Node n) {
        Cell feet = terrain.cell(n.x, n.y + 1, n.z);
        if (feet.solid || feet.lava || feet.harmful) return;
        // enter() prices "no floor -> place one" as a bridge; for a pillar that placement is the move.
        Entry up = enter(n.x, n.y + 1, n.z, true);
        if (up == null || up.stance != Stance.GROUND || up.place == NavPath.Node.NONE) return;
        relax(n, n.x, n.y + 1, n.z, Move.PILLAR, PILLAR + up.extra - PLACE, up);
    }

    private void swimVertical(Node n) {
        Entry up = enter(n.x, n.y + 1, n.z, false);
        if (up != null && up.stance == Stance.WATER) {
            relax(n, n.x, n.y + 1, n.z, Move.SWIM, SWIM, up);
        }
        Entry down = enter(n.x, n.y - 1, n.z, false);
        if (down != null && down.stance == Stance.WATER) {
            relax(n, n.x, n.y - 1, n.z, Move.SWIM, SWIM, down);
        }
    }

    private boolean outOfRange(int x, int z) {
        int dx = x - sx, dz = z - sz;
        return dx * dx + dz * dz > rangeSq;
    }

    private void relax(Node from, int x, int y, int z, Move move, double cost, Entry e) {
        long key = BlockPos.asLong(x, y, z);
        Node n = nodes.get(key);
        double g = from.g + cost;
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.heuristic(x, y, z);
            nodes.put(key, n);
        } else if (g >= n.g - 1e-9) {
            return;
        }
        n.g = g;
        n.f = g + n.h;
        n.parent = from;
        n.move = move;
        if (e != null) {
            n.breaks = pack(e.breaks);
            n.doors = pack(e.doors);
            n.place = e.place;
        } else {
            n.breaks = NO_CELLS;
            n.doors = NO_CELLS;
            n.place = NavPath.Node.NONE;
        }
        n.closed = false;
        open.add(n);
    }

    private static long[] pack(List<Long> list) {
        if (list == null || list.isEmpty()) return NO_CELLS;
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
