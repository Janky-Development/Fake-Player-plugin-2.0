package me.bill.fakePlayerPlugin.fakeplayer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BotNavUtil {

    private BotNavUtil() {}

    @FunctionalInterface
    public interface SelectionBounds {
        boolean contains(int x, int y, int z);
    }

    @Nullable
    public static Location findStandLocation(World world, @Nullable SelectionBounds sel, int tx, int ty, int tz) {
        int[][] candidates = {
            {tx + 1, ty, tz}, {tx - 1, ty, tz}, {tx, ty, tz + 1}, {tx, ty, tz - 1},
            {tx + 2, ty, tz}, {tx - 2, ty, tz}, {tx, ty, tz + 2}, {tx, ty, tz - 2},
            {tx + 1, ty - 1, tz}, {tx - 1, ty - 1, tz}, {tx, ty - 1, tz + 1}, {tx, ty - 1, tz - 1},
            {tx + 1, ty + 1, tz}, {tx - 1, ty + 1, tz}, {tx, ty + 1, tz + 1}, {tx, ty + 1, tz - 1}
        };
        Location targetCenter = new Location(world, tx + 0.5, ty + 0.5, tz + 0.5);

        if (sel != null) {
            for (int[] c : candidates) {
                if (sel.contains(c[0], c[1], c[2])) continue;
                if (walkable(world, c[0], c[1], c[2])) {
                    Location loc = new Location(world, c[0] + 0.5, c[1], c[2] + 0.5);
                    if (loc.distanceSquared(targetCenter) <= 36.0) return loc;
                }
            }
        }

        for (int[] c : candidates) {
            if (walkable(world, c[0], c[1], c[2])) {
                Location loc = new Location(world, c[0] + 0.5, c[1], c[2] + 0.5);
                if (loc.distanceSquared(targetCenter) <= 36.0) return loc;
            }
        }
        return null;
    }

    public static Location faceToward(Location from, Location target) {
        Location loc = from.clone();
        double dx = target.getX() - loc.getX();
        double dy = target.getY() - (loc.getY() + 1.62);
        double dz = target.getZ() - loc.getZ();
        double xz = Math.sqrt(dx * dx + dz * dz);
        loc.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        loc.setPitch((float) -Math.toDegrees(Math.atan2(dy, xz)));
        return loc;
    }



    /**
     * True when a bot can stand with its feet in {@code (x,y,z)} - decided by the pathfinder's own
     * collision-shape terrain model, so stand-location searches and routing always agree.
     */
    public static boolean walkable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) return false;
        try {
            var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            var terrain = new me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain(level);
            return terrain.stance(x, y, z, false)
                    == me.bill.fakePlayerPlugin.fakeplayer.pathfinding.NavTerrain.Stance.GROUND;
        } catch (Throwable t) {
            return canStandOn(world, x, y - 1, z)
                    && canPassThrough(world, x, y, z)
                    && canPassThrough(world, x, y + 1, z);
        }
    }

    public static boolean canPassThrough(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight()) return true;
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block block = world.getBlockAt(x, y, z);
            Material mat = block.getType();
            if (mat.isAir() || mat == Material.WATER) return true;
            if (mat == Material.LAVA || mat == Material.COBWEB) return false;
            if (block.getBlockData() instanceof Fence) return false;
            if (mat.name().contains("_WALL")
                    || mat == Material.COBBLESTONE_WALL
                    || mat == Material.MOSSY_COBBLESTONE_WALL) {
                return false;
            }
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Door door) return door.isOpen();
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Gate gate) return gate.isOpen();
            if (block.getBlockData() instanceof TrapDoor trapDoor) return trapDoor.isOpen();
            if (block.getBlockData() instanceof Slab slab) return slab.getType() == Slab.Type.BOTTOM;
            if (isClimbable(mat)) return true;
            return block.isPassable();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canStandOn(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight()) return false;
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block block = world.getBlockAt(x, y, z);
            Material mat = block.getType();
            if (mat.isAir() || mat == Material.WATER) return false;
            if (mat.isSolid() && mat.isOccluding()) return true;
            if (block.getBlockData() instanceof Slab) return true;
            if (mat.name().contains("STAIRS")) return true;
            if (block.getBlockData() instanceof Fence || mat.name().contains("WALL")) return false;
            if (mat == Material.GLASS
                    || mat.name().contains("STAINED_GLASS") && !mat.name().contains("PANE")) return true;
            if (mat == Material.CHEST
                    || mat == Material.TRAPPED_CHEST
                    || mat == Material.ENDER_CHEST
                    || mat == Material.BARREL) return true;
            if (mat.name().contains("LEAVES")) return true;
            if (mat == Material.FARMLAND || mat == Material.DIRT_PATH || mat == Material.SOUL_SAND) return true;
            if (mat == Material.HONEY_BLOCK || mat.name().contains("_BED") || mat == Material.SCAFFOLDING) return true;
            if (isClimbable(mat)) return true;
            if (block.getBlockData() instanceof TrapDoor trapDoor) {
                return !trapDoor.isOpen() && trapDoor.getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
            }
            if (mat == Material.MAGMA_BLOCK) return true;
            return !block.isPassable();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isClimbable(Material mat) {
        return mat == Material.LADDER
                || mat == Material.VINE
                || mat == Material.TWISTING_VINES
                || mat == Material.TWISTING_VINES_PLANT
                || mat == Material.WEEPING_VINES
                || mat == Material.WEEPING_VINES_PLANT
                || mat == Material.CAVE_VINES
                || mat == Material.CAVE_VINES_PLANT
                || mat == Material.SCAFFOLDING;
    }

    public static void useStorageBlock(Player bot, Block block) {
        try {
            ServerPlayer nms = ((CraftPlayer) bot).getHandle();
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            Vec3 hitVec = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
            nms.resetLastActionTime();
            var result = NmsPlayerSpawner.useItemOn(nms, InteractionHand.MAIN_HAND, hit);
            if (NmsPlayerSpawner.consumesAction(result)) {
                nms.swing(InteractionHand.MAIN_HAND);
            }
        } catch (Throwable ignored) {
        }
    }
}
