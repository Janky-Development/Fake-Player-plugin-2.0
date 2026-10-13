package me.bill.fakePlayerPlugin.mining;

import java.util.List;

/**
 * An axis-aligned block box for the clear job, with the geometry the layer-by-layer excavator needs.
 * Coordinates are inclusive block coordinates.
 */
public record ClearRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ClearRegion {
        if (minX > maxX) {
            int t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minY > maxY) {
            int t = minY;
            minY = maxY;
            maxY = t;
        }
        if (minZ > maxZ) {
            int t = minZ;
            minZ = maxZ;
            maxZ = t;
        }
    }

    /** One full chunk column range. */
    public static ClearRegion ofChunk(int chunkX, int chunkZ, int minY, int maxY) {
        return new ClearRegion(chunkX << 4, minY, chunkZ << 4, (chunkX << 4) + 15, maxY, (chunkZ << 4) + 15);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int layers() {
        return maxY - minY + 1;
    }

    public long cellsPerLayer() {
        return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    public long totalCells() {
        return cellsPerLayer() * layers();
    }

    public ClearRegion withY(int newMinY, int newMaxY) {
        return new ClearRegion(minX, newMinY, minZ, maxX, newMaxY, maxZ);
    }

    /** The chunks (as {@code [cx, cz]}) this region touches. */
    public List<int[]> chunks() {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) out.add(new int[] {cx, cz});
        }
        return out;
    }

}
