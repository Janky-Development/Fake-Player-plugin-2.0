package me.bill.fakePlayerPlugin.command;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.gui.MainMenuGui;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;

/** {@code /fpp menu}: opens the main menu hub. */
public final class MenuCommand implements FppCommand {

    private final MainMenuGui gui;

    public MenuCommand(MainMenuGui gui) {
        this.gui = gui;
    }

    @Override
    public String getName() {
        return "menu";
    }

    @Override
    public List<String> getAliases() {
        return List.of("gui");
    }

    @Override
    public String getUsage() {
        return "";
    }

    @Override
    public String getDescription() {
        return "Open the main menu.";
    }

    @Override
    public String getPermission() {
        return Perm.HELP;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.hasAny(sender, Perm.HELP, Perm.LIST, Perm.SETTINGS, Perm.SPAWN, Perm.USER_SPAWN);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }
        if (!canUse(sender)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        gui.open(player);
        return true;
    }
}
