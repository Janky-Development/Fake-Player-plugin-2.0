package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.ProfileProperty;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.network.ViewerPacketGuard;
import me.bill.fakePlayerPlugin.util.AttributeCompat;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.TextUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

public final class FakePlayerBody {

    public static final String VISUAL_PDC_VALUE = "fpp-visual";

    public static final String NAMETAG_PDC_VALUE = "fpp-nametag";

    /**
     * NMS team used only to hide bots' vanilla over-head names (both rows live in the text_display).
     * Folia's Scoreboard/Team API is documented as entirely broken - global state the project hasn't
     * implemented for regionised ticking - so this team is built directly from NMS and is
     * deliberately never registered with Bukkit's/the server's real scoreboard. It exists purely as
     * a data holder for building {@code ClientboundSetPlayerTeamPacket}s, which are pushed to
     * clients by hand (see {@link #hideVanillaNameNow}/{@link #unhideVanillaNameNow}).
     */
    private static final String HIDDEN_NAME_TEAM = "fpp_hidden_names";

    private static volatile PlayerTeam hideTeam;

    /** Bot names currently hidden - the source of truth, since we don't rely on the team object's own player set. */
    private static final Set<String> hiddenNames = ConcurrentHashMap.newKeySet();

    /** Viewers (real players) who have already been sent the team-create packet for {@link #hideTeam}. */
    private static final Set<UUID> viewersKnowingTeam = ConcurrentHashMap.newKeySet();

    /**
     * Names whose vanilla name tag must never be shown to any viewer. Registered before the body is
     * even created and kept until it is removed; {@link ViewerPacketGuard} refuses to let any other
     * team packet pull these entries off {@link #HIDDEN_NAME_TEAM} on a client.
     */
    private static final Set<String> protectedNames = ConcurrentHashMap.newKeySet();

    private FakePlayerBody() {}

    private static PlayerTeam hideTeam() {
        PlayerTeam t = hideTeam;
        if (t != null) return t;
        synchronized (FakePlayerBody.class) {
            if (hideTeam != null) return hideTeam;
            try {
                MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
                PlayerTeam team = new PlayerTeam(server.getScoreboard(), HIDDEN_NAME_TEAM);
                team.setNameTagVisibility(Team.Visibility.NEVER);
                team.setCollisionRule(Team.CollisionRule.ALWAYS);
                hideTeam = team;
                ViewerPacketGuard.protect(team, protectedNames);
            } catch (Throwable t2) {
                FppLogger.warn("FakePlayerBody: failed to build hide-nametag team (vanilla names will show): "
                        + t2.getMessage());
            }
            return hideTeam;
        }
    }

    /**
     * Brings a newly-joined (or newly-synced) viewer up to date on the hide-team and every bot
     * currently hidden from it. Since the team is never registered with the real scoreboard, vanilla
     * login-sync never tells the client about it - this call is the only way they learn.
     */
    public static void syncHiddenNamesTo(Player viewer) {
        PlayerTeam team = hideTeam();
        if (team == null) return;
        viewersKnowingTeam.add(viewer.getUniqueId());
        PacketHelper.sendTeamCreate(viewer, team);
        for (String name : hiddenNames) {
            PacketHelper.sendTeamMembership(viewer, team, name, true);
        }
    }

    /** Forgets a disconnected viewer so a future rejoin gets a fresh full sync. */
    public static void forgetViewer(UUID viewerUuid) {
        viewersKnowingTeam.remove(viewerUuid);
    }

    private static void ensureViewerKnowsTeam(Player viewer, PlayerTeam team) {
        if (viewersKnowingTeam.add(viewer.getUniqueId())) {
            PacketHelper.sendTeamCreate(viewer, team);
        }
    }

    public static Player spawn(FakePlayer fp, Location loc) {
        return spawn(fp, loc, -1);
    }

