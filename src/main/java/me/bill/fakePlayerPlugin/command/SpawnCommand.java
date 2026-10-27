package me.bill.fakePlayerPlugin.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotType;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.SpawnActions;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;

/**
 * {@code /fpp spawn}: spawns a bot (or {@code --count} of them) and can hand it work the moment it is
 * online.
 *
 * <pre>
 *   /fpp spawn [--name N] [--count N] [--location x y z world] [--delay 5s]
 *              [--preset P] [&lt;action&gt; [args…] ( then | wait 5s ) &lt;action&gt; …]
 * </pre>
 *
 * <p>Bots spawn at the commanding player's own location, or wherever an admin ({@link Perm#SPAWN})
 * targets with {@code --location}. Console senders have no location of their own, so {@code --location}
 * is mandatory for them and they use the admin tier only.
 *
 * <p><b>Actions</b> are ordinary FPP sub-commands run <em>as the spawner</em> (their own permissions
 * decide) once the bot is online - so the spawn system adds no capability of its own. Allowed names are
 * the built-in set plus {@code spawn-actions.extra-commands} and any command whose usage starts with
 * {@code <bot>}, minus {@code spawn-actions.blocked-commands}. Extras: {@code preset <name>} (config
 * presets), {@code wait <duration>}, {@code run <raw command>} (admins), and the placeholders
 * {@code {bot} {owner} {x} {y} {z} {world} {index}} in any argument.
 */
public class SpawnCommand implements FppCommand {

    /** Built-in bot-first sub-commands that make sense as "do this on spawn". */
    private static final Set<String> BUILTIN_ACTIONS =
            Set.of("attack", "move", "find", "left-click", "right-click", "storage", "sneak", "freeze");

    private static final Set<String> NEVER = Set.of("spawn", "delete", "stop", "menu", "help", "reload");

    private static final String THEN = "then";
    private static final String WAIT = "wait";
    private static final String RUN = "run";
    private static final String PRESET = "preset";

    private final FakePlayerManager manager;
    private final CommandManager commands;

    public SpawnCommand(FakePlayerManager manager, CommandManager commands) {
        this.manager = manager;
        this.commands = commands;
    }

    @Override
    public String getName() {
        return "spawn";
    }

    @Override
    public String getUsage() {
        return "[--name <name>] [--count <n>] [--location <x> <y> <z> <world>] [--delay <t>] [--preset <p>]"
                + " [<action> [args…] [then|wait <t> <action> …]]";
    }

    @Override
    public String getDescription() {
        return "Spawns a fake player bot at your location, or --location <x> <y> <z> <world> (admin"
                + " only; required from console/command blocks). Add actions (attack, move, left-click,"
                + " right-click, find, storage, sneak, freeze, preset <name>, wait <t>) to have the bot"
                + " start them as soon as it is online.";
    }

