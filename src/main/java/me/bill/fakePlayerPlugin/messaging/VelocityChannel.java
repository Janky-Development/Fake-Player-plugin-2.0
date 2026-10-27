package me.bill.fakePlayerPlugin.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.BotNameConfig;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerBody;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PacketHelper;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotCache;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotEntry;
import me.bill.fakePlayerPlugin.fakeplayer.SkinProfile;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.kyori.adventure.text.Component;

public final class VelocityChannel implements PluginMessageListener {

    public static final String SUBCHANNEL_BOT_SPAWN = "BOT_SPAWN";
    public static final String SUBCHANNEL_BOT_DESPAWN = "BOT_DESPAWN";
    public static final String SUBCHANNEL_BOT_UPDATE = "BOT_UPDATE";
    public static final String SUBCHANNEL_ALERT = "ALERT";
    public static final String SUBCHANNEL_SYNC = "SYNC";
    public static final String SUBCHANNEL_RESYNC = "RESYNC";
    public static final String SUBCHANNEL_SERVER_OFFLINE = "SERVER_OFFLINE";
    public static final String SUBCHANNEL_SERVER_STATS = "SERVER_STATS";
    public static final String SUBCHANNEL_NETWORK_STATS = "NETWORK_STATS";

    public static final String CHANNEL = "fpp:main";

    public static final String PROXY_CHANNEL = "fpp:proxy";

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final FakePlayerPlugin plugin;

    private final FakePlayerManager manager;

    private final Set<String> recentIds = ConcurrentHashMap.newKeySet();

    private final Set<UUID> pendingProxyDespawnUuids = ConcurrentHashMap.newKeySet();

    private volatile boolean pendingResync = false;

    private volatile boolean pendingProxyBroadcast = false;

    private volatile boolean pendingServerStats = false;

    private volatile boolean retryTaskRunning = false;

