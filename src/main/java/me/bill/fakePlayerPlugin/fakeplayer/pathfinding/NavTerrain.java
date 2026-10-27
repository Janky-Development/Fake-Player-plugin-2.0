package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The pathfinder's read-only view of the world. Everything is derived from the real block state and
 * collision shapes (the same data the server's own physics uses), never from material-name heuristics,
 * so slabs, stairs, carpets, farmland, ladders, doors etc. are all classified by what the bot's body
 * would actually do against them. Chunks are only ever read when already loaded - an unloaded column is
 * treated as solid wall, so a search never forces chunk loads.
 *
 * <p>Cells are classified lazily and cached for the lifetime of one instance; call {@link #refresh()}
 * before re-validating a path that was planned earlier.
 */
public class NavTerrain {

    /** How a body-height cell behaves for a walking player. */
    public static final class Cell {
        /** Collision that blocks a player's body (anything taller than a carpet). */
        public final boolean solid;
        /** Highest collision Y inside the cell (0 when empty); a floor when {@code 0.5..1.0}. */
        public final float top;

        public final boolean water;
        public final boolean lava;
        /** Damages / cripples a bot that stands in or on it (fire, cactus, magma, berries, web, …). */
        public final boolean harmful;

        public final boolean climbable;
        /** A closed wooden door / fence gate: solid now, but a bot can open it. */
        public final boolean openable;
        /** Can be mined away when the path is allowed to break blocks. */
        public final boolean breakable;
        /** Slows the walker (soul sand, honey). */
        public final boolean slow;
        /** Ticks the bot's current tool needs to mine this cell (only meaningful when {@link #breakable}). */
        public final float breakTicks;

        public Cell(
                boolean solid,
                float top,
                boolean water,
                boolean lava,
                boolean harmful,
                boolean climbable,
                boolean openable,
                boolean breakable,
                boolean slow) {
            this(solid, top, water, lava, harmful, climbable, openable, breakable, slow, 15f);
        }

        public Cell(
                boolean solid,
                float top,
                boolean water,
                boolean lava,
                boolean harmful,
                boolean climbable,
                boolean openable,
                boolean breakable,
                boolean slow,
                float breakTicks) {
            this.solid = solid;
            this.top = top;
            this.water = water;
            this.lava = lava;
            this.harmful = harmful;
            this.climbable = climbable;
            this.openable = openable;
            this.breakable = breakable;
            this.slow = slow;
            this.breakTicks = breakTicks;
        }

        /** True when the cell is open air / fluid / non-blocking decoration (a body can occupy it). */
        public boolean passable() {
            return !solid;
        }

        public boolean floor() {
            return solid && top >= 0.5f && top <= 1.0f && !harmful;
        }
    }

    private static final Cell UNLOADED = new Cell(true, 1.0f, false, false, false, false, false, false, false);
    private static final Cell OUT_OF_WORLD = new Cell(false, 0f, false, false, false, false, false, false, false);

    private final ServerLevel level;
    private final int minY;
    private final int maxY;
    private final LongObjMap<Cell> cache = new LongObjMap<>(512);
    private net.minecraft.server.level.ServerPlayer digger;
    private java.util.function.LongPredicate breakFilter;

    public NavTerrain(ServerLevel level) {
        this.level = level;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
    }

    /** For tests: a terrain not backed by a level; subclasses supply {@link #classify} / {@link #isLoaded}. */
    protected NavTerrain(int minY, int maxY) {
        this.level = null;
        this.minY = minY;
        this.maxY = maxY;
    }

    public ServerLevel level() {
        return level;
    }

    /** Restricts digging to cells the filter accepts (packed block-pos keys); null allows any. */
    public void setBreakFilter(java.util.function.LongPredicate filter) {
        this.breakFilter = filter;
        cache.clear();
    }

    /** Lets the terrain price digging by the bot's real tool/enchants/effects (else a flat estimate). */
    public void setDigger(net.minecraft.server.level.ServerPlayer digger) {
        this.digger = digger;
        cache.clear();
    }

    /** Drops cached cells so subsequent reads reflect the live world again. */
    public void refresh() {
        cache.clear();
    }

    public boolean isLoaded(int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    public Cell cell(int x, int y, int z) {
        if (y < minY) return UNLOADED; // below the world: a solid "floor" nobody should path into
        if (y > maxY) return OUT_OF_WORLD;
        long key = BlockPos.asLong(x, y, z);
        Cell c = cache.get(key);
        if (c == null) {
            c = classify(x, y, z);
            cache.put(key, c);
        }
        return c;
    }

    protected Cell classify(int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return UNLOADED;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = chunk.getBlockState(pos);
        Block block = state.getBlock();

        boolean water = state.getFluidState().is(FluidTags.WATER);
        boolean lava = state.getFluidState().is(FluidTags.LAVA);

        VoxelShape shape = state.getCollisionShape(level, pos);
        float top = shape.isEmpty() ? 0f : (float) shape.max(Direction.Axis.Y);
        // Carpet / snow layers / pressure plates are walked over, not walked into.
        boolean solid = !shape.isEmpty() && top > 0.125f;

        boolean climbable = state.is(BlockTags.CLIMBABLE);
        if (climbable) solid = false;

        boolean openable = false;
        if (block instanceof DoorBlock && state.is(BlockTags.WOODEN_DOORS) && !state.getValue(DoorBlock.OPEN)) {
            openable = true;
        } else if (block instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            openable = true;
        }

        boolean harmful = lava
                || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.COBWEB
                || block == Blocks.POWDER_SNOW
                || block == Blocks.POINTED_DRIPSTONE;

        boolean slow = block == Blocks.SOUL_SAND || block == Blocks.HONEY_BLOCK;

        boolean breakable = false;
        float breakTicks = 15f;
        if (solid) {
            float hardness = state.getDestroySpeed(level, pos);
            breakable = hardness >= 0f && !isProtected(block) && !state.hasBlockEntity();
            if (breakable && breakFilter != null && !breakFilter.test(pos.asLong())) breakable = false;
            if (breakable && digger != null) {
                float progress = state.getDestroyProgress(digger, level, pos);
                breakTicks = progress <= 0f ? 600f : Math.min(600f, 1f / progress);
            }
        }
        return new Cell(solid, top, water, lava, harmful, climbable, openable, breakable, slow, breakTicks);
    }

    /** Blocks never mined for path clearance regardless of tool/hardness. */
    private static boolean isProtected(Block block) {
        return block == Blocks.OBSIDIAN
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.ANCIENT_DEBRIS
                || block == Blocks.SPAWNER
                || block == Blocks.BEACON
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.END_PORTAL_FRAME;
    }

    // ── Node queries ────────────────────────────────────────────────────────────────────────

    /** What kind of position a feet-block is. */
    public enum Stance {
        /** Cannot be occupied (inside a wall / lava / harmful). */
        INVALID,
        /** Body fits, but there is nothing to stand on - passable only by falling through. */
        AIR,
        /** Standing on a floor. */
        GROUND,
        /** Swimming (feet in water). */
        WATER,
        /** Hanging on a ladder / vine / scaffolding. */
        CLIMB
    }

    /**
     * Classifies the feet block {@code (x,y,z)}: can a 0.6x1.8 body be here, and is it supported?
     *
     * @param avoidWater treat water as impassable
     */
    public Stance stance(int x, int y, int z, boolean avoidWater) {
        Cell feet = cell(x, y, z);
        Cell head = cell(x, y + 1, z);
        if (feet.solid || head.solid) return Stance.INVALID;
        if (feet.lava || head.lava || feet.harmful || head.harmful) return Stance.INVALID;
        if (feet.climbable || head.climbable) return Stance.CLIMB;
        if (feet.water) return avoidWater ? Stance.INVALID : Stance.WATER;
        Cell below = cell(x, y - 1, z);
        if (below.floor()) return Stance.GROUND;
        if (below.water) return avoidWater ? Stance.INVALID : Stance.AIR; // land on the surface = swim
        return Stance.AIR;
    }
}
