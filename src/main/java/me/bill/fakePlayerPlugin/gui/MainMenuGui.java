package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.permission.Perm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * {@code /fpp menu}: the plugin's hub. One card per feature, laid out centred on a backdrop and greyed
 * out (with an error sound) when the viewer lacks its permission. Every other menu's "back" button
 * returns here. Counts and toggles are re-rendered on each click.
 */
public final class MainMenuGui implements Listener {

    private static final String KIND = "menu";
    private static final long CONFIRM_WINDOW_MS = 5_000L;

    /** One card: whether it is visible/usable for a viewer, how it renders, and what a click does. */
    private record Entry(
            String id,
            java.util.function.Predicate<Player> allowed,
            Function<Player, ItemStack> render,
            BiConsumer<Player, ClickType> click) {}

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final BotListGui botListGui;
    private final SettingGui settingGui;
    private final HelpGui helpGui;

    private final Set<UUID> pendingRebuild = new HashSet<>();
    private final Map<UUID, Long> despawnConfirm = new HashMap<>();

    public MainMenuGui(
            FakePlayerPlugin plugin,
            FakePlayerManager manager,
            BotListGui botListGui,
            SettingGui settingGui,
            HelpGui helpGui) {
        this.plugin = plugin;
        this.manager = manager;
        this.botListGui = botListGui;
        this.settingGui = settingGui;
        this.helpGui = helpGui;
        GuiKit.setMainMenu(this::open);
    }

    public void open(Player player) {
        build(player);
        GuiKit.playOpen(player);
    }

    // ── cards ────────────────────────────────────────────────────────────────────────────────

    private List<Entry> entries() {
        List<Entry> list = new ArrayList<>();

        list.add(new Entry(
                "active bots",
                p -> Perm.has(p, Perm.LIST),
                p -> card(
                        Material.PLAYER_HEAD,
                        "active bots",
                        GuiKit.ACCENT,
                        List.of("every bot, what it is doing and", "where it is. click one to manage it."),
                        "bots online  " + manager.getCount(),
                        "◈ click to open"),
                (p, c) -> switchTo(p, () -> botListGui.open(p))));

        list.add(new Entry(
                "spawn bot",
                p -> Perm.has(p, Perm.SPAWN) || Perm.has(p, Perm.USER_SPAWN),
                p -> card(
                        Material.EMERALD,
                        "spawn a bot",
                        GuiKit.ON_GREEN,
                        List.of("spawns a bot at your location."),
                        null,
                        "◈ click to spawn"),
                (p, c) -> {
                    GuiKit.playSuccess(p);
                    p.closeInventory();
                    p.performCommand("fpp spawn");
                }));

        list.add(new Entry(
                "settings",
                p -> Perm.has(p, Perm.SETTINGS),
                p -> card(
                        Material.COMPARATOR,
                        "settings",
                        GuiKit.ACCENT,
                        List.of("every plugin option, editable in game."),
                        null,
                        "◈ click to open"),
                (p, c) -> switchTo(p, () -> settingGui.open(p))));

        list.add(new Entry(
                "chunk loading",
                p -> Perm.has(p, Perm.SETTINGS),
                p -> {
                    boolean on = Config.chunkLoadingEnabled();
                    String view =
                            Config.chunkLoadingRadius() == 0 ? "minimum" : Config.chunkLoadingRadius() + " chunks";
                    return card(
                            Material.GRASS_BLOCK,
                            "chunk loading",
                            on ? GuiKit.ON_GREEN : GuiKit.OFF_RED,
                            List.of(
                                    "bots act like afk players: the server",
                                    "loads chunks around them. this sets the",
                                    "render distance each bot reports."),
                            (on ? "on  •  " : "off  •  ") + view,
                            "◈ click to toggle");
                },
                (p, c) -> {
                    boolean now = !Config.chunkLoadingEnabled();
                    plugin.getConfig().set("chunk-loading.enabled", now);
                    plugin.saveConfig();
                    Config.reload();
                    GuiKit.playUiClick(p, now ? 1.2f : 0.85f);
                    build(p);
                }));

        list.add(new Entry(
                "rent a bot",
                p -> Config.rentalGuiEnabled()
                        && plugin.getRentGui() != null
                        && plugin.getRentGui().canOpen(p),
                p -> card(
                        Material.CLOCK,
                        "rent a bot",
                        GuiKit.ACCENT,
                        List.of("rent bots by the hour, extend time", "and set auto-renew."),
                        "your rentals  "
                                + manager.getBotsOwnedBy(p.getUniqueId()).stream()
                                        .filter(me.bill.fakePlayerPlugin.fakeplayer.FakePlayer::isRented)
                                        .count(),
                        "◈ click to open"),
                (p, c) -> switchTo(p, () -> plugin.getRentGui().open(p))));

        list.add(new Entry(
                "help",
                p -> Perm.has(p, Perm.HELP),
                p -> card(
                        Material.BOOK,
                        "help",
                        GuiKit.ACCENT,
                        List.of("every command and what it does."),
                        null,
                        "◈ click to open"),
                (p, c) -> switchTo(p, () -> helpGui.open(p))));

        list.add(new Entry(
                "despawn all",
                p -> Perm.has(p, Perm.DELETE_ALL),
                p -> {
                    Long at = despawnConfirm.get(p.getUniqueId());
                    boolean confirming = at != null && System.currentTimeMillis() - at < CONFIRM_WINDOW_MS;
                    return card(
                            Material.TNT,
                            "despawn all bots",
                            GuiKit.OFF_RED,
                            List.of("removes every bot from the server."),
                            "bots  " + manager.getCount(),
                            confirming ? "⚠ click again to confirm (5s)" : "⚠ click to despawn everything");
                },
                (p, c) -> {
                    Long at = despawnConfirm.get(p.getUniqueId());
                    if (at != null && System.currentTimeMillis() - at < CONFIRM_WINDOW_MS) {
                        despawnConfirm.remove(p.getUniqueId());
                        GuiKit.playSuccess(p);
                        p.closeInventory();
                        p.performCommand("fpp delete --all");
                    } else {
                        despawnConfirm.put(p.getUniqueId(), System.currentTimeMillis());
                        GuiKit.playUiClick(p, 0.6f);
                        build(p);
                    }
                }));
        return list;
    }

