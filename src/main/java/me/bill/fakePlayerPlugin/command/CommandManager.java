package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.gui.HelpGui;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.AttributionManager;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.TextUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class CommandManager implements CommandExecutor, TabCompleter {

    private static final TextColor ACCENT = TextColor.fromHexString("#A78BFA");
    private static final TextColor DARK_GRAY = TextColor.fromHexString("#5F5B73");
    private static final TextColor GRAY = TextColor.fromHexString("#9691AB");
    private static final TextColor WHITE = TextColor.fromHexString("#EEECF7");

    private final List<FppCommand> commands = new ArrayList<>();
    private final Map<String, FppCommand> byName = new LinkedHashMap<>();
    private final FakePlayerPlugin plugin;

    public CommandManager(FakePlayerPlugin plugin) {
        this.plugin = plugin;
        register(new HelpCommand(this));
    }

    public void register(FppCommand command) {
        if (!byName.containsKey(command.getName().toLowerCase())) {
            commands.add(command);
            byName.put(command.getName().toLowerCase(), command);

            for (String alias : command.getAliases()) {
                byName.putIfAbsent(alias.toLowerCase(), command);
            }
            Config.debug("Registered command: fpp " + command.getName());
        }
    }

    /** Looks a registered sub-command up by name or alias (case-insensitive); null if unknown. */
    public FppCommand getCommand(String nameOrAlias) {
        return nameOrAlias == null ? null : byName.get(nameOrAlias.toLowerCase());
    }

    public List<FppCommand> getCommands() {
        return Collections.unmodifiableList(commands);
    }

    public void setHelpGui(HelpGui helpGui) {
        FppCommand help = byName.get("help");
        if (help instanceof HelpCommand hc) {
            hc.setHelpGui(helpGui);
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args) {

        if (args.length == 0) {

            if (!sender.hasPermission(Perm.COMMAND)) return true;

            if (sender.hasPermission(Perm.PLUGIN_INFO)) {

                sendPluginInfo(sender);
            } else {

                sendHelpHint(sender);
            }
            return true;
        }

        if (plugin.isVersionUnsupported()) {
            sender.sendMessage(Lang.get("version-unsupported", "version", plugin.getDetectedMcVersion()));
            return true;
        }

        String subName = args[0].toLowerCase();
        FppCommand sub = byName.get(subName);

        if (sub == null) {
            Config.debug(sender.getName() + " used unknown sub-command: " + subName);
            sender.sendMessage(Lang.get("unknown-command", label));
            return true;
        }

        if (!sub.canUse(sender)) {
            Config.debug(sender.getName() + " was denied: fpp " + subName);
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }

        Config.debug(sender.getName() + " executed: fpp " + String.join(" ", args));

        if (sub instanceof HelpCommand hc) hc.setLastLabel(label);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        if (requiresBotOwnership(subName)
                && sender instanceof Player player
                && subArgs.length > 0
                && !subArgs[0].startsWith("--")
                && !subArgs[0].equalsIgnoreCase("--all")) {
            FakePlayer fp = plugin.getFakePlayerManager().getByName(subArgs[0]);
            if (fp != null && !BotAccess.canAdminister(player, fp)) {
                sender.sendMessage(Lang.get("no-permission"));
                return true;
            }
        }
        if (expandTaskTargets(sender, subName, sub, subArgs)) return true;
        sub.execute(sender, subArgs);
        return true;
    }

    private static boolean requiresBotOwnership(String name) {
        return Set.of(
                        "move",
                        "mine",
                        "find",
                        "clear",
                        "place",
                        "use",
                        "attack",
                        "follow",
                        "sleep",
                        "perf",
                        "stop",
                        "storage",
                        "inventory",
                        "inv",
                        "settings",
                        "xp",
                        "cmd",
                        "left-click",
                        "right-click")
                .contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean expandTaskTargets(CommandSender sender, String subName, FppCommand sub, String[] subArgs) {
        if (!isTaskCommand(subName) || subArgs.length == 0) return false;
        if (subArgs[0].equalsIgnoreCase("--all") && shouldExpandAll(subName)) {
            String[] rest = Arrays.copyOfRange(subArgs, 1, subArgs.length);
            int started = 0;
            for (FakePlayer fp : plugin.getFakePlayerManager().getActivePlayers()) {
                if (fp.getPlayer() == null || !fp.getPlayer().isOnline()) continue;
                if (sender instanceof Player player && !BotAccess.canAdminister(player, fp)) continue;
                sub.execute(sender, prepend(fp.getName(), rest));
                started++;
            }
            sender.sendMessage(Lang.get("task-dispatched", "count", String.valueOf(started)));
            return true;
        }
        return false;
    }

    private static boolean isTaskCommand(String name) {
        return Set.of(
                        "move",
                        "mine",
                        "find",
                        "place",
                        "use",
                        "attack",
                        "follow",
                        "sleep",
                        "perf",
                        "stop",
                        "storage",
                        "left-click",
                        "right-click")
                .contains(name.toLowerCase());
    }

    private static boolean shouldExpandAll(String name) {
        return Set.of("mine", "find", "place", "use", "storage").contains(name.toLowerCase());
    }

    private static String[] prepend(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args) {

        if (args.length == 1) {

            return commands.stream()
                    .filter(cmd -> safeCanUse(cmd, sender))
                    .map(FppCommand::getName)
                    .filter(name -> name.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (args.length >= 2) {
            String subName = args[0].toLowerCase(Locale.ROOT);
            FppCommand sub = byName.get(subName);

            if (sub != null && safeCanUse(sub, sender)) {
                return safeTabComplete(sub, sender, Arrays.copyOfRange(args, 1, args.length));
            }
        }

        return Collections.emptyList();
    }

    private boolean safeCanUse(FppCommand command, CommandSender sender) {
        try {
            return command.canUse(sender);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<String> safeTabComplete(FppCommand command, CommandSender sender, String[] args) {
        try {
            return command.tabComplete(sender, args);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private void sendHelpHint(CommandSender sender) {
        Component divider = TextUtil.colorize(Lang.raw("divider"));
        sender.sendMessage(divider);
        sender.sendMessage(Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text("ᴛʏᴘᴇ ").color(GRAY))
                .append(Component.text("/fpp help")
                        .color(ACCENT)
                        .clickEvent(ClickEvent.runCommand("/fpp help"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open the help" + " menu")
                                .color(GRAY))))
                .append(Component.text(" ꜰᴏʀ ᴀ ʟɪꜱᴛ ᴏꜰ ᴄᴏᴍᴍᴀɴᴅꜱ.").color(GRAY)));
        sender.sendMessage(divider);
    }

    private void sendPluginInfo(CommandSender sender) {
        String version = plugin.getPluginMeta().getVersion();
        List<String> authors = plugin.getPluginMeta().getAuthors();
        String author = AttributionManager.formatAuthors(authors);

        Component divider = TextUtil.colorize(Lang.raw("divider"));
        Component header = TextUtil.colorize(Lang.raw("info-screen-header"));

        sender.sendMessage(divider);
        sender.sendMessage(header);
        sender.sendMessage(Component.empty());

        sender.sendMessage(row("ᴠᴇʀꜱɪᴏɴ", version));
        sender.sendMessage(row("ᴀᴜᴛʜᴏʀ", author));

        FakePlayerManager fpm = plugin.getFakePlayerManager();
        if (fpm != null) {
            sender.sendMessage(row("ᴀᴄᴛɪᴠᴇ ʙᴏᴛꜱ", String.valueOf(fpm.getCount())));
        }

        sender.sendMessage(Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text("ᴅᴏᴡɴʟᴏᴀᴅ ").color(GRAY))
                .append(Component.text("→ ").color(DARK_GRAY))
                .append(Component.text("Modrinth")
                        .color(ACCENT)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl("https://modrinth.com/plugin/fake-player-plugin-fpp"))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to open Modrinth").color(GRAY))))
                .append(Component.text(", ").color(GRAY))
                .append(Component.text("PaperMC")
                        .color(ACCENT)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl("https://hangar.papermc.io/Pepe-tf/FakePlayerPlugin"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open PaperMC" + " Hangar")
                                .color(GRAY))))
                .append(Component.text(", ").color(GRAY))
                .append(Component.text("BuiltByBit")
                        .color(ACCENT)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl("https://builtbybit.com/resources/fake-player-plugin.98704/"))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to open BuiltByBit").color(GRAY)))));

        sender.sendMessage(Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text("ꜱᴜᴘᴘᴏʀᴛ  ").color(GRAY))
                .append(Component.text("→ ").color(DARK_GRAY))
                .append(Component.text("Discord")
                        .color(ACCENT)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl("https://discord.gg/Q9cd9frzRt"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to join the support" + " Discord")
                                .color(GRAY)))));

        sender.sendMessage(Component.empty());

        sender.sendMessage(Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text("ᴛʏᴘᴇ ").color(GRAY))
                .append(Component.text("/fpp help")
                        .color(ACCENT)
                        .clickEvent(ClickEvent.runCommand("/fpp help"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open the help" + " menu")
                                .color(GRAY))))
                .append(Component.text(" ꜰᴏʀ ᴀ ʟɪꜱᴛ ᴏꜰ ᴄᴏᴍᴍᴀɴᴅꜱ.").color(GRAY)));

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text("ꜰʀᴇᴇ & ᴏᴘᴇɴ-ꜱᴏᴜʀᴄᴇ").color(GRAY))
                .append(Component.text(" · ").color(DARK_GRAY))
                .append(Component.text("ɪꜰ ʏᴏᴜ ᴘᴀɪᴅ ꜰᴏʀ ᴛʜɪꜱ, ʏᴏᴜ ᴡᴇʀᴇ ꜱᴄᴀᴍᴍᴇᴅ.")
                        .color(GRAY)));

        sender.sendMessage(divider);
    }

    private Component row(String label, String value) {
        return Component.empty()
                .append(Component.text("  ").color(DARK_GRAY))
                .append(Component.text(label).color(GRAY))
                .append(Component.text(" → ").color(DARK_GRAY))
                .append(Component.text(value).color(WHITE));
    }
}
