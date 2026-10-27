package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.event.FppBotTaskEvent;
import me.bill.fakePlayerPlugin.api.impl.FppApiImpl;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.PveController;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;
import me.bill.fakePlayerPlugin.util.BotAccess;

/**
 * {@code /fpp attack}: the one-line way to put a bot to work as a fighter - an AFK mob-farm guard that
 * holds its post, or a hunter that chases and returns. It is a front-end for the bot's persistent PVE
 * settings (the same ones the settings GUI edits), so a configured bot keeps fighting across restarts.
 * The fighting itself lives in {@link PveController}.
 *
 * <pre>
 *   /fpp attack Bot                    guard: attack hostile mobs that come in reach (holds position)
 *   /fpp attack Bot --chase            hunt: walk to hostile mobs in range, return afterwards
 *   /fpp attack Bot --mobs zombie,skeleton --range 12 --priority lowest-health
 *   /fpp attack --all --any            every bot, any non-player mob
 *   /fpp attack Bot --once             one landed hit, then stop
 *   /fpp attack Bot --status | --stop  /  /fpp attack --stop
 * </pre>
 */
public final class AttackCommand implements FppCommand {

    private static final double MAX_RANGE = 48.0;

    private final FakePlayerPlugin plugin;
    private final FakePlayerManager manager;

    public AttackCommand(FakePlayerPlugin plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ── metadata ────────────────────────────────────────────────────────────

    @Override
    public String getName() {
        return "attack";
    }

    @Override
    public String getUsage() {
        return "<bot|--all> [--stand|--chase] [--hostile|--any|--mobs <types>] [--range <n>]"
                + " [--priority nearest|lowest-health] [--once]  |  <bot> --status  |  <bot|--all> --stop";
    }

    @Override
    public String getDescription() {
        return "Fighting bot: guards its post or hunts hostile mobs with real client attacks (AFK mob farms).";
    }

    @Override
    public String getPermission() {
        return Perm.ATTACK;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.ATTACK);
    }

    // ── execution ───────────────────────────────────────────────────────────

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.get("attack-usage"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("--stop")) {
            stopAll();
            sender.sendMessage(Lang.get("attack-stopped-all"));
            return true;
        }

        String target = args[0];
        Options o = parse(sender, args);
        if (o == null) return true;

        if (target.equalsIgnoreCase("--all")) {
            if (o.stop) {
                stopAll();
                sender.sendMessage(Lang.get("attack-stopped-all"));
                return true;
            }
            int count = 0;
            for (FakePlayer fp : new ArrayList<>(manager.getActivePlayers())) {
                if (!canControl(sender, fp)) continue;
                Player bot = fp.getPlayer();
                if (bot == null || !bot.isOnline()) continue;
                apply(fp, o);
                count++;
            }
            sender.sendMessage(
                    count == 0
                            ? Lang.get("attack-no-bots")
                            : Lang.get("attack-started-all", "count", String.valueOf(count)));
            return true;
        }

        FakePlayer fp = manager.getByName(target);
        if (fp == null) {
            sender.sendMessage(Lang.get("attack-not-found", "name", target));
            return true;
        }
        if (!canControl(sender, fp)) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        Player bot = fp.getPlayer();
        if (bot == null || !bot.isOnline()) {
            sender.sendMessage(Lang.get("attack-bot-offline", "name", fp.getDisplayName()));
            return true;
        }

        if (o.status) {
            sendStatus(sender, fp);
            return true;
        }
        if (o.stop) {
            stopAttacking(fp.getUuid());
            sender.sendMessage(Lang.get("attack-stopped", "name", fp.getDisplayName()));
            return true;
        }

