package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.function.LongPredicate;

import org.jetbrains.annotations.Nullable;

public final class BotPathfinder {
    private BotPathfinder() {}

    /**
     * What a route may do beyond walking.
     *
     * @param breakAllowed when non-null, digging is only permitted in cells it accepts (packed
     *     {@link net.minecraft.core.BlockPos#asLong} keys) - how a bot is told "mine, but only inside
     *     this region". Null = any breakable block.
     */
    public record PathOptions(
            boolean parkour,
            boolean breakBlocks,
            boolean placeBlocks,
            boolean avoidWater,
            boolean avoidLava,
            @Nullable LongPredicate breakAllowed) {

        public PathOptions(
                boolean parkour, boolean breakBlocks, boolean placeBlocks, boolean avoidWater, boolean avoidLava) {
            this(parkour, breakBlocks, placeBlocks, avoidWater, avoidLava, null);
        }

        public PathOptions(boolean parkour, boolean breakBlocks, boolean placeBlocks) {
            this(parkour, breakBlocks, placeBlocks, false, false, null);
        }

        public static final PathOptions DEFAULT = new PathOptions(false, false, false, false, false, null);

        public PathOptions withPlaceBlocks(boolean place) {
            return new PathOptions(parkour, breakBlocks, place, avoidWater, avoidLava, breakAllowed);
        }
    }
}
