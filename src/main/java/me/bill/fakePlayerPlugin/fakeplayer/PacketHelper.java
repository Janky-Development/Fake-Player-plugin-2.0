package me.bill.fakePlayerPlugin.fakeplayer;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.network.ViewerPacketGuard;
import me.bill.fakePlayerPlugin.perf.ProfilerToken;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

@SuppressWarnings("unused")
public final class PacketHelper {

    private PacketHelper() {}

    private static volatile boolean ready = false;
    private static volatile boolean broken = false;
    private static final Map<UUID, ProfileCacheEntry> profileCache = new ConcurrentHashMap<>();
    private static final Map<UUID, TabAddPacketCacheEntry> tabAddPacketCache = new ConcurrentHashMap<>();

    private static Class<?> craftPlayerClass;
    private static Class<?> gameProfileClass;
    private static Class<?> playerInfoUpdatePacketClass;
    private static Class<?> playerInfoUpdateActionClass;
    private static Class<?> playerInfoUpdateEntryClass;
    private static Class<?> playerInfoRemovePacketClass;
    private static Class<?> addEntityPacketClass;
    private static Class<?> removeEntitiesPacketClass;
    private static Class<?> moveEntityRotPacketClass;
    private static Class<?> rotateHeadPacketClass;
    private static Class<?> vec3Class;
    private static Class<?> entityTypeClass;
    private static Object vec3Zero;
    private static Object gameTypeSurvival;
    private static Object entityTypePlayer;

    private static volatile Constructor<?> cachedAddEntityCtor = null;
    private static volatile Constructor<?> cachedRemoveEntitiesCtor = null;
    private static volatile Constructor<?> cachedTeleportCtor = null;
    private static volatile Class<?> cachedTeleportPacketClass = null;
    private static volatile Constructor<?> cachedHurtAnimationCtor = null;
    private static volatile Constructor<?> cachedEntityEventCtor = null;
    private static volatile Constructor<?> cachedAnimateCtor = null;

    private static volatile Constructor<?> cachedPosSyncCtor = null;

    private static volatile boolean posSyncUsesEntityArg = false;

    private static volatile boolean posSyncCtorLookupDone = false;

    private static volatile Constructor<?> cachedMoveEntityRotCtor = null;

    private static volatile Constructor<?> cachedRotateHeadCtorInt = null;

    private static volatile Constructor<?> cachedRotateHeadCtorEntity = null;

    private static volatile boolean rotCtorLookupDone = false;

    private static volatile Object cachedUpdateDisplayNameActions = null;

    private static volatile Object cachedUpdateLatencyActions = null;

    private static volatile Object cachedUpdateListedActions = null;

    private static volatile Object cachedUpdateDisplayLatencyListedActions = null;

    private static volatile Constructor<?> cachedEntryCtorWinner = null;

    private static volatile Class<?>[] cachedEntryCtorParamTypes = null;

    private static volatile int cachedInfoUpdateSecondArgStrategy = 0;

    private static volatile Class<?> cachedInfoUpdateArrayCompType = null;

    private static Constructor<?> gameProfileCtor;
    private static Method componentLiteral;
    private static Method craftPlayerGetHandle;
    private static Constructor<?> playerInfoUpdateCtor;

    private static Method paperAdventureAsVanilla;

    private static volatile Field cachedConnectionField;

