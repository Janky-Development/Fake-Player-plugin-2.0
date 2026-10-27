package me.bill.fakePlayerPlugin.listener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotDamageEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotDeathEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotInventoryEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotTargetEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotBroadcast;
import me.bill.fakePlayerPlugin.fakeplayer.ChunkLoader;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerBody;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.PacketHelper;
import me.bill.fakePlayerPlugin.util.AttributeCompat;
import me.bill.fakePlayerPlugin.util.FppScheduler;

public class FakePlayerEntityListener implements Listener {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final ChunkLoader chunkLoader;
    private final Map<UUID, Long> recentBotDeaths = new ConcurrentHashMap<>();
    private final Map<EntityDamageEvent, Double> preDamageHealth = Collections.synchronizedMap(new WeakHashMap<>());

    public FakePlayerEntityListener(FakePlayerPlugin plugin, FakePlayerManager manager, ChunkLoader chunkLoader) {
        this.plugin = plugin;
        this.manager = manager;
        this.chunkLoader = chunkLoader;
    }

    /** Bots never show a death/kill message in chat - matches the rest of the chat-suppression design. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBotDeathMessage(PlayerDeathEvent event) {
        if (!isFakeBotBody(event.getEntity())) return;
        event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        FakePlayer fp = manager.getByEntity(p);
        if (fp == null) {
            FakePlayer byUuid = manager.getByUuid(p.getUniqueId());
            if (byUuid == null) return;
            manager.stampFakePlayerMarker(p, byUuid);
            fp = byUuid;
            Config.debugNmsDamage("recovered bot damage target by UUID after entity lookup miss: "
                    + byUuid.getName()
                    + " world="
                    + p.getWorld().getName()
                    + " entityId="
                    + p.getEntityId());
        }

        preDamageHealth.put(event, p.getHealth());
        if (event.isCancelled()) return;

        Entity damager = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            damager = byEntity.getDamager();
        }

        var damageEvent = new FppBotDamageEvent(new FppBotImpl(fp), event.getDamage(), event.getCause(), damager);
        Bukkit.getPluginManager().callEvent(damageEvent);
        if (damageEvent.isCancelled()) {
            Config.debugNmsDamage("FPP API cancelled bot damage for " + fp.getName());
            event.setCancelled(true);
            return;
        }
        if (damageEvent.getDamage() != event.getDamage()) {
            event.setDamage(damageEvent.getDamage());
        }

        if (!Config.bodyDamageable()) {
            if (event instanceof EntityDamageByEntityEvent) {
                Config.debugNmsDamage("bodyDamageable=false cancelled entity damage for " + fp.getName());
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getTarget() instanceof Player targetPlayer)) return;
        FakePlayer fp = manager.getByEntity(targetPlayer);
        if (fp == null) return;
        var targetEvt = new FppBotTargetEvent(new FppBotImpl(fp), event.getEntity());
        Bukkit.getPluginManager().callEvent(targetEvt);
        if (targetEvt.isCancelled()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityDamageConfirmed(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player bot)) return;
        FakePlayer fp = manager.getByEntity(bot);
        if (fp == null) {
            fp = manager.getByUuid(bot.getUniqueId());
            if (fp == null) return;
            manager.stampFakePlayerMarker(bot, fp);
        }
        Double beforeHealth = preDamageHealth.remove(event);
        if (event.isCancelled()) {
            debugDamageDecision(bot, event, beforeHealth, "cancelled");
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage > 0.0) {
            debugDamageDecision(bot, event, beforeHealth, "normal");
            fp.addDamageTaken(finalDamage);
        } else {
            debugDamageDecision(bot, event, beforeHealth, "zero-final");
        }
    }

    private void debugDamageDecision(Player bot, EntityDamageEvent event, Double beforeHealth, String decision) {
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        Config.debugNmsDamage("bot="
                + bot.getName()
                + " uuid="
                + bot.getUniqueId()
                + " entityId="
                + bot.getEntityId()
                + " world="
                + bot.getWorld().getName()
                + " decision="
                + decision
                + " cause="
                + event.getCause()
                + " cancelled="
                + event.isCancelled()
                + " raw="
                + String.format("%.3f", event.getDamage())
                + " final="
                + String.format("%.3f", event.getFinalDamage())
                + " healthBefore="
                + (beforeHealth != null ? String.format("%.3f", beforeHealth) : "unknown")
                + " healthNow="
                + String.format("%.3f", bot.getHealth())
                + " noDamageTicks="
                + bot.getNoDamageTicks()
                + " invulnerable="
                + bot.isInvulnerable()
                + " gameMode="
                + bot.getGameMode()
                + " damager="
                + (damager != null ? damager.getType() + ":" + damager.getName() : "none"));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!isFakeBotBody(event.getEntity())) return;
        event.setCancelled(true);
        FakePlayer fp = manager.getByEntityId(event.getEntity().getEntityId());
        Config.debug("Blocked portal traversal for bot body: " + (fp != null ? fp.getName() : "unknown"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!isFakeBotBody(event.getPlayer())) return;
        event.setCancelled(true);
        Config.debug("Blocked portal traversal for bot: " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (!isFakeBotBody(event.getEntity())) return;
        // EntityTeleportEvent is the super-type of PlayerTeleportEvent.
        // For player-type bots the PlayerTeleportEvent handler below takes
        // precedence (it can distinguish TeleportCause); skip here so that
        // PLUGIN/COMMAND cross-world teleports are not blocked.
        if (event.getEntity() instanceof Player) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;
        if (!from.getWorld().equals(to.getWorld())) {
            event.setCancelled(true);
            Config.debug("Blocked cross-world teleport for bot body: "
                    + event.getEntity().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        FakePlayer fp = manager.getByEntity(event.getPlayer());
        if (fp == null) fp = manager.getByUuid(event.getPlayer().getUniqueId());
        if (fp == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;

        // Block non-plugin cross-world teleports (e.g. NETHER_PORTAL/END_PORTAL).
        if (!from.getWorld().equals(to.getWorld())) {
            if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                    && event.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) {
                event.setCancelled(true);
                Config.debug("Blocked cross-world PlayerTeleportEvent for bot body: "
                        + event.getPlayer().getName());
                return;
            }

            manager.refreshAfterTeleport(fp, event.getPlayer(), to.clone());
            return;
        }

        manager.refreshAfterTeleport(fp, event.getPlayer(), to.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!isFakeBotBody(event.getEntity())) return;

        FakePlayer fp = manager.getByEntity(event.getEntity());
        if (fp == null) return;
        long now = System.currentTimeMillis();
        Long lastDeath = recentBotDeaths.put(fp.getUuid(), now);
        if (lastDeath != null && now - lastDeath < 1500L) {
            Config.debugNmsBot("Ignored duplicate death event for bot '" + fp.getName() + "'");
            return;
        }

        if (Config.suppressDrops()) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }

        // Bot death (kill) messages were removed with the chat system.
        Player killer = event.getEntity().getKiller();

        fp.incrementDeathCount();
        fp.setAlive(false);

        // Fire API death event.
        var fppApi = plugin.getFppApi();
        if (fppApi != null) {
            FppBotDeathEvent deathEvt = new FppBotDeathEvent(new FppBotImpl(fp), killer);
            Bukkit.getPluginManager().callEvent(deathEvt);
        }

        final String name = fp.getName();
        final Player deadPlayer = (event.getEntity() instanceof Player p2) ? p2 : null;
        manager.removeFromEntityIndex(event.getEntity().getEntityId());

        if (fp.isRespawnOnDeath()) {

            int delay = Math.max(0, Config.respawnDelay());
            if (chunkLoader != null) chunkLoader.releaseForBot(fp);

            final Location respawnFailLoc = event.getEntity().getLocation();
            fp.setPlayer(null);
            fp.setRespawning(true);
            final UUID botUuid = fp.getUuid();

            FppScheduler.runSyncLater(
                    plugin,
                    () -> {
                        if (deadPlayer == null || !deadPlayer.isOnline()) {
                            fp.setRespawning(false);
                            manager.removeByName(name, respawnFailLoc);
                            recentBotDeaths.remove(botUuid);
                            return;
                        }

                        deadPlayer.spigot().respawn();

                        FppScheduler.runSyncLater(
                                plugin,
                                () -> {
                                    Player newEntity = Bukkit.getPlayer(botUuid);
                                    if (newEntity == null || newEntity.isDead()) {

                                        fp.setRespawning(false);
                                        if (newEntity == null) manager.removeByName(name, respawnFailLoc);
                                        recentBotDeaths.remove(botUuid);
                                        return;
                                    }

                                    fp.setPlayer(newEntity);
                                    fp.setAlive(true);
                                    recentBotDeaths.remove(botUuid);
                                    manager.registerEntityIndex(newEntity.getEntityId(), fp);

                                    try {
                                        if (FakePlayerManager.FAKE_PLAYER_KEY != null) {
                                            newEntity
                                                    .getPersistentDataContainer()
                                                    .set(
                                                            FakePlayerManager.FAKE_PLAYER_KEY,
                                                            PersistentDataType.STRING,
                                                            FakePlayerBody.VISUAL_PDC_VALUE + ":" + fp.getName());
                                        }
                                    } catch (Exception ignored) {
                                    }

                                    try {
                                        var attr = newEntity.getAttribute(AttributeCompat.MAX_HEALTH);
                                        if (attr != null) {
                                            double hp = Config.maxHealth();
                                            attr.setBaseValue(hp);
                                            newEntity.setHealth(hp);
                                        }
                                    } catch (Exception ignored) {
                                    }

                                    fp.setRespawning(false);
                                },
                                1L);
                    },
                    delay);

        } else {

            if (chunkLoader != null) chunkLoader.releaseForBot(fp);
            String deathDespawnName = BotBroadcast.resolveDisplayName(fp);
            final UUID deathDespawnUuid = fp.getUuid();
            Config.debugNmsBot("Bot '" + name + "' died - scheduling despawn (respawnOnDeath=false)");
            if (deadPlayer != null) {
                manager.markDespawning(deadPlayer.getUniqueId(), deathDespawnName);
            }
            FppScheduler.runSyncLater(
                    plugin,
                    () -> {
                        manager.fireSyntheticQuit(fp, PlayerQuitEvent.QuitReason.DISCONNECTED);

                        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                        PacketHelper.broadcastTabListRemove(fp, onlinePlayers);
                        Config.debugNmsBot("Sent tab-list remove for '" + name + "'");

                        var vc2 = plugin.getVelocityChannel();
                        if (vc2 != null) {
                            vc2.broadcastBotDespawn(fp.getUuid());
                            Config.debugNmsBot("Broadcasted bot despawn for '" + name + "'");
                        }
                        Location loc = deadPlayer.getLocation();
                        FppScheduler.runAtLocation(plugin, loc, () -> {
                            if (deadPlayer != null) {
                                try {
                                    if (manager.isExplicitUuidBot(fp)) {
                                        NmsPlayerSpawner.removeFakePlayerFast(deadPlayer, "death");
                                        Config.debugNmsBot("Removed dead bot '" + name + "' (fast, explicit uuid)");
                                    } else {
                                        NmsPlayerSpawner.removeFakePlayer(deadPlayer, "death");
                                        Config.debugNmsBot("Removed dead bot '" + name + "' (normal)");
                                    }
                                } finally {
                                    fp.setPlayer(null);
                                    manager.restoreExplicitUuidSourceState(fp);
                                    manager.clearDespawningNextTick(deathDespawnUuid);
                                }
                            }
                            // fp's entity reference is already null at this point (cleared above), so
                            // pass the pre-removal death location through explicitly for the despawn effect.
                            manager.removeByName(name, loc);
                            recentBotDeaths.remove(fp.getUuid());
                            Config.debugNmsBot("Removed bot '" + name + "' from manager after death");
                        });
                    },
                    20L);
        }
    }

    /**
     * Tells the bot's owner and whoever killed it (if online, and not the same person twice) that it
     * died and was removed (respawn-on-death is off). No one else sees this.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBotRespawn(PlayerRespawnEvent event) {
        FakePlayer fp = manager.getByName(event.getPlayer().getName());
        if (fp == null || !fp.isRespawning()) return;
        Location spawnLoc = fp.getSpawnLocation();
        if (spawnLoc != null && spawnLoc.getWorld() != null) {
            event.setRespawnLocation(spawnLoc);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!isFakeBotBody(event.getEntity())) return;

        FakePlayer fp = manager.getByUuid(event.getEntity().getUniqueId());
        if (fp == null) return;

        var invEvt = new FppBotInventoryEvent(
                new FppBotImpl(fp),
                FppBotInventoryEvent.Action.PICKUP,
                event.getItem().getItemStack(),
                -1);
        Bukkit.getPluginManager().callEvent(invEvt);
        if (invEvt.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        if (!Config.bodyPickUpItems() || !fp.isPickUpItemsEnabled()) {
            event.setCancelled(true);
            return;
        }

        // Bot inventory is viewed natively; no manual refresh needed.
    }

    private boolean isFakeBotBody(Entity entity) {
        if (!(entity instanceof Player)) return false;

        return manager.getByEntity(entity) != null;
    }
}
