package me.bill.fakePlayerPlugin.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;
import me.bill.fakePlayerPlugin.util.FppScheduler;

public class XpCommand implements FppCommand {

    private static final int XP_COOLDOWN_SECONDS = 30;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    private final Map<UUID, Long> xpCooldowns = new ConcurrentHashMap<>();

    public XpCommand(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public String getName() {
        return "xp";
    }

    @Override
    public String getUsage() {
        return "<bot>";
    }

    @Override
    public String getDescription() {
        return "Collect XP from a bot";
    }

    @Override
    public String getPermission() {
        return Perm.USER_XP;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.USER_XP);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.get("xp-usage"));
            return false;
        }

        String botName = args[0];
        FakePlayer fp = manager.getByName(botName);

        if (fp == null) {
            sender.sendMessage(Lang.get("xp-not-found", "name", botName));
            return false;
        }

        Player botPlayer = fp.getPlayer();
        if (botPlayer == null) {
            sender.sendMessage(Lang.get("xp-not-found", "name", botName));
            return false;
        }

        if (sender instanceof Player player) {
            if (!BotAccess.canAdminister(player, fp)) {
                sender.sendMessage(Lang.get("no-permission"));
                return false;
            }
        }

        int totalXp = botPlayer.getTotalExperience();

        if (totalXp <= 0) {
            sender.sendMessage(Lang.get("xp-no-xp", "name", fp.getDisplayName()));
            return false;
        }

        if (sender instanceof Player player) {
            player.giveExp(totalXp, false);
        }

        botPlayer.setTotalExperience(0);
        botPlayer.setLevel(0);
        botPlayer.setExp(0f);

        UUID botUuid = fp.getUuid();
        long cooldownEnd = System.currentTimeMillis() + (XP_COOLDOWN_SECONDS * 1000L);
        xpCooldowns.put(botUuid, cooldownEnd);

        FppScheduler.runSyncLater(plugin, () -> xpCooldowns.remove(botUuid), XP_COOLDOWN_SECONDS * 20L);

        sender.sendMessage(Lang.get(
                "xp-success",
                "name",
                fp.getDisplayName(),
                "xp",
                String.valueOf(totalXp),
                "seconds",
                String.valueOf(XP_COOLDOWN_SECONDS)));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return manager.getActivePlayers().stream()
                    .map(FakePlayer::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    public boolean isOnXpCooldown(UUID botUuid) {
        Long cooldownEnd = xpCooldowns.get(botUuid);
        if (cooldownEnd == null) return false;
        return System.currentTimeMillis() < cooldownEnd;
    }


}
