package me.bill.fakePlayerPlugin.command;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.gui.BotSettingGui;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppScheduler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class InventoryCommand implements FppCommand, Listener {

    private static final int GUI_SIZE = 54;
    private static final int[] GUI_TO_BOT = new int[GUI_SIZE];
    private static final Set<Integer> DECO = new HashSet<>();
    private static final Set<Integer> EQUIP_SLOTS = Set.of(45, 46, 47, 48, 50);

    private static final TextColor ACCENT = TextColor.fromHexString("#A78BFA");
    private static final TextColor DARK_GRAY = TextColor.fromHexString("#5F5B73");
    private static final TextColor GRAY = TextColor.fromHexString("#9691AB");
    private static final TextColor OFF_RED = TextColor.fromHexString("#FF6A5C");
    private static final TextColor VAL_YELLOW = TextColor.fromHexString("#BAFF4F");

    private static final Material LABEL_MAT = Material.PURPLE_STAINED_GLASS_PANE;
    private static final Material BLANK_MAT = Material.GRAY_STAINED_GLASS_PANE;

    static {
        Arrays.fill(GUI_TO_BOT, -1);

        for (int i = 0; i < 27; i++) GUI_TO_BOT[i] = i + 9;

        for (int i = 0; i < 9; i++) GUI_TO_BOT[27 + i] = i;

        for (int i = 36; i <= 44; i++) DECO.add(i);

        GUI_TO_BOT[45] = 36;
        GUI_TO_BOT[46] = 37;
        GUI_TO_BOT[47] = 38;
        GUI_TO_BOT[48] = 39;

        DECO.add(49);

        GUI_TO_BOT[50] = 40;

        DECO.add(51);
        DECO.add(52);
        DECO.add(53);
    }

    private final FakePlayerManager manager;
    private final Plugin plugin;
    private final BotSettingGui botSettingGui;
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<Inventory, UUID> invToBot = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> botLocks = new ConcurrentHashMap<>();

    public InventoryCommand(FakePlayerManager manager, Plugin plugin, BotSettingGui botSettingGui) {
        this.manager = manager;
        this.plugin = plugin;
        this.botSettingGui = botSettingGui;
    }

    @Override
    public String getName() {
        return "inventory";
    }

    @Override
    public List<String> getAliases() {
        return List.of("inv");
    }

    @Override
    public String getUsage() {
        return "<bot>";
    }

    @Override
    public String getDescription() {
        return "Open a bot's full inventory";
    }

    @Override
    public String getPermission() {
        return Perm.INVENTORY_CMD;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.hasAny(sender, Perm.INVENTORY_CMD, Perm.INVENTORY_OWN);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("inv-player-only"));
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(Lang.get("inv-usage"));
            return false;
        }
        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("inv-not-found", "name", args[0]));
            return false;
        }
        if (fp.getPlayer() == null || fp.isBodyless()) {
            sender.sendMessage(Lang.get("inv-bodyless", "name", fp.getDisplayName()));
            return false;
        }
        if (!BotAccess.canAdminister(player, fp)) {
            sender.sendMessage(Lang.get("no-permission"));
            return false;
        }
        openGui(player, fp);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String lower = args[0].toLowerCase();
            return manager.getActivePlayers().stream()
                    .map(FakePlayer::getName)
                    .filter(n -> n.toLowerCase().startsWith(lower))
                    .toList();
        }
        return List.of();
    }

    public void openGui(Player viewer, FakePlayer fp) {
        Player botPlayer = fp.getPlayer();
        UUID owner = botLocks.putIfAbsent(fp.getUuid(), viewer.getUniqueId());
        if (owner != null && !owner.equals(viewer.getUniqueId())) {
            viewer.sendMessage(Lang.get("inv-busy", "name", fp.getDisplayName()));
            return;
        }
        if (botPlayer == null || !botPlayer.isOnline() || fp.isBodyless()) {
            botLocks.remove(fp.getUuid(), viewer.getUniqueId());
            viewer.sendMessage(Lang.get("inv-bodyless", "name", fp.getDisplayName()));
            return;
        }
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("]  ").color(DARK_GRAY))
                .append(Component.text("\u026A\u0274\u1D20").color(DARK_GRAY).decoration(TextDecoration.BOLD, true))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text(fp.getDisplayName()).color(ACCENT));
        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, title);
        PlayerInventory botInv = botPlayer.getInventory();
        for (int guiSlot = 0; guiSlot < GUI_SIZE; guiSlot++) {
            if (DECO.contains(guiSlot)) {
                gui.setItem(guiSlot, makeDecoItem(guiSlot));
            } else {
                int botSlot = GUI_TO_BOT[guiSlot];
                if (botSlot >= 0) {
                    ItemStack item = botInv.getItem(botSlot);
                    if (item != null && item.getType() != Material.AIR) {
                        gui.setItem(guiSlot, item.clone());
                    }
                }
            }
        }
        sessions.put(viewer.getUniqueId(), fp.getUuid());
        invToBot.put(gui, fp.getUuid());
        fp.setInventoryOpen(true);
        viewer.openInventory(gui);
        botPlayer
                .getLocation()
                .getWorld()
                .playSound(botPlayer.getLocation(), Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f, 1.0f);
    }

    public boolean isInventoryOpen(UUID botUuid) {
        return botLocks.containsKey(botUuid);
    }

    private static final String SC_BOOTS = "\u0299\u1D0F\u1D0F\u1D1B\uA731";

    private static final String SC_LEGGINGS = "\u029F\u1D07\u0262\u0262\u026A\u0274\u0262\uA731";

    private static final String SC_CHEST = "\u1D04\u029C\u1D07\uA731\u1D1B\u1D18\u029F\u1D00\u1D1B\u1D07";

    private static final String SC_HELMET = "\u029C\u1D07\u029F\u1D0D\u1D07\u1D1B";

    private static final String SC_OFFHAND = "\u1D0F\uA730\uA730\u029C\u1D00\u0274\u1D05";

    private static final String SC_ANY = "\u1D00\u0274\u028F";

    private static final String SC_OR = "\u1D0F\u0280";

    private static final String SC_ELYTRA = "\u1D07\u029F\u028F\u1D1B\u0280\u1D00";

    private static final String SC_UNRESTR = "\u029C\u1D07\u1D00\u1D05 \uA731\u029F\u1D0F\u1D1B \u026A\uA731"
            + " \u1D1C\u0274\u0280\u1D07\uA731\u1D1B\u0280\u026A\u1D04\u1D1B\u1D07\u1D05";

    private static final String SC_BOOT_TYPES = "(\u029F\u1D07\u1D00\u1D1B\u029C\u1D07\u0280, \u026A\u0280\u1D0F\u0274,"
            + " \u0262\u1D0F\u029F\u1D05, \u1D05\u026A\u1D00\u1D0D\u1D0F\u0274\u1D05,"
            + " \u0274\u1D07\u1D1B\u029C\u1D07\u0280\u026A\u1D1B\u1D07,"
            + " \u1D04\u029C\u1D00\u026A\u0274)";

    private static ItemStack makeDecoItem(int slot) {
        return switch (slot) {
            case 36 -> equipLabel(SC_BOOTS, SC_ANY + " " + SC_BOOTS + "  " + SC_BOOT_TYPES);
            case 37 -> equipLabel(SC_LEGGINGS, SC_ANY + " " + SC_LEGGINGS);
            case 38 -> equipLabel(SC_CHEST, SC_ANY + " " + SC_CHEST + "  " + SC_OR + "  " + SC_ELYTRA);
            case 39 -> equipLabel(SC_HELMET, SC_ANY + " \u026A\u1D1B\u1D07\u1D0D  \u2014  " + SC_UNRESTR);
            case 41 -> equipLabel(
                    SC_OFFHAND,
                    SC_ANY
                            + " \u026A\u1D1B\u1D07\u1D0D  \u2014  "
                            + SC_OFFHAND
                            + " \uA731\u029F\u1D0F\u1D1B \u026A\uA731"
                            + " \u1D1C\u0274\u0280\u1D07\uA731\u1D1B\u0280\u026A\u1D04\u1D1B\u1D07\u1D05");
            default -> blankPane();
        };
    }

    private static ItemStack equipLabel(String title, String restriction) {
        ItemStack item = new ItemStack(LABEL_MAT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("\u2193 ").color(ACCENT))
                .append(Component.text(title).color(ACCENT).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(
                Component.empty(),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("\u1D00\u1D04\u1D04\u1D07\u1D18\u1D1B\uA731" + "  ")
                                .color(DARK_GRAY))
                        .append(Component.text(restriction).color(GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack blankPane() {
        ItemStack item = new ItemStack(BLANK_MAT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isCompatibleWithSlot(int guiSlot, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return true;
        String n = item.getType().name();
        return switch (guiSlot) {
            case 45 -> n.endsWith("_BOOTS");
            case 46 -> n.endsWith("_LEGGINGS");
            case 47 -> n.endsWith("_CHESTPLATE") || n.equals("ELYTRA");
            default -> true;
        };
    }

    private static String slotTypeName(int guiSlot) {
        return switch (guiSlot) {
            case 45 -> "\u1D1A\u1D0F\u1D0F\u1D1B\uA731";
            case 46 -> "\u029F\u1D07\u0262\u0262\u026A\u0274\u0262\uA731";
            case 47 -> "\u1D04\u029C\u1D07\uA731\u1D1B\u1D18\u029F\u1D00\u1D1B\u1D07  \u1D0F\u0280 "
                    + " \u1D07\u029F\u028F\u1D1B\u0280\u1D00";
            default -> "\u026A\u1D1B\u1D07\u1D0D";
        };
    }

    private static ItemStack getIncomingItem(InventoryClickEvent event) {
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> {
                if (event.getWhoClicked() instanceof Player p) {
                    int btn = event.getHotbarButton();
                    yield btn >= 0 ? p.getInventory().getItem(btn) : event.getCursor();
                }
                yield null;
            }
            default -> null;
        };
    }

    private static void sendIncompatibleHint(Player player, int guiSlot) {
        player.sendActionBar(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("\uA730\u1D18\u1D18").color(ACCENT))
                .append(Component.text("]  ").color(DARK_GRAY))
                .append(Component.text("\u2717 ").color(OFF_RED))
                .append(Component.text("\u1D1B\u029C\u026A\uA731  \uA731\u029F\u1D0F\u1D1B"
                                + "  \u1D0F\u0274\u029F\u028F "
                                + " \u1D00\u1D04\u1D04\u1D07\u1D18\u1D1B\uA731 "
                                + " ")
                        .color(GRAY))
                .append(Component.text(slotTypeName(guiSlot)).color(VAL_YELLOW).decoration(TextDecoration.BOLD, true)));
    }

    private void syncToBotInventory(Inventory gui, PlayerInventory botInv) {
        for (int guiSlot = 0; guiSlot < GUI_SIZE; guiSlot++) {
            if (DECO.contains(guiSlot)) continue;
            int botSlot = GUI_TO_BOT[guiSlot];
            if (botSlot < 0) continue;
            ItemStack item = gui.getItem(guiSlot);
            botInv.setItem(botSlot, (item == null || item.getType() == Material.AIR) ? null : item.clone());
        }
    }

    private void scheduleSync(UUID botUuid, Inventory gui) {
        FppScheduler.runSync(plugin, () -> {
            FakePlayer fp = manager.getByUuid(botUuid);
            if (fp == null || fp.getPlayer() == null || fp.isBodyless()) return;
            syncToBotInventory(gui, fp.getPlayer().getInventory());
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        UUID botUuid = invToBot.get(top);
        if (botUuid == null) return;
        int rawSlot = event.getRawSlot();
        boolean inTop = rawSlot >= 0 && rawSlot < GUI_SIZE;

        if (inTop && DECO.contains(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp == null || fp.getPlayer() == null || fp.isBodyless()) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
            return;
        }

        if (inTop && EQUIP_SLOTS.contains(rawSlot)) {
            InventoryAction action = event.getAction();
            switch (action) {
                case SWAP_WITH_CURSOR, HOTBAR_SWAP, PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                    ItemStack incoming = getIncomingItem(event);
                    if (incoming != null
                            && incoming.getType() != Material.AIR
                            && !isCompatibleWithSlot(rawSlot, incoming)) {
                        event.setCancelled(true);
                        sendIncompatibleHint(player, rawSlot);
                        return;
                    }
                }
                default -> {}
            }
        }
        scheduleSync(botUuid, top);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        UUID botUuid = invToBot.get(top);
        if (botUuid == null) return;

        if (event.getRawSlots().stream().anyMatch(DECO::contains)) {
            event.setCancelled(true);
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (EQUIP_SLOTS.contains(slot) && !isCompatibleWithSlot(slot, event.getCursor())) {
                event.setCancelled(true);
                sendIncompatibleHint(player, slot);
                return;
            }
        }
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp == null || fp.getPlayer() == null || fp.isBodyless()) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
            return;
        }
        scheduleSync(botUuid, top);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        Inventory top = event.getView().getTopInventory();
        UUID botUuid = invToBot.remove(top);
        if (botUuid == null) return;
        botLocks.remove(botUuid, viewer.getUniqueId());
        sessions.remove(viewer.getUniqueId());
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) {
            fp.setInventoryOpen(false);
            if (fp.getPlayer() != null && !fp.isBodyless()) {
                syncToBotInventory(top, fp.getPlayer().getInventory());
                fp.getPlayer()
                        .getLocation()
                        .getWorld()
                        .playSound(
                                fp.getPlayer().getLocation(),
                                Sound.BLOCK_CHEST_CLOSE,
                                SoundCategory.BLOCKS,
                                0.5f,
                                1.0f);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClickBot(PlayerInteractAtEntityEvent event) {

        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player botPlayer)) return;
        FakePlayer fp = manager.getByEntity(botPlayer);
        if (fp == null || fp.isBodyless()) return;

        Player player = event.getPlayer();

        if (player.isSneaking() && Config.isBotShiftRightClickSettingsEnabled() && Perm.has(player, Perm.SETTINGS)) {
            event.setCancelled(true);
            if (!BotAccess.canAdminister(player, fp)) {
                player.sendMessage(Lang.get("no-permission"));
                return;
            }
            botSettingGui.open(player, fp);
            return;
        }

        if (!Config.isBotRightClickEnabled()) return;

        if (fp.hasRightClickCommand()) {
            if (!BotAccess.canAdminister(player, fp)) return;
            event.setCancelled(true);

            Bukkit.dispatchCommand(fp.getPlayer(), fp.getRightClickCommand());
            return;
        }

        if (!Perm.hasAny(player, Perm.INVENTORY_RIGHTCLICK, Perm.INVENTORY_OWN)) return;
        if (!BotAccess.canAdminister(player, fp)) return;
        event.setCancelled(true);
        openGui(player, fp);
        player.sendMessage(Lang.get("inv-opened", "name", fp.getDisplayName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerQuit(PlayerQuitEvent event) {
        UUID viewerUuid = event.getPlayer().getUniqueId();
        UUID botUuid = sessions.remove(viewerUuid);
        if (botUuid != null) botLocks.remove(botUuid, viewerUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDespawn(FppBotDespawnEvent event) {
        UUID botUuid = event.getBot().getUuid();
        botLocks.remove(botUuid);
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(sessions).entrySet()) {
            if (!botUuid.equals(entry.getValue())) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) viewer.closeInventory();
            sessions.remove(entry.getKey());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDeath(PlayerDeathEvent event) {
        FakePlayer fp = manager.getByEntity(event.getEntity());
        if (fp == null) return;
        UUID botUuid = fp.getUuid();
        botLocks.remove(botUuid);
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(sessions).entrySet()) {
            if (botUuid.equals(entry.getValue())) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) viewer.closeInventory();
            }
        }
    }
}
