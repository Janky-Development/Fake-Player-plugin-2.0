package me.bill.fakePlayerPlugin.fakeplayer;

import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Dispatches <em>authentic</em> serverbound interaction packets through the bot's real
 * {@link net.minecraft.server.network.ServerGamePacketListenerImpl} (the injected
 * {@link me.bill.fakePlayerPlugin.fakeplayer.network.FakeServerGamePacketListenerImpl}, which only
 * overrides the outbound {@code send(...)} - every inbound {@code handle*} method is the live server
 * implementation).
 *
 * <p>This makes the server perform each click exactly as it does for a real client: reach/anti-cheat
 * validation, Bukkit events ({@code PlayerInteractEvent}, {@code BlockBreakEvent},
 * {@code PlayerInteractEntityEvent}, …), item durability, hunger, use-timing and every item's native
 * behaviour - with zero per-item special-casing in the plugin. Nothing here mutates the world
 * directly; the server does all the work.
 */
public final class BotClickDispatcher {

    private BotClickDispatcher() {}

    /**
     * Per-bot monotonically increasing packet sequence number. The server acks these via a
     * clientbound packet that our fake connection harmlessly swallows, but the handlers still expect
     * a sane, non-decreasing value.
     */
    private static final ConcurrentHashMap<UUID, AtomicInteger> SEQUENCES = new ConcurrentHashMap<>();

    // ServerboundUseItemPacket gained (yRot,xRot) in 1.21.3+/26.x. Probe once, cache the arity.
    private static volatile Constructor<?> useItemCtor;
    private static volatile int useItemArity; // 4 = (hand,int,float,float), 2 = (hand,int)

    private static int nextSequence(ServerPlayer nms) {
        return SEQUENCES
                .computeIfAbsent(nms.getBukkitEntity().getUniqueId(), k -> new AtomicInteger())
                .incrementAndGet();
    }

    /** Discards the cached sequence for a bot (call on despawn to avoid a slow leak). */
    public static void forget(UUID botUuid) {
        SEQUENCES.remove(botUuid);
    }

    // ── Left-click: block breaking ──────────────────────────────────────────────────────────────

