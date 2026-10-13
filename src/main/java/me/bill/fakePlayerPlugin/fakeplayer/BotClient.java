package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import me.bill.fakePlayerPlugin.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A faithful server-side port of the vanilla client's click logic - {@code Minecraft#startAttack},
 * {@code #continueAttack}, {@code #startUseItem} and {@code MultiPlayerGameMode}'s destroy-block state
 * machine (decompiled from the 26.x client). A bot has no client, so this class <em>is</em> its
 * client: every decision (what the crosshair is on, when a dig starts / continues / finishes, which hand
 * is tried and in what order, attack-charge gating, miss and use delays) follows the real client's
 * order and thresholds, and every effect goes out as the same serverbound packet a real client sends
 * through {@link BotClickDispatcher} - so the server's own validation, Bukkit events and item logic run
 * unchanged.
 *
 * <p>One instance per bot, shared by the left- and right-click loops (the vanilla client keeps a
 * single {@code MultiPlayerGameMode}; e.g. a right-click is ignored while a block is being dug).
 */
public final class BotClient {

    /** Vanilla {@code Minecraft#missTime} reset after swinging at nothing. */
    private static final int MISS_TIME = 10;

    private static final Map<UUID, BotClient> CLIENTS = new ConcurrentHashMap<>();

    public static BotClient of(ServerPlayer nms) {
        return CLIENTS.computeIfAbsent(nms.getUUID(), k -> new BotClient());
    }

    public static void forget(UUID botUuid) {
        CLIENTS.remove(botUuid);
        BotClickDispatcher.forget(botUuid);
    }

    // ── Minecraft ─────────────────────────────────────────────────────────────────────────────
    /** Ticks until the next attack/dig-start is allowed (set after swinging at air). */
    public int missTime;
    /** Ticks until the held use key may fire again. */
    public int rightClickDelay;

    // ── MultiPlayerGameMode ───────────────────────────────────────────────────────────────────
    private boolean isDestroying;
    private BlockPos destroyBlockPos = new BlockPos(-1, -1, -1);
    private Direction destroyDirection;
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private int destroyDelay;

    private BotClient() {}

    public boolean isDestroying() {
        return isDestroying;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    //  Crosshair pick  (LocalPlayer#raycastHitResult)
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * What the bot's crosshair is on, exactly as the client's per-tick {@code pick}: an item with an
     * {@code ATTACK_RANGE} component (spear) uses its own reach first; otherwise the nearest of
     * block/entity within the block/entity interaction-range attributes wins.
     */
    public static HitResult pick(ServerPlayer p) {
        ItemStack active = p.getActiveItem();
        AttackRange itemRange = active.get(DataComponents.ATTACK_RANGE);
        double blockRange = p.blockInteractionRange();
        HitResult hit = null;
        if (itemRange != null) {
            hit = itemRange.getClosesetHit(p, 1.0F, EntitySelector.CAN_BE_PICKED);
            if (hit instanceof BlockHitResult) {
                hit = filter(hit, p.getEyePosition(1.0F), blockRange);
            }
        }
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            hit = pickNearest(p, blockRange, p.entityInteractionRange());
        }
        return hit;
    }

    private static HitResult pickNearest(ServerPlayer p, double blockRange, double entityRange) {
        double maxDistance = Math.max(blockRange, entityRange);
        double maxDistanceSq = Mth.square(maxDistance);
        Vec3 from = p.getEyePosition(1.0F);
        HitResult blockHit = p.pick(maxDistance, 1.0F, false);
        double blockDistSq = blockHit.getLocation().distanceToSqr(from);
        if (blockHit.getType() != HitResult.Type.MISS) {
            maxDistanceSq = blockDistSq;
            maxDistance = Math.sqrt(maxDistanceSq);
        }
        Vec3 dir = p.getViewVector(1.0F);
        Vec3 to = from.add(dir.x * maxDistance, dir.y * maxDistance, dir.z * maxDistance);
        AABB box = p.getBoundingBox().expandTowards(dir.scale(maxDistance)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHit =
                ProjectileUtil.getEntityHitResult(p, from, to, box, EntitySelector.CAN_BE_PICKED, maxDistanceSq);
        return entityHit != null && entityHit.getLocation().distanceToSqr(from) < blockDistSq
                ? filter(entityHit, from, entityRange)
                : filter(blockHit, from, blockRange);
    }

    private static HitResult filter(HitResult hit, Vec3 from, double maxRange) {
        Vec3 loc = hit.getLocation();
        if (loc.distanceToSqr(from) < maxRange * maxRange) return hit;
        Direction dir = Direction.getApproximateNearest(loc.x - from.x, loc.y - from.y, loc.z - from.z);
        return BlockHitResult.miss(loc, dir, BlockPos.containing(loc));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    //  Left click  (Minecraft#startAttack / #continueAttack)
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * A fresh attack press. Returns {@code true} when it ended instantly (block broke on the press),
     * which the client uses to suppress {@link #continueAttack} for that tick.
     *
     * @param breakPause ticks of "destroy delay" applied after a block finishes (vanilla: 5)
     */
    public boolean startAttack(ServerPlayer p, HitResult hit, int breakPause) {
        if (missTime > 0) return false;
        if (hit == null) {
            if (!p.gameMode.isCreative()) missTime = MISS_TIME;
            return false;
        }
        ItemStack held = p.getMainHandItem();
        if (!held.isItemEnabled(p.level().enabledFeatures())) return false;
        if (p.cannotAttackWithItem(held, 0)) return false;

        // Keep attribute modifiers (held weapon damage/speed) current, as the client's tick does.
        try {
            p.detectEquipmentUpdates();
        } catch (Throwable t) {
            Config.debugNms("BotClient.startAttack detectEquipmentUpdates failed: " + t.getMessage());
        }

        if (held.has(DataComponents.PIERCING_WEAPON)) {
            // Spear "stab" - the client sends the STAB action and swings.
            BotClickDispatcher.stab(p);
            BotClickDispatcher.punch(p);
            return true;
        }

        boolean endAttack = false;
        boolean miss = false;
        switch (hit.getType()) {
            case ENTITY -> {
                Entity target = ((EntityHitResult) hit).getEntity();
                AttackRange range = held.get(DataComponents.ATTACK_RANGE);
                if (isAttackable(p, target) && (range == null || range.isInRange(p, hit.getLocation()))) {
                    BotClickDispatcher.attack(p, target);
                    if (p.gameMode.isCreative()) destroyDelay = breakPause;
                }
            }
            case BLOCK -> {
                BlockHitResult bh = (BlockHitResult) hit;
                BlockPos pos = bh.getBlockPos();
                if (!p.level().getBlockState(pos).isAir()) {
                    startDestroyBlock(p, pos, bh.getDirection(), breakPause);
                    if (p.level().getBlockState(pos).isAir()) endAttack = true;
                } else {
                    miss = true;
                }
            }
            default -> miss = true;
        }
        if (miss) {
            if (!p.gameMode.isCreative()) missTime = MISS_TIME;
            p.resetAttackStrengthTicker();
        }
        BotClickDispatcher.punch(p);
        return endAttack;
    }

    /** Mirrors the server's own "invalid entity attacked" guard so a bot is never kicked for a click. */
    private static boolean isAttackable(ServerPlayer p, Entity target) {
        return target != p
                && !(target instanceof ItemEntity)
                && !(target instanceof ExperienceOrb)
                && !(target instanceof AbstractArrow arrow && !arrow.isAttackable());
    }

    /** {@code Minecraft#continueAttack}: advances / aborts the dig depending on key state and crosshair. */
    public void continueAttack(ServerPlayer p, HitResult hit, boolean down, int breakPause) {
        if (!down) missTime = 0;
        if (missTime > 0 || p.isUsingItem()) return;
        if (p.getMainHandItem().has(DataComponents.PIERCING_WEAPON)) return;

        if (down && hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bh = (BlockHitResult) hit;
            BlockPos pos = bh.getBlockPos();
            if (!p.level().getBlockState(pos).isAir()) {
                if (continueDestroyBlock(p, pos, bh.getDirection(), breakPause)) {
                    BotClickDispatcher.punch(p);
                }
                return;
            }
        }
        stopDestroyBlock(p);
    }

    /** End-of-tick bookkeeping from {@code Minecraft#tick}. */
    public void endTick() {
        if (missTime > 0) missTime--;
    }

    // ── MultiPlayerGameMode: destroy state machine ──────────────────────────────────────────

    private boolean sameDestroyTarget(ServerPlayer p, BlockPos pos) {
        return pos.equals(destroyBlockPos) && ItemStack.isSameItemSameComponents(p.getMainHandItem(), destroyingItem);
    }

    private boolean startDestroyBlock(ServerPlayer p, BlockPos pos, Direction dir, int breakPause) {
        if (p.blockActionRestricted(p.level(), pos, p.gameMode.getGameModeForPlayer())) return false;
        if (!p.level().getWorldBorder().isWithinBounds(pos)) return false;

        if (p.gameMode.isCreative()) {
            BotClickDispatcher.startDestroy(p, pos, dir);
            destroyDelay = breakPause;
        } else if (!isDestroying || !sameDestroyTarget(p, pos)) {
            destroyDelay = 0;
            if (isDestroying) {
                BotClickDispatcher.abortDestroy(p, destroyBlockPos);
            }
            // Tool pick happens before the dig starts, like a player scrolling the hotbar first.
            if (Config.autoToolSwitchEnabled()) {
                BotToolSelector.equipBestTool(p.getBukkitEntity(), pos);
            }
            BlockState state = p.level().getBlockState(pos);
            boolean instant = !state.isAir() && state.getDestroyProgress(p, p.level(), pos) >= 1.0F;
            if (!instant) {
                isDestroying = true;
                destroyBlockPos = pos;
                destroyDirection = dir;
                destroyingItem = p.getMainHandItem().copy();
                destroyProgress = 0.0F;
            }
            BotClickDispatcher.startDestroy(p, pos, dir);
        }
        return true;
    }

    private boolean continueDestroyBlock(ServerPlayer p, BlockPos pos, Direction dir, int breakPause) {
        if (destroyDelay > 0) {
            destroyDelay--;
            return true;
        }
        if (p.gameMode.isCreative() && p.level().getWorldBorder().isWithinBounds(pos)) {
            destroyDelay = breakPause;
            BotClickDispatcher.startDestroy(p, pos, dir);
            return true;
        }
        if (sameDestroyTarget(p, pos)) {
            BlockState state = p.level().getBlockState(pos);
            if (state.isAir()) {
                isDestroying = false;
                return false;
            }
            destroyProgress += state.getDestroyProgress(p, p.level(), pos);
            if (destroyProgress >= 1.0F) {
                isDestroying = false;
                BotClickDispatcher.stopDestroy(p, pos, dir);
                destroyDirection = null;
                destroyProgress = 0.0F;
                destroyDelay = breakPause;
            } else if (destroyDirection != dir) {
                destroyDirection = dir;
                BotClickDispatcher.changeDestroyDirection(p, pos, dir);
            }
            return true;
        }
        return startDestroyBlock(p, pos, dir, breakPause);
    }

    /** {@code MultiPlayerGameMode#stopDestroyBlock}: aborts the dig the server still thinks is running. */
    public void stopDestroyBlock(ServerPlayer p) {
        if (!isDestroying) return;
        BotClickDispatcher.abortDestroy(p, destroyBlockPos);
        isDestroying = false;
        destroyProgress = 0.0F;
        p.resetAttackStrengthTicker();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    //  Right click  (Minecraft#startUseItem)
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** What a single right-click attempt did, for the caller's own pacing. */
    public enum UseResult {
        /** Nothing in either hand reacted. */
        NONE,
        /** An interaction was consumed (entity, block or item). */
        CONSUMED
    }

    /**
     * One right-click press: entity interact → use-on-block → use-item, main hand first then off hand -
     * the same per-hand sequence and early-outs as {@code Minecraft#startUseItem}. Does nothing while a
     * block is being dug.
     *
     * @param delay value the use-delay is reset to (vanilla: 4)
     */
    public UseResult startUseItem(ServerPlayer p, HitResult hit, int delay) {
        if (isDestroying) return UseResult.NONE;
        rightClickDelay = delay;

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = p.getItemInHand(hand);
            if (!held.isItemEnabled(p.level().enabledFeatures())) return UseResult.NONE;

            if (hit != null) {
                switch (hit.getType()) {
                    case ENTITY -> {
                        EntityHitResult eh = (EntityHitResult) hit;
                        Entity entity = eh.getEntity();
                        if (!p.level().getWorldBorder().isWithinBounds(entity.blockPosition())) return UseResult.NONE;
                        if (p.isWithinEntityInteractionRange(entity, 0.0) && interactEntity(p, entity, eh, hand)) {
                            return UseResult.CONSUMED;
                        }
                    }
                    case BLOCK -> {
                        if (useItemOn(p, hand, (BlockHitResult) hit)) return UseResult.CONSUMED;
                    }
                    default -> {}
                }
            }

            if (!held.isEmpty() && useItem(p, hand)) return UseResult.CONSUMED;
        }
        return UseResult.NONE;
    }

    /** Pre-click snapshot used to infer the {@code InteractionResult} the void packet handlers hide. */
    private record Snapshot(
            boolean using,
            ItemStack main,
            ItemStack off,
            boolean riding,
            boolean sleeping,
            AbstractContainerMenu menu) {
        static Snapshot of(ServerPlayer p) {
            return new Snapshot(
                    p.isUsingItem(),
                    p.getMainHandItem().copy(),
                    p.getOffhandItem().copy(),
                    p.isPassenger(),
                    p.isSleeping(),
                    p.containerMenu);
        }

        /** True when the click observably did something. Closes any screen it opened (a bot can't use one). */
        boolean changed(ServerPlayer p) {
            if (!using && p.isUsingItem()) return true;
            if (!ItemStack.matches(main, p.getMainHandItem()) || !ItemStack.matches(off, p.getOffhandItem())) {
                return true;
            }
            if (riding != p.isPassenger() || sleeping != p.isSleeping()) return true;
            if (p.containerMenu != null && p.containerMenu != p.inventoryMenu) {
                try {
                    p.closeContainer();
                } catch (Throwable ignored) {
                }
                return true;
            }
            return false;
        }
    }

    private boolean interactEntity(ServerPlayer p, Entity entity, EntityHitResult hit, InteractionHand hand) {
        Snapshot before = Snapshot.of(p);
        Vec3 relative = hit.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
        BotClickDispatcher.interactEntity(p, entity, hand, relative, p.isShiftKeyDown());
        // The vanilla client hard-codes interactAt on a non-marker armor stand as SUCCESS, so the click
        // is always consumed there even when the server-side swap does nothing.
        boolean consumed = entity instanceof net.minecraft.world.entity.decoration.ArmorStand || before.changed(p);
        if (consumed) BotClickDispatcher.swing(p, hand);
        return consumed;
    }

    private boolean useItemOn(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!p.level().getWorldBorder().isWithinBounds(pos)) return false;
        BlockPos placePos = pos.relative(hit.getDirection());
        BlockState clickedBefore = p.level().getBlockState(pos);
        BlockState placeBefore = p.level().getBlockState(placePos);
        Snapshot before = Snapshot.of(p);

        BotClickDispatcher.useItemOn(p, hand, hit);

        boolean blockChanged =
                p.level().getBlockState(pos) != clickedBefore || p.level().getBlockState(placePos) != placeBefore;
        boolean consumed = blockChanged || before.changed(p);
        if (consumed) BotClickDispatcher.swing(p, hand);
        return consumed;
    }

    private boolean useItem(ServerPlayer p, InteractionHand hand) {
        Snapshot before = Snapshot.of(p);
        BotClickDispatcher.useItem(p, hand, p.getYRot(), p.getXRot());
        boolean consumed = before.changed(p);
        // Instant uses (pearls, eggs, snowballs…) swing; multi-tick uses (eat, draw, block) do not.
        if (consumed && !p.isUsingItem()) BotClickDispatcher.swing(p, hand);
        return consumed;
    }

    /** {@code MultiPlayerGameMode#releaseUsingItem}. */
    public static void releaseUsingItem(ServerPlayer p) {
        BotClickDispatcher.releaseUse(p);
    }

    /** Progress (0..1) of the dig in flight, or 0 when idle. */
    public float destroyProgress() {
        return isDestroying ? destroyProgress : 0.0F;
    }

    /**
     * One tick of "hold the attack key on whatever the crosshair is on": press when idle, keep the
     * button down otherwise, exactly the dig loop of a held left-click. Used by every system that mines
     * (find, clear, path-clearing), so they all dig with the client's own pacing.
     */
    public void holdAttack(ServerPlayer p, HitResult hit, int breakPause) {
        boolean instant = false;
        if (!isDestroying && hit != null && hit.getType() == HitResult.Type.BLOCK) {
            instant = startAttack(p, hit, breakPause);
        }
        continueAttack(p, hit, !instant, breakPause);
        endTick();
    }

    /** Drops the left-click side's in-flight state when its task is torn down. */
    public void resetAttack(ServerPlayer p) {
        stopDestroyBlock(p);
        missTime = 0;
    }
}
