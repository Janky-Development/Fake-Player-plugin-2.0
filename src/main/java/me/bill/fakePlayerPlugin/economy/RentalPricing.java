package me.bill.fakePlayerPlugin.economy;

import java.util.List;

import org.bukkit.command.CommandSender;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.permission.Perm;

/**
 * The single source of truth for what a rental costs. Pure arithmetic over config + the payer's
 * permissions, so every entry point (command, GUI, quotes) shows and charges the same number.
 *
 * <p>Total = (slot fee when a new bot is bought + hourly price x hours) reduced by the better of the
 * bulk-discount tier reached and the payer's {@code fpp.rent.discount.<n>} permission (they do not
 * stack), or zero for {@code fpp.rent.free}.
 */
public final class RentalPricing {

    private RentalPricing() {}

    /** A priced purchase; every field is already rounded to cents. */
    public record Quote(
            int hours, double slotFee, double hourFee, double discountPercent, double total, boolean free) {}

    /** Prices a purchase for {@code payer} from live config and permissions. */
    public static Quote quote(CommandSender payer, int hours, boolean newBot) {
        return quote(payer, hours, newBot, null);
    }

    /**
     * Like {@link #quote(CommandSender, int, boolean)}, but a package with a fixed price replaces the
     * computed subtotal (discounts and {@code fpp.rent.free} still apply).
     */
    public static Quote quote(CommandSender payer, int hours, boolean newBot, Double fixedSubtotal) {
        Quote q = compute(
                hours,
                newBot ? Config.rentalPricePerBotSlot() : 0.0,
                Config.rentalPricePerHour(),
                Config.rentalBulkDiscounts(),
                Perm.resolveRentDiscount(payer),
                Perm.has(payer, Perm.RENT_FREE));
        if (fixedSubtotal == null) return q;
        double total = q.free() ? 0.0 : fixedSubtotal * (1.0 - q.discountPercent() / 100.0);
        return new Quote(hours, 0.0, round(fixedSubtotal), q.discountPercent(), round(total), q.free());
    }

    /** Pure pricing math (unit-tested). {@code tiers} are {@code {minHours, percent}} sorted ascending. */
    public static Quote compute(
            int hours,
            double slotFee,
            double perHour,
            List<int[]> tiers,
            double permissionDiscount,
            boolean free) {
        double hourFee = perHour * Math.max(0, hours);
        double tierDiscount = 0;
        for (int[] tier : tiers) {
            if (hours >= tier[0]) tierDiscount = Math.max(tierDiscount, tier[1]);
        }
        double discount = Math.max(0, Math.min(100, Math.max(tierDiscount, permissionDiscount)));
        double total = free ? 0.0 : (slotFee + hourFee) * (1.0 - discount / 100.0);
        return new Quote(hours, round(slotFee), round(hourFee), discount, round(total), free);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
