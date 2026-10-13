package me.bill.fakePlayerPlugin.command;

import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppClickMode;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotClient;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PathfindingService;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.FppLogger;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;

/**
 * Left-click: the bot presses its attack button like a real client - attacks the entity under its
 * crosshair (at full charge), digs the block under it, or swings at air. Every decision is made by
 * {@link BotClient}, a port of the vanilla client's {@code startAttack}/{@code continueAttack}; this
 * class only drives its button state per mode:
 *
 * <ul>
 *   <li>{@code --once}: one press. A dig that press started is held until the block breaks.</li>
 *   <li>{@code --repeat}: spam-click - a fresh press every interval, the button never held (so a
 *       survival block only breaks if it is instant-break; entities are hit at whatever charge the
 *       weapon has, exactly like a player mashing the button).</li>
 *   <li>{@code --hold}: the button stays down; digging continues block after block exactly as a held
 *       mouse button does, entities are attacked each time the weapon is fully charged.</li>
 * </ul>
 */
public final class LeftClickCommand extends AbstractClickCommand {

    /** Valid range for a bot's configurable left-click interval (server default or per-bot override). */
    public static final int MIN_INTERVAL_TICKS = 1;

    public static final int MAX_INTERVAL_TICKS = 40;

    public LeftClickCommand(FakePlayerPlugin plugin, FakePlayerManager manager, PathfindingService pathfinding) {
        super(plugin, manager, pathfinding);
    }

    @Override
    protected String taskName() {
        return "left-click";
    }

    @Override
    protected PathfindingService.Owner navOwner() {
        return PathfindingService.Owner.MINE;
    }

    @Override
    protected double navArrivalDistance() {
        return 0.5;
    }

    @Override
    protected void dbg(String msg) {
        FppLogger.debug("LEFTCLICK", Config.debugLeftClick(), msg);
    }

    @Override
    public String getDescription() {
        return "Bot left-clicks like a real player (attacks what it aims at, else breaks blocks). Default: --once";
    }

    @Override
    protected String stopPermission() {
        return Perm.LEFT_CLICK_STOP;
    }

    @Override
    protected String modePermission(FppClickMode mode) {
        return switch (mode) {
            case ONCE -> Perm.LEFT_CLICK_ONCE;
            case REPEAT -> Perm.LEFT_CLICK_REPEAT;
            case HOLD -> Perm.LEFT_CLICK_HOLD;
            default -> Perm.LEFT_CLICK;
        };
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.hasAny(
                sender,
                Perm.LEFT_CLICK,
                Perm.LEFT_CLICK_START,
                Perm.LEFT_CLICK_ONCE,
                Perm.LEFT_CLICK_REPEAT,
                Perm.LEFT_CLICK_HOLD,
                Perm.LEFT_CLICK_STOP);
    }

    @Override
    protected boolean tickClient(FakePlayer fp, ServerPlayer nms, ClickState s) {
        BotClient c = BotClient.of(nms);

        // The vanilla client swallows attack presses and dig progress while a use-item is in progress.
        if (nms.isUsingItem()) {
            c.endTick();
            return false;
        }

        HitResult hit = BotClient.pick(nms);
        int pause = fp.resolveLeftClickIntervalTicks();
        boolean digging = c.isDestroying();

        // A hit entity (or a spear stab) only lands properly at full charge - the attack indicator a
        // real player waits for before clicking.
        boolean needsCharge =
                hit.getType() == HitResult.Type.ENTITY || nms.getMainHandItem().has(DataComponents.PIERCING_WEAPON);
        boolean ready = !needsCharge
                || (nms.getAttackStrengthScale(0.0F) >= 1.0F && !nms.cannotAttackWithItem(nms.getMainHandItem(), 0));

        boolean press = false;
        if (s.mode == FppClickMode.REPEAT) {
            // Spam-click: one press every interval, regardless of charge or what the last press did.
            if (s.pause > 0) {
                s.pause--;
            } else {
                press = true;
                s.pressed = true;
                s.pause = Math.max(0, pause - 1);
            }
        } else if (!s.pressed) {
            if (ready) {
                press = true;
                s.pressed = true;
            }
        } else if (s.mode == FppClickMode.HOLD && !digging && needsCharge) {
            press = ready;
        }

        boolean instant = press && c.startAttack(nms, hit, pause);

        // Button state: --hold keeps it down; --once keeps it down only while a dig it started runs;
        // --repeat never holds (each press is released immediately, like a mashed button).
        boolean down = s.mode == FppClickMode.HOLD || (s.mode == FppClickMode.ONCE && c.isDestroying());
        c.continueAttack(nms, hit, down && !instant, pause);

        boolean stillDigging = c.isDestroying();
        c.endTick();

        dbg("tick: bot=" + fp.getDisplayName() + " hit=" + hit.getType() + " press=" + press + " digging="
                + stillDigging);

        return s.mode == FppClickMode.ONCE && s.pressed && !stillDigging;
    }

    @Override
    protected void onBeforeStop(FakePlayer fp, Player bot, @Nullable ClickState state) {
        // Cancel any dig the server still thinks is running so no ghost cracks linger.
        BotClient.of(((CraftPlayer) bot).getHandle()).resetAttack(((CraftPlayer) bot).getHandle());
    }
}
