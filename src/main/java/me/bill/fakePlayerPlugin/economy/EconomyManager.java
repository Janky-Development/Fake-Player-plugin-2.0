package me.bill.fakePlayerPlugin.economy;

import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.Config;

/**
 * Picks and delegates to whichever {@link EconomyProvider} is configured/available, so the rest of
 * the plugin (the rental command, the GUI) never has to know or care which of Vault, "Vault2.0", or
 * ExcellentEconomy is actually installed.
 *
 * <p>{@code economy.provider} in config.yml selects the strategy:
 * <ul>
 *   <li>{@code auto} (default) - Vault (which also transparently covers "Vault2.0" and
 *       Vault-bridged ExcellentEconomy) if its Economy service is registered, else native
 *       ExcellentEconomy if present, else unavailable.</li>
 *   <li>{@code vault} / {@code excellenteconomy} - pin to one specific backend.</li>
 *   <li>{@code none} - economy features are fully disabled (rentals become free/unlimited, gated
 *       purely by permissions - see {@code Config.economyEnabled()}).</li>
 * </ul>
 */
public final class EconomyManager {

    private final VaultEconomyProvider vault = new VaultEconomyProvider();
    private final ExcellentEconomyProvider excellentEconomy = new ExcellentEconomyProvider();

    @Nullable
    private EconomyProvider active() {
        if (!Config.economyEnabled()) return null;
        String mode = Config.economyProvider().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "vault" -> vault.isAvailable() ? vault : null;
            case "excellenteconomy", "excellent-economy" -> excellentEconomy.isAvailable() ? excellentEconomy : null;
            case "none" -> null;
            default -> vault.isAvailable() ? vault : (excellentEconomy.isAvailable() ? excellentEconomy : null);
        };
    }

    /** True when economy is enabled in config AND a working backend is actually available right now. */
    public boolean isAvailable() {
        return active() != null;
    }

    public double getBalance(OfflinePlayer player) {
        EconomyProvider provider = active();
        return provider != null ? provider.getBalance(player) : 0.0;
    }

    public boolean has(OfflinePlayer player, double amount) {
        EconomyProvider provider = active();
        return provider != null && provider.has(player, amount);
    }

    /** Withdraws {@code amount} from the player. Returns false (and touches nothing) if unavailable or insufficient. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        EconomyProvider provider = active();
        if (provider == null || !provider.has(player, amount)) return false;
        return provider.withdraw(player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        EconomyProvider provider = active();
        return provider != null && provider.deposit(player, amount);
    }

    /** Outcome of {@link #charge}. */
    public enum ChargeResult {
        OK,
        /** Economy disabled or no backend present - nothing was touched. */
        UNAVAILABLE,
        INSUFFICIENT,
        /** The backend refused the withdrawal. Nothing was charged. */
        FAILED
    }

    /**
     * Charges {@code amount} for {@code reason}; a non-positive amount is a successful no-op so callers
     * need no free-purchase special case. Every transaction is logged (debug, or INFO with
     * {@code economy.log-transactions}).
     */
    public ChargeResult charge(OfflinePlayer player, double amount, String reason) {
        if (amount <= 0) return ChargeResult.OK;
        EconomyProvider provider = active();
        if (provider == null) return ChargeResult.UNAVAILABLE;
        if (!provider.has(player, amount)) return ChargeResult.INSUFFICIENT;
        if (!provider.withdraw(player, amount)) return ChargeResult.FAILED;
        log("charged " + name(player) + " " + provider.format(amount) + " for " + reason);
        return ChargeResult.OK;
    }

    /** Gives back a previous charge (a spawn failed after paying, etc.). */
    public boolean refund(OfflinePlayer player, double amount, String reason) {
        if (amount <= 0) return true;
        boolean ok = deposit(player, amount);
        log((ok ? "refunded " : "FAILED to refund ") + name(player) + " " + format(amount) + " (" + reason + ")");
        return ok;
    }

    private static String name(OfflinePlayer p) {
        return p.getName() != null ? p.getName() : p.getUniqueId().toString();
    }

    private static void log(String message) {
        if (Config.economyLogTransactions()) {
            me.bill.fakePlayerPlugin.util.FppLogger.info("[Economy] " + message);
        } else {
            Config.debugRental("[Economy] " + message);
        }
    }

    public String format(double amount) {
        EconomyProvider provider = active();
        return provider != null ? provider.format(amount) : String.format(Locale.ROOT, "%.2f", amount);
    }
}
