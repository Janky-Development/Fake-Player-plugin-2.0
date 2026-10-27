package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;

/**
 * /fpp stop [&lt;bot&gt;|--all]
 * <p>
 * Immediately cancels every active core task for one bot or all bots.
 */
public final class StopCommand implements FppCommand {

    private final FakePlayerManager manager;

    // All references are nullable - injected after construction.
    @Nullable
    private MoveCommand moveCommand;

    @Nullable
    private LeftClickCommand leftClickCommand;

    @Nullable
    private RightClickCommand rightClickCommand;

    @Nullable
    private AttackCommand attackCommand;

    @Nullable
    private FindCommand findCommand;

    private ClearCommand clearCommand;

    // ── Constructor ──────────────────────────────────────────────────────────

    public StopCommand(@NotNull FakePlayerManager manager) {
        this.manager = manager;
    }

    // ── Dependency injection ─────────────────────────────────────────────────

    public void setMoveCommand(@Nullable MoveCommand cmd) {
        this.moveCommand = cmd;
    }

    public void setLeftClickCommand(@Nullable LeftClickCommand cmd) {
        this.leftClickCommand = cmd;
    }

    public void setRightClickCommand(@Nullable RightClickCommand cmd) {
        this.rightClickCommand = cmd;
    }

    public void setAttackCommand(@Nullable AttackCommand cmd) {
        this.attackCommand = cmd;
    }

    public void setClearCommand(@Nullable ClearCommand cmd) {
        this.clearCommand = cmd;
    }

    public void setFindCommand(@Nullable FindCommand cmd) {
        this.findCommand = cmd;
    }

    // ── FppCommand metadata ──────────────────────────────────────────────────

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String getPermission() {
        return Perm.STOP;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.STOP);
    }

    @Override
    public String getUsage() {
        return "[<bot>|--all]";
    }

    @Override
    public String getDescription() {
        return "Stop all active tasks for one bot or all bots.";
    }

    // ── Command execution ─────────────────────────────────────────────────────

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        // Default: stop all if no argument is supplied.
        if (args.length == 0 || args[0].equalsIgnoreCase("--all")) {
            int stopped = stopAllBots();
            if (stopped == 0) {
                sender.sendMessage(Lang.get("stop-all-nothing"));
            } else {
                sender.sendMessage(Lang.get("stop-all-done", "count", String.valueOf(stopped)));
            }
            return true;
        }

        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
            sender.sendMessage(Lang.get("stop-not-found", "name", args[0]));
            return true;
        }

        boolean didAnything = stopBot(fp.getUuid());
        if (didAnything) {
            sender.sendMessage(Lang.get("stop-done", "name", fp.getDisplayName()));
        } else {
            sender.sendMessage(Lang.get("stop-nothing", "name", fp.getDisplayName()));
        }
        return true;
    }

    // ── Stop helpers ─────────────────────────────────────────────────────────

    /**
     * Stops all running tasks for a single bot.
     *
     * @return true if at least one task was cancelled.
     */
    private boolean stopBot(@NotNull UUID uuid) {
        boolean did = false;

        if (moveCommand != null) {
            moveCommand.cleanupBot(uuid);
            did = true;
        }
        if (leftClickCommand != null && leftClickCommand.isClicking(uuid)) {
            leftClickCommand.stopClicking(uuid);
            did = true;
        }
        if (rightClickCommand != null && rightClickCommand.isClicking(uuid)) {
            rightClickCommand.stopClicking(uuid);
            did = true;
        }
        if (attackCommand != null && attackCommand.isAttacking(uuid)) {
            attackCommand.stopAttacking(uuid);
            did = true;
        }
        if (findCommand != null && findCommand.isFinding(uuid)) {
            findCommand.cleanupBot(uuid);
            did = true;
        }
        if (clearCommand != null && clearCommand.isClearing(uuid)) {
            clearCommand.cleanupBot(uuid);
            did = true;
        }

        return did;
    }

    /**
     * Stops all running tasks for every active bot.
     *
     * @return total number of bots that had at least one task stopped.
     */
    private int stopAllBots() {
        int count = 0;
        for (FakePlayer fp : new ArrayList<>(manager.getActivePlayers())) {
            if (stopBot(fp.getUuid())) count++;
        }
        return count;
    }

    // ── Tab-completion ────────────────────────────────────────────────────────

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String in = args[0].toLowerCase();
            if ("--all".startsWith(in)) out.add("--all");
            for (FakePlayer fp : manager.getActivePlayers())
                if (fp.getName().toLowerCase().startsWith(in)) out.add(fp.getName());
        }
        return out;
    }
}
