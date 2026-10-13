package me.bill.fakePlayerPlugin.economy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppRentalPurchaseEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.economy.EconomyManager.ChargeResult;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;

import net.kyori.adventure.text.Component;

/**
 * Shared "charge the player and extend a bot's rental" logic, so every place that offers it - {@code
 * /fpp rent}, the per-bot settings GUI, auto-renew - charges and caps time identically. Prices come from
 * {@link RentalPricing}; money moves through {@link EconomyManager#charge}.
 */
public final class RentalPurchases {

    private RentalPurchases() {}

    /** Outcome of a purchase; {@code bot} is the rented bot on success (new or extended). */
    public record Result(boolean success, Component message, @Nullable FakePlayer bot) {
        public Result(boolean success, Component message) {
            this(success, message, null);
        }
    }

    /** Charges {@code payer} the quoted price and extends {@code bot}'s rental by {@code hours}. */
    public static Result extend(FakePlayerPlugin plugin, Player payer, FakePlayer bot, int hours) {
        return extend(plugin, payer, bot, hours, null, "command");
    }

    /**
     * Extends {@code bot}; {@code fixedPrice} (a package price) replaces the computed subtotal and
     * {@code source} ({@code command|gui|package|…}) is reported to {@link FppRentalPurchaseEvent}.
     */
    public static Result extend(
            FakePlayerPlugin plugin,
            Player payer,
            FakePlayer bot,
            int hours,
            @Nullable Double fixedPrice,
            String source) {
        RentalPricing.Quote quote = RentalPricing.quote(payer, hours, false, fixedPrice);

        FppRentalPurchaseEvent event = new FppRentalPurchaseEvent(
                payer, FppRentalPurchaseEvent.Type.EXTEND, new FppBotImpl(bot), hours, quote.total(), source);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return new Result(false, Lang.get("rent-cancelled"));
        double price = event.getPrice();

        ChargeResult charge = charge(plugin, payer, price, "rental extend " + bot.getName() + " +" + hours + "h");
        if (charge != ChargeResult.OK) return new Result(false, chargeFailure(plugin, payer, charge, price));

        long finalExpiry = grantHours(plugin, bot, hours);
        Config.debugRental("Extended rental bot '" + bot.getName() + "' by " + hours + "h for " + payer.getName());
        return new Result(
                true,
                Lang.get(
                        "rent-extended",
                        "name",
                        bot.getDisplayName(),
                        "hours",
                        String.valueOf(hours),
                        "cost",
                        plugin.getEconomyManager().format(price),
                        "remaining",
                        formatRemaining(finalExpiry - System.currentTimeMillis())),
                bot);
    }

    /**
     * Rents a brand-new bot for {@code player} at their location: limit check, price event, charge,
     * spawn, expiry - refunding automatically if the spawn fails. One implementation for the command,
     * the GUI and packages.
     */
    public static Result buyNew(
            FakePlayerPlugin plugin, Player player, int hours, @Nullable Double fixedPrice, String source) {
        if (!player.hasPermission(Perm.RENT_UNLIMITED)) {
            int cap = rentLimit(player);
            if (rentedCount(plugin, player) >= cap) {
                return new Result(false, Lang.get("rent-limit-reached", "limit", String.valueOf(cap)));
            }
        }

        RentalPricing.Quote quote = RentalPricing.quote(player, hours, true, fixedPrice);
        FppRentalPurchaseEvent event = new FppRentalPurchaseEvent(
                player, FppRentalPurchaseEvent.Type.NEW, null, hours, quote.total(), source);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return new Result(false, Lang.get("rent-cancelled"));
        double price = event.getPrice();

        ChargeResult charge = charge(plugin, player, price, "rental new bot " + hours + "h");
        if (charge != ChargeResult.OK) return new Result(false, chargeFailure(plugin, player, charge, price));

        var manager = plugin.getFakePlayerManager();
        Set<UUID> before = new HashSet<>();
        for (FakePlayer fp : manager.getBotsOwnedBy(player.getUniqueId())) before.add(fp.getUuid());

        int result = manager.spawn(player.getLocation().clone(), 1, player, null, false);
        FakePlayer bot = null;
        if (result > 0) {
            for (FakePlayer fp : manager.getBotsOwnedBy(player.getUniqueId())) {
                if (!before.contains(fp.getUuid())) {
                    bot = fp;
                    break;
                }
            }
        }
        if (bot == null) {
            // Nothing was spawned (bot cap, no names left…) - give the money straight back.
            refund(plugin, player, price, "rental spawn failed");
            return new Result(false, Lang.get("rent-spawn-failed"));
        }

        setRentalExpiry(plugin, bot, System.currentTimeMillis() + hours * 3_600_000L);
        bot.setRentalWarningSent(false);
        Config.debugRental("Bought rental bot '" + bot.getName() + "' for " + player.getName() + " (" + hours
                + "h, cost=" + price + ", via " + source + ")");
        return new Result(
                true,
                Lang.get(
                        "rent-bought",
                        "name",
                        bot.getDisplayName(),
                        "hours",
                        String.valueOf(hours),
                        "cost",
                        plugin.getEconomyManager().format(price)),
                bot);
    }

