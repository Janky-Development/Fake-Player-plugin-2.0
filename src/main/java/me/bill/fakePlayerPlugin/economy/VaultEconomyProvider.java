package me.bill.fakePlayerPlugin.economy;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import me.bill.fakePlayerPlugin.config.Config;

/**
 * Talks to whatever plugin currently holds Bukkit's {@code net.milkbowl.vault.economy.Economy}
 * service registration. This one integration transparently covers three of FPP's four named
 * targets at once:
 *
 * <ul>
 *   <li><b>Vault</b> (milkbowl/Vault) - the interface this class talks to <i>is</i> Vault's own.</li>
 *   <li><b>"Vault2.0" (shalom25/Vault2.0)</b> - inspected directly: its {@code plugin.yml} declares
 *       {@code name: "Vault"} (the literal same plugin name as real Vault) and its {@code SimpleEconomy}
 *       class {@code implements net.milkbowl.vault.economy.Economy} - i.e. it's a from-scratch
 *       reimplementation of classic Vault's exact interface, registered into the exact same
 *       {@code ServicesManager} slot. No separate integration code is needed or possible to
 *       distinguish it from real Vault; this class covers both automatically.</li>
 *   <li><b>ExcellentEconomy</b> - its own docs confirm it "works right out of the box with Vault to
 *       hook into all your economy stuff automatically" when Vault is present, which also means
 *       registering (or being read through) this same service.</li>
 * </ul>
 *
 * <p>Reflective (no compile-time Vault dependency), following the same defensive pattern as
 * {@link me.bill.fakePlayerPlugin.fakeplayer.ViaVersionCompat}: every call fails silently if Vault
 * (or a Vault-compatible provider) isn't present.
 */
public final class VaultEconomyProvider implements EconomyProvider {

    private volatile boolean ready = false;
    private volatile boolean broken = false;

    private Class<?> economyClass;
    private Method isEnabledMethod;
    private Method formatMethod;
    private Method getBalanceMethod;
    private Method hasMethod;
    private Method withdrawMethod;
    private Method depositMethod;
    private Method transactionSuccessMethod;

    private synchronized void init() {
        if (ready || broken) return;
        try {
            economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            isEnabledMethod = economyClass.getMethod("isEnabled");
            formatMethod = economyClass.getMethod("format", double.class);
            getBalanceMethod = economyClass.getMethod("getBalance", OfflinePlayer.class);
            hasMethod = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            withdrawMethod = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            depositMethod = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);

            Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            transactionSuccessMethod = responseClass.getMethod("transactionSuccess");

            ready = true;
            Config.debugStartup("VaultEconomyProvider: Economy API classes resolved.");
        } catch (Throwable t) {
            broken = true;
            Config.debugStartup("VaultEconomyProvider: unavailable - " + t);
        }
    }

    private boolean ensureReady() {
        if (ready) return true;
        if (broken) return false;
        init();
        return ready;
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String backingPluginName() {
        RegisteredServiceProvider<?> reg = registration();
        return reg != null ? reg.getPlugin().getName() : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RegisteredServiceProvider<?> registration() {
        if (!ensureReady()) return null;
        return Bukkit.getServicesManager().getRegistration((Class) economyClass);
    }

    private Object provider() {
        RegisteredServiceProvider<?> reg = registration();
        if (reg == null) return null;
        try {
            Object economy = reg.getProvider();
            return (Boolean) isEnabledMethod.invoke(economy) ? economy : null;
        } catch (Throwable t) {
            Config.debugStartup("VaultEconomyProvider.provider() failed: " + t);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return provider() != null;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        Object economy = provider();
        if (economy == null) return 0.0;
        try {
            return (double) getBalanceMethod.invoke(economy, player);
        } catch (Throwable t) {
            Config.debugStartup("VaultEconomyProvider.getBalance failed: " + t);
            return 0.0;
        }
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Object economy = provider();
        if (economy == null) return false;
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (Throwable t) {
            Config.debugStartup("VaultEconomyProvider.has failed: " + t);
            return false;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Object economy = provider();
        if (economy == null) return false;
        try {
            Object response = withdrawMethod.invoke(economy, player, amount);
            return (boolean) transactionSuccessMethod.invoke(response);
        } catch (Throwable t) {
            Config.debugStartup("VaultEconomyProvider.withdraw failed: " + t);
            return false;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Object economy = provider();
        if (economy == null) return false;
        try {
            Object response = depositMethod.invoke(economy, player, amount);
            return (boolean) transactionSuccessMethod.invoke(response);
        } catch (Throwable t) {
            Config.debugStartup("VaultEconomyProvider.deposit failed: " + t);
            return false;
        }
    }

    @Override
    public String format(double amount) {
        Object economy = provider();
        if (economy == null) return String.format("%.2f", amount);
        try {
            return (String) formatMethod.invoke(economy, amount);
        } catch (Throwable t) {
            return String.format("%.2f", amount);
        }
    }
}
