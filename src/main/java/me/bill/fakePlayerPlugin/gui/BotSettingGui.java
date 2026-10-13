package me.bill.fakePlayerPlugin.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotSettingChangeEvent;
import me.bill.fakePlayerPlugin.api.impl.FppBotImpl;
import me.bill.fakePlayerPlugin.command.LeftClickCommand;
import me.bill.fakePlayerPlugin.command.RightClickCommand;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.economy.RentalPurchases;
import me.bill.fakePlayerPlugin.fakeplayer.BotFoods;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.NmsPlayerSpawner;
import me.bill.fakePlayerPlugin.fakeplayer.SkinManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinModelDetector;
import me.bill.fakePlayerPlugin.fakeplayer.SkinProfile;
import me.bill.fakePlayerPlugin.fakeplayer.pathfinding.PathfindingDebugManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.TextUtil;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class BotSettingGui implements Listener {

    private static final TextColor ACCENT = GuiKit.ACCENT;
    private static final TextColor ON_GREEN = GuiKit.ON_GREEN;
    private static final TextColor OFF_RED = GuiKit.OFF_RED;
    private static final TextColor VALUE_YELLOW = GuiKit.VALUE_YELLOW;
    private static final TextColor YELLOW = GuiKit.YELLOW;
    private static final TextColor GRAY = GuiKit.GRAY;
    private static final TextColor DARK_GRAY = GuiKit.DARK_GRAY;
    private static final TextColor WHITE = GuiKit.WHITE;
    private static final TextColor DANGER_RED = GuiKit.DANGER_RED;
    private static final TextColor COMING_SOON_COLOR = GuiKit.COMING_SOON_COLOR;
    private static final TextColor SELECTED_GREEN = GuiKit.SELECTED_GREEN;

    private static final int SIZE = 54;
    private static final int SETTINGS_PER_PAGE = 45;
    /** Longest allowed bot display name (visible characters) - keeps the floating name-tag readable. */
    private static final int RENAME_MAX_LENGTH = 32;

    private static final int SLOT_RESET = 45;
    private static final int SLOT_CAT_PREV = 46;
    private static final int SLOT_CAT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    private static final int CAT_WINDOW = 5;
    private static final int CAT_WINDOW_START = 47;

    private static final int MOB_GUI_SIZE = 54;
    private static final int MOB_SLOTS = 45;
    private static final int MOB_SLOT_BACK = 45;
    private static final int MOB_SLOT_PREV_PAGE = 46;
    private static final int MOB_SLOT_CLEAR = 49;
    private static final int MOB_SLOT_NEXT_PAGE = 52;
    private static final int MOB_SLOT_CLOSE = 53;

    private static final List<MobDisplay> MOB_LIST;

    static {
        List<MobDisplay> list = new ArrayList<>();

        list.add(new MobDisplay(EntityType.ZOMBIE, Material.ZOMBIE_HEAD, "ᴢᴏᴍʙɪᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.SKELETON, Material.SKELETON_SKULL, "ꜱᴋᴇʟᴇᴛᴏɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.CREEPER, Material.CREEPER_HEAD, "ᴄʀᴇᴇᴘᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.SPIDER, Material.SPIDER_EYE, "ꜱᴘɪᴅᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.CAVE_SPIDER, Material.FERMENTED_SPIDER_EYE, "ᴄᴀᴠᴇ ꜱᴘɪᴅᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.ENDERMAN, Material.ENDER_PEARL, "ᴇɴᴅᴇʀᴍᴀɴ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.WITCH, Material.SPLASH_POTION, "ᴡɪᴛᴄʜ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.PILLAGER, Material.CROSSBOW, "ᴘɪʟʟᴀɢᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.VINDICATOR, Material.IRON_AXE, "ᴠɪɴᴅɪᴄᴀᴛᴏʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.EVOKER, Material.TOTEM_OF_UNDYING, "ᴇᴠᴏᴋᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.RAVAGER, Material.SADDLE, "ʀᴀᴠᴀɢᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.VEX, Material.IRON_SWORD, "ᴠᴇx", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.PHANTOM, Material.PHANTOM_MEMBRANE, "ᴘʜᴀɴᴛᴏᴍ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.DROWNED, Material.TRIDENT, "ᴅʀᴏᴡɴᴇᴅ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.HUSK, Material.SAND, "ʜᴜꜱᴋ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.STRAY, Material.ARROW, "ꜱᴛʀᴀʏ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.BLAZE, Material.BLAZE_ROD, "ʙʟᴀᴢᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.GHAST, Material.GHAST_TEAR, "ɢʜᴀꜱᴛ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.MAGMA_CUBE, Material.MAGMA_CREAM, "ᴍᴀɢᴍᴀ ᴄᴜʙᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.SLIME, Material.SLIME_BALL, "ꜱʟɪᴍᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.HOGLIN, Material.COOKED_PORKCHOP, "ʜᴏɢʟɪɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.PIGLIN_BRUTE, Material.GOLDEN_AXE, "ᴘɪɢʟɪɴ ʙʀᴜᴛᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.WARDEN, Material.SCULK_SHRIEKER, "ᴡᴀʀᴅᴇɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(
                EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SKULL, "ᴡɪᴛʜᴇʀ ꜱᴋᴇʟᴇᴛᴏɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.GUARDIAN, Material.PRISMARINE_SHARD, "ɢᴜᴀʀᴅɪᴀɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.ELDER_GUARDIAN, Material.PRISMARINE_CRYSTALS, "ᴇʟᴅᴇʀ ɢᴜᴀʀᴅɪᴀɴ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.SHULKER, Material.SHULKER_SHELL, "ꜱʜᴜʟᴋᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.SILVERFISH, Material.STONE_BRICKS, "ꜱɪʟᴠᴇʀꜰɪꜱʜ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.ENDERMITE, Material.ENDER_EYE, "ᴇɴᴅᴇʀᴍɪᴛᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.BREEZE, Material.WIND_CHARGE, "ʙʀᴇᴇᴢᴇ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.BOGGED, Material.POISONOUS_POTATO, "ʙᴏɢɢᴇᴅ", "ʜᴏꜱᴛɪʟᴇ"));

        list.add(new MobDisplay(EntityType.ZOMBIFIED_PIGLIN, Material.GOLD_NUGGET, "ᴢᴏᴍʙɪꜰɪᴇᴅ ᴘɪɢʟɪɴ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.PIGLIN, Material.GOLD_INGOT, "ᴘɪɢʟɪɴ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.WOLF, Material.BONE, "ᴡᴏʟꜰ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.IRON_GOLEM, Material.IRON_BLOCK, "ɪʀᴏɴ ɢᴏʟᴇᴍ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.BEE, Material.HONEYCOMB, "ʙᴇᴇ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.POLAR_BEAR, Material.COD, "ᴘᴏʟᴀʀ ʙᴇᴀʀ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.LLAMA, Material.LEAD, "ʟʟᴀᴍᴀ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.DOLPHIN, Material.HEART_OF_THE_SEA, "ᴅᴏʟᴘʜɪɴ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.GOAT, Material.WHEAT, "ɢᴏᴀᴛ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.PANDA, Material.BAMBOO, "ᴘᴀɴᴅᴀ", "ɴᴇᴜᴛʀᴀʟ"));
        list.add(new MobDisplay(EntityType.TRADER_LLAMA, Material.LEAD, "ᴛʀᴀᴅᴇʀ ʟʟᴀᴍᴀ", "ɴᴇᴜᴛʀᴀʟ"));

        list.add(new MobDisplay(EntityType.ENDER_DRAGON, Material.DRAGON_HEAD, "ᴇɴᴅᴇʀ ᴅʀᴀɢᴏɴ", "ʙᴏꜱꜱ"));
        list.add(new MobDisplay(EntityType.WITHER, Material.NETHER_STAR, "ᴡɪᴛʜᴇʀ", "ʙᴏꜱꜱ"));

        list.add(new MobDisplay(EntityType.COW, Material.BEEF, "ᴄᴏᴡ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.PIG, Material.PORKCHOP, "ᴘɪɢ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SHEEP, Material.WHITE_WOOL, "ꜱʜᴇᴇᴘ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.CHICKEN, Material.FEATHER, "ᴄʜɪᴄᴋᴇɴ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.RABBIT, Material.RABBIT_FOOT, "ʀᴀʙʙɪᴛ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SQUID, Material.INK_SAC, "ꜱQᴜɪᴅ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.GLOW_SQUID, Material.GLOW_INK_SAC, "ɢʟᴏᴡ ꜱQᴜɪᴅ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.TURTLE, Material.TURTLE_EGG, "ᴛᴜʀᴛʟᴇ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.COD, Material.COD, "ᴄᴏᴅ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SALMON, Material.SALMON, "ꜱᴀʟᴍᴏɴ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.TROPICAL_FISH, Material.TROPICAL_FISH, "ᴛʀᴏᴘɪᴄᴀʟ ꜰɪꜱʜ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.PUFFERFISH, Material.PUFFERFISH, "ᴘᴜꜰꜰᴇʀꜰɪꜱʜ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.VILLAGER, Material.EMERALD, "ᴠɪʟʟᴀɢᴇʀ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.WANDERING_TRADER, Material.EMERALD_BLOCK, "ᴡᴀɴᴅᴇʀɪɴɢ ᴛʀᴀᴅᴇʀ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.HORSE, Material.GOLDEN_APPLE, "ʜᴏʀꜱᴇ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.DONKEY, Material.CHEST, "ᴅᴏɴᴋᴇʏ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.MULE, Material.CHEST, "ᴍᴜʟᴇ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.CAT, Material.STRING, "ᴄᴀᴛ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.PARROT, Material.COOKIE, "ᴘᴀʀʀᴏᴛ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.FOX, Material.SWEET_BERRIES, "ꜰᴏx", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.OCELOT, Material.COD, "ᴏᴄᴇʟᴏᴛ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.AXOLOTL, Material.AXOLOTL_BUCKET, "ᴀxᴏʟᴏᴛʟ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.FROG, Material.SLIME_BALL, "ꜰʀᴏɢ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.TADPOLE, Material.TADPOLE_BUCKET, "ᴛᴀᴅᴘᴏʟᴇ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.ALLAY, Material.AMETHYST_SHARD, "ᴀʟʟᴀʏ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SNIFFER, Material.TORCHFLOWER_SEEDS, "ꜱɴɪꜰꜰᴇʀ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.CAMEL, Material.CACTUS, "ᴄᴀᴍᴇʟ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.ARMADILLO, Material.BRUSH, "ᴀʀᴍᴀᴅɪʟʟᴏ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SNOW_GOLEM, Material.SNOW_BLOCK, "ꜱɴᴏᴡ ɢᴏʟᴇᴍ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.STRIDER, Material.WARPED_FUNGUS, "ꜱᴛʀɪᴅᴇʀ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.BAT, Material.BLACK_DYE, "ʙᴀᴛ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.MOOSHROOM, Material.RED_MUSHROOM, "ᴍᴏᴏꜱʜʀᴏᴏᴍ", "ᴘᴀꜱꜱɪᴠᴇ"));
        list.add(new MobDisplay(EntityType.SKELETON_HORSE, Material.BONE_BLOCK, "ꜱᴋᴇʟᴇᴛᴏɴ ʜᴏʀꜱᴇ", "ᴜɴᴅᴇᴀᴅ"));
        list.add(new MobDisplay(EntityType.ZOMBIE_HORSE, Material.ROTTEN_FLESH, "ᴢᴏᴍʙɪᴇ ʜᴏʀꜱᴇ", "ᴜɴᴅᴇᴀᴅ"));
        list.add(new MobDisplay(EntityType.ZOMBIE_VILLAGER, Material.GOLDEN_APPLE, "ᴢᴏᴍʙɪᴇ ᴠɪʟʟᴀɢᴇʀ", "ʜᴏꜱᴛɪʟᴇ"));
        list.add(new MobDisplay(EntityType.ZOGLIN, Material.ROTTEN_FLESH, "ᴢᴏɢʟɪɴ", "ʜᴏꜱᴛɪʟᴇ"));

        MOB_LIST = Collections.unmodifiableList(list);
    }

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    private final Map<UUID, int[]> sessions = new HashMap<>();

    private final Map<UUID, UUID> botSessions = new HashMap<>();

    private final Map<UUID, UUID> botLocks = new HashMap<>();

    private final Map<UUID, ChatInputSes> chatSessions = new HashMap<>();
    private final Set<UUID> pendingChatInput = new HashSet<>();
    private final Set<UUID> pendingRebuild = new HashSet<>();

    private final Set<UUID> pendingDelete = new HashSet<>();

    private final Map<UUID, Long> pendingResetConfirm = new HashMap<>();
    private final Map<UUID, Integer> confirmTickTaskIds = new HashMap<>();
    private static final long RESET_CONFIRM_WINDOW_MS = 5000L;

    private final Map<UUID, Integer> mobSelectorPage = new HashMap<>();

    private final Set<UUID> inMobSelector = new HashSet<>();

    private final Map<UUID, Integer> foodSelectorPage = new HashMap<>();

    private final Set<UUID> inFoodSelector = new HashSet<>();

    private final Map<UUID, Integer> editPauseCounts = new HashMap<>();

    private final List<BotCategory> categories;

    public BotSettingGui(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.categories = List.of(general(), pve(), pathfinding(), skin(), autoEat(), danger());
    }

    private List<BotCategory> allCategories(Player viewer) {
        return categories;
    }

    public void open(Player player, FakePlayer bot) {
        if (!BotAccess.canAdminister(player, bot)) {
            player.sendMessage(Lang.get("no-permission"));
            return;
        }
        UUID botUuid = bot.getUuid();
        UUID uuid = player.getUniqueId();
        if (!acquireBotLock(botUuid, uuid)) {
            player.sendMessage(Lang.get("inv-busy", "name", bot.getDisplayName()));
            return;
        }
        if (botUuid.equals(botSessions.get(uuid))) {
            build(player);
            return;
        }
        pauseBotForEditing(bot);
        sessions.put(uuid, new int[] {0, 0, 0});
        botSessions.put(uuid, botUuid);
        build(player);
    }

    public @NotNull List<String> getCategoryNames() {
        List<String> names = new ArrayList<>(categories.size());
        for (BotCategory category : categories) names.add(category.label());
        return Collections.unmodifiableList(names);
    }

    public void shutdown() {
        for (UUID botUuid : new ArrayList<>(editPauseCounts.keySet())) resumeBotAfterEditing(botUuid);
        sessions.clear();
        botSessions.clear();
        botLocks.clear();
        chatSessions.forEach((uuid, ses) -> FppScheduler.cancelTask(ses.cleanupTaskId));
        chatSessions.clear();
        pendingChatInput.clear();
        pendingRebuild.clear();
        pendingDelete.clear();
        pendingResetConfirm.clear();
        confirmTickTaskIds.forEach((uuid, taskId) -> FppScheduler.cancelTask(taskId));
        confirmTickTaskIds.clear();
        mobSelectorPage.clear();
        inMobSelector.clear();
        inFoodSelector.clear();
        editPauseCounts.clear();
    }

    private void build(Player player) {
        UUID uuid = player.getUniqueId();
        int[] state = sessions.get(uuid);
        UUID botUuid = botSessions.get(uuid);
        if (state == null || botUuid == null) return;

        FakePlayer bot = manager.getByUuid(botUuid);
        if (bot == null) {
            cleanup(uuid);
            player.sendMessage(Lang.get("delete-not-found", "name", "?"));
            return;
        }
        if (!BotAccess.canAdminister(player, bot)) {
            cleanup(uuid);
            player.closeInventory();
            player.sendMessage(Lang.get("no-permission"));
            return;
        }

        int catIdx = state[0];
        int pageIdx = state[1];
        int catOffset = state[2];
        List<BotCategory> all = allCategories(player);
        if (catIdx >= all.size()) catIdx = all.size() - 1;
        state[0] = catIdx;
        BotCategory cat = all.get(catIdx);
        boolean isOp = isOp(player);

        GuiHolder holder = new GuiHolder(uuid);
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(bot.getName()).color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text(cat.label()).color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, SIZE, title);

        List<BotEntry> entries = visibleEntries(cat, isOp);
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) SETTINGS_PER_PAGE));
        pageIdx = Math.min(pageIdx, Math.max(0, totalPages - 1));
        state[1] = pageIdx;

        int startIdx = pageIdx * SETTINGS_PER_PAGE;
        int endIdx = Math.min(startIdx + SETTINGS_PER_PAGE, entries.size());
        for (int i = startIdx; i < endIdx; i++) {
            inv.setItem(i - startIdx, buildEntryItem(entries.get(i), bot, player));
        }

        inv.setItem(SLOT_RESET, buildResetButton());
        inv.setItem(
                SLOT_CAT_PREV, catOffset > 0 ? buildCatArrow(false) : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < CAT_WINDOW; i++) {
            int ci = catOffset + i;
            inv.setItem(
                    CAT_WINDOW_START + i,
                    ci < all.size()
                            ? buildCategoryTab(all.get(ci), ci == catIdx)
                            : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        }
        inv.setItem(
                SLOT_CAT_NEXT,
                catOffset + CAT_WINDOW < all.size()
                        ? buildCatArrow(true)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(SLOT_CLOSE, buildBackButton(player));

        pendingRebuild.add(uuid);
        player.openInventory(inv);
        pendingRebuild.remove(uuid);
        sessions.put(uuid, state);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {

        if (event.getInventory().getHolder() instanceof MobSelectorHolder msh) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null) return;
            if (!event.getClickedInventory().equals(event.getInventory())) return;
            handleMobSelectorClick(player, msh, event.getSlot());
            return;
        }

        if (event.getInventory().getHolder() instanceof FoodSelectorHolder fsh) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null) return;
            if (!event.getClickedInventory().equals(event.getInventory())) return;
            handleFoodSelectorClick(player, fsh, event.getSlot());
            return;
        }

        if (event.getInventory().getHolder() instanceof ShareSelectorHolder ssh) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null) return;
            if (!event.getClickedInventory().equals(event.getInventory())) return;
            handleShareSelectorClick(player, ssh, event.getSlot());
            return;
        }

        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getInventory())) return;

        UUID uuid = player.getUniqueId();
        int[] state = sessions.get(holder.uuid);
        UUID botUuid = botSessions.get(uuid);
        if (state == null || botUuid == null) return;

        FakePlayer bot = manager.getByUuid(botUuid);
        if (bot == null) {
            player.closeInventory();
            return;
        }
        if (!BotAccess.canAdminister(player, bot)) {
            player.closeInventory();
            player.sendMessage(Lang.get("no-permission"));
            return;
        }

        boolean isOp = isOp(player);
        int slot = event.getSlot();
        int catIdx = state[0];
        int catOffset = state[2];

        if (slot == SLOT_RESET) {
            playUiClick(player, 0.6f);
            resetBot(player, bot, isOp);
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
            if (catOffset + CAT_WINDOW < allCategories(player).size()) {
                playUiClick(player, 1.0f);
                state[2]++;
            }
            build(player);
            return;
        }
        if (slot == SLOT_CLOSE) {
            playUiClick(player, 0.8f);
            if (event.isShiftClick() && GuiKit.hasMainMenu()) {
                GuiKit.openMainMenu(player);
                return;
            }
            if (Perm.has(player, Perm.LIST)) {
                // Back to the bot list rather than closing outright.
                player.performCommand("fpp list");
                return;
            }
            player.closeInventory();
            return;
        }
        if (slot >= CAT_WINDOW_START && slot < CAT_WINDOW_START + CAT_WINDOW) {
            int ci = catOffset + (slot - CAT_WINDOW_START);
            if (ci < allCategories(player).size()) {
                if (ci != catIdx) playUiClick(player, 1.3f);
                state[0] = ci;
                state[1] = 0;
                build(player);
            }
            return;
        }
        if (slot < 45) {
            List<BotCategory> allCats = allCategories(player);
            if (catIdx >= allCats.size()) return;
            List<BotEntry> entries = visibleEntries(allCats.get(catIdx), isOp);
            int entryIdx = state[1] * SETTINGS_PER_PAGE + slot;
            if (entryIdx >= entries.size()) return;
            handleEntryClick(player, bot, entries.get(entryIdx), isOp);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (event.getInventory().getHolder() instanceof MobSelectorHolder) {

            if (pendingRebuild.contains(uuid)) return;
            inMobSelector.remove(uuid);
            mobSelectorPage.remove(uuid);

            if (event.getReason() != InventoryCloseEvent.Reason.DISCONNECT && sessions.containsKey(uuid)) {
                FppScheduler.runSync(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && sessions.containsKey(uuid)) build(p);
                });
            }
            return;
        }

        if (event.getInventory().getHolder() instanceof FoodSelectorHolder) {
            if (pendingRebuild.contains(uuid)) return;
            inFoodSelector.remove(uuid);
            foodSelectorPage.remove(uuid);
            if (event.getReason() != InventoryCloseEvent.Reason.DISCONNECT && sessions.containsKey(uuid)) {
                FppScheduler.runSync(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && sessions.containsKey(uuid)) build(p);
                });
            }
            return;
        }

        if (event.getInventory().getHolder() instanceof ShareSelectorHolder) {
            if (pendingRebuild.contains(uuid)) return;
            if (event.getReason() != InventoryCloseEvent.Reason.DISCONNECT && sessions.containsKey(uuid)) {
                FppScheduler.runSync(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && sessions.containsKey(uuid)) build(p);
                });
            }
            return;
        }

        if (!(event.getInventory().getHolder() instanceof GuiHolder)) return;
        if (pendingChatInput.contains(uuid)) return;
        if (pendingRebuild.contains(uuid)) return;
        if (pendingDelete.contains(uuid)) return;
        if (inMobSelector.contains(uuid)) return;
        if (inFoodSelector.contains(uuid)) return;
        cleanup(uuid);
        if (event.getReason() != InventoryCloseEvent.Reason.DISCONNECT && event.getPlayer() instanceof Player player) {
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✔ ").color(ON_GREEN))
                    .append(Component.text("ʙᴏᴛ ꜱᴇᴛᴛɪɴɢꜱ ꜱᴀᴠᴇᴅ • ꜱᴇᴛᴛɪɴɢꜱ ᴀᴘᴘʟɪᴇᴅ")
                            .color(WHITE)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputSes ses = chatSessions.remove(uuid);
        if (ses == null) return;

        event.setCancelled(true);
        FppScheduler.cancelTask(ses.cleanupTaskId);

        String raw = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim();

        handleChatInput(uuid, ses, raw);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputSes ses = chatSessions.remove(uuid);
        if (ses == null) return;

        event.setCancelled(true);
        FppScheduler.cancelTask(ses.cleanupTaskId);
        handleChatInput(uuid, ses, event.getMessage().trim());
    }

    private void handleChatInput(UUID uuid, ChatInputSes ses, String raw) {
        sessions.put(uuid, ses.guiState);
        FppScheduler.runSync(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;

            if (raw.equalsIgnoreCase("cancel")) {
                p.sendActionBar(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("✦ ").color(ACCENT))
                        .append(Component.text("ᴄᴀɴᴄᴇʟʟᴇᴅ - ʀᴇᴛᴜʀɴɪɴɢ ᴛᴏ" + " ꜱᴇᴛᴛɪɴɢꜱ.")
                                .color(GRAY)));
                build(p);
                return;
            }

            FakePlayer bot = manager.getByUuid(ses.botUuid);
            if (bot == null) {
                p.sendActionBar(Lang.get("delete-not-found", "name", "?"));
                cleanup(uuid);
                return;
            }

            applyInput(p, bot, ses.inputType, raw);
            build(p);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ChatInputSes ses = chatSessions.remove(uuid);
        if (ses != null) FppScheduler.cancelTask(ses.cleanupTaskId);
        inMobSelector.remove(uuid);
        mobSelectorPage.remove(uuid);
        inFoodSelector.remove(uuid);
        foodSelectorPage.remove(uuid);
        cleanup(uuid);
        PathfindingDebugManager.clearViewer(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDespawn(FppBotDespawnEvent event) {
        releaseAllEditors(event.getBot().getUuid());
        PathfindingDebugManager.clearBot(event.getBot().getUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDeath(PlayerDeathEvent event) {
        FakePlayer bot = manager.getByEntity(event.getEntity());
        if (bot != null) releaseAllEditors(bot.getUuid());
    }

    private void handleEntryClick(Player player, FakePlayer bot, BotEntry entry, boolean isOp) {
        switch (entry.type()) {
            case COMING_SOON -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.8f, 1.0f);
                player.sendActionBar(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("⊘ ").color(COMING_SOON_COLOR))
                        .append(Component.text(entry.label() + "  ")
                                .color(WHITE)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text("- ᴄᴏᴍɪɴɢ ꜱᴏᴏɴ")
                                .color(COMING_SOON_COLOR)
                                .decoration(TextDecoration.BOLD, true)));
            }
            case TOGGLE -> {
                boolean newVal;
                if ("show_path".equals(entry.id())) {
                    newVal = PathfindingDebugManager.toggle(player.getUniqueId(), bot.getUuid());
                } else {
                    newVal = applyToggle(bot, entry.id());

                    if (!newVal) {
                        if ("pickup_items".equals(entry.id())) {
                            dropBotInventory(bot);
                        } else if ("pickup_xp".equals(entry.id())) {
                            dropBotXp(bot);
                        }
                    }

                    manager.persistBotSettings(bot);
                }
                playUiClick(player, newVal ? 1.2f : 0.85f);
                sendActionBarConfirm(player, entry.label(), newVal ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ");
                build(player);
            }
            case CYCLE_PRIORITY -> {
                cyclePriority(bot);
                manager.persistBotSettings(bot);
                restartPveIfActive(bot);
                playUiClick(player, 1.0f);
                sendActionBarConfirm(player, entry.label(), bot.getPvePriority());
                build(player);
            }
            case CYCLE_PVE_MODE -> {
                cyclePveMode(bot);
                manager.persistBotSettings(bot);
                restartPveIfActive(bot);
                playUiClick(player, 1.0f);
                sendActionBarConfirm(player, entry.label(), pveModeLabel(bot));
                build(player);
            }
            case ACTION -> {
                playUiClick(player, 1.0f);
                openChatInput(player, bot, entry);
            }
            case MOB_SELECTOR -> {
                playUiClick(player, 1.0f);
                openMobSelector(player, bot);
            }
            case FOOD_SELECTOR -> {
                playUiClick(player, 1.0f);
                openFoodSelector(player, bot);
            }
            case IMMEDIATE -> {
                if ("share_control".equals(entry.id())) {
                    if (!BotAccess.canShare(player, bot)) {
                        player.sendMessage(Lang.get("no-permission"));
                        return;
                    }
                    openShareSelector(player, bot);
                    return;
                }
                applyImmediate(player, bot, entry.id());
                playUiClick(player, 0.85f);
                build(player);
            }
            case DANGER -> {
                if (!isOp) return;
                playUiClick(player, 0.6f);
                applyDanger(player, bot, entry.id());
            }
        }
    }

    private void fireSettingChange(FakePlayer bot, String key, Object oldValue, Object newValue) {
        Bukkit.getPluginManager().callEvent(new FppBotSettingChangeEvent(new FppBotImpl(bot), key, oldValue, newValue));
    }

    private boolean applyToggle(FakePlayer bot, String id) {
        return switch (id) {
            case "frozen" -> {
                boolean old = bot.isFrozen();
                bot.setFrozen(!old);
                fireSettingChange(bot, "frozen", old, bot.isFrozen());
                yield bot.isFrozen();
            }
            case "respawn_on_death" -> {
                boolean old = bot.isRespawnOnDeath();
                bot.setRespawnOnDeath(!old);
                fireSettingChange(bot, "respawn_on_death", old, bot.isRespawnOnDeath());
                yield bot.isRespawnOnDeath();
            }
            case "head_ai_enabled" -> {
                boolean old = bot.isHeadAiEnabled();
                bot.setHeadAiEnabled(!old);
                fireSettingChange(bot, "head_ai_enabled", old, bot.isHeadAiEnabled());
                yield bot.isHeadAiEnabled();
            }
            case "swim_ai_enabled" -> {
                boolean old = bot.isSwimAiEnabled();
                bot.setSwimAiEnabled(!old);
                fireSettingChange(bot, "swim_ai_enabled", old, bot.isSwimAiEnabled());
                yield bot.isSwimAiEnabled();
            }
            case "pickup_items" -> {
                boolean old = bot.isPickUpItemsEnabled();
                boolean v = !old;
                bot.setPickUpItemsEnabled(v);
                fireSettingChange(bot, "pickup_items", old, bot.isPickUpItemsEnabled());

                Player body = bot.getPlayer();
                if (body != null) body.setCanPickupItems(v);
                yield v;
            }
            case "pickup_xp" -> {
                boolean old = bot.isPickUpXpEnabled();
                bot.setPickUpXpEnabled(!old);
                fireSettingChange(bot, "pickup_xp", old, bot.isPickUpXpEnabled());
                yield bot.isPickUpXpEnabled();
            }
            case "auto_milk" -> {
                boolean old = bot.isAutoMilkEnabled();
                bot.setAutoMilkEnabled(!old);
                fireSettingChange(bot, "auto_milk", old, bot.isAutoMilkEnabled());
                yield bot.isAutoMilkEnabled();
            }
            case "auto_eat" -> {
                boolean old = bot.isAutoEatEnabled();
                bot.setAutoEatEnabled(!old);
                fireSettingChange(bot, "auto_eat", old, bot.isAutoEatEnabled());
                yield bot.isAutoEatEnabled();
            }
            case "prevent_bad_omen" -> {
                boolean old = bot.isPreventBadOmen();
                bot.setPreventBadOmen(!old);
                fireSettingChange(bot, "prevent_bad_omen", old, bot.isPreventBadOmen());
                yield bot.isPreventBadOmen();
            }
            case "nav_parkour" -> {
                boolean old = bot.isNavParkour();
                bot.setNavParkour(!old);
                fireSettingChange(bot, "nav_parkour", old, bot.isNavParkour());
                yield bot.isNavParkour();
            }
            case "nav_break_blocks" -> {
                boolean old = bot.isNavBreakBlocks();
                bot.setNavBreakBlocks(!old);
                fireSettingChange(bot, "nav_break_blocks", old, bot.isNavBreakBlocks());
                yield bot.isNavBreakBlocks();
            }
            case "nav_place_blocks" -> {
                boolean old = bot.isNavPlaceBlocks();
                bot.setNavPlaceBlocks(!old);
                fireSettingChange(bot, "nav_place_blocks", old, bot.isNavPlaceBlocks());
                yield bot.isNavPlaceBlocks();
            }
            case "pve_enabled" -> bot.isPveEnabled();
            case "pve_move" -> bot.isPveMoveToTarget();
            default -> false;
        };
    }

    private void restartPveIfActive(FakePlayer bot) {
        var pve = plugin.getPveController();
        if (pve != null) pve.refresh(bot);
        if (bot.isPveEnabled())
            fireSettingChange(
                    bot, "pve_restart", null, bot.getPveSmartAttackMode().name());
    }

    private void cyclePriority(FakePlayer bot) {
        String old = bot.getPvePriority();
        String current = bot.getPvePriority();
        bot.setPvePriority("nearest".equals(current) ? "lowest-health" : "nearest");
        fireSettingChange(bot, "pve_priority", old, bot.getPvePriority());
    }

    private void cyclePveMode(FakePlayer bot) {
        var oldMode = bot.getPveSmartAttackMode();
        boolean oldEnabled = bot.isPveEnabled();
        boolean oldMove = bot.isPveMoveToTarget();

        bot.setPveSmartAttackMode(oldMode.next());
        fireSettingChange(
                bot,
                "pve_smart_attack_mode",
                oldMode.name(),
                bot.getPveSmartAttackMode().name());
        if (oldEnabled != bot.isPveEnabled()) {
            fireSettingChange(bot, "pve_enabled", oldEnabled, bot.isPveEnabled());
        }
        if (oldMove != bot.isPveMoveToTarget()) {
            fireSettingChange(bot, "pve_move", oldMove, bot.isPveMoveToTarget());
        }

        var attackCmd = plugin.getAttackCommand();
        if (attackCmd != null && !bot.isPveEnabled()) attackCmd.stopAttacking(bot.getUuid());
    }

    private String pveModeLabel(FakePlayer bot) {
        return switch (bot.getPveSmartAttackMode()) {
            case OFF -> "✘ ᴏꜰꜰ";
            case ON_NO_MOVE -> "✔ ᴏɴ · ꜱᴛɪʟʟ";
            case ON_MOVE -> "✔ ᴏɴ · ᴍᴏᴠᴇ";
        };
    }

    private void applyImmediate(Player player, FakePlayer bot, String id) {
        switch (id) {
            case "skin_info" -> sendActionBarConfirm(player, "ᴄᴜʀʀᴇɴᴛ ꜱᴋɪɴ", skinSummary(bot));
            case "skin_reroll" -> rerollSkin(player, bot);
            case "pve_status" -> sendActionBarConfirm(player, "ᴘᴠᴇ ꜱᴛᴀᴛᴜꜱ", pveStatusLabel(bot));
            default -> {}
        }
    }

    private void rerollSkin(Player player, FakePlayer bot) {
        SkinManager skinManager = plugin.getSkinManager();
        if (skinManager == null || !Config.skinRarePoolsEnabled()) {
            sendActionBarConfirm(player, "ʀᴇ-ʀᴏʟʟ ꜱᴋɪɴ", "✘ ꜱᴋɪɴ ᴘᴏᴏʟꜱ ᴅɪꜱᴀʙʟᴇᴅ");
            return;
        }
        // Clearing the resolved skin makes resolveEffectiveSkin roll the pools again - identical
        // odds to a fresh spawn, including the rare tiers.
        bot.setResolvedSkin(null);
        skinManager.resolveEffectiveSkin(bot, skin -> {
            boolean applied = skin != null && skin.isValid() && skinManager.applySkinFromProfile(bot, skin);
            sendActionBarConfirm(player, "ʀᴇ-ʀᴏʟʟ ꜱᴋɪɴ", applied ? skinSummary(bot) : "✘ ʀᴏʟʟ ꜰᴀɪʟᴇᴅ");
            if (player.isOnline()) build(player);
        });
    }

    private void applyDanger(Player player, FakePlayer bot, String id) {
        if ("reset_all".equals(id)) {
            UUID uuid = player.getUniqueId();
            Long confirmTime = pendingResetConfirm.get(uuid);
            long now = System.currentTimeMillis();

            if (confirmTime == null || now - confirmTime > RESET_CONFIRM_WINDOW_MS) {
                pendingResetConfirm.put(uuid, now);
                player.sendMessage(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("⚠ ").color(DANGER_RED))
                        .append(Component.text("ᴄʟɪᴄᴋ ᴀɢᴀɪɴ ᴡɪᴛʜɪɴ 5ꜱ ᴛᴏ ᴄᴏɴꜰɪʀᴍ ʀᴇꜱᴇᴛ.")
                                .color(YELLOW)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.8f, 0.5f);
                startConfirmCountdown(player, bot, uuid);
                return;
            }

            cancelConfirmCountdown(uuid);
            pendingResetConfirm.remove(uuid);
            resetBot(player, bot, true);
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("⟲ ").color(YELLOW))
                    .append(Component.text("ᴀʟʟ ꜱᴇᴛᴛɪɴɢꜱ ʀᴇꜱᴇᴛ ꜰᴏʀ  ").color(WHITE))
                    .append(Component.text(bot.getName()).color(ACCENT)));
            return;
        }
        if ("delete".equals(id)) {
            String botName = bot.getName();
            UUID playerUuid = player.getUniqueId();

            pendingDelete.add(playerUuid);
            cleanup(playerUuid);
            player.closeInventory();
            pendingDelete.remove(playerUuid);

            manager.delete(botName, "gui_delete");
            player.sendMessage(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("✕ ").color(DANGER_RED))
                    .append(Component.text("ᴅᴇʟᴇᴛᴇᴅ ʙᴏᴛ  ").color(WHITE))
                    .append(Component.text(botName).color(ACCENT)));
        }
    }

    /** Ticks the reset-all confirm window every second so its lore shows a live countdown. */
    private void startConfirmCountdown(Player player, FakePlayer bot, UUID uuid) {
        cancelConfirmCountdown(uuid);
        int taskId = FppScheduler.runSyncRepeatingWithId(
                plugin,
                () -> {
                    Long confirmTime = pendingResetConfirm.get(uuid);
                    if (confirmTime == null || !player.isOnline()) {
                        cancelConfirmCountdown(uuid);
                        return;
                    }
                    long remainingMs = RESET_CONFIRM_WINDOW_MS - (System.currentTimeMillis() - confirmTime);
                    if (remainingMs <= 0) {
                        pendingResetConfirm.remove(uuid);
                        cancelConfirmCountdown(uuid);
                        build(player);
                        return;
                    }
                    build(player);
                },
                20L,
                20L);
        confirmTickTaskIds.put(uuid, taskId);
    }

    private void cancelConfirmCountdown(UUID uuid) {
        Integer taskId = confirmTickTaskIds.remove(uuid);
        if (taskId != null) FppScheduler.cancelTask(taskId);
    }

    private void applyInput(Player player, FakePlayer bot, String inputType, String raw) {
        switch (inputType) {
            case "rename" -> {
                String newName = raw.trim();
                String plain = PlainTextComponentSerializer.plainText()
                        .serialize(TextUtil.colorize(newName))
                        .trim();
                if (plain.isEmpty()) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ᴛʜᴀᴛ ɴᴀᴍᴇ ɪꜱ ᴇᴍᴘᴛʏ ᴏʀ ɪɴᴠᴀʟɪᴅ.")
                                    .color(GRAY)));
                    return;
                }
                if (plain.length() > RENAME_MAX_LENGTH) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ᴛᴏᴏ ʟᴏɴɢ - ᴍᴀx " + RENAME_MAX_LENGTH + " ᴄʜᴀʀᴀᴄᴛᴇʀꜱ.")
                                    .color(GRAY)));
                    return;
                }
                manager.renameBot(bot, newName);
                sendActionBarConfirm(player, "ʀᴇɴᴀᴍᴇᴅ", plain);
            }
            case "auto_eat_threshold" -> {
                int val;
                try {
                    val = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ - ᴇɴᴛᴇʀ 0-19.")
                                    .color(GRAY)));
                    return;
                }
                if (val < 0) val = 0;
                if (val > 19) val = 19;
                int old = bot.getAutoEatHungerThreshold();
                bot.setAutoEatHungerThreshold(val);
                fireSettingChange(bot, "auto_eat_threshold", old, bot.getAutoEatHungerThreshold());
                manager.persistBotSettings(bot);
                sendActionBarConfirm(player, "ᴀᴜᴛᴏ-ᴇᴀᴛ ᴀᴛ", bot.getAutoEatHungerThreshold() + " / 20 ʜᴜɴɢᴇʀ");
            }
            case "chunk_load_radius" -> {
                int globalMax = Config.chunkLoadingEnabled() ? Config.chunkLoadingRadius() : 0;
                int val;
                try {
                    val = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text(
                                            "ɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ - ᴇɴᴛᴇʀ -1 (ɢʟᴏʙᴀʟ), 0" + " (ᴏꜰꜰ), ᴏʀ 1-" + globalMax + ".")
                                    .color(GRAY)));
                    return;
                }

                if (val < -1) val = -1;
                if (val > globalMax && globalMax > 0) val = globalMax;
                int old = bot.getChunkLoadRadius();
                bot.setChunkLoadRadius(val);
                fireSettingChange(bot, "chunk_load_radius", old, bot.getChunkLoadRadius());
                manager.persistBotSettings(bot);
                String display = val == -1 ? "ɢʟᴏʙᴀʟ (" + globalMax + ")" : val == 0 ? "ᴅɪꜱᴀʙʟᴇᴅ" : val + " ᴄʜᴜɴᴋꜱ";
                sendActionBarConfirm(player, "ᴄʜᴜɴᴋ ʀᴀᴅɪᴜꜱ", display);
            }
            case "pve_range" -> {
                double val;
                try {
                    val = Double.parseDouble(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ - ᴇɴᴛᴇʀ 1-64.")
                                    .color(GRAY)));
                    return;
                }
                if (val < 1) val = 1;
                if (val > 64) val = 64;
                bot.setPveRange(val);
                manager.persistBotSettings(bot);
                restartPveIfActive(bot);
                sendActionBarConfirm(player, "ᴘᴠᴇ ʀᴀɴɢᴇ", (int) val + " ʙʟᴏᴄᴋꜱ");
            }
            case "rental_extend" -> {
                if (!player.hasPermission(Perm.RENT)) {
                    player.sendMessage(Lang.get("no-permission"));
                    return;
                }
                int hours;
                try {
                    hours = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Lang.get("rent-invalid-hours", "value", raw.trim()));
                    return;
                }
                int min = Config.rentalMinHours();
                int max = Config.rentalMaxHours();
                if (hours < min || hours > max) {
                    player.sendMessage(Lang.get(
                            "rent-hours-out-of-range", "min", String.valueOf(min), "max", String.valueOf(max)));
                    return;
                }
                RentalPurchases.Result result = RentalPurchases.extend(plugin, player, bot, hours);
                player.sendMessage(result.message());
            }
            case "left_click_interval" -> {
                int val;
                try {
                    val = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ - ᴇɴᴛᴇʀ "
                                            + LeftClickCommand.MIN_INTERVAL_TICKS
                                            + "-"
                                            + LeftClickCommand.MAX_INTERVAL_TICKS
                                            + ", ᴏʀ 0 ꜰᴏʀ ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ.")
                                    .color(GRAY)));
                    return;
                }
                if (val <= 0) {
                    val = -1;
                } else {
                    val = Math.max(
                            LeftClickCommand.MIN_INTERVAL_TICKS, Math.min(LeftClickCommand.MAX_INTERVAL_TICKS, val));
                }
                bot.setLeftClickIntervalTicks(val);
                manager.persistBotSettings(bot);
                persistClickIntervals(bot);
                String display = val == -1 ? "ɢʟᴏʙᴀʟ (" + Config.leftClickIntervalTicks() + ")" : val + " ᴛɪᴄᴋꜱ";
                sendActionBarConfirm(player, "ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ", display);
            }
            case "right_click_interval" -> {
                int val;
                try {
                    val = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("✘ ").color(OFF_RED))
                            .append(Component.text("ɪɴᴠᴀʟɪᴅ ɴᴜᴍʙᴇʀ - ᴇɴᴛᴇʀ "
                                            + RightClickCommand.MIN_INTERVAL_TICKS
                                            + "-"
                                            + RightClickCommand.MAX_INTERVAL_TICKS
                                            + ", ᴏʀ 0 ꜰᴏʀ ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ.")
                                    .color(GRAY)));
                    return;
                }
                if (val <= 0) {
                    val = -1;
                } else {
                    val = Math.max(
                            RightClickCommand.MIN_INTERVAL_TICKS, Math.min(RightClickCommand.MAX_INTERVAL_TICKS, val));
                }
                bot.setRightClickIntervalTicks(val);
                manager.persistBotSettings(bot);
                persistClickIntervals(bot);
                String display = val == -1 ? "ɢʟᴏʙᴀʟ (" + Config.rightClickIntervalTicks() + ")" : val + " ᴛɪᴄᴋꜱ";
                sendActionBarConfirm(player, "ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ", display);
            }
        }
    }

    private void persistClickIntervals(FakePlayer bot) {
        var db = plugin.getDatabaseManager();
        if (db != null) {
            db.updateBotClickIntervals(
                    bot.getUuid().toString(), bot.getLeftClickIntervalTicks(), bot.getRightClickIntervalTicks());
        }
    }

    private void openMobSelector(Player player, FakePlayer bot) {
        UUID uuid = player.getUniqueId();
        inMobSelector.add(uuid);
        mobSelectorPage.put(uuid, 0);

        pendingRebuild.add(uuid);
        buildMobSelector(player, bot, 0);
        pendingRebuild.remove(uuid);
    }

    private void buildMobSelector(Player player, FakePlayer bot, int page) {
        UUID uuid = player.getUniqueId();
        int totalPages = Math.max(1, (int) Math.ceil(MOB_LIST.size() / (double) MOB_SLOTS));
        page = Math.min(page, totalPages - 1);
        mobSelectorPage.put(uuid, page);

        Set<String> selectedTypes = bot.getPveMobTypes();

        MobSelectorHolder holder = new MobSelectorHolder(uuid);
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(bot.getName()).color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text("ꜱᴇʟᴇᴄᴛ ᴍᴏʙꜱ").color(DARK_GRAY))
                .append(Component.text("  (" + (page + 1) + "/" + totalPages + ")")
                        .color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, MOB_GUI_SIZE, title);

        int startIdx = page * MOB_SLOTS;
        int endIdx = Math.min(startIdx + MOB_SLOTS, MOB_LIST.size());
        for (int i = startIdx; i < endIdx; i++) {
            MobDisplay mob = MOB_LIST.get(i);
            boolean selected = selectedTypes.contains(mob.type.name());
            inv.setItem(i - startIdx, buildMobItem(mob, selected));
        }

        inv.setItem(MOB_SLOT_BACK, buildMobBarItem(Material.ARROW, "◄  ʙᴀᴄᴋ ᴛᴏ ꜱᴇᴛᴛɪɴɢꜱ", ACCENT));

        inv.setItem(
                MOB_SLOT_PREV_PAGE,
                page > 0
                        ? buildMobBarItem(Material.MAGENTA_STAINED_GLASS_PANE, "◄  ᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ", COMING_SOON_COLOR)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        inv.setItem(47, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(48, glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        boolean isAllHostile = selectedTypes.isEmpty();
        ItemStack clearItem = new ItemStack(isAllHostile ? Material.NETHER_STAR : Material.STRUCTURE_VOID);
        ItemMeta clearMeta = clearItem.getItemMeta();
        if (isAllHostile) {
            clearMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            clearMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        clearMeta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✦  ᴀʟʟ ʜᴏꜱᴛɪʟᴇ ᴍᴏʙꜱ")
                        .color(isAllHostile ? SELECTED_GREEN : VALUE_YELLOW)
                        .decoration(TextDecoration.BOLD, true)));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(isAllHostile ? "◈  ᴄᴜʀʀᴇɴᴛʟʏ ᴀᴄᴛɪᴠᴇ" : "ᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴇᴀʀ ᴀʟʟ ᴛᴀʀɢᴇᴛꜱ")
                        .color(isAllHostile ? SELECTED_GREEN : DARK_GRAY)));
        clearMeta.lore(clearLore);
        clearItem.setItemMeta(clearMeta);
        inv.setItem(MOB_SLOT_CLEAR, clearItem);

        inv.setItem(50, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(51, glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        inv.setItem(
                MOB_SLOT_NEXT_PAGE,
                page < totalPages - 1
                        ? buildMobBarItem(Material.LIME_STAINED_GLASS_PANE, "▶  ɴᴇxᴛ ᴘᴀɢᴇ", ON_GREEN)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        inv.setItem(MOB_SLOT_CLOSE, buildCloseButton());

        inMobSelector.add(uuid);
        pendingRebuild.add(uuid);
        player.openInventory(inv);
        pendingRebuild.remove(uuid);
    }

    private void handleMobSelectorClick(Player player, MobSelectorHolder holder, int slot) {
        UUID uuid = player.getUniqueId();
        UUID botUuid = botSessions.get(uuid);
        if (botUuid == null) return;
        FakePlayer bot = manager.getByUuid(botUuid);
        if (bot == null) {
            player.closeInventory();
            return;
        }

        int page = mobSelectorPage.getOrDefault(uuid, 0);

        if (slot == MOB_SLOT_BACK) {
            playUiClick(player, 1.0f);
            inMobSelector.remove(uuid);
            mobSelectorPage.remove(uuid);
            pendingRebuild.add(uuid);
            build(player);
            pendingRebuild.remove(uuid);
            return;
        }

        if (slot == MOB_SLOT_CLOSE) {
            playUiClick(player, 0.8f);
            inMobSelector.remove(uuid);
            mobSelectorPage.remove(uuid);
            player.closeInventory();
            return;
        }

        if (slot == MOB_SLOT_PREV_PAGE && page > 0) {
            playUiClick(player, 1.0f);
            pendingRebuild.add(uuid);
            buildMobSelector(player, bot, page - 1);
            pendingRebuild.remove(uuid);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(MOB_LIST.size() / (double) MOB_SLOTS));
        if (slot == MOB_SLOT_NEXT_PAGE && page < totalPages - 1) {
            playUiClick(player, 1.0f);
            pendingRebuild.add(uuid);
            buildMobSelector(player, bot, page + 1);
            pendingRebuild.remove(uuid);
            return;
        }

        if (slot == MOB_SLOT_CLEAR) {
            bot.setPveMobTypes(new LinkedHashSet<>());
            manager.persistBotSettings(bot);
            restartPveIfActive(bot);
            playUiClick(player, 1.2f);
            sendActionBarConfirm(player, "ᴍᴏʙ ᴛᴀʀɢᴇᴛ", "ᴀʟʟ ʜᴏꜱᴛɪʟᴇ");
            pendingRebuild.add(uuid);
            buildMobSelector(player, bot, page);
            pendingRebuild.remove(uuid);
            return;
        }

        if (slot >= 0 && slot < MOB_SLOTS) {
            int mobIdx = page * MOB_SLOTS + slot;
            if (mobIdx >= MOB_LIST.size()) return;

            MobDisplay mob = MOB_LIST.get(mobIdx);
            boolean nowSelected = bot.togglePveMobType(mob.type.name());
            manager.persistBotSettings(bot);
            restartPveIfActive(bot);
            playUiClick(player, 1.2f);
            int count = bot.getPveMobTypes().size();
            String label = nowSelected
                    ? "+" + mob.displayName + " (" + count + " ꜱᴇʟᴇᴄᴛᴇᴅ)"
                    : "-" + mob.displayName + " (" + (count == 0 ? "ᴀʟʟ ʜᴏꜱᴛɪʟᴇ" : count + " ꜱᴇʟᴇᴄᴛᴇᴅ") + ")";
            sendActionBarConfirm(player, "ᴍᴏʙ ᴛᴀʀɢᴇᴛ", label);

            pendingRebuild.add(uuid);
            buildMobSelector(player, bot, page);
            pendingRebuild.remove(uuid);
        }
    }

    private void openFoodSelector(Player player, FakePlayer bot) {
        UUID uuid = player.getUniqueId();
        inFoodSelector.add(uuid);
        foodSelectorPage.put(uuid, 0);
        pendingRebuild.add(uuid);
        buildFoodSelector(player, bot, 0);
        pendingRebuild.remove(uuid);
    }

    private void buildFoodSelector(Player player, FakePlayer bot, int page) {
        UUID uuid = player.getUniqueId();
        List<BotFoods.FoodDef> foods = BotFoods.all();
        int totalPages = Math.max(1, (int) Math.ceil(foods.size() / (double) MOB_SLOTS));
        page = Math.min(Math.max(0, page), totalPages - 1);
        foodSelectorPage.put(uuid, page);

        Set<Material> selected = bot.getAutoEatFoods();

        FoodSelectorHolder holder = new FoodSelectorHolder(uuid);
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(bot.getName()).color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text("ᴀʟʟᴏᴡᴇᴅ ꜰᴏᴏᴅꜱ").color(DARK_GRAY))
                .append(Component.text("  (" + (page + 1) + "/" + totalPages + ")")
                        .color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, MOB_GUI_SIZE, title);

        int startIdx = page * MOB_SLOTS;
        int endIdx = Math.min(startIdx + MOB_SLOTS, foods.size());
        for (int i = startIdx; i < endIdx; i++) {
            BotFoods.FoodDef food = foods.get(i);
            inv.setItem(i - startIdx, buildFoodItem(food, selected.contains(food.material())));
        }

        inv.setItem(MOB_SLOT_BACK, buildMobBarItem(Material.ARROW, "◄  ʙᴀᴄᴋ ᴛᴏ ꜱᴇᴛᴛɪɴɢꜱ", ACCENT));
        inv.setItem(
                MOB_SLOT_PREV_PAGE,
                page > 0
                        ? buildMobBarItem(Material.MAGENTA_STAINED_GLASS_PANE, "◄  ᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ", COMING_SOON_COLOR)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(47, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(48, glassFiller(Material.GRAY_STAINED_GLASS_PANE));

        boolean anyFood = selected.isEmpty();
        ItemStack clearItem = new ItemStack(anyFood ? Material.NETHER_STAR : Material.STRUCTURE_VOID);
        ItemMeta clearMeta = clearItem.getItemMeta();
        if (anyFood) {
            clearMeta.addEnchant(Enchantment.UNBREAKING, 1, true);
            clearMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        clearMeta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✦  ᴀɴʏ ꜰᴏᴏᴅ")
                        .color(anyFood ? SELECTED_GREEN : VALUE_YELLOW)
                        .decoration(TextDecoration.BOLD, true)));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(anyFood ? "◈  ᴄᴜʀʀᴇɴᴛʟʏ ᴇᴀᴛɪɴɢ ᴀɴʏ ꜰᴏᴏᴅ" : "ᴄʟɪᴄᴋ ᴛᴏ ᴀʟʟᴏᴡ ᴀɴʏ ꜰᴏᴏᴅ")
                        .color(anyFood ? SELECTED_GREEN : DARK_GRAY)));
        clearMeta.lore(clearLore);
        clearItem.setItemMeta(clearMeta);
        inv.setItem(MOB_SLOT_CLEAR, clearItem);

        inv.setItem(50, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(51, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(
                MOB_SLOT_NEXT_PAGE,
                page < totalPages - 1
                        ? buildMobBarItem(Material.LIME_STAINED_GLASS_PANE, "▶  ɴᴇxᴛ ᴘᴀɢᴇ", ON_GREEN)
                        : glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(MOB_SLOT_CLOSE, buildCloseButton());

        inFoodSelector.add(uuid);
        pendingRebuild.add(uuid);
        player.openInventory(inv);
        pendingRebuild.remove(uuid);
    }

    private ItemStack buildFoodItem(BotFoods.FoodDef food, boolean selected) {
        ItemStack item = new ItemStack(food.material());
        ItemMeta meta = item.getItemMeta();
        if (selected) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(food.display())
                        .color(selected ? SELECTED_GREEN : WHITE)
                        .decoration(TextDecoration.BOLD, selected)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ʜᴜɴɢᴇʀ  ").color(DARK_GRAY))
                .append(Component.text("+" + food.nutrition()).color(VALUE_YELLOW)));
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(selected ? "◈  ꜱᴇʟᴇᴄᴛᴇᴅ" : "◈  ᴄʟɪᴄᴋ ᴛᴏ ᴀʟʟᴏᴡ")
                        .color(selected ? SELECTED_GREEN : DARK_GRAY)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void handleFoodSelectorClick(Player player, FoodSelectorHolder holder, int slot) {
        UUID uuid = player.getUniqueId();
        UUID botUuid = botSessions.get(uuid);
        if (botUuid == null) return;
        FakePlayer bot = manager.getByUuid(botUuid);
        if (bot == null) {
            player.closeInventory();
            return;
        }

        int page = foodSelectorPage.getOrDefault(uuid, 0);
        List<BotFoods.FoodDef> foods = BotFoods.all();
        int totalPages = Math.max(1, (int) Math.ceil(foods.size() / (double) MOB_SLOTS));

        if (slot == MOB_SLOT_BACK) {
            playUiClick(player, 1.0f);
            inFoodSelector.remove(uuid);
            foodSelectorPage.remove(uuid);
            pendingRebuild.add(uuid);
            build(player);
            pendingRebuild.remove(uuid);
            return;
        }
        if (slot == MOB_SLOT_CLOSE) {
            playUiClick(player, 0.8f);
            inFoodSelector.remove(uuid);
            foodSelectorPage.remove(uuid);
            player.closeInventory();
            return;
        }
        if (slot == MOB_SLOT_PREV_PAGE && page > 0) {
            playUiClick(player, 1.0f);
            pendingRebuild.add(uuid);
            buildFoodSelector(player, bot, page - 1);
            pendingRebuild.remove(uuid);
            return;
        }
        if (slot == MOB_SLOT_NEXT_PAGE && page < totalPages - 1) {
            playUiClick(player, 1.0f);
            pendingRebuild.add(uuid);
            buildFoodSelector(player, bot, page + 1);
            pendingRebuild.remove(uuid);
            return;
        }
        if (slot == MOB_SLOT_CLEAR) {
            bot.setAutoEatFoods(new LinkedHashSet<>());
            manager.persistBotSettings(bot);
            playUiClick(player, 1.2f);
            sendActionBarConfirm(player, "ᴀᴜᴛᴏ-ᴇᴀᴛ ꜰᴏᴏᴅꜱ", "ᴀɴʏ ꜰᴏᴏᴅ");
            pendingRebuild.add(uuid);
            buildFoodSelector(player, bot, page);
            pendingRebuild.remove(uuid);
            return;
        }
        if (slot >= 0 && slot < MOB_SLOTS) {
            int idx = page * MOB_SLOTS + slot;
            if (idx >= foods.size()) return;
            BotFoods.FoodDef food = foods.get(idx);
            boolean nowSelected = bot.toggleAutoEatFood(food.material());
            manager.persistBotSettings(bot);
            playUiClick(player, 1.2f);
            int count = bot.getAutoEatFoods().size();
            String label = nowSelected
                    ? "+" + food.display() + " (" + count + " ꜱᴇʟᴇᴄᴛᴇᴅ)"
                    : "-" + food.display() + " (" + (count == 0 ? "ᴀɴʏ ꜰᴏᴏᴅ" : count + " ꜱᴇʟᴇᴄᴛᴇᴅ") + ")";
            sendActionBarConfirm(player, "ᴀᴜᴛᴏ-ᴇᴀᴛ ꜰᴏᴏᴅ", label);
            pendingRebuild.add(uuid);
            buildFoodSelector(player, bot, page);
            pendingRebuild.remove(uuid);
        }
    }

    private void openShareSelector(Player player, FakePlayer bot) {
        UUID uuid = player.getUniqueId();
        pendingRebuild.add(uuid);
        buildShareSelector(player, bot);
        pendingRebuild.remove(uuid);
    }

    private void buildShareSelector(Player player, FakePlayer bot) {
        ShareSelectorHolder holder = new ShareSelectorHolder(player.getUniqueId());
        Component title = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("] ").color(DARK_GRAY))
                .append(Component.text(bot.getName()).color(ACCENT))
                .append(Component.text("  ·  ").color(DARK_GRAY))
                .append(Component.text("ꜱʜᴀʀᴇ ᴄᴏɴᴛʀᴏʟ").color(DARK_GRAY));

        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        int slot = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            if (manager.getByUuid(candidate.getUniqueId()) != null) continue;
            if (candidate.getUniqueId().equals(bot.getSpawnedByUuid())) continue;
            if (candidate.getUniqueId().equals(player.getUniqueId())) continue;
            inv.setItem(slot++, buildSharePlayerItem(candidate, bot.hasSharedController(candidate.getUniqueId())));
        }
        if (slot == 0) {
            ItemStack item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("ɴᴏ ᴏɴʟɪɴᴇ ᴘʟᴀʏᴇʀꜱ").color(OFF_RED));
            meta.lore(List.of(
                    Component.text("ᴘʟᴀʏᴇʀꜱ ᴍᴜꜱᴛ ʙᴇ ᴏɴʟɪɴᴇ ᴛᴏ ꜱʜᴀʀᴇ ᴄᴏɴᴛʀᴏʟ.").color(GRAY)));
            item.setItemMeta(meta);
            inv.setItem(22, item);
        }
        inv.setItem(45, buildMobBarItem(Material.ARROW, "◄  ʙᴀᴄᴋ ᴛᴏ ꜱᴇᴛᴛɪɴɢꜱ", ACCENT));
        for (int i = 46; i < 53; i++) inv.setItem(i, glassFiller(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(53, buildCloseButton());
        player.openInventory(inv);
    }

    private ItemStack buildSharePlayerItem(Player player, boolean shared) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setPlayerProfile(player.getPlayerProfile());
            if (shared) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.displayName(Component.text(player.getName())
                    .color(shared ? SELECTED_GREEN : ACCENT)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(shared ? "✔ ᴄᴀɴ ᴄᴏɴᴛʀᴏʟ ᴛʜɪꜱ ʙᴏᴛ" : "✘ ɴᴏ ᴄᴏɴᴛʀᴏʟ ᴀᴄᴄᴇꜱꜱ")
                            .color(shared ? SELECTED_GREEN : GRAY),
                    Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴛᴏɢɢʟᴇ").color(YELLOW)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void handleShareSelectorClick(Player player, ShareSelectorHolder holder, int slot) {
        UUID uuid = player.getUniqueId();
        UUID botUuid = botSessions.get(uuid);
        if (botUuid == null) return;
        FakePlayer bot = manager.getByUuid(botUuid);
        if (bot == null) {
            player.closeInventory();
            return;
        }
        if (!BotAccess.canShare(player, bot)) {
            player.sendMessage(Lang.get("no-permission"));
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            playUiClick(player, 1.0f);
            pendingRebuild.add(uuid);
            build(player);
            pendingRebuild.remove(uuid);
            return;
        }
        if (slot == 53) {
            playUiClick(player, 0.8f);
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= 45) return;
        ItemStack item = player.getOpenInventory().getTopInventory().getItem(slot);
        if (item == null || !item.hasItemMeta() || item.getItemMeta().displayName() == null) return;
        String targetName = PlainTextComponentSerializer.plainText()
                .serialize(item.getItemMeta().displayName());
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return;
        boolean shared = bot.hasSharedController(target.getUniqueId());
        if (shared) bot.removeSharedController(target.getUniqueId());
        else bot.addSharedController(target.getUniqueId());
        playUiClick(player, shared ? 0.85f : 1.2f);
        sendActionBarConfirm(player, "ꜱʜᴀʀᴇ ᴄᴏɴᴛʀᴏʟ", target.getName() + (shared ? " ʀᴇᴠᴏᴋᴇᴅ" : " ɢʀᴀɴᴛᴇᴅ"));
        pendingRebuild.add(uuid);
        buildShareSelector(player, bot);
        pendingRebuild.remove(uuid);
    }

    private ItemStack buildMobItem(MobDisplay mob, boolean selected) {
        ItemStack item = new ItemStack(mob.material);
        ItemMeta meta = item.getItemMeta();

        if (selected) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        TextColor nameColor = selected ? SELECTED_GREEN : WHITE;
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(mob.displayName).color(nameColor).decoration(TextDecoration.BOLD, selected)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ᴛʏᴘᴇ  ").color(DARK_GRAY))
                .append(Component.text(mob.category).color(GRAY)));
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ɪᴅ  ").color(DARK_GRAY))
                .append(Component.text(mob.type.name().toLowerCase()).color(GRAY)));
        lore.add(Component.empty());
        if (selected) {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("◈  ᴛᴀʀɢᴇᴛᴇᴅ").color(SELECTED_GREEN)));
            lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ʀᴇᴍᴏᴠᴇ"));
        } else {
            lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴀᴅᴅ ᴛᴀʀɢᴇᴛ"));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildMobBarItem(Material mat, String label, TextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(label).color(color).decoration(TextDecoration.BOLD, true)));
        item.setItemMeta(meta);
        return item;
    }

    private void dropBotInventory(FakePlayer fp) {
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

    private void dropBotXp(FakePlayer fp) {
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
    }

    private void resetBot(Player player, FakePlayer bot, boolean isOp) {
        fireSettingChange(bot, "reset", null, null);

        bot.setFrozen(false);
        bot.setRespawnOnDeath(Config.respawnOnDeath());
        bot.setHeadAiEnabled(true);
        bot.setSwimAiEnabled(Config.swimAiEnabled());
        bot.setChunkLoadRadius(-1);
        bot.setPickUpItemsEnabled(Config.bodyPickUpItems());
        bot.setPickUpXpEnabled(Config.bodyPickUpXp());

        bot.setAiPersonality(null);
        manager.applyPing(bot, -1);

        bot.setPveEnabled(false);
        var attackCmd = plugin.getAttackCommand();
        if (attackCmd != null) attackCmd.stopAttacking(bot.getUuid());
        bot.setPveRange(Config.attackMobDefaultRange());
        bot.setPvePriority(Config.attackMobDefaultPriority());
        bot.setPveSmartAttackMode(FakePlayer.PveSmartAttackMode.OFF);
        bot.setPveMobTypes(new LinkedHashSet<>());

        bot.setNavParkour(Config.pathfindingParkour());
        bot.setNavBreakBlocks(Config.pathfindingBreakBlocks());
        bot.setNavPlaceBlocks(Config.pathfindingPlaceBlocks());
        if (isOp) bot.setRightClickCommand(null);

        manager.persistBotSettings(bot);
        build(player);
        player.sendActionBar(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⟲ ").color(YELLOW))
                .append(Component.text("ʙᴏᴛ ꜱᴇᴛᴛɪɴɢꜱ  ").color(WHITE))
                .append(Component.text("ʀᴇꜱᴇᴛ ᴛᴏ ᴅᴇꜰᴀᴜʟᴛꜱ").color(YELLOW).decoration(TextDecoration.BOLD, true)));
    }

    private void openChatInput(Player player, FakePlayer bot, BotEntry entry) {
        UUID uuid = player.getUniqueId();
        int[] guiState = sessions.get(uuid);
        if (guiState == null) return;

        pendingChatInput.add(uuid);
        player.closeInventory();
        pendingChatInput.remove(uuid);

        String promptLabel;
        String currentVal;
        switch (entry.id()) {
            case "rename" -> {
                promptLabel = "ɴᴇᴡ ᴅɪꜱᴘʟᴀʏ ɴᴀᴍᴇ (ᴍᴀx " + RENAME_MAX_LENGTH + ")";
                currentVal = bot.getDisplayName();
            }
            case "auto_eat_threshold" -> {
                promptLabel = "ʜᴜɴɢᴇʀ ᴛʜʀᴇꜱʜᴏʟᴅ (0-19)";
                currentVal = bot.getAutoEatHungerThreshold() + " / 20";
            }
            case "chunk_load_radius" -> {
                int gMax = Config.chunkLoadingEnabled() ? Config.chunkLoadingRadius() : 0;
                promptLabel = "ʀᴀᴅɪᴜꜱ (-1=ɢʟᴏʙᴀʟ, 0=ᴏꜰꜰ, 1-" + gMax + ")";
                int cur = bot.getChunkLoadRadius();
                currentVal = cur == -1 ? "ɢʟᴏʙᴀʟ (" + gMax + ")" : cur == 0 ? "ᴅɪꜱᴀʙʟᴇᴅ" : cur + " ᴄʜᴜɴᴋꜱ";
            }
            case "pve_range" -> {
                promptLabel = "ᴅᴇᴛᴇᴄᴛ ʀᴀɴɢᴇ (1-64)";
                currentVal = (int) bot.getPveRange() + " ʙʟᴏᴄᴋꜱ";
            }
            case "rental_extend" -> {
                int min = Config.rentalMinHours();
                int max = Config.rentalMaxHours();
                promptLabel = "ʜᴏᴜʀꜱ ᴛᴏ ʙᴜʏ (" + min + "-" + max + ", " + Config.rentalPricePerHour() + "/ʜ)";
                currentVal = bot.isRented()
                        ? RentalPurchases.formatRemaining(
                                RentalPurchases.currentExpiry(bot) - System.currentTimeMillis())
                        : "ᴘᴇʀᴍᴀɴᴇɴᴛ";
            }
            case "left_click_interval" -> {
                promptLabel = "ᴛɪᴄᴋꜱ ("
                        + LeftClickCommand.MIN_INTERVAL_TICKS
                        + "-"
                        + LeftClickCommand.MAX_INTERVAL_TICKS
                        + ", 0 = ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ)";
                currentVal = bot.getLeftClickIntervalTicks() > 0
                        ? bot.getLeftClickIntervalTicks() + " ᴛɪᴄᴋꜱ"
                        : "ɢʟᴏʙᴀʟ (" + Config.leftClickIntervalTicks() + ")";
            }
            case "right_click_interval" -> {
                promptLabel = "ᴛɪᴄᴋꜱ ("
                        + RightClickCommand.MIN_INTERVAL_TICKS
                        + "-"
                        + RightClickCommand.MAX_INTERVAL_TICKS
                        + ", 0 = ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ)";
                currentVal = bot.getRightClickIntervalTicks() > 0
                        ? bot.getRightClickIntervalTicks() + " ᴛɪᴄᴋꜱ"
                        : "ɢʟᴏʙᴀʟ (" + Config.rightClickIntervalTicks() + ")";
            }
            default -> {
                promptLabel = entry.label();
                currentVal = "?";
            }
        }

        player.sendMessage(Component.empty());
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("┌─ ").color(DARK_GRAY))
                .append(Component.text("[").color(DARK_GRAY))
                .append(Component.text("ꜰᴘᴘ").color(ACCENT))
                .append(Component.text("]  ").color(DARK_GRAY))
                .append(Component.text("ʙᴏᴛ ꜱᴇᴛᴛɪɴɢꜱ").color(WHITE).decoration(TextDecoration.BOLD, true))
                .append(Component.text("  ·  ᴇᴅɪᴛ ᴠᴀʟᴜᴇ").color(DARK_GRAY)));
        player.sendMessage(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("│  ").color(DARK_GRAY))
                .append(Component.text(entry.label()).color(VALUE_YELLOW).decoration(TextDecoration.BOLD, true)));
        String[] descLines = entry.description().split("\\\\n|\n");
        for (String line : descLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                for (String wrapped : wrapText(trimmed, 42)) {
                    player.sendMessage(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("│  ").color(DARK_GRAY))
                            .append(Component.text(wrapped).color(GRAY)));
                }
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
                    ChatInputSes stale = chatSessions.remove(uuid);
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

        chatSessions.put(uuid, new ChatInputSes(entry.id(), bot.getUuid(), guiState.clone(), taskId));
    }

    private ItemStack buildEntryItem(BotEntry entry, FakePlayer bot, Player viewer) {

        if (entry.type() == BotEntryType.COMING_SOON) {
            ItemStack item = new ItemStack(entry.icon());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("⊘ ").color(COMING_SOON_COLOR))
                    .append(Component.text(entry.label())
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
            for (String line : entry.description().split("\\\\n|\n")) {
                if (!line.isBlank())
                    lore.add(Component.empty()
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text(line).color(GRAY)));
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
        boolean isToggle = entry.type() == BotEntryType.TOGGLE;
        boolean isDanger = entry.type() == BotEntryType.DANGER;
        boolean isOn = isToggle && getBoolValue(entry.id(), bot, viewer);

        TextColor nameColor = isDanger ? DANGER_RED : (isToggle ? (isOn ? ON_GREEN : OFF_RED) : ACCENT);
        ItemStack item = new ItemStack(dynamicIcon(entry, bot, viewer));
        ItemMeta meta = item.getItemMeta();

        if (isToggle && isOn) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(entry.label()).color(nameColor).decoration(TextDecoration.BOLD, true)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        TextColor valColor = isDanger ? DANGER_RED : (isToggle ? (isOn ? ON_GREEN : OFF_RED) : VALUE_YELLOW);
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ᴠᴀʟᴜᴇ  ").color(DARK_GRAY))
                .append(Component.text(valueString(entry, bot, viewer))
                        .color(valColor)
                        .decoration(TextDecoration.BOLD, true)));
        lore.add(Component.empty());
        for (String line : entry.description().split("\\\\n|\n")) {
            if (!line.isBlank())
                lore.add(Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(line).color(isDanger ? DANGER_RED : GRAY)));
        }
        lore.add(Component.empty());
        switch (entry.type()) {
            case TOGGLE -> lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴛᴏɢɢʟᴇ"));
            case CYCLE_PRIORITY -> lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴄʏᴄʟᴇ"));
            case ACTION -> lore.add(hint("✎ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴇᴅɪᴛ ɪɴ ᴄʜᴀᴛ"));
            case MOB_SELECTOR -> lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴏᴘᴇɴ ᴍᴏʙ ꜱᴇʟᴇᴄᴛᴏʀ"));
            case FOOD_SELECTOR -> lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴏᴘᴇɴ ꜰᴏᴏᴅ ʟɪꜱᴛ"));
            case IMMEDIATE -> lore.add(hint("◈ ", "ᴄʟɪᴄᴋ ᴛᴏ ᴄʟᴇᴀʀ"));
            case DANGER -> lore.add(dangerConfirmHint(entry, viewer));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component hint(String icon, String text) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(icon).color(ACCENT))
                .append(Component.text(text).color(DARK_GRAY));
    }

    /** Shows a live "confirm within Ns" countdown on the reset-all button while it's armed. */
    private Component dangerConfirmHint(BotEntry entry, Player viewer) {
        if ("reset_all".equals(entry.id()) && viewer != null) {
            Long confirmTime = pendingResetConfirm.get(viewer.getUniqueId());
            if (confirmTime != null) {
                long remainingMs = RESET_CONFIRM_WINDOW_MS - (System.currentTimeMillis() - confirmTime);
                long remainingS = Math.max(0, (remainingMs + 999) / 1000);
                return Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("◈ ").color(DANGER_RED))
                        .append(Component.text("ᴄᴏɴꜰɪʀᴍ ᴡɪᴛʜɪɴ " + remainingS + "ꜱ")
                                .color(YELLOW)
                                .decoration(TextDecoration.BOLD, true));
            }
        }
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("◈ ").color(DANGER_RED))
                .append(Component.text("ᴄʟɪᴄᴋ ᴛᴏ ᴄᴏɴꜰɪʀᴍ").color(DARK_GRAY));
    }

    private String valueString(BotEntry entry, FakePlayer bot, Player viewer) {
        if (entry.valueOverride() != null) return entry.valueOverride();
        return switch (entry.id()) {
            case "show_path" -> PathfindingDebugManager.isViewing(viewer.getUniqueId(), bot.getUuid())
                    ? "✔ ᴇɴᴀʙʟᴇᴅ"
                    : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "frozen" -> bot.isFrozen() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "respawn_on_death" -> bot.isRespawnOnDeath() ? "✔ ʀᴇꜱᴘᴀᴡɴ" : "✘ ᴅᴇꜱᴘᴀᴡɴ";
            case "head_ai_enabled" -> bot.isHeadAiEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "swim_ai_enabled" -> bot.isSwimAiEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "pickup_items" -> bot.isPickUpItemsEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "pickup_xp" -> bot.isPickUpXpEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "auto_milk" -> bot.isAutoMilkEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "prevent_bad_omen" -> bot.isPreventBadOmen() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "nav_parkour" -> bot.isNavParkour() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "nav_break_blocks" -> bot.isNavBreakBlocks() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "nav_place_blocks" -> bot.isNavPlaceBlocks() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "pve_enabled" -> pveModeLabel(bot);
            case "share_control" -> bot.getSharedControllers().size() + " ꜱʜᴀʀᴇᴅ";
            case "pve_range" -> (int) bot.getPveRange() + " ʙʟᴏᴄᴋꜱ";
            case "pve_priority" -> bot.getPvePriority() != null ? bot.getPvePriority() : "nearest";
            case "pve_mob_type" -> {
                Set<String> types = bot.getPveMobTypes();
                if (types.isEmpty()) yield "ᴀʟʟ ʜᴏꜱᴛɪʟᴇ";
                if (types.size() == 1) {
                    String t = types.iterator().next();
                    for (MobDisplay md : MOB_LIST) {
                        if (md.type.name().equals(t)) yield md.displayName;
                    }
                    yield t.toLowerCase();
                }
                yield types.size() + " ᴍᴏʙ ᴛʏᴘᴇꜱ";
            }
            case "chunk_load_radius" -> {
                int r = bot.getChunkLoadRadius();
                int gMax = Config.chunkLoadingEnabled() ? Config.chunkLoadingRadius() : 0;
                yield r == -1 ? "ɢʟᴏʙᴀʟ (" + gMax + ")" : r == 0 ? "ᴅɪꜱᴀʙʟᴇᴅ" : r + " ᴄʜᴜɴᴋꜱ";
            }
            case "rental_extend" -> bot.isRented()
                    ? RentalPurchases.formatRemaining(RentalPurchases.currentExpiry(bot) - System.currentTimeMillis())
                    : "ᴘᴇʀᴍᴀɴᴇɴᴛ";
            case "left_click_interval" -> bot.getLeftClickIntervalTicks() > 0
                    ? bot.getLeftClickIntervalTicks() + " ᴛɪᴄᴋꜱ"
                    : "ɢʟᴏʙᴀʟ (" + Config.leftClickIntervalTicks() + ")";
            case "right_click_interval" -> bot.getRightClickIntervalTicks() > 0
                    ? bot.getRightClickIntervalTicks() + " ᴛɪᴄᴋꜱ"
                    : "ɢʟᴏʙᴀʟ (" + Config.rightClickIntervalTicks() + ")";
            case "auto_eat" -> bot.isAutoEatEnabled() ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ";
            case "auto_eat_threshold" -> bot.getAutoEatHungerThreshold() + " / 20 ʜᴜɴɢᴇʀ";
            case "auto_eat_foods" -> {
                int n = bot.getAutoEatFoods().size();
                yield n == 0 ? "ᴀɴʏ ꜰᴏᴏᴅ" : n + " ꜱᴇʟᴇᴄᴛᴇᴅ";
            }
            case "reset_all" -> "⚠ ɢᴇɴᴇʀᴀʟ · ᴄʜᴀᴛ · ᴘᴠᴇ · ᴘᴀᴛʜ · ᴄᴍᴅꜱ";
            case "delete" -> bot.getName();
            case "skin_info" -> skinSummary(bot);
            case "skin_reroll" -> "ᴄʟɪᴄᴋ ᴛᴏ ʀᴏʟʟ";
            case "pve_status" -> pveStatusLabel(bot);
            default -> "?";
        };
    }

    /** Live combat state for the PVE status entry: off / scanning / fighting. */
    private String pveStatusLabel(FakePlayer bot) {
        if (!bot.isPveEnabled()) return "✘ ᴏꜰꜰ";
        var pve = plugin.getPveController();
        if (pve != null && pve.isEngaged(bot.getUuid())) return "⚔ ꜰɪɢʜᴛɪɴɢ";
        return "◌ ꜱᴄᴀɴɴɪɴɢ ꜰᴏʀ ᴛᴀʀɢᴇᴛꜱ";
    }

    /** One-line summary of the bot's current skin: source/rarity + detected player model. */
    private String skinSummary(FakePlayer bot) {
        SkinProfile skin = bot.getResolvedSkin();
        if (skin == null || !skin.isValid()) return "ᴠᴀɴɪʟʟᴀ ᴅᴇꜰᴀᴜʟᴛ";
        SkinModelDetector.SkinModel model = SkinModelDetector.detectFromTextureValue(skin.getValue());
        String modelLabel =
                switch (model) {
                    case SLIM -> "ꜱʟɪᴍ";
                    case CLASSIC -> "ᴄʟᴀꜱꜱɪᴄ";
                    case UNKNOWN -> "?";
                };
        return skinRarityLabel(skin.getSource()) + " · " + modelLabel;
    }

    private static String skinRarityLabel(String source) {
        if (source == null) return "ᴄᴜꜱᴛᴏᴍ";
        if (source.startsWith("pool:")) {
            String tail = source.substring(source.lastIndexOf(':') + 1);
            if ("main".equals(tail)) return "ᴍᴀɪɴ";
            if (tail.startsWith("1-in-")) return "✨ ʀᴀʀᴇ " + tail.replace("1-in-", "1/");
        }
        if (source.startsWith("despawn:")) return "ʀᴇꜱᴛᴏʀᴇᴅ";
        return "ᴄᴜꜱᴛᴏᴍ";
    }

    private boolean getBoolValue(String id, FakePlayer bot, Player viewer) {
        return switch (id) {
            case "show_path" -> PathfindingDebugManager.isViewing(viewer.getUniqueId(), bot.getUuid());
            case "frozen" -> bot.isFrozen();
            case "respawn_on_death" -> bot.isRespawnOnDeath();
            case "head_ai_enabled" -> bot.isHeadAiEnabled();
            case "swim_ai_enabled" -> bot.isSwimAiEnabled();
            case "pickup_items" -> bot.isPickUpItemsEnabled();
            case "pickup_xp" -> bot.isPickUpXpEnabled();
            case "auto_milk" -> bot.isAutoMilkEnabled();
            case "auto_eat" -> bot.isAutoEatEnabled();
            case "prevent_bad_omen" -> bot.isPreventBadOmen();
            case "nav_parkour" -> bot.isNavParkour();
            case "nav_break_blocks" -> bot.isNavBreakBlocks();
            case "nav_place_blocks" -> bot.isNavPlaceBlocks();
            case "pve_enabled" -> bot.isPveEnabled();
            case "pve_move" -> bot.isPveMoveToTarget();
            default -> false;
        };
    }

    private Material dynamicIcon(BotEntry entry, FakePlayer bot, Player viewer) {
        return switch (entry.id()) {
            case "show_path" -> PathfindingDebugManager.isViewing(viewer.getUniqueId(), bot.getUuid())
                    ? Material.FILLED_MAP
                    : Material.MAP;
            case "frozen" -> bot.isFrozen() ? Material.BLUE_ICE : Material.PACKED_ICE;
            case "respawn_on_death" -> bot.isRespawnOnDeath() ? Material.TOTEM_OF_UNDYING : Material.SKELETON_SKULL;
            case "head_ai_enabled" -> bot.isHeadAiEnabled() ? Material.PLAYER_HEAD : Material.SKELETON_SKULL;
            case "swim_ai_enabled" -> bot.isSwimAiEnabled() ? Material.WATER_BUCKET : Material.BUCKET;
            case "pickup_items" -> bot.isPickUpItemsEnabled() ? Material.HOPPER : Material.CHEST;
            case "pickup_xp" -> bot.isPickUpXpEnabled() ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE;
            case "auto_milk" -> bot.isAutoMilkEnabled() ? Material.MILK_BUCKET : Material.BUCKET;
            case "prevent_bad_omen" -> bot.isPreventBadOmen() ? Material.OMINOUS_BOTTLE : Material.GLASS_BOTTLE;
            case "nav_parkour" -> bot.isNavParkour() ? Material.SLIME_BALL : Material.RABBIT_FOOT;
            case "nav_break_blocks" -> bot.isNavBreakBlocks() ? Material.DIAMOND_PICKAXE : Material.IRON_PICKAXE;
            case "nav_place_blocks" -> bot.isNavPlaceBlocks() ? Material.GRASS_BLOCK : Material.DIRT;
            case "pve_enabled" -> switch (bot.getPveSmartAttackMode()) {
                case OFF -> Material.WOODEN_SWORD;
                case ON_NO_MOVE -> Material.IRON_SWORD;
                case ON_MOVE -> Material.DIAMOND_SWORD;
            };
            case "share_control" -> Material.PLAYER_HEAD;
            case "pve_mob_type" -> {
                Set<String> types = bot.getPveMobTypes();
                if (types.isEmpty()) yield Material.ZOMBIE_HEAD;
                if (types.size() == 1) {
                    String t = types.iterator().next();
                    for (MobDisplay md : MOB_LIST) {
                        if (md.type.name().equals(t)) yield md.material;
                    }
                }
                yield Material.ZOMBIE_HEAD;
            }
            case "chunk_load_radius" -> bot.getChunkLoadRadius() == 0 ? Material.STRUCTURE_VOID : Material.MAP;
            case "pve_status" -> {
                var pve = plugin.getPveController();
                if (!bot.isPveEnabled()) yield Material.GRAY_DYE;
                yield pve != null && pve.isEngaged(bot.getUuid()) ? Material.DIAMOND_SWORD : Material.SPYGLASS;
            }
            default -> entry.icon();
        };
    }

    private ItemStack buildCategoryTab(BotCategory cat, boolean active) {
        ItemStack item = new ItemStack(active ? cat.activeMat() : cat.inactiveMat());
        ItemMeta meta = item.getItemMeta();
        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(cat.label()).color(ACCENT).decoration(TextDecoration.BOLD, active)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(active ? "◈  ᴄᴜʀʀᴇɴᴛʟʏ ᴠɪᴇᴡɪɴɢ" : "ᴄʟɪᴄᴋ ᴛᴏ ꜱᴡɪᴛᴄʜ")
                        .color(active ? ON_GREEN : DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCatArrow(boolean isNext) {
        Material mat = isNext ? Material.LIME_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE;
        TextColor col = isNext ? ON_GREEN : COMING_SOON_COLOR;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(isNext ? "▶" : "◄").color(col).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("ꜱᴄʀᴏʟʟ ᴄᴀᴛᴇɢᴏʀɪᴇꜱ " + (isNext ? "ꜰᴏʀᴡᴀʀᴅ" : "ʙᴀᴄᴋᴡᴀʀᴅ") + ".")
                        .color(DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildResetButton() {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("⟲  ʀᴇꜱᴇᴛ ʙᴏᴛ").color(YELLOW)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ʀᴇꜱᴇᴛ ᴀʟʟ ʙᴏᴛ ꜱᴇᴛᴛɪɴɢꜱ").color(GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴛᴏ ᴅᴇꜰᴀᴜʟᴛ ᴠᴀʟᴜᴇꜱ.").color(GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    /** Main-screen footer button: back to the bot list (or close without list access); shift = main menu. */
    private ItemStack buildBackButton(Player viewer) {
        boolean toList = Perm.has(viewer, Perm.LIST);
        ItemStack item = new ItemStack(toList ? Material.SPECTRAL_ARROW : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(toList ? "◄  ʙᴀᴄᴋ ᴛᴏ ʙᴏᴛ ʟɪꜱᴛ" : "✕  ᴄʟᴏꜱᴇ")
                        .color(toList ? ACCENT : OFF_RED)
                        .decoration(TextDecoration.BOLD, true)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(toList ? "ᴄʟɪᴄᴋ - ʙᴀᴄᴋ ᴛᴏ ᴛʜᴇ ʙᴏᴛ ʟɪꜱᴛ" : "ᴄʟɪᴄᴋ - ᴄʟᴏꜱᴇ ᴛʜᴇ ᴍᴇɴᴜ")
                        .color(DARK_GRAY)));
        if (GuiKit.hasMainMenu()) {
            lore.add(Component.empty()
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("ꜱʜɪꜰᴛ-ᴄʟɪᴄᴋ - ᴍᴀɪɴ ᴍᴇɴᴜ").color(DARK_GRAY)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCloseButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✕  ᴄʟᴏꜱᴇ").color(OFF_RED).decoration(TextDecoration.BOLD, true)));
        meta.lore(List.of(
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ᴄʟɪᴄᴋ - ᴄʟᴏꜱᴇ ᴛʜᴇ ᴍᴇɴᴜ").color(DARK_GRAY)),
                Component.empty()
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("ꜱʜɪꜰᴛ-ᴄʟɪᴄᴋ - ʙᴀᴄᴋ ᴛᴏ ᴛʜᴇ ʙᴏᴛ ʟɪꜱᴛ")
                                .color(DARK_GRAY))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack glassFiller(Material mat) {
        return GuiKit.glassFiller(mat);
    }

    private static List<BotEntry> visibleEntries(BotCategory cat, boolean isOp) {
        if (isOp) return cat.entries();
        return cat.entries().stream().filter(e -> !e.opOnly()).toList();
    }

    private void cleanup(UUID uuid) {
        UUID botUuid = botSessions.get(uuid);
        if (botUuid != null) {
            releaseBotLock(botUuid, uuid);
            resumeBotAfterEditing(botUuid);
        }
        sessions.remove(uuid);
        botSessions.remove(uuid);
        pendingResetConfirm.remove(uuid);
        cancelConfirmCountdown(uuid);
    }

    private boolean acquireBotLock(UUID botUuid, UUID viewerUuid) {
        UUID owner = botLocks.putIfAbsent(botUuid, viewerUuid);
        return owner == null || owner.equals(viewerUuid);
    }

    private void releaseBotLock(UUID botUuid, UUID viewerUuid) {
        botLocks.remove(botUuid, viewerUuid);
    }

    private void releaseAllEditors(UUID botUuid) {
        botLocks.remove(botUuid);
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(botSessions).entrySet()) {
            if (!botUuid.equals(entry.getValue())) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                pendingDelete.add(entry.getKey());
                viewer.closeInventory();
            }
            cleanup(entry.getKey());
            pendingDelete.remove(entry.getKey());
        }
    }

    private void pauseBotForEditing(FakePlayer bot) {
        UUID botUuid = bot.getUuid();
        editPauseCounts.merge(botUuid, 1, Integer::sum);
        bot.setInventoryOpen(true);
        Player player = bot.getPlayer();
        if (player != null && player.isOnline()) {
            manager.lockForAction(botUuid, player.getLocation());
            NmsPlayerSpawner.setMovementForward(player, 0f);
            player.setSprinting(false);
            player.setVelocity(new Vector(0, 0, 0));
        }
    }

    private void resumeBotAfterEditing(UUID botUuid) {
        Integer count = editPauseCounts.get(botUuid);
        if (count != null && count > 1) {
            editPauseCounts.put(botUuid, count - 1);
            return;
        }
        editPauseCounts.remove(botUuid);
        manager.unlockAction(botUuid);
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp != null) fp.setInventoryOpen(false);
    }

    private boolean isOp(Player player) {
        return Perm.has(player, Perm.OP);
    }

    private void sendActionBarConfirm(Player player, String label, String value) {
        player.sendActionBar(Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("✔ ").color(ON_GREEN))
                .append(Component.text(label + "  ").color(WHITE))
                .append(Component.text("→  ").color(DARK_GRAY))
                .append(Component.text(value).color(VALUE_YELLOW).decoration(TextDecoration.BOLD, true)));
    }

    private static void playUiClick(Player player, float pitch) {
        GuiKit.playUiClick(player, pitch);
    }

    private BotCategory general() {
        int globalMax = Config.chunkLoadingEnabled() ? Config.chunkLoadingRadius() : 0;
        return new BotCategory(
                "⚙ ɢᴇɴᴇʀᴀʟ",
                Material.COMPARATOR,
                Material.GRAY_DYE,
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.action(
                                "rename",
                                "ʀᴇɴᴀᴍᴇ ʙᴏᴛ",
                                "ꜱᴇᴛ ᴛʜɪꜱ ʙᴏᴛ'ꜱ ᴅɪꜱᴘʟᴀʏ ɴᴀᴍᴇ.\n"
                                        + "ꜱʜᴏᴡɴ ᴀʙᴏᴠᴇ ɪᴛꜱ ʜᴇᴀᴅ, ɪɴ ᴛʜᴇ ᴛᴀʙ\n"
                                        + "ʟɪꜱᴛ ᴀɴᴅ ɪɴ ᴄᴏᴍᴍᴀɴᴅ ᴏᴜᴛᴘᴜᴛ.\n"
                                        + "ɪᴅᴇɴᴛɪᴛʏ (ᴜᴜɪᴅ) ꜱᴛᴀʏꜱ ᴛʜᴇ ꜱᴀᴍᴇ.",
                                Material.NAME_TAG,
                                false),
                        BotEntry.toggle(
                                "frozen",
                                "ꜰʀᴏᴢᴇɴ",
                                "ʙᴏᴛ ᴄᴀɴɴᴏᴛ ᴍᴏᴠᴇ ᴡʜᴇɴ ꜰʀᴏᴢᴇɴ.\nᴛᴏɢɢʟᴇ ᴛᴏ ᴘᴀᴜꜱᴇ ᴀʟʟ ᴍᴏᴠᴇᴍᴇɴᴛ.",
                                Material.PACKED_ICE,
                                false),
                        BotEntry.toggle(
                                "respawn_on_death",
                                "ʀᴇꜱᴘᴀᴡɴ ᴏɴ ᴅᴇᴀᴛʜ",
                                "ᴛʜɪꜱ ʙᴏᴛ ʀᴇꜱᴘᴀᴡɴꜱ ᴀꜰᴛᴇʀ ᴅᴇᴀᴛʜ ᴡʜᴇɴ ᴇɴᴀʙʟᴇᴅ.\n" + "ᴅɪꜱᴀʙʟᴇᴅ = ᴅᴇᴀᴛʜ ᴅᴇꜱᴘᴀᴡɴꜱ ᴛʜᴇ ʙᴏᴛ.",
                                Material.TOTEM_OF_UNDYING,
                                false),
                        BotEntry.toggle(
                                "head_ai_enabled",
                                "ʜᴇᴀᴅ ᴀɪ (ʟᴏᴏᴋ ᴀᴛ ᴘʟᴀʏᴇʀ)",
                                "ʙᴏᴛ ꜱᴍᴏᴏᴛʜʟʏ ʀᴏᴛᴀᴛᴇꜱ ᴛᴏᴡᴀʀᴅ ᴘʟᴀʏᴇʀꜱ ᴡʜᴇɴ ᴇɴᴀʙʟᴇᴅ.\n"
                                        + "ᴅɪꜱᴀʙʟᴇ ᴛᴏ ᴋᴇᴇᴘ ʜᴇᴀᴅ ꜱᴛᴀᴛɪᴏɴᴀʀʏ.",
                                Material.PLAYER_HEAD,
                                false),
                        BotEntry.action(
                                "chunk_load_radius",
                                "ᴄʜᴜɴᴋ ʀᴀᴅɪᴜꜱ",
                                "ʜᴏᴡ ᴍᴀɴʏ ᴄʜᴜɴᴋꜱ ᴛʜɪꜱ ʙᴏᴛ ʟᴏᴀᴅꜱ.\n"
                                        + "-1 = ꜰᴏʟʟᴏᴡ ɢʟᴏʙᴀʟ ᴄᴏɴꜰɪɢ\n"
                                        + "0  = ᴅɪꜱᴀʙʟᴇᴅ ꜰᴏʀ ᴛʜɪꜱ ʙᴏᴛ\n"
                                        + "1-"
                                        + globalMax
                                        + " = ꜰɪxᴇᴅ ʀᴀᴅɪᴜꜱ (ᴄᴀᴘᴘᴇᴅ ᴀᴛ ɢʟᴏʙᴀʟ ᴍᴀx)",
                                Material.MAP,
                                false),
                        BotEntry.toggle(
                                "pickup_items",
                                "ᴘɪᴄᴋ ᴜᴘ ɪᴛᴇᴍꜱ",
                                "ᴛʜɪꜱ ʙᴏᴛ ᴘɪᴄᴋꜱ ᴜᴘ ɪᴛᴇᴍ ᴇɴᴛɪᴛɪᴇꜱ\nɪɴᴛᴏ ɪᴛꜱ ɪɴᴠᴇɴᴛᴏʀʏ ᴡʜᴇɴ ᴇɴᴀʙʟᴇᴅ.",
                                Material.HOPPER,
                                false),
                        BotEntry.toggle(
                                "pickup_xp",
                                "ᴘɪᴄᴋ ᴜᴘ xᴘ",
                                "ᴛʜɪꜱ ʙᴏᴛ ᴄᴏʟʟᴇᴄᴛꜱ ᴇxᴘᴇʀɪᴇɴᴄᴇ ᴏʀʙꜱ\n" + "ᴡʜᴇɴ ᴇɴᴀʙʟᴇᴅ. /ꜰᴘᴘ xᴘ ᴄᴏᴏʟᴅᴏᴡɴ ꜱᴛɪʟʟ ᴀᴘᴘʟɪᴇꜱ.",
                                Material.EXPERIENCE_BOTTLE,
                                false),
                        BotEntry.toggle(
                                "auto_milk",
                                "ᴀᴜᴛᴏ ᴍɪʟᴋ",
                                "ᴀᴜᴛᴏᴍᴀᴛɪᴄᴀʟʟʏ ᴄᴜʀᴇ ʜᴀʀᴍꜰᴜʟ ᴇꜰꜰᴇᴄᴛꜱ\n"
                                        + "(ᴘᴏɪꜱᴏɴ, ᴡɪᴛʜᴇʀ, ꜱʟᴏᴡɴᴇꜱꜱ, ᴇᴛᴄ.)\n"
                                        + "ɢʟᴏʙᴀʟ: "
                                        + (Config.autoMilkEnabled() ? "ᴇɴᴀʙʟᴇᴅ" : "ᴅɪꜱᴀʙʟᴇᴅ"),
                                Material.MILK_BUCKET,
                                false),
                        BotEntry.toggle(
                                "prevent_bad_omen",
                                "ʙʟᴏᴄᴋ ʙᴀᴅ ᴏᴍᴇɴ",
                                "ᴘʀᴇᴠᴇɴᴛ ʙᴀᴅ ᴏᴍᴇɴ, ʀᴀɪᴅ ᴏᴍᴇɴ\n"
                                        + "ᴀɴᴅ ᴛʀɪᴀʟ ᴏᴍᴇɴ ᴇꜰꜰᴇᴄᴛꜱ.\n"
                                        + "ᴘʀᴇᴠᴇɴᴛꜱ ʙᴏᴛꜱ ꜰʀᴏᴍ ᴛʀɪɢɢᴇʀɪɴɢ ʀᴀɪᴅꜱ.\n"
                                        + "ɢʟᴏʙᴀʟ: "
                                        + (Config.preventBadOmen() ? "ᴇɴᴀʙʟᴇᴅ" : "ᴅɪꜱᴀʙʟᴇᴅ"),
                                Material.OMINOUS_BOTTLE,
                                false),
                        BotEntry.immediate(
                                "share_control",
                                "ꜱʜᴀʀᴇ ᴄᴏɴᴛʀᴏʟ",
                                "ᴏᴘᴇɴ ᴀ ʀᴇᴀʟ-ᴘʟᴀʏᴇʀ ꜱᴇʟᴇᴄᴛᴏʀ\n"
                                        + "ᴛᴏ ɢʀᴀɴᴛ ᴏʀ ʀᴇᴠᴏᴋᴇ ᴄᴏɴᴛʀᴏʟ.\n"
                                        + "ᴏɴʟʏ ᴏᴡɴᴇʀꜱ ᴀɴᴅ ᴀᴅᴍɪɴꜱ ᴄᴀɴ ꜱʜᴀʀᴇ.",
                                Material.PLAYER_HEAD,
                                false),
                        BotEntry.action(
                                "rental_extend",
                                "ʀᴇɴᴛᴀʟ ᴛɪᴍᴇ",
                                "ᴛʜɪꜱ ʙᴏᴛ'ꜱ ʀᴇᴍᴀɪɴɪɴɢ ᴘᴀɪᴅ ᴛɪᴍᴇ.\n"
                                        + "ᴄʟɪᴄᴋ ᴛᴏ ʙᴜʏ ᴍᴏʀᴇ ʜᴏᴜʀꜱ ᴡɪᴛʜ ʏᴏᴜʀ\n"
                                        + "ᴇᴄᴏɴᴏᴍʏ ʙᴀʟᴀɴᴄᴇ (" + Config.rentalPricePerHour() + "/ʜ).\n"
                                        + "ɴᴏᴛ ʀᴇɴᴛᴇᴅ = ᴘᴇʀᴍᴀɴᴇɴᴛ, ɴᴇᴠᴇʀ ᴇxᴘɪʀᴇꜱ.",
                                Material.CLOCK,
                                false),
                        BotEntry.action(
                                "left_click_interval",
                                "ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ",
                                "ᴛɪᴄᴋꜱ ʙᴇᴛᴡᴇᴇɴ ʙʟᴏᴄᴋ ʙʀᴇᴀᴋꜱ ᴡʜɪʟᴇ\n"
                                        + "ʀᴇᴘᴇᴀᴛ/ʜᴏʟᴅ ᴍɪɴɪɴɢ. ᴇɴᴛɪᴛʏ ᴀᴛᴛᴀᴄᴋꜱ ᴀʀᴇ\n"
                                        + "ᴜɴᴀꜰꜰᴇᴄᴛᴇᴅ (ᴡᴇᴀᴘᴏɴ ꜱᴘᴇᴇᴅ ᴘᴀᴄᴇꜱ ᴛʜᴏꜱᴇ).\n"
                                        + "ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ: "
                                        + Config.leftClickIntervalTicks()
                                        + " ᴛɪᴄᴋꜱ.",
                                Material.IRON_PICKAXE,
                                false),
                        BotEntry.action(
                                "right_click_interval",
                                "ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ",
                                "ᴛɪᴄᴋꜱ ʙᴇᴛᴡᴇᴇɴ ʜᴇʟᴅ ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ᴘᴜʟꜱᴇꜱ.\n"
                                        + "ᴠᴀɴɪʟʟᴀ'ꜱ ᴏᴡɴ ᴄʟɪᴇɴᴛ ᴜꜱᴇꜱ ~4.\n"
                                        + "ɢʟᴏʙᴀʟ ᴅᴇꜰᴀᴜʟᴛ: "
                                        + Config.rightClickIntervalTicks()
                                        + " ᴛɪᴄᴋꜱ.",
                                Material.IRON_HOE,
                                false)));
    }

    private BotCategory pve() {
        return new BotCategory(
                "🗡 ᴘᴠᴇ",
                Material.IRON_SWORD,
                Material.STONE_SWORD,
                Material.LIME_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.immediate(
                                "pve_status",
                                "ᴘᴠᴇ ꜱᴛᴀᴛᴜꜱ",
                                "ʟɪᴠᴇ ᴄᴏᴍʙᴀᴛ ꜱᴛᴀᴛᴇ ᴏꜰ ᴛʜɪꜱ ʙᴏᴛ:\n"
                                        + "ᴏꜰꜰ / ꜱᴄᴀɴɴɪɴɢ / ꜰɪɢʜᴛɪɴɢ.\n"
                                        + "ᴄʟɪᴄᴋ ᴛᴏ ʀᴇꜰʀᴇꜱʜ.",
                                Material.SPYGLASS,
                                false),
                        BotEntry.cyclePveMode(
                                "pve_enabled",
                                "ꜱᴍᴀʀᴛ ᴀᴛᴛᴀᴄᴋ",
                                "ᴄʏᴄʟᴇꜱ ʙᴇᴛᴡᴇᴇɴ ᴏꜰꜰ, ᴏɴ ᴡɪᴛʜᴏᴜᴛ\n"
                                        + "ᴍᴏᴠᴇᴍᴇɴᴛ, ᴀɴᴅ ᴏɴ ᴡɪᴛʜ ᴍᴏᴠᴇᴍᴇɴᴛ.\n"
                                        + "ꜱᴍᴀʀᴛ ᴀᴛᴛᴀᴄᴋ ᴜꜱᴇꜱ ᴡᴇᴀᴘᴏɴ ᴄᴏᴏʟᴅᴏᴡɴꜱ\n"
                                        + "ᴀɴᴅ ꜱᴍᴏᴏᴛʜ ʀᴏᴛᴀᴛɪᴏɴ.",
                                Material.IRON_SWORD,
                                false),
                        BotEntry.mobSelector(
                                "pve_mob_type",
                                "ꜱᴇʟᴇᴄᴛ ᴛᴀʀɢᴇᴛ ᴍᴏʙꜱ",
                                "ᴏᴘᴇɴ ᴀ ᴠɪꜱᴜᴀʟ ꜱᴇʟᴇᴄᴛᴏʀ ᴛᴏ ᴘɪᴄᴋ\n"
                                        + "ᴡʜɪᴄʜ ᴍᴏʙ ᴛʏᴘᴇꜱ ᴛʜᴇ ʙᴏᴛ ᴛᴀʀɢᴇᴛꜱ.\n"
                                        + "ᴄʟɪᴄᴋ ᴛᴏ ᴛᴏɢɢʟᴇ ᴍᴜʟᴛɪᴘʟᴇ ᴍᴏʙꜱ.\n"
                                        + "'ᴀʟʟ ʜᴏꜱᴛɪʟᴇ' = ᴄʟᴇᴀʀ ᴀʟʟ.",
                                Material.ZOMBIE_HEAD,
                                false),
                        BotEntry.action(
                                "pve_range",
                                "ᴅᴇᴛᴇᴄᴛ ʀᴀɴɢᴇ",
                                "ʜᴏᴡ ꜰᴀʀ (ɪɴ ʙʟᴏᴄᴋꜱ) ᴛʜᴇ ʙᴏᴛ ꜱᴄᴀɴꜱ\n"
                                        + "ꜰᴏʀ ᴍᴏʙꜱ ᴛᴏ ᴀᴛᴛᴀᴄᴋ.\n"
                                        + "ʀᴀɴɢᴇ: 1 – 64 ʙʟᴏᴄᴋꜱ.",
                                Material.SPYGLASS,
                                false),
                        BotEntry.cyclePriority(
                                "pve_priority",
                                "ᴛᴀʀɢᴇᴛ ᴘʀɪᴏʀɪᴛʏ",
                                "ʜᴏᴡ ᴛʜᴇ ʙᴏᴛ ᴄʜᴏᴏꜱᴇꜱ ɪᴛꜱ ᴛᴀʀɢᴇᴛ.\n" + "ᴄʏᴄʟᴇꜱ: nearest ↔ lowest-health",
                                Material.COMPARATOR,
                                false)));
    }

    private BotCategory pathfinding() {
        return new BotCategory(
                "🧭 ᴘᴀᴛʜꜰɪɴᴅɪɴɢ",
                Material.COMPASS,
                Material.CLOCK,
                Material.CYAN_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.toggle(
                                "show_path",
                                "ꜱʜᴏᴡ ᴘᴀᴛʜ (ᴅᴇʙᴜɢ)",
                                "ʀᴇɴᴅᴇʀꜱ ᴀ ᴘᴀʀᴛɪᴄʟᴇ ᴛʀᴀɪʟ ᴀʟᴏɴɢ ᴛʜɪꜱ\n"
                                        + "ʙᴏᴛ'ꜱ ᴀᴄᴛɪᴠᴇ ᴘᴀᴛʜꜰɪɴᴅɪɴɢ ʀᴏᴜᴛᴇ,\n"
                                        + "ᴠɪꜱɪʙʟᴇ ᴏɴʟʏ ᴛᴏ ʏᴏᴜ (ʙᴀʀɪᴛᴏɴᴇ-ꜱᴛʏʟᴇ).\n"
                                        + "ᴏʀᴀɴɢᴇ = ɴᴇxᴛ ᴡᴀʏᴘᴏɪɴᴛ, ʀᴇᴅ = ᴅᴇꜱᴛɪɴᴀᴛɪᴏɴ.",
                                Material.MAP,
                                false),
                        BotEntry.toggle(
                                "nav_parkour",
                                "ᴘᴀʀᴋᴏᴜʀ",
                                "ᴀʟʟᴏᴡꜱ ᴛʜᴇ ᴘᴀᴛʜꜰɪɴᴅᴇʀ ᴛᴏ ᴘʟᴀɴ ꜱʜᴏʀᴛ\nɢᴀᴘ ᴊᴜᴍᴘꜱ ɪɴꜱᴛᴇᴀᴅ ᴏꜰ ᴀʟᴡᴀʏꜱ ʀᴏᴜᴛɪɴɢ ᴀʀᴏᴜɴᴅ.",
                                Material.SLIME_BALL,
                                false),
                        BotEntry.toggle(
                                "nav_break_blocks",
                                "ʙʀᴇᴀᴋ ʙʟᴏᴄᴋꜱ",
                                "ᴀʟʟᴏᴡꜱ ᴛʜᴇ ʙᴏᴛ ᴛᴏ ᴍɪɴᴇ ᴛʜʀᴏᴜɢʜ\nᴏʙꜱᴛʀᴜᴄᴛɪɴɢ ʙʟᴏᴄᴋꜱ ᴡʜɪʟᴇ ɴᴀᴠɪɢᴀᴛɪɴɢ.",
                                Material.DIAMOND_PICKAXE,
                                false),
                        BotEntry.toggle(
                                "nav_place_blocks",
                                "ᴘʟᴀᴄᴇ ʙʟᴏᴄᴋꜱ (ʙʀɪᴅɢᴇ)",
                                "ᴀʟʟᴏᴡꜱ ᴛʜᴇ ʙᴏᴛ ᴛᴏ ʙʀɪᴅɢᴇ ᴏᴠᴇʀ ɢᴀᴘꜱ\nʙʏ ᴘʟᴀᴄɪɴɢ ʙʟᴏᴄᴋꜱ ᴡʜɪʟᴇ ɴᴀᴠɪɢᴀᴛɪɴɢ.",
                                Material.GRASS_BLOCK,
                                false)));
    }

    private BotCategory skin() {
        return new BotCategory(
                "🎨 ꜱᴋɪɴ",
                Material.PAINTING,
                Material.ITEM_FRAME,
                Material.MAGENTA_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.immediate(
                                "skin_info",
                                "ᴄᴜʀʀᴇɴᴛ ꜱᴋɪɴ",
                                "ᴛʜᴇ ꜱᴋɪɴ ᴛʜɪꜱ ʙᴏᴛ ɪꜱ ᴡᴇᴀʀɪɴɢ:\n"
                                        + "ꜱᴏᴜʀᴄᴇ (ᴍᴀɪɴ / ʀᴀʀᴇ ᴛɪᴇʀ / ᴄᴜꜱᴛᴏᴍ) ᴀɴᴅ\n"
                                        + "ᴘʟᴀʏᴇʀ ᴍᴏᴅᴇʟ (ꜱʟɪᴍ/ᴄʟᴀꜱꜱɪᴄ, ᴀᴜᴛᴏ-ᴅᴇᴛᴇᴄᴛᴇᴅ).",
                                Material.PAINTING,
                                false),
                        BotEntry.immediate(
                                "skin_reroll",
                                "ʀᴇ-ʀᴏʟʟ ꜱᴋɪɴ",
                                "ʀᴏʟʟꜱ ᴀ ꜰʀᴇꜱʜ ꜱᴋɪɴ ꜰʀᴏᴍ ᴛʜᴇ ᴘᴏᴏʟꜱ -\n"
                                        + "ꜱᴀᴍᴇ ʀᴀʀᴇ-ᴛɪᴇʀ ᴄʜᴀɴᴄᴇꜱ ᴀꜱ ᴀ ꜰʀᴇꜱʜ ꜱᴘᴀᴡɴ.\n"
                                        + "ᴛʜᴇ ɴᴇᴡ ꜱᴋɪɴ ᴘᴇʀꜱɪꜱᴛꜱ ʟɪᴋᴇ ᴀ ʀᴏʟʟᴇᴅ ᴏɴᴇ.",
                                Material.EXPERIENCE_BOTTLE,
                                false)));
    }

    private BotCategory autoEat() {
        return new BotCategory(
                "🍖 ᴀᴜᴛᴏ-ᴇᴀᴛ",
                Material.COOKED_BEEF,
                Material.BEEF,
                Material.ORANGE_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.toggle(
                                "auto_eat",
                                "ᴀᴜᴛᴏ-ᴇᴀᴛ",
                                "ᴡʜᴇɴ ᴇɴᴀʙʟᴇᴅ, ᴛʜᴇ ʙᴏᴛ ᴇᴀᴛꜱ ꜰᴏᴏᴅ ꜰʀᴏᴍ\n"
                                        + "ɪᴛꜱ ɪɴᴠᴇɴᴛᴏʀʏ ᴡʜᴇɴ ʜᴜɴɢʀʏ. ɪᴛ ᴘᴀᴜꜱᴇꜱ\n"
                                        + "ᴡʜᴀᴛᴇᴠᴇʀ ɪᴛ'ꜱ ᴅᴏɪɴɢ, ᴇᴀᴛꜱ, ᴛʜᴇɴ ꜱᴡɪᴛᴄʜᴇꜱ\n"
                                        + "ʙᴀᴄᴋ ᴛᴏ ᴡʜᴀᴛ ɪᴛ ᴡᴀꜱ ʜᴏʟᴅɪɴɢ.",
                                Material.COOKED_CHICKEN,
                                false),
                        BotEntry.action(
                                "auto_eat_threshold",
                                "ʜᴜɴɢᴇʀ ᴛʜʀᴇꜱʜᴏʟᴅ",
                                "ᴇᴀᴛ ᴡʜᴇɴ ʜᴜɴɢᴇʀ ꜰᴀʟʟꜱ ᴛᴏ ᴏʀ ʙᴇʟᴏᴡ\n"
                                        + "ᴛʜɪꜱ ᴠᴀʟᴜᴇ (0-19, ᴡʜᴇʀᴇ 20 ɪꜱ ꜰᴜʟʟ).\n"
                                        + "ʜɪɢʜᴇʀ = ᴇᴀᴛꜱ ꜱᴏᴏɴᴇʀ / ᴍᴏʀᴇ ᴏꜰᴛᴇɴ.",
                                Material.CLOCK,
                                false),
                        BotEntry.foodSelector(
                                "auto_eat_foods",
                                "ᴀʟʟᴏᴡᴇᴅ ꜰᴏᴏᴅꜱ",
                                "ᴘɪᴄᴋ ᴡʜɪᴄʜ ꜰᴏᴏᴅꜱ ᴛʜᴇ ʙᴏᴛ ᴍᴀʏ ᴇᴀᴛ.\n"
                                        + "ᴘʀɪᴏʀɪᴛʏ: ᴏꜰꜰ-ʜᴀɴᴅ → ʜᴏᴛʙᴀʀ → ɪɴᴠᴇɴᴛᴏʀʏ.\n"
                                        + "ɴᴏɴᴇ ꜱᴇʟᴇᴄᴛᴇᴅ = ᴇᴀᴛ ᴀɴʏ ꜰᴏᴏᴅ.",
                                Material.APPLE,
                                false)));
    }

    private BotCategory danger() {
        return new BotCategory(
                "⚠ ᴅᴀɴɢᴇʀ",
                Material.TNT,
                Material.COAL,
                Material.RED_STAINED_GLASS_PANE,
                List.of(
                        BotEntry.danger(
                                "reset_all",
                                "ʀᴇꜱᴇᴛ ᴀʟʟ ꜱᴇᴛᴛɪɴɢꜱ",
                                "⚠ ʀᴇꜱᴇᴛ ᴇᴠᴇʀʏ ꜱᴇᴛᴛɪɴɢ ᴏɴ ᴛʜɪꜱ ʙᴏᴛ\nᴛᴏ ᴅᴇꜰᴀᴜʟᴛ ᴠᴀʟᴜᴇꜱ.\n"
                                        + "ɢᴇɴᴇʀᴀʟ, ᴄʜᴀᴛ, ᴘᴠᴇ, ᴘᴀᴛʜꜰɪɴᴅɪɴɢ,\n"
                                        + "ᴄᴏᴍᴍᴀɴᴅꜱ - ᴀʟʟ ʀᴇꜱᴇᴛ.",
                                Material.REDSTONE_BLOCK,
                                true),
                        BotEntry.danger(
                                "delete",
                                "ᴅᴇʟᴇᴛᴇ ʙᴏᴛ",
                                "⚠ ᴘᴇʀᴍᴀɴᴇɴᴛʟʏ ʀᴇᴍᴏᴠᴇ ᴛʜɪꜱ ʙᴏᴛ.\nᴛʜɪꜱ ᴀᴄɪᴠᴇ ᴄᴀɴɴᴏᴛ ʙᴇ ᴜɴᴅᴏɴᴇ.",
                                Material.TNT,
                                true)));
    }

    private record GuiHolder(UUID uuid) implements InventoryHolder {
        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record MobSelectorHolder(UUID playerUuid) implements InventoryHolder {
        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record FoodSelectorHolder(UUID playerUuid) implements InventoryHolder {
        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record ShareSelectorHolder(UUID playerUuid) implements InventoryHolder {
        @SuppressWarnings("NullableProblems")
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record MobDisplay(EntityType type, Material material, String displayName, String category) {}

    private record BotCategory(
            String label, Material activeMat, Material inactiveMat, Material separatorGlass, List<BotEntry> entries) {}

    private static List<String> wrapText(String text, int maxLen) {
        return GuiKit.wrapText(text, maxLen);
    }

    private enum BotEntryType {
        TOGGLE,
        CYCLE_PRIORITY,
        CYCLE_PVE_MODE,
        ACTION,
        MOB_SELECTOR,
        FOOD_SELECTOR,
        IMMEDIATE,
        DANGER,
        COMING_SOON
    }

    private record BotEntry(
            String id,
            String label,
            String description,
            Material icon,
            BotEntryType type,
            boolean opOnly,
            String valueOverride) {
        BotEntry(String id, String label, String description, Material icon, BotEntryType type, boolean opOnly) {
            this(id, label, description, icon, type, opOnly, null);
        }

        static BotEntry toggle(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.TOGGLE, opOnly);
        }

        static BotEntry cyclePriority(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.CYCLE_PRIORITY, opOnly);
        }

        static BotEntry cyclePveMode(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.CYCLE_PVE_MODE, opOnly);
        }

        static BotEntry action(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.ACTION, opOnly);
        }

        static BotEntry mobSelector(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.MOB_SELECTOR, opOnly);
        }

        static BotEntry foodSelector(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.FOOD_SELECTOR, opOnly);
        }

        static BotEntry immediate(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.IMMEDIATE, opOnly);
        }

        static BotEntry danger(String id, String label, String desc, Material icon, boolean opOnly) {
            return new BotEntry(id, label, desc, icon, BotEntryType.DANGER, opOnly);
        }

        static BotEntry comingSoon(String id, String label, String desc, Material icon) {
            return new BotEntry(id, label, desc, icon, BotEntryType.COMING_SOON, false);
        }
    }

    private record ChatInputSes(String inputType, UUID botUuid, int[] guiState, int cleanupTaskId) {}
}
