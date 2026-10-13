# FAQ & Troubleshooting

## General

### Q: What server software is supported?
**A:** Paper/Purpur 1.21+ (up to 1.21.11 and the year-based 26.1.x–26.3.x releases) and Folia. FPP has full Folia support with region-threaded bot spawning.

### Q: Does it work on Spigot or CraftBukkit?
**A:** No. FPP uses Paper-specific APIs and NMS Mojang-mapped classes.

### Q: What Java version do I need?
**A:** JDK 21+ for both the server and for building from source.

### Q: Can I use this on a server with ViaVersion?
**A:** Yes, but the server itself must be Paper 1.21+ - ViaVersion only affects the *client* versions
that can connect, not the server's own version. If ViaVersion is installed, bots automatically register
themselves with it as running the server's own native protocol version, so Via never treats a bot as an
unrecognized connection (no "can't find version" console warnings).

### Q: How do I see what a bot is doing?
**A:** The nametag above every bot shows its live activity (idle / moving / mining / fighting /
eating / searching / sneaking …), updated twice a second. The `/fpp list` GUI shows the same activity per
bot, and the pathfinding particle debug view (per-bot settings → 🧭 ᴘᴀᴛʜꜰɪɴᴅɪɴɢ → ꜱʜᴏᴡ ᴘᴀᴛʜ, or
globally via `/fpp settings` → 🐛 ᴅᴇʙᴜɢ → ꜱʜᴏᴡ ᴀʟʟ ᴘᴀᴛʜꜱ) renders each bot's route.

### Q: Can I see debug output in-game instead of the console?
**A:** Yes. Enable `debug-chat: true` in `debug.yml` (or via `/fpp settings` → Debug). All debug output will be sent to online players with `fpp.op` or `fpp.notify` as chat messages.

## Bots & Spawning

### Q: Why don't bots show in the tab list or server-list player count?
**A:** By design. Bots are unlisted in the tab by default, subtracted from the server-list ping
count, and removed from its hover sample. They also earn no advancements and send no
join/leave/death/advancement chat messages (bots never produce chat events at all). An admin can enable `tab-list.enabled` (or the Body toggle in
`/fpp settings`); bots are then listed only as `[Bot] - <botname>`. They are never counted as
players in `%fpp_online%` / `%fpp_total%` either way.

### Q: A boat or minecart ran into my bot and it got on - can I stop that?
**A:** That's `body.auto-mount` (on by default): vehicles pick bots up the way they pick up
villagers. Turn it off in the config or `/fpp settings` → Body. A bot only leaves a seat when
deliberately dismounted (`/fpp sneak`) and a real player boarding a boat always takes the first
(controlling) seat.

### Q: A bot was in a boat when it despawned - where did the boat go?
**A:** Same as a real player logging out: if the bot was the only player aboard, the vehicle goes
with it and is re-created, with the bot seated, the next time that bot spawns. If another player
was aboard, the vehicle stays and the bot boards it again when it comes back. Saved in
`data/bot-mounts.yml`.

### Q: Players get kicked with "Network Protocol Error ... Cannot remove from team".
**A:** Fixed in v2.1.0. Bots' vanilla names are hidden with a hand-pushed scoreboard team that
could desync from another plugin's team (TAB, LuckPerms prefixes, AFK displays). A per-viewer packet
guard now keeps every client's team state consistent.

### Q: Bots appear but have no skin.
**A:** Skins come from the rarity pools in `plugins/FakePlayerPlugin/skins/` (`main_skin.txt` +
`1-<N>%.txt` files of NameMC URLs). A fresh skin needs one MineSkin signing round-trip (a few
seconds) the first time it's ever used; after that it's cached forever in `data/skin-cache.yml`.
Enable the `skin-pool` debug topic to trace the pipeline. To disable skins set
`skin.rare-pools: false`.

### Q: I removed a bot's name tag (e.g. with `/kill @e[type=text_display]`) and it came back - why?
**A:** By design. The name tag is a real world entity, so anything that can remove an entity can remove
it - but a self-heal check runs every 10 ticks and respawns it the moment it's gone. There's no way to
make a bot permanently tag-less; the mandatory "ʙᴏᴛ ʙʏ {owner}" disclosure row can't be disabled either.

### Q: I can still see the bot's normal vanilla name overlapping the custom name tag - why?
**A:** The vanilla over-head name is hidden by putting each bot on a packet-only scoreboard team, and
that is re-applied every few ticks. Since v2.0.6.1 those packets no longer depend on the tab-list
packet setup, which was the most likely reason the hide was skipped. If you still see it, another plugin
may be moving bots onto its own scoreboard team (a player can only be on one team at a time) - enable
debug and look for `sendTeam` / `hideVanillaNameNow` / "hide-nametag" lines in the log.