    /**
     * Begin (or, for instant-break blocks, complete) destruction of {@code pos}. The server drives
     * subsequent progress in {@code ServerPlayerGameMode.tick()}; the caller sends {@link #stopDestroy}
     * when its client-faithful progress predictor says the block is done.
     */
    public static void startDestroy(ServerPlayer nms, BlockPos pos, Direction face) {
        dispatch(
                nms,
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, nextSequence(nms)));
    }

    /** Finish destroying {@code pos} - routes through the real {@code handlePlayerAction} destroy path. */
    public static void stopDestroy(ServerPlayer nms, BlockPos pos, Direction face) {
        dispatch(
                nms,
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, nextSequence(nms)));
    }

    /** Cancel an in-progress dig (clears server-side crack progress). */
    public static void abortDestroy(ServerPlayer nms, BlockPos pos) {
        dispatch(
                nms,
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        pos,
                        Direction.DOWN,
                        nextSequence(nms)));
    }

    // ── Swing / punch ───────────────────────────────────────────────────────────────────────────

    // 26.3 replaced ServerboundSwingPacket with the argument-less ServerboundPunchPacket (left-click
    // only; use-swings became server-driven). Probe once so the same jar works on both protocols.
    private static volatile boolean swingProbed;
    private static volatile Constructor<?> swingCtor;
    private static volatile Object punchInstance;

    private static void probeSwing() {
        if (swingProbed) return;
        synchronized (BotClickDispatcher.class) {
            if (swingProbed) return;
            try {
                Class<?> punch = Class.forName("net.minecraft.network.protocol.game.ServerboundPunchPacket");
                punchInstance = punch.getField("INSTANCE").get(null);
            } catch (Throwable ignored) {
                // pre-26.3 protocol - fall back to the swing packet below
            }
            try {
                Class<?> swing = Class.forName("net.minecraft.network.protocol.game.ServerboundSwingPacket");
                swingCtor = swing.getConstructor(InteractionHand.class);
            } catch (Throwable ignored) {
                // 26.3+: no swing packet exists
            }
            swingProbed = true;
        }
    }

    /**
     * The left-click arm swing a real client sends with every attack / dig tick: the dedicated punch
     * packet where the protocol has one, else a main-hand swing.
     */
    @SuppressWarnings("unchecked")
    public static void punch(ServerPlayer nms) {
        probeSwing();
        if (punchInstance != null) {
            dispatch(nms, (Packet<ServerGamePacketListener>) punchInstance);
        } else {
            swing(nms, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * A client-sourced arm swing after a successful interaction. Only meaningful on protocols that still
     * have a swing packet; on 26.3+ the server swings for use-actions itself.
     */
    @SuppressWarnings("unchecked")
    public static void swing(ServerPlayer nms, InteractionHand hand) {
        probeSwing();
        if (swingCtor == null) return;
        try {
            dispatch(nms, (Packet<ServerGamePacketListener>) swingCtor.newInstance(hand));
        } catch (Throwable t) {
            Config.debugNms("BotClickDispatcher.swing failed: " + t.getMessage());
        }
    }

    /** Spear stab ({@code Action.STAB}). */
    public static void stab(ServerPlayer nms) {
        dispatch(
                nms,
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN, 0));
    }

    /** Releases a held use-item (bow, shield, food…) - the client's key-up while {@code isUsingItem}. */
    public static void releaseUse(ServerPlayer nms) {
        dispatch(
                nms,
                new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN, 0));
    }

    /** Re-faces an in-progress dig; the action only exists on newer protocols, so it is looked up by name. */
    public static void changeDestroyDirection(ServerPlayer nms, BlockPos pos, Direction face) {
        try {
            ServerboundPlayerActionPacket.Action action =
                    ServerboundPlayerActionPacket.Action.valueOf("CHANGE_DESTROY_DIRECTION");
            dispatch(nms, new ServerboundPlayerActionPacket(action, pos, face, 0));
        } catch (IllegalArgumentException ignored) {
            // protocol predates the action - the server re-derives the face from the next tick anyway
        }
    }

    // ── Left-click: entity attack ───────────────────────────────────────────────────────────────

    public static void attack(ServerPlayer nms, net.minecraft.world.entity.Entity target) {
        dispatch(nms, new ServerboundAttackPacket(target.getId()));
    }

    // ── Right-click: entity interact ────────────────────────────────────────────────────────────

    /**
     * Right-click an entity exactly like a real client's interact packet. {@code relativeHit} is the
     * hit point relative to the entity's position (what the vanilla packet carries) so armor-stand
     * equipping, feeding, taming, breeding, mounting, leashing and trading behave as for a real player.
     */
    public static void interactEntity(
            ServerPlayer nms,
            net.minecraft.world.entity.Entity target,
            InteractionHand hand,
            @Nullable Vec3 relativeHit,
            boolean sneaking) {
        dispatch(
                nms,
                new ServerboundInteractPacket(
                        target.getId(), hand, relativeHit != null ? relativeHit : Vec3.ZERO, sneaking));
    }

    // ── Right-click: use item on a block ────────────────────────────────────────────────────────

    public static void useItemOn(ServerPlayer nms, InteractionHand hand, BlockHitResult hit) {
        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(hand, hit, nextSequence(nms));
        stampTimestamp(packet);
        dispatch(nms, packet);
    }

    // ── Right-click: use item in hand (eat/drink/bow/shield/throw/…) ─────────────────────────────

    public static void useItem(ServerPlayer nms, InteractionHand hand, float yRot, float xRot) {
        try {
            ServerboundUseItemPacket packet = buildUseItemPacket(hand, nextSequence(nms), yRot, xRot);
            if (packet != null) {
                stampTimestamp(packet);
                dispatch(nms, packet);
            }
        } catch (Throwable t) {
            Config.debugNms("BotClickDispatcher.useItem failed: " + t.getMessage());
        }
    }

    /**
     * Sets the Spigot-added {@code public long timestamp} field on use packets. The network-read
     * constructor stamps it with {@code System.currentTimeMillis()}, but our directly-constructed
     * packets leave it {@code 0} - and {@code handleUseItem}/{@code handleUseItemOn} feed it into
     * Paper's incoming-packet spam limiter ({@code checkLimit}), where a constant 0 looks like "no
     * time passed since the last packet" and permanently drops every use packet after the first few.
     * Reflection keeps this version-tolerant: if the field is ever removed, stamping is a no-op.
     */
    private static volatile java.lang.reflect.Field useItemTimestampField;

    private static volatile java.lang.reflect.Field useItemOnTimestampField;
    private static volatile boolean timestampProbed;

    private static void stampTimestamp(Object packet) {
        if (!timestampProbed) {
            synchronized (BotClickDispatcher.class) {
                if (!timestampProbed) {
                    useItemTimestampField = findTimestampField(ServerboundUseItemPacket.class);
                    useItemOnTimestampField = findTimestampField(ServerboundUseItemOnPacket.class);
                    timestampProbed = true;
                }
            }
        }
        try {
            if (packet instanceof ServerboundUseItemPacket && useItemTimestampField != null) {
                useItemTimestampField.setLong(packet, System.currentTimeMillis());
            } else if (packet instanceof ServerboundUseItemOnPacket && useItemOnTimestampField != null) {
                useItemOnTimestampField.setLong(packet, System.currentTimeMillis());
            }
        } catch (Exception e) {
            Config.debugNms("BotClickDispatcher.stampTimestamp failed: " + e.getMessage());
        }
    }

    @Nullable
    private static java.lang.reflect.Field findTimestampField(Class<?> packetClass) {
        try {
            java.lang.reflect.Field f = packetClass.getField("timestamp");
            if (f.getType() == long.class) return f;
        } catch (NoSuchFieldException ignored) {
            Config.debugNms("BotClickDispatcher: no Spigot timestamp field on " + packetClass.getSimpleName());
        }
        return null;
    }

    @Nullable
    private static ServerboundUseItemPacket buildUseItemPacket(InteractionHand hand, int seq, float yRot, float xRot)
            throws Exception {
        if (useItemCtor == null) {
            synchronized (BotClickDispatcher.class) {
                if (useItemCtor == null) {
                    try {
                        useItemCtor = ServerboundUseItemPacket.class.getConstructor(
                                InteractionHand.class, int.class, float.class, float.class);
                        useItemArity = 4;
                    } catch (NoSuchMethodException e) {
                        useItemCtor = ServerboundUseItemPacket.class.getConstructor(InteractionHand.class, int.class);
                        useItemArity = 2;
                    }
                    Config.debugNms("BotClickDispatcher: ServerboundUseItemPacket arity = " + useItemArity);
                }
            }
        }
        return (ServerboundUseItemPacket)
                (useItemArity == 4
                        ? useItemCtor.newInstance(hand, seq, yRot, xRot)
                        : useItemCtor.newInstance(hand, seq));
    }

    // ── teleport confirmation ───────────────────────────────────────────────────────────────────

    private static volatile java.lang.reflect.Field awaitingPosField;
    private static volatile java.lang.reflect.Field awaitingTeleportIdField;
    private static volatile boolean teleportFieldsProbed;

    /**
     * Confirms any pending server→client teleport exactly like a real client: dispatches a
     * {@code ServerboundAcceptTeleportationPacket} with the pending id, which snaps the player to the
     * awaited position and clears {@code awaitingPositionFromClient} (the gate that otherwise blocks
     * every {@code handleUseItemOn} interaction).
     */
    private static void ensureTeleportAccepted(ServerPlayer nms) {
        try {
            if (!teleportFieldsProbed) {
                synchronized (BotClickDispatcher.class) {
                    if (!teleportFieldsProbed) {
                        Class<?> listener = net.minecraft.server.network.ServerGamePacketListenerImpl.class;
                        awaitingPosField = findDeclaredField(listener, "awaitingPositionFromClient");
                        awaitingTeleportIdField = findDeclaredField(listener, "awaitingTeleport");
                        teleportFieldsProbed = true;
                    }
                }
            }
            if (awaitingPosField == null || awaitingTeleportIdField == null) return;
            if (awaitingPosField.get(nms.connection) == null) return;
            int pendingId = awaitingTeleportIdField.getInt(nms.connection);
            new ServerboundAcceptTeleportationPacket(pendingId).handle(nms.connection);
            Config.debugNms("BotClickDispatcher: confirmed pending teleport id=" + pendingId + " for "
                    + nms.getScoreboardName());
        } catch (Throwable t) {
            Config.debugNms("BotClickDispatcher.ensureTeleportAccepted failed: " + t.getMessage());
        }
    }

    @Nullable
    private static java.lang.reflect.Field findDeclaredField(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            Config.debugNms("BotClickDispatcher: field " + name + " not found on " + owner.getSimpleName());
            return null;
        }
    }

    // ── dispatch ────────────────────────────────────────────────────────────────────────────────

    private static void dispatch(ServerPlayer nms, Packet<ServerGamePacketListener> packet) {
        try {
            // Every interaction handler is gated behind ServerGamePacketListenerImpl#hasClientLoaded().
            // A real client clears that gate by sending ServerboundPlayerLoadedPacket once it finishes
            // loading; a bot never does, so its handlers would silently no-op. Send that same packet
            // (idempotent - the gate flips true after the first call) so the server treats the bot as a
            // fully-loaded client. Skipped while waiting-for-respawn, which is correct.
            if (!nms.connection.hasClientLoaded()) {
                new ServerboundPlayerLoadedPacket().handle(nms.connection);
            }
            // Every teleport/rotation done through the connection (bot spawn, /fpp tp, action locks,
            // CraftPlayer#setRotation) arms awaitingPositionFromClient, and handleUseItemOn refuses ALL
            // block interactions until the client confirms with an accept-teleport packet. A real
            // client confirms instantly; the bot must do the same or every lever/bone-meal/placement
            // silently no-ops forever.
            ensureTeleportAccepted(nms);
            nms.resetLastActionTime();
            packet.handle(nms.connection);
        } catch (Throwable t) {
            Config.debugNms("BotClickDispatcher.dispatch failed for "
                    + packet.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