    public static Player spawn(FakePlayer fp, Location loc, int initialPing) {
        if (loc == null || loc.getWorld() == null) {
            FppLogger.warn("FakePlayerBody.spawn: location or world is null for " + fp.getName());
            return null;
        }

        protectedNames.add(fp.getName());
        guardAllViewers();
        try {
            FppLogger.debug("FakePlayerBody.spawn: START for '" + fp.getName() + "' at " + loc + " (Folia="
                    + NmsPlayerSpawner.isFoliaServer() + ")");

            Player player = NmsPlayerSpawner.spawnFakePlayer(
                    fp.getUuid(),
                    fp.getName(),
                    fp.getResolvedSkin(),
                    loc.getWorld(),
                    loc.getX(),
                    loc.getY(),
                    loc.getZ(),
                    loc.getYaw(),
                    loc.getPitch(),
                    initialPing,
                    shouldLoadSavedPlayerData(fp));

            if (player == null) {
                FppLogger.warn("FakePlayerBody.spawn: NmsPlayerSpawner returned null for '" + fp.getName() + "'");
                return null;
            }

            FppLogger.debug("FakePlayerBody.spawn: NmsPlayerSpawner returned player, calling finalizeSpawnedBody...");
            finalizeSpawnedBody(fp, player);
            FppLogger.debug("FakePlayerBody.spawn: SUCCESS for '" + fp.getName() + "'");
            return player;

        } catch (Exception e) {
            FppLogger.error("FakePlayerBody.spawn failed for '" + fp.getName() + "': " + e.getMessage());
            FppLogger.debug("  Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            return null;
        }
    }

    /**
     * Callback-based body spawn compatibility wrapper.
     */
    public static void spawnAsync(FakePlayer fp, Location loc, Consumer<Player> callback) {
        spawnAsync(fp, loc, -1, callback);
    }

    public static void spawnAsync(FakePlayer fp, Location loc, int initialPing, Consumer<Player> callback) {
        if (loc == null || loc.getWorld() == null) {
            callback.accept(null);
            return;
        }
        protectedNames.add(fp.getName());
        guardAllViewers();
        NmsPlayerSpawner.spawnFakePlayerAsync(
                fp.getUuid(),
                fp.getName(),
                fp.getResolvedSkin(),
                loc.getWorld(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                initialPing,
                shouldLoadSavedPlayerData(fp),
                player -> {
                    if (player == null) {
                        FppLogger.warn("FakePlayerBody.spawnAsync: NmsPlayerSpawner returned null for " + fp.getName());
                        callback.accept(null);
                        return;
                    }
                    try {
                        finalizeSpawnedBody(fp, player);
                    } catch (Exception e) {
                        FppLogger.error("FakePlayerBody.spawnAsync finalize failed for "
                                + fp.getName()
                                + ": "
                                + e.getMessage());
                    }
                    callback.accept(player);
                });
    }

    /**
     * Puts the packet guard on every real viewer before a bot is created, so the server's own
     * "player joined" tab entry for the bot is already rewritten when it is broadcast.
     */
    private static void guardAllViewers() {
        for (Player viewer : Bukkit.getOnlinePlayers()) PacketHelper.installViewerGuard(viewer);
    }

    private static boolean shouldLoadSavedPlayerData(FakePlayer fp) {
        return fp != null && Boolean.TRUE.equals(fp.getMetadata("fpp.explicit-uuid-spawn"));
    }

    private static void finalizeSpawnedBody(FakePlayer fp, Player player) {
        // Hide the vanilla name tag the moment the body exists - not only when the text_display
        // spawns - so there is no window (or failure path) where the real name tag can render.
        protectedNames.add(fp.getName());
        hideVanillaName(fp);
        try {
            FakePlayerPlugin pl = FakePlayerPlugin.getInstance();
            if (pl != null && pl.getFakePlayerManager() != null)
                pl.getFakePlayerManager().unlistBotEverywhere(fp);
        } catch (Throwable ignored) {
        }
        if (Config.tabListEnabled()) {
            try {
                player.playerListName(Component.text(fp.getTabListName()));
            } catch (Throwable t) {
                FppLogger.debug("FakePlayerBody: list name failed for " + fp.getName() + ": " + t);
            }
        }
        try {
            if (FakePlayerManager.FAKE_PLAYER_KEY != null) {
                player.getPersistentDataContainer().remove(new NamespacedKey(FakePlayerPlugin.getInstance(), "npc"));
                player.getPersistentDataContainer()
                        .set(
                                FakePlayerManager.FAKE_PLAYER_KEY,
                                PersistentDataType.STRING,
                                VISUAL_PDC_VALUE + ":" + fp.getName());
            }
        } catch (Exception e) {
            FppLogger.debug("FakePlayerBody.spawn: PDC tag failed for " + fp.getName() + ": " + e.getMessage());
        }

        player.setGravity(true);
        player.setInvulnerable(false);
        player.setNoDamageTicks(0);
        player.setCollidable(true);
        player.setCanPickupItems(fp.isPickUpItemsEnabled());

        String displayName = fp.getRawDisplayName() != null ? fp.getRawDisplayName() : fp.getDisplayName();
        if (displayName != null && !displayName.isEmpty()) {
            try {
                player.displayName(TextUtil.colorize(displayName));
            } catch (Exception e) {
                FppLogger.debug("Failed to set display name for " + fp.getName() + ": " + e.getMessage());
            }
        }

        try {
            var maxHpAttr = player.getAttribute(AttributeCompat.MAX_HEALTH);
            if (maxHpAttr != null) {
                double hp = Config.maxHealth();
                maxHpAttr.setBaseValue(hp);
                player.setHealth(hp);
            }
        } catch (Exception ignored) {
        }

        player.setAllowFlight(false);
        player.setFlying(false);

        boolean shouldNudgeDown = true;
        try {
            shouldNudgeDown = !player.isInWater() && !player.isInLava();
        } catch (Throwable ignored) {
        }
        if (shouldNudgeDown) {
            player.setVelocity(new Vector(0, -0.001, 0));
        }

        Config.debug("FakePlayerBody: spawned "
                + fp.getName()
                + " (gravity=true, damageable="
                + Config.bodyDamageable()
                + ", flying=false)");

        applyPaperSkin(player, fp.getResolvedSkin());

        spawnNametag(fp, player);
    }

    public static void removeAll(FakePlayer fp) {
        removeAll(fp, false, "body_remove");
    }

    public static void removeAll(FakePlayer fp, String reason) {
        removeAll(fp, false, reason);
    }

    public static void removeAllFast(FakePlayer fp) {
        removeAll(fp, true, "body_remove");
    }

    public static void removeAllFast(FakePlayer fp, String reason) {
        removeAll(fp, true, reason);
    }

    public static void removeAllWithoutSaving(FakePlayer fp) {
        removeAll(fp, true, "body_remove");
    }

    public static void removeAllWithoutSaving(FakePlayer fp, String reason) {
        removeAll(fp, true, reason);
    }

    private static void removeAll(FakePlayer fp, boolean fast, String reason) {
        if (fp == null) return;
        removeNametag(fp);
        try {
            Player player = fp.getPlayer();
            if (player != null && player.isOnline()) {
                if (fast) {
                    Config.debugNmsBot("FakePlayerBody.removeAllFast: '" + fp.getName() + "' - reason: " + reason);
                    NmsPlayerSpawner.removeFakePlayerFast(player, reason);
                } else {
                    Config.debugNmsBot("FakePlayerBody.removeAll: '" + fp.getName() + "' - reason: " + reason);
                    NmsPlayerSpawner.removeFakePlayer(player, reason);
                }
            } else {
                Config.debugNmsBot(
                        "FakePlayerBody.removeAll skipped: player is null or offline for '" + fp.getName() + "'");
            }
        } catch (Exception e) {
            FppLogger.error("FakePlayerBody.removeAll failed for "
                    + (fp.getName() != null ? fp.getName() : "?")
                    + " (reason: " + reason + "): "
                    + e.getMessage());
        }
    }

    public static void resolveAndFinish(
            Plugin plugin, FakePlayer fp, Location loc, Runnable onReady, @Nullable Runnable onSkinApplied) {
        FakePlayerPlugin fpp = FakePlayerPlugin.getInstance();
        SkinManager skinManager = fpp != null ? fpp.getSkinManager() : null;
        if (skinManager == null) {
            FppScheduler.runAtLocation(plugin, loc, () -> {
                onReady.run();
                if (onSkinApplied != null) onSkinApplied.run();
            });
            return;
        }

        SkinProfile resolved = fp.getResolvedSkin();
        if (resolved == null || !resolved.isValid()) {
            FppScheduler.runAtLocation(plugin, loc, onReady);
            skinManager.resolveEffectiveSkin(
                    fp,
                    skin -> FppScheduler.runAtLocation(plugin, loc, () -> {
                        Player body = fp.getPlayer();
                        if (body != null && body.isOnline()) {
                            applyResolvedSkin(plugin, fp, body);
                        }
                        if (skin != null && skin.isValid() && onSkinApplied != null) {
                            onSkinApplied.run();
                        }
                    }));
            return;
        }

        skinManager.resolveEffectiveSkin(
                fp,
                skin -> FppScheduler.runAtLocation(plugin, loc, () -> {
                    onReady.run();
                    if (skin != null && skin.isValid() && onSkinApplied != null) {
                        onSkinApplied.run();
                    }
                }));
    }

    public static void resolveAndFinish(Plugin plugin, FakePlayer fp, Location loc, Runnable onReady) {
        resolveAndFinish(plugin, fp, loc, onReady, null);
    }

    public static void applyResolvedSkin(Plugin plugin, FakePlayer fp, Entity body) {
        if (!(body instanceof Player player)) return;
        FakePlayerPlugin fpp = FakePlayerPlugin.getInstance();
        applyPaperSkin(player, fp.getResolvedSkin());
    }

    private static void applyPaperSkin(Player bot, SkinProfile skin) {
        if (skin == null || !skin.isValid()) {
            Config.debugSkinPool(
                    "applyPaperSkin(" + bot.getName() + "): no valid resolved skin to apply - stays default.");
            return;
        }
        Config.debugSkinPool("applyPaperSkin(" + bot.getName() + "): applying skin (" + skin.getSource() + ", folia="
                + NmsPlayerSpawner.isFoliaServer() + ").");
        FakePlayerPlugin fpp = FakePlayerPlugin.getInstance();
        if (NmsPlayerSpawner.isFoliaServer()) {
            NmsPlayerSpawner.applySkinToGameProfile(bot, skin);
            NmsPlayerSpawner.forceAllSkinParts(bot);
            return;
        }
        if (fpp != null && fpp.getSkinManager() != null) {
            FakePlayer fp = fpp.getFakePlayerManager() != null
                    ? fpp.getFakePlayerManager().getByUuid(bot.getUniqueId())
                    : null;
            if (fp != null && fpp.getSkinManager().applySkinFromProfile(fp, skin)) {
                Config.debugSkin("FakePlayerBody: skin-manager skin applied to "
                        + bot.getName()
                        + " ("
                        + skin.getSource()
                        + ")");
                return;
            }
        }
        try {
            var profile = bot.getPlayerProfile();
            profile.removeProperty("textures");
            profile.setProperty(new ProfileProperty(
                    "textures", skin.getValue(), skin.getSignature() != null ? skin.getSignature() : ""));
            bot.setPlayerProfile(profile);
            Config.debugSkin("FakePlayerBody: paper skin applied to " + bot.getName() + " (" + skin.getSource() + ")");

            NmsPlayerSpawner.forceAllSkinParts(bot);
        } catch (Exception e) {
            FppLogger.debug("FakePlayerBody: paper skin apply failed for " + bot.getName() + ": " + e.getMessage());
        }
    }

    public static void removeNametag(FakePlayer fp) {
        if (fp == null) return;
        unhideVanillaName(fp);
        removeNametagEntity(fp);
    }

    /** Removes only the text_display; the vanilla name stays hidden (no flicker on respawn/refresh). */
    private static void removeNametagEntity(FakePlayer fp) {
        if (fp == null) return;
        for (Entity extra : fp.getNametagExtraEntities()) removeEntityQuietly(extra);
        fp.setNametagExtraEntities(null);
        Entity tag = fp.getNametagEntity();
        fp.setNametagEntity(null);
        removeEntityQuietly(tag);
    }

    private static void removeEntityQuietly(Entity tag) {
        if (tag == null) return;
        if (NmsPlayerSpawner.isFoliaServer()) {
            FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
            if (plugin != null) {
                FppScheduler.runAtEntity(plugin, tag, () -> {
                    try {
                        tag.remove();
                    } catch (Throwable ignored) {
                    }
                });
                return;
            }
        }
        try {
            tag.remove();
        } catch (Throwable ignored) {
        }
    }

    /** Row indices, bottom to top. */
    private static final int ROW_OWNER = 0;

    private static final int ROW_NAME = 1;
    private static final int ROW_ACTIVITY = 2;

    /**
     * Spawns the name tag as three separate {@link TextDisplay} rows - activity (top), name
     * (middle) and the mandatory "bot by &lt;owner&gt;" disclosure (bottom) - each with its own
     * background plate, like stacked vanilla name tags, and hides the bot's vanilla over-head name.
     * The name row is the primary entity ({@link FakePlayer#getNametagEntity()}); the other two are
     * kept in {@link FakePlayer#getNametagExtraEntities()}.
     */
    public static Entity spawnNametag(FakePlayer fp, Entity body) {
        if (fp == null || body == null || !Config.nametagSecondLineEnabled()) return null;
        Location base = body.getLocation();
        if (base.getWorld() == null) return null;
        removeNametagEntity(fp);
        try {
            hideVanillaName(fp);
            TextDisplay owner = spawnRow(fp, base, ROW_OWNER);
            TextDisplay name = spawnRow(fp, base, ROW_NAME);
            TextDisplay activity = spawnRow(fp, base, ROW_ACTIVITY);
            fp.setNametagExtraEntities(java.util.List.of(activity, owner));
            fp.setNametagEntity(name);
            return name;
        } catch (Throwable e) {
            FppLogger.debug("FakePlayerBody.spawnNametag failed for " + fp.getName() + ": " + e.getMessage());
            removeNametagEntity(fp);
            return null;
        }
    }

    private static TextDisplay spawnRow(FakePlayer fp, Location base, int row) {
        int interp = Math.max(0, Config.nametagInterpolationTicks());
        Location loc = rowLocation(base, row);
        return base.getWorld().spawn(loc, TextDisplay.class, td -> {
            td.text(buildRow(fp, row));
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(false);
            td.setShadowed(false);
            // Each row has its own translucent black plate, like a vanilla name tag.
            td.setBackgroundColor(Color.fromARGB(Config.nametagBackgroundAlpha(), 0, 0, 0));
            td.setPersistent(false);
            td.setInvulnerable(true);
            td.setTeleportDuration(interp);
            if (FakePlayerManager.FAKE_PLAYER_KEY != null) {
                td.getPersistentDataContainer()
                        .set(
                                FakePlayerManager.FAKE_PLAYER_KEY,
                                PersistentDataType.STRING,
                                NAMETAG_PDC_VALUE + ":" + fp.getName());
            }
        });
    }

    private static Location rowLocation(Location botLoc, int row) {
        return botLoc.clone().add(0, Config.nametagSecondLineYOffset() + row * Config.nametagRowSpacing(), 0);
    }

    /** True while every row of the tag still exists in the world. */
    public static boolean nametagIntact(FakePlayer fp) {
        Entity primary = fp.getNametagEntity();
        if (primary == null || !primary.isValid()) return false;
        java.util.List<Entity> extras = fp.getNametagExtraEntities();
        if (extras.size() != 2) return false;
        for (Entity e : extras) if (e == null || !e.isValid()) return false;
        return true;
    }

    /**
     * Rebuilds the tag text (e.g. after an owner change). The text_display is entity state, so on
     * Folia the actual mutation must run on the region thread that currently owns it - callers may
     * invoke this from anywhere (a command, an async callback, the global-region activity-refresh
     * loop), so the dispatch happens here rather than being the caller's responsibility.
     */
    public static void refreshNametag(FakePlayer fp) {
        if (fp == null) return;
        // Cheap, idempotent self-heal: re-assert the vanilla-name-hiding team membership on every
        // refresh (every ~10 ticks, driven by the activity-label loop) in case the one-time hide at
        // spawn didn't stick (e.g. a scoreboard that wasn't ready yet, or a missed broadcast).
        hideVanillaName(fp);
        updateRow(fp, fp.getNametagEntity(), ROW_NAME);
        java.util.List<Entity> extras = fp.getNametagExtraEntities();
        if (extras.size() == 2) {
            updateRow(fp, extras.get(0), ROW_ACTIVITY);
            updateRow(fp, extras.get(1), ROW_OWNER);
        }
    }

    private static void updateRow(FakePlayer fp, Entity entity, int row) {
        if (!(entity instanceof TextDisplay td)) return;
        if (NmsPlayerSpawner.isFoliaServer()) {
            FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
            if (plugin == null) return;
            FppScheduler.runAtEntity(plugin, td, () -> {
                if (td.isValid()) td.text(buildRow(fp, row));
            });
            return;
        }
        if (td.isValid()) td.text(buildRow(fp, row));
    }

    /**
     * Re-asserts that this bot's vanilla over-head name is hidden, independent of the text_display
     * refresh cycle. Minecraft only allows one scoreboard team per player at a time, so anything else
     * that puts the bot on a <em>different</em> team (another plugin's own prefix/nametag-color
     * system, an admin running {@code /scoreboard teams join}, …) silently evicts it from ours -
     * bringing the real name back - without ever touching the text_display, so
     * {@link #refreshNametag} alone wouldn't catch it for a bot whose activity label never changes.
     * Callers should invoke this unconditionally on a steady cadence (not gated on label change) so
     * the hide genuinely never lapses for more than one sweep interval, regardless of cause.
     */
    public static void reassertVanillaNameHidden(FakePlayer fp) {
        hideVanillaName(fp);
    }

    private static void hideVanillaName(FakePlayer fp) {
        // Building/sending packets touches no per-entity region state, but keep this on the global
        // region on Folia anyway for consistency with the rest of the visual-sync pipeline.
        if (NmsPlayerSpawner.isFoliaServer()) {
            FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
            if (plugin != null) {
                FppScheduler.runSync(plugin, () -> hideVanillaNameNow(fp));
                return;
            }
        }
        hideVanillaNameNow(fp);
    }

    private static void hideVanillaNameNow(FakePlayer fp) {
        try {
            PlayerTeam team = hideTeam();
            if (team == null) return;
            String name = fp.getName();
            hiddenNames.add(name);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                ensureViewerKnowsTeam(viewer, team);
                PacketHelper.sendTeamMembership(viewer, team, name, true);
            }
        } catch (Throwable t) {
            FppLogger.debug("hideVanillaNameNow failed for " + fp.getName() + ": " + t);
        }
    }

    private static void unhideVanillaName(FakePlayer fp) {
        if (NmsPlayerSpawner.isFoliaServer()) {
            FakePlayerPlugin plugin = FakePlayerPlugin.getInstance();
            if (plugin != null) {
                FppScheduler.runSync(plugin, () -> unhideVanillaNameNow(fp));
                return;
            }
        }
        unhideVanillaNameNow(fp);
    }

    private static void unhideVanillaNameNow(FakePlayer fp) {
        try {
            String name = fp.getName();
            protectedNames.remove(name);
            // removeNametag() (and therefore this) unconditionally runs before EVERY spawn, including
            // a bot's very first one - where its name was never added to any client's team roster.
            // The vanilla client's Scoreboard#removePlayerFromTeam throws (and disconnects!) if asked
            // to remove a name that isn't currently a member, so only broadcast REMOVE for a name that
            // really was hidden.
            boolean wasHidden = hiddenNames.remove(name);
            if (!wasHidden) return;
            PlayerTeam team = hideTeam;
            if (team == null) return;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                PacketHelper.sendTeamMembership(viewer, team, name, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Keeps every row of the tag glued above the moving bot. Called from the tick loop. */
    public static void moveNametag(FakePlayer fp, Location botLoc) {
        if (fp == null || botLoc == null) return;
        Entity primary = fp.getNametagEntity();
        if (primary == null) return;
        if (!primary.isValid()) {
            fp.setNametagEntity(null);
            return;
        }
        Player body = fp.getPlayer();
        boolean riding = body != null && body.isInsideVehicle();
        moveRow(primary, rowLocation(botLoc, ROW_NAME), riding);
        java.util.List<Entity> extras = fp.getNametagExtraEntities();
        if (extras.size() == 2) {
            moveRow(extras.get(0), rowLocation(botLoc, ROW_ACTIVITY), riding);
            moveRow(extras.get(1), rowLocation(botLoc, ROW_OWNER), riding);
        }
    }

    private static void moveRow(Entity tag, Location target, boolean riding) {
        if (tag == null || !tag.isValid()) return;
        try {
            // A passenger is positioned by its vehicle every tick; smoothing the tag over several
            // ticks on top of that makes it visibly trail behind. Snap (1 tick) while riding.
            if (tag instanceof TextDisplay td) {
                int want = riding ? 1 : Math.max(0, Config.nametagInterpolationTicks());
                if (td.getTeleportDuration() != want) td.setTeleportDuration(want);
            }
            if (NmsPlayerSpawner.isFoliaServer() || tag.getWorld() != target.getWorld()) {
                tag.teleportAsync(target);
            } else {
                tag.teleport(target);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Component buildRow(FakePlayer fp, int row) {
        String owner = fp.getSpawnedBy() != null && !fp.getSpawnedBy().isBlank() ? fp.getSpawnedBy() : "?";
        Component indicatorRow =
                TextUtil.colorize(Config.nametagSecondLineFormat().replace("{owner}", owner));
        // Dark gray reads better on the translucent plate; the old default (#9691AB) is mapped to it
        // so existing configs pick the change up without an edit.
        TextColor legacyGray = TextColor.fromHexString("#9691AB");
        if (legacyGray.equals(indicatorRow.color())) {
            indicatorRow = indicatorRow.color(NamedTextColor.DARK_GRAY);
        }
        TextColor indicatorColor = indicatorRow.color() != null ? indicatorRow.color() : NamedTextColor.DARK_GRAY;
        switch (row) {
            case ROW_ACTIVITY:
                return Component.text(BotActivity.currentLabel(fp))
                        .color(indicatorColor)
                        .decoration(TextDecoration.ITALIC, true);
            case ROW_NAME: {
                String rawName = fp.getRawDisplayName() != null ? fp.getRawDisplayName() : fp.getDisplayName();
                Component nameRow = TextUtil.colorize(rawName != null && !rawName.isBlank() ? rawName : fp.getName());
                if (nameRow.color() == null) nameRow = nameRow.color(TextColor.fromHexString("#A78BFA"));
                return nameRow;
            }
            default:
                return indicatorRow;
        }
    }
}
