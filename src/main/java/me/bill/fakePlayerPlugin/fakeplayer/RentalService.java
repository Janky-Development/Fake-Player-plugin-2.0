package me.bill.fakePlayerPlugin.fakeplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.economy.EconomyManager;
import me.bill.fakePlayerPlugin.economy.RentalPricing;
import me.bill.fakePlayerPlugin.economy.RentalPurchases;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppScheduler;

/**
 * The rental life-cycle, swept on a timer: warns an owner once as time runs low, renews bots that
 * opted into auto-renew, and despawns a bot once its paid time (plus any configured grace period) has
 * run out.
 *
 * <p>It runs regardless of whether economy purchasing is enabled -
 * {@code /fpp rent give} can hand out time with no economy plugin at all - so it only looks at which
 * bots actually carry an expiry.
 */
public final class RentalService {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final Map<UUID, Integer> autoRenew = new ConcurrentHashMap<>();
    private int taskId = -1;

    public RentalService(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        if (taskId >= 0) return;
        var db = plugin.getDatabaseManager();
        if (db != null) {
            db.loadRentalAutoRenew().forEach((uuid, hours) -> {
                try {
                    autoRenew.put(UUID.fromString(uuid), hours);
                } catch (IllegalArgumentException ignored) {
                }
            });
        }
        long periodTicks = Math.max(100L, Config.rentalSweepIntervalSeconds() * 20L);
        taskId = FppScheduler.runSyncRepeatingWithId(plugin, this::sweep, periodTicks, periodTicks);
    }

    public void shutdown() {
        if (taskId >= 0) {
            FppScheduler.cancelTask(taskId);
            taskId = -1;
        }
    }

    // ── auto-renew ──────────────────────────────────────────────────────────

    /** Hours this bot renews by automatically, or 0 when auto-renew is off. */
    public int getAutoRenewHours(UUID botUuid) {
        return autoRenew.getOrDefault(botUuid, 0);
    }

    /** {@code hours <= 0} switches auto-renew off. Persisted across restarts. */
    public void setAutoRenewHours(UUID botUuid, int hours) {
        if (hours > 0) autoRenew.put(botUuid, hours);
        else autoRenew.remove(botUuid);
        var db = plugin.getDatabaseManager();
        if (db != null) db.setRentalAutoRenew(botUuid.toString(), Math.max(0, hours));
    }

    // ── sweep ───────────────────────────────────────────────────────────────

    private void sweep() {
        long now = System.currentTimeMillis();
        long warnThresholdMs = Config.rentalWarnMinutesBeforeExpiry() * 60_000L;
        long leadMs = Config.rentalAutoRenewLeadMinutes() * 60_000L;
        long graceMs = Config.rentalExpiryGraceSeconds() * 1000L;

        // Snapshot first: despawning inside the loop would mutate manager.getActivePlayers().
        List<FakePlayer> expired = new ArrayList<>();
        for (FakePlayer fp : new ArrayList<>(manager.getActivePlayers())) {
            Long expiresAt = fp.getRentalExpiresAt();
            if (expiresAt == null) continue;
            if (hasUnlimitedRental(fp)) continue;

            long remaining = expiresAt - now;

            // Auto-renew before the clock actually runs out.
            if (remaining <= leadMs && getAutoRenewHours(fp.getUuid()) > 0 && tryAutoRenew(fp)) continue;

            if (remaining <= -graceMs) {
                expired.add(fp);
                continue;
            }
            if (warnThresholdMs > 0 && !fp.isRentalWarningSent() && remaining <= warnThresholdMs && remaining > 0) {
                warnOwner(fp, remaining);
                fp.setRentalWarningSent(true);
            }
        }

        for (FakePlayer fp : expired) expire(fp);
    }

    /** @return true when the bot was renewed (so this sweep skips it). */
    private boolean tryAutoRenew(FakePlayer fp) {
        int hours = getAutoRenewHours(fp.getUuid());
        EconomyManager economy = plugin.getEconomyManager();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(fp.getSpawnedByUuid());
        Player online = owner.getPlayer();

        double cost;
        if (online != null) {
            cost = RentalPricing.quote(online, hours, false).total();
        } else {
            cost = RentalPricing.compute(
                            hours, 0.0, Config.rentalPricePerHour(), Config.rentalBulkDiscounts(), 0.0, false)
                    .total();
        }

        boolean paid = cost <= 0;
        if (!paid && economy != null) {
            paid = economy.charge(owner, cost, "rental auto-renew " + fp.getName() + " +" + hours + "h")
                    == EconomyManager.ChargeResult.OK;
        }
        if (!paid) {
            // Stop trying every sweep; the bot now expires normally and the owner is told why.
            setAutoRenewHours(fp.getUuid(), 0);
            if (online != null) online.sendMessage(Lang.get("rent-auto-failed", "name", fp.getDisplayName()));
            return false;
        }

        long expiry = RentalPurchases.grantHours(plugin, fp, hours);
        if (online != null) {
            online.sendMessage(Lang.get(
                    "rent-auto-renewed",
                    "name",
                    fp.getDisplayName(),
                    "hours",
                    String.valueOf(hours),
                    "cost",
                    economy != null ? economy.format(cost) : String.valueOf(cost),
                    "remaining",
                    RentalPurchases.formatRemaining(expiry - System.currentTimeMillis())));
        }
        return true;
    }

    /** True if the bot's owner currently holds the bypass permission - checked live, not cached. */
    private boolean hasUnlimitedRental(FakePlayer fp) {
        Player owner = Bukkit.getPlayer(fp.getSpawnedByUuid());
        return owner != null && owner.isOnline() && owner.hasPermission(Perm.RENT_UNLIMITED);
    }

    private void warnOwner(FakePlayer fp, long remainingMs) {
        Player owner = Bukkit.getPlayer(fp.getSpawnedByUuid());
        if (owner == null || !owner.isOnline()) return;
        long minutes = Math.max(1, remainingMs / 60_000L);
        owner.sendMessage(Lang.get("rental-warning", "name", fp.getDisplayName(), "minutes", String.valueOf(minutes)));
    }

    private void expire(FakePlayer fp) {
        Player owner = Bukkit.getPlayer(fp.getSpawnedByUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(Lang.get("rental-expired", "name", fp.getDisplayName()));
        }
        Config.debugRental(
                "Rental time expired for bot '" + fp.getName() + "' (owner=" + fp.getSpawnedBy() + ") - despawning.");
        setAutoRenewHours(fp.getUuid(), 0);
        manager.delete(fp.getName(), "rental_expired");
    }
}