### Q: How do bot UUIDs work?
**A:** Deterministic and name-derived with a recognizable `fb07` prefix: `bot` →
`fb070000-0000-0000-0000-000000000001`, `bot2` → `…-000000000002`; custom names hash into the low
bits. They can never collide with real accounts.

### Q: Spawn cooldown is blocking players.
**A:** Set `spawn-cooldown: 0` in `config.yml` or grant `fpp.bypass.cooldown`.

### Q: "Max bots reached" but I have fewer than the limit.
**A:** The limit is both global (`limits.max-bots`) and personal (`fpp.spawn.limit.N`). Check both.

### Q: Can I spawn several bots at once?
**A:** No - `/fpp spawn` intentionally creates exactly one bot per command (auto-named, or
`--name <name>` for a custom name).

### Q: Can I run `/fpp spawn` from the server console or a command block?
**A:** Yes, if you hold `fpp.spawn` (the admin tier) - but you must pass `--location <x> <y> <z>
<world>`, since neither has a location of its own to spawn at by default. `fpp.spawn.user` (personal
bot limit, ownership, cooldown) needs a real player and isn't usable from console/command blocks.

### Q: Can I spawn a bot somewhere other than my own location?
**A:** Admins (`fpp.spawn`) can with `/fpp spawn --location <x> <y> <z> <world>`, combinable with
`--name`. User-tier spawns (`fpp.spawn.user`) always spawn at the sender's own location.

## Tasks, Combat & Pathfinding

### Q: How do I make a bot fight mobs?
**A:** One line: `/fpp attack <bot>` makes it guard its post and hit hostile mobs that come into reach
(an AFK mob farm); `/fpp attack <bot> --chase --range 16` hunts them through pathfinding and returns to
its post afterwards. Narrow it with `--mobs zombie,skeleton`, `--priority lowest-health`, or use `--any`;
`/fpp attack <bot> --status` shows what it is fighting. The same settings live in the bot's `🗡 ᴘᴠᴇ` GUI
page and persist across restarts. Hits are real client attacks (crosshair pick, full attack charge, best
weapon auto-equipped), so damage, cooldowns and enchantments are the server's own.

### Q: Can I make bots pathfind to coordinates or follow players?
**A:** Yes - `/fpp move <bot> --to <bot|player>` follows a target live, and
`/fpp move <bot> --coords <x> <y> <z> [world]` walks to a fixed point. Both are backed by the
built-in A* pathfinder, which also climbs, swims, opens doors and - when enabled for the bot - parkours,
digs and builds.

### Q: Bot is stuck and won't move.
**A:** The pathfinder auto-recalculates when stuck, and abandons a target after a few fruitless
cycles (`pathfinding.max-stuck-cycles`). Enable the `pathfinding` debug topic for grep-friendly
`event=STUCK/PATH_REJECTED/…` console lines explaining exactly what happened. `/fpp stop <bot>`
cancels everything manually.

### Q: How does `/fpp find` work?
**A:** It looks for blocks the bot could actually *see* - ones with an exposed face - ranks them by
walking effort, walks there, mines with the real client dig logic, and keeps following the vein as it
opens up. Buried ore is never detected (it is not X-ray); use `--scan` to see what is visible. It
auto-equips the best tool, skips unreachable blocks, and when the inventory runs low it deposits into the
nearest registered storage (`/fpp storage`) before resuming. Accepts lists and aliases:
`/fpp find bot ores`, `/fpp find bot oak_log,birch_log`.

### Q: How do I clear out a whole chunk or area?
**A:** `/fpp clear <bot>` clears the chunk the bot stands in, layer by layer from the surface down; use
`--area x1 y1 z1 x2 y2 z2`, `--selection` (WorldEdit) or `--radius n` for other shapes, and
`--depth n`/`--bottom y` to limit how far down it goes. It runs on its own: it digs inside the region
only, seals water/lava, collects drops, deposits at a registered storage (or `--trash` junk), recovers
when stuck and builds up with scaffold blocks if it must. Check `/fpp clear <bot> --status`.

### Q: Can a bot start working the moment it spawns?
**A:** Yes - add an action to the spawn command: `/fpp spawn --name Guard attack --chase`,
`/fpp spawn --count 3 --preset guard`, `/fpp spawn --name Miner clear --chunk then wait 30s attack`.
Presets live under `spawn-actions.presets`.

### Q: Can a bot do two things at once (mine and fight, walk and use)?
**A:** Yes. Starting a new task (`move` / `find` / `left-click` / `right-click` / `attack`) no longer
stops the bot's other running tasks, and PVE auto-combat always fights back regardless of what else
the bot is doing. The one real limit is movement: a bot has one body, so only one thing can actually be
walking it around at a time. That's arbitrated by priority instead of by cancelling - an explicit
`/fpp move` always wins, a manual task's own walk-to-reach outranks background movement (PVE's chase,
an auto-deposit trip), and whichever loses out just waits its turn instead of hijacking the bot mid-walk.
Two hand-actions aimed at two *different* targets at once will still visibly alternate the bot's aim
between them each tick (one head, one main hand) - that's a real single-body limit, not a bug.

