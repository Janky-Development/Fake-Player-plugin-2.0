package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.economy.EconomyManager;
import me.bill.fakePlayerPlugin.economy.RentalPackage;
import me.bill.fakePlayerPlugin.economy.RentalPricing;
import me.bill.fakePlayerPlugin.economy.RentalPurchases;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.RentalService;
import me.bill.fakePlayerPlugin.permission.Perm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The built-in rental shop. Three views, one inventory:
 *
 * <ul>
 *   <li><b>shop</b> - the configured {@link RentalPackage}s, each priced for this viewer (slot fee,
 *       hourly rate, bulk/permission discounts all applied); click twice to confirm renting a new bot.</li>
 *   <li><b>rentals</b> - the bots you currently rent, with time left and auto-renew state.</li>
 *   <li><b>bot</b> - one rented bot: the same packages priced as extensions, plus the auto-renew switch.</li>
 * </ul>
 *
 * It only ever calls {@link RentalPurchases}, so charging, price events, limits and refunds behave
 * exactly like {@code /fpp rent} and package commands used by other shop plugins.
 */
public final class RentGui implements Listener {

    private static final String KIND = "rent";
    private static final long CONFIRM_MS = 6_000L;

    private static final int SLOT_INFO = 45;
    private static final int SLOT_NAV = 46;
    private static final int SLOT_RENTALS = 47;

    private enum View {
        SHOP,
        RENTALS,
        BOT
    }

    private static final class Session {
        View view = View.SHOP;
        UUID bot;
        String pendingKey;
        long pendingAt;
    }

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public RentGui(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean canOpen(Player player) {
        return Perm.hasAny(player, Perm.RENT, Perm.RENT_INFO, Perm.RENT_FREE, Perm.RENT_ADMIN);
    }

    public void open(Player player) {
        Session s = new Session();
        sessions.put(player.getUniqueId(), s);
        build(player, s);
        GuiKit.playOpen(player);
    }

    // ── build ────────────────────────────────────────────────────────────────

    private record Card(ItemStack item, Runnable onClick, boolean enabled) {}

    private void build(Player viewer, Session s) {
        List<Card> cards = new ArrayList<>();
        String subtitle;
        switch (s.view) {
            case RENTALS -> {
                subtitle = "my rentals";
                for (FakePlayer fp : ownedRented(viewer)) cards.add(rentalCard(viewer, s, fp));
            }
            case BOT -> {
                FakePlayer fp = manager.getByUuid(s.bot);
                if (fp == null || !fp.isRented() || !mayManage(viewer, fp)) {
                    s.view = View.RENTALS;
                    build(viewer, s);
                    return;
                }
                subtitle = fp.getName();
                for (RentalPackage pkg : packages(viewer)) cards.add(packageCard(viewer, s, pkg, fp));
                cards.add(autoRenewCard(viewer, s, fp));
            }
            default -> {
                subtitle = "rent a bot";
                for (RentalPackage pkg : packages(viewer)) cards.add(packageCard(viewer, s, pkg, null));
                if (cards.isEmpty()) cards.add(new Card(simple(Material.BARRIER, "no packages", GuiKit.OFF_RED,
                        List.of("an admin has not configured any", "rental packages yet.")), () -> {}, false));
            }
        }

        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(GuiKit.DARK_GRAY))
                .append(GuiKit.smallCaps("fpp", GuiKit.ACCENT, false))
                .append(Component.text("] ").color(GuiKit.DARK_GRAY))
                .append(GuiKit.smallCaps(subtitle, GuiKit.DARK_GRAY, false));

        Inventory inv = Bukkit.createInventory(new GuiKit.SimpleHolder(viewer.getUniqueId(), KIND), GuiKit.SIZE, title);
        GuiKit.fillContentBackdrop(inv);

        int shown = Math.min(cards.size(), GuiKit.CONTENT_CAPACITY);
        List<Integer> slots = GuiKit.centeredSlots(shown);
        for (int i = 0; i < shown; i++) inv.setItem(slots.get(i), cards.get(i).item());

        GuiKit.fillFooter(inv);
        inv.setItem(SLOT_INFO, infoCard(viewer));
        if (s.view != View.SHOP) {
            inv.setItem(SLOT_NAV, simple(Material.ARROW, s.view == View.BOT ? "my rentals" : "shop", GuiKit.ACCENT,
                    List.of(s.view == View.BOT ? "back to your rented bots." : "back to the packages.")));
        }
        if (s.view != View.RENTALS) {
            inv.setItem(SLOT_RENTALS, simple(Material.CLOCK, "my rentals", GuiKit.ACCENT,
                    List.of("your rented bots, time left", "and auto-renew.")));
        }
        inv.setItem(GuiKit.SLOT_BACK, GuiKit.backOrClose());

        // Remember what each slot does so a click is a pure lookup.
        clickMap.put(viewer.getUniqueId(), cardsBySlot(slots, cards, shown));
        viewer.openInventory(inv);
    }

