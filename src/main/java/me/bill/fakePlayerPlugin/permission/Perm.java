package me.bill.fakePlayerPlugin.permission;

import org.bukkit.command.CommandSender;

@SuppressWarnings("unused")
public final class Perm {

    private Perm() {}

    public static final String COMMAND = "fpp.command";
    public static final String PLUGIN_INFO = "fpp.plugininfo";

    public static final String OP = "fpp.op";

    public static final String ADMIN = "fpp.admin";

    public static final String USE = "fpp.use";

    public static final String SPAWN = "fpp.spawn";

    public static final String USER_SPAWN = "fpp.spawn.user";

    public static final String BOT_LIMIT_PREFIX = "fpp.spawn.limit.";

    public static final String DESPAWN = "fpp.despawn";

    public static final String DELETE = "fpp.delete";

    public static final String DELETE_ALL = "fpp.delete.all";

    public static final String DESPAWN_BULK = "fpp.despawn.bulk";

    public static final String DESPAWN_OWN = "fpp.despawn.own";

    public static final String HELP = "fpp.help";
    public static final String LIST = "fpp.list";

    public static final String INFO = "fpp.info";

    public static final String USER_INFO = "fpp.info.user";

    public static final String TP = "fpp.tp";

    public static final String USER_TPH = "fpp.tph";
    public static final String USER_TPH_ALL = "fpp.tph.all";

    public static final String USER_XP = "fpp.xp";

    public static final String MOVE = "fpp.move";

    public static final String MOVE_TO = "fpp.move.to";

    public static final String MOVE_COORDS = "fpp.move.coords";

    public static final String FREEZE = "fpp.freeze";

    public static final String SNEAK = "fpp.sneak";

    public static final String RENAME = "fpp.rename";

    public static final String INVENTORY = "fpp.inventory";

    public static final String INVENTORY_CMD = "fpp.inventory.cmd";

    public static final String INVENTORY_RIGHTCLICK = "fpp.inventory.rightclick";

    public static final String INVENTORY_OWN = "fpp.inventory.own";

    public static final String LEFT_CLICK = "fpp.left-click";
    public static final String LEFT_CLICK_START = "fpp.left-click.start";
    public static final String LEFT_CLICK_ONCE = "fpp.left-click.once";
    public static final String LEFT_CLICK_REPEAT = "fpp.left-click.repeat";
    public static final String LEFT_CLICK_HOLD = "fpp.left-click.hold";
    public static final String LEFT_CLICK_STOP = "fpp.left-click.stop";

    public static final String RIGHT_CLICK = "fpp.right-click";
    public static final String RIGHT_CLICK_START = "fpp.right-click.start";
    public static final String RIGHT_CLICK_ONCE = "fpp.right-click.once";
    public static final String RIGHT_CLICK_REPEAT = "fpp.right-click.repeat";
    public static final String RIGHT_CLICK_HOLD = "fpp.right-click.hold";
    public static final String RIGHT_CLICK_STOP = "fpp.right-click.stop";

    public static final String USE_CMD = "fpp.use.cmd";

    public static final String USEITEM_LEGACY = "fpp.useitem";

    public static final String USE_START = "fpp.useitem.start";

    public static final String USE_ONCE = "fpp.useitem.once";

    public static final String USE_STOP = "fpp.useitem.stop";

    public static final String ATTACK = "fpp.attack";

    public static final String FIND = "fpp.find";

    public static final String PERF = "fpp.perf";

    public static final String STOP = "fpp.stop";

    public static final String SETOWNER = "fpp.setowner";

    public static final String SAVE = "fpp.save";

    public static final String STORAGE = "fpp.storage";

    public static final String RELOAD = "fpp.reload";

    public static final String SETTINGS = "fpp.settings";

    public static final String CHECK = "fpp.check";

    /** Manage bot auto-register/login against an installed login plugin: /fpp auth on|off|status|reset|setpassword. */
    public static final String AUTH = "fpp.auth";

    public static final String BYPASS_MAX = "fpp.bypass.max";

    public static final String BYPASS_COOLDOWN = "fpp.bypass.cooldown";

    public static final String NOTIFY = "fpp.notify";

    /** Buy/extend a rented bot with real economy currency. */
    public static final String RENT = "fpp.rent";

    /** View rental time remaining. */
    public static final String RENT_INFO = "fpp.rent.info";

    /** Grant rental time without charging - console/admin/other-shop-plugin integration point. */
    public static final String RENT_GIVE = "fpp.rent.give";

    /** This player's/bot's rentals never expire from time running out. */
    public static final String RENT_UNLIMITED = "fpp.rent.unlimited";

    /** {@code /fpp clear}: layer-by-layer excavation of a chunk or area. */
    public static final String CLEAR = "fpp.clear";

    /** Rent without being charged (no economy needed); counts toward the rented-bot limit. */
    public static final String RENT_FREE = "fpp.rent.free";

    /** Turn on auto-renew for a rented bot. */
    public static final String RENT_AUTO = "fpp.rent.auto";

    /** List every rental on the server and act on other players' rented bots. */
    public static final String RENT_ADMIN = "fpp.rent.admin";

    /** {@code fpp.rent.discount.<1-100>} - percent off rental prices (best node wins). */
    public static final String RENT_DISCOUNT_PREFIX = "fpp.rent.discount.";

    /** {@code fpp.rent.limit.<1-50>} - max simultaneously rented bots (best node wins). */
    public static final String RENT_LIMIT_PREFIX = "fpp.rent.limit.";

    /** Spawn without paying {@code economy.spawn.price}. */
    public static final String SPAWN_FREE = "fpp.spawn.free";

    public static boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    public static boolean missing(CommandSender sender, String permission) {
        return !has(sender, permission);
    }

    public static boolean hasAny(CommandSender sender, String... permissions) {
        for (String perm : permissions) {
            if (sender.hasPermission(perm)) return true;
        }
        return false;
    }

    /** Best {@code fpp.rent.discount.<n>} the sender holds, as a percentage 0-100. */
    public static double resolveRentDiscount(CommandSender sender) {
        double best = 0;
        for (int i = 1; i <= 100; i++) {
            if (sender.hasPermission(RENT_DISCOUNT_PREFIX + i)) best = i;
        }
        return best;
    }

    /** Best {@code fpp.rent.limit.<n>} the sender holds, or -1 when none (use the config default). */
    public static int resolveRentLimit(CommandSender sender) {
        int best = -1;
        for (int i = 1; i <= 50; i++) {
            if (sender.hasPermission(RENT_LIMIT_PREFIX + i)) best = i;
        }
        return best;
    }

    public static int resolveUserBotLimit(CommandSender sender) {
        int best = -1;
        for (int i = 1; i <= 100; i++) {
            if (sender.hasPermission(BOT_LIMIT_PREFIX + i)) best = i;
        }
        return best;
    }
}
