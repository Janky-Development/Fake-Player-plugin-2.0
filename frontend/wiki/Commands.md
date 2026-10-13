# Commands

All commands are prefixed with `/fpp` (aliases: `fakeplayer`, `fp`).

## Core Commands

| Command | Usage | Description | Permission |
|---------|-------|-------------|------------|
| **spawn** | `[--name <n>] [--count <n>] [--location <x> <y> <z> <world>] [--delay <t>] [--preset <p>] [<action> [args] [then\|wait <t> <action> ...]]` | Spawn a fake player bot at your own location - auto-named `bot`, `bot2`, … or custom-named via `--name` (`--count` spawns several, capped by `spawn-actions.max-count`). `--location` targets another spot/world instead (admin only; mandatory from console/command blocks). **Actions** run as you once the bot is online: any bot-first command (`attack`, `move`, `find`, `clear`, `left-click`, `right-click`, `storage`, `sneak`, `freeze`, …), `preset <name>`, `wait <t>`, admin `run <command>`; placeholders `{bot} {owner} {x} {y} {z} {world} {index}`; durations `10t`/`5s`/`2m`; chain with `then`. E.g. `/fpp spawn --name Guard attack --chase --range 12`. Presets and limits: `spawn-actions` in config | `fpp.spawn` (admin) / `fpp.spawn.user` (user, in-game only) |
| **despawn** | `<name> \| --all \| --own \| --count <n> \| --random [--count <n>]` | Despawn bots by name, owner, count, or random selection | `fpp.despawn` |
| **list** | `[page]` | List all currently active bots | `fpp.list` |
| **menu** | - (alias `gui`) | Open the main menu hub: bots, spawn, settings, chunk loading, rent, help, despawn-all | `fpp.help` (cards check their own permission) |
| **tph** | `[botname\|all]` | Teleport your bot(s) to you | `fpp.tph` |
| **tp** | `[botname]` | Teleport you to a bot | `fpp.tp` |
| **xp** | `/fpp xp <bot>` | Collect XP from a bot | `fpp.xp` |
| **move** | `<bot\|--all> --to <bot\|player>  \|  <bot\|--all> --coords <x> <y> <z> [world]  \|  <bot\|--all> --stop` | Pathfind to a bot/player (follows live) or to coordinates | `fpp.move` (+ `fpp.move.to` / `fpp.move.coords`) |
| **left-click** | `<bot> [--once\|--repeat\|--hold\|--stop]  \|  --stop` | Bot left-clicks: breaks the block / attacks the entity at the **exact point** you're aiming at; walks to a reachable vantage first if out of reach | `fpp.left-click` |
| **right-click** | `<bot> [--once\|--repeat\|--hold\|--stop]  \|  --stop` | Bot right-clicks: uses held items and interacts with the block/entity at the **exact point** you're aiming at; must aim at a button/lever's hit box to trigger it | `fpp.right-click` |
| **find** | `<bot> <block[,block…]\|ores\|logs\|…> [--radius <n>] [--count <n>] [--prefer-visible] [--hazards] [--scan]  \|  <bot> --stop` | Find and mine blocks the bot could actually **see** (an exposed face - buried blocks are never detected, no X-ray). Ranks by walking effort, follows a vein as it opens up, mines with the real client dig logic, auto-deposits when full. `--scan` only reports what is visible. |-c <n>] [--prefer-visible]  \|  <bot> --stop  \|  --stop` | Bot searches for and reports nearby blocks | `fpp.find` |
| **clear** | `<bot> [--chunk [cx cz] \| --area x1 y1 z1 x2 y2 z2 \| --selection \| --radius n] [--top y] [--bottom y \| --depth n] [--keep blocks] [--trash [blocks]] [--containers] [--tunnel] [--no-build] [--no-seal] \| <bot> --status \| <bot> --stop` | Self-running excavation: clears a chunk/area layer by layer, top to bottom, from inside the layer; digs down into the next layer, seals water/lava, collects drops, deposits at storage or discards `--trash`, recovers when stuck and builds up with scaffold blocks. Digs only inside the region | `fpp.clear` (op) |
| **storage** | `<bot> [storage_name\|--list\|--remove <name>\|--clear\|--enable <name>\|--disable <name>\|--deposit [name]]` | Set or manage bot storage targets (chest/barrel/hopper/shulker) | `fpp.storage` |
| **attack** | `<bot\|--all> [--stand\|--chase] [--hostile\|--any\|--mobs <types>] [--range <n>] [--priority nearest\|lowest-health] [--once]  \|  <bot> --status  \|  --stop` | Puts a bot on fighting duty: guards its post (AFK mob farm) or hunts and returns, with real client attacks | `fpp.attack` |
| **sneak** | `<bot> [on\|off\|toggle]` | Toggle or set the sneaking state for a live bot body | `fpp.sneak` |
| **stop** | `[<bot>\|all]` | Stop all active tasks for one bot or all bots | `fpp.stop` |
| **freeze** | `<bot\|all> [on\|off]` | Freeze or unfreeze a bot in place | `fpp.freeze` |
| **inventory** | `/fpp inventory <bot>` (alias: `inv`) | Open a bot's full inventory | `fpp.inventory` |
| **save** | - | Save all active bot data immediately | `fpp.save` |
| **setowner** | `<bot> <player>` | Set the owner of a bot | `fpp.setowner` |
| **rename** | `<bot> <new name>` | Change a bot's display name (name-tag, tab list, command output). Identity/UUID is unchanged and the "ʙᴏᴛ ʙʏ {owner}" row is kept. Supports colour codes; max 32 visible chars | `fpp.rename` |
| **info** | `[bot\|spawner] <name>` | Query bot session history from the database | `fpp.info` (admin) / `fpp.info.user` (own bots) |
| **check** | `[--deep\|--simulation\|--commands\|--listeners\|--nms\|--database\|--folia\|--world\|--config\|--extensions\|--memory\|--all]` | Run a system health check | `fpp.check` |
| **reload** | `[all\|config\|lang]` | Reloads the plugin configuration (optionally target a subsystem) | `fpp.reload` |
| **settings** | `[bot]` | Open the interactive settings GUI (global, per-bot, or **debug** category) | `fpp.settings` |
| **rent** | `buy <hours> \| extend <bot> <hours> \| quote <hours> [--new] \| info [bot] \| auto <bot> <on [hours]\|off> \| list [player] \| give <player> <bot\|--new> <hours> \| clear <bot>` | Rent a bot with real economy currency, billed per hour; quotes, bulk/permission discounts, auto-renew - see [Economy](Economy) | `fpp.rent` (+ `.info` / `.auto` / `.give` / `.admin` / `.free`) |
| **auth** | `on\|off\|status [bot]\|reset <bot>\|setpassword <bot> <password>` | Manage bot auto-register/login against an installed login plugin - see [Configuration](Configuration) | `fpp.auth` |
| **perf** | `check\|top\|report\|report stop\|history\|spark` | Performance dashboard, history, benchmark reports, and Spark CPU profiler | `fpp.perf` |
| **help** | `[page]` | Shows the command help menu | `fpp.help` |

## Usage Examples

```
/fpp spawn                            # spawn one auto-named bot at your location
/fpp spawn --name Miner               # spawn one bot with a custom name
/fpp spawn --location 100 64 -200 world_nether   # spawn one bot at a specific location/world (admin only)
/fpp spawn --name Miner --location 100 64 -200 world   # from console/command block, --location is required
/fpp despawn --all                    # remove all bots
/fpp despawn --own                    # remove bots you spawned
/fpp despawn --random --count 3       # remove 3 random bots
/fpp move bot1 --to bot2                        # pathfind bot1 to bot2's location (follows live)
/fpp move bot1 --to Steve                       # pathfind bot1 to a real online player
/fpp move bot1 --coords 100 64 -200             # pathfind bot1 to coordinates
/fpp left-click bot1 --once           # break/attack the target once
/fpp right-click bot1 --repeat        # repeatedly use/interact with target
/fpp spawn --name Guard attack --chase --range 12     # spawn and start hunting
/fpp spawn --count 3 --preset guard                # three guard bots
/fpp spawn --name Miner left-click --hold then wait 30s right-click --repeat
/fpp spawn --name Pal run msg {owner} {bot} is ready (admin raw command)
/fpp attack bot1                      # guard: hit hostile mobs that come into reach (AFK farm)
/fpp attack bot1 --chase --range 16   # hunt hostile mobs within 16 blocks, return to post after
/fpp attack bot1 --mobs zombie,skeleton --priority lowest-health
/fpp attack --all --any               # every bot, any non-player mob
/fpp attack bot1 --once               # one landed hit, then stop
/fpp attack bot1 --status             # what it is fighting right now
/fpp sneak bot1 toggle                # toggle sneak state
/fpp rename bot1 Miner                 # rename bot1's display name to "Miner"
/fpp stop bot1                        # stop all active tasks on bot1
/fpp freeze bot1 on                   # freeze bot1
/fpp inv bot1                         # open bot1 inventory
/fpp check --all                      # run all health checks
/fpp info bot1                        # show session history for bot1
/fpp rent buy 4                       # spawn a new rented bot for 4 hours
/fpp rent extend bot1 4               # add 4 more hours to bot1
/fpp rent info                        # show remaining time on your rented bots
/fpp rent give Steve --new 4          # (console/admin) grant Steve a new 4h bot, no charge
/fpp auth on                          # enable bot auto-register/login
/fpp auth status bot1                 # check whether bot1 has a remembered password
/fpp auth reset bot1                  # forget bot1's password - it registers fresh next join
```

## Notes

- `--all` on task commands sends the command to every bot the sender can administer.
- `--once` performs a single action and then stops.
- `--stop` cancels the command's activity for the specified bot(s).
- `spawn` creates exactly **one** bot per command - the bulk `[amount]` form and the bot-type tag were removed.
- `spawn` spawns at your own location by default; admins (`fpp.spawn`) can target another spot/world with `--location <x> <y> <z> <world>`. Console and command-block senders have no location of their own, so `--location` is mandatory for them, and they're admin-tier only - `fpp.spawn.user` (personal bot limit, ownership, cooldown) needs a real player.
- `--name` names are validated (1-16 chars, letters/numbers/underscores, badword filter) and rejected if a bot already uses them or a real player with that name is online. The mandatory "ʙᴏᴛ ʙʏ {owner}" nametag row still marks every bot as a bot regardless of its name.
- `/fpp move` is pathfinding-only: `--to <bot|player>` (follows the target live if it keeps moving) or `--coords <x> <y> <z> [world]`, backed by the in-house tick-costed A* pathfinding engine (walk, step, drop, climb, swim, doors, parkour, break/bridge/pillar when enabled). Directional raw-input movement was removed.
- `/fpp attack` is the one-line fighter setup and shares its persistent settings with the bot's `🗡 ᴘᴠᴇ` GUI page. Combat is the vanilla client's: the bot looks at its target, the crosshair pick decides what is in reach and sight, and the hit is sent as a normal attack packet gated on the weapon's attack charge. Defaults (hostile mobs only, tamed pets and villagers spared, best weapon auto-equipped, optional falling criticals, return-to-post after a chase) live under `attack-mob:` in `config.yml`.
- `left-click` and `right-click` are the core click automation commands; older mine/use/place-style commands were removed.
- **Precise aim & vantage:** click commands aim at the exact point you were looking at (not the block-face centre). If the target is out of reach, the bot walks to a spot it can reach it from - preferring your own standing location (a vantage the target is provably aim-able from) before searching around the target. `right-click` only activates a button/lever when the bot actually aims at the switch's hit box, not the block it's mounted on.
- **Configurable pacing:** the tick interval between presses during `--repeat` (and the use-delay of `right-click --hold`) is set server-wide by `left-click.interval-ticks`/`right-click.interval-ticks` in `config.yml` (see [Configuration](Configuration)), and each bot can override its own value in the settings GUI (`⚙ ɢᴇɴᴇʀᴀʟ` → ʟᴇꜰᴛ/ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ, 1–40 ticks). Entity attacks during `left-click` are unaffected - those are still paced solely by the held weapon's real attack-speed cooldown.
- **Auto tool-switch:** before starting a dig, `left-click` swaps to the best available tool (highest tier of the right category - pickaxe/axe/shovel/hoe) already in the bot's inventory, the way a competent player would. Toggle with `left-click.auto-tool-switch` in `config.yml` (default on).
- **Off-hand fallback:** `right-click` tries the main hand first; if that action has no effect (e.g. the main-hand item has no use), it retries with whatever's in the off-hand - so a bot carrying a shield or food off-hand can actually use it, exactly like a real client's own hand loop.
- **Multitasking:** starting a new task no longer stops the bot's other running tasks - `move`, `find`, `left-click`, `right-click`, `attack`, and PVE auto-combat can all be active on the same bot at once. A bot still has only one body, so movement is arbitrated by priority (`move` > a hand-action's walk-to-reach > background movement like PVE's chase) rather than by cancellation; whichever loses out just declines gracefully instead of hijacking the bot mid-walk. Two hand-actions aimed at two different targets at once will still visibly alternate the bot's aim between them each tick - that's a real single-body limit, not a bug.
- **Auto-eat runs in parallel:** eating from the off-hand (the default/preferred source) no longer pauses anything at all - mining, moving, and combat keep going untouched. Only the main-hand fallback (no off-hand food available) still briefly pauses hand-actions, since it borrows the main hand itself for the eat animation.
- All core sub-commands use `--flag` style only (no bare-word duplicates like `list`/`enable`/`toggle` as an alternative spelling of a `--flag`).
- **Auth**: off by default (`auth.enabled: false` in `config.yml`, or `/fpp auth on`). A bot stands still and doesn't turn its head from join until its register/login outcome is known, exactly like a real not-yet-authenticated player. See [Configuration](Configuration) for the full config reference.
