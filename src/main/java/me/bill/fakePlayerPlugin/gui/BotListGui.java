package me.bill.fakePlayerPlugin.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.bukkit.inventory.meta.SkullMeta;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.BotActivity;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinProfile;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * GUI listing active bots. A normal user sees only the bots they own; an admin/OP sees every bot on
 * the server. Clicking a bot opens its {@link BotSettingGui}. Supports live in-place refresh
 * (uptime/location/status update once a second while open), a name/owner search filter, and a
 * cycle-able sort order.
 */
public final class BotListGui implements Listener {

    private static final int SIZE = 54;
    /** Bots per page: centred cards on a backdrop (rows 0-4, 7 per row). */
    private static final int PER_PAGE = GuiKit.CONTENT_CAPACITY;
    private static final int SLOT_SORT = 45;
    private static final int SLOT_SEARCH = 46;
    private static final int SLOT_SPAWN = 47;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_SETTINGS = 51;
    private static final int SLOT_CLOSE = 53;
    private static final long REFRESH_PERIOD_TICKS = 20L;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, String> filters = new HashMap<>();
    private final Map<UUID, SortMode> sortModes = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private int refreshTaskId = -1;

    public enum SortMode {
        NAME("ɴᴀᴍᴇ"),
        UPTIME("ᴜᴘᴛɪᴍᴇ"),
        OWNER("ᴏᴡɴᴇʀ"),
        STATUS("ꜱᴛᴀᴛᴜꜱ");

        final String label;

        SortMode(String label) {
            this.label = label;
        }

        SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public BotListGui(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player) {
        pages.put(player.getUniqueId(), 0);
        build(player);
    }

    private boolean isAdmin(Player player) {
        return Perm.hasAny(player, Perm.OP, Perm.ADMIN);
    }

    /** Bots this viewer may see: all bots for admins, only their own otherwise - filtered and sorted. */
    private List<FakePlayer> visibleBots(Player player) {
        UUID uuid = player.getUniqueId();
        List<FakePlayer> bots = isAdmin(player)
                ? new ArrayList<>(manager.getActivePlayers())
                : new ArrayList<>(manager.getBotsOwnedBy(uuid));

        String filter = filters.get(uuid);
        if (filter != null && !filter.isBlank()) {
            String needle = filter.toLowerCase();
            bots.removeIf(fp -> {
                String name = fp.getName() == null ? "" : fp.getName().toLowerCase();
                String owner =
                        fp.getSpawnedBy() == null ? "" : fp.getSpawnedBy().toLowerCase();
                return !name.contains(needle) && !owner.contains(needle);
            });
        }

        SortMode sort = sortModes.getOrDefault(uuid, SortMode.NAME);
        Comparator<FakePlayer> cmp =
                switch (sort) {
                    case NAME -> Comparator.comparing(
                            fp -> fp.getName() == null ? "" : fp.getName().toLowerCase());
                    case UPTIME -> Comparator.comparing(
                            (FakePlayer fp) -> fp.getSpawnTime() == null ? Instant.EPOCH : fp.getSpawnTime());
                    case OWNER -> Comparator.<FakePlayer, String>comparing(fp -> fp.getSpawnedBy() == null
                                    ? ""
                                    : fp.getSpawnedBy().toLowerCase())
                            .thenComparing(fp ->
                                    fp.getName() == null ? "" : fp.getName().toLowerCase());
                    case STATUS -> Comparator.<FakePlayer>comparingInt(fp -> fp.isFrozen() ? 0 : 1)
                            .thenComparing(fp ->
                                    fp.getName() == null ? "" : fp.getName().toLowerCase());
                };
        bots.sort(cmp);
        return bots;
    }

    private void build(Player player) {
        UUID uuid = player.getUniqueId();
        boolean admin = isAdmin(player);
        List<FakePlayer> bots = visibleBots(player);

        int totalPages = Math.max(1, (int) Math.ceil(bots.size() / (double) PER_PAGE));
        int page = Math.min(Math.max(0, pages.getOrDefault(uuid, 0)), totalPages - 1);
        pages.put(uuid, page);

        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(GuiKit.DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(GuiKit.ACCENT))
                .append(Component.text("] ").color(GuiKit.DARK_GRAY))
                .append(Component.text(admin ? "ᴀʟʟ ʙᴏᴛꜱ" : "ʏᴏᴜʀ ʙᴏᴛꜱ").color(GuiKit.DARK_GRAY));

        Inventory inv = Bukkit.createInventory(new GuiKit.SimpleHolder(uuid, "list"), SIZE, title);

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, bots.size());
        GuiKit.fillContentBackdrop(inv);
        List<Integer> cardSlots = GuiKit.centeredSlots(end - start);
        for (int i = start; i < end; i++) {
            inv.setItem(cardSlots.get(i - start), botHead(bots.get(i)));
        }