### Q: How do I make a bot eat automatically?
**A:** Open its settings → `🍖 ᴀᴜᴛᴏ-ᴇᴀᴛ`. Toggle **ᴀᴜᴛᴏ-ᴇᴀᴛ** on, set the **ʜᴜɴɢᴇʀ ᴛʜʀᴇꜱʜᴏʟᴅ**
(0-19; the bot eats at or below it), and open **ᴀʟʟᴏᴡᴇᴅ ꜰᴏᴏᴅꜱ** to pick which foods it may eat
(none selected = any food). When hungry the bot prefers food in its off-hand, then hotbar, then
inventory. Eating from the off-hand - the default/preferred source - runs **fully in parallel**: it
doesn't pause mining, moving, or combat at all, the same way a real player can snack on something in
their off-hand mid-task. Only the main-hand fallback (no off-hand food available) still briefly pauses
hand-actions, since it has to borrow the main hand itself for the eat animation. The global default
threshold is `automation.auto-eat-threshold` in `config.yml`.

### Q: My bot flips levers / presses buttons just by looking at the wall they're on.
**A:** Fixed - the bot now must aim at the button/lever's actual hit box to trigger it, exactly like
a real player. Aiming at the block it's mounted on (or a corner of that face) no longer activates
the switch.

### Q: How does the bot decide where to stand for a click?
**A:** If the target is already in reach it clicks from where it is. If not, it walks to a spot it
can reach the target from - preferring **your own standing location** (since you just aimed at the
target from there, it's a provably reachable vantage) before falling back to searching around the
target itself.

## Economy / Rental

### Q: How do I let players pay for bots?
**A:** Set `economy.enabled: true` in `config.yml`, install Vault (or ExcellentEconomy), and set your
prices under `economy.rental.*`. Players then use `/fpp rent buy <hours>` /
`/fpp rent extend <bot> <hours>`. See [Economy](Economy) for full setup.

### Q: Does it work with EssentialsX's/CMI's economy, not just "Vault" the plugin?
**A:** Yes - FPP talks to Vault's `Economy` *service*, which any Vault-compatible plugin (EssentialsX,
CMI, the "Vault2.0" reimplementation, ExcellentEconomy-with-Vault-present, …) registers into. You
don't need the literal Vault.jar specifically as long as something provides that service.

### Q: Can I use my own shop plugin (ShopGUIPlus, EconomyShopGUI, …) instead of FPP's built-in charging?
**A:** Yes - that's exactly what `/fpp rent give <player> <bot|--new> <hours>` is for. It never
touches an economy plugin itself; point your shop's reward/command action at it and your shop plugin
handles the payment however it already does. `economy.enabled` doesn't even need to be `true` for
this path.

### Q: What happens when a rented bot's time runs out?
**A:** It despawns through the same safe path as `/fpp despawn` (inventory/XP saved normally) and the
owner is notified if online. They get a one-time warning `economy.rental.warn-minutes-before-expiry`
minutes beforehand too.

### Q: Can I make specific bots or players exempt from expiring?
**A:** Grant `fpp.rent.unlimited` - that player's rented bots never expire from time running out,
regardless of what `rentalExpiresAt` they were given.

## Database

### Q: Can I use SQLite for a network setup?
**A:** No. SQLite is local-only. Use MySQL for multi-server setups.

### Q: Database connection fails on startup.
**A:** Verify credentials, firewall rules, and that the MySQL user has CREATE/ALTER permissions (schema migrations need them).

## Performance

### Q: Server lag with many bots.
**A:**
- Lower `chunk-loading.view-distance` or set `mass-disable-threshold` lower (bots then report a smaller
  render distance; the server's own chunk system does the rest)
- Reduce `head-ai.tick-rate`
- Increase `performance.position-sync-distance` (or set to `128`)
- Reduce bot count or spawn in batches
- Use `/fpp perf top` / `/fpp perf report` to measure

## Building

### Q: How do I tell which jar I am running?
**A:** Check the **Build** row in the startup banner, hover the creator head at the bottom-left of any menu, or use `%fpp_build%`. The build ID changes on every build.

### Q: How do I build from source?
**A:** JDK 21+ and `./gradlew clean shadowJar`. The paperweight dev bundle downloads automatically.

### Q: `velocity-companion` or `bungee-companion` build fails.
**A:** These directories are `.gitignored` and may not exist. Only build them if you have the companion source.

## Fall Damage

### Q: Bots take fall damage even with `body.damageable: false`.
**A:** `body.damageable` only controls player/entity damage. Fall damage is governed by `combat.fall-damage.enabled` and is independent. Set `combat.fall-damage.enabled: false` to disable fall damage entirely.