        apply(fp, o);
        sender.sendMessage(Lang.get(
                o.once ? "attack-started-once" : "attack-started-config",
                "name",
                fp.getDisplayName(),
                "mode",
                fp.getPveSmartAttackMode().movesWhileAttacking() ? "chase" : "stand",
                "range",
                trim(fp.getPveRange()),
                "targets",
                describeTargets(fp)));
        return true;
    }

    private boolean canControl(CommandSender sender, FakePlayer fp) {
        return !(sender instanceof Player player)
                || Perm.has(sender, Perm.ADMIN)
                || BotAccess.canAdminister(player, fp);
    }

    /** Parsed flags; unset fields keep the bot's existing/ configured value. */
    private static final class Options {
        boolean stop;
        boolean status;
        boolean once;

        @Nullable
        Boolean chase;

        @Nullable
        Set<String> mobs;

        @Nullable
        Double range;

        @Nullable
        String priority;
    }

    private @Nullable Options parse(CommandSender sender, String[] args) {
        Options o = new Options();
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            switch (a) {
                case "--stop" -> o.stop = true;
                case "--status" -> o.status = true;
                case "--once" -> o.once = true;
                case "--stand", "--guard" -> o.chase = false;
                case "--chase", "--hunt", "--move" -> o.chase = true;
                case "--hostile" -> o.mobs = new LinkedHashSet<>();
                case "--any", "--all-mobs" -> o.mobs = new LinkedHashSet<>(List.of(PveController.ANY_MOB));
                case "--mobs", "--mob" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage(Lang.get("attack-usage"));
                        return null;
                    }
                    Set<String> types = new LinkedHashSet<>();
                    for (String raw : args[++i].split(",")) {
                        String name = raw.trim().toUpperCase(Locale.ROOT);
                        if (name.isEmpty()) continue;
                        if (!name.equals(PveController.ANY_MOB) && !isMobType(name)) {
                            sender.sendMessage(Lang.get("attack-unknown-mob", "type", raw.trim()));
                            return null;
                        }
                        types.add(name);
                    }
                    o.mobs = types;
                }
                case "--range" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage(Lang.get("attack-usage"));
                        return null;
                    }
                    try {
                        double r = Double.parseDouble(args[++i]);
                        if (!(r >= 1.0) || r > MAX_RANGE) throw new NumberFormatException();
                        o.range = r;
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Lang.get("attack-invalid-range", "max", trim(MAX_RANGE)));
                        return null;
                    }
                }
                case "--priority" -> {
                    if (i + 1 >= args.length) {
                        sender.sendMessage(Lang.get("attack-usage"));
                        return null;
                    }
                    String p = args[++i].toLowerCase(Locale.ROOT);
                    if (!p.equals("nearest") && !p.equals("lowest-health")) {
                        sender.sendMessage(Lang.get("attack-usage"));
                        return null;
                    }
                    o.priority = p;
                }
                default -> {
                    sender.sendMessage(Lang.get("attack-usage"));
                    return null;
                }
            }
        }
        return o;
    }

    private static boolean isMobType(String name) {
        try {
            EntityType type = EntityType.valueOf(name);
            return type.isAlive() && type != EntityType.PLAYER && type != EntityType.ARMOR_STAND;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Writes the options onto the bot's persistent PVE settings and (re)starts its combat task. */
    private void apply(FakePlayer fp, Options o) {
        if (o.mobs != null) fp.setPveMobTypes(o.mobs);
        if (o.range != null) fp.setPveRange(o.range);
        if (o.priority != null) fp.setPvePriority(o.priority);

        boolean chase = o.chase != null ? o.chase : Config.attackDefaultChase();
        // A bare re-run keeps a bot that is already configured to chase chasing.
        if (o.chase == null && fp.isPveEnabled()) chase = fp.isPveMoveToTarget();
        fp.setPveSmartAttackMode(
                chase ? FakePlayer.PveSmartAttackMode.ON_MOVE : FakePlayer.PveSmartAttackMode.ON_NO_MOVE);

        FppApiImpl.fireTaskEvent(fp, "attack", FppBotTaskEvent.Action.START);
        PveController pve = plugin.getPveController();
        if (pve != null) {
            if (o.once) pve.attackOnce(fp);
            else pve.refresh(fp);
        }
    }

    private void sendStatus(CommandSender sender, FakePlayer fp) {
        PveController pve = plugin.getPveController();
        if (!fp.isPveEnabled()) {
            sender.sendMessage(Lang.get("attack-status-off", "name", fp.getDisplayName()));
            return;
        }
        LivingEntity t = pve != null ? pve.currentTarget(fp.getUuid()) : null;
        sender.sendMessage(Lang.get(
                "attack-status-on",
                "name",
                fp.getDisplayName(),
                "mode",
                fp.getPveSmartAttackMode().movesWhileAttacking() ? "chase" : "stand",
                "range",
                trim(fp.getPveRange()),
                "targets",
                describeTargets(fp),
                "target",
                t != null ? t.getType().name().toLowerCase(Locale.ROOT) : "none"));
    }

    private static String describeTargets(FakePlayer fp) {
        Set<String> types = fp.getPveMobTypes();
        if (types.isEmpty()) return "hostile";
        if (types.contains(PveController.ANY_MOB)) return "any mob";
        return String.join(", ", types).toLowerCase(Locale.ROOT);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    // ── API kept for StopCommand / GUI / other systems ──────────────────────

    public void stopAttacking(UUID botUuid) {
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp == null) return;
        boolean wasOn = fp.isPveEnabled();
        fp.setPveSmartAttackMode(FakePlayer.PveSmartAttackMode.OFF);
        PveController pve = plugin.getPveController();
        if (pve != null) pve.refresh(fp);
        if (wasOn) FppApiImpl.fireTaskEvent(fp, "attack", FppBotTaskEvent.Action.STOP);
    }

    public void stopAll() {
        for (FakePlayer fp : new ArrayList<>(manager.getActivePlayers())) {
            if (fp.isPveEnabled()) stopAttacking(fp.getUuid());
        }
    }

    public boolean isAttacking(UUID botUuid) {
        FakePlayer fp = manager.getByUuid(botUuid);
        return fp != null && fp.isPveEnabled();
    }

    // ── tab completion ──────────────────────────────────────────────────────

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!canUse(sender)) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("--stop", "--all")) if (s.startsWith(prefix)) out.add(s);
            for (FakePlayer fp : manager.getActivePlayers()) {
                if (fp.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(fp.getName());
            }
            return out;
        }

        String prev = args[args.length - 2].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (prev) {
            case "--mobs", "--mob" -> {
                String head = "";
                String tail = prefix;
                int comma = prefix.lastIndexOf(',');
                if (comma >= 0) {
                    head = prefix.substring(0, comma + 1);
                    tail = prefix.substring(comma + 1);
                }
                for (EntityType t : EntityType.values()) {
                    if (!t.isAlive() || t == EntityType.PLAYER || t == EntityType.ARMOR_STAND) continue;
                    String n = t.name().toLowerCase(Locale.ROOT);
                    if (n.startsWith(tail)) out.add(head + n);
                    if (out.size() >= 40) break;
                }
                return out;
            }
            case "--priority" -> {
                for (String s : List.of("nearest", "lowest-health")) if (s.startsWith(prefix)) out.add(s);
                return out;
            }
            case "--range" -> {
                for (String s : List.of("4", "8", "12", "16", "24")) if (s.startsWith(prefix)) out.add(s);
                return out;
            }
            default -> {}
        }
        Set<String> used = new java.util.HashSet<>();
        for (int i = 1; i < args.length - 1; i++) used.add(args[i].toLowerCase(Locale.ROOT));
        for (String flag : List.of(
                "--stand",
                "--chase",
                "--hostile",
                "--any",
                "--mobs",
                "--range",
                "--priority",
                "--once",
                "--status",
                "--stop")) {
            if (!used.contains(flag) && flag.startsWith(prefix)) out.add(flag);
        }
        return out;
    }
}