    public static void refund(FakePlayerPlugin plugin, Player payer, double amount, String reason) {
        EconomyManager economy = plugin.getEconomyManager();
        if (economy != null) economy.refund(payer, amount, reason);
    }

    /** Free-form charge honouring "no economy needed when the price is zero". */
    private static ChargeResult charge(FakePlayerPlugin plugin, Player payer, double amount, String reason) {
        if (amount <= 0) return ChargeResult.OK;
        EconomyManager economy = plugin.getEconomyManager();
        if (economy == null) return ChargeResult.UNAVAILABLE;
        return economy.charge(payer, amount, reason);
    }

    public static Component chargeFailure(FakePlayerPlugin plugin, Player payer, ChargeResult r, double cost) {
        EconomyManager economy = plugin.getEconomyManager();
        return switch (r) {
            case UNAVAILABLE -> Lang.get("rent-economy-unavailable");
            case INSUFFICIENT -> Lang.get(
                    "rent-insufficient-funds",
                    "cost",
                    economy.format(cost),
                    "balance",
                    economy.format(economy.getBalance(payer)));
            default -> Lang.get("rent-transaction-failed");
        };
    }

    /** Adds {@code hours} (capped by the bank limit) to the bot's rental and returns the new expiry. */
    public static long grantHours(FakePlayerPlugin plugin, FakePlayer bot, int hours) {
        long now = System.currentTimeMillis();
        long base = Math.max(now, currentExpiry(bot));
        long cap = now + Config.rentalMaxBankedHours() * 3_600_000L;
        long expiry = Math.min(base + hours * 3_600_000L, cap);
        setRentalExpiry(plugin, bot, expiry);
        bot.setRentalWarningSent(false);
        return expiry;
    }

    public static void setRentalExpiry(FakePlayerPlugin plugin, FakePlayer bot, Long epochMillis) {
        bot.setRentalExpiresAt(epochMillis);
        var db = plugin.getDatabaseManager();
        if (db != null) db.updateBotRentalExpiry(bot.getUuid().toString(), epochMillis);
    }

    public static long currentExpiry(FakePlayer bot) {
        Long existing = bot.getRentalExpiresAt();
        return existing != null ? existing : 0L;
    }

    /** Rented bots a player currently owns that count toward their rental cap. */
    public static int rentedCount(FakePlayerPlugin plugin, Player owner) {
        return (int) plugin.getFakePlayerManager().getBotsOwnedBy(owner.getUniqueId()).stream()
                .filter(FakePlayer::isRented)
                .count();
    }

    /** The player's rented-bot cap: {@code fpp.rent.limit.<n>} if held, else the config default. */
    public static int rentLimit(Player owner) {
        int perm = Perm.resolveRentLimit(owner);
        return perm >= 0 ? perm : Config.rentalMaxBotsPerPlayer();
    }

    public static String formatRemaining(long millis) {
        if (millis <= 0) return "0m";
        long totalMinutes = millis / 60_000L;
        long d = totalMinutes / (60 * 24);
        long h = (totalMinutes / 60) % 24;
        long m = totalMinutes % 60;
        if (d > 0) return d + "d " + h + "h";
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}
