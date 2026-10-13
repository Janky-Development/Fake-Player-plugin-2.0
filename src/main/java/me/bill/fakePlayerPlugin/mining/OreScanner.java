package me.bill.fakePlayerPlugin.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * "Legit" block detection for the find system: it reports only blocks a player standing in the open
 * could actually have seen - ones with at least one face touching air, water, glass, foliage or any
 * other see-through block. A block sealed inside solid rock is never reported, so this is not X-ray:
 * the bot discovers a buried vein exactly as a person does, by uncovering it (mining one block of a
 * vein exposes its neighbours, which then become visible to the next scan).
 *
 * <p>Candidates are ranked by walking effort rather than straight distance (vertical travel costs more
 * than horizontal), so the bot goes for the easy block first, and blocks touching lava are flagged as
 * hazards so callers can skip them.
 *
 * <p>Pure logic over a {@link BlockView}; the production view is backed by chunk snapshots so the scan
 * can run off the main thread.
 */
public final class OreScanner {

    private OreScanner() {}

    /** Read-only block access (any thread-safe backing). */
    public interface BlockView {
        /** Material key at the position, or {@code null} when the chunk is not loaded / out of range. */
        Object type(int x, int y, int z);
    }

    /** One detected block. {@code score} is walking effort (lower = easier). */
    public record Hit(int x, int y, int z, double score, boolean hazard) {}

    public static final double VERTICAL_WEIGHT = 1.7;

    /**
     * @param wanted materials to find, compared with {@link Object#equals} against the view's keys
     * @param seeThrough true for keys that do NOT hide a neighbouring face (air, fluids, glass, leaves…)
     * @param isLava true for keys that make an adjacent block hazardous
     * @param excluded packed positions to skip (already mined / reserved by another bot)
     * @param limit at most this many best hits are returned
     */
    public static List<Hit> scan(
            BlockView view,
            int ox,
            int oy,
            int oz,
            int radius,
            int minY,
            int maxY,
            Set<?> wanted,
            Predicate<Object> seeThrough,
            Predicate<Object> isLava,
            LongPredicate excluded,
            boolean allowHazards,
            int limit) {
        List<Hit> hits = new ArrayList<>();
        double radiusSq = (double) radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    int y = oy + dy;
                    if (y < minY || y > maxY) continue;
                    double distSq = dx * (double) dx + dy * (double) dy + dz * (double) dz;
                    if (distSq > radiusSq) continue;

                    int x = ox + dx;
                    int z = oz + dz;
                    Object here = view.type(x, y, z);
                    if (here == null || !wanted.contains(here)) continue;
                    if (excluded != null && excluded.test(pack(x, y, z))) continue;

                    boolean exposed = false;
                    boolean hazard = false;
                    for (int[] d : FACES) {
                        Object n = view.type(x + d[0], y + d[1], z + d[2]);
                        if (n == null) continue; // unloaded neighbour: assume closed
                        if (seeThrough.test(n)) exposed = true;
                        if (isLava.test(n)) hazard = true;
                    }
                    if (!exposed) continue;
                    if (hazard && !allowHazards) continue;

                    double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double score = horizontal + VERTICAL_WEIGHT * Math.abs(dy) + (hazard ? 25 : 0);
                    hits.add(new Hit(x, y, z, score, hazard));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Same packing as {@code BlockPos.asLong} so keys are interchangeable with the rest of the plugin. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
