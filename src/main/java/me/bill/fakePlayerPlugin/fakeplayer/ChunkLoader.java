package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotChunkLoadEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps each bot's reported client options (chiefly its render distance) in line with the config and
 * its own override. The bot is an ordinary player to the server: its chunks are loaded, generated and
 * ticked by the server's native player chunk loader around where the bot stands - exactly what happens
 * for a real client that is AFK. This class only plays the part of the client's options menu (see
 * {@link BotClientOptions}); it holds no chunk tickets and changes no server setting.
 */
public final class ChunkLoader {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    /** Last chunk each bot was seen in, to raise {@link FppBotChunkLoadEvent} on entering a new one. */
    private final Map<UUID, long[]> lastChunk = new HashMap<>();

    public ChunkLoader(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;

        long interval = Math.max(1L, Config.chunkLoadingUpdateInterval());
        FppScheduler.runSyncRepeating(plugin, this::tick, interval, interval);
    }

    private void tick() {
        int active = manager.getActivePlayers().size();
        for (FakePlayer fp : manager.getActivePlayers()) {
            Player bot = fp.getPlayer();
            if (bot == null || !bot.isOnline()) continue;
            FppScheduler.runAtEntity(plugin, bot, () -> tickBot(fp, bot, active));
        }
    }

    private void tickBot(FakePlayer fp, Player bot, int activeBots) {
        if (!bot.isOnline()) return;
        ServerPlayer nms = ((CraftPlayer) bot).getHandle();

        BotClientOptions.sync(nms, BotClientOptions.effectiveViewDistance(fp, activeBots));

        // Announce each newly entered chunk once (API event), as a client would see its own chunk change.
        Location loc = bot.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        long[] prev = lastChunk.get(fp.getUuid());
        if (prev == null || prev[0] != cx || prev[1] != cz) {
            lastChunk.put(fp.getUuid(), new long[] {cx, cz});
            if (bot.getWorld().isChunkLoaded(cx, cz)) {
                Bukkit.getPluginManager()
                        .callEvent(new FppBotChunkLoadEvent(new FppBotImpl(fp), bot.getWorld().getChunkAt(cx, cz)));
            }
        }
    }

    /** Forgets per-bot state (despawn). */
    public void releaseForBot(FakePlayer fp) {
        lastChunk.remove(fp.getUuid());
    }

    public void releaseAll() {
        lastChunk.clear();
    }
}
