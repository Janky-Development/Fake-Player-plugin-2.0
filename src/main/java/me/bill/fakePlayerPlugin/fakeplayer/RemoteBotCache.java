package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteBotCache {

    private final ConcurrentHashMap<UUID, RemoteBotEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> serverRealPlayerCounts = new ConcurrentHashMap<>();

    private volatile int networkTotalPlayers = -1;
    private volatile int networkTotalBots = -1;

    public void add(RemoteBotEntry entry) {
        entries.put(entry.uuid(), entry);
    }

    public void remove(UUID uuid) {
        entries.remove(uuid);
    }

    public void removeAllFromServer(String serverId) {
        entries.values().removeIf(e -> serverId.equals(e.serverId()));
        serverRealPlayerCounts.remove(serverId);
    }

    public void clear() {
        entries.clear();
        serverRealPlayerCounts.clear();
    }

    public RemoteBotEntry get(UUID uuid) {
        return entries.get(uuid);
    }

    public Collection<RemoteBotEntry> getAll() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public int count() {
        return entries.size();
    }

    public void setServerRealPlayerCount(String serverId, int count) {
        serverRealPlayerCounts.put(serverId, Math.max(0, count));
    }











    // ── Proxy-pushed totals ────────────────────────────────────────────────────

    public void setNetworkTotalPlayers(int count) {
        this.networkTotalPlayers = Math.max(0, count);
    }

    public void setNetworkTotalBots(int count) {
        this.networkTotalBots = Math.max(0, count);
    }

    public int getNetworkTotalPlayers() {
        return networkTotalPlayers;
    }

    public int getNetworkTotalBots() {
        return networkTotalBots;
    }

    public boolean hasNetworkStats() {
        return networkTotalPlayers >= 0;
    }
}