    public VelocityChannel(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void broadcastServerStats() {
        if (!Config.isNetworkMode() || !plugin.isEnabled()) return;
        int realPlayers = Math.max(0, Bukkit.getOnlinePlayers().size() - manager.getCount());
        String msgId = generateAndTrackId();
        boolean sent =
                sendPluginMessage(SUBCHANNEL_SERVER_STATS, msgId, Config.serverId(), String.valueOf(realPlayers));
        if (sent) {
            Config.debugNetwork("[VelocityChannel] SERVER_STATS sent: " + realPlayers + " real players.");
        } else {
            pendingServerStats = true;
            scheduleProxyRetryIfNeeded();
            Config.debugNetwork(
                    "[VelocityChannel] SERVER_STATS queued (no real player): " + realPlayers + " real players.");
        }
    }

    public void flushPendingServerStats() {
        if (!pendingServerStats) return;
        pendingServerStats = false;
        broadcastServerStats();
    }

    private String generateAndTrackId() {
        String id =
                System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        recentIds.add(id);
        FppScheduler.runSyncLater(plugin, () -> recentIds.remove(id), 100L);
        return id;
    }

    private boolean isDuplicate(String msgId, String originServer) {
        return recentIds.contains(msgId) || Config.serverId().equals(originServer);
    }

    private void trackIncoming(String msgId) {
        recentIds.add(msgId);
        FppScheduler.runSyncLater(plugin, () -> recentIds.remove(msgId), 100L);
    }

    private Player findRealCarrierPlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (manager.getByUuid(p.getUniqueId()) != null) continue;
            // Guard against bots whose bodies are still online after activePlayers was cleared
            // (e.g. during bulk despawn). Skip any player with the fake-player PDC tag.
            if (FakePlayerManager.FAKE_PLAYER_KEY != null) {
                String pdc = p.getPersistentDataContainer()
                        .get(FakePlayerManager.FAKE_PLAYER_KEY, PersistentDataType.STRING);
                if (pdc != null && pdc.startsWith(FakePlayerBody.VISUAL_PDC_VALUE)) continue;
            }
            return p;
        }
        return null;
    }

    public boolean sendPluginMessage(String subchannel, String... data) {
        Player carrier = findRealCarrierPlayer();
        if (carrier == null) {
            Config.debugNetwork("[VelocityChannel] dropped (no real player online): " + subchannel);
            return false;
        }
        try {
            ByteArrayOutputStream innerBuf = new ByteArrayOutputStream();
            DataOutputStream innerOut = new DataOutputStream(innerBuf);
            innerOut.writeUTF(subchannel);
            for (String f : data) innerOut.writeUTF(f != null ? f : "");
            byte[] innerBytes = innerBuf.toByteArray();

            ByteArrayOutputStream outerBuf = new ByteArrayOutputStream();
            DataOutputStream outerOut = new DataOutputStream(outerBuf);
            outerOut.writeUTF("Forward");
            outerOut.writeUTF("ALL");
            outerOut.writeUTF(CHANNEL);
            outerOut.writeShort(innerBytes.length);
            outerOut.write(innerBytes);

            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, outerBuf.toByteArray());
            Config.debugNetwork("[VelocityChannel] Sent '"
                    + subchannel
                    + "' ("
                    + innerBytes.length
                    + " bytes) via real player: "
                    + carrier.getName()
                    + ".");
            return true;
        } catch (IOException e) {
            FppLogger.warn("[VelocityChannel] send failed: " + e.getMessage());
            return false;
        }
    }

    public boolean sendDirectToProxy(String subchannel, String... data) {
        Player carrier = findRealCarrierPlayer();
        if (carrier == null) {
            Config.debugNetwork("[VelocityChannel] fpp:proxy send dropped (no real carrier): " + subchannel);
            return false;
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeUTF(subchannel);
            for (String f : data) out.writeUTF(f != null ? f : "");
            byte[] bytes = buf.toByteArray();
            carrier.sendPluginMessage(plugin, PROXY_CHANNEL, bytes);
            Config.debugNetwork("[VelocityChannel] Sent '"
                    + subchannel
                    + "' on fpp:proxy ("
                    + bytes.length
                    + " bytes) via "
                    + carrier.getName()
                    + ".");
            return true;
        } catch (IOException e) {
            FppLogger.warn("[FPP-Proxy] sendDirectToProxy failed for '" + subchannel + "': " + e.getMessage());
            return false;
        }
    }

    public void broadcastBotSpawn(FakePlayer fp) {
        SkinProfile skin = fp.getResolvedSkin();
        String skinValue = (skin != null) ? skin.getValue() : "";
        String skinSignature = (skin != null) ? skin.getSignature() : "";
        String msgId = generateAndTrackId();

        Player realCarrier = findRealCarrierPlayer();
        if (realCarrier == null) {
            pendingProxyBroadcast = true;
            scheduleProxyRetryIfNeeded();
        }
        sendDirectToProxy(
                SUBCHANNEL_BOT_SPAWN,
                msgId,
                Config.serverId(),
                fp.getUuid().toString(),
                fp.getName(),
                fp.getDisplayName(),
                fp.getPacketProfileName(),
                skinValue,
                skinSignature,
                String.valueOf(fp.getEffectivePing()));

        if (Config.isNetworkMode()) {
            sendPluginMessage(
                    SUBCHANNEL_BOT_SPAWN,
                    msgId,
                    Config.serverId(),
                    fp.getUuid().toString(),
                    fp.getName(),
                    fp.getDisplayName(),
                    fp.getPacketProfileName(),
                    skinValue,
                    skinSignature,
                    String.valueOf(fp.getEffectivePing()));
            Config.debugNetwork("[VelocityChannel] BOT_SPAWN BungeeCord Forward sent for '" + fp.getName() + "'.");
        }
    }

    public void broadcastConfigUpdated(String fileName) {
        if (!Config.isNetworkMode()) return;
        String msgId = generateAndTrackId();
        sendPluginMessage(SUBCHANNEL_SYNC, msgId, Config.serverId(), "config_updated", fileName);
        Config.debugNetwork("[VelocityChannel] SYNC/config_updated sent for '" + fileName + "'.");
    }

    public void broadcastBotDespawn(UUID uuid) {
        String msgId = generateAndTrackId();

        boolean sent = sendDirectToProxy(SUBCHANNEL_BOT_DESPAWN, msgId, Config.serverId(), uuid.toString());
        if (!sent) {
            pendingProxyDespawnUuids.add(uuid);
            scheduleProxyRetryIfNeeded();
        }

        if (Config.isNetworkMode()) {
            sendPluginMessage(SUBCHANNEL_BOT_DESPAWN, msgId, Config.serverId(), uuid.toString());
            Config.debugNetwork("[VelocityChannel] BOT_DESPAWN BungeeCord Forward sent for " + uuid + ".");
        }
    }

    public void broadcastResyncRequest() {
        if (!Config.isNetworkMode()) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            pendingResync = true;
            Config.debugNetwork("[VelocityChannel] RESYNC queued (no players online yet).");
            return;
        }
        String msgId = generateAndTrackId();
        sendPluginMessage(SUBCHANNEL_RESYNC, msgId, Config.serverId());
        Config.debugNetwork("[VelocityChannel] RESYNC request broadcast.");
    }

    public void broadcastServerOffline() {

        if (!plugin.isEnabled()) {
            Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE skipped (plugin disabled).");
            return;
        }

        String msgId =
                System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);

        boolean sent = sendDirectToProxy(SUBCHANNEL_SERVER_OFFLINE, msgId, Config.serverId());
        if (!sent) {
            Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE fpp:proxy send failed (no real carrier).");
        }

        if (Config.isNetworkMode()) {
            try {
                sendPluginMessage(SUBCHANNEL_SERVER_OFFLINE, msgId, Config.serverId());
                Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE BungeeCord Forward sent.");
            } catch (Exception e) {
                Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE BungeeCord send failed: " + e.getMessage());
            }
        }
    }

    public boolean hasPendingResync() {
        return pendingResync;
    }

    public void clearPendingResync() {
        pendingResync = false;
    }

    public boolean hasPendingProxyBroadcast() {
        return pendingProxyBroadcast;
    }

    public void clearPendingProxyBroadcast() {
        pendingProxyBroadcast = false;
        retryTaskRunning = false;
    }

    private void scheduleProxyRetryIfNeeded() {
        if (retryTaskRunning) return;
        retryTaskRunning = true;
        final int[] retryTaskId = {-1};
        retryTaskId[0] = FppScheduler.runSyncRepeatingWithId(
                plugin,
                () -> {
                    boolean nothingToDo =
                            !pendingProxyBroadcast && pendingProxyDespawnUuids.isEmpty() && !pendingServerStats;
                    if (nothingToDo) {
                        FppScheduler.cancelTask(retryTaskId[0]);
                        retryTaskRunning = false;
                        return;
                    }
                    Player carrier = findRealCarrierPlayer();
                    if (carrier == null) {
                        return;
                    }

                    // Flush any pending despawns first
                    if (!pendingProxyDespawnUuids.isEmpty()) {
                        Set<UUID> copy = new HashSet<>(pendingProxyDespawnUuids);
                        pendingProxyDespawnUuids.clear();
                        for (UUID pendingUuid : copy) {
                            String msgId = generateAndTrackId();
                            sendDirectToProxy(SUBCHANNEL_BOT_DESPAWN, msgId, Config.serverId(), pendingUuid.toString());
                        }
                    }

                    // Then flush pending spawns
                    if (pendingProxyBroadcast) {
                        pendingProxyBroadcast = false;
                        for (FakePlayer botFp : manager.getActivePlayers()) {
                            broadcastBotSpawn(botFp);
                        }
                    }

                    // Then flush pending stats
                    if (pendingServerStats) {
                        pendingServerStats = false;
                        broadcastServerStats();
                    }

                    if (pendingProxyDespawnUuids.isEmpty() && !pendingProxyBroadcast && !pendingServerStats) {
                        FppScheduler.cancelTask(retryTaskId[0]);
                        retryTaskRunning = false;
                    }
                },
                60L,
                60L);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!CHANNEL.equals(channel) && !PROXY_CHANNEL.equals(channel)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String subchannel = in.readUTF();
            Config.debugNetwork("[VelocityChannel] Recv '" + subchannel + "' via " + player.getName());
            switch (subchannel) {
                case SUBCHANNEL_BOT_SPAWN -> handleBotSpawn(in);
                case SUBCHANNEL_BOT_DESPAWN -> handleBotDespawn(in);
                case SUBCHANNEL_BOT_UPDATE -> handleBotUpdate(in);
                case SUBCHANNEL_ALERT -> handleAlert(in);
                case SUBCHANNEL_SYNC -> handleSync(in);
                case SUBCHANNEL_RESYNC -> handleResync(in);
                case SUBCHANNEL_SERVER_OFFLINE -> handleServerOffline(in);
                case SUBCHANNEL_SERVER_STATS -> handleServerStats(in);
                case SUBCHANNEL_NETWORK_STATS -> handleNetworkStats(in);
                default -> FppLogger.warn("[VelocityChannel] Unknown subchannel: '" + subchannel + "'.");
            }
        } catch (IOException e) {
            FppLogger.warn("[VelocityChannel] Parse error from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handleBotSpawn(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();
        UUID uuid = UUID.fromString(in.readUTF());
        String name = in.readUTF();
        String displayName = in.readUTF();
        String packetName = in.readUTF();
        String skinValue = in.readUTF();
        String skinSignature = in.readUTF();
        int ping;
        try {
            String pingStr = in.readUTF();
            ping = Integer.parseInt(pingStr);
        } catch (EOFException | NumberFormatException e) {
            ping = 0;
        }

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] BOT_SPAWN echo suppressed: " + name);
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork("[VelocityChannel] BOT_SPAWN '" + name + "' from '" + originServer + "'.");

        String safePacketName = packetName.isBlank() ? name : packetName;

        RemoteBotEntry entry = new RemoteBotEntry(
                originServer, uuid, name, displayName, safePacketName, skinValue, skinSignature, ping);

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache != null) cache.add(entry);

        if (Config.tabListEnabled()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                PacketHelper.sendTabListAddRaw(
                        online, uuid, safePacketName, FakePlayer.tabListName(name), skinValue, skinSignature, ping);
            }
        }
    }

    private void handleBotDespawn(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();
        UUID uuid = UUID.fromString(in.readUTF());

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] BOT_DESPAWN echo suppressed: " + uuid);
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork("[VelocityChannel] BOT_DESPAWN " + uuid + " from '" + originServer + "'.");

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache != null) cache.remove(uuid);

        for (Player online : Bukkit.getOnlinePlayers()) {
            PacketHelper.sendTabListRemoveByUuid(online, uuid);
        }
    }

    private void handleAlert(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String message = in.readUTF();

        if (recentIds.contains(msgId)) {
            Config.debugNetwork("[VelocityChannel] ALERT echo suppressed.");
            return;
        }
        trackIncoming(msgId);
        broadcastAlertLocally(message);
    }

    private void handleBotUpdate(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();
        UUID uuid = UUID.fromString(in.readUTF());
        String newDisplayName = in.readUTF();

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] BOT_UPDATE echo suppressed: " + uuid);
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork("[VelocityChannel] BOT_UPDATE "
                + uuid
                + " displayName='"
                + newDisplayName
                + "' from '"
                + originServer
                + "'.");

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache == null) return;

        RemoteBotEntry existing = cache.get(uuid);
        if (existing == null) return;

        RemoteBotEntry updated = new RemoteBotEntry(
                existing.serverId(),
                existing.uuid(),
                existing.name(),
                newDisplayName,
                existing.packetProfileName(),
                existing.skinValue(),
                existing.skinSignature(),
                existing.ping());
        cache.add(updated);

        if (Config.tabListEnabled()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                PacketHelper.sendTabListDisplayNameUpdate(
                        online, uuid, FakePlayer.tabListName(existing.name()), existing.ping());
            }
        }
    }

    private void handleSync(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();
        String key = in.readUTF();
        String value = in.readUTF();

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] SYNC echo suppressed.");
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork("[VelocityChannel] SYNC - " + key + "='" + value + "' from '" + originServer + "'.");

        if ("config_updated".equals(key) && Config.configSyncMode().equalsIgnoreCase("AUTO_PULL")) {
            var csm = plugin.getConfigSyncManager();
            if (csm != null) {

                FppScheduler.runAsync(plugin, () -> {
                    boolean pulled = csm.pull(value, false);
                    if (pulled) {
                        FppLogger.info("[ConfigSync] Reactive pull applied for '"
                                + value
                                + "' (pushed by "
                                + originServer
                                + ").");

                        FppScheduler.runSync(plugin, () -> reloadSubsystemForFile(value));
                    }
                });
            }
        }
    }

    private void handleResync(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] RESYNC echo suppressed.");
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork(
                "[VelocityChannel] RESYNC requested by '" + originServer + "' - re-broadcasting local bots.");

        FppScheduler.runSync(plugin, () -> {
            for (FakePlayer fp : manager.getActivePlayers()) {
                broadcastBotSpawn(fp);
            }
        });
    }

    private void handleServerOffline(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE echo suppressed.");
            return;
        }
        trackIncoming(msgId);

        Config.debugNetwork("[VelocityChannel] SERVER_OFFLINE from '" + originServer + "' - purging remote bots.");

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache == null) return;

        List<UUID> evicted = new ArrayList<>();
        for (RemoteBotEntry entry : cache.getAll()) {
            if (originServer.equals(entry.serverId())) {
                evicted.add(entry.uuid());
            }
        }

        cache.removeAllFromServer(originServer);

        if (!evicted.isEmpty() && Config.tabListEnabled()) {
            FppScheduler.runSync(plugin, () -> {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    for (UUID uuid : evicted) {
                        PacketHelper.sendTabListRemoveByUuid(online, uuid);
                    }
                }
                Config.debugNetwork("[VelocityChannel] Removed "
                        + evicted.size()
                        + " tab entries for offline server '"
                        + originServer
                        + "'.");
            });
        }
    }

    private void handleServerStats(DataInputStream in) throws IOException {
        String msgId = in.readUTF();
        String originServer = in.readUTF();
        String countStr = in.readUTF();

        if (isDuplicate(msgId, originServer)) {
            Config.debugNetwork("[VelocityChannel] SERVER_STATS echo suppressed.");
            return;
        }
        trackIncoming(msgId);

        int realPlayers = 0;
        try {
            realPlayers = Integer.parseInt(countStr);
        } catch (NumberFormatException ignored) {
        }

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache != null) {
            cache.setServerRealPlayerCount(originServer, realPlayers);
        }

        Config.debugNetwork(
                "[VelocityChannel] SERVER_STATS from '" + originServer + "' - real players: " + realPlayers + ".");
    }

    private void handleNetworkStats(DataInputStream in) throws IOException {
        int totalPlayers = in.readInt();
        int totalBots = in.readInt();

        RemoteBotCache cache = plugin.getRemoteBotCache();
        if (cache != null) {
            cache.setNetworkTotalPlayers(totalPlayers);
            cache.setNetworkTotalBots(totalBots);
        }

        Config.debugNetwork("[VelocityChannel] NETWORK_STATS - total players: "
                + totalPlayers
                + ", total bots: "
                + totalBots
                + ".");
    }

    private void reloadSubsystemForFile(String fileName) {
        switch (fileName) {
            case "config.yml" -> {
                Config.reload();
                Config.debugConfigSync("[ConfigSync] config.yml reloaded after reactive pull.");
            }
            case "bot-names.yml" -> {
                BotNameConfig.reload();
                Config.debugConfigSync("[ConfigSync] bot-names.yml reloaded after reactive pull.");
            }
            case "language/en.yml" -> {
                Lang.reload();
                Config.debugConfigSync("[ConfigSync] language/en.yml reloaded after reactive pull.");
            }
            default -> Config.debugConfigSync("[ConfigSync] Unknown file for reactive reload: " + fileName);
        }
    }

    private void broadcastAlertLocally(String message) {
        Component line = Lang.get("alert-received", "message", message);
        Bukkit.getServer().broadcast(line);
    }
}
