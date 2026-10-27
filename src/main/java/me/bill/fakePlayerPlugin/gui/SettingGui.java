package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.PathfindingDebugManager;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.AttributionManager;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class SettingGui implements Listener {

    private static final TextColor ACCENT = GuiKit.ACCENT;
    private static final TextColor ON_GREEN = GuiKit.ON_GREEN;
    private static final TextColor OFF_RED = GuiKit.OFF_RED;
    private static final TextColor VALUE_YELLOW = GuiKit.VALUE_YELLOW;
    private static final TextColor YELLOW = GuiKit.YELLOW;
    private static final TextColor GRAY = GuiKit.GRAY;
    private static final TextColor DARK_GRAY = GuiKit.DARK_GRAY;
    private static final TextColor WHITE = GuiKit.WHITE;
    private static final TextColor COMING_SOON_COLOR = GuiKit.COMING_SOON_COLOR;

    private static final int SIZE = 54;
    /** Cards per page - laid out centred on a backdrop (the 45-slot grid is only the content area). */
    private static final int SETTINGS_PER_PAGE = GuiKit.CONTENT_CAPACITY;

    private static final int SLOT_RESET = 44;
    private static final int SLOT_CAT_PREV = 46;
    private static final int SLOT_CAT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;

    private static final int CAT_WINDOW = 5;

    private static final int CAT_WINDOW_START = 47;

    private static final UUID SKIN_OWNER_UUID = UUID.fromString("a318f9f4-e2bf-479c-a47a-6a2c1b0b9e66");
    private static final String SKIN_OWNER_NAME = "F_PP";

    private static final long SKULL_TTL_MS = 30L * 60 * 1_000;

    private volatile ItemStack cachedOwnerSkull = null;
    private volatile long skullRefreshedAt = 0L;

    private final FakePlayerPlugin plugin;

    private final Map<UUID, int[]> sessions = new HashMap<>();

    private final Map<UUID, ChatInputSession> chatSessions = new HashMap<>();

    private final Set<UUID> pendingChatInput = new HashSet<>();

    private final Set<UUID> pendingRebuild = new HashSet<>();

    private final Category[] categories;

    public SettingGui(FakePlayerPlugin plugin) {
        this.plugin = plugin;
        this.categories = new Category[] {general(), body(), chunks(), auth(), perf(), debug()};

        if (!AttributionManager.quickAuthorCheck()) {
            // Attribution integrity check failed - silently continuing.
        }
    }

    public void open(Player player) {
        sessions.put(player.getUniqueId(), new int[] {0, 0, 0});
        build(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getInventory())) return;
        if (!Perm.has(player, Perm.SETTINGS)) return;

        int[] state = sessions.get(holder.uuid);
        if (state == null) return;

        List<SettingsTabRef> tabs = visibleTabs(player);
        if (tabs.isEmpty()) return;

        int slot = event.getSlot();
        int catIdx = state[0];
        int pageIdx = state[1];
        int catOffset = state[2];
        if (catIdx >= tabs.size()) catIdx = tabs.size() - 1;
        SettingsTabRef currentTab = tabs.get(catIdx);

        if (slot == SLOT_RESET) {
            playUiClick(player, 0.6f);
            resetAllCategories(player);
            return;
        }

        if (slot == SLOT_CAT_PREV) {
            if (catOffset > 0) {
                playUiClick(player, 1.0f);
                state[2]--;
            }
            build(player);
            return;
        }

        if (slot == SLOT_CAT_NEXT) {
            if (catOffset + CAT_WINDOW < tabs.size()) {
                playUiClick(player, 1.0f);
                state[2]++;
            }
            build(player);
            return;
        }

        if (slot == SLOT_CLOSE) {
            playUiClick(player, 0.8f);
            if (event.isShiftClick() && Perm.has(player, Perm.LIST)) {
                // Switching inventories still fires the close event, so settings save as usual.
                player.performCommand("fpp list");
                return;
            }
            // Back to the main menu (its open fires this menu's close event, so settings save as usual).
            GuiKit.backOrClose(player);
            return;
        }

        if (slot == GuiKit.SLOT_CONTENT_PREV || slot == GuiKit.SLOT_CONTENT_NEXT) {
            int total = Math.max(1, (int) Math.ceil(currentTab.entries(player).size() / (double) SETTINGS_PER_PAGE));
            int target = slot == GuiKit.SLOT_CONTENT_NEXT ? pageIdx + 1 : pageIdx - 1;
            if (target >= 0 && target < total) {
                playUiClick(player, 1.0f);
                state[1] = target;
                build(player);
            } else {
                GuiKit.playError(player);
            }
            return;
        }

        if (slot >= CAT_WINDOW_START && slot < CAT_WINDOW_START + CAT_WINDOW) {
            int ci = catOffset + (slot - CAT_WINDOW_START);
            if (ci < tabs.size()) {
                if (ci != catIdx) playUiClick(player, 1.3f);
                state[0] = ci;
                state[1] = 0;
                build(player);
            }
            return;
        }

        List<SettingEntry> pageEntries = currentTab.entries(player);
        int onPage = Math.min(SETTINGS_PER_PAGE, Math.max(0, pageEntries.size() - pageIdx * SETTINGS_PER_PAGE));
        int settingIdx = GuiKit.centeredSlots(onPage).indexOf(slot);
        if (settingIdx >= 0) {
            List<SettingEntry> settings = pageEntries;
            int entryIdx = pageIdx * SETTINGS_PER_PAGE + settingIdx;
            if (entryIdx >= settings.size()) return;

            SettingEntry entry = settings.get(entryIdx);

            if (PATHFINDING_DEBUG_ALL_KEY.equals(entry.configKey)) {
                boolean newState = !PathfindingDebugManager.isViewingAny(player.getUniqueId());
                for (FakePlayer fp : plugin.getFakePlayerManager().getActivePlayers()) {
                    PathfindingDebugManager.setViewing(player.getUniqueId(), fp.getUuid(), newState);
                }
                playUiClick(player, newState ? 1.2f : 0.85f);
                sendActionBarConfirm(player, entry.label, newState ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ");
                build(player);
                return;
            }

            if (entry.type == SettingType.COMING_SOON) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.8f, 1.0f);
                player.sendActionBar(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("⊘ ").color(COMING_SOON_COLOR))
                        .append(Component.text(entry.label + "  ").color(WHITE).decoration(TextDecoration.BOLD, false))
                        .append(Component.text("- ᴄᴏᴍɪɴɢ ꜱᴏᴏɴ")
                                .color(COMING_SOON_COLOR)
                                .decoration(TextDecoration.BOLD, true)));
                return;
            }

            if (entry.type == SettingType.ACTION) {
                if (entry.clickAction != null) entry.clickAction.run();
                else handleAction(player, entry.configKey);
                build(player);
            } else if (entry.type == SettingType.TOGGLE) {
                entry.apply(plugin);
                plugin.saveConfig();
                Config.reload();
                applyLiveEffect(entry.configKey);
                String newVal = entry.currentValueString(plugin);
                playUiClick(player, newVal.startsWith("✔") ? 1.2f : 0.85f);
                sendActionBarConfirm(player, entry.label, newVal);
                build(player);
            } else if (entry.type == SettingType.DEBUG_TOGGLE) {
                entry.applyDebugToggle();
                Config.reload();
                String newVal = entry.currentValueString(plugin);
                playUiClick(player, newVal.startsWith("✔") ? 1.2f : 0.85f);
                sendActionBarConfirm(player, entry.label, newVal);
                build(player);
            } else {
                playUiClick(player, 1.0f);
                openChatInput(player, entry, state.clone());
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!(event.getInventory().getHolder() instanceof GuiHolder)) return;

        if (pendingChatInput.contains(uuid)) return;

        if (pendingRebuild.contains(uuid)) return;
        sessions.remove(uuid);

        plugin.saveConfig();
        Config.reload();

        if (event.getReason() != InventoryCloseEvent.Reason.DISCONNECT && event.getPlayer() instanceof Player player) {
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✔ ").color(ON_GREEN))
                    .append(Component.text("ꜱᴇᴛᴛɪɴɢꜱ ꜱᴀᴠᴇᴅ • ᴄᴏɴꜰɪɢ ᴜᴘᴅᴀᴛᴇᴅ").color(WHITE)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputSession ses = chatSessions.remove(uuid);
        if (ses == null) return;

        event.setCancelled(true);
        handleChatInput(
                uuid,
                ses,
                PlainTextComponentSerializer.plainText()
                        .serialize(event.message())
                        .trim());
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputSession ses = chatSessions.remove(uuid);
        if (ses == null) return;

        event.setCancelled(true);
        handleChatInput(uuid, ses, event.getMessage().trim());
    }

    private void handleChatInput(UUID uuid, ChatInputSession ses, String raw) {
        FppScheduler.cancelTask(ses.cleanupTaskId);

        sessions.put(uuid, ses.guiState);
        FppScheduler.runSync(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;

            if (raw.equalsIgnoreCase("cancel")) {
                p.sendMessage(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("✦ ").color(ACCENT))
                        .append(Component.text("ᴄᴀɴᴄᴇʟʟᴇᴅ - ʀᴇᴛᴜʀɴɪɴɢ ᴛᴏ" + " ꜱᴇᴛᴛɪɴɢꜱ.")
                                .color(GRAY)));
                build(p);
                return;
            }

            boolean ok = tryApply(p, ses.entry, raw);
            if (ok) {
                plugin.saveConfig();
                Config.reload();
                applyLiveEffect(ses.entry.configKey);
                sendActionBarConfirm(p, ses.entry.label, ses.entry.currentValueString(plugin));
            }
            build(p);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        pendingChatInput.remove(uuid);
        ChatInputSession ses = chatSessions.remove(uuid);
        if (ses != null) {
            FppScheduler.cancelTask(ses.cleanupTaskId);
        }
    }

    private void openChatInput(Player player, SettingEntry entry, int[] guiState) {
        UUID uuid = player.getUniqueId();

        pendingChatInput.add(uuid);
        player.closeInventory();
        pendingChatInput.remove(uuid);

        String currentVal = entry.currentValueString(plugin).replace("✔ ", "").replace("✘ ", "");

        player.sendMessage(Component.empty());
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("┌─ ").color(DARK_GRAY))
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("]  ").color(DARK_GRAY))
                .append(Component.text("ꜱᴇᴛᴛɪɴɢꜱ").color(WHITE).decoration(TextDecoration.BOLD, true))
                .append(Component.text("  ·  ᴇᴅɪᴛ ᴠᴀʟᴜᴇ").color(DARK_GRAY)));
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("│  ").color(DARK_GRAY))
                .append(Component.text(entry.label).color(VALUE_YELLOW).decoration(TextDecoration.BOLD, true)));
        for (String line : entry.description.split("\\\\n|\n")) {
            if (!line.isBlank()) {
                player.sendMessage(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("│  ").color(DARK_GRAY))
                        .append(Component.text(line).color(GRAY)));
            }
        }
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("│  ").color(DARK_GRAY)));
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("│  ").color(DARK_GRAY))
                .append(Component.text("ᴄᴜʀʀᴇɴᴛ  ").color(DARK_GRAY))
                .append(Component.text(currentVal).color(VALUE_YELLOW).decoration(TextDecoration.BOLD, true)));
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("└─ ").color(DARK_GRAY))
                .append(Component.text("ᴛʏᴘᴇ ᴀ ɴᴇᴡ ᴠᴀʟᴜᴇ, ᴏʀ ").color(GRAY))
                .append(Component.text("ᴄᴀɴᴄᴇʟ").color(OFF_RED).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" ᴛᴏ ɢᴏ ʙᴀᴄᴋ.").color(GRAY)));
        player.sendMessage(Component.empty());

        int taskId = FppScheduler.runSyncLaterWithId(
                plugin,
                () -> {
                    ChatInputSession stale = chatSessions.remove(uuid);
                    if (stale != null) {
                        sessions.put(uuid, stale.guiState);
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.sendMessage(Component.empty()
                                    .decoration(TextDecoration.ITALIC, false)
                                    .append(Component.text("✦ ").color(ACCENT))
                                    .append(Component.text("ɪɴᴘᴜᴛ ᴛɪᴍᴇᴅ" + " ᴏᴜᴛ -" + " ʀᴇᴛᴜʀɴɪɴɢ" + " ᴛᴏ ꜱᴇᴛᴛɪɴɢꜱ.")
                                            .color(GRAY)));
                            build(p);
                        }
                    }
                },
                20L * 60);

        chatSessions.put(uuid, new ChatInputSession(entry, guiState, taskId));
    }

    private boolean tryApply(Player player, SettingEntry entry, String raw) {
        var cfg = plugin.getConfig();
        try {
            switch (entry.type) {
                case CYCLE_INT -> {
                    int val = Integer.parseInt(raw);
                    if (val < 0) {
                        player.sendMessage(Component.empty()
                                .decoration(TextDecoration.ITALIC, false)
                                .append(Component.text("✘ ").color(OFF_RED))
                                .append(Component.text("ᴠᴀʟᴜᴇ ᴍᴜꜱᴛ ʙᴇ ").color(GRAY))
                                .append(Component.text("0 ᴏʀ ɢʀᴇᴀᴛᴇʀ").color(VALUE_YELLOW))
                                .append(Component.text(".").color(GRAY)));
                        return false;
                    }
                    cfg.set(entry.configKey, val);
                }
                case CYCLE_DOUBLE -> {
                    double val = Double.parseDouble(raw);
                    if (val < 0) {
                        player.sendMessage(Component.empty()
                                .decoration(TextDecoration.ITALIC, false)
                                .append(Component.text("✘ ").color(OFF_RED))
                                .append(Component.text("ᴠᴀʟᴜᴇ ᴍᴜꜱᴛ ʙᴇ ").color(GRAY))
                                .append(Component.text("0 ᴏʀ ɢʀᴇᴀᴛᴇʀ").color(VALUE_YELLOW))
                                .append(Component.text(".").color(GRAY)));
                        return false;
                    }
                    cfg.set(entry.configKey, val);
                }
                case STRING -> {
                    if (raw.isBlank()) {
                        player.sendMessage(Component.empty()
                                .decoration(TextDecoration.ITALIC, false)
                                .append(Component.text("✘ ").color(OFF_RED))
                                .append(Component.text("ᴠᴀʟᴜᴇ ᴄᴀɴ'ᴛ ʙᴇ ᴇᴍᴘᴛʏ.").color(GRAY)));
                        return false;
                    }
                    cfg.set(entry.configKey, raw);
                }
                default -> {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✘ ").color(OFF_RED))
                    .append(Component.text("\"").color(GRAY))
                    .append(Component.text(raw).color(VALUE_YELLOW))
                    .append(Component.text("\" ɪꜱ ɴᴏᴛ ᴀ ᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ.").color(GRAY)));
            return false;
        }
        return true;
    }

    private void build(Player player) {
        UUID uuid = player.getUniqueId();
        int[] state = sessions.get(uuid);
        if (state == null) return;

        int catIdx = state[0];
        int pageIdx = state[1];
        int catOffset = state[2];
        List<SettingsTabRef> tabs = visibleTabs(player);
        if (tabs.isEmpty()) return;
        if (catIdx >= tabs.size()) catIdx = tabs.size() - 1;
        state[0] = catIdx;
        SettingsTabRef tab = tabs.get(catIdx);

        GuiHolder holder = new GuiHolder(uuid);
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(tab.label()).color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, SIZE, title);

        List<SettingEntry> settings = tab.entries(player);
        int settingsCount = settings.size();
        int totalPages = Math.max(1, (int) Math.ceil(settingsCount / (double) SETTINGS_PER_PAGE));
        pageIdx = Math.min(pageIdx, Math.max(0, totalPages - 1));
        state[1] = pageIdx;

        int startIdx = pageIdx * SETTINGS_PER_PAGE;
        int endIdx = Math.min(startIdx + SETTINGS_PER_PAGE, settingsCount);
        GuiKit.fillContentBackdrop(inv);
        List<Integer> cardSlots = GuiKit.centeredSlots(endIdx - startIdx);
        for (int i = startIdx; i < endIdx; i++) {
            inv.setItem(cardSlots.get(i - startIdx), buildSettingItem(settings.get(i), player));
        }
        if (totalPages > 1) {
            if (pageIdx > 0) {
                inv.setItem(
                        GuiKit.SLOT_CONTENT_PREV, GuiKit.buildPageNavArrow(false, pageIdx, GuiKit.COMING_SOON_COLOR));
            }
            if (pageIdx < totalPages - 1) {
                inv.setItem(GuiKit.SLOT_CONTENT_NEXT, GuiKit.buildPageNavArrow(true, pageIdx + 2, GuiKit.ON_GREEN));
            }
        }

        inv.setItem(GuiKit.SLOT_CREDIT, GuiKit.creditHead(plugin));
        inv.setItem(SLOT_RESET, buildResetAllButton());

        inv.setItem(
                SLOT_CAT_PREV, catOffset > 0 ? buildCatArrow(false) : glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        for (int i = 0; i < CAT_WINDOW; i++) {
            int ci = catOffset + i;
            inv.setItem(
                    CAT_WINDOW_START + i,
                    ci < tabs.size()
                            ? buildCategoryTab(tabs.get(ci), ci == catIdx)
                            : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        }

        inv.setItem(
                SLOT_CAT_NEXT,
                catOffset + CAT_WINDOW < tabs.size()
                        ? buildCatArrow(true)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        inv.setItem(SLOT_CLOSE, GuiKit.backOrClose());

        pendingRebuild.add(uuid);
        player.openInventory(inv);
        pendingRebuild.remove(uuid);
        sessions.put(uuid, state);
    }

    private ItemStack buildSettingItem(SettingEntry entry, Player viewer) {

        if (entry.type == SettingType.COMING_SOON) {

            ItemStack item =
                    "skin.guaranteed-skin".equals(entry.configKey) ? getOwnerSkull() : new ItemStack(entry.icon);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("⊘ ").color(COMING_SOON_COLOR))
                    .append(Component.text(entry.label)
                            .color(COMING_SOON_COLOR)
                            .decoration(TextDecoration.BOLD, true)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ᴠᴀʟᴜᴇ  ").color(DARK_GRAY))
                    .append(Component.text("⚠ ᴄᴏᴍɪɴɢ ꜱᴏᴏɴ")
                            .color(COMING_SOON_COLOR)
                            .decoration(TextDecoration.BOLD, true)));
            lore.add(Component.empty());
            for (String line : entry.description.split("\\\\n|\n")) {
                if (!line.isBlank()) {
                    lore.add(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text(line).color(GRAY)));
                }
            }
            lore.add(Component.empty());
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("⊘ ").color(COMING_SOON_COLOR))
                    .append(Component.text("ꜰᴇᴀᴛᴜʀᴇ ᴜɴᴀᴠᴀɪʟᴀʙʟᴇ").color(DARK_GRAY)));
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        boolean isToggle = entry.type == SettingType.TOGGLE;
        boolean isOn = isToggle
                && (PATHFINDING_DEBUG_ALL_KEY.equals(entry.configKey)
                        ? PathfindingDebugManager.isViewingAny(viewer.getUniqueId())
                        : plugin.getConfig().getBoolean(entry.configKey, false));

        TextColor nameColor = isToggle ? (isOn ? ON_GREEN : OFF_RED) : ACCENT;

        ItemStack item = "skin.guaranteed-skin".equals(entry.configKey) ? getOwnerSkull() : new ItemStack(entry.icon);
        ItemMeta meta = item.getItemMeta();

        if (isToggle && isOn) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(entry.label).color(nameColor).decoration(TextDecoration.BOLD, true)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        String valStr = PATHFINDING_DEBUG_ALL_KEY.equals(entry.configKey)
                ? (isOn ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ")
                : entry.currentValueString(plugin);
        TextColor valColor = isToggle ? (isOn ? ON_GREEN : OFF_RED) : VALUE_YELLOW;
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ᴠᴀʟᴜᴇ  ").color(DARK_GRAY))
                .append(Component.text(valStr).color(valColor).decoration(TextDecoration.BOLD, true)));
        lore.add(Component.empty());

        String[] descLines = entry.description.split("\\\\n|\n");
        for (String line : descLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                for (String wrapped : wrapText(trimmed, 40)) {
                    lore.add(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text(wrapped).color(GRAY)));
                }
            }
        }
        lore.add(Component.empty());

        if (isToggle) {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("◈ ").color(ACCENT))
                    .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴛᴏɢɢʟᴇ").color(DARK_GRAY)));
        } else if (entry.type == SettingType.ACTION) {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("⚠ ").color(OFF_RED))
                    .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ʀᴜɴ ᴛʜɪꜱ ᴀᴄᴛɪᴏɴ").color(DARK_GRAY)));
        } else {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✎ ").color(ACCENT))
                    .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ꜱᴇᴛ ᴀ ᴠᴀʟᴜᴇ ɪɴ ᴄʜᴀᴛ").color(DARK_GRAY)));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<SettingsTabRef> visibleTabs(Player viewer) {
        List<SettingsTabRef> tabs = new ArrayList<>(categories.length);
        for (Category category : categories) {
            tabs.add(new SettingsTabRef(category));
        }
        return tabs;
    }

    private ItemStack buildCategoryTab(SettingsTabRef tab, boolean active) {
        Material mat = active ? tab.activeMat() : tab.inactiveMat();
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(tab.label()).color(ACCENT).decoration(TextDecoration.BOLD, active)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(active ? "◈  ᴄᴜʀʀᴇɴᴛʟʏ ᴠɪᴇᴡɪɴɢ" : "ᴄʟɪᴄᴋ ᴛᴏ ꜱᴡɪᴛᴄʜ")
                        .color(active ? ON_GREEN : DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCatArrow(boolean isNext) {
        Material mat = isNext ? Material.LIME_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE;
        String label = isNext ? "▶" : "◄";
        TextColor col = isNext ? ON_GREEN : COMING_SOON_COLOR;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(label).color(col).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ꜱᴄʀᴏʟʟ ᴄᴀᴛᴇɢᴏʀɪᴇꜱ " + (isNext ? "ꜰᴏʀᴡᴀʀᴅ" : "ʙᴀᴄᴋᴡᴀʀᴅ") + ".")
                        .color(DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private record SettingsTabRef(Category builtin) {

        String label() {
            return builtin.label;
        }

        Material activeMat() {
            return builtin.activeMat;
        }

        Material inactiveMat() {
            return builtin.inactiveMat;
        }

        Material separatorGlass() {
            return builtin.separatorGlass;
        }

        List<SettingEntry> entries(Player viewer) {
            return builtin.settings;
        }
    }

    private ItemStack buildResetAllButton() {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⟲  ʀᴇꜱᴇᴛ ᴀʟʟ").color(YELLOW).decoration(TextDecoration.BOLD, false)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ʀᴇꜱᴇᴛ ᴇᴠᴇʀʏ ꜱᴇᴛᴛɪɴɢ ᴀᴄʀᴏꜱꜱ").color(GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴀʟʟ ᴄᴀᴛᴇɢᴏʀɪᴇꜱ ᴛᴏ ᴅᴇꜰᴀᴜʟᴛꜱ.").color(GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getOwnerSkull() {
        long now = System.currentTimeMillis();
        ItemStack cached = cachedOwnerSkull;
        if (cached != null && (now - skullRefreshedAt) < SKULL_TTL_MS) {
            return cached.clone();
        }

        ItemStack skull = buildSkullSync();
        cachedOwnerSkull = skull;
        skullRefreshedAt = now;

        scheduleSkullRefresh();
        return skull.clone();
    }

    private ItemStack buildSkullSync() {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            PlayerProfile profile = Bukkit.createProfile(SKIN_OWNER_UUID, SKIN_OWNER_NAME);
            meta.setPlayerProfile(profile);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private void scheduleSkullRefresh() {
        FppScheduler.runAsync(plugin, () -> {
            try {
                PlayerProfile profile = Bukkit.createProfile(SKIN_OWNER_UUID, SKIN_OWNER_NAME);
                profile.complete(true);
                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) skull.getItemMeta();
                if (meta != null) {
                    meta.setPlayerProfile(profile);
                    skull.setItemMeta(meta);
                }
                cachedOwnerSkull = skull;
                skullRefreshedAt = System.currentTimeMillis();
            } catch (Exception ignored) {

            }
        });
    }

    private void resetAllCategories(Player player) {
        var cfg = plugin.getConfig();
        var defaults = cfg.getDefaults();
        for (Category cat : categories) {
            for (SettingEntry entry : cat.settings) {
                switch (entry.type) {
                    case TOGGLE -> cfg.set(
                            entry.configKey, defaults != null ? defaults.getBoolean(entry.configKey, false) : false);
                    case CYCLE_INT -> cfg.set(
                            entry.configKey,
                            defaults != null
                                    ? defaults.getInt(entry.configKey, entry.intValues[0])
                                    : entry.intValues[0]);
                    case CYCLE_DOUBLE -> cfg.set(
                            entry.configKey,
                            defaults != null
                                    ? defaults.getDouble(entry.configKey, entry.dblValues[0])
                                    : entry.dblValues[0]);
                    case STRING -> cfg.set(
                            entry.configKey, defaults != null ? defaults.getString(entry.configKey, "") : "");
                    default -> {}
                }
            }
        }
        plugin.saveConfig();
        Config.reload();
        for (Category cat : categories) {
            for (SettingEntry entry : cat.settings) {
                applyLiveEffect(entry.configKey);
            }
        }
        build(player);
        player.sendActionBar(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⟲ ").color(YELLOW))
                .append(Component.text("ᴀʟʟ ꜱᴇᴛᴛɪɴɢꜱ  ").color(WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.text("ʀᴇꜱᴇᴛ ᴛᴏ ᴅᴇꜰᴀᴜʟᴛꜱ").color(YELLOW).decoration(TextDecoration.BOLD, true)));
    }

    private ItemStack glassFiller(Material mat) {
        return GuiKit.glassFiller(mat);
    }

    private void applyLiveEffect(String configKey) {
        FakePlayerManager fpm = plugin.getFakePlayerManager();

        if (configKey.startsWith("performance.self-profiler")) {
            var profiler = plugin.getBuiltinProfiler();
            if (profiler != null) {
                if (Config.performanceSelfProfilerEnabled()) profiler.start();
                else profiler.stop();
                profiler.setMethodLevelEnabled(Config.performanceSelfProfilerMethodLevel());
            }
            return;
        }

        if (configKey.startsWith("performance.")) {
            // Every performance.* key (interval, thresholds, spark toggle, ...) is only read at
            // PerformanceMonitor#start() time, so a plain stop()+start() picks up whatever changed -
            // start() itself checks performance.enabled and no-ops if it's now off.
            var monitor = plugin.getPerformanceMonitor();
            if (monitor != null) {
                monitor.stop();
                monitor.start();
            }
            return;
        }

        if (configKey.startsWith("auth.")) {
            // Nothing to restart live - auth.* is read fresh on every bot join (see
            // BotAuthManager#handleBotJoin), so a config change only affects the *next* join.
            return;
        }

        if (configKey.equals("tab-list.enabled")) {
            if (fpm != null) fpm.applyTabListConfig();
            return;
        }

        if (configKey.equals("body.pushable")
                || configKey.equals("body.damageable")
                || configKey.equals("combat.max-health")) {
            if (fpm != null) fpm.applyBodyConfig();
            return;
        }

        if (configKey.equals("body.pick-up-items")) {
            boolean enabled = plugin.getConfig().getBoolean("body.pick-up-items", false);
            if (fpm != null) {

                fpm.getActivePlayers().forEach(fp -> {
                    Player body = fp.getPlayer();
                    if (body != null) body.setCanPickupItems(enabled && fp.isPickUpItemsEnabled());
                });
                if (!enabled) {
                    fpm.getActivePlayers().forEach(this::dropBotInventoryWithAnimation);
                }
            }
            return;
        }

        if (configKey.equals("body.pick-up-xp")) {
            boolean enabled = plugin.getConfig().getBoolean("body.pick-up-xp", true);
            if (!enabled && fpm != null) {
                fpm.getActivePlayers().forEach(fp -> {
                    Player bot = fp.getPlayer();
                    if (bot == null || !bot.isOnline()) return;
                    int xp = bot.getTotalExperience();
                    if (xp <= 0) return;
                    World world = bot.getWorld();
                    Location loc = bot.getLocation();
                    world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(xp));
                    bot.setTotalExperience(0);
                    bot.setLevel(0);
                    bot.setExp(0f);
                });
            }
            return;
        }

        if (configKey.equals("skin.guaranteed-skin")) {
            boolean enabled = plugin.getConfig().getBoolean("skin.guaranteed-skin", false);
            if (fpm != null && plugin.getSkinManager() != null) {
                fpm.getActivePlayers().forEach(fp -> {
                    Player bot = fp.getPlayer();
                    if (bot == null || !bot.isOnline()) return;

                    if (enabled) {

                        plugin.getSkinManager().resolveEffectiveSkin(fp, skin -> {
                            if (skin == null || !skin.isValid()) {
                                Config.debugSkin(
                                        "SettingGui: no valid skin" + " resolved for bot '" + fp.getName() + "'");
                                return;
                            }
                            FppScheduler.runSyncLater(
                                    plugin,
                                    () -> {
                                        Player b = fp.getPlayer();
                                        if (b == null || !b.isOnline()) return;
                                        plugin.getSkinManager().applySkinFromProfile(fp, skin);
                                        Config.debugSkin("SettingGui:"
                                                + " re-applied"
                                                + " custom"
                                                + " skin"
                                                + " for bot"
                                                + " '"
                                                + fp.getName()
                                                + "'");
                                    },
                                    3L);
                        });
                    } else {

                        boolean reset = plugin.getSkinManager().resetToDefaultSkin(fp);
                        Config.debugSkin(
                                "SettingGui: reset bot '" + fp.getName() + "' to default skin (success=" + reset + ")");
                    }
                });
            }
        }
    }

    private void dropBotInventoryWithAnimation(FakePlayer fp) {
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) return;

        boolean hasItems = false;
        for (ItemStack item : bot.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) return;

        Location loc = bot.getLocation();
        float origYaw = loc.getYaw();
        float origPitch = loc.getPitch();

        bot.setRotation(origYaw, 90f);
        NmsPlayerSpawner.setHeadYaw(bot, origYaw);

        FppScheduler.runSyncLater(
                plugin,
                () -> {
                    Player b = fp.getPlayer();
                    if (b == null || !b.isOnline()) return;

                    ItemStack[] contents = b.getInventory().getContents().clone();
                    b.getInventory().clear();
                    for (ItemStack item : contents) {
                        if (item != null && item.getType() != Material.AIR) {
                            b.getWorld().dropItemNaturally(b.getLocation(), item);
                        }
                    }

                    FppScheduler.runSyncLater(
                            plugin,
                            () -> {
                                Player b2 = fp.getPlayer();
                                if (b2 == null || !b2.isOnline()) return;
                                b2.setRotation(origYaw, origPitch);
                                NmsPlayerSpawner.setHeadYaw(b2, origYaw);
                            },
                            5L);
                },
                3L);
    }

    private void sendActionBarConfirm(Player player, String label, String newVal) {
        player.sendActionBar(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✔ ").color(ON_GREEN))
                .append(Component.text(label + "  ").color(WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.text("→  ").color(DARK_GRAY))
                .append(Component.text(newVal).color(VALUE_YELLOW).decoration(TextDecoration.BOLD, true)));
    }

    private static void playUiClick(Player player, float pitch) {
        GuiKit.playUiClick(player, pitch);
    }

    private Category general() {
        return new Category(
                "⚙ ɢᴇɴᴇʀᴀʟ",
                Material.COMPARATOR,
                Material.GRAY_DYE,
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                "persistence.enabled",
                                "ᴘᴇʀꜱɪꜱᴛ ᴏɴ ʀᴇꜱᴛᴀʀᴛ",
                                "ʙᴏᴛꜱ ʀᴇꜱᴛᴏʀᴇ ᴛᴏ ᴛʜᴇɪʀ ʟᴀꜱᴛ ᴘᴏꜱɪᴛɪᴏɴ\nᴀꜰᴛᴇʀ ᴀ ꜱᴇʀᴠᴇʀ ʀᴇꜱᴛᴀʀᴛ.",
                                Material.ENDER_CHEST),
                        SettingEntry.toggle(
                                "chunk-loading.enabled",
                                "ᴄʜᴜɴᴋ ʟᴏᴀᴅɪɴɢ",
                                "ʙᴏᴛꜱ ᴋᴇᴇᴘ ꜱᴜʀʀᴏᴜɴᴅɪɴɢ ᴄʜᴜɴᴋꜱ\nʟᴏᴀᴅᴇᴅ ʟɪᴋᴇ ʀᴇᴀʟ ᴘʟᴀʏᴇʀꜱ.",
                                Material.GRASS_BLOCK),
                        SettingEntry.cycleInt(
                                "spawn-cooldown",
                                "ꜱᴘᴀᴡɴ ᴄᴏᴏʟᴅᴏᴡɴ (ꜱ)",
                                "ꜱᴇᴄᴏɴᴅꜱ ʙᴇᴛᴡᴇᴇɴ /ꜰᴘᴘ ꜱᴘᴀᴡɴ ᴜꜱᴇꜱ\nᴘᴇʀ ᴘʟᴀʏᴇʀ. 0 = ᴅɪꜱᴀʙʟᴇᴅ.",
                                Material.CLOCK,
                                new int[] {0, 10, 30, 60, 120, 300}),
                        SettingEntry.cycleInt(
                                "limits.max-bots",
                                "ɢʟᴏʙᴀʟ ʙᴏᴛ ᴄᴀᴘ",
                                "ᴍᴀxɪᴍᴜᴍ ʙᴏᴛꜱ ꜱᴇʀᴠᴇʀ-ᴡɪᴅᴇ.\n0 = ɴᴏ ʟɪᴍɪᴛ.",
                                Material.CHEST,
                                new int[] {10, 25, 50, 100, 250, 500, 1000}),
                        SettingEntry.cycleInt(
                                "limits.user-bot-limit",
                                "ᴘᴇʀ-ᴜꜱᴇʀ ʙᴏᴛ ʟɪᴍɪᴛ",
                                "ᴅᴇꜰᴀᴜʟᴛ ᴘᴇʀꜱᴏɴᴀʟ ʟɪᴍɪᴛ ꜰᴏʀ\nꜰᴘᴘ.ᴜꜱᴇʀ.ꜱᴘᴀᴡɴ ᴘʟᴀʏᴇʀꜱ.",
                                Material.SHIELD,
                                new int[] {1, 2, 3, 5, 10}),
                        SettingEntry.cycleInt(
                                "chunk-loading.radius",
                                "ᴄʜᴜɴᴋ ʟᴏᴀᴅ ʀᴀᴅɪᴜꜱ",
                                "ʟᴏɴɢᴇꜱᴛ ʀᴀɴᴅᴏᴍ ᴅᴇʟᴀʏ ʙᴇꜰᴏʀᴇ\nᴀ ʙᴏᴛ ʟᴇᴀᴠᴇꜱ. 20 = 1 ꜱᴇᴄᴏɴᴅ.",
                                Material.COMPASS,
                                new int[] {0, 2, 4, 6, 8, 12, 16}),
                        SettingEntry.cycleInt(
                                "chunk-loading.radius-duplicate",
                                "ᴄʜᴜɴᴋ ʟᴏᴀᴅ ʀᴀᴅɪᴜꜱ",
                                "ᴄʜᴜɴᴋ ʀᴀᴅɪᴜꜱ ᴋᴇᴘᴛ ʟᴏᴀᴅᴇᴅ ᴀʀᴏᴜɴᴅ\nᴇᴀᴄʜ ʙᴏᴛ. 0 = ꜱᴇʀᴠᴇʀ ᴅᴇꜰᴀᴜʟᴛ.",
                                Material.COMPASS,
                                new int[] {0, 2, 4, 6, 8, 12, 16}),
                        SettingEntry.action(
                                "reset-all-bots",
                                "ʀᴇꜱᴇᴛ ᴀʟʟ ʙᴏᴛꜱ",
                                "ᴅᴇꜱᴘᴀᴡɴ ᴀʟʟ ᴀᴄᴛɪᴠᴇ ʙᴏᴛꜱ ᴀɴᴅ\n" + "ᴄʟᴇᴀʀ ᴛʜᴇɪʀ ʀᴜɴɴɪɴɢ ᴛᴀꜱᴋꜱ.",
                                Material.TNT)));
    }

    private void handleAction(Player player, String key) {
        if ("reset-all-bots".equals(key)) {
            int count = plugin.getFakePlayerManager().getActivePlayers().size();
            plugin.getFakePlayerManager().removeAll();
            player.sendMessage(Component.text("Reset " + count + " active bot(s).", YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
    }

    private Category body() {
        return new Category(
                "🤖 ʙᴏᴅʏ",
                Material.ARMOR_STAND,
                Material.ARMOR_STAND,
                Material.LIME_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                "tab-list.enabled",
                                "ᴛᴀʙ ʟɪꜱᴛ",
                                "ꜱʜᴏᴡ ʙᴏᴛꜱ ɪɴ ᴛʜᴇ ᴛᴀʙ ʟɪꜱᴛ.\nᴀʟᴡᴀʏꜱ ʟɪꜱᴛᴇᴅ ᴀꜱ \"[ʙᴏᴛ] - <ɴᴀᴍᴇ>\".",
                                Material.NAME_TAG),
                        SettingEntry.toggle(
                                "body.pushable",
                                "ᴘᴜꜱʜᴀʙʟᴇ",
                                "ᴀʟʟᴏᴡ ᴘʟᴀʏᴇʀꜱ ᴀɴᴅ ᴇɴᴛɪᴛɪᴇꜱ\nᴛᴏ ᴘᴜꜱʜ ʙᴏᴛ ʙᴏᴅɪᴇꜱ.",
                                Material.PISTON),
                        SettingEntry.toggle(
                                "body.damageable",
                                "ᴅᴀᴍᴀɢᴇᴀʙʟᴇ",
                                "ʙᴏᴛꜱ ᴛᴀᴋᴇ ᴘʟᴀʏᴇʀ/ᴇɴᴛɪᴛʏ ᴅᴀᴍᴀɢᴇ.\nꜰᴀʟꜱᴇ = ɪᴍᴍᴜɴᴇ ᴛᴏ ᴘᴠᴘ/ᴍᴏʙꜱ ᴏɴʟʏ.",
                                Material.IRON_SWORD),
                        SettingEntry.toggle(
                                "body.auto-mount",
                                "ᴀᴜᴛᴏ ᴍᴏᴜɴᴛ",
                                "ʙᴏᴀᴛꜱ ᴀɴᴅ ᴍᴏᴠɪɴɢ ᴍɪɴᴇᴄᴀʀᴛꜱ ᴘɪᴄᴋ\nʙᴏᴛꜱ ᴜᴘ ᴡʜᴇɴ ᴛʜᴇʏ ʀᴜɴ ɪɴᴛᴏ ᴛʜᴇᴍ,\nʟɪᴋᴇ ᴠɪʟʟᴀɢᴇʀꜱ.",
                                Material.OAK_BOAT),
                        SettingEntry.toggle(
                                "body.pick-up-items",
                                "ᴘɪᴄᴋ ᴜᴘ ɪᴛᴇᴍꜱ",
                                "ʙᴏᴛꜱ ᴘɪᴄᴋ ᴜᴘ ɪᴛᴇᴍꜱ ꜰʀᴏᴍ ᴛʜᴇ ɢʀᴏᴜɴᴅ\nʟɪᴋᴇ ᴀ ʀᴇᴀʟ ᴘʟᴀʏᴇʀ.",
                                Material.HOPPER),
                        SettingEntry.toggle(
                                "body.pick-up-xp",
                                "ᴘɪᴄᴋ ᴜᴘ xᴘ",
                                "ʙᴏᴛꜱ ᴄᴏʟʟᴇᴄᴛ ᴇxᴘᴇʀɪᴇɴᴄᴇ ᴏʀʙꜱ\nꜰʀᴏᴍ ᴛʜᴇ ɢʀᴏᴜɴᴅ.",
                                Material.EXPERIENCE_BOTTLE),
                        SettingEntry.toggle(
                                "head-ai.enabled",
                                "ʜᴇᴀᴅ ᴀɪ",
                                "ʙᴏᴛꜱ ꜱᴍᴏᴏᴛʜʟʏ ʀᴏᴛᴀᴛᴇ ᴛᴏ ꜰᴀᴄᴇ\nᴛʜᴇ ɴᴇᴀʀᴇꜱᴛ ᴘʟᴀʏᴇʀ ɪɴ ʀᴀɴɢᴇ.",
                                Material.ENDER_EYE),
                        SettingEntry.toggle(
                                "swim-ai.enabled",
                                "ꜱᴡɪᴍ ᴀɪ",
                                "ʙᴏᴛꜱ ᴜꜱᴇ ʙᴀꜱɪᴄ ꜰʟᴏᴀᴛ/ᴊᴜᴍᴘ ꜱᴡɪᴍ ᴀɪ\nᴡʜᴇɴ ꜱᴜʙᴍᴇʀɢᴇᴅ ɪɴ ᴡᴀᴛᴇʀ ᴏʀ ʟᴀᴠᴀ.",
                                Material.WATER_BUCKET),
                        SettingEntry.toggle(
                                "death.respawn-on-death",
                                "ʀᴇꜱᴘᴀᴡɴ ᴏɴ ᴅᴇᴀᴛʜ",
                                "ʙᴏᴛꜱ ᴀᴜᴛᴏᴍᴀᴛɪᴄᴀʟʟʏ ᴄᴏᴍᴇ ʙᴀᴄᴋ\nᴀꜰᴛᴇʀ ʙᴇɪɴɢ ᴋɪʟʟᴇᴅ.",
                                Material.TOTEM_OF_UNDYING),
                        SettingEntry.toggle(
                                "death.suppress-drops",
                                "ꜱᴜᴘᴘʀᴇꜱꜱ ᴅʀᴏᴘꜱ",
                                "ʙᴏᴛꜱ ᴅʀᴏᴘ ɴᴏ ɪᴛᴇᴍꜱ ᴏʀ xᴘ\nᴡʜᴇɴ ᴛʜᴇʏ ᴅɪᴇ.",
                                Material.CHEST),
                        SettingEntry.toggle(
                                "body.drop-items-on-despawn",
                                "ᴅʀᴏᴘ ᴏɴ ᴅᴇꜱᴘᴀᴡɴ",
                                "ᴅʀᴏᴘ ɪɴᴠᴇɴᴛᴏʀʏ + xᴘ ᴡʜᴇɴ ᴀ ʙᴏᴛ\nɪꜱ ᴅᴇꜱᴘᴀᴡɴᴇᴅ. ᴏꜰꜰ = ʀᴇᴍᴇᴍʙᴇʀꜱ ɪᴛᴇᴍꜱ\nᴏɴ ɴᴇxᴛ ꜱᴘᴀᴡɴ ᴡɪᴛʜ ᴛʜᴇ ꜱᴀᴍᴇ ɴᴀᴍᴇ.",
                                Material.ENDER_CHEST),
                        SettingEntry.cycleDouble(
                                "combat.max-health",
                                "ᴍᴀx ʜᴇᴀʟᴛʜ (½-ʜᴇᴀʀᴛꜱ)",
                                "ʙᴏᴛ ʙᴀꜱᴇ ʜᴇᴀʟᴛʜ. 20 = 10 ʜᴇᴀʀᴛꜱ.\n" + "ᴀᴘᴘʟɪᴇᴅ ᴀᴛ ꜱᴘᴀᴡɴ ᴀɴᴅ ᴏɴ /ꜰᴘᴘ ʀᴇʟᴏᴀᴅ.",
                                Material.GOLDEN_APPLE,
                                new double[] {5, 10, 15, 20, 40}),
                        SettingEntry.cycleInt(
                                "death.respawn-delay",
                                "ʀᴇꜱᴘᴀᴡɴ ᴅᴇʟᴀʏ (ᴛɪᴄᴋꜱ)",
                                "ᴛɪᴄᴋꜱ ʙᴇꜰᴏʀᴇ ᴀ ᴅᴇᴀᴅ ʙᴏᴛ ʀᴇᴛᴜʀɴꜱ.\n1 = ɪɴꜱᴛᴀɴᴛ  ·  20 = 1 ꜱᴇᴄᴏɴᴅ.",
                                Material.CLOCK,
                                new int[] {1, 5, 10, 15, 20, 40, 60, 100})));
    }

    private Category auth() {
        return new Category(
                "🔐 ᴀᴜᴛʜ",
                Material.TRIPWIRE_HOOK,
                Material.STRING,
                Material.PURPLE_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                "auth.enabled",
                                "ᴇɴᴀʙʟᴇ ᴀᴜᴛʜ",
                                "ʙᴏᴛꜱ ᴀᴜᴛᴏ-ʀᴇɢɪꜱᴛᴇʀ/ʟᴏɢɪɴ ᴀɢᴀɪɴꜱᴛ ᴀɴ\nɪɴꜱᴛᴀʟʟᴇᴅ ʟᴏɢɪɴ ᴘʟᴜɢɪɴ. ɴᴇᴇᴅꜱ\nᴅᴀᴛᴀʙᴀꜱᴇ.ᴇɴᴀʙʟᴇᴅ ᴛᴏ ʀᴇᴍᴇᴍʙᴇʀ ᴘᴀꜱꜱᴡᴏʀᴅꜱ.",
                                Material.TRIPWIRE_HOOK),
                        SettingEntry.text(
                                "auth.register-command",
                                "ʀᴇɢɪꜱᴛᴇʀ ᴄᴏᴍᴍᴀɴᴅ",
                                "ꜱᴇɴᴛ ᴀꜱ ᴛʜᴇ ʙᴏᴛ ᴏɴ ɪᴛꜱ ꜰɪʀꜱᴛ ᴊᴏɪɴ.\n%password% ɪꜱ ʀᴇᴘʟᴀᴄᴇᴅ ᴡɪᴛʜ ɪᴛꜱ\nɢᴇɴᴇʀᴀᴛᴇᴅ ᴘᴀꜱꜱᴡᴏʀᴅ. ɴᴏ ʟᴇᴀᴅɪɴɢ ꜱʟᴀꜱʜ.",
                                Material.WRITABLE_BOOK),
                        SettingEntry.text(
                                "auth.login-command",
                                "ʟᴏɢɪɴ ᴄᴏᴍᴍᴀɴᴅ",
                                "ꜱᴇɴᴛ ᴀꜱ ᴛʜᴇ ʙᴏᴛ ᴏɴ ᴇᴠᴇʀʏ ʟᴀᴛᴇʀ ᴊᴏɪɴ,\nᴡɪᴛʜ ɪᴛꜱ ʀᴇᴍᴇᴍʙᴇʀᴇᴅ ᴘᴀꜱꜱᴡᴏʀᴅ.\nɴᴏ ʟᴇᴀᴅɪɴɢ ꜱʟᴀꜱʜ.",
                                Material.WRITTEN_BOOK),
                        SettingEntry.cycleInt(
                                "auth.delay-min-ticks",
                                "ᴍɪɴ ᴅᴇʟᴀʏ (ᴛɪᴄᴋꜱ)",
                                "ꜱʜᴏʀᴛᴇꜱᴛ ʀᴀɴᴅᴏᴍ ᴅᴇʟᴀʏ ʙᴇꜰᴏʀᴇ ᴛʜᴇ\nʀᴇɢɪꜱᴛᴇʀ/ʟᴏɢɪɴ ᴄᴏᴍᴍᴀɴᴅ ꜰɪʀᴇꜱ.\n20 = 1 ꜱᴇᴄᴏɴᴅ.",
                                Material.CLOCK,
                                new int[] {0, 10, 20, 40, 60}),
                        SettingEntry.cycleInt(
                                "auth.delay-max-ticks",
                                "ᴍᴀx ᴅᴇʟᴀʏ (ᴛɪᴄᴋꜱ)",
                                "ʟᴏɴɢᴇꜱᴛ ʀᴀɴᴅᴏᴍ ᴅᴇʟᴀʏ ʙᴇꜰᴏʀᴇ ᴛʜᴇ\nʀᴇɢɪꜱᴛᴇʀ/ʟᴏɢɪɴ ᴄᴏᴍᴍᴀɴᴅ ꜰɪʀᴇꜱ.\n60 = 3 ꜱᴇᴄᴏɴᴅꜱ.",
                                Material.CLOCK,
                                new int[] {20, 40, 60, 100, 200}),
                        SettingEntry.cycleInt(
                                "auth.pending-timeout-ticks",
                                "ᴘᴇɴᴅɪɴɢ ᴛɪᴍᴇᴏᴜᴛ (ᴛɪᴄᴋꜱ)",
                                "ʜᴀʀᴅ ᴄᴀᴘ ᴏɴ ʜᴏᴡ ʟᴏɴɢ ᴀ ʙᴏᴛ ꜱᴛᴀʏꜱ\nꜰʀᴏᴢᴇɴ ᴡᴀɪᴛɪɴɢ ꜰᴏʀ ɪᴛꜱ ᴏᴜᴛᴄᴏᴍᴇ.\n100 = 5 ꜱᴇᴄᴏɴᴅꜱ.",
                                Material.REPEATER,
                                new int[] {40, 60, 100, 200, 400}),
                        SettingEntry.cycleInt(
                                "auth.password.length",
                                "ᴘᴀꜱꜱᴡᴏʀᴅ ʟᴇɴɢᴛʜ",
                                "ɢᴇɴᴇʀᴀᴛᴇᴅ ᴘᴀꜱꜱᴡᴏʀᴅ ᴄʜᴀʀᴀᴄᴛᴇʀ ᴄᴏᴜɴᴛ.\nꜱᴛᴀʏ ᴜɴᴅᴇʀ ʏᴏᴜʀ ʟᴏɢɪɴ ᴘʟᴜɢɪɴ'ꜱ\nᴍᴀx-ʟᴇɴɢᴛʜ ᴘᴏʟɪᴄʏ ɪꜰ ɪᴛ ʜᴀꜱ ᴏɴᴇ.",
                                Material.NAME_TAG,
                                new int[] {8, 10, 12, 16, 24}),
                        SettingEntry.toggle(
                                "auth.password.uppercase",
                                "ᴘᴀꜱꜱᴡᴏʀᴅ: ᴜᴘᴘᴇʀᴄᴀꜱᴇ",
                                "ɪɴᴄʟᴜᴅᴇ ᴜᴘᴘᴇʀᴄᴀꜱᴇ ʟᴇᴛᴛᴇʀꜱ.",
                                Material.PAPER),
                        SettingEntry.toggle(
                                "auth.password.lowercase",
                                "ᴘᴀꜱꜱᴡᴏʀᴅ: ʟᴏᴡᴇʀᴄᴀꜱᴇ",
                                "ɪɴᴄʟᴜᴅᴇ ʟᴏᴡᴇʀᴄᴀꜱᴇ ʟᴇᴛᴛᴇʀꜱ.",
                                Material.PAPER),
                        SettingEntry.toggle(
                                "auth.password.digits", "ᴘᴀꜱꜱᴡᴏʀᴅ: ᴅɪɢɪᴛꜱ", "ɪɴᴄʟᴜᴅᴇ ɴᴜᴍʙᴇʀꜱ.", Material.PAPER),
                        SettingEntry.toggle(
                                "auth.password.symbols",
                                "ᴘᴀꜱꜱᴡᴏʀᴅ: ꜱʏᴍʙᴏʟꜱ",
                                "ɪɴᴄʟᴜᴅᴇ ꜱʏᴍʙᴏʟꜱ (!@#%^&*-_=+).",
                                Material.PAPER)));
    }

    private static String sc(String text) {
        return me.bill.fakePlayerPlugin.util.TextUtil.toSmallCaps(text);
    }

    /** Chunk loading + the bot's "client options menu" - everything a bot reports to the server about itself. */
    private Category chunks() {
        return new Category(
                "🗺 " + sc("chunks"),
                Material.GRASS_BLOCK,
                Material.MAP,
                Material.LIME_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                "chunk-loading.enabled",
                                sc("chunk loading"),
                                sc(
                                        "bots behave like afk players: the server\nloads and ticks chunks around them.\noff = bots report the minimum distance."),
                                Material.GRASS_BLOCK),
                        SettingEntry.text(
                                "chunk-loading.view-distance",
                                sc("render distance"),
                                sc(
                                        "chunks each bot reports, like a client's\nvideo setting. auto = server view\ndistance, 0 = minimum, max 32."),
                                Material.SPYGLASS),
                        SettingEntry.cycleInt(
                                "chunk-loading.update-interval",
                                sc("update interval (ticks)"),
                                sc("how often each bot's options are\nre-synced with the server."),
                                Material.CLOCK,
                                new int[] {10, 20, 40, 100, 200}),
                        SettingEntry.cycleInt(
                                "chunk-loading.mass-disable-threshold",
                                sc("mass threshold (bots)"),
                                sc("at this many active bots every bot drops\nto the mass render distance. 0 = never."),
                                Material.HOPPER,
                                new int[] {0, 25, 50, 100, 200, 500}),
                        SettingEntry.cycleInt(
                                "chunk-loading.mass-view-distance",
                                sc("mass render distance"),
                                sc("render distance used while the mass\nthreshold is reached."),
                                Material.COMPASS,
                                new int[] {2, 3, 4, 6, 8}),
                        SettingEntry.text(
                                "bot-client.language",
                                sc("language"),
                                sc("locale each bot reports (en_us, de_de…)."),
                                Material.WRITABLE_BOOK),
                        SettingEntry.text(
                                "bot-client.main-hand",
                                sc("main hand"),
                                sc("right or left. the bot's model\nreports this to every viewer."),
                                Material.IRON_SWORD),
                        SettingEntry.text(
                                "bot-client.skin-parts",
                                sc("skin layers"),
                                sc(
                                        "all, none, or a 0-127 bitmask\n(cape 1, jacket 2, sleeves 4/8,\npants 16/32, hat 64)."),
                                Material.LEATHER_CHESTPLATE),
                        SettingEntry.text(
                                "bot-client.particles",
                                sc("particles"),
                                sc("all, decreased or minimal."),
                                Material.FIREWORK_STAR),
                        SettingEntry.text(
                                "bot-client.chat-visibility",
                                sc("chat visibility"),
                                sc("full, system or hidden."),
                                Material.PAPER),
                        SettingEntry.toggle(
                                "bot-client.allow-server-listings",
                                sc("server listings"),
                                sc("whether bots may appear in the\nserver list's player sample."),
                                Material.NAME_TAG)));
    }

    private Category perf() {
        return new Category(
                "📈 ᴘᴇʀꜰ",
                Material.SPYGLASS,
                Material.CLOCK,
                Material.PURPLE_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                "performance.enabled",
                                "ᴇɴᴀʙʟᴇ ᴍᴏɴɪᴛᴏʀ",
                                "ᴡᴀᴛᴄʜᴇꜱ ꜱᴇʀᴠᴇʀ ʜᴇᴀʟᴛʜ (ᴛᴘꜱ, ᴀɴᴅ\nᴍꜱᴘᴛ/ᴄᴘᴜ/ɢᴄ ɪꜰ ꜱᴘᴀʀᴋ ɪꜱ ɪɴꜱᴛᴀʟʟᴇᴅ)\nᴀɴᴅ ᴡᴀʀɴꜱ ɪɴ ᴄᴏɴꜱᴏʟᴇ ᴏɴ ᴛʀᴏᴜʙʟᴇ.",
                                Material.SPYGLASS),
                        SettingEntry.toggle(
                                "performance.spark-enabled",
                                "ꜱᴘᴀʀᴋ ɪɴᴛᴇɢʀᴀᴛɪᴏɴ",
                                "ᴜꜱᴇꜱ ᴀɴ ɪɴꜱᴛᴀʟʟᴇᴅ ꜱᴘᴀʀᴋ ꜰᴏʀ ᴍꜱᴘᴛ/ᴄᴘᴜ/ɢᴄ\nᴅᴀᴛᴀ ᴀɴᴅ ᴇɴᴀʙʟᴇꜱ /ꜰᴘᴘ ᴘᴇʀꜰ ꜱᴘᴀʀᴋ'ꜱ\nᴄᴘᴜ ᴘʀᴏꜰɪʟᴇʀ.",
                                Material.OBSERVER),
                        SettingEntry.toggle(
                                "performance.placeholders",
                                "ᴘʟᴀᴄᴇʜᴏʟᴅᴇʀꜱ",
                                "ᴇxᴘᴏꜱᴇꜱ ᴘᴇʀꜰᴏʀᴍᴀɴᴄᴇ ᴅᴀᴛᴀ ᴛʜʀᴏᴜɢʜ\nᴘʟᴀᴄᴇʜᴏʟᴅᴇʀᴀᴘɪ (%fpp_tps%, ᴇᴛᴄ.).",
                                Material.PAPER),
                        SettingEntry.cycleInt(
                                "performance.sample-interval-ticks",
                                "ꜱᴀᴍᴘʟᴇ ɪɴᴛᴇʀᴠᴀʟ (ᴛɪᴄᴋꜱ)",
                                "ʜᴏᴡ ᴏꜰᴛᴇɴ ᴀ ꜱɴᴀᴘꜱʜᴏᴛ ɪꜱ ᴛᴀᴋᴇɴ.\n20 = 1 ꜱᴇᴄᴏɴᴅ.",
                                Material.CLOCK,
                                new int[] {20, 40, 60, 100, 200}),
                        SettingEntry.cycleInt(
                                "performance.history-minutes",
                                "ʜɪꜱᴛᴏʀʏ (ᴍɪɴᴜᴛᴇꜱ)",
                                "ʜᴏᴡ ʟᴏɴɢ ꜱᴀᴍᴘʟᴇꜱ ᴀʀᴇ ᴋᴇᴘᴛ ꜰᴏʀ\n/ꜰᴘᴘ ᴘᴇʀꜰ ʜɪꜱᴛᴏʀʏ.",
                                Material.BOOK,
                                new int[] {5, 10, 15, 30, 60}),
                        SettingEntry.cycleDouble(
                                "performance.warn-mspt",
                                "ᴡᴀʀɴ ᴍꜱᴘᴛ",
                                "ᴄᴏɴꜱᴏʟᴇ ᴡᴀʀɴɪɴɢ ᴛʜʀᴇꜱʜᴏʟᴅ ꜰᴏʀ\nᴍꜱ-ᴘᴇʀ-ᴛɪᴄᴋ (ɴᴇᴇᴅꜱ ꜱᴘᴀʀᴋ).",
                                Material.REDSTONE_TORCH,
                                new double[] {40.0, 50.0, 60.0, 80.0, 100.0}),
                        SettingEntry.cycleDouble(
                                "performance.warn-tps",
                                "ᴡᴀʀɴ ᴛᴘꜱ",
                                "ᴄᴏɴꜱᴏʟᴇ ᴡᴀʀɴɪɴɢ ᴛʜʀᴇꜱʜᴏʟᴅ ꜰᴏʀ\nᴛɪᴄᴋꜱ-ᴘᴇʀ-ꜱᴇᴄᴏɴᴅ ᴅʀᴏᴘᴘɪɴɢ ʙᴇʟᴏᴡ.",
                                Material.REDSTONE_TORCH,
                                new double[] {15.0, 16.0, 18.0, 19.0}),
                        SettingEntry.cycleInt(
                                "performance.warn-consecutive-samples",
                                "ᴡᴀʀɴ ꜱᴀᴍᴘʟᴇꜱ",
                                "ᴄᴏɴꜱᴇᴄᴜᴛɪᴠᴇ ʙᴀᴅ ꜱᴀᴍᴘʟᴇꜱ ʙᴇꜰᴏʀᴇ\nᴀ ᴡᴀʀɴɪɴɢ ꜰɪʀᴇꜱ. ᴀᴠᴏɪᴅꜱ ᴏɴᴇ-ᴏꜰꜰ ʟᴀɢ ꜱᴘɪᴋᴇꜱ.",
                                Material.TARGET,
                                new int[] {1, 2, 3, 5, 10}),
                        SettingEntry.cycleInt(
                                "performance.warn-cooldown-minutes",
                                "ᴡᴀʀɴ ᴄᴏᴏʟᴅᴏᴡɴ (ᴍɪɴ)",
                                "ᴍɪɴɪᴍᴜᴍ ᴛɪᴍᴇ ʙᴇᴛᴡᴇᴇɴ ʀᴇᴘᴇᴀᴛ\nᴄᴏɴꜱᴏʟᴇ ᴡᴀʀɴɪɴɢꜱ.",
                                Material.CLOCK,
                                new int[] {1, 3, 5, 10, 30}),
                        SettingEntry.cycleInt(
                                "performance.auto-profiler-timeout-seconds",
                                "ᴀᴜᴛᴏ-ᴘʀᴏꜰɪʟᴇʀ ᴛɪᴍᴇᴏᴜᴛ (ꜱ)",
                                "ᴍᴀx ʀᴜɴᴛɪᴍᴇ ꜰᴏʀ ᴀ ꜱᴘᴀʀᴋ ᴘʀᴏꜰɪʟᴇ ꜱᴇꜱꜱɪᴏɴ\nᴀᴜᴛᴏ-ꜱᴛᴀʀᴛᴇᴅ ᴏɴ ᴀ ᴡᴀʀɴɪɴɢ.",
                                Material.MAGMA_CREAM,
                                new int[] {30, 60, 90, 120}),
                        SettingEntry.toggle(
                                "performance.self-profiler.enabled",
                                "ꜱᴇʟꜰ-ᴘʀᴏꜰɪʟᴇʀ",
                                "ᴛɪᴍᴇꜱ ꜱᴇᴄᴛɪᴏɴꜱ ᴏꜰ ᴛʜᴇ ᴘʟᴜɢɪɴ'ꜱ ᴏᴡɴ\nᴄᴏᴅᴇ ꜰᴏʀ /ꜰᴘᴘ ᴘᴇʀꜰ ʀᴇᴘᴏʀᴛ. ꜱᴍᴀʟʟ\nᴏᴠᴇʀʜᴇᴀᴅ ᴡʜɪʟᴇ ᴏɴ.",
                                Material.COMPARATOR),
                        SettingEntry.toggle(
                                "performance.self-profiler.method-level",
                                "ꜱᴇʟꜰ-ᴘʀᴏꜰɪʟᴇʀ: ᴍᴇᴛʜᴏᴅ-ʟᴇᴠᴇʟ",
                                "ꜰɪɴᴇʀ-ɢʀᴀɪɴᴇᴅ ᴍᴇᴛʜᴏᴅ ᴛɪᴍɪɴɢꜱ.\nʜɪɢʜᴇʀ ᴏᴠᴇʀʜᴇᴀᴅ - ᴜꜱᴇ ꜰᴏʀ ᴅᴇᴇᴘ\nᴅɪᴀɢɴᴏꜱɪꜱ ᴏɴʟʏ.",
                                Material.REDSTONE),
                        SettingEntry.toggle(
                                "performance.self-profiler.export-on-warning",
                                "ᴇxᴘᴏʀᴛ ᴏɴ ᴡᴀʀɴɪɴɢ",
                                "ᴀᴜᴛᴏ-ꜱᴀᴠᴇꜱ ᴀ ꜰᴜʟʟ ᴘᴇʀꜰᴏʀᴍᴀɴᴄᴇ ʀᴇᴘᴏʀᴛ\nᴡʜᴇɴ ᴀ ᴄᴏɴꜱᴏʟᴇ ᴡᴀʀɴɪɴɢ ꜰɪʀᴇꜱ.",
                                Material.CHEST)));
    }

    private static final String PATHFINDING_DEBUG_ALL_KEY = "pathfinding_debug_all";

    private Category debug() {
        return new Category(
                "🐛 ᴅᴇʙᴜɢ",
                Material.REDSTONE_TORCH,
                Material.LEVER,
                Material.RED_STAINED_GLASS_PANE,
                List.of(
                        SettingEntry.toggle(
                                PATHFINDING_DEBUG_ALL_KEY,
                                "ꜱʜᴏᴡ ᴀʟʟ ᴘᴀᴛʜꜱ",
                                "ᴛᴏɢɢʟᴇꜱ ᴛʜᴇ ᴘᴀᴛʜꜰɪɴᴅɪɴɢ ᴘᴀʀᴛɪᴄʟᴇ ᴛʀᴀɪʟ (ꜱᴀᴍᴇ ᴏɴᴇ ᴀꜱ ᴀ\n"
                                        + "ʙᴏᴛ'ꜱ ᴏᴡɴ ꜱᴇᴛᴛɪɴɢꜱ) ꜰᴏʀ ᴇᴠᴇʀʏ ᴄᴜʀʀᴇɴᴛʟʏ ᴀᴄᴛɪᴠᴇ ʙᴏᴛ ᴀᴛ\n"
                                        + "ᴏɴᴄᴇ, ᴠɪꜱɪʙʟᴇ ᴏɴʟʏ ᴛᴏ ʏᴏᴜ. ᴅᴏᴇꜱɴ'ᴛ ᴀᴜᴛᴏ-ᴀᴘᴘʟʏ ᴛᴏ ʙᴏᴛꜱ\n"
                                        + "ꜱᴘᴀᴡɴᴇᴅ ᴀꜰᴛᴇʀ ʏᴏᴜ ᴛᴏɢɢʟᴇ ᴛʜɪꜱ - ʀᴇ-ᴄʟɪᴄᴋ ᴛᴏ ᴘɪᴄᴋ ᴛʜᴇᴍ ᴜᴘ.",
                                Material.MAP),
                        // ── Core switches ────────────────────────────────────
                        SettingEntry.debugToggle(
                                "enabled", "ᴍᴀꜱᴛᴇʀ ᴅᴇʙᴜɢ", "ᴇɴᴀʙʟᴇꜱ ᴀʟʟ ᴅᴇʙᴜɢ ᴏᴜᴛᴘᴜᴛ.", Material.BEACON),
                        SettingEntry.debugToggle(
                                "debug-chat",
                                "ᴅᴇʙᴜɢ ᴄʜᴀᴛ",
                                "ʙʀᴏᴀᴅᴄᴀꜱᴛ ᴅᴇʙᴜɢ ᴏᴜᴛᴘᴜᴛ ᴛᴏ ᴏᴘ/ɴᴏᴛɪꜰʏ ᴘʟᴀʏᴇʀꜱ.",
                                Material.BOOK),
                        SettingEntry.debugToggle("general", "ɢᴇɴᴇʀᴀʟ", "ʙᴀꜱɪᴄ ᴅᴇʙᴜɢ ɪɴꜰᴏʀᴍᴀᴛɪᴏɴ.", Material.PAPER),
                        SettingEntry.debugToggle(
                                "startup", "ꜱᴛᴀʀᴛᴜᴘ", "ᴅᴇᴛᴀɪʟᴇᴅ ʟᴏɢɢɪɴɢ ᴅᴜʀɪɴɢ ɪɴɪᴛɪᴀʟɪᴢᴀᴛɪᴏɴ.", Material.CAMPFIRE),
                        // ── Bot systems ──────────────────────────────────────
                        SettingEntry.debugToggle(
                                "pathfinding",
                                "ᴘᴀᴛʜꜰɪɴᴅɪɴɢ",
                                "ᴅᴇᴛᴀɪʟᴇᴅ ɴᴀᴠɪɢᴀᴛɪᴏɴ ᴅɪᴀɢɴᴏꜱᴛɪᴄꜱ (ꜱᴛᴜᴄᴋ/ʀᴇᴄᴀʟᴄ,\n"
                                        + "ɢɪᴠᴇ-ᴜᴘꜱ, ᴡᴀᴛᴄʜᴅᴏɢ, ᴍɪɴᴇ ꜱᴛᴀʟʟꜱ) ʟᴏɢɢᴇᴅ ᴛᴏ ᴄᴏɴꜱᴏʟᴇ.",
                                Material.TARGET),
                        SettingEntry.debugToggle(
                                "skin-pool",
                                "ꜱᴋɪɴ ᴘᴏᴏʟ",
                                "ꜰᴜʟʟ ꜱᴋɪɴ ᴘɪᴘᴇʟɪɴᴇ ᴛʀᴀᴄᴇ: ᴘᴏᴏʟ ʟᴏᴀᴅɪɴɢ, ʀᴀʀɪᴛʏ ʀᴏʟʟꜱ,\n"
                                        + "ᴄᴀᴄʜᴇ ʜɪᴛꜱ, ᴅᴏᴡɴʟᴏᴀᴅꜱ, ᴍᴏᴅᴇʟ ᴅᴇᴛᴇᴄᴛɪᴏɴ, ᴍɪɴᴇꜱᴋɪɴ\n"
                                        + "ꜱɪɢɴɪɴɢ, ᴀɴᴅ ꜱᴋɪɴ ᴀᴘᴘʟʏ.",
                                Material.ARMOR_STAND),
                        SettingEntry.debugToggle(
                                "commands", "ᴄᴏᴍᴍᴀɴᴅꜱ", "ᴄᴏᴍᴍᴀɴᴅ ᴇxᴇᴄᴜᴛɪᴏɴ ᴅᴇʙᴜɢɢɪɴɢ.", Material.COMMAND_BLOCK),
                        SettingEntry.debugToggle(
                                "head-ai", "ʜᴇᴀᴅ ᴀɪ", "ʙᴏᴛ ʜᴇᴀᴅ ʀᴏᴛᴀᴛɪᴏɴ ᴀɴᴅ ᴀɪ ᴛᴀʀɢᴇᴛɪɴɢ.", Material.ENDER_EYE),
                        SettingEntry.debugToggle("chat", "ᴄʜᴀᴛ", "ʙᴏᴛ ᴄʜᴀᴛ ꜱʏꜱᴛᴇᴍ ᴅᴇʙᴜɢɢɪɴɢ.", Material.OAK_SIGN),
                        SettingEntry.debugToggle(
                                "right-click",
                                "ʀɪɢʜᴛ-ᴄʟɪᴄᴋ",
                                "ᴘʟᴀʏᴇʀ-ʙᴏᴛ ɪɴᴛᴇʀᴀᴄᴛɪᴏɴ (ʀɪɢʜᴛ-ᴄʟɪᴄᴋ).",
                                Material.OAK_DOOR),
                        SettingEntry.debugToggle(
                                "right-click-head",
                                "ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ʜᴇᴀᴅ",
                                "ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ᴏɴ ʙᴏᴛ ʜᴇᴀᴅ.",
                                Material.CARVED_PUMPKIN),
                        SettingEntry.debugToggle(
                                "left-click", "ʟᴇꜰᴛ-ᴄʟɪᴄᴋ", "ʙᴏᴛ ʟᴇꜰᴛ-ᴄʟɪᴄᴋ (ʙʀᴇᴀᴋ / ᴀᴛᴛᴀᴄᴋ).", Material.IRON_PICKAXE),
                        SettingEntry.debugToggle(
                                "left-click-head",
                                "ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ʜᴇᴀᴅ",
                                "ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ᴀɪᴍ / ᴛᴀʀɢᴇᴛɪɴɢ.",
                                Material.SKELETON_SKULL),
                        // ── NMS internals ────────────────────────────────────
                        SettingEntry.debugToggle(
                                "nms.enabled", "ɴᴍꜱ ᴍᴀꜱᴛᴇʀ", "ʟᴏᴡ-ʟᴇᴠᴇʟ ɴᴍꜱ ɪɴᴛᴇʀᴀᴄᴛɪᴏɴꜱ.", Material.COMPASS),
                        SettingEntry.debugToggle("nms.bot", "ɴᴍꜱ ʙᴏᴛ", "ʙᴏᴛ ʟɪꜰᴇᴄʏᴄʟᴇ ᴇᴠᴇɴᴛꜱ.", Material.PLAYER_HEAD),
                        SettingEntry.debugToggle(
                                "nms.connection",
                                "ɴᴍꜱ ᴄᴏɴɴᴇᴄᴛɪᴏɴ",
                                "ɴᴍꜱ ᴄᴏɴɴᴇᴄᴛɪᴏɴ/ᴘᴀᴄᴋᴇᴛ ʟɪꜱᴛᴇɴᴇʀ ᴇᴠᴇɴᴛꜱ.",
                                Material.REDSTONE),
                        SettingEntry.debugToggle(
                                "nms.damage",
                                "ɴᴍꜱ ᴅᴀᴍᴀɢᴇ",
                                "ʙᴏᴛ ᴅᴀᴍᴀɢᴇ ᴅᴇᴄɪꜱɪᴏɴꜱ ᴀɴᴅ ᴄᴀɴᴄᴇʟʟᴀᴛɪᴏɴꜱ.",
                                Material.IRON_SWORD),
                        SettingEntry.debugToggle(
                                "nms.physics", "ɴᴍꜱ ᴘʜʏꜱɪᴄꜱ", "ᴘʜʏꜱɪᴄꜱ ᴀɴᴅ ᴍᴏᴠᴇᴍᴇɴᴛ ᴛɪᴄᴋ ᴏᴘᴇʀᴀᴛɪᴏɴꜱ.", Material.ANVIL),
                        SettingEntry.debugToggle(
                                "nms.skin", "ɴᴍꜱ ꜱᴋɪɴ", "ꜱᴋɪɴ ᴀᴘᴘʟɪᴄᴀᴛɪᴏɴ ᴀɴᴅ ʀᴇꜱᴏʟᴜᴛɪᴏɴ.", Material.LEATHER),
                        // ── Storage & network ────────────────────────────────
                        SettingEntry.debugToggle(
                                "database.enabled", "ᴅᴀᴛᴀʙᴀꜱᴇ ᴍᴀꜱᴛᴇʀ", "ᴀʟʟ ᴅᴀᴛᴀʙᴀꜱᴇ ᴅᴇʙᴜɢɢɪɴɢ.", Material.CHEST),
                        SettingEntry.debugToggle(
                                "database.connection", "ᴅʙ ᴄᴏɴɴᴇᴄᴛɪᴏɴ", "ᴄᴏɴɴᴇᴄᴛɪᴏɴ ʟɪꜰᴇᴄʏᴄʟᴇ.", Material.HOPPER),
                        SettingEntry.debugToggle(
                                "database.operations", "ᴅʙ ᴏᴘᴇʀᴀᴛɪᴏɴꜱ", "ǫᴜᴇʀʏ ᴏᴘᴇʀᴀᴛɪᴏɴꜱ.", Material.WRITABLE_BOOK),
                        SettingEntry.debugToggle(
                                "database.migration",
                                "ᴅʙ ᴍɪɢʀᴀᴛɪᴏɴ",
                                "ᴍɪɢʀᴀᴛɪᴏɴ ᴀɴᴅ ꜱᴄʜᴇᴍᴀ ᴏᴘᴇʀᴀᴛɪᴏɴꜱ.",
                                Material.FURNACE),
                        SettingEntry.debugToggle(
                                "database.persistence",
                                "ᴅʙ ᴘᴇʀꜱɪꜱᴛᴇɴᴄᴇ",
                                "ᴘᴇʀꜱɪꜱᴛᴇɴᴄᴇ ꜱᴀᴠᴇ/ʟᴏᴀᴅ.",
                                Material.ENDER_CHEST),
                        SettingEntry.debugToggle("packets", "ᴘᴀᴄᴋᴇᴛꜱ", "ᴘᴀᴄᴋᴇᴛ ɪɴᴊᴇᴄᴛɪᴏɴ ᴅᴇʙᴜɢɢɪɴɢ.", Material.MAP),
                        SettingEntry.debugToggle(
                                "network", "ɴᴇᴛᴡᴏʀᴋ", "ᴍᴜʟᴛɪ-ꜱᴇʀᴠᴇʀ ɴᴇᴛᴡᴏʀᴋ ᴏᴘᴇʀᴀᴛɪᴏɴꜱ.", Material.SPYGLASS),
                        SettingEntry.debugToggle(
                                "config-sync",
                                "ᴄᴏɴꜰɪɢ ꜱʏɴᴄ",
                                "ᴄᴏɴꜰɪɢ ꜱʏɴᴄʜʀᴏɴɪᴢᴀᴛɪᴏɴ ᴀᴄʀᴏꜱꜱ ɴᴇᴛᴡᴏʀᴋ.",
                                Material.REPEATER)));
    }

    private static final class GuiHolder implements InventoryHolder {
        final UUID uuid;

        GuiHolder(UUID uuid) {
            this.uuid = uuid;
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record Category(
            String label,
            Material activeMat,
            Material inactiveMat,
            Material separatorGlass,
            List<SettingEntry> settings) {}

    private enum SettingType {
        TOGGLE,
        CYCLE_INT,
        CYCLE_DOUBLE,
        STRING,
        ACTION,
        COMING_SOON,
        DEBUG_TOGGLE
    }

    private record ChatInputSession(SettingEntry entry, int[] guiState, int cleanupTaskId) {}

    private static final class SettingEntry {
        final String configKey;
        final String label;
        final String description;
        final Material icon;
        final SettingType type;
        final int[] intValues;
        final double[] dblValues;
        final String valueOverride;
        final Runnable clickAction;

        private SettingEntry(
                String configKey,
                String label,
                String description,
                Material icon,
                SettingType type,
                int[] intValues,
                double[] dblValues,
                String valueOverride,
                Runnable clickAction) {
            this.configKey = configKey;
            this.label = label;
            this.description = description;
            this.icon = icon;
            this.type = type;
            this.intValues = intValues;
            this.dblValues = dblValues;
            this.valueOverride = valueOverride;
            this.clickAction = clickAction;
        }

        static SettingEntry toggle(String key, String label, String desc, Material icon) {
            return new SettingEntry(key, label, desc, icon, SettingType.TOGGLE, null, null, null, null);
        }

        static SettingEntry cycleInt(String key, String label, String desc, Material icon, int[] values) {
            return new SettingEntry(key, label, desc, icon, SettingType.CYCLE_INT, values, null, null, null);
        }

        static SettingEntry cycleDouble(String key, String label, String desc, Material icon, double[] values) {
            return new SettingEntry(key, label, desc, icon, SettingType.CYCLE_DOUBLE, null, values, null, null);
        }

        static SettingEntry text(String key, String label, String desc, Material icon) {
            return new SettingEntry(key, label, desc, icon, SettingType.STRING, null, null, null, null);
        }

        static SettingEntry comingSoon(String key, String label, String desc, Material icon) {
            return new SettingEntry(key, label, desc, icon, SettingType.COMING_SOON, null, null, null, null);
        }

        static SettingEntry action(String key, String label, String desc, Material icon) {
            return new SettingEntry(key, label, desc, icon, SettingType.ACTION, null, null, null, null);
        }

        static SettingEntry action(
                String key, String label, String desc, Material icon, String valueOverride, Runnable clickAction) {
            return new SettingEntry(key, label, desc, icon, SettingType.ACTION, null, null, valueOverride, clickAction);
        }

        static SettingEntry debugToggle(String key, String label, String desc, Material icon) {
            return new SettingEntry(key, label, desc, icon, SettingType.DEBUG_TOGGLE, null, null, null, null);
        }

        String currentValueString(FakePlayerPlugin plugin) {
            if (valueOverride != null) return valueOverride;
            var cfg = plugin.getConfig();
            return switch (type) {
                case TOGGLE -> cfg.getBoolean(configKey, false) ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
                case DEBUG_TOGGLE -> Config.debugBoolValue(configKey, false) ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
                case CYCLE_INT -> String.valueOf(cfg.getInt(configKey, intValues[0]));
                case STRING -> cfg.getString(configKey, "");
                case ACTION -> "ᴄʟɪᴄᴋ ᴛᴏ ʀᴜɴ";
                case CYCLE_DOUBLE -> {
                    double d = cfg.getDouble(configKey, dblValues[0]);
                    yield (d == Math.floor(d) && !Double.isInfinite(d))
                            ? String.valueOf((int) d)
                            : String.format("%.2f", d);
                }
                case COMING_SOON -> "⚠ ᴄᴏᴍɪɴɢ ꜱᴏᴏɴ";
            };
        }

        void apply(FakePlayerPlugin plugin) {
            if (type == SettingType.TOGGLE) {
                plugin.getConfig().set(configKey, !plugin.getConfig().getBoolean(configKey, false));
            }
        }

        void applyDebugToggle() {
            if (type == SettingType.DEBUG_TOGGLE) {
                Config.setDebugBool(configKey, !Config.debugBoolValue(configKey, false));
            }
        }
    }

    private static List<String> wrapText(String text, int maxLen) {
        return GuiKit.wrapText(text, maxLen);
    }
}
