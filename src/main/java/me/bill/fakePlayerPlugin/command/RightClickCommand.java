package me.bill.fakePlayerPlugin.command;

import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppClickMode;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;

/**
 * Right-click: the bot presses its use button like a real client - interacts with the entity or block
 * under its crosshair, else uses the item in hand (main hand first, off hand if the main hand did
 * nothing). Every decision is made by {@link BotClient}, a port of the vanilla client's
 * {@code startUseItem}; this class only drives its button state per mode:
 *
 * <ul>
 *   <li>{@code --once}: one press. A multi-tick use that press started (eating, drawing a bow, raising a
 *       shield) is held up to {@link #USE_HOLD_CAP_TICKS} ticks, then released.</li>
 *   <li>{@code --repeat}: spam-click - the press is repeated at the bot's right-click interval and
 *       released immediately, so multi-tick uses (eating, bow draws) are tapped, not held.</li>
 *   <li>{@code --hold}: the button stays down - a bow keeps drawing, a shield stays raised, held food is
 *       re-eaten - with the vanilla use-delay between presses.</li>
 * </ul>
 */
public final class RightClickCommand extends AbstractClickCommand {

    /** Longest a --once press holds a multi-tick use: a full bow draw is 20, most food is 32. */
    private static final int USE_HOLD_CAP_TICKS = 40;

    /** Valid range for a bot's configurable right-click interval (server default or per-bot override). */
    public static final int MIN_INTERVAL_TICKS = 1;

    public static final int MAX_INTERVAL_TICKS = 40;

    public RightClickCommand(FakePlayerPlugin plugin, FakePlayerManager manager, PathfindingService pathfinding) {
        super(plugin, manager, pathfinding);
    }

    @Override
    protected String taskName() {
        return "right-click";
    }

    @Override
    protected PathfindingService.Owner navOwner() {
        return PathfindingService.Owner.USE;
    }

    @Override
    protected double navArrivalDistance() {
        return 0.35;
    }

    @Override
    protected void dbg(String msg) {
        FppLogger.debug("RIGHTCLICK", Config.debugRightClick(), msg);
    }

    @Override
    public String getDescription() {
        return "Bot right-clicks like a real player (interacts with what it aims at, uses items). Default: --once";
    }

    @Override
    protected String stopPermission() {
        return Perm.RIGHT_CLICK_STOP;
    }

    @Override
    protected String modePermission(FppClickMode mode) {
        return switch (mode) {
            case ONCE -> Perm.RIGHT_CLICK_ONCE;
            case REPEAT -> Perm.RIGHT_CLICK_REPEAT;
            case HOLD -> Perm.RIGHT_CLICK_HOLD;
            default -> Perm.RIGHT_CLICK;
        };
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.hasAny(
                sender,
                Perm.RIGHT_CLICK,
                Perm.RIGHT_CLICK_START,
                Perm.RIGHT_CLICK_ONCE,
                Perm.RIGHT_CLICK_REPEAT,
                Perm.RIGHT_CLICK_HOLD,
                Perm.RIGHT_CLICK_STOP);
    }

    @Override
    protected boolean tickClient(FakePlayer fp, ServerPlayer nms, ClickState s) {
        BotClient c = BotClient.of(nms);
        if (c.rightClickDelay > 0) c.rightClickDelay--;

        // The configured interval replaces the vanilla right-click delay of 4 ticks.
        int delay = fp.resolveRightClickIntervalTicks();
        boolean hold = s.mode == FppClickMode.HOLD;

        if (nms.isUsingItem()) {
            // Key stays down for --hold; otherwise only as long as the use is worth waiting for.
            boolean keep = hold
                    || (s.mode == FppClickMode.ONCE
                            && nms.getTicksUsingItem()
                                    < Math.min(nms.getUseItem().getUseDuration(nms), USE_HOLD_CAP_TICKS));
            if (keep) {
                s.keyDown = true;
                return false;
            }
            BotClient.releaseUsingItem(nms);
            s.keyDown = false;
            s.pause = Math.max(0, delay - 1);
            return s.mode == FppClickMode.ONCE;
        }

        if (s.keyDown) {
            // The use ended on its own (food eaten, item used up).
            s.keyDown = false;
            s.pause = Math.max(0, delay - 1);
            if (s.mode == FppClickMode.ONCE) return true;
        }

        boolean press;
        if (hold) {
            press = c.rightClickDelay == 0;
        } else if (!s.pressed || s.mode == FppClickMode.REPEAT) {
            if (s.pause > 0) {
                s.pause--;
                press = false;
            } else {
                press = true;
            }
        } else {
            press = false;
        }
        if (!press) return false;

        s.pressed = true;
        HitResult hit = BotClient.pick(nms);
        BotClient.UseResult result = c.startUseItem(nms, hit, delay);
        dbg("press: bot=" + fp.getDisplayName() + " hit=" + hit.getType() + " result=" + result);

        if (nms.isUsingItem()) {
            s.keyDown = true;
            return false;
        }
        s.pause = Math.max(0, delay - 1);
        return s.mode == FppClickMode.ONCE;
    }

    @Override
    protected void onAfterStop(Player bot) {
        ServerPlayer nms = ((CraftPlayer) bot).getHandle();
        if (nms.isUsingItem()) BotClient.releaseUsingItem(nms);
    }
}
