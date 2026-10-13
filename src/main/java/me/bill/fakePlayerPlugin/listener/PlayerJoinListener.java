package me.bill.fakePlayerPlugin.listener;

import java.lang.reflect.Field;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerBody;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PacketHelper;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppScheduler;

public class PlayerJoinListener implements Listener {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    private static volatile Field hasPlayedBeforeField = null;

    private static volatile Field firstPlayedField = null;
    private static volatile Field lastPlayedField = null;

    public PlayerJoinListener(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinEarly(PlayerJoinEvent event) {

        // Earliest point a real viewer's connection can be guarded: still before the server sends
        // them the existing players' tab-list entries, so bots are never listed to them under a
        // bare name even for a frame.
        me.bill.fakePlayerPlugin.fakeplayer.PacketHelper.installViewerGuard(event.getPlayer());

        UUID uuid = event.getPlayer().getUniqueId();
        Location requestedSpawn = NmsPlayerSpawner.consumePendingSpawnLocation(uuid);
        if (requestedSpawn != null && requestedSpawn.getWorld() != null) {
            NmsPlayerSpawner.correctPendingSpawnLocation(event.getPlayer(), requestedSpawn);
        }

        FakePlayer fp = manager.getByUuid(uuid);
        if (fp == null) return;

        // Bots never announce themselves: no join message.
        event.joinMessage(null);

        if (event.getPlayer().getFirstPlayed() != 0L) {
            forceHasPlayedBefore(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitEarly(PlayerQuitEvent event) {
        // Bot leave messages are removed; real players' quit messages are left untouched.
        if (manager.getByUuid(event.getPlayer().getUniqueId()) == null) return;
        event.quitMessage(null);
    }

    private static void forceHasPlayedBefore(Player player) {
        try {
            if (hasPlayedBeforeField == null) {
                hasPlayedBeforeField = findField(player.getClass(), "hasPlayedBefore");
            }
            if (hasPlayedBeforeField != null) {
                hasPlayedBeforeField.setBoolean(player, true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stampFirstPlayed(Player player) {
        try {
            if (firstPlayedField == null) {
                firstPlayedField = findField(player.getClass(), "firstPlayed");
            }
            if (lastPlayedField == null) {
                lastPlayedField = findField(player.getClass(), "lastPlayed");
            }
            long now = System.currentTimeMillis();
            if (firstPlayedField != null) {
                long fp = firstPlayedField.getLong(player);
                if (fp == 0L) firstPlayedField.setLong(player, now - 60_000L);
            }
            if (lastPlayedField != null) {
                long lp = lastPlayedField.getLong(player);
                if (lp == 0L) lastPlayedField.setLong(player, now - 1_000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {

        FakePlayer fp = manager.getByUuid(event.getPlayer().getUniqueId());
        if (fp != null) {
            return;
        }

        try {
            var upd = plugin.getUpdateNotification();
            if (upd != null) {
                var p = event.getPlayer();
                if (Perm.has(p, Perm.OP) || Perm.has(p, Perm.NOTIFY)) {
                    try {
                        p.sendMessage(upd);
                    } catch (NoSuchMethodError | NoClassDefFoundError e) {
                        p.sendMessage(upd.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (plugin.isVersionUnsupported() && Perm.has(event.getPlayer(), Perm.OP)) {
                event.getPlayer()
                        .sendMessage(Lang.get("version-unsupported-admin", "version", plugin.getDetectedMcVersion()));
            }
        } catch (Throwable ignored) {
        }

        if (manager.getCount() == 0 && plugin.getRemoteBotCache().count() == 0) return;

        try {
            var vc = plugin.getVelocityChannel();
            if (vc != null && vc.hasPendingResync()) {
                vc.clearPendingResync();
                FppScheduler.runSyncLater(plugin, vc::broadcastResyncRequest, 5L);
            }
        } catch (Throwable ignored) {
        }

        try {
            var vc = plugin.getVelocityChannel();
            if (vc != null && vc.hasPendingProxyBroadcast()) {
                vc.clearPendingProxyBroadcast();
                FppScheduler.runSyncLater(
                        plugin,
                        () -> {
                            for (FakePlayer botFp : manager.getActivePlayers()) {
                                vc.broadcastBotSpawn(botFp);
                            }
                        },
                        10L);
            }
        } catch (Throwable ignored) {
        }

        try {
            var vc = plugin.getVelocityChannel();
            if (vc != null) {
                vc.flushPendingServerStats();
            }
        } catch (Throwable ignored) {
        }

        long delayTicks = manager.isRestorationInProgress() ? 40L : 5L;

        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    try {
                        manager.syncToPlayer(event.getPlayer());
                    } catch (Throwable ignored) {
                    }

                    if (Config.isNetworkMode() && Config.tabListEnabled()) {
                        try {
                            var cache = plugin.getRemoteBotCache();
                            if (cache != null) {
                                for (var entry : cache.getAll()) {
                                    PacketHelper.sendTabListAddRaw(
                                            event.getPlayer(),
                                            entry.uuid(),
                                            entry.packetProfileName(),
                                            entry.displayName(),
                                            entry.skinValue(),
                                            entry.skinSignature(),
                                            entry.ping());
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                },
                delayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // The hide-nametag team is never registered with the real scoreboard, so a rejoining client
        // has no memory of it - forget them so the next join re-syncs from scratch.
        FakePlayerBody.forgetViewer(uuid);

        if (manager.hasSyntheticQuit(uuid)) {
            event.quitMessage(null);
            return;
        }

        if (manager.isRenaming(uuid)) {
            return;
        }

        String despawnName = manager.getDespawningDisplayName(uuid);
        if (despawnName != null) {
            return;
        }

        FakePlayer fp = manager.getByUuid(uuid);
        if (fp != null) {
            if (isServerStopping()) return;
            if (manager.isBodyTransitioning(uuid)) return;
            manager.removeByName(fp.getName());
            return;
        }

        String name = event.getPlayer().getName();

        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    try {
                        manager.validateUserBotNames(uuid, name);
                    } catch (Throwable ignored) {
                    }
                },
                2L);
    }

    private static boolean isServerStopping() {
        try {
            Object stopping = Bukkit.class.getMethod("isStopping").invoke(null);
            return stopping instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