    @Override
    public String getPermission() {
        return Perm.SPAWN;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return Perm.has(sender, Perm.SPAWN) || Perm.has(sender, Perm.USER_SPAWN);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Action grammar
    // ═════════════════════════════════════════════════════════════════════════

    private boolean isActionName(String lower) {
        if (lower.equals(RUN) || lower.equals(PRESET) || lower.equals(WAIT)) return true;
        return allowedAction(lower) != null;
    }

    /** The registered command if {@code name} may be used as a spawn action, else null. */
    private @Nullable FppCommand allowedAction(String name) {
        if (commands == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (NEVER.contains(n) || Config.spawnActionsBlocked().stream().anyMatch(b -> b.equalsIgnoreCase(n))) {
            return null;
        }
        FppCommand c = commands.getCommand(n);
        if (c == null) return null;
        boolean allowed = BUILTIN_ACTIONS.contains(n)
                || Config.spawnActionsExtra().stream().anyMatch(e -> e.equalsIgnoreCase(n))
                || c.getUsage() != null && c.getUsage().startsWith("<bot");
        return allowed ? c : null;
    }

    /** Index of the first token that begins an action (skipping the arguments of the spawn flags). */
    private int findActionStart(String[] args) {
        int i = 0;
        while (i < args.length) {
            String t = args[i].toLowerCase(Locale.ROOT);
            switch (t) {
                case "--name", "--count", "--delay", "--preset" -> i += 2;
                case "--location" -> i += 5;
                default -> {
                    if (isActionName(t)) return i;
                    i++;
                }
            }
        }
        return args.length;
    }

    /** One parsed action step, before per-bot placeholder substitution. */
    private record Template(long delayTicks, boolean raw, List<String> tokens) {}

    /**
     * Parses {@code tail} into templates. Returns null (after messaging the sender) on any error:
     * unknown/blocked/denied action, bad duration, unknown preset, or {@code run} without permission.
     */
    private @Nullable List<Template> compile(CommandSender sender, List<String> tail, int depth) {
        List<Template> out = new ArrayList<>();
        long pendingDelay = 0;
        List<String> cur = new ArrayList<>();
        boolean curRaw = false;

        for (int i = 0; i <= tail.size(); i++) {
            String tok = i < tail.size() ? tail.get(i) : null;
            String low = tok == null ? null : tok.toLowerCase(Locale.ROOT);

            boolean boundary = tok == null || low.equals(THEN) || (low.equals(WAIT) && !curRaw);
            // `run` swallows everything up to a then/wait; so does any other action.
            if (boundary) {
                if (!cur.isEmpty()) {
                    if (!validate(sender, cur, curRaw)) return null;
                    out.add(new Template(pendingDelay, curRaw, List.copyOf(cur)));
                    pendingDelay = 0;
                    cur = new ArrayList<>();
                    curRaw = false;
                }
                if (tok != null && low.equals(WAIT)) {
                    if (i + 1 >= tail.size()) {
                        sender.sendMessage(Lang.get("spawn-action-bad-duration", "value", ""));
                        return null;
                    }
                    long ticks = parseDuration(tail.get(++i));
                    if (ticks < 0) {
                        sender.sendMessage(Lang.get("spawn-action-bad-duration", "value", tail.get(i)));
                        return null;
                    }
                    pendingDelay += ticks;
                }
                continue;
            }

            if (cur.isEmpty() && low.equals(PRESET)) {
                if (i + 1 >= tail.size()) {
                    sender.sendMessage(Lang.get("spawn-action-unknown-preset", "preset", ""));
                    return null;
                }
                List<Template> expanded = expandPreset(sender, tail.get(++i), depth);
                if (expanded == null) return null;
                for (int k = 0; k < expanded.size(); k++) {
                    Template t = expanded.get(k);
                    out.add(k == 0 ? new Template(t.delayTicks() + pendingDelay, t.raw(), t.tokens()) : t);
                }
                pendingDelay = 0;
                continue;
            }

            if (cur.isEmpty() && low.equals(RUN)) {
                curRaw = true;
                continue;
            }
            cur.add(tok);
        }
        return out;
    }

    private boolean validate(CommandSender sender, List<String> tokens, boolean raw) {
        if (raw) {
            if (!Config.spawnActionsAllowRun() || !Perm.has(sender, Perm.ADMIN)) {
                sender.sendMessage(Lang.get("spawn-action-denied", "action", RUN));
                return false;
            }
            return true;
        }
        String name = tokens.get(0);
        FppCommand action = allowedAction(name);
        if (action == null) {
            sender.sendMessage(Lang.get("spawn-action-unknown", "action", name));
            return false;
        }
        if (!action.canUse(sender)) {
            sender.sendMessage(Lang.get("spawn-action-denied", "action", name.toLowerCase(Locale.ROOT)));
            return false;
        }
        return true;
    }

    private @Nullable List<Template> expandPreset(CommandSender sender, String name, int depth) {
        String body = Config.spawnPresets().get(name.toLowerCase(Locale.ROOT));
        if (body == null || depth > 3) {
            sender.sendMessage(Lang.get("spawn-action-unknown-preset", "preset", name));
            return null;
        }
        return compile(sender, List.of(body.trim().split("\\s+")), depth + 1);
    }

    /** {@code 10t} ticks, {@code 5s} seconds, {@code 2m} minutes, bare number = seconds; -1 if invalid. */
    static long parseDuration(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        double mult = 20.0;
        char last = s.charAt(s.length() - 1);
        if (last == 't') {
            mult = 1.0;
            s = s.substring(0, s.length() - 1);
        } else if (last == 's') {
            s = s.substring(0, s.length() - 1);
        } else if (last == 'm') {
            mult = 1200.0;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double v = Double.parseDouble(s);
            if (v < 0 || Double.isNaN(v) || v > 72_000) return -1;
            return Math.round(v * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Execution
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public boolean execute(CommandSender sender, String[] rawArgs) {
        int split = findActionStart(rawArgs);
        List<String> flagArgs = new ArrayList<>(List.of(rawArgs).subList(0, split));
        List<String> tail = new ArrayList<>(List.of(rawArgs).subList(split, rawArgs.length));

        Player player = sender instanceof Player p ? p : null;
        boolean isAdmin = Perm.has(sender, Perm.SPAWN);
        boolean isUser = !isAdmin && Perm.has(sender, Perm.USER_SPAWN);
        if (!isAdmin && !isUser) {
            sender.sendMessage(Lang.get("no-permission"));
            return true;
        }
        // User-tier spawning tracks ownership, a bot limit and a cooldown against a real player's UUID -
        // none of which exist for console/command-block senders, so they're admin-only.
        if (player == null && !isAdmin) {
            sender.sendMessage(Lang.get("player-only"));
            return true;
        }

        // ── flags ──
        String customName = null;
        int count = 1;
        long initialDelay = Config.spawnActionsInitialDelayTicks();
        List<String> presetFlags = new ArrayList<>();
        Location location = player != null ? player.getLocation().clone() : null;

        for (int i = 0; i < flagArgs.size(); i++) {
            String t = flagArgs.get(i).toLowerCase(Locale.ROOT);
            switch (t) {
                case "--name" -> {
                    if (i + 1 >= flagArgs.size()) {
                        sender.sendMessage(Lang.get("spawn-invalid-name", "name", ""));
                        return true;
                    }
                    customName = flagArgs.get(++i);
                }
                case "--count" -> {
                    try {
                        count = Integer.parseInt(i + 1 < flagArgs.size() ? flagArgs.get(++i) : "");
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Lang.get("spawn-invalid-count", "max", String.valueOf(maxCount())));
                        return true;
                    }
                    if (count < 1 || count > maxCount()) {
                        sender.sendMessage(Lang.get("spawn-invalid-count", "max", String.valueOf(maxCount())));
                        return true;
                    }
                }
                case "--delay" -> {
                    long d = parseDuration(i + 1 < flagArgs.size() ? flagArgs.get(++i) : "");
                    if (d < 0) {
                        sender.sendMessage(Lang.get("spawn-action-bad-duration", "value", ""));
                        return true;
                    }
                    initialDelay = Math.max(1, d);
                }
                case "--preset" -> {
                    if (i + 1 >= flagArgs.size()) {
                        sender.sendMessage(Lang.get("spawn-action-unknown-preset", "preset", ""));
                        return true;
                    }
                    presetFlags.add(flagArgs.get(++i));
                }
                case "--location" -> {
                    if (!isAdmin) {
                        sender.sendMessage(Lang.get("no-permission"));
                        return true;
                    }
                    if (i + 4 >= flagArgs.size()) {
                        sender.sendMessage(Lang.get("spawn-invalid-location"));
                        return true;
                    }
                    double x, y, z;
                    try {
                        x = Double.parseDouble(flagArgs.get(i + 1));
                        y = Double.parseDouble(flagArgs.get(i + 2));
                        z = Double.parseDouble(flagArgs.get(i + 3));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Lang.get("spawn-invalid-location"));
                        return true;
                    }
                    String worldName = flagArgs.get(i + 4);
                    i += 4;
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        sender.sendMessage(Lang.get("spawn-world-not-found", "world", worldName));
                        return true;
                    }
                    float yaw = player != null ? player.getLocation().getYaw() : 0f;
                    float pitch = player != null ? player.getLocation().getPitch() : 0f;
                    location = new Location(world, x, y, z, yaw, pitch);
                }
                default -> {
                    sender.sendMessage(Lang.get("unknown-command", "0", "fpp"));
                    return true;
                }
            }
        }
        if (customName != null) count = 1; // a fixed name can only belong to one bot

        if (location == null) {
            // Console/command-block sender that didn't pass --location.
            sender.sendMessage(Lang.get("spawn-invalid-location"));
            return true;
        }

        // ── actions (validated before anything is spawned) ──
        List<String> fullTail = new ArrayList<>();
        for (String preset : presetFlags) {
            fullTail.add(PRESET);
            fullTail.add(preset);
            fullTail.add(THEN);
        }
        fullTail.addAll(tail);
        List<Template> templates = List.of();
        if (!fullTail.isEmpty()) {
            if (!Config.spawnActionsEnabled()) {
                sender.sendMessage(Lang.get("spawn-actions-disabled"));
                return true;
            }
            templates = compile(sender, fullTail, 0);
            if (templates == null) return true;
        }

        if (player != null && !Perm.has(sender, Perm.BYPASS_COOLDOWN) && manager.isOnCooldown(player.getUniqueId())) {
            long remaining = manager.getRemainingCooldown(player.getUniqueId());
            sender.sendMessage(Lang.get("spawn-cooldown", "seconds", String.valueOf(remaining)));
            return true;
        }

        // ── spawn ──
        boolean bypassMax = Perm.has(sender, Perm.BYPASS_MAX);
        int limit = Integer.MAX_VALUE;
        if (isUser) {
            int permLimit = Perm.resolveUserBotLimit(sender);
            limit = permLimit >= 0 ? permLimit : Config.userBotLimit();
        }

        // Optional spawn price: only the user tier pays, and fpp.spawn.free waives it.
        double unitPrice = isUser && !Perm.has(sender, Perm.SPAWN_FREE) ? Config.spawnPrice() : 0.0;
        double charged = 0.0;
        me.bill.fakePlayerPlugin.economy.EconomyManager economy = unitPrice > 0 ? plugin().getEconomyManager() : null;
        if (unitPrice > 0) {
            if (economy == null || !economy.isAvailable()) {
                sender.sendMessage(Lang.get("rent-economy-unavailable"));
                return true;
            }
            double total = unitPrice * count;
            if (!economy.has(player, total)) {
                sender.sendMessage(Lang.get(
                        "rent-insufficient-funds",
                        "cost",
                        economy.format(total),
                        "balance",
                        economy.format(economy.getBalance(player))));
                return true;
            }
        }

        List<FakePlayer> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (unitPrice > 0
                    && economy.charge(player, unitPrice, "spawn")
                            != me.bill.fakePlayerPlugin.economy.EconomyManager.ChargeResult.OK) {
                sender.sendMessage(Lang.get("rent-transaction-failed"));
                break;
            }
            charged = unitPrice > 0 ? unitPrice : 0.0;
            if (isUser && manager.getBotsOwnedBy(player.getUniqueId()).size() >= limit) {
                sender.sendMessage(Lang.get("spawn-user-limit-reached", "limit", String.valueOf(limit)));
                break;
            }
            Set<UUID> before = new HashSet<>();
            for (FakePlayer existing : manager.getActivePlayers()) before.add(existing.getUuid());

            int result;
            if (isUser) {
                result = customName != null
                        ? manager.spawn(location, 1, player, customName, false, BotType.AFK)
                        : manager.spawnUserBot(location, 1, player, false, BotType.AFK);
            } else {
                result = manager.spawn(location, 1, player, customName, bypassMax, BotType.AFK);
            }
            if (!handleSpawnResult(sender, result, customName)) {
                if (charged > 0) economy.refund(player, charged, "spawn failed");
                break;
            }

            for (FakePlayer fp : manager.getActivePlayers()) {
                if (!before.contains(fp.getUuid())) {
                    spawned.add(fp);
                    break;
                }
            }
        }
        if (spawned.isEmpty()) return true;

        if (player != null) manager.recordSpawnCooldown(player.getUniqueId());
        sender.sendMessage(Lang.get(
                "spawn-success", "count", String.valueOf(spawned.size()), "total", String.valueOf(manager.getCount())));

        if (!templates.isEmpty()) {
            for (int i = 0; i < spawned.size(); i++) {
                queue(sender, player, spawned.get(i), i + 1, location, templates, initialDelay);
            }
        }
        return true;
    }

    private static me.bill.fakePlayerPlugin.FakePlayerPlugin plugin() {
        return me.bill.fakePlayerPlugin.FakePlayerPlugin.getInstance();
    }

    private static int maxCount() {
        return Config.spawnActionsMaxCount();
    }

    /** Builds the per-bot command lines (placeholders substituted) and parks them on the bot. */
    private void queue(
            CommandSender sender,
            @Nullable Player player,
            FakePlayer fp,
            int index,
            Location at,
            List<Template> templates,
            long initialDelay) {
        Map<String, String> vars = Map.of(
                "{bot}", fp.getName(),
                "{owner}", sender.getName(),
                "{x}", String.valueOf(at.getBlockX()),
                "{y}", String.valueOf(at.getBlockY()),
                "{z}", String.valueOf(at.getBlockZ()),
                "{world}", at.getWorld() != null ? at.getWorld().getName() : "",
                "{index}", String.valueOf(index));

        List<SpawnActions.Step> steps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Template t : templates) {
            List<String> toks = new ArrayList<>();
            for (String tok : t.tokens()) toks.add(substitute(tok, vars));
            String line;
            if (t.raw()) {
                line = String.join(" ", toks);
                names.add("run");
            } else {
                String cmd = toks.get(0).toLowerCase(Locale.ROOT);
                line = "fpp " + cmd + " " + fp.getName()
                        + (toks.size() > 1 ? " " + String.join(" ", toks.subList(1, toks.size())) : "");
                names.add(cmd);
            }
            steps.add(new SpawnActions.Step(t.delayTicks(), line));
        }
        UUID owner = player != null ? player.getUniqueId() : new UUID(0, 0);
        fp.setMetadata(SpawnActions.KEY, new SpawnActions(owner, player == null, initialDelay, steps));
        sender.sendMessage(Lang.get(
                "spawn-actions-queued",
                "name",
                fp.getName(),
                "actions",
                String.join(" → ", new LinkedHashSet<>(names))));
    }

