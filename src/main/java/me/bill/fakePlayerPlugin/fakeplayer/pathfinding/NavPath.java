package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.List;

/** A finished route: an ordered list of feet-block nodes, each tagged with how it is entered. */
public final class NavPath {

    /** How a node is entered from the previous one - drives the executor's jump/sprint/aim behaviour. */
    public enum Move {
        /** The starting node (current position). */
        START,
        WALK,
        DIAGONAL,
        ASCEND,
        DESCEND,
        FALL,
        PARKOUR,
        CLIMB_UP,
        CLIMB_DOWN,
        /** Jump and place a block under yourself - climbs one block with no support to walk up. */
        PILLAR,
        SWIM
    }

    /**
     * One step. {@code breaks}/{@code doors} are packed {@link net.minecraft.core.BlockPos#asLong} cells
     * to clear / open on the way in; {@code place} is the floor cell to bridge ({@link #NONE} if none).
     */
    public record Node(int x, int y, int z, Move move, long[] breaks, long[] doors, long place) {
        public static final long NONE = Long.MIN_VALUE;

        public double cx() {
            return x + 0.5;
        }

        public double cz() {
            return z + 0.5;
        }

        public boolean needsWork() {
            return breaks.length > 0 || doors.length > 0 || place != NONE;
        }
    }

    private final List<Node> nodes;
    private final boolean partial;

    public NavPath(List<Node> nodes, boolean partial) {
        this.nodes = nodes;
        this.partial = partial;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public int size() {
        return nodes.size();
    }

    public Node get(int i) {
        return nodes.get(i);
    }

    public Node last() {
        return nodes.get(nodes.size() - 1);
    }

    /** True when the search ran out of budget before the goal: the path leads to the closest point found. */
    public boolean isPartial() {
        return partial;
    }
}