    private final Map<UUID, Map<Integer, Card>> clickMap = new HashMap<>();

    private static Map<Integer, Card> cardsBySlot(List<Integer> slots, List<Card> cards, int shown) {
        Map<Integer, Card> map = new HashMap<>();
        for (int i = 0; i < shown; i++) map.put(slots.get(i), cards.get(i));
        return map;
    }

    // ── cards ────────────────────────────────────────────────────────────────

    private List<RentalPackage> packages(Player viewer) {
        List<RentalPackage> out = new ArrayList<>();
        for (RentalPackage p : Config.rentalPackages()) {
            if (p.permission() == null || viewer.hasPermission(p.permission())) out.add(p);
        }
        return out;
    }

    private Card packageCard(Player viewer, Session s, RentalPackage pkg, FakePlayer extendBot) {
        boolean extend = extendBot != null;
        RentalPricing.Quote q = RentalPricing.quote(viewer, pkg.hours(), !extend, pkg.fixedPrice());
        EconomyManager economy = plugin.getEconomyManager();
        boolean payable = q.total() <= 0 || (economy != null && economy.isAvailable());
        boolean affordable = q.total() <= 0 || (payable && economy.has(viewer, q.total()));
        boolean canBuy = Perm.hasAny(viewer, Perm.RENT, Perm.RENT_FREE);

        String key = (extend ? "ext:" + extendBot.getUuid() + ":" : "new:") + pkg.id();
        boolean pending = key.equals(s.pendingKey) && System.currentTimeMillis() - s.pendingAt < CONFIRM_MS;

        TextColor nameColor = !canBuy || !payable || !affordable ? GuiKit.OFF_RED : GuiKit.ACCENT;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("duration", pkg.hours() + "h"));
        lore.add(line("price", q.free() ? "free" : fmt(q.total())));
        if (!q.free() && q.discountPercent() > 0) {
            lore.add(GuiKit.smallCaps("  " + trim(q.discountPercent()) + "% discount applied", GuiKit.ON_GREEN, false));
        }
        if (!extend && Config.rentalPricePerBotSlot() > 0 && !q.free() && pkg.fixedPrice() == null) {
            lore.add(GuiKit.smallCaps("  incl. slot fee " + fmt(q.slotFee()), GuiKit.DARK_GRAY, false));
        }
        if (!pkg.description().isEmpty()) {
            lore.add(Component.empty());
            for (String d : pkg.description()) {
                for (String w : GuiKit.wrapText(d, 34)) lore.add(GuiKit.smallCaps(w, GuiKit.GRAY, false));
            }
        }
        lore.add(Component.empty());
        if (!canBuy) lore.add(GuiKit.hint("🔒 ", "you can't rent bots"));
        else if (!payable) lore.add(GuiKit.hint("⚠ ", "no economy plugin available"));
        else if (!affordable) {
            lore.add(GuiKit.hint("✕ ", "need " + fmt(q.total()) + ", have " + fmt(economy.getBalance(viewer))));
        } else if (pending) lore.add(GuiKit.hint("⚠ ", "click again to confirm"));
        else lore.add(GuiKit.hint("◈ ", extend ? "click to add this time" : "click to rent a new bot"));

