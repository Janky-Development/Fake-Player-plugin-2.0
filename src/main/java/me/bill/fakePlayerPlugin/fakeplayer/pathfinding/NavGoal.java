package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

/**
 * The search target: the feet block of the destination plus an arrival radius. A node satisfies the
 * goal when it is within {@code radius} blocks horizontally and at most one block vertically.
 */
public record NavGoal(int x, int y, int z, double radius) {

    boolean isGoal(int nx, int ny, int nz) {
        double dx = nx - x;
        double dz = nz - z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(ny - y) <= 1;
    }

    /** Lower bound (in ticks) on the cost of reaching the goal region from this node. */
    double heuristic(int nx, int ny, int nz) {
        double dx = Math.abs(nx - x);
        double dz = Math.abs(nz - z);
        double diag = Math.min(dx, dz);
        double straight = Math.max(dx, dz) - diag;
        double h = diag * NavSearch.DIAGONAL + straight * NavSearch.WALK;
        h -= radius * NavSearch.WALK;
        if (h < 0) h = 0;
        int dy = y - ny;
        if (dy > 1) h += (dy - 1) * 4.0;
        return h;
    }
}
