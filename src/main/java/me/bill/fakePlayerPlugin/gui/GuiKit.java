package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.TextUtil;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Shared building blocks for the plugin's inventory GUIs (colors, glass fillers, text wrapping, click
 * sounds, page-nav arrows, a generic {@link InventoryHolder} marker, and a centralized chat-capture
 * mechanism for search prompts) - extracted from near-identical copies that used to live in every GUI
 * class separately.
 */
public final class GuiKit {

    private GuiKit() {}

    // FPP 2.0 "Bot Console" palette - violet / lime, see language/en.yml header for the full reference.
    public static final TextColor ACCENT = TextColor.fromHexString("#A78BFA");
    public static final TextColor ON_GREEN = TextColor.fromHexString("#BAFF4F");
    public static final TextColor OFF_RED = TextColor.fromHexString("#FF6A5C");
    public static final TextColor VALUE_YELLOW = TextColor.fromHexString("#BAFF4F");
    public static final TextColor YELLOW = TextColor.fromHexString("#BAFF4F");
    public static final TextColor GRAY = TextColor.fromHexString("#9691AB");
    public static final TextColor DARK_GRAY = TextColor.fromHexString("#5F5B73");
    public static final TextColor WHITE = TextColor.fromHexString("#EEECF7");
    public static final TextColor DANGER_RED = TextColor.fromHexString("#FF6A5C");
    public static final TextColor COMING_SOON_COLOR = TextColor.fromHexString("#5F5B73");
    public static final TextColor SELECTED_GREEN = TextColor.fromHexString("#BAFF4F");
    public static final TextColor FROZEN = TextColor.fromHexString("#CABAFF");

    public static final int CAT_WINDOW = 5;

    // ── Shared 54-slot layout (rows 0-4 content, row 5 footer) ─────────────────────────────────
    public static final int SIZE = 54;
    public static final int PER_PAGE = 45;
    /** Footer slot that takes the "back to main menu" (or close) button. */
    public static final int SLOT_BACK = 53;
    /** Bottom-left footer slot that holds the creator's credit head in every menu. */
    public static final int SLOT_CREDIT = 45;

    public static final int CONTENT_ROWS = 5;
    /** At most 7 cards per row keeps a backdrop-only margin column on each side for a framed look. */
    public static final int MAX_PER_ROW = 7;

    public static final int CONTENT_CAPACITY = CONTENT_ROWS * MAX_PER_ROW;
    /** Content-page buttons live in the two margin columns of the top row (never collide with a card). */
    public static final int SLOT_CONTENT_PREV = 0;

    public static final int SLOT_CONTENT_NEXT = 8;

    // ── Main-menu navigation (the main menu registers itself once) ─────────────────────────────
    private static volatile Consumer<Player> mainMenuOpener;

    public static void setMainMenu(Consumer<Player> opener) {
        mainMenuOpener = opener;
    }

    public static boolean hasMainMenu() {
        return mainMenuOpener != null;
    }

    /** Opens the main menu for {@code player}; a no-op until the menu has registered itself. */
    public static void openMainMenu(Player player) {
        Consumer<Player> opener = mainMenuOpener;
        if (opener != null) opener.accept(player);
    }

    /**
     * Slot indices for {@code count} content cards laid out centred: rows split as evenly as possible
     * (10 cards -> 5+5), the block of rows centred vertically and each row horizontally. Reading order;
     * resolve a clicked slot back with {@code centeredSlots(count).indexOf(slot)}.
     */
    public static List<Integer> centeredSlots(int count) {
        List<Integer> slots = new ArrayList<>();
        if (count <= 0) return slots;
        int capped = Math.min(count, CONTENT_CAPACITY);
        int rows = Math.min(CONTENT_ROWS, (int) Math.ceil(capped / (double) MAX_PER_ROW));
        int base = capped / rows;
        int extra = capped % rows;
        int rowStart = (CONTENT_ROWS - rows) / 2;
        int placed = 0;
        for (int r = 0; r < rows && placed < capped; r++) {
            int itemsThisRow = base + (r < extra ? 1 : 0);
            int colStart = (9 - itemsThisRow) / 2;
            for (int c = 0; c < itemsThisRow && placed < capped; c++) {
                slots.add((rowStart + r) * 9 + colStart + c);
                placed++;
            }
        }
        return slots;
    }

    /** Fills the whole content area with a dark pane so cards sit framed rather than floating. */
    public static void fillContentBackdrop(org.bukkit.inventory.Inventory inv) {
        ItemStack backdrop = glassFiller(Material.BLACK_STAINED_GLASS_PANE);
        for (int s = 0; s < PER_PAGE; s++) inv.setItem(s, backdrop);
    }