        ItemStack item = new ItemStack(pkg.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiKit.smallCaps(pkg.label(), nameColor, true));
        meta.lore(lore);
        if (pending) GuiKit.glow(meta);
        item.setItemMeta(meta);

        Runnable click = () -> {
            if (!canBuy || !payable || !affordable) {
                GuiKit.playError(viewer);
                if (!canBuy) viewer.sendMessage(me.bill.fakePlayerPlugin.lang.Lang.get("no-permission"));
                return;
            }
            if (!pending) {
                s.pendingKey = key;
                s.pendingAt = System.currentTimeMillis();
                GuiKit.playUiClick(viewer, 0.9f);
                build(viewer, s);
                return;
            }
            s.pendingKey = null;
            RentalPurchases.Result r = extend
                    ? RentalPurchases.extend(plugin, viewer, extendBot, pkg.hours(), pkg.fixedPrice(), "gui")
                    : RentalPurchases.buyNew(plugin, viewer, pkg.hours(), pkg.fixedPrice(), "gui");
            viewer.sendMessage(r.message());
            if (r.success()) {
                GuiKit.playSuccess(viewer);
                if (!extend && r.bot() != null) {
                    s.view = View.BOT;
                    s.bot = r.bot().getUuid();
                }
            } else {
                GuiKit.playError(viewer);
            }
            build(viewer, s);
        };
        return new Card(item, click, true);
    }

    private Card rentalCard(Player viewer, Session s, FakePlayer fp) {
        RentalService service = plugin.getRentalService();
        int auto = service != null ? service.getAutoRenewHours(fp.getUuid()) : 0;
        long remaining = RentalPurchases.currentExpiry(fp) - System.currentTimeMillis();
        boolean unlimited = viewer.hasPermission(Perm.RENT_UNLIMITED);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("time left", unlimited ? "unlimited" : RentalPurchases.formatRemaining(remaining)));
        lore.add(line("auto-renew", auto > 0 ? "on (" + auto + "h)" : "off"));
        if (!fp.getSpawnedBy().equalsIgnoreCase(viewer.getName())) lore.add(line("owner", fp.getSpawnedBy()));
        lore.add(Component.empty());
        lore.add(GuiKit.hint("◈ ", "click to extend or manage"));

        ItemStack item = GuiKit.build(Material.CLOCK, GuiKit.smallCaps(fp.getName(), GuiKit.ACCENT, true), lore);
        return new Card(item, () -> {
            s.view = View.BOT;
            s.bot = fp.getUuid();
            GuiKit.playUiClick(viewer, 1.2f);
            build(viewer, s);
        }, true);
    }

    private Card autoRenewCard(Player viewer, Session s, FakePlayer fp) {
        RentalService service = plugin.getRentalService();
        int auto = service != null ? service.getAutoRenewHours(fp.getUuid()) : 0;
        boolean allowed = Perm.hasAny(viewer, Perm.RENT_AUTO, Perm.RENT_ADMIN);
        boolean on = auto > 0;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(line("state", on ? "on - " + auto + "h per renewal" : "off"));
        lore.add(Component.empty());
        for (String l : List.of(
                "renews shortly before time runs out,",
                "charging the owner at their price.",
                "turns itself off if they can't pay.")) {
            lore.add(GuiKit.smallCaps(l, GuiKit.GRAY, false));
        }
        lore.add(Component.empty());
        lore.add(allowed ? GuiKit.hint("◈ ", "click to toggle") : GuiKit.hint("🔒 ", "you can't use auto-renew"));

        ItemStack item = new ItemStack(on ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiKit.smallCaps("auto-renew", on ? GuiKit.ON_GREEN : GuiKit.GRAY, true));
        meta.lore(lore);
        if (on) GuiKit.glow(meta);
        item.setItemMeta(meta);

        return new Card(item, () -> {
            if (!allowed || service == null) {
                GuiKit.playError(viewer);
                return;
            }
            service.setAutoRenewHours(fp.getUuid(), on ? 0 : Config.rentalAutoRenewDefaultHours());
            GuiKit.playUiClick(viewer, on ? 0.85f : 1.2f);
            build(viewer, s);
        }, allowed);
    }

    private ItemStack infoCard(Player viewer) {
        EconomyManager economy = plugin.getEconomyManager();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        boolean eco = economy != null && economy.isAvailable();
        lore.add(line("balance", eco ? fmt(economy.getBalance(viewer)) : "no economy"));
        boolean unlimitedCap = viewer.hasPermission(Perm.RENT_UNLIMITED);
        lore.add(line(
                "rented bots",
                RentalPurchases.rentedCount(plugin, viewer) + "/" + (unlimitedCap ? "∞" : RentalPurchases.rentLimit(viewer))));
        double discount = Perm.resolveRentDiscount(viewer);
        if (discount > 0) lore.add(line("your discount", trim(discount) + "%"));
        if (Perm.has(viewer, Perm.RENT_FREE)) lore.add(line("pricing", "free (fpp.rent.free)"));
        lore.add(Component.empty());
        lore.add(GuiKit.smallCaps("bulk discounts are applied automatically", GuiKit.DARK_GRAY, false));
        return GuiKit.build(Material.EMERALD, GuiKit.smallCaps("your account", GuiKit.ACCENT, true), lore);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<FakePlayer> ownedRented(Player viewer) {
        List<FakePlayer> out = new ArrayList<>();
        for (FakePlayer fp : manager.getBotsOwnedBy(viewer.getUniqueId())) {
            if (fp.isRented()) out.add(fp);
        }
        return out;
    }

    private boolean mayManage(Player viewer, FakePlayer fp) {
        return fp.getSpawnedByUuid().equals(viewer.getUniqueId()) || Perm.hasAny(viewer, Perm.ADMIN, Perm.RENT_ADMIN);
    }

    private String fmt(double amount) {
        EconomyManager economy = plugin.getEconomyManager();
        return economy != null ? economy.format(amount) : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static Component line(String label, String value) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(GuiKit.smallCaps(label + "  ", GuiKit.DARK_GRAY, false))
                .append(Component.text(value).color(GuiKit.VALUE_YELLOW).decoration(TextDecoration.BOLD, true));
    }

    private static ItemStack simple(Material icon, String label, TextColor color, List<String> lore) {
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(GuiKit.smallCaps(s, GuiKit.DARK_GRAY, false));
        return GuiKit.build(icon, GuiKit.smallCaps(label, color, true), l);
    }

    // ── events ───────────────────────────────────────────────────────────────

    private static boolean isRent(Inventory inv) {
        return inv.getHolder() instanceof GuiKit.SimpleHolder h && KIND.equals(h.kind());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!isRent(event.getInventory())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;

        int slot = event.getSlot();
        if (slot == GuiKit.SLOT_BACK) {
            GuiKit.playUiClick(player, 0.8f);
            sessions.remove(player.getUniqueId());
            GuiKit.backOrClose(player);
            return;
        }
        if (slot == SLOT_NAV && s.view != View.SHOP) {
            GuiKit.playUiClick(player, 1.0f);
            s.view = s.view == View.BOT ? View.RENTALS : View.SHOP;
            s.pendingKey = null;
            build(player, s);
            return;
        }
        if (slot == SLOT_RENTALS && s.view != View.RENTALS) {
            GuiKit.playUiClick(player, 1.2f);
            s.view = View.RENTALS;
            s.pendingKey = null;
            build(player, s);
            return;
        }
        Map<Integer, Card> map = clickMap.get(player.getUniqueId());
        Card card = map != null ? map.get(slot) : null;
        if (card != null) card.onClick().run();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!isRent(event.getInventory())) return;
        // A rebuild closes then reopens: only forget once the viewer really left every rent view.
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !isRent(p.getOpenInventory().getTopInventory())) {
                sessions.remove(id);
                clickMap.remove(id);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        clickMap.remove(event.getPlayer().getUniqueId());
    }
}
