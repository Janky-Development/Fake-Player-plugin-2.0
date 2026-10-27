package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.economy.EconomyManager;
import me.bill.fakePlayerPlugin.economy.RentalPricing;
import me.bill.fakePlayerPlugin.economy.RentalPurchases;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;

/**
 * Bot rental: pay real economy currency for a bot slot and/or hours of runtime, so a bot
 * auto-despawns once its paid time runs out ({@link me.bill.fakePlayerPlugin.fakeplayer.RentalService}
 * enforces that separately).
 *
 * <p>{@code buy}/{@code extend} are the self-service, economy-charging path. {@code give} is the
 * <em>dynamic</em> entry point the request asked for: it never touches an economy plugin at all, so
 * a server's own custom shop plugin (ShopGUIPlus, EconomyShopGUI, zShop, …) can charge the player
 * however it likes and simply run {@code fpp rent give <player> --new <hours>} as the purchase's
 * reward command - from console, with no FPP economy integration required on their end at all.
 */
public final class RentCommand implements FppCommand {

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    public RentCommand(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public String getName() {
        return "rent";
    }

    @Override
    public String getUsage() {
        return "buy <hours>  |  extend <bot> <hours>  |  quote <hours> [--new]  |  info [bot]  |  auto <bot>"
                + " <on [hours]|off>  |  list [player]  |  give <player> <bot|--new> <hours>  |  clear <bot>";
    }

    @Override
    public String getDescription() {
        return "Rent a bot with real economy currency, billed per hour.";
    }

    @Override
    public String getPermission() {
        return Perm.RENT;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.hasAny(
                sender, Perm.RENT, Perm.RENT_INFO, Perm.RENT_GIVE, Perm.RENT_AUTO, Perm.RENT_ADMIN, Perm.RENT_FREE);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && Config.rentalGuiEnabled() && plugin.getRentGui() != null) {
                if (!plugin.getRentGui().canOpen(player)) {
                    sender.sendMessage(Lang.get("no-permission"));
                    return true;
                }
                plugin.getRentGui().open(player);
                return true;
            }
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "buy" -> buy(sender, rest);
            case "extend" -> extend(sender, rest);
            case "info" -> info(sender, rest);
            case "give" -> give(sender, rest);
            case "clear" -> clear(sender, rest);
            case "gui", "shop", "menu" -> openGui(sender);
            case "packages" -> packages(sender);
            case "package", "pkg" -> pack(sender, rest);
            case "quote", "price" -> quote(sender, rest);
            case "auto" -> auto(sender, rest);
            case "list" -> list(sender, rest);
            default -> {
                sender.sendMessage(Lang.get("rent-usage"));
                yield true;
            }
        };
    }

    // ── buy ──────────────────────────────────────────────────────────────────────────────────

    private boolean buy(CommandSender sender, String[] args) {
        if (!Perm.has(sender, Perm.RENT)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }

        Integer hours = parseHours(sender, args[0]);
        if (hours == null) return true;

        RentalPurchases.Result result = RentalPurchases.buyNew(plugin, player, hours, null, "command");
        sender.sendMessage(result.message());
        return true;
    }

    // ── extend ───────────────────────────────────────────────────────────────────────────────

    private boolean extend(CommandSender sender, String[] args) {
        if (!Perm.has(sender, Perm.RENT)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }

        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("rent-bot-not-found", "name", args[0]));
            return true;
        }
        if (!Perm.hasAny(sender, Perm.ADMIN, Perm.RENT_ADMIN) && !BotAccess.canAdminister(player, fp)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (!fp.isRented() && !Perm.hasAny(sender, Perm.ADMIN, Perm.RENT_ADMIN)) {
            sender.sendMessage(Lang.get("rent-info-permanent", "name", fp.getDisplayName()));
            return true;
        }

        Integer hours = parseHours(sender, args[1]);
        if (hours == null) return true;

        RentalPurchases.Result result = RentalPurchases.extend(plugin, player, fp, hours);
        sender.sendMessage(result.message());
        return true;
    }

    // ── info ─────────────────────────────────────────────────────────────────────────────────

    private boolean info(CommandSender sender, String[] args) {
        if (!Perm.hasAny(sender, Perm.RENT_INFO, Perm.RENT, Perm.ADMIN)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }

        if (args.length >= 1) {
            FakePlayer fp = manager.getByName(args[0]);
            if (fp == null) {
                sender.sendMessage(Lang.get("rent-bot-not-found", "name", args[0]));
                return true;
            }
            if (sender instanceof Player player
                    && !Perm.has(sender, Perm.ADMIN)
                    && !BotAccess.canAdminister(player, fp)) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
            sendInfoLine(sender, fp);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        List<FakePlayer> owned = manager.getBotsOwnedBy(player.getUniqueId());
        boolean any = false;
        for (FakePlayer fp : owned) {
            if (!fp.isRented()) continue;
            sendInfoLine(sender, fp);
            any = true;
        }
        if (!any) sender.sendMessage(Lang.get("rent-info-none"));
        return true;
    }

    private void sendInfoLine(CommandSender sender, FakePlayer fp) {
        if (!fp.isRented()) {
            sender.sendMessage(Lang.get("rent-info-permanent", "name", fp.getDisplayName()));
            return;
        }
        long remaining = RentalPurchases.currentExpiry(fp) - System.currentTimeMillis();
        sender.sendMessage(Lang.get(
                "rent-info-line",
                "name",
                fp.getDisplayName(),
                "remaining",
                RentalPurchases.formatRemaining(remaining)));
    }

    // ── give (dynamic / shop-plugin / admin entry point) ────────────────────────────────────────

    private boolean give(CommandSender sender, String[] args) {
        if (!Perm.has(sender, Perm.RENT_GIVE)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[0]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage(Lang.get("rent-player-not-found", "name", args[0]));
            return true;
        }

        Integer hours = parseHours(sender, args[2]);
        if (hours == null) return true;

        FakePlayer fp;
        if ("--new".equalsIgnoreCase(args[1])) {
            Player online = target.getPlayer();
            if (online == null || !online.isOnline()) {
                sender.sendMessage(Lang.get("rent-give-needs-online", "name", args[0]));
                return true;
            }
            Set<UUID> before = ownedUuids(online);
            int result = manager.spawn(online.getLocation().clone(), 1, online, null, false);
            if (result <= 0) {
                sender.sendMessage(Lang.get("rent-spawn-failed"));
                return true;
            }
            fp = findNewBot(online, before);
            if (fp == null) {
                sender.sendMessage(Lang.get("rent-spawn-failed"));
                return true;
            }
        } else {
            fp = manager.getByName(args[1]);
            if (fp == null) {
                sender.sendMessage(Lang.get("rent-bot-not-found", "name", args[1]));
                return true;
            }
        }

        long finalExpiry = RentalPurchases.grantHours(plugin, fp, hours);

        Config.debugRental("Granted " + hours + "h rental on '" + fp.getName() + "' to " + target.getName()
                + " via /fpp rent give (sender=" + sender.getName() + ")");
        sender.sendMessage(Lang.get(
                "rent-given",
                "name",
                fp.getDisplayName(),
                "player",
                String.valueOf(target.getName()),
                "hours",
                String.valueOf(hours)));

        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            online.sendMessage(Lang.get(
                    "rent-received",
                    "name",
                    fp.getDisplayName(),
                    "hours",
                    String.valueOf(hours),
                    "remaining",
                    RentalPurchases.formatRemaining(finalExpiry - System.currentTimeMillis())));
        }
        return true;
    }

    // ── clear (admin: make a rented bot permanent again) ────────────────────────────────────────

    private boolean clear(CommandSender sender, String[] args) {
        if (!Perm.has(sender, Perm.RENT_GIVE)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }
        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("rent-bot-not-found", "name", args[0]));
            return true;
        }
        RentalPurchases.setRentalExpiry(plugin, fp, null);
        sender.sendMessage(Lang.get("rent-cleared", "name", fp.getDisplayName()));
        return true;
    }

    // ── gui / packages (shop-GUI integration surface) ──────────────────────────────────────────

    private boolean openGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        if (plugin.getRentGui() == null || !plugin.getRentGui().canOpen(player)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        plugin.getRentGui().open(player);
        return true;
    }

    private boolean packages(CommandSender sender) {
        if (!Perm.hasAny(sender, Perm.RENT, Perm.RENT_INFO, Perm.RENT_GIVE, Perm.RENT_FREE)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        EconomyManager economy = plugin.getEconomyManager();
        var all = Config.rentalPackages();
        if (all.isEmpty()) {
            sender.sendMessage(Lang.get("rent-packages-none"));
            return true;
        }
        for (var p : all) {
            RentalPricing.Quote q = RentalPricing.quote(sender, p.hours(), true, p.fixedPrice());
            sender.sendMessage(Lang.get(
                    "rent-package-line",
                    "id",
                    p.id(),
                    "label",
                    p.label(),
                    "hours",
                    String.valueOf(p.hours()),
                    "price",
                    q.free() ? "free" : economy != null ? economy.format(q.total()) : String.valueOf(q.total())));
        }
        return true;
    }

    /**
     * {@code /fpp rent package <id> [<bot>|--new] [--for <player>]} - the one command any shop/GUI
     * plugin needs. {@code --for} charges (and delivers to) another online player and needs
     * {@code fpp.rent.give}; run it from console as the shop's reward action.
     */
    private boolean pack(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }
        var pkg = Config.rentalPackages().stream()
                .filter(p -> p.id().equalsIgnoreCase(args[0]))
                .findFirst()
                .orElse(null);
        if (pkg == null) {
            sender.sendMessage(Lang.get("rent-package-unknown", "id", args[0]));
            return true;
        }

        Player buyer = sender instanceof Player p ? p : null;
        String botArg = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--for") && i + 1 < args.length) {
                if (!Perm.has(sender, Perm.RENT_GIVE)) {
                    sender.sendMessage(Lang.get("no-permission"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[++i]);
                if (target == null) {
                    sender.sendMessage(Lang.get("rent-give-needs-online", "name", args[i]));
                    return true;
                }
                buyer = target;
            } else if (!args[i].equalsIgnoreCase("--new")) {
                botArg = args[i];
            }
        }
        if (buyer == null) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        if (sender == buyer && !Perm.hasAny(sender, Perm.RENT, Perm.RENT_FREE)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (pkg.permission() != null && !buyer.hasPermission(pkg.permission())) {
            sender.sendMessage(Lang.get("rent-package-denied", "id", pkg.id()));
            return true;
        }

        RentalPurchases.Result result;
        if (botArg != null) {
            FakePlayer fp = manager.getByName(botArg);
            if (fp == null) {
                sender.sendMessage(Lang.get("rent-bot-not-found", "name", botArg));
                return true;
            }
            if (!fp.getSpawnedByUuid().equals(buyer.getUniqueId())
                    && !Perm.hasAny(sender, Perm.ADMIN, Perm.RENT_ADMIN)) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
            result = RentalPurchases.extend(plugin, buyer, fp, pkg.hours(), pkg.fixedPrice(), "package");
        } else {
            result = RentalPurchases.buyNew(plugin, buyer, pkg.hours(), pkg.fixedPrice(), "package");
        }
        sender.sendMessage(result.message());
        if (sender != buyer) buyer.sendMessage(result.message());
        return true;
    }

    // ── quote ────────────────────────────────────────────────────────────────────────────────

    private boolean quote(CommandSender sender, String[] args) {
        if (!Perm.hasAny(sender, Perm.RENT, Perm.RENT_INFO, Perm.RENT_FREE)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }
        Integer hours = parseHours(sender, args[0]);
        if (hours == null) return true;

        boolean newBot = args.length >= 2 && args[1].equalsIgnoreCase("--new");
        RentalPricing.Quote q = RentalPricing.quote(sender, hours, newBot);
        EconomyManager economy = plugin.getEconomyManager();
        java.util.function.DoubleFunction<String> fmt =
                v -> economy != null ? economy.format(v) : String.format(Locale.ROOT, "%.2f", v);

        sender.sendMessage(Lang.get(
                q.free() ? "rent-quote-free" : "rent-quote",
                "hours",
                String.valueOf(hours),
                "total",
                fmt.apply(q.total()),
                "slot",
                fmt.apply(q.slotFee()),
                "rate",
                fmt.apply(q.hourFee()),
                "discount",
                trim(q.discountPercent())));
        return true;
    }

    // ── auto-renew ───────────────────────────────────────────────────────────────────────────

    private boolean auto(CommandSender sender, String[] args) {
        if (!Perm.hasAny(sender, Perm.RENT_AUTO, Perm.RENT_ADMIN)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }
        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("rent-bot-not-found", "name", args[0]));
            return true;
        }
        if (sender instanceof Player player
                && !Perm.hasAny(sender, Perm.ADMIN, Perm.RENT_ADMIN)
                && !BotAccess.canAdminister(player, fp)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        if (!fp.isRented()) {
            sender.sendMessage(Lang.get("rent-info-permanent", "name", fp.getDisplayName()));
            return true;
        }
        var service = plugin.getRentalService();
        if (service == null) return true;

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (mode.equals("off")) {
            service.setAutoRenewHours(fp.getUuid(), 0);
            sender.sendMessage(Lang.get("rent-auto-off", "name", fp.getDisplayName()));
            return true;
        }
        if (!mode.equals("on")) {
            sender.sendMessage(Lang.get("rent-usage"));
            return true;
        }
        int hours = Config.rentalAutoRenewDefaultHours();
        if (args.length >= 3) {
            Integer parsed = parseHours(sender, args[2]);
            if (parsed == null) return true;
            hours = parsed;
        }
        service.setAutoRenewHours(fp.getUuid(), hours);
        sender.sendMessage(Lang.get("rent-auto-on", "name", fp.getDisplayName(), "hours", String.valueOf(hours)));
        return true;
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────

    private boolean list(CommandSender sender, String[] args) {
        boolean admin = Perm.hasAny(sender, Perm.ADMIN, Perm.RENT_ADMIN);
        if (!admin && !Perm.hasAny(sender, Perm.RENT_INFO, Perm.RENT)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        List<FakePlayer> bots;
        if (admin && args.length == 0) {
            bots = new ArrayList<>(manager.getActivePlayers());
        } else if (admin) {
            OfflinePlayer target = resolveOfflinePlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Lang.get("rent-player-not-found", "name", args[0]));
                return true;
            }
            bots = manager.getBotsOwnedBy(target.getUniqueId());
        } else if (sender instanceof Player player) {
            bots = manager.getBotsOwnedBy(player.getUniqueId());
        } else {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }

        var service = plugin.getRentalService();
        int shown = 0;
        for (FakePlayer fp : bots) {
            if (!fp.isRented()) continue;
            long remaining = RentalPurchases.currentExpiry(fp) - System.currentTimeMillis();
            int auto = service != null ? service.getAutoRenewHours(fp.getUuid()) : 0;
            sender.sendMessage(Lang.get(
                    "rent-list-line",
                    "name",
                    fp.getDisplayName(),
                    "owner",
                    String.valueOf(fp.getSpawnedBy()),
                    "remaining",
                    RentalPurchases.formatRemaining(remaining),
                    "auto",
                    auto > 0 ? auto + "h" : "off"));
            shown++;
        }
        if (shown == 0) sender.sendMessage(Lang.get("rent-info-none"));
        return true;
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────

    @org.jetbrains.annotations.Nullable
    private Integer parseHours(CommandSender sender, String raw) {
        int hours;
        try {
            hours = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(Lang.get("rent-invalid-hours", "value", raw));
            return null;
        }
        int min = Config.rentalMinHours();
        int max = Config.rentalMaxHours();
        if (hours < min || hours > max) {
            sender.sendMessage(
                    Lang.get("rent-hours-out-of-range", "min", String.valueOf(min), "max", String.valueOf(max)));
            return null;
        }
        return hours;
    }

    private Set<UUID> ownedUuids(Player player) {
        Set<UUID> set = new HashSet<>();
        for (FakePlayer fp : manager.getBotsOwnedBy(player.getUniqueId())) set.add(fp.getUuid());
        return set;
    }

    /** The one bot in {@code owner}'s list that wasn't in {@code before} - i.e. the one just spawned. */
    @org.jetbrains.annotations.Nullable
    private FakePlayer findNewBot(Player owner, Set<UUID> before) {
        for (FakePlayer fp : manager.getBotsOwnedBy(owner.getUniqueId())) {
            if (!before.contains(fp.getUuid())) return fp;
        }
        return null;
    }

    @org.jetbrains.annotations.Nullable
    private OfflinePlayer resolveOfflinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!canUse(sender)) return List.of();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : List.of(
                    "gui", "buy", "extend", "quote", "packages", "package", "info", "auto", "list", "give", "clear")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("auto") && args.length == 3) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (sub.equals("quote") && args.length == 3) return List.of("--new");
        if ((sub.equals("package") || sub.equals("pkg")) && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Config.rentalPackages().stream()
                    .map(p -> p.id())
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        if ((sub.equals("package") || sub.equals("pkg")) && args.length >= 3) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("--for".startsWith(prefix)) out.add("--for");
            if (args[args.length - 2].equalsIgnoreCase("--for")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                }
                return out;
            }
            for (FakePlayer fp : manager.getActivePlayers()) {
                if (fp.isRented() && fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
            }
            return out;
        }
        if (sub.equals("list") && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
            return out;
        }
        if ((sub.equals("extend") || sub.equals("info") || sub.equals("clear") || sub.equals("auto"))
                && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (FakePlayer fp : manager.getActivePlayers()) {
                if (fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
            }
            return out;
        }

        if (sub.equals("give")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                }
                return out;
            }
            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                if ("--new".startsWith(prefix)) out.add("--new");
                for (FakePlayer fp : manager.getActivePlayers()) {
                    if (fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
                }
                return out;
            }
        }

        return List.of();
    }
}