    public static void fillFooter(org.bukkit.inventory.Inventory inv) {
        ItemStack f = glassFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int s = PER_PAGE; s < SIZE; s++) inv.setItem(s, f);
    }

    // ── Text / item helpers ────────────────────────────────────────────────────────────────────

    public static Component text(String plain, TextColor color, boolean bold) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(plain).color(color).decoration(TextDecoration.BOLD, bold));
    }

    public static Component smallCaps(String text, TextColor color, boolean bold) {
        return text(TextUtil.toSmallCaps(text), color, bold);
    }

    public static Component hint(String icon, String smallCapsText) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(icon).color(ACCENT))
                .append(smallCaps(smallCapsText, DARK_GRAY, false));
    }

    public static void glow(ItemMeta meta) {
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    public static ItemStack build(Material material, Component displayName, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(displayName);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final java.util.UUID CREATOR_UUID =
            java.util.UUID.fromString("a318f9f4-e2bf-479c-a47a-6a2c1b0b9e66");
    private static final String CREATOR_NAME = "F_PP";

    /**
     * The plugin credit: the creator's head with the version / build ID. Decorative only - menus
     * place it at {@link #SLOT_CREDIT} and never give that slot a click action.
     */
    public static ItemStack creditHead(Plugin plugin) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setPlayerProfile(Bukkit.createProfile(CREATOR_UUID));
        }
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(text("[", DARK_GRAY, false))
                .append(smallCaps("fake player plugin", ACCENT, true))
                .append(text("]", DARK_GRAY, false)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(creditRow("created by", CREATOR_NAME));
        lore.add(creditRow("version", plugin.getPluginMeta().getVersion()));
        lore.add(creditRow("build", me.bill.fakePlayerPlugin.util.BuildInfo.id()));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component creditRow(String label, String value) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(smallCaps(label + "  ", GRAY, false))
                .append(text(value, WHITE, false));
    }

    public static ItemStack closeButton() {
        return build(
                Material.BARRIER,
                text("✕  ", OFF_RED, true).append(smallCaps("close", OFF_RED, true)),
                List.of(smallCaps("close this menu.", DARK_GRAY, false)));
    }

    public static ItemStack backButton() {
        return build(
                Material.SPECTRAL_ARROW,
                text("◄  ", ACCENT, true).append(smallCaps("main menu", ACCENT, true)),
                List.of(smallCaps("back to the fpp main menu.", DARK_GRAY, false)));
    }

    /** Back-to-main-menu when the hub exists, otherwise a plain close button. */
    public static ItemStack backOrClose() {
        return hasMainMenu() ? backButton() : closeButton();
    }

    /** What clicking {@link #backOrClose()} does: open the hub, or just close. */
    public static void backOrClose(Player player) {
        if (hasMainMenu()) openMainMenu(player);
        else player.closeInventory();
    }

    public static void playOpen(Player player) {
        play(player, Sound.BLOCK_ENDER_CHEST_OPEN, 0.3f, 1.4f);
    }

    public static void playSuccess(Player player) {
        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
    }

    public static void playError(Player player) {
        play(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }

    private static void play(Player player, Sound sound, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    /** A generic marker record any GUI can reuse instead of writing its own near-identical holder. */
    public record SimpleHolder(UUID uuid, String kind) implements InventoryHolder {
        public SimpleHolder(UUID uuid) {
            this(uuid, "main");
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return null;
        }
    }

    public static ItemStack glassFiller(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.lore(List.of());
        item.setItemMeta(meta);
        return item;
    }

    public static void playUiClick(Player player, float pitch) {
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, pitch);
        } catch (Throwable ignored) {
        }
    }

    /** Word-wraps {@code text} to lines no longer than {@code maxLen} characters. */
    public static List<String> wrapText(String text, int maxLen) {
        if (text == null || text.isEmpty()) return List.of();
        if (text.length() <= maxLen) return List.of(text);
        List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty() && sb.length() + 1 + word.length() > maxLen) {
                lines.add(sb.toString().trim());
                sb.setLength(0);
            }
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word);
        }
        if (!sb.isEmpty()) lines.add(sb.toString().trim());
        return lines;
    }

    /** A prev/next page arrow, target page shown in lore (not stack count). */
    public static ItemStack buildPageNavArrow(boolean isNext, int targetPage, TextColor color) {
        Material mat = isNext ? Material.LIME_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(isNext ? "▶" : "◄").color(color).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text((isNext ? "ɴᴇxᴛ ᴘᴀɢᴇ" : "ᴘʀᴇᴠ ᴘᴀɢᴇ") + " (" + targetPage + ")")
                        .color(DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    /** A search icon; {@code active} indicates a filter is currently applied (adds a glow + clear hint). */
    public static ItemStack buildSearchItem(String currentFilter) {
        boolean active = currentFilter != null && !currentFilter.isBlank();
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("🔍 ꜱᴇᴀʀᴄʜ")
                        .color(active ? ON_GREEN : ACCENT)
                        .decoration(TextDecoration.BOLD, true)));
        List<Component> lore = new java.util.ArrayList<>();
        if (active) {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ꜰɪʟᴛᴇʀ: ").color(DARK_GRAY))
                    .append(Component.text(currentFilter).color(VALUE_YELLOW)));
            lore.add(Component.empty());
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("◈ ").color(ACCENT))
                    .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴄʜᴀɴɢᴇ").color(DARK_GRAY)));
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✕ ").color(OFF_RED))
                    .append(Component.text("ꜱʜɪꜰᴛ+ᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴇᴀʀ").color(DARK_GRAY)));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        } else {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ꜱᴇᴀʀᴄʜ ʙʏ ɴᴀᴍᴇ").color(DARK_GRAY)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── Chat capture (search prompts) ──────────────────────────────────────────

    private record PendingCapture(Consumer<String> onSubmit, Runnable onCancel, int timeoutTaskId) {}

    private static final Map<UUID, PendingCapture> CAPTURES = new HashMap<>();
    private static ChatCaptureListener listenerInstance;
    private static Plugin ownerPlugin;

    /** Must be called once at plugin startup before any GUI uses {@link #beginCapture}. */
    public static void registerChatCapture(Plugin plugin) {
        ownerPlugin = plugin;
        if (listenerInstance != null) return;
        listenerInstance = new ChatCaptureListener();
        Bukkit.getPluginManager().registerEvents(listenerInstance, plugin);
    }

    /**
     * Captures the next chat message from {@code player} instead of letting it reach normal chat.
     * Calls {@code onSubmit} with the typed text, or {@code onCancel} if the player types "cancel" /
     * quits / times out.
     */
    public static void beginCapture(
            Plugin plugin, Player player, Consumer<String> onSubmit, Runnable onCancel, long timeoutTicks) {
        UUID uuid = player.getUniqueId();
        cancelCapture(uuid);
        int taskId = FppScheduler.runSyncLaterWithId(
                plugin,
                () -> {
                    PendingCapture pc = CAPTURES.remove(uuid);
                    if (pc != null && pc.onCancel() != null) pc.onCancel().run();
                },
                timeoutTicks);
        CAPTURES.put(uuid, new PendingCapture(onSubmit, onCancel, taskId));
    }

    private static void cancelCapture(UUID uuid) {
        PendingCapture prev = CAPTURES.remove(uuid);
        if (prev != null && prev.timeoutTaskId() >= 0) FppScheduler.cancelTask(prev.timeoutTaskId());
    }

    private static final class ChatCaptureListener implements Listener {

        @EventHandler
        public void onModernChat(AsyncChatEvent event) {
            handle(event.getPlayer(), PlainTextComponentSerializer.plainText().serialize(event.message()), event);
        }

        // AsyncPlayerChatEvent is deprecated in favor of AsyncChatEvent, but some server/plugin setups
        // still only fire the legacy event - kept as a fallback alongside onModernChat above.
        @SuppressWarnings("deprecation")
        @EventHandler
        public void onLegacyChat(AsyncPlayerChatEvent event) {
            if (!CAPTURES.containsKey(event.getPlayer().getUniqueId())) return;
            handle(event.getPlayer(), event.getMessage(), event);
        }

        private void handle(Player player, String raw, org.bukkit.event.Cancellable event) {
            UUID uuid = player.getUniqueId();
            PendingCapture pc = CAPTURES.get(uuid);
            if (pc == null) return;
            event.setCancelled(true);
            cancelCapture(uuid);
            String trimmed = raw.trim();
            if (ownerPlugin == null) return;
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                if (trimmed.equalsIgnoreCase("cancel")) {
                    if (pc.onCancel() != null) pc.onCancel().run();
                } else if (pc.onSubmit() != null) {
                    pc.onSubmit().accept(trimmed);
                }
            });
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            cancelCapture(event.getPlayer().getUniqueId());
        }
    }
}