    private static String substitute(String token, Map<String, String> vars) {
        String out = token;
        for (Map.Entry<String, String> e : vars.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    /** Sends the failure message for a spawn result code; returns true when the spawn succeeded. */
    private boolean handleSpawnResult(CommandSender sender, int result, String customName) {
        switch (result) {
            case -1 -> sender.sendMessage(Lang.get("spawn-max-reached", "max", String.valueOf(Config.maxBots())));
            case -2 -> sender.sendMessage(Lang.get("spawn-invalid-name", "name", String.valueOf(customName)));
            case -4 -> sender.sendMessage(Lang.get("spawn-name-online", "name", String.valueOf(customName)));
            case 0 -> sender.sendMessage(
                    customName != null
                            ? Lang.get("spawn-name-taken", "name", customName)
                            : Lang.get("spawn-no-names-left"));
            default -> {
                return result > 0;
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tab completion
    // ═════════════════════════════════════════════════════════════════════════

    private List<String> actionNames(CommandSender sender) {
        Set<String> names = new LinkedHashSet<>(new java.util.TreeSet<>(BUILTIN_ACTIONS));
        if (commands != null) {
            for (FppCommand c : commands.getCommands()) {
                if (allowedAction(c.getName()) != null) names.add(c.getName());
            }
        }
        List<String> out = new ArrayList<>();
        for (String n : names) {
            FppCommand c = allowedAction(n);
            if (c != null && c.canUse(sender)) out.add(n);
        }
        out.add(PRESET);
        out.add(WAIT);
        if (Config.spawnActionsAllowRun() && Perm.has(sender, Perm.ADMIN)) out.add(RUN);
        return out;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!canUse(sender)) return List.of();
        boolean isAdmin = Perm.has(sender, Perm.SPAWN);
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        // Argument of a flag.
        if (args.length >= 2) {
            String prev = args[args.length - 2].toLowerCase(Locale.ROOT);
            if (prev.equals("--name")) return List.of();
            if (prev.equals("--count")) return List.of("2", "3", "5", "10");
            if (prev.equals("--delay")) return List.of("5s", "10s", "30s", "1m");
            if (prev.equals("--preset") || (prev.equals(PRESET) && findActionStart(args) < args.length)) {
                return Config.spawnPresets().keySet().stream()
                        .filter(n -> n.startsWith(typed))
                        .toList();
            }
            if (prev.equals(WAIT)) return List.of("5s", "10s", "30s", "1m", "20t");
        }

        int locationFlag = indexOf(args, "--location");
        if (isAdmin && locationFlag >= 0 && args.length - 1 - locationFlag <= 4) {
            int offset = args.length - 1 - locationFlag; // 1=x, 2=y, 3=z, 4=world
            return switch (offset) {
                case 1 -> typed.isEmpty() ? List.of("<x>") : List.of();
                case 2 -> typed.isEmpty() ? List.of("<y>") : List.of();
                case 3 -> typed.isEmpty() ? List.of("<z>") : List.of();
                case 4 -> Bukkit.getWorlds().stream()
                        .map(World::getName)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed))
                        .toList();
                default -> List.of();
            };
        }

        // Inside an action: hand over to that command's own completion (the bot stands in as arg 0).
        int actionAt = findActionStart(args);
        if (actionAt < args.length) {
            int last = actionAt;
            for (int i = actionAt; i < args.length; i++) {
                String a = args[i].toLowerCase(Locale.ROOT);
                if (a.equals(THEN)) last = i + 1;
                else if (a.equals(WAIT)) last = i + 2;
            }
            if (last >= args.length)
                return actionNames(sender).stream().sorted().toList();
            String first = args[last].toLowerCase(Locale.ROOT);
            if (last == args.length - 1) {
                List<String> out = new ArrayList<>();
                for (String n : actionNames(sender)) if (n.startsWith(first)) out.add(n);
                return out;
            }
            FppCommand action = allowedAction(first);
            List<String> out = new ArrayList<>();
            if (action != null && action.canUse(sender)) {
                List<String> sub = new ArrayList<>();
                sub.add("<bot>");
                sub.addAll(List.of(args).subList(last + 1, args.length));
                out.addAll(action.tabComplete(sender, sub.toArray(new String[0])));
            }
            if (THEN.startsWith(typed)) out.add(THEN);
            if (WAIT.startsWith(typed)) out.add(WAIT);
            return out;
        }

        List<String> suggestions = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (String a : args) used.add(a.toLowerCase(Locale.ROOT));
        for (String flag : List.of("--name", "--count", "--delay", "--preset")) {
            if (!used.contains(flag) && flag.startsWith(typed)) suggestions.add(flag);
        }
        if (isAdmin && locationFlag < 0 && "--location".startsWith(typed)) suggestions.add("--location");
        for (String action : actionNames(sender)) {
            if (action.startsWith(typed)) suggestions.add(action);
        }
        return suggestions;
    }

    private static int indexOf(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) if (args[i].equalsIgnoreCase(flag)) return i;
        return -1;
    }
}
