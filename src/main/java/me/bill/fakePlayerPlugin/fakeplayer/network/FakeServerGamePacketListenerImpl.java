package me.bill.fakePlayerPlugin.fakeplayer.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Replaces the vanilla packet listener on a fake player's {@code ServerPlayer.connection} field -
 * see {@link #applyKnockback} for the knockback interception, and {@link #listen} below for the
 * one other thing this class does: {@code send} below completely replaces the vanilla method (no
 * {@code super.send(...)} call at all), so literally everything except a knockback packet is
 * otherwise silently dropped with zero trace, including any message a plugin sends the bot - e.g.
 * an installed login plugin's response to a bot's register/login attempt ("wrong password",
 * "welcome back", ...), which {@code auth.BotAuthManager} would otherwise have no way to see.
 * Captured from whichever of the several ways a plugin might actually display that: a plain chat
 * line ({@code ClientboundSystemChatPacket}, from {@code Player#sendMessage}), or a
 * title/subtitle/action-bar ({@code ClientboundSetTitleTextPacket}/{@code
 * ClientboundSetSubtitleTextPacket}/{@code ClientboundSetActionBarTextPacket}) - OpeNLogin
 * specifically uses the title/subtitle UI for its login prompts and responses, not chat. {@link
 * #listen} taps all four before they're dropped, for whoever - currently only {@code
 * BotAuthManager} - registers for a given UUID.
 */
public final class FakeServerGamePacketListenerImpl extends ServerGamePacketListenerImpl {

    // UUID -> callback, populated/cleared entirely by the registrant (BotAuthManager) - see
    // #listen/#stopListening. A plain Consumer<String> rather than anything Bukkit-typed, so this
    // NMS-facing class stays free of any dependency on the rest of the plugin.
    private static final Map<UUID, Consumer<String>> MESSAGE_LISTENERS = new ConcurrentHashMap<>();

    /**
     * Delivers a plain-text copy of every {@code ClientboundSystemChatPacket} (or title/subtitle/
     * action-bar packet - see class doc) this connection would otherwise silently drop for {@code
     * uuid} to {@code onMessage}, until {@link #stopListening} is called for the same UUID.
     * There's deliberately no automatic expiry here - timing/timeout policy belongs entirely to
     * the caller.
     */
    public static void listen(UUID uuid, Consumer<String> onMessage) {
        if (uuid != null && onMessage != null) MESSAGE_LISTENERS.put(uuid, onMessage);
    }

    public static void stopListening(UUID uuid) {
        if (uuid != null) MESSAGE_LISTENERS.remove(uuid);
    }

    private void notifyListener(Packet<?> packet) {
        if (MESSAGE_LISTENERS.isEmpty()) return; // the overwhelmingly common case - skip the map lookup entirely
        try {
            UUID uuid = this.player.getUUID();
            Consumer<String> listener = MESSAGE_LISTENERS.get(uuid);
            if (listener == null) return;
            String text = extractText(packet);
            if (text != null && !text.isBlank()) listener.accept(text);
        } catch (Throwable ignored) {
            // A packet-internals mismatch on some server version must never affect this
            // connection's actual job (dropping the packet) - see class doc.
        }
    }

    /** Plain text out of whichever of chat/title/subtitle/action-bar {@code packet} is, or {@code null} for anything else - see class doc for why all four are read, not just chat. */
    private static String extractText(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket p) return p.content().getString();
        if (packet instanceof ClientboundSetTitleTextPacket p) return p.text().getString();
        if (packet instanceof ClientboundSetSubtitleTextPacket p)
            return p.text().getString();
        if (packet instanceof ClientboundSetActionBarTextPacket p)
            return p.text().getString();
        return null;
    }

    private static volatile boolean entityIdFallbackResolved = false;

    private static volatile Method cachedEntityIdMethod = null;

    private static int resolveEntityId(ClientboundSetEntityMotionPacket packet) {

        if (!entityIdFallbackResolved) {
            synchronized (FakeServerGamePacketListenerImpl.class) {
                if (!entityIdFallbackResolved) {
                    for (String name : new String[] {"id", "getId", "getEntityId"}) {
                        try {
                            Method m = ClientboundSetEntityMotionPacket.class.getMethod(name);
                            if (m.getReturnType() == int.class) {
                                m.setAccessible(true);
                                cachedEntityIdMethod = m;
                                Config.debugNms("FakePacketListener: entity-ID getter resolved → " + name + "()");
                                break;
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                    if (cachedEntityIdMethod == null) {
                        Config.debugNms("FakePacketListener: entity-ID getter not found - ID check skipped"
                                + " (packet arrived on this entity's own connection)");
                    }
                    entityIdFallbackResolved = true;
                }
            }
        }
        if (cachedEntityIdMethod == null) return -1;
        try {
            return (int) cachedEntityIdMethod.invoke(packet);
        } catch (Exception e) {
            return -1;
        }
    }

    private enum KbStrategy {
        UNRESOLVED,
        GET_MOVEMENT,
        GET_XA,
        FIELD_SCAN,
        NONE
    }

    private static volatile KbStrategy kbStrategy = KbStrategy.UNRESOLVED;

    private static Method getMovementMethod;
    private static Method lerpMotionVec3Method;

    private static Method getXaMethod, getYaMethod, getZaMethod;
    private static Method lerpMotion3Method;
    private static Method setDeltaMovementMethod;
    private static Class<?> vec3Class;
    private static Field hasImpulseField;

    private static Field scanXField, scanYField, scanZField;

    public FakeServerGamePacketListenerImpl(
            MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    public static FakeServerGamePacketListenerImpl create(
            Object server, Object connection, Object player, Object cookie) {
        return new FakeServerGamePacketListenerImpl(
                (MinecraftServer) server, (Connection) connection, (ServerPlayer) player, (CommonListenerCookie)
                        cookie);
    }

    @Override
    public void onDisconnect(@NotNull DisconnectionDetails details) {
        String playerName = this.player.getScoreboardName();
        String reasonStr = details.reason() != null ? details.reason().getString() : "null";
        Config.debugNmsConn("onDisconnect: player='" + playerName + "' reason='" + reasonStr + "'");
        try {
            super.onDisconnect(details);
            Config.debugNmsConn("onDisconnect completed for '" + playerName + "'");
        } catch (IllegalStateException e) {
            if ("Already retired".equals(e.getMessage())) {
                Config.debugNms("FakeServerGamePacketListenerImpl: suppressed double-retirement for "
                        + playerName
                        + " (entity scheduler already retired by death path)");
            } else {
                FppLogger.error(
                        "onDisconnect failed for '" + playerName + "' (reason: " + reasonStr + "): " + e.getMessage());
                throw e;
            }
        }
    }

    @Override
    public void send(Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            applyKnockback(motionPacket);
        }
        notifyListener(packet);
    }

    @SuppressWarnings("unused")
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            applyKnockback(motionPacket);
        }
        notifyListener(packet);
    }

    private void applyKnockback(ClientboundSetEntityMotionPacket packet) {
        if (!Config.bodyPushable()) return;
        if (NmsPlayerSpawner.isFoliaServer()) return;

        int packetEntityId = resolveEntityId(packet);
        int myId = this.player.getId();
        if (packetEntityId != -1 && packetEntityId != myId) return;

        // Cancelled damage should suppress packet knockback, matching normal player behavior.
        try {
            Player bukkit = this.player.getBukkitEntity() instanceof Player p ? p : null;
            if (bukkit != null) {
                var last = bukkit.getLastDamageCause();
                if (last instanceof EntityDamageByEntityEvent byEntity) {
                    Entity attacker = resolveKnockbackSource(byEntity.getDamager());
                    if (attacker instanceof Player && byEntity.isCancelled()) {
                        Config.debugNms("[KB-DEBUG] packet knockback blocked after cancelled player damage for bot="
                                + bukkit.getName());
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            KbStrategy strategy = resolveStrategy();
            switch (strategy) {
                case GET_MOVEMENT -> {
                    Object movement = getMovementMethod.invoke(packet);
                    lerpMotionVec3Method.invoke(this.player, movement);
                    markVelocityChanged();
                }
                case GET_XA -> {
                    double xa = toMotionComponent(getXaMethod.invoke(packet));
                    double ya = toMotionComponent(getYaMethod.invoke(packet));
                    double za = toMotionComponent(getZaMethod.invoke(packet));
                    if (lerpMotion3Method != null) {
                        lerpMotion3Method.invoke(this.player, xa, ya, za);
                    } else {
                        Object v = vec3Class
                                .getConstructor(double.class, double.class, double.class)
                                .newInstance(xa, ya, za);
                        setDeltaMovementMethod.invoke(this.player, v);
                    }
                    markVelocityChanged();
                }
                case FIELD_SCAN -> {
                    double fx = toMotionComponent(scanXField.get(packet));
                    double fy = toMotionComponent(scanYField.get(packet));
                    double fz = toMotionComponent(scanZField.get(packet));
                    if (lerpMotion3Method != null) {
                        lerpMotion3Method.invoke(this.player, fx, fy, fz);
                    } else if (setDeltaMovementMethod != null && vec3Class != null) {
                        Object v = vec3Class
                                .getConstructor(double.class, double.class, double.class)
                                .newInstance(fx, fy, fz);
                        setDeltaMovementMethod.invoke(this.player, v);
                    }
                    markVelocityChanged();
                }
                case NONE, UNRESOLVED -> {}
            }
        } catch (Exception e) {
            FppLogger.warn("Knockback apply failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Entity resolveKnockbackSource(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) return shooterEntity;
        }
        return damager;
    }

    private void markVelocityChanged() {
        this.player.hurtMarked = true;
        try {
            if (hasImpulseField == null) {
                hasImpulseField = findField(ServerPlayer.class, "hasImpulse", boolean.class);
            }
            if (hasImpulseField != null) {
                hasImpulseField.setBoolean(this.player, true);
            }
        } catch (Exception e) {
            Config.debugNms("[KB-DEBUG] could not set hasImpulse: " + e.getMessage());
        }
    }

    private static double toMotionComponent(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Unsupported motion component type: " + value);
        }
        if (value instanceof Double || value instanceof Float) {
            return number.doubleValue();
        }
        return number.doubleValue() / 8000.0D;
    }

    private static synchronized KbStrategy resolveStrategy() {
        if (kbStrategy != KbStrategy.UNRESOLVED) return kbStrategy;

        try {
            vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
        } catch (ClassNotFoundException e) {
            Config.debugNms("FakePacketListener: Vec3 class not found: " + e.getMessage());
        }

        try {
            Method gm = ClientboundSetEntityMotionPacket.class.getMethod("getMovement");
            Class<?> returnType = gm.getReturnType();

            Method lm = vec3Class != null ? findMethod(ServerPlayer.class, "setDeltaMovement", vec3Class) : null;
            if (lm == null) lm = findLerpMotionVec3(returnType);
            if (lm != null) {
                getMovementMethod = gm;
                lerpMotionVec3Method = lm;
                Config.debugNms("FakePacketListener: knockback strategy → GET_MOVEMENT"
                        + " (apply="
                        + lm.getName()
                        + "("
                        + returnType.getSimpleName()
                        + "))");
                return kbStrategy = KbStrategy.GET_MOVEMENT;
            }

            getMovementMethod = gm;
            Config.debugNms("FakePacketListener: getMovement() found but no Vec3 apply method;"
                    + " will try GET_XA / FIELD_SCAN");
        } catch (NoSuchMethodException ignored) {

        } catch (Exception e) {
            Config.debugNms("FakePacketListener: GET_MOVEMENT probe failed: " + e.getMessage());
        }

        Method xa = probeMethod(ClientboundSetEntityMotionPacket.class, "getXa", "xa");
        Method ya = probeMethod(ClientboundSetEntityMotionPacket.class, "getYa", "ya");
        Method za = probeMethod(ClientboundSetEntityMotionPacket.class, "getZa", "za");
        if (xa != null && ya != null && za != null) {
            getXaMethod = xa;
            getYaMethod = ya;
            getZaMethod = za;

            lerpMotion3Method = findMethod(ServerPlayer.class, "lerpMotion", double.class, double.class, double.class);
            if (lerpMotion3Method == null && vec3Class != null) {

                setDeltaMovementMethod = findMethod(ServerPlayer.class, "setDeltaMovement", vec3Class);
            }
            Config.debugNms("FakePacketListener: knockback strategy → GET_XA"
                    + " (lerpMotion3="
                    + (lerpMotion3Method != null)
                    + ", setDelta="
                    + (setDeltaMovementMethod != null)
                    + ")");
            return kbStrategy = KbStrategy.GET_XA;
        }

        setDeltaMovementMethod =
                vec3Class != null ? findMethod(ServerPlayer.class, "setDeltaMovement", vec3Class) : null;
        lerpMotion3Method = findMethod(ServerPlayer.class, "lerpMotion", double.class, double.class, double.class);

        if (setDeltaMovementMethod != null || lerpMotion3Method != null) {

            String[][] nameTriplets = {
                {"xa", "ya", "za"},
                {"xd", "yd", "zd"},
                {"motX", "motY", "motZ"},
            };
            for (String[] triplet : nameTriplets) {
                Field fx = findDeclaredNumericField(ClientboundSetEntityMotionPacket.class, triplet[0]);
                Field fy = findDeclaredNumericField(ClientboundSetEntityMotionPacket.class, triplet[1]);
                Field fz = findDeclaredNumericField(ClientboundSetEntityMotionPacket.class, triplet[2]);
                if (fx != null && fy != null && fz != null) {
                    scanXField = fx;
                    scanYField = fy;
                    scanZField = fz;
                    Config.debugNms("FakePacketListener: knockback strategy → FIELD_SCAN"
                            + " fields=["
                            + triplet[0]
                            + ","
                            + triplet[1]
                            + ","
                            + triplet[2]
                            + "]"
                            + " applyLerp3="
                            + (lerpMotion3Method != null)
                            + " applySetDelta="
                            + (setDeltaMovementMethod != null));
                    return kbStrategy = KbStrategy.FIELD_SCAN;
                }
            }

            List<Field> doubleFields = new ArrayList<>();
            for (Field f : ClientboundSetEntityMotionPacket.class.getDeclaredFields()) {
                if (isNumericType(f.getType())) {
                    f.setAccessible(true);
                    doubleFields.add(f);
                }
            }
            if (doubleFields.size() >= 3) {
                scanXField = doubleFields.get(0);
                scanYField = doubleFields.get(1);
                scanZField = doubleFields.get(2);
                Config.debugNms("FakePacketListener: knockback strategy → FIELD_SCAN"
                        + " (positional double fields: "
                        + scanXField.getName()
                        + ","
                        + scanYField.getName()
                        + ","
                        + scanZField.getName()
                        + ")");
                return kbStrategy = KbStrategy.FIELD_SCAN;
            }
        }

        Config.debugNms("FakePacketListener: knockback strategy = NONE (no compatible MC API found)");
        return kbStrategy = KbStrategy.NONE;
    }

    private static Method findLerpMotionVec3(Class<?> vec3Type) {
        Class<?> cur = ServerPlayer.class;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if ("lerpMotion".equals(m.getName())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(vec3Type)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method probeMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Method m = cur.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name, Class<?> type) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Field findDeclaredNumericField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getDeclaredField(name);
            if (isNumericType(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }

    private static boolean isNumericType(Class<?> type) {
        return type == double.class
                || type == float.class
                || type == int.class
                || type == long.class
                || type == short.class
                || type == byte.class;
    }
}
