package me.bill.fakePlayerPlugin.fakeplayer.pathfinding;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.lang.Lang;

/**
 * Tracks which players have opted in to seeing a given bot's live pathfinding route rendered as
 * particles (Baritone-style). Purely a viewer preference - never persisted, never a bot property.
 */
public final class PathfindingDebugManager {

    private static final Map<UUID, Set<UUID>> viewersByBot = new ConcurrentHashMap<>();

    private PathfindingDebugManager() {}

    public static boolean isViewing(UUID viewerUuid, UUID botUuid) {
        Set<UUID> viewers = viewersByBot.get(botUuid);
        return viewers != null && viewers.contains(viewerUuid);
    }

    /** Flips the viewer's subscription for this bot and returns the new state. */
    public static boolean toggle(UUID viewerUuid, UUID botUuid) {
        Set<UUID> viewers = viewersByBot.computeIfAbsent(botUuid, k -> ConcurrentHashMap.newKeySet());
        if (!viewers.add(viewerUuid)) {
            viewers.remove(viewerUuid);
            if (viewers.isEmpty()) viewersByBot.remove(botUuid);
            return false;
        }
        return true;
    }

    /** Explicitly sets (rather than flips) the viewer's subscription for this bot. */
    public static void setViewing(UUID viewerUuid, UUID botUuid, boolean viewing) {
        if (viewing) {
            viewersByBot
                    .computeIfAbsent(botUuid, k -> ConcurrentHashMap.newKeySet())
                    .add(viewerUuid);
        } else {
            Set<UUID> viewers = viewersByBot.get(botUuid);
            if (viewers == null) return;
            viewers.remove(viewerUuid);
            if (viewers.isEmpty()) viewersByBot.remove(botUuid);
        }
    }

    /** True if this viewer currently has the path debug view enabled for at least one bot. */
    public static boolean isViewingAny(UUID viewerUuid) {
        for (Set<UUID> viewers : viewersByBot.values()) {
            if (viewers.contains(viewerUuid)) return true;
        }
        return false;
    }

    public static Set<UUID> getViewers(UUID botUuid) {
        Set<UUID> viewers = viewersByBot.get(botUuid);
        return viewers == null ? Set.of() : viewers;
    }

    /**
     * Chat-side counterpart of the particle debug: sent to every viewer subscribed to this bot whenever
     * something goes wrong (stuck, no path, watchdog, mining stall), so the cause is explained.
     */
    public static void sendChat(UUID botUuid, String botName, String langKey, String... args) {
        Set<UUID> viewers = getViewers(botUuid);
        if (viewers.isEmpty()) return;

        String[] merged = new String[args.length + 2];
        merged[0] = "name";
        merged[1] = botName;
        System.arraycopy(args, 0, merged, 2, args.length);

        var message = Lang.get(langKey, merged);
        for (UUID viewerUuid : viewers) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            if (viewer != null && viewer.isOnline()) viewer.sendMessage(message);
        }
    }

    public static void clearBot(UUID botUuid) {
        viewersByBot.remove(botUuid);
    }

    public static void clearViewer(UUID viewerUuid) {
        for (Set<UUID> viewers : viewersByBot.values()) viewers.remove(viewerUuid);
        viewersByBot.values().removeIf(Set::isEmpty);
    }
}