        ItemStack filler = GuiKit.glassFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int s = 45; s <= 53; s++) inv.setItem(s, filler);
        inv.setItem(SLOT_SORT, sortItem(sortModes.getOrDefault(uuid, SortMode.NAME)));
        inv.setItem(SLOT_SEARCH, GuiKit.buildSearchItem(filters.get(uuid)));
        inv.setItem(SLOT_INFO, infoItem(admin, bots.size(), page, totalPages));
        if (page > 0) inv.setItem(SLOT_PREV, GuiKit.buildPageNavArrow(false, page, GuiKit.COMING_SOON_COLOR));
        if (page < totalPages - 1) inv.setItem(SLOT_NEXT, GuiKit.buildPageNavArrow(true, page + 2, GuiKit.ON_GREEN));
        if (Perm.has(player, Perm.SPAWN) || Perm.has(player, Perm.USER_SPAWN)) {
            inv.setItem(SLOT_SPAWN, spawnItem());
        }
        if (Perm.has(player, Perm.SETTINGS)) {
            inv.setItem(SLOT_SETTINGS, settingsItem());
        }
        inv.setItem(SLOT_CLOSE, GuiKit.backOrClose());

        player.openInventory(inv);
        startTrackingViewer(uuid);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiKit.SimpleHolder holder) || !"list".equals(holder.kind()))
            return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

        UUID uuid = player.getUniqueId();
        int page = pages.getOrDefault(uuid, 0);
        List<FakePlayer> bots = visibleBots(player);
        int totalPages = Math.max(1, (int) Math.ceil(bots.size() / (double) PER_PAGE));
        int slot = event.getSlot();

        if (slot == SLOT_CLOSE) {
            GuiKit.playUiClick(player, 0.8f);
            GuiKit.backOrClose(player);
        } else if (slot == SLOT_SPAWN && (Perm.has(player, Perm.SPAWN) || Perm.has(player, Perm.USER_SPAWN))) {
            GuiKit.playUiClick(player, 1.2f);
            player.performCommand("fpp spawn");
            // The 1 Hz refresh notices the changed bot count and rebuilds the page with the new bot.
        } else if (slot == SLOT_SETTINGS && Perm.has(player, Perm.SETTINGS)) {
            GuiKit.playUiClick(player, 1.0f);
            player.performCommand("fpp settings");
        } else if (slot == SLOT_SORT) {
            SortMode next = sortModes.getOrDefault(uuid, SortMode.NAME).next();
            sortModes.put(uuid, next);
            GuiKit.playUiClick(player, 1.0f);
            player.sendActionBar(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("◈ ᴠɪᴇᴡ ").color(GuiKit.DARK_GRAY))
                    .append(Component.text("ꜱᴏʀᴛᴇᴅ ʙʏ " + next.label).color(GuiKit.ON_GREEN)));
            build(player);
        } else if (slot == SLOT_SEARCH) {
            if (event.isShiftClick() && filters.get(uuid) != null) {
                filters.remove(uuid);
                GuiKit.playUiClick(player, 0.85f);
                build(player);
                return;
            }
            GuiKit.playUiClick(player, 1.0f);
            player.closeInventory();
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✦ ").color(GuiKit.ACCENT))
                    .append(Component.text("ᴛʏᴘᴇ ᴀ ɴᴀᴍᴇ/ᴏᴡɴᴇʀ ꜰɪʟᴛᴇʀ ɪɴ ᴄʜᴀᴛ, ᴏʀ '")
                            .color(GuiKit.GRAY))
                    .append(Component.text("cancel").color(GuiKit.YELLOW))
                    .append(Component.text("'.").color(GuiKit.GRAY)));
            GuiKit.beginCapture(
                    plugin,
                    player,
                    text -> {
                        filters.put(uuid, text);
                        pages.put(uuid, 0);
                        build(player);
                    },
                    () -> build(player),
                    20L * 30);
        } else if (slot == SLOT_PREV && page > 0) {
            pages.put(uuid, page - 1);
            GuiKit.playUiClick(player, 1.0f);
            build(player);
        } else if (slot == SLOT_NEXT && page < totalPages - 1) {
            pages.put(uuid, page + 1);
            GuiKit.playUiClick(player, 1.0f);
            build(player);
        } else if (slot >= 0 && slot < GuiKit.PER_PAGE) {
            int onPage = Math.min(PER_PAGE, Math.max(0, bots.size() - page * PER_PAGE));
            int local = GuiKit.centeredSlots(onPage).indexOf(slot);
            int idx = local < 0 ? -1 : page * PER_PAGE + local;
            if (idx >= 0 && idx < bots.size()) {
                FakePlayer bot = bots.get(idx);
                GuiKit.playUiClick(player, 1.3f);
                // Drill into the bot's management GUI (viewer already owns it or is an admin).
                plugin.getBotSettingGui().open(player, bot);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuiKit.SimpleHolder holder && "list".equals(holder.kind())) {
            UUID uuid = event.getPlayer().getUniqueId();
            pages.remove(uuid);
            filters.remove(uuid);
            sortModes.remove(uuid);
            stopTrackingViewer(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pages.remove(uuid);
        filters.remove(uuid);
        sortModes.remove(uuid);
        stopTrackingViewer(uuid);
    }

    // ── Live auto-refresh ─────────────────────────────────────────────────────

    private void startTrackingViewer(UUID uuid) {
        viewers.add(uuid);
        if (refreshTaskId < 0) {
            refreshTaskId = FppScheduler.runSyncRepeatingWithId(
                    plugin, this::tickRefresh, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
        }
    }

    private void stopTrackingViewer(UUID uuid) {
        viewers.remove(uuid);
        if (viewers.isEmpty() && refreshTaskId >= 0) {
            FppScheduler.cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    private void tickRefresh() {
        for (UUID uuid : new ArrayList<>(viewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                stopTrackingViewer(uuid);
                continue;
            }
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof GuiKit.SimpleHolder holder)
                    || !"list".equals(holder.kind())) {
                stopTrackingViewer(uuid);
                continue;
            }
            Inventory inv = player.getOpenInventory().getTopInventory();
            int page = pages.getOrDefault(uuid, 0);
            List<FakePlayer> bots = visibleBots(player);
            int start = page * PER_PAGE;
            int end = Math.min(start + PER_PAGE, bots.size());
            int expectedCount = end - start;

            int currentCount = 0;
            for (int s = 0; s < PER_PAGE; s++) {
                ItemStack item = inv.getItem(s);
                if (item != null && item.getType() == Material.PLAYER_HEAD) currentCount++;
            }
            if (currentCount != expectedCount) {
                // Bot roster shifted since the page was last built - full rebuild self-heals.
                build(player);
                continue;
            }
            for (int i = start; i < end; i++) {
                refreshLore(inv.getItem(i - start), bots.get(i));
            }
        }
    }

    private void refreshLore(ItemStack head, FakePlayer fp) {
        if (head == null) return;
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return;
        meta.lore(botLore(fp));
        head.setItemMeta(meta);
    }

    private ItemStack botHead(FakePlayer fp) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            Player body = fp.getPlayer();
            try {
                if (body != null && body.isOnline()) {
                    meta.setPlayerProfile(body.getPlayerProfile());
                }
            } catch (Throwable ignored) {
            }
            String display = fp.getDisplayName() != null ? fp.getDisplayName() : fp.getName();
            meta.displayName(Component.text(display).color(GuiKit.ACCENT).decoration(TextDecoration.ITALIC, false));
            meta.lore(botLore(fp));
            head.setItemMeta(meta);
        }
        return head;
    }

    private List<Component> botLore(FakePlayer fp) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("ᴏᴡɴᴇʀ", fp.getSpawnedBy() != null ? fp.getSpawnedBy() : "?"));
        lore.add(line("ʟᴏᴄᴀᴛɪᴏɴ", location(fp)));
        lore.add(line("ᴜᴘᴛɪᴍᴇ", uptime(fp.getSpawnTime())));
        lore.add(line("ᴀᴄᴛɪᴠɪᴛʏ", BotActivity.currentLabel(fp)));
        String rarity = rareSkinLabel(fp);
        if (rarity != null) {
            lore.add(Component.text("ꜱᴋɪɴ: ", GuiKit.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(rarity, GuiKit.VALUE_YELLOW)));
        }
        lore.add(Component.text("ꜱᴛᴀᴛᴜꜱ: ", GuiKit.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                        fp.isFrozen()
                                ? Component.text("❄ ꜰʀᴏᴢᴇɴ", GuiKit.FROZEN)
                                : Component.text("● ᴀᴄᴛɪᴠᴇ", GuiKit.ON_GREEN)));
        lore.add(Component.empty());
        lore.add(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴍᴀɴᴀɢᴇ", GuiKit.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    /** "✨ ʀᴀʀᴇ 1/N" when the bot wears a rare pool skin, else null (no line for main/custom). */
    private static String rareSkinLabel(FakePlayer fp) {
        SkinProfile skin = fp.getResolvedSkin();
        if (skin == null || !skin.isValid() || skin.getSource() == null) return null;
        String source = skin.getSource();
        if (!source.startsWith("pool:")) return null;
        String tail = source.substring(source.lastIndexOf(':') + 1);
        return tail.startsWith("1-in-") ? "✨ ʀᴀʀᴇ " + tail.replace("1-in-", "1/") : null;
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", GuiKit.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value, GuiKit.WHITE));
    }

    private ItemStack infoItem(boolean admin, int count, int page, int totalPages) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(admin ? "ᴀʟʟ ʙᴏᴛꜱ" : "ʏᴏᴜʀ ʙᴏᴛꜱ", GuiKit.ACCENT)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("ᴛᴏᴛᴀʟ", String.valueOf(count)));
        lore.add(line("ᴘᴀɢᴇ", (page + 1) + "/" + totalPages));
        lore.add(Component.text(admin ? "ꜱᴇᴇɪɴɢ ᴇᴠᴇʀʏ ʙᴏᴛ ᴏɴ ᴛʜᴇ ꜱᴇʀᴠᴇʀ" : "ꜱᴇᴇɪɴɢ ᴏɴʟʏ ʙᴏᴛꜱ ʏᴏᴜ ᴏᴡɴ", GuiKit.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack sortItem(SortMode mode) {
        ItemStack it = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⇅ ꜱᴏʀᴛ").color(GuiKit.ACCENT).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴄᴜʀʀᴇɴᴛ: ").color(GuiKit.DARK_GRAY))
                        .append(Component.text(mode.label).color(GuiKit.VALUE_YELLOW)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴄʏᴄʟᴇ").color(GuiKit.DARK_GRAY))));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack spawnItem() {
        ItemStack it = new ItemStack(Material.EGG);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("＋ ꜱᴘᴀᴡɴ ʙᴏᴛ").color(GuiKit.ON_GREEN).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ꜱᴘᴀᴡɴꜱ ᴏɴᴇ ᴀᴜᴛᴏ-ɴᴀᴍᴇᴅ ʙᴏᴛ").color(GuiKit.GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴀᴛ ʏᴏᴜʀ ʟᴏᴄᴀᴛɪᴏɴ.").color(GuiKit.GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ꜱᴘᴀᴡɴ").color(GuiKit.DARK_GRAY))));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack settingsItem() {
        ItemStack it = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⚙ ɢʟᴏʙᴀʟ ꜱᴇᴛᴛɪɴɢꜱ")
                        .color(GuiKit.ACCENT)
                        .decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴏᴘᴇɴꜱ ᴛʜᴇ ᴘʟᴜɢɪɴ-ᴡɪᴅᴇ ꜱᴇᴛᴛɪɴɢꜱ ɢᴜɪ")
                                .color(GuiKit.GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("(ɢᴇɴᴇʀᴀʟ · ʙᴏᴅʏ · ᴅᴇʙᴜɢ).").color(GuiKit.GRAY))));
        it.setItemMeta(meta);
        return it;
    }

    private static String location(FakePlayer fp) {
        Location loc = fp.getPhysicsEntity() != null && fp.getPhysicsEntity().isValid()
                ? fp.getPhysicsEntity().getLocation()
                : fp.getSpawnLocation();
        if (loc == null) return "unknown";
        return (loc.getWorld() != null ? loc.getWorld().getName() : "?") + " " + loc.getBlockX() + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
    }

    private static String uptime(Instant spawnTime) {
        if (spawnTime == null) return "?";
        long secs = Duration.between(spawnTime, Instant.now()).getSeconds();
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }
}
