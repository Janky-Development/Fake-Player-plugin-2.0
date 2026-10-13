package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import com.destroystokyo.paper.profile.PlayerProfile;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.command.CommandManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class HelpGui implements Listener {

    private static final TextColor ACCENT = GuiKit.ACCENT;
    private static final TextColor ON_GREEN = GuiKit.ON_GREEN;
    private static final TextColor ORANGE = GuiKit.COMING_SOON_COLOR;
    private static final TextColor DARK_GRAY = GuiKit.DARK_GRAY;
    private static final TextColor GRAY = GuiKit.GRAY;
    private static final TextColor WHITE = GuiKit.WHITE;
    private static final TextColor YELLOW = GuiKit.YELLOW;

    private static final TextColor ARG_REQUIRED = TextColor.fromHexString("#BAFF4F");
    private static final TextColor ARG_OPTIONAL = TextColor.fromHexString("#9691AB");
    private static final TextColor ARG_FLAG = TextColor.fromHexString("#CABAFF");
    private static final TextColor ARG_CHOICE = TextColor.fromHexString("#BAFF4F");

    private static final TextColor CAT_ALL = TextColor.fromHexString("#EEECF7");
    private static final TextColor CAT_CORE = TextColor.fromHexString("#A78BFA");
    private static final TextColor CAT_BOT = TextColor.fromHexString("#BAFF4F");
    private static final TextColor CAT_ACTION = TextColor.fromHexString("#FF6A5C");
    private static final TextColor CAT_AUTH = TextColor.fromHexString("#CABAFF");
    private static final TextColor CAT_PERF = TextColor.fromHexString("#9691AB");

    private enum Category {
        ALL("ᴀʟʟ", CAT_ALL, Material.COMPASS, Material.COMPASS, "ꜱʜᴏᴡꜱ ᴇᴠᴇʀʏ ᴀᴠᴀɪʟᴀʙʟᴇ ᴄᴏᴍᴍᴀɴᴅ."),
        CORE("ᴄᴏʀᴇ", CAT_CORE, Material.NETHER_STAR, Material.GRAY_DYE, "ꜱᴘᴀᴡɴ, ᴅᴇꜱᴘᴀᴡɴ, ꜱᴀᴠᴇ, ʀᴇʟᴏᴀᴅ & ᴍᴏʀᴇ."),
        BOTS("ʙᴏᴛꜱ", CAT_BOT, Material.PLAYER_HEAD, Material.SKELETON_SKULL, "ᴘᴇʀ-ʙᴏᴛ ᴍᴀɴᴀɢᴇᴍᴇɴᴛ, ɪɴᴠᴇɴᴛᴏʀʏ & ᴏᴡɴᴇʀꜱ."),
        ACTIONS(
                "ᴀᴄᴛɪᴏɴꜱ",
                CAT_ACTION,
                Material.DIAMOND_PICKAXE,
                Material.IRON_PICKAXE,
                "ɴᴀᴠ, ᴍɪɴɪɴɢ, ꜰɪɴᴅ, ᴄᴏᴍʙᴀᴛ, ꜱᴛᴏʀᴀɢᴇ & ᴍᴏʀᴇ."),
        AUTH(
                "ᴀᴜᴛʜ",
                CAT_AUTH,
                Material.TRIPWIRE_HOOK,
                Material.STRING,
                "ʙᴏᴛ ᴀᴜᴛᴏ-ʀᴇɢɪꜱᴛᴇʀ/ʟᴏɢɪɴ ᴀɢᴀɪɴꜱᴛ ᴀɴ ɪɴꜱᴛᴀʟʟᴇᴅ ʟᴏɢɪɴ ᴘʟᴜɢɪɴ."),
        PERF(
                "ᴘᴇʀꜰ",
                CAT_PERF,
                Material.SPYGLASS,
                Material.CLOCK,
                "ᴛᴘꜱ/ᴍꜱᴘᴛ ᴍᴏɴɪᴛᴏʀɪɴɢ, ʙᴇɴᴄʜᴍᴀʀᴋꜱ & ᴛʜᴇ ꜱᴘᴀʀᴋ ᴘʀᴏꜰɪʟᴇʀ.");

        final String label;
        final TextColor color;
        final Material activeMat;
        final Material inactiveMat;
        final String tooltip;

        Category(String label, TextColor color, Material activeMat, Material inactiveMat, String tooltip) {
            this.label = label;
            this.color = color;
            this.activeMat = activeMat;
            this.inactiveMat = inactiveMat;
            this.tooltip = tooltip;
        }
    }

    private static final Category[] CATEGORIES = Category.values();

    private static final int SIZE = 54;
    // One slot shy of a full 45-slot grid, deliberately - the freed slot 44 holds the info head
    // below, since row 6 (45-53) is entirely spoken for by nav + a tab per Category.values() (see
    // CAT_SLOT_START) and has no room left for it.
    private static final int CMDS_PER_PAGE = 44;
    private static final int SLOT_INFO_HEAD = 44;

    private static final int SLOT_PAGE_PREV = 45;
    private static final int CAT_SLOT_START = 46;
    private static final int SLOT_PAGE_NEXT = 52;
    private static final int SLOT_CLOSE = 53;

    private record Session(int page, int catIdx, String alias) {
        Session withPage(int p) {
            return new Session(p, catIdx, alias);
        }

        Session withCat(int c) {
            return new Session(0, c, alias);
        }

        Category category() {
            return CATEGORIES[catIdx];
        }
    }

    private record HelpEntry(
            String name, List<String> aliases, String usage, String description, String permission, Material icon) {}

    private final Plugin plugin;
    private final CommandManager commandManager;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> pendingRebuild = new HashSet<>();

    public HelpGui(Plugin plugin, CommandManager commandManager) {
        this.plugin = plugin;
        this.commandManager = commandManager;
    }

    public void open(Player player, String alias) {
        sessions.put(player.getUniqueId(), new Session(0, 0, alias));
        build(player);
    }

    public void open(Player player) {
        open(player, "fpp");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;

        UUID uuid = player.getUniqueId();
        Session session = sessions.get(uuid);
        if (session == null) return;

        int slot = event.getSlot();
        int totalPages = totalPages(player, session.category());

        if (slot == SLOT_PAGE_PREV) {
            if (session.page() > 0) {
                sessions.put(uuid, session.withPage(session.page() - 1));
                playClick(player, 1.0f);
                build(player);
            }
        } else if (slot == SLOT_PAGE_NEXT) {
            if (session.page() < totalPages - 1) {
                sessions.put(uuid, session.withPage(session.page() + 1));
                playClick(player, 1.0f);
                build(player);
            }
        } else if (slot == SLOT_CLOSE) {
            playClick(player, 0.8f);
            GuiKit.backOrClose(player);
        } else if (slot >= CAT_SLOT_START && slot < CAT_SLOT_START + CATEGORIES.length) {
            int ci = slot - CAT_SLOT_START;
            if (ci != session.catIdx()) {
                playClick(player, 1.3f);
            }
            sessions.put(uuid, session.withCat(ci));
            build(player);
        } else if (slot < CMDS_PER_PAGE) {
            int idx = session.page() * CMDS_PER_PAGE + slot;
            if (idx < filteredCommands(player, session.category()).size()) playClick(player, 1.3f);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (pendingRebuild.contains(uuid)) return;
        sessions.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void build(Player player) {
        UUID uuid = player.getUniqueId();
        Session raw = sessions.get(uuid);
        if (raw == null) return;

        List<HelpEntry> visible = filteredCommands(player, raw.category());
        int totalPages = Math.max(1, (int) Math.ceil(visible.size() / (double) CMDS_PER_PAGE));
        int page = Math.min(raw.page(), totalPages - 1);
        Session session = raw.withPage(page);
        sessions.put(uuid, session);

        Holder holder = new Holder(uuid);

        String catLabel = session.category() == Category.ALL ? "ᴄᴏᴍᴍᴀɴᴅꜱ" : session.category().label + " ᴄᴏᴍᴍᴀɴᴅꜱ";
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(catLabel).color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, SIZE, title);

        int start = page * CMDS_PER_PAGE;
        int end = Math.min(start + CMDS_PER_PAGE, visible.size());
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, buildCommandItem(visible.get(i), session.alias()));
        }

        ItemStack filler = glassFiller(Material.GRAY_STAINED_GLASS_PANE);
        for (int s = SLOT_INFO_HEAD; s <= 53; s++) inv.setItem(s, filler);

        inv.setItem(SLOT_INFO_HEAD, buildInfoHead(plugin));

        inv.setItem(SLOT_PAGE_PREV, page > 0 ? buildNavArrow(false, page) : filler);

        for (int i = 0; i < CATEGORIES.length; i++) {
            inv.setItem(CAT_SLOT_START + i, buildCategoryTab(CATEGORIES[i], i == session.catIdx()));
        }

        inv.setItem(SLOT_PAGE_NEXT, page < totalPages - 1 ? buildNavArrow(true, page + 2) : filler);

        inv.setItem(SLOT_CLOSE, GuiKit.backOrClose());

        pendingRebuild.add(uuid);
        player.openInventory(inv);
        pendingRebuild.remove(uuid);

        sessions.put(uuid, session);
    }

    private List<HelpEntry> filteredCommands(Player player, Category cat) {
        return commandManager.getCommands().stream()
                .filter(cmd -> cmd.canUse(player))
                .map(cmd -> new HelpEntry(
                        cmd.getName(),
                        cmd.getAliases(),
                        cmd.getUsage(),
                        cmd.getDescription(),
                        cmd.getPermission(),
                        iconFor(cmd.getName())))
                .filter(cmd -> cat == Category.ALL || categoryFor(cmd.name()) == cat)
                .toList();
    }

    private int totalPages(Player player, Category cat) {
        return Math.max(1, (int) Math.ceil(filteredCommands(player, cat).size() / (double) CMDS_PER_PAGE));
    }

    private static ItemStack buildCategoryTab(Category cat, boolean active) {
        ItemStack item = new ItemStack(active ? cat.activeMat : cat.inactiveMat);
        ItemMeta meta = item.getItemMeta();

        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(cat.label).color(ACCENT).decoration(TextDecoration.BOLD, active)));

        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(active ? "◈  ᴄᴜʀʀᴇɴᴛʟʏ ᴠɪᴇᴡɪɴɢ" : "ᴄʟɪᴄᴋ ᴛᴏ ꜱᴡɪᴛᴄʜ")
                        .color(active ? ON_GREEN : DARK_GRAY))));

        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildCommandItem(HelpEntry cmd, String alias) {
        String name = cmd.name();
        String prefix = "/" + alias + " ";

        ItemStack item = new ItemStack(cmd.icon());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(prefix).color(DARK_GRAY).decoration(TextDecoration.BOLD, true))
                .append(Component.text(name).color(ACCENT).decoration(TextDecoration.BOLD, true)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        Category cat = categoryFor(cmd.name());
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(cat.label).color(cat.color).decoration(TextDecoration.BOLD, true)));
        lore.add(divider());

        String desc = cmd.description();
        if (desc != null && !desc.isBlank()) {
            for (String line : wrapText(desc, 38)) {
                lore.add(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(line).color(GRAY)));
            }
        }

        String usage = cmd.usage();
        if (usage != null && !usage.isBlank()) {
            lore.add(Component.empty());
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ᴜꜱᴀɢᴇ").color(DARK_GRAY).decoration(TextDecoration.BOLD, true)));
            for (String mode : splitModes(usage)) {
                lore.add(buildUsageLine(name, mode, alias));
            }
        }

        List<String> aliases = cmd.aliases();
        if (!aliases.isEmpty()) {
            lore.add(Component.empty());
            Component row = Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ᴀʟɪᴀꜱ  ").color(DARK_GRAY).decoration(TextDecoration.BOLD, true));
            for (int i = 0; i < aliases.size(); i++) {
                row = row.append(Component.text(aliases.get(i)).color(ACCENT));
                if (i < aliases.size() - 1)
                    row = row.append(Component.text(", ").color(DARK_GRAY));
            }
            lore.add(row);
        }

        String perm = cmd.permission();
        if (perm != null && !perm.isBlank()) {
            lore.add(Component.empty());
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ᴘᴇʀᴍ  ").color(DARK_GRAY).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(perm).color(YELLOW)));
        }

        lore.add(Component.empty());
        lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴠɪᴇᴡ ᴅᴇᴛᴀɪʟꜱ"));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component buildUsageLine(String cmdName, String mode, String alias) {
        Component line = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("  ▸ ").color(DARK_GRAY))
                .append(Component.text("/" + alias + " " + cmdName + " ").color(DARK_GRAY));

        for (String t : mode.split(" ")) {
            TextColor col;
            if (t.startsWith("<") && t.endsWith(">")) col = ARG_REQUIRED;
            else if (t.startsWith("[") && t.endsWith("]")) col = ARG_OPTIONAL;
            else if (t.startsWith("--")) col = ARG_FLAG;
            else if (t.contains("|")) col = ARG_CHOICE;
            else col = WHITE;
            line = line.append(Component.text(t + " ").color(col));
        }
        return line;
    }

    private static List<String> splitModes(String usage) {
        List<String> modes = new ArrayList<>();
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : usage.toCharArray()) {
            if (c == '<' || c == '[') {
                depth++;
                sb.append(c);
            } else if (c == '>' || c == ']') {
                depth = Math.max(0, depth - 1);
                sb.append(c);
            } else if (c == '|' && depth == 0) {
                String part = sb.toString().trim();
                if (!part.isEmpty()) modes.add(part);
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        String last = sb.toString().trim();
        if (!last.isEmpty()) modes.add(last);
        return modes.isEmpty() ? List.of(usage) : modes;
    }

    private static Category categoryFor(String name) {
        return switch (name.toLowerCase()) {
            case "spawn", "despawn", "delete", "list", "info", "check", "help", "reload", "settings", "save" -> Category
                    .CORE;
            case "tp", "tph", "freeze", "inventory", "inv", "xp", "setowner", "rename" -> Category.BOTS;
            case "move", "storage", "attack", "find", "sneak", "stop", "left-click", "right-click" -> Category.ACTIONS;
            case "auth" -> Category.AUTH;
            case "perf" -> Category.PERF;
            default -> Category.CORE;
        };
    }

    private static Material iconFor(String name) {
        return switch (name.toLowerCase()) {
            case "spawn" -> Material.PLAYER_HEAD;
            case "despawn", "delete" -> Material.BONE;
            case "list" -> Material.BOOK;
            case "help" -> Material.KNOWLEDGE_BOOK;
            case "info" -> Material.MAP;
            case "chat" -> Material.PAPER;
            case "reload" -> Material.NETHER_STAR;
            case "freeze" -> Material.PACKED_ICE;
            case "tp" -> Material.ENDER_PEARL;
            case "tph" -> Material.ENDER_EYE;
            case "rank" -> Material.GOLDEN_CHESTPLATE;
            case "lpinfo" -> Material.GOLDEN_HELMET;
            case "move" -> Material.COMPASS;
            case "inventory", "inv" -> Material.CHEST;
            case "cmd" -> Material.COMMAND_BLOCK;
            case "mine" -> Material.DIAMOND_PICKAXE;
            case "use" -> Material.WOODEN_AXE;
            case "attack" -> Material.IRON_SWORD;
            case "left-click" -> Material.DIAMOND_PICKAXE;
            case "right-click" -> Material.WOODEN_AXE;
            case "settings" -> Material.COMPARATOR;
            case "sync" -> Material.OBSERVER;
            case "save" -> Material.ENDER_CHEST;
            case "xp" -> Material.EXPERIENCE_BOTTLE;
            case "personality", "persona" -> Material.WRITABLE_BOOK;
            case "storage" -> Material.BARREL;
            case "place" -> Material.OAK_PLANKS;
            case "find" -> Material.COMPASS;
            case "follow" -> Material.LEAD;
            case "sleep" -> Material.RED_BED;
            case "stop" -> Material.BARRIER;
            case "bots", "mybots", "botmenu" -> Material.PLAYER_HEAD;
            case "setowner" -> Material.NAME_TAG;
            case "rename" -> Material.OAK_SIGN;
            case "auth" -> Material.TRIPWIRE_HOOK;
            case "perf" -> Material.SPYGLASS;
            default -> Material.COMMAND_BLOCK;
        };
    }

    private static ItemStack buildNavArrow(boolean isNext, int targetPage) {
        Material mat = isNext ? Material.LIME_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE;
        TextColor col = isNext ? ON_GREEN : ORANGE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(isNext ? "▶" : "◄").color(col).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text((isNext ? "ɴᴇxᴛ ᴘᴀɢᴇ" : "ᴘʀᴇᴠ ᴘᴀɢᴇ") + " (" + targetPage + ")")
                        .color(DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildInfoHead(Plugin rawPlugin) {
        UUID ownerUuid = UUID.fromString("a318f9f4-e2bf-479c-a47a-6a2c1b0b9e66");
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skull = (SkullMeta) item.getItemMeta();

        PlayerProfile profile = Bukkit.createProfile(ownerUuid);
        skull.setPlayerProfile(profile);

        skull.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴀᴋᴇ ᴘʟᴀʏᴇʀ ᴘʟᴜɢɪɴ").color(ACCENT).decoration(TextDecoration.BOLD, true))
                .append(Component.text("]").color(DARK_GRAY)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ᴀᴅᴠᴀɴᴄᴇᴅ ꜰᴀᴋᴇ ᴘʟᴀʏᴇʀ ꜱᴘᴏᴏꜰᴇʀ").color(GRAY)));
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ꜰᴏʀ ᴘᴀᴘᴇʀ/ᴘᴜʀᴘᴜʀ/ꜰᴏʟɪᴀ 1.21+").color(DARK_GRAY)));

        lore.add(Component.empty());
        lore.add(divider());
        lore.add(Component.empty());

        String version = rawPlugin.getPluginMeta().getVersion();
        String author = rawPlugin.getPluginMeta().getAuthors().isEmpty()
                ? "Unknown"
                : String.join(", ", rawPlugin.getPluginMeta().getAuthors());

        if (rawPlugin instanceof FakePlayerPlugin fpp) {
            String latest = fpp.getLatestKnownVersion();
            if (fpp.isRunningBeta() && latest != null) {

                lore.add(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("⚗ ʙᴇᴛᴀ ʙᴜɪʟᴅ  ")
                                .color(TextColor.fromHexString("#A78BFA"))
                                .decoration(TextDecoration.BOLD, true))
                        .append(Component.text("ʟᴀᴛᴇꜱᴛ ꜱᴛᴀʙʟᴇ: ").color(DARK_GRAY))
                        .append(Component.text(latest).color(GRAY)));
            } else if (latest != null && fpp.getUpdateNotification() != null) {

                lore.add(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("⚠ ɴᴇᴡ ᴠᴇʀꜱɪᴏɴ  ")
                                .color(TextColor.fromHexString("#BAFF4F"))
                                .decoration(TextDecoration.BOLD, true))
                        .append(Component.text(latest).color(ON_GREEN).decoration(TextDecoration.BOLD, true)));
            }
        }

        lore.add(infoRow("ᴠᴇʀꜱɪᴏɴ", version));
        lore.add(infoRow("ᴀᴜᴛʜᴏʀ", author));

        if (rawPlugin instanceof FakePlayerPlugin fpp) {
            String mcVer = fpp.getDetectedMcVersion();
            if (mcVer != null && !mcVer.isBlank()) lore.add(infoRow("ᴍɪɴᴇᴄʀᴀꜰᴛ", mcVer));
        }

        lore.add(Component.empty());
        lore.add(divider());
        lore.add(Component.empty());

        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ᴅᴏᴡɴʟᴏᴀᴅ  ").color(GRAY).decoration(TextDecoration.BOLD, true))
                .append(Component.text("Modrinth").color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text("PaperMC").color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text("BuiltByBit").color(ACCENT)));

        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ꜱᴜᴘᴘᴏʀᴛ   ").color(GRAY).decoration(TextDecoration.BOLD, true))
                .append(Component.text("Discord").color(ACCENT)));

        lore.add(Component.empty());
        lore.add(hint("◈ ", "ᴜꜱᴇ /ꜰᴘᴘ ꜰᴏʀ ʟɪɴᴋꜱ & ꜰᴜʟʟ ɪɴꜰᴏ"));

        skull.lore(lore);
        item.setItemMeta(skull);
        return item;
    }

    private static Component infoRow(String key, String value) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(key + "  ").color(DARK_GRAY).decoration(TextDecoration.BOLD, true))
                .append(Component.text(value).color(GRAY));
    }

    private static ItemStack glassFiller(Material mat) {
        return GuiKit.glassFiller(mat);
    }

    private static Component divider() {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("  ─────────────────────").color(TextColor.fromHexString("#2A2735")));
    }

    private static Component hint(String icon, String text) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(icon).color(ACCENT))
                .append(Component.text(text).color(DARK_GRAY));
    }

    private static void playClick(Player player, float pitch) {
        GuiKit.playUiClick(player, pitch);
    }

    private static List<String> wrapText(String text, int maxLen) {
        return GuiKit.wrapText(text, maxLen);
    }

    public static final class Holder implements InventoryHolder {
        public final UUID uuid;

        Holder(UUID uuid) {
            this.uuid = uuid;
        }

        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