    private static volatile Method cachedSendMethod;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized void init() {
        if (ready || broken) return;
        try {
            craftPlayerClass = getCraftPlayerClass();
            for (Method m : craftPlayerClass.getDeclaredMethods()) {
                if (m.getName().equals("getHandle") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    craftPlayerGetHandle = m;
                    break;
                }
            }

            ClassLoader nmsLoader = findNmsClassLoader();
            if (nmsLoader == null)
                throw new IllegalStateException("Cannot find NMS classloader - join the server first.");

            gameProfileClass = nmsLoader.loadClass("com.mojang.authlib.GameProfile");
            for (Constructor<?> c : gameProfileClass.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 2 && pt[0] == UUID.class && pt[1] == String.class) {
                    c.setAccessible(true);
                    gameProfileCtor = c;
                    break;
                }
            }

            String pkg = "net.minecraft.network.protocol.game.";
            playerInfoUpdatePacketClass = nmsLoader.loadClass(pkg + "ClientboundPlayerInfoUpdatePacket");
            playerInfoUpdateActionClass = nmsLoader.loadClass(pkg + "ClientboundPlayerInfoUpdatePacket$Action");
            playerInfoUpdateEntryClass = nmsLoader.loadClass(pkg + "ClientboundPlayerInfoUpdatePacket$Entry");
            playerInfoRemovePacketClass = nmsLoader.loadClass(pkg + "ClientboundPlayerInfoRemovePacket");
            addEntityPacketClass = nmsLoader.loadClass(pkg + "ClientboundAddEntityPacket");
            removeEntitiesPacketClass = nmsLoader.loadClass(pkg + "ClientboundRemoveEntitiesPacket");
            moveEntityRotPacketClass = nmsLoader.loadClass(pkg + "ClientboundMoveEntityPacket$Rot");
            rotateHeadPacketClass = nmsLoader.loadClass(pkg + "ClientboundRotateHeadPacket");

            Class<?> gameTypeClass = nmsLoader.loadClass("net.minecraft.world.level.GameType");
            gameTypeSurvival = Enum.valueOf((Class<? extends Enum>) gameTypeClass, "SURVIVAL");

            Class<?> componentCls = nmsLoader.loadClass("net.minecraft.network.chat.Component");
            for (Method m : componentCls.getDeclaredMethods()) {
                if (m.getName().equals("literal")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    componentLiteral = m;
                    break;
                }
            }

            vec3Class = nmsLoader.loadClass("net.minecraft.world.phys.Vec3");
            vec3Zero = scanStaticField(vec3Class, "ZERO");

            entityTypeClass = nmsLoader.loadClass("net.minecraft.world.entity.EntityType");
            try {
                entityTypePlayer = scanStaticField(entityTypeClass, "PLAYER");
            } catch (NoSuchFieldException e) {
                // 26.3 moved the entity type constants out of EntityType into EntityTypes
                entityTypePlayer = scanStaticField(nmsLoader.loadClass("net.minecraft.world.entity.EntityTypes"), "PLAYER");
            }

            playerInfoUpdateCtor = findPlayerInfoUpdateCtor();
            if (playerInfoUpdateCtor == null)
                throw new IllegalStateException("Cannot find ClientboundPlayerInfoUpdatePacket constructor.");

            try {
                Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
                paperAdventureAsVanilla = paperAdventure.getDeclaredMethod("asVanilla", Component.class);
                paperAdventureAsVanilla.setAccessible(true);
                Config.debugPackets("PaperAdventure.asVanilla found - colored tablist names supported.");
            } catch (Exception ex) {
                Config.debugPackets(
                        "PaperAdventure.asVanilla not found, will fall back to literal: " + ex.getMessage());
            }

            Config.debugPackets("PacketHelper ready.");
            ready = true;
        } catch (Exception e) {
            broken = true;
            FppLogger.warn("PacketHelper init failed: " + e.getMessage());
            if (Config.debugPackets()) FppLogger.warn("  → " + e);
        }
    }

    private static ClassLoader findNmsClassLoader() {
        return NmsHelper.findNmsClassLoader();
    }

    private static Constructor<?> findPlayerInfoUpdateCtor() {
        Constructor<?> fallback = null;
        for (Constructor<?> c : playerInfoUpdatePacketClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == EnumSet.class) {
                c.setAccessible(true);
                if (p[1] == playerInfoUpdateEntryClass || p[1].isArray() || isEntryCollectionParameter(c)) {
                    Config.debugPackets("PlayerInfoUpdatePacket ctor: second param = " + p[1].getName());
                    return c;
                }
                if (fallback == null) fallback = c;
            }
        }
        if (fallback != null) {
            Config.debugPackets("PlayerInfoUpdatePacket ctor fallback: second param = "
                    + fallback.getParameterTypes()[1].getName());
        }
        return fallback;
    }

    private static boolean isEntryCollectionParameter(Constructor<?> ctor) {
        Class<?> rawType = ctor.getParameterTypes()[1];
        if (rawType != List.class && rawType != Collection.class) return false;
        Type genericType = ctor.getGenericParameterTypes()[1];
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return rawType == List.class;
        }
        Type[] args = parameterizedType.getActualTypeArguments();
        if (args.length != 1) return false;
        Type arg = args[0];
        if (arg == playerInfoUpdateEntryClass) return true;
        return arg.getTypeName().equals(playerInfoUpdateEntryClass.getName());
    }

    private static boolean ensureReady() {
        if (ready) return true;
        if (broken) return false;
        init();
        return ready;
    }

    public static ProfilerToken profile(String section) {
        FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
        if (plugin == null) {
            return ProfilerToken.NO_OP;
        }
        return plugin.getProfiler().enter(section);
    }

    public static void sendTabListAdd(Player receiver, FakePlayer fp) {
        if (receiver == null || fp == null) return;
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListAdd")) {
            Object nms = getHandle(receiver);
            if (nms == null) return;

            String dispStr = fp.getTabListName();
            Object displayName = fp.getCachedNmsDisplayComponent();
            if (displayName == null || !dispStr.equals(fp.getCachedNmsDisplaySource())) {
                Component adv = MiniMessage.miniMessage().deserialize(dispStr);
                displayName = adventureToNms(adv);
                if (displayName == null) displayName = componentLiteral(dispStr);
                if (displayName == null) return;
                fp.setCachedNmsDisplay(displayName, dispStr);
            }

            int latency = fp.getEffectivePing();
            SkinProfile skin = fp.getResolvedSkin();
            String profileName = fp.getPacketProfileName();
            if (profileName == null || profileName.isBlank()) profileName = fp.getName();
            String skinValue = skin != null && skin.isValid() ? skin.getValue() : null;
            String skinSignature = skin != null && skin.isValid() ? skin.getSignature() : null;
            TabAddPacketCacheEntry cached = tabAddPacketCache.get(fp.getUuid());
            Object packet;
            if (cached != null && cached.matches(profileName, dispStr, skinValue, skinSignature, latency)) {
                packet = cached.packet();
            } else {
                Object profile = buildProfileWithSkin(fp);
                if (profile == null) return;
                Object entry = buildEntryWithLatency(fp.getUuid(), profile, displayName, latency);
                Object actions = buildActionSet();
                packet = playerInfoUpdateCtor.newInstance(actions, buildSecondArg(entry));
                tabAddPacketCache.put(
                        fp.getUuid(),
                        new TabAddPacketCacheEntry(profileName, dispStr, skinValue, skinSignature, latency, packet));
            }

            sendPacket(nms, packet);
            if (Config.debugPackets()) Config.debugPackets("Tab ADD for " + fp.getName());
        } catch (Exception e) {
            FppLogger.error("sendTabListAdd failed: " + describeException(e));
            if (Config.debugPackets()) FppLogger.warn("  → " + e);
        }
    }

    private static Object buildProfileWithSkin(FakePlayer fp) throws Exception {

        String profileName = fp.getPacketProfileName();
        if (profileName == null || profileName.isBlank()) profileName = fp.getName();
        SkinProfile skin = fp.getResolvedSkin();
        String skinValue = skin != null && skin.isValid() ? skin.getValue() : null;
        String skinSignature = skin != null && skin.isValid() ? skin.getSignature() : null;
        ProfileCacheEntry cached = profileCache.get(fp.getUuid());
        if (cached != null && cached.matches(profileName, skinValue, skinSignature)) return cached.profile();

        Object profile;
        if (skin != null && skin.isValid()) {
            try {
                profile = SkinProfileInjector.createGameProfile(gameProfileClass, fp.getUuid(), profileName, skin);
                profileCache.put(fp.getUuid(), new ProfileCacheEntry(profileName, skinValue, skinSignature, profile));
                return profile;
            } catch (Exception e) {
                Config.debugPackets("buildProfileWithSkin: mutable profile failed - " + e.getMessage());
            }
        }

        profile = gameProfileCtor != null
                ? gameProfileCtor.newInstance(fp.getUuid(), profileName)
                : gameProfileClass.getDeclaredConstructors()[0].newInstance(fp.getUuid(), profileName);
        profileCache.put(fp.getUuid(), new ProfileCacheEntry(profileName, skinValue, skinSignature, profile));
        return profile;
    }

    private record ProfileCacheEntry(String profileName, String skinValue, String skinSignature, Object profile) {
        boolean matches(String profileName, String skinValue, String skinSignature) {
            return java.util.Objects.equals(this.profileName, profileName)
                    && java.util.Objects.equals(this.skinValue, skinValue)
                    && java.util.Objects.equals(this.skinSignature, skinSignature);
        }
    }

    private record TabAddPacketCacheEntry(
            String profileName,
            String displayName,
            String skinValue,
            String skinSignature,
            int latency,
            Object packet) {
        boolean matches(String profileName, String displayName, String skinValue, String skinSignature, int latency) {
            return this.latency == latency
                    && java.util.Objects.equals(this.profileName, profileName)
                    && java.util.Objects.equals(this.displayName, displayName)
                    && java.util.Objects.equals(this.skinValue, skinValue)
                    && java.util.Objects.equals(this.skinSignature, skinSignature);
        }
    }

    private static void injectProperty(Object profile, String key, String value, String signature) throws Exception {

        ClassLoader cl = profile.getClass().getClassLoader();
        Class<?> propertyClass = cl.loadClass("com.mojang.authlib.properties.Property");

        Object property;
        try {
            Constructor<?> c3 = propertyClass.getDeclaredConstructor(String.class, String.class, String.class);
            c3.setAccessible(true);
            property = c3.newInstance(key, value, signature != null ? signature : "");
        } catch (NoSuchMethodException ex) {

            Constructor<?> c2 = propertyClass.getDeclaredConstructor(String.class, String.class);
            c2.setAccessible(true);
            property = c2.newInstance(key, value);
        }

        Method getProps = null;
        for (Method m : profile.getClass().getMethods()) {
            if (m.getName().equals("getProperties") && m.getParameterCount() == 0) {
                getProps = m;
                break;
            }
        }
        if (getProps == null) throw new NoSuchMethodException("GameProfile.getProperties()");
        Object propertyMap = getProps.invoke(profile);

        for (Method m : propertyMap.getClass().getMethods()) {
            if ("put".equals(m.getName()) && m.getParameterCount() == 2) {
                try {
                    m.invoke(propertyMap, key, property);
                    Config.debugPackets("injectProperty: direct put succeeded.");
                    return;
                } catch (Exception ignored) {
                    break;
                }
            }
        }

        for (Field f : propertyMap.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object delegate = f.get(propertyMap);
            if (delegate == null) continue;
            for (Method m : delegate.getClass().getMethods()) {
                if ("put".equals(m.getName()) && m.getParameterCount() == 2) {
                    try {
                        m.invoke(delegate, key, property);
                        Config.debugPackets("injectProperty: put via delegate '" + f.getName() + "' succeeded.");
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        Class<?> hashMultimapClass = cl.loadClass("com.google.common.collect.HashMultimap");
        Method create = hashMultimapClass.getMethod("create");
        Object mutableMap = create.invoke(null);

        Method valuesMethod = null;
        for (Method m : propertyMap.getClass().getMethods()) {
            if ("values".equals(m.getName()) && m.getParameterCount() == 0) {
                valuesMethod = m;
                break;
            }
        }
        if (valuesMethod != null) {
            Object existing = valuesMethod.invoke(propertyMap);
            if (existing instanceof Iterable<?> iter) {
                Method putMethod = null;
                for (Method m : mutableMap.getClass().getMethods()) {
                    if ("put".equals(m.getName()) && m.getParameterCount() == 2) {
                        putMethod = m;
                        break;
                    }
                }
                if (putMethod != null) {
                    for (Object entry : iter) {

                        String entryKey = null;
                        for (String getter : new String[] {"getName", "name"}) {
                            try {
                                entryKey = (String)
                                        entry.getClass().getMethod(getter).invoke(entry);
                                break;
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                        if (entryKey != null) putMethod.invoke(mutableMap, entryKey, entry);
                    }
                }
            }
        }

        for (Method m : mutableMap.getClass().getMethods()) {
            if ("put".equals(m.getName()) && m.getParameterCount() == 2) {
                m.invoke(mutableMap, key, property);
                break;
            }
        }

        for (Field f : propertyMap.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object delegate = f.get(propertyMap);
            if (delegate != null && delegate.getClass().getName().contains("Multimap")) {
                f.set(propertyMap, mutableMap);
                Config.debugPackets("injectProperty: replaced delegate multimap - succeeded.");
                return;
            }
        }

        FppLogger.warn("injectProperty: all strategies exhausted - skin may not appear in tab list.");
    }

    public static void sendTabListRemove(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListRemove")) {
            ServerPlayer receiverNms = getServerPlayer(receiver);
            if (receiverNms == null) return;
            Constructor<?> ctor = getConstructor(playerInfoRemovePacketClass, List.class);
            if (ctor == null) {
                ctor = playerInfoRemovePacketClass.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
            }
            sendDirect(receiverNms, ctor.newInstance(List.of(fp.getUuid())));
            if (Config.debugPackets()) Config.debugPackets("Tab REMOVE for " + fp.getName());
        } catch (Exception e) {
            FppLogger.error("sendTabListRemove failed: " + e.getMessage());
        }
    }

    public static void broadcastTabListRemove(FakePlayer fp, Collection<? extends Player> receivers) {
        if (!ensureReady() || receivers.isEmpty()) return;
        try (var token = profile("PacketHelper.sendTabListRemove")) {
            Constructor<?> ctor = getConstructor(playerInfoRemovePacketClass, List.class);
            if (ctor == null) {
                ctor = playerInfoRemovePacketClass.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
            }
            Object packet = ctor.newInstance(List.of(fp.getUuid()));
            for (Player receiver : receivers) {
                ServerPlayer receiverNms = getServerPlayer(receiver);
                if (receiverNms != null) sendDirect(receiverNms, packet);
            }
            if (Config.debugPackets()) Config.debugPackets("Tab REMOVE broadcast for " + fp.getName());
        } catch (Exception e) {
            FppLogger.error("broadcastTabListRemove failed: " + e.getMessage());
        }
    }

    public static void sendTabListRemoveByUuid(Player receiver, UUID uuid) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListRemoveByUuid")) {
            ServerPlayer receiverNms = getServerPlayer(receiver);
            if (receiverNms == null) return;
            Constructor<?> ctor = getConstructor(playerInfoRemovePacketClass, List.class);
            if (ctor == null) {
                ctor = playerInfoRemovePacketClass.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
            }
            sendDirect(receiverNms, ctor.newInstance(List.of(uuid)));
            if (Config.debugPackets()) Config.debugPackets("Tab REMOVE raw for " + uuid);
        } catch (Exception e) {
            FppLogger.error("sendTabListRemoveByUuid failed: " + e.getMessage());
        }
    }

    public static void sendTabListAddRaw(
            Player receiver,
            UUID uuid,
            String packetProfileName,
            String displayName,
            String skinValue,
            String skinSignature,
            int pingMs) {
        if (!ensureReady()) return;
        try {
            Object nms = getHandle(receiver);

            String safeProfileName = (packetProfileName == null || packetProfileName.isBlank())
                    ? uuid.toString().replace("-", "").substring(0, 8)
                    : packetProfileName;

            Object profile = gameProfileCtor != null
                    ? gameProfileCtor.newInstance(uuid, safeProfileName)
                    : gameProfileClass.getDeclaredConstructors()[0].newInstance(uuid, safeProfileName);

            if (skinValue != null && !skinValue.isBlank()) {
                try {
                    injectProperty(profile, "textures", skinValue, skinSignature);
                } catch (Exception ex) {
                    Config.debugPackets("sendTabListAddRaw: skin inject failed - " + ex.getMessage());
                }
            }

            Component adventureComponent = MiniMessage.miniMessage().deserialize(displayName);
            Object nmsDisplayName = adventureToNms(adventureComponent);

            Object entry = buildEntryWithLatency(uuid, profile, nmsDisplayName, pingMs);
            Object actions = buildActionSet();

            sendPacket(nms, playerInfoUpdateCtor.newInstance(actions, buildSecondArg(entry)));
            Config.debugPackets("Tab ADD raw → "
                    + receiver.getName()
                    + " for "
                    + safeProfileName
                    + (skinValue != null && !skinValue.isBlank() ? " [skinned]" : ""));
        } catch (Exception e) {
            FppLogger.error("sendTabListAddRaw failed: " + e.getMessage());
            if (Config.debugPackets()) FppLogger.warn("  → " + e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sendTabListDisplayNameUpdate(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListDisplayNameUpdate")) {
            Object nms = getHandle(receiver);

            Object profile = fp.getCachedTabListGameProfile();
            if (profile == null) {
                profile = gameProfileCtor != null
                        ? gameProfileCtor.newInstance(fp.getUuid(), fp.getName())
                        : gameProfileClass.getDeclaredConstructors()[0].newInstance(fp.getUuid(), fp.getName());
                fp.setCachedTabListGameProfile(profile);
            }

            String dispStr = fp.getTabListName();

            Object displayName = fp.getCachedNmsDisplayComponent();
            if (displayName == null || !dispStr.equals(fp.getCachedNmsDisplaySource())) {
                Component adv = MiniMessage.miniMessage().deserialize(dispStr);
                displayName = adventureToNms(adv);
                fp.setCachedNmsDisplay(displayName, dispStr);
            }

            int latency = fp.getEffectivePing();
            Object entry = buildEntryWithLatency(fp.getUuid(), profile, displayName, latency);

            if (cachedUpdateDisplayNameActions == null) {
                Class<? extends Enum> e = rawEnum(playerInfoUpdateActionClass);
                cachedUpdateDisplayNameActions = EnumSet.of(Enum.valueOf(e, "UPDATE_DISPLAY_NAME"));
            }
            Object actions = cachedUpdateDisplayNameActions;

            sendPacket(nms, playerInfoUpdateCtor.newInstance(actions, buildSecondArg(entry)));
        } catch (Exception e) {

        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sendTabListDisplayNameUpdate(Player receiver, UUID uuid, String rawDisplayName, int pingMs) {
        if (!ensureReady()) return;
        try {
            Object nms = getHandle(receiver);
            Object profile = gameProfileCtor != null
                    ? gameProfileCtor.newInstance(uuid, uuid.toString().substring(0, 8))
                    : gameProfileClass.getDeclaredConstructors()[0].newInstance(
                            uuid, uuid.toString().substring(0, 8));

            Component adventureComponent = MiniMessage.miniMessage().deserialize(rawDisplayName);
            Object displayName = adventureToNms(adventureComponent);

            Object entry = buildEntryWithLatency(uuid, profile, displayName, pingMs);

            if (cachedUpdateDisplayNameActions == null) {
                Class<? extends Enum> e = rawEnum(playerInfoUpdateActionClass);
                cachedUpdateDisplayNameActions = EnumSet.of(Enum.valueOf(e, "UPDATE_DISPLAY_NAME"));
            }
            sendPacket(nms, playerInfoUpdateCtor.newInstance(cachedUpdateDisplayNameActions, buildSecondArg(entry)));
        } catch (Exception e) {

        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sendTabListLatencyUpdate(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListLatencyUpdate")) {
            Object nms = getHandle(receiver);

            Object profile = fp.getCachedTabListGameProfile();
            if (profile == null) {
                profile = gameProfileCtor != null
                        ? gameProfileCtor.newInstance(fp.getUuid(), fp.getName())
                        : gameProfileClass.getDeclaredConstructors()[0].newInstance(fp.getUuid(), fp.getName());
                fp.setCachedTabListGameProfile(profile);
            }

            Object displayName = fp.getCachedNmsDisplayComponent();
            if (displayName == null) {
                Component adv = MiniMessage.miniMessage().deserialize(fp.getTabListName());
                displayName = adventureToNms(adv);
                fp.setCachedNmsDisplay(displayName, fp.getTabListName());
            }

            int latency = fp.getEffectivePing();
            Object entry = buildEntryWithLatency(fp.getUuid(), profile, displayName, latency);

            if (cachedUpdateLatencyActions == null) {
                Class<? extends Enum> e = rawEnum(playerInfoUpdateActionClass);
                cachedUpdateLatencyActions = EnumSet.of(Enum.valueOf(e, "UPDATE_LATENCY"));
            }

            sendPacket(nms, playerInfoUpdateCtor.newInstance(cachedUpdateLatencyActions, buildSecondArg(entry)));
            if (Config.debugPackets()) Config.debugPackets("Tab LATENCY for " + fp.getName());
        } catch (Exception e) {
            Config.debugPackets("sendTabListLatencyUpdate failed: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sendTabListUpdateListed(Player receiver, FakePlayer fp, boolean listed) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListUpdateListed")) {
            Object nms = getHandle(receiver);

            Object profile = fp.getCachedTabListGameProfile();
            if (profile == null) {
                profile = buildProfileWithSkin(fp);
                fp.setCachedTabListGameProfile(profile);
            }

            Object displayName = fp.getCachedNmsDisplayComponent();
            if (displayName == null) {
                Component adv = MiniMessage.miniMessage().deserialize(fp.getTabListName());
                displayName = adventureToNms(adv);
                fp.setCachedNmsDisplay(displayName, fp.getTabListName());
            }

            int latency = fp.getEffectivePing();
            Object entry = buildEntryWithListedFlag(fp.getUuid(), profile, displayName, latency, listed);

            if (cachedUpdateListedActions == null) {
                Class<? extends Enum> e = rawEnum(playerInfoUpdateActionClass);
                cachedUpdateListedActions = EnumSet.of(Enum.valueOf(e, "UPDATE_LISTED"));
            }

            sendPacket(nms, playerInfoUpdateCtor.newInstance(cachedUpdateListedActions, buildSecondArg(entry)));
            if (Config.debugPackets()) Config.debugPackets("Tab LISTED for " + fp.getName());
        } catch (Exception e) {
            Config.debugPackets("sendTabListUpdateListed failed: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void sendTabListRefreshEntry(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTabListRefreshEntry")) {
            Object nms = getHandle(receiver);
            if (nms == null) return;

            Object profile = buildProfileWithSkin(fp);
            if (profile == null) return;

            String dispStr = fp.getTabListName();
            Object displayName = fp.getCachedNmsDisplayComponent();
            if (displayName == null || !dispStr.equals(fp.getCachedNmsDisplaySource())) {
                Component adv = MiniMessage.miniMessage().deserialize(dispStr);
                displayName = adventureToNms(adv);
                if (displayName == null) displayName = componentLiteral(dispStr);
                if (displayName == null) return;
                fp.setCachedNmsDisplay(displayName, dispStr);
            }

            int latency = fp.getEffectivePing();
            boolean listed = Config.tabListEnabled();
            Object entry = buildEntryWithListedFlag(fp.getUuid(), profile, displayName, latency, listed);

            if (cachedUpdateDisplayLatencyListedActions == null) {
                Class<? extends Enum> e = rawEnum(playerInfoUpdateActionClass);
                cachedUpdateDisplayLatencyListedActions = EnumSet.of(
                        Enum.valueOf(e, "UPDATE_DISPLAY_NAME"),
                        Enum.valueOf(e, "UPDATE_LISTED"),
                        Enum.valueOf(e, "UPDATE_LATENCY"));
            }
            Object actions = cachedUpdateDisplayLatencyListedActions;

            sendPacket(nms, playerInfoUpdateCtor.newInstance(actions, buildSecondArg(entry)));
            if (Config.debugPackets()) Config.debugPackets("Tab REFRESH for " + fp.getName());
        } catch (Exception e) {
            Config.debugPackets("sendTabListRefreshEntry failed: " + describeException(e));
        }
    }

    public static void spawnFakePlayer(Player receiver, FakePlayer fp, Location loc) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.spawnFakePlayer")) {
            Object nms = getHandle(receiver);
            Constructor<?> ctor = cachedAddEntityCtor;
            if (ctor == null) {
                ctor = addEntityPacketClass.getConstructor(
                        int.class,
                        UUID.class,
                        double.class,
                        double.class,
                        double.class,
                        float.class,
                        float.class,
                        entityTypeClass,
                        int.class,
                        vec3Class,
                        double.class);
                cachedAddEntityCtor = ctor;
            }
            sendPacket(
                    nms,
                    ctor.newInstance(
                            fp.getPlayer().getEntityId(),
                            fp.getUuid(),
                            loc.getX(),
                            loc.getY(),
                            loc.getZ(),
                            loc.getPitch(),
                            loc.getYaw(),
                            entityTypePlayer,
                            0,
                            vec3Zero,
                            0.0));
            if (Config.debugPackets()) Config.debugPackets("Spawn entity for " + fp.getName());
        } catch (Exception e) {
            FppLogger.error("spawnFakePlayer failed: " + e.getMessage());
            if (Config.debugPackets()) FppLogger.warn("  → " + e);
        }
    }

    public static void despawnFakePlayer(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.despawnFakePlayer")) {
            Object nms = getHandle(receiver);
            Constructor<?> ctor = cachedRemoveEntitiesCtor;
            if (ctor == null) {
                ctor = removeEntitiesPacketClass.getConstructor(int[].class);
                cachedRemoveEntitiesCtor = ctor;
            }
            sendPacket(nms, ctor.newInstance((Object) new int[] {fp.getPlayer().getEntityId()}));
            if (Config.debugPackets()) Config.debugPackets("Despawn entity for " + fp.getName());
        } catch (Exception e) {
            FppLogger.error("despawnFakePlayer failed: " + e.getMessage());
        }
    }

    public static void sendTeleport(Player receiver, FakePlayer fp, Location loc) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendTeleport")) {
            Object nms = getHandle(receiver);
            ClassLoader cl = nms.getClass().getClassLoader();

            Constructor<?> ctor = cachedTeleportCtor;
            if (ctor == null) {
                String[] candidates = {
                    "net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket",
                    "net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket"
                };
                outer:
                for (String className : candidates) {
                    try {
                        Class<?> pktClass = cl.loadClass(className);
                        for (Constructor<?> c : pktClass.getDeclaredConstructors()) {
                            c.setAccessible(true);
                            Class<?>[] pt = c.getParameterTypes();
                            if (pt.length == 7 && pt[0] == int.class && pt[1] == double.class) {
                                cachedTeleportPacketClass = pktClass;
                                cachedTeleportCtor = c;
                                ctor = c;
                                break outer;
                            }
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            if (ctor != null) {
                sendPacket(
                        nms,
                        ctor.newInstance(
                                fp.getEntityId(),
                                loc.getX(),
                                loc.getY(),
                                loc.getZ(),
                                loc.getYaw(),
                                loc.getPitch(),
                                true));
            }
        } catch (Exception ignored) {

        }
    }

    public static void sendHurtAnimation(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendHurtAnimation")) {
            Object nms = getHandle(receiver);
            ClassLoader cl = nms.getClass().getClassLoader();
            try {
                Class<?> hurtClass = cl.loadClass("net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket");
                Constructor<?> ctor = cachedHurtAnimationCtor;
                if (ctor == null) {
                    for (Constructor<?> c : hurtClass.getDeclaredConstructors()) {
                        c.setAccessible(true);
                        Class<?>[] pt = c.getParameterTypes();
                        if (pt.length == 2 && pt[0] == int.class && pt[1] == float.class) {
                            cachedHurtAnimationCtor = c;
                            ctor = c;
                            break;
                        }
                    }
                }
                if (ctor != null) {
                    Player bot = fp.getPlayer();
                    float yaw = bot != null ? bot.getLocation().getYaw() : 0.0f;
                    sendPacket(nms, ctor.newInstance(fp.getEntityId(), yaw));
                    return;
                }
            } catch (ClassNotFoundException ignored) {
            }

            Class<?> eventClass = cl.loadClass("net.minecraft.network.protocol.game.ClientboundEntityEventPacket");
            Constructor<?> ctor = cachedEntityEventCtor;
            if (ctor == null) {
                for (Constructor<?> c : eventClass.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length == 2 && pt[1] == byte.class) {
                        cachedEntityEventCtor = c;
                        ctor = c;
                        break;
                    }
                }
            }
            Player bot = fp.getPlayer();
            if (bot == null || ctor == null) return;
            Object botNms = getHandle(bot);
            sendPacket(nms, ctor.newInstance(botNms, (byte) 2));
        } catch (Exception ignored) {
        }
    }

    public static void sendSwingArm(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendSwingArm")) {
            Object nms = getHandle(receiver);
            ClassLoader cl = nms.getClass().getClassLoader();
            Class<?> animClass = cl.loadClass("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
            Constructor<?> ctor = cachedAnimateCtor;
            if (ctor == null) {
                for (Constructor<?> c : animClass.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        cachedAnimateCtor = c;
                        ctor = c;
                        break;
                    }
                }
            }
            if (ctor != null) {
                sendPacket(nms, ctor.newInstance(fp.getEntityId(), 0));
            }
        } catch (Exception ignored) {
        }
    }

    public static void sendEatAnimation(Player receiver, FakePlayer fp) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendEatAnimation")) {
            Object nms = getHandle(receiver);
            ClassLoader cl = nms.getClass().getClassLoader();

            Class<?> entityEventClass =
                    cl.loadClass("net.minecraft.network.protocol.game.ClientboundEntityEventPacket");

            Constructor<?> ctor = cachedEntityEventCtor;
            if (ctor == null) {
                for (Constructor<?> c : entityEventClass.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length == 2 && pt[1] == byte.class) {
                        cachedEntityEventCtor = c;
                        ctor = c;
                        break;
                    }
                }
            }
            Player botPlayer = fp.getPlayer();
            if (botPlayer == null || ctor == null) return;
            Object botNms = getHandle(botPlayer);
            sendPacket(nms, ctor.newInstance(botNms, (byte) 9));
        } catch (Exception ignored) {
        }
    }

    public static void sendRotation(Player receiver, FakePlayer fp, float yaw, float pitch, float headYaw) {
        if (!ensureReady()) return;
        ServerPlayer receiverNms = getServerPlayer(receiver);
        if (receiverNms == null) return;
        sendRotationDirect(receiverNms, fp, yaw, pitch, headYaw, true);
    }

    public static void sendRotationDirect(
            Player receiver, FakePlayer fp, float yaw, float pitch, float headYaw, boolean profile) {
        if (!ensureReady()) return;
        ServerPlayer receiverNms = getServerPlayer(receiver);
        if (receiverNms == null) return;
        sendRotationDirect(receiverNms, fp, yaw, pitch, headYaw, profile);
    }

    public static void sendRotationDirect(
            ServerPlayer receiverNms, FakePlayer fp, float yaw, float pitch, float headYaw, boolean profile) {
        if (!ensureReady()) return;
        try (var token = profile ? profile("PacketHelper.sendRotation") : ProfilerToken.NO_OP) {
            int entityId = fp.getEntityId();
            if (entityId == -1) return;

            byte encYaw = angleToByte(yaw);
            byte encPitch = angleToByte(pitch);
            byte encHead = angleToByte(headYaw);

            if (!rotCtorLookupDone) {
                synchronized (PacketHelper.class) {
                    if (!rotCtorLookupDone) {
                        for (Constructor<?> c : moveEntityRotPacketClass.getDeclaredConstructors()) {
                            Class<?>[] p = c.getParameterTypes();
                            if (p.length == 4 && p[0] == int.class && p[1] == byte.class) {
                                c.setAccessible(true);
                                cachedMoveEntityRotCtor = c;
                                break;
                            }
                        }
                        for (Constructor<?> c : rotateHeadPacketClass.getDeclaredConstructors()) {
                            c.setAccessible(true);
                            Class<?>[] p = c.getParameterTypes();
                            if (p.length == 2 && p[0] == int.class) {
                                cachedRotateHeadCtorInt = c;
                                break;
                            } else if (p.length == 2) {
                                cachedRotateHeadCtorEntity = c;
                            }
                        }
                        rotCtorLookupDone = true;
                    }
                }
            }

            if (cachedMoveEntityRotCtor != null) {
                sendDirect(receiverNms, cachedMoveEntityRotCtor.newInstance(entityId, encYaw, encPitch, true));
            }
            if (cachedRotateHeadCtorInt != null) {
                sendDirect(receiverNms, cachedRotateHeadCtorInt.newInstance(entityId, encHead));
            } else if (cachedRotateHeadCtorEntity != null && fp.getPhysicsEntity() != null) {
                ServerPlayer nmsEntity = getServerPlayer(fp.getPhysicsEntity());
                if (nmsEntity != null) {
                    sendDirect(receiverNms, cachedRotateHeadCtorEntity.newInstance(nmsEntity, encHead));
                }
            }
        } catch (Exception e) {
            Config.debugPackets("sendRotation failed: " + e.getMessage());
        }
    }

    public static void sendPositionSync(Player receiver, Player bot) {
        if (!ensureReady()) return;
        try (var token = profile("PacketHelper.sendPositionSync")) {
            Object receiverNms = craftPlayerGetHandle.invoke(receiver);

            if (!posSyncCtorLookupDone) {
                synchronized (PacketHelper.class) {
                    if (!posSyncCtorLookupDone) {
                        ClassLoader cl = receiverNms.getClass().getClassLoader();
                        String[] candidates = {
                            "net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket",
                            "net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket"
                        };
                        outer:
                        for (String className : candidates) {
                            try {
                                Class<?> pktClass = cl.loadClass(className);

                                for (Constructor<?> c : pktClass.getDeclaredConstructors()) {
                                    c.setAccessible(true);
                                    Class<?>[] pt = c.getParameterTypes();
                                    if (pt.length == 7 && pt[0] == int.class && pt[1] == double.class) {
                                        cachedPosSyncCtor = c;
                                        posSyncUsesEntityArg = false;
                                        Config.debugPackets("sendPositionSync: using 7-param ctor from " + className);
                                        break outer;
                                    }
                                }

                                for (Constructor<?> c : pktClass.getDeclaredConstructors()) {
                                    c.setAccessible(true);
                                    if (c.getParameterCount() == 1) {
                                        cachedPosSyncCtor = c;
                                        posSyncUsesEntityArg = true;
                                        Config.debugPackets(
                                                "sendPositionSync: using 1-param (Entity) ctor from" + " " + className);
                                        break outer;
                                    }
                                }
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                        posSyncCtorLookupDone = true;
                        if (cachedPosSyncCtor == null) {
                            Config.debugPackets("sendPositionSync: no suitable position-sync packet constructor"
                                    + " found - position sync disabled.");
                        }
                    }
                }
            }

            if (cachedPosSyncCtor == null) return;

            Object packet;
            if (posSyncUsesEntityArg) {
                ServerPlayer nmsBot = getServerPlayer(bot);
                if (nmsBot == null) return;
                packet = cachedPosSyncCtor.newInstance(nmsBot);
            } else {
                Location loc = bot.getLocation();
                packet = cachedPosSyncCtor.newInstance(
                        bot.getEntityId(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), true);
            }
            sendDirect((ServerPlayer) receiverNms, packet);

        } catch (Exception e) {
            Config.debugPackets("sendPositionSync failed: " + e.getMessage());
        }
    }

    public static void sendPositionSync(Player receiver, Player bot, Location loc) {
        if (!ensureReady()) return;
        if (!posSyncCtorLookupDone) {

            sendPositionSync(receiver, bot);
            return;
        }
        if (cachedPosSyncCtor == null) return;
        try {
            ServerPlayer receiverNms = getServerPlayer(receiver);
            if (receiverNms == null) return;
            Object packet;
            if (posSyncUsesEntityArg) {
                ServerPlayer nmsBot = getServerPlayer(bot);
                if (nmsBot == null) return;
                packet = cachedPosSyncCtor.newInstance(nmsBot);
            } else {
                packet = cachedPosSyncCtor.newInstance(
                        bot.getEntityId(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), true);
            }
            sendDirect(receiverNms, packet);
        } catch (Exception e) {
            Config.debugPackets("sendPositionSync (pre-loc) failed: " + e.getMessage());
        }
    }

    private static Object getHandle(Player player) throws Exception {
        if (player instanceof CraftPlayer craft) {
            return craft.getHandle();
        }
        if (craftPlayerGetHandle != null) return craftPlayerGetHandle.invoke(craftPlayerClass.cast(player));
        for (Method m : craftPlayerClass.getDeclaredMethods()) {
            if (m.getName().equals("getHandle") && m.getParameterCount() == 0) {
                m.setAccessible(true);
                craftPlayerGetHandle = m;
                return m.invoke(craftPlayerClass.cast(player));
            }
        }
        throw new NoSuchMethodException("CraftPlayer.getHandle()");
    }

    private static ServerPlayer getServerPlayer(Player player) {
        if (player instanceof CraftPlayer craft) {
            return craft.getHandle();
        }
        try {
            return (ServerPlayer) getHandle(player);
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendDirect(ServerPlayer nms, Object packet) {
        if (nms == null || packet == null) return;
        nms.connection.send((Packet<?>) packet);
    }

    /**
     * Directly sends a "team created/updated" packet (name + options, e.g. NAME_TAG_VISIBILITY) to
     * one receiver. Folia's Scoreboard/Team API is documented as entirely broken (global state that
     * hasn't been implemented for regionised ticking - see the Folia README's "Current broken API"
     * section), so bot name-tag hiding is done with a {@code PlayerTeam} that is never registered
     * with Bukkit's/NMS's real scoreboard - it exists purely as a packet-building helper, and this
     * class is responsible for pushing it to clients directly instead.
     */
    public static void sendTeamCreate(Player receiver, PlayerTeam team) {
        // Deliberately not gated on ensureReady(): these are plain NMS packets and must still go out
        // if the (unrelated) tab-list reflection init failed.
        try {
            ServerPlayer nms = getServerPlayer(receiver);
            if (nms == null) return;
            installTeamGuard(nms);
            sendDirect(nms, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        } catch (Throwable t) {
            FppLogger.debug("sendTeamCreate failed for " + receiver.getName() + ": " + t);
        }
    }

    /**
     * Directly sends a single player's team-membership (add/remove) packet to one receiver. Must
     * only be called for a viewer that has already received {@link #sendTeamCreate} for this team -
     * otherwise the client has no record of the team to add/remove the entry from.
     */
    public static void sendTeamMembership(Player receiver, PlayerTeam team, String entryName, boolean add) {
        try {
            ServerPlayer nms = getServerPlayer(receiver);
            if (nms == null) return;
            installTeamGuard(nms);
            ClientboundSetPlayerTeamPacket.Action action =
                    add ? ClientboundSetPlayerTeamPacket.Action.ADD : ClientboundSetPlayerTeamPacket.Action.REMOVE;
            sendDirect(nms, ClientboundSetPlayerTeamPacket.createPlayerPacket(team, entryName, action));
        } catch (Throwable t) {
            FppLogger.debug("sendTeamMembership failed for " + receiver.getName() + ": " + t);
        }
    }

    /**
     * Makes sure the viewer's connection has a {@link ViewerPacketGuard}, so hand-pushed team packets
     * can never desync the client's scoreboard from what other plugins/the server send it (which
     * otherwise kicks the client with "Network Protocol Error"). Must run before the first team
     * packet FPP sends to that viewer.
     */
    /**
     * Tells {@code viewer} to stop listing {@code uuid} in the tab list, via the vanilla "update
     * listed" packet (the player-info entry itself is kept, so the bot's skin still renders).
     */
    public static void sendUnlisted(Player viewer, UUID uuid) {
        if (viewer == null || uuid == null) return;
        try {
            ServerPlayer nms = getServerPlayer(viewer);
            if (nms == null) return;
            installTeamGuard(nms);
            sendDirect(
                    nms,
                    net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.updateListed(uuid, false));
        } catch (Throwable t) {
            FppLogger.debug("sendUnlisted failed for " + viewer.getName() + ": " + t);
        }
    }

    /**
     * Sets {@code uuid}'s tab-list state for one viewer directly: listed flag plus, when listing,
     * the {@code displayName} shown. Uses the vanilla packet types straight (no reflection), so it
     * works regardless of the tab-list reflection helpers' state.
     */
    public static void sendTabEntry(Player viewer, UUID uuid, boolean listed, String displayName) {
        if (viewer == null || uuid == null) return;
        try {
            ServerPlayer nms = getServerPlayer(viewer);
            if (nms == null) return;
            installTeamGuard(nms);
            var entry = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                    uuid,
                    null,
                    listed,
                    0,
                    net.minecraft.world.level.GameType.SURVIVAL,
                    listed && displayName != null ? net.minecraft.network.chat.Component.literal(displayName) : null,
                    false,
                    0,
                    null);
            var actions = java.util.EnumSet.of(
                    net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                    net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
            sendDirect(nms, new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(actions, entry));
        } catch (Throwable t) {
            FppLogger.debug("sendTabEntry failed for " + viewer.getName() + ": " + t);
        }
    }

    /** Installs the viewer packet guard on {@code viewer}'s connection (idempotent). */
    public static void installViewerGuard(Player viewer) {
        ServerPlayer nms = getServerPlayer(viewer);
        if (nms != null) installTeamGuard(nms);
    }

    private static void installTeamGuard(ServerPlayer nms) {
        try {
            if (nms.connection == null || nms.connection.connection == null) return;
            ViewerPacketGuard.install(nms.connection.connection.channel);
        } catch (Throwable t) {
            FppLogger.debug("installTeamGuard failed: " + t);
        }
    }

    private static void sendPacket(Object serverPlayer, Object packet) throws Exception {
        if (serverPlayer == null || packet == null) return;
        if (cachedConnectionField == null) {
            Field f = findFieldInHierarchy(serverPlayer.getClass(), "connection");
            if (f == null) throw new IllegalStateException("ServerPlayer.connection field not found");
            f.setAccessible(true);
            cachedConnectionField = f;
        }
        Object conn = cachedConnectionField.get(serverPlayer);
        if (conn == null) throw new IllegalStateException("ServerPlayer.connection is null");

        if (cachedSendMethod == null) {

            Class<?> cur = conn.getClass();
            outer:
            while (cur != null && cur != Object.class) {
                if (!cur.getName().startsWith("me.bill.")) {
                    for (Method m : cur.getDeclaredMethods()) {
                        if (m.getName().equals("send") && m.getParameterCount() == 1) {
                            m.setAccessible(true);
                            cachedSendMethod = m;
                            break outer;
                        }
                    }
                }
                cur = cur.getSuperclass();
            }
            if (cachedSendMethod == null) throw new IllegalStateException("connection.send(Packet) not found");
        }
        cachedSendMethod.invoke(conn, packet);
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object scanStaticField(Class<?> clazz, String name) throws Exception {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz.getSimpleName());
    }

    private static Constructor<?> getConstructor(Class<?> clazz, Class<?>... params) {
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> rawEnum(Class<?> c) {
        return (Class<? extends Enum>) c;
    }

    private static Class<?> getCraftPlayerClass() throws ClassNotFoundException {
        try {
            return Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
        } catch (ClassNotFoundException ignored) {
        }

        String pkg = Bukkit.getServer().getClass().getPackage().getName();
        String[] parts = pkg.split("\\.");
        String version = parts.length >= 4 ? parts[3] + "." : "";
        return Class.forName("org.bukkit.craftbukkit." + version + "entity.CraftPlayer");
    }

    private static byte angleToByte(float degrees) {
        return (byte) Math.floor(degrees * 256f / 360f);
    }

    private static Object adventureToNms(Component component) {
        if (paperAdventureAsVanilla != null) {
            try {
                return paperAdventureAsVanilla.invoke(null, component);
            } catch (Exception e) {
                Config.debugPackets("PaperAdventure.asVanilla failed: " + e.getMessage());
            }
        }

        if (componentLiteral != null) {
            try {
                String plain = PlainTextComponentSerializer.plainText().serialize(component);
                return componentLiteral.invoke(null, plain);
            } catch (Exception e) {
                Config.debugPackets("componentLiteral fallback failed: " + e.getMessage());
            }
        }
        return null;
    }

    private static Object componentLiteral(String text) {
        if (componentLiteral == null) return null;
        try {
            return componentLiteral.invoke(null, text != null ? text : "");
        } catch (Exception e) {
            Config.debugPackets("componentLiteral failed: " + describeException(e));
            return null;
        }
    }

    private static String describeException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
            cause = ite.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static volatile Object cachedFullActionSet;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object buildActionSet() {
        Object cached = cachedFullActionSet;
        if (cached != null) return cached;

        Class<? extends Enum> enumClass = rawEnum(playerInfoUpdateActionClass);

        EnumSet actions = EnumSet.noneOf(enumClass);

        actions.add(Enum.valueOf(enumClass, "ADD_PLAYER"));
        actions.add(Enum.valueOf(enumClass, "UPDATE_LISTED"));
        actions.add(Enum.valueOf(enumClass, "UPDATE_LATENCY"));
        actions.add(Enum.valueOf(enumClass, "UPDATE_GAME_MODE"));
        actions.add(Enum.valueOf(enumClass, "UPDATE_DISPLAY_NAME"));
        tryAddAction(actions, enumClass, "UPDATE_LIST_ORDER");
        tryAddAction(actions, enumClass, "UPDATE_HAT");

        cachedFullActionSet = actions;
        return actions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tryAddAction(EnumSet actions, Class<? extends Enum> enumClass, String name) {
        try {
            actions.add(Enum.valueOf(enumClass, name));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static Object buildEntryWithLatency(UUID uuid, Object profile, Object displayName, int latency)
            throws Exception {
        if (cachedEntryCtorWinner != null) {
            return cachedEntryCtorWinner.newInstance(
                    mapEntryArgs(cachedEntryCtorParamTypes, uuid, profile, displayName, latency));
        }
        Constructor<?>[] ctors = playerInfoUpdateEntryClass.getDeclaredConstructors();
        Arrays.sort(ctors, (a, b) -> b.getParameterCount() - a.getParameterCount());
        Exception last = null;
        for (Constructor<?> ctor : ctors) {
            ctor.setAccessible(true);
            try {
                Object result =
                        ctor.newInstance(mapEntryArgs(ctor.getParameterTypes(), uuid, profile, displayName, latency));
                cachedEntryCtorParamTypes = ctor.getParameterTypes();
                cachedEntryCtorWinner = ctor;
                return result;
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("No Entry ctor matched. Last: " + (last != null ? last.getMessage() : "?"));
    }

    private static Object buildEntryWithListedFlag(
            UUID uuid, Object profile, Object displayName, int latency, boolean listed) throws Exception {
        if (cachedEntryCtorWinner != null) {
            return cachedEntryCtorWinner.newInstance(
                    mapEntryArgsWithListed(cachedEntryCtorParamTypes, uuid, profile, displayName, latency, listed));
        }
        Constructor<?>[] ctors = playerInfoUpdateEntryClass.getDeclaredConstructors();
        Arrays.sort(ctors, (a, b) -> b.getParameterCount() - a.getParameterCount());
        Exception last = null;
        for (Constructor<?> ctor : ctors) {
            ctor.setAccessible(true);
            try {
                Object result = ctor.newInstance(
                        mapEntryArgsWithListed(ctor.getParameterTypes(), uuid, profile, displayName, latency, listed));
                cachedEntryCtorParamTypes = ctor.getParameterTypes();
                cachedEntryCtorWinner = ctor;
                return result;
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("No Entry ctor matched. Last: " + (last != null ? last.getMessage() : "?"));
    }

    private static Object buildSecondArg(Object entry) throws Exception {
        if (cachedInfoUpdateSecondArgStrategy == 0) {
            Class<?> spt = playerInfoUpdateCtor.getParameterTypes()[1];
            if (spt == playerInfoUpdateEntryClass) {
                cachedInfoUpdateSecondArgStrategy = 1;
            } else if (spt.isArray()) {
                cachedInfoUpdateSecondArgStrategy = 2;
                cachedInfoUpdateArrayCompType = spt.getComponentType();
            } else {
                cachedInfoUpdateSecondArgStrategy = 3;
            }
        }
        return switch (cachedInfoUpdateSecondArgStrategy) {
            case 1 -> entry;
            case 2 -> {
                Object arr = Array.newInstance(cachedInfoUpdateArrayCompType, 1);
                Array.set(arr, 0, entry);
                yield arr;
            }
            default -> List.of(entry);
        };
    }

    private static Object[] mapEntryArgs(Class<?>[] types, UUID uuid, Object profile, Object displayName) {
        return mapEntryArgs(types, uuid, profile, displayName, 0);
    }

    private static Object[] mapEntryArgs(Class<?>[] types, UUID uuid, Object profile, Object displayName, int latency) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            args[i] = switch (type.getSimpleName()) {
                case "UUID" -> uuid;
                case "GameProfile" -> profile;

                case "boolean" -> true;
                case "Boolean" -> Boolean.TRUE;
                case "int" -> latency;
                case "Integer" -> Integer.valueOf(latency);
                case "GameType" -> gameTypeSurvival;
                case "Component" -> displayName;
                default -> defaultEntryArg(type);
            };
        }
        return args;
    }

    private static Object[] mapEntryArgsWithListed(
            Class<?>[] types, UUID uuid, Object profile, Object displayName, int latency, boolean listed) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            args[i] = switch (type.getSimpleName()) {
                case "UUID" -> uuid;
                case "GameProfile" -> profile;

                case "boolean" -> listed;
                case "Boolean" -> Boolean.valueOf(listed);
                case "int" -> latency;
                case "Integer" -> Integer.valueOf(latency);
                case "GameType" -> gameTypeSurvival;
                case "Component" -> displayName;
                default -> defaultEntryArg(type);
            };
        }
        return args;
    }

    private static Object defaultEntryArg(Class<?> type) {
        if (type == Optional.class) return Optional.empty();
        if (type == String.class) return "";
        if (type == List.class || type == Collection.class) return List.of();
        if (type == EnumSet.class || type == Set.class) return Set.of();
        if (type == long.class) return 0L;
        if (type == Long.class) return Long.valueOf(0L);
        if (type == float.class) return 0f;
        if (type == Float.class) return Float.valueOf(0f);
        if (type == double.class) return 0d;
        if (type == Double.class) return Double.valueOf(0d);
        if (type == byte.class) return (byte) 0;
        if (type == Byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return (short) 0;
        if (type == Short.class) return Short.valueOf((short) 0);
        return null;
    }
}