    private static ItemStack card(
            Material icon, String label, TextColor color, List<String> description, String value, String... hints) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        for (String line : description) lore.add(GuiKit.smallCaps(line, GuiKit.GRAY, false));
        if (value != null) {
            lore.add(Component.empty());
            lore.add(GuiKit.smallCaps(value, GuiKit.VALUE_YELLOW, false));
        }
        lore.add(Component.empty());
        for (String hint : hints) {
            if (hint != null) lore.add(GuiKit.hint("", hint));
        }
        return GuiKit.build(icon, GuiKit.smallCaps(label, color, true), lore);
    }

    private static ItemStack lockedCard(Entry entry) {
        return GuiKit.build(
                Material.GRAY_DYE,
                GuiKit.smallCaps(entry.id(), GuiKit.GRAY, false),
                List.of(Component.empty(), GuiKit.hint("🔒 ", "you don't have permission for this")));
    }

    // ── build ────────────────────────────────────────────────────────────────────────────────

    private void build(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(GuiKit.DARK_GRAY))
                .append(GuiKit.smallCaps("fpp", GuiKit.ACCENT, false))
                .append(Component.text("] ").color(GuiKit.DARK_GRAY))
                .append(GuiKit.smallCaps("main menu", GuiKit.DARK_GRAY, false));

        Inventory inv = Bukkit.createInventory(new GuiKit.SimpleHolder(uuid, KIND), GuiKit.SIZE, title);
        GuiKit.fillContentBackdrop(inv);

        List<Entry> entries = entries();
        List<Integer> slots = GuiKit.centeredSlots(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            inv.setItem(slots.get(i), e.allowed().test(viewer) ? e.render().apply(viewer) : lockedCard(e));
        }

        GuiKit.fillFooter(inv);
        inv.setItem(GuiKit.SLOT_CREDIT, GuiKit.creditHead(plugin));
        inv.setItem(GuiKit.SLOT_BACK, GuiKit.closeButton());

        pendingRebuild.add(uuid);
        viewer.openInventory(inv);
        pendingRebuild.remove(uuid);
    }

    /** Opens another menu after playing the click sound (its own close handler can't clobber ours). */
    private void switchTo(Player player, Runnable openOther) {
        GuiKit.playUiClick(player, 1.3f);
        despawnConfirm.remove(player.getUniqueId());
        openOther.run();
    }

    // ── events ───────────────────────────────────────────────────────────────────────────────

    private static boolean isMenu(Inventory inv) {
        return inv.getHolder() instanceof GuiKit.SimpleHolder h && KIND.equals(h.kind());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!isMenu(event.getInventory())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

        int slot = event.getSlot();
        if (slot == GuiKit.SLOT_BACK) {
            GuiKit.playUiClick(player, 0.8f);
            player.closeInventory();
            return;
        }
        if (slot >= GuiKit.PER_PAGE) return;

        List<Entry> entries = entries();
        int idx = GuiKit.centeredSlots(entries.size()).indexOf(slot);
        if (idx < 0 || idx >= entries.size()) return;
        Entry entry = entries.get(idx);
        if (!entry.allowed().test(player)) {
            GuiKit.playError(player);
            return;
        }
        entry.click().accept(player, event.getClick());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!isMenu(event.getInventory())) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (pendingRebuild.contains(uuid)) return;
        despawnConfirm.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        despawnConfirm.remove(event.getPlayer().getUniqueId());
    }
}
