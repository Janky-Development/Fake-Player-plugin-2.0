# Configuration

Main file: `plugins/FakePlayerPlugin/config.yml`

Run `/fpp reload` to apply most changes without restarting.

## Structure

### `config-version`
Managed automatically by the built-in migrator. **Do not edit.**

### `language`
Default: `en`. Points to `plugins/FakePlayerPlugin/language/<lang>.yml`.

---

## 1. Spawning

### `limits`
- `max-bots: 1000` - global cap (`0` = unlimited)
- `user-bot-limit: 1` - default personal limit for `fpp.use` players
- `spawn-presets: [1, 5, 10, 15, 20]` - tab-complete suggestions for `/fpp spawn`

### `spawn-cooldown`
Seconds between `/fpp spawn` uses. `0` = disabled.

### `persistence`
- `enabled: true` - bots save position on shutdown and rejoin on restart
- `restore-delay-ticks: 0` - delay before restore starts after plugin enable
- `restore-batch-size: 1` - bots restored per batch
- `autosave-interval-seconds: 120` - how often tasks, inventories, XP and settings are saved (`0` = off, minimum `30`)

### `heartbeat`
- `enabled: true` - controls whether the network heartbeat manager publishes server status

> Note: `join-delay` and `leave-delay` were removed in recent versions.

---

## 2. Appearance

### `bot-name`
- `mode: random` - `random` (generate username) or `pool` (pick from `bot-names.yml`)
- `admin-format: '{bot_name}'` - display name for admin spawns
- `user-format: 'bot-{spawner}-{num}'` - display name for user spawns

### `badword-filter`
- `enabled: true` - block/rename bad names
- `use-global-list: false` - fetch remote profanity list
- `global-list-url: "..."` - remote word list URL
- `global-list-timeout-ms: 5000` - fetch timeout
- `words: []` - inline word list (merged with `bad-words.yml`)
- `whitelist: []` - allowed names even if they match bad words
- `auto-rename: true` - silently rename bad names instead of blocking
- `auto-detection`
  - `enabled: true`
  - `mode: normal` - `off` / `normal` / `strict`

### `bot-interaction`
- `right-click-enabled: true` - right-click opens inventory/executes command
- `shift-right-click-settings: true` - shift+right-click opens bot settings GUI

### `messages`
- Bots never produce chat events (join, leave, death, kill, advancement messages).
- `notify-admins-on-join: true` - send compatibility warnings to admins on join

### `tab-list`
- `enabled: false` - show bots in the tab list. Off by default. When on, a bot is **only ever** listed as
  `[Bot] - <botname>` (enforced at packet level for every viewer, including the server's own join sync).
  Toggle live in `/fpp settings` → Body.

### `nametag`
- `second-line.format: '<#BAFF4F>ʙᴏᴛ ʙʏ {owner}</#BAFF4F>'` - the mandatory bottom row (cannot be disabled).
  `{owner}` = the player/console that spawned the bot
- `second-line.y-offset: 1.9` - blocks above the bot's feet where the tag block starts
- `second-line.row-spacing: 0.3` - blocks between the three rows (activity / name / owner)
- `second-line.background-opacity: 0.25` - each row's black plate, `0.0`-`1.0` (`0.25` = vanilla name tag)
- `second-line.interpolation-ticks: 3` - follow smoothing (snaps while a bot is riding)

### `update-checker`
- `enabled: true` - check Modrinth (`fake-player-plugin-fpp`) for a newer release
- `auto-update: true` - download it to `plugins/update/` under this jar's file name, verify Modrinth's
  SHA-512, and let the server install it on the next restart. Nothing is replaced while running.
  Set `false` to only be notified

### `metrics`
- `enabled: true` - anonymous FastStats usage statistics

---

## 3. Body & Combat

### `body`
- `pushable: true` - players/explosions can push bots
- `damageable: true` - take all damage (if `false`, still takes environmental)
- `pick-up-items: true`
- `pick-up-xp: true`
- `auto-mount: true` - boats and moving rideable minecarts that run into a bot pick it up, like vanilla
  mobs. A bot stays seated unless deliberately dismounted (`/fpp sneak`), and a real player entering a boat
  always gets the first seat. Toggle live in `/fpp settings` → Body
- `drop-items-on-despawn: false` - `true` drops inventory on despawn; `false` keeps it

### `combat`
- `max-health: 20.0` - standard player HP
- `hurt-sound: true`
- `fall-damage`
  - `enabled: true`
  - `safe-distance: 3.0` - blocks before damage starts
  - `multiplier: 1.0` - damage scale

### `death`
- `respawn-on-death: false` - respawn at spawn location after death
- `respawn-delay: 1` - ticks before respawn
- `suppress-drops: false` - `true` = suppress all drops

### `skin`
- `rare-pools: true` - enable the rarity skin system (disable = vanilla default Steve/Alex)
- `mineskin-api-key: ''` - optional MineSkin API key (mineskin.org/apikey) for a larger signing quota

Skins are defined in `plugins/FakePlayerPlugin/skins/` as text files of NameMC skin URLs:
`main_skin.txt` is the default every bot spawns with; each `1-<N>%.txt` file is a "1 in N" rarity
pool rolled on fresh spawns (rarest first). Skins are signed once through MineSkin - with the
slim/classic model auto-detected - and cached forever in `data/skin-cache.yml`. A bot keeps its
rolled skin across despawns and restarts; re-roll from the bot's `🎨 ꜱᴋɪɴ` settings category.

### `automation`
Defaults copied to newly spawned bots:
- `auto-eat: true`
- `auto-eat-threshold: 17` - default hunger level (0-19) at or below which a bot eats; per-bot overridable in the settings GUI
- `auto-place-bed: true`
- `auto-milk: true`
- `prevent-bad-omen: true`

### `left-click` / `right-click`
Server-wide default pacing for held `/fpp left-click`/`/fpp right-click` (`--repeat`/`--hold`).
Each bot can override its own interval in the settings GUI (`⚙ ɢᴇɴᴇʀᴀʟ` → ʟᴇꜰᴛ/ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ);
these are only the fallback for bots that haven't been given their own value.
- `left-click.interval-ticks: 4` - ticks between block breaks while `--repeat`/`--hold` mining
  (vanilla's own `destroyDelay` is ~5 ticks; default matches that). Entity attacks are unaffected -
  those are paced solely by the held weapon's real attack-speed cooldown, exactly like a real player.
- `right-click.interval-ticks: 4` - ticks between held right-click pulses (default matches the
  vanilla client's own `rightClickDelay`).
- Both accept 1-40 ticks (enforced per-bot too).

---

## 4. AI & Navigation

### `head-ai`
- `enabled: true` - smooth head rotation toward nearest player
- `look-range: 8.0` - detection radius
- `turn-speed: 0.3` - smoothing (0.0 = frozen, 1.0 = instant)
- `tick-rate: 3` - scan every N ticks

### `swim-ai`
- `enabled: true` - automatic upward swimming

### `collision`
- `walk-radius: 0.85` - push radius when walking into a bot
- `walk-strength: 0.22`
- `hit-strength: 0.45`
- `hit-max-horizontal-speed: 0.80`
- `bot-radius: 0.90` - bot-vs-bot separation radius
- `bot-strength: 0.14`
- `max-horizontal-speed: 0.30`

### `pathfinding`
The engine is an A* costed in ticks over the server's real collision shapes. Every key below is optional
(they are not in the default `config.yml`); the value shown is the built-in default.
- `parkour: false` - allow 2-3 block gap jumps (also per bot in the settings GUI)
- `break-blocks: false` - allow digging obstructing blocks while pathing (priced by the bot's real tool)
- `place-blocks: false` - allow bridging gaps and pillaring up with scaffold blocks from the inventory
- `place-material: DIRT` - preferred scaffold block (else cobblestone, deepslate, dirt, netherrack, stone, …)
- `arrival-distance: 1.2` - blocks from the target at which a walk counts as arrived
- `waypoint-arrival-distance: 0.65` - how close to a path node counts as reaching it
- `sprint-distance: 6.0` - remaining path length above which the bot sprints on straight runs
- `follow-recalc-distance: 3.5` - how far a followed target may move before re-planning
- `stuck-ticks: 10` - ticks without progress before a stuck check fires (a hop first, then a re-plan)
- `stuck-threshold: 0.04` - movement (blocks) below which a tick counts as "no progress"
- `max-stuck-cycles: 4` - fruitless stuck cycles before the walk is abandoned
- `max-fall: 3` - deepest drop (1-16 blocks) a route may take voluntarily
- `max-range: 64` - base search radius (grows with the distance to the goal, capped at 256)
- `max-nodes-extended: 1800` - node budget; the search allows `max(4000, 4 × this)` nodes, time-sliced at
  ~1.5 ms per bot per tick, then settles for the closest explored point and re-plans from there

---

## 5. Database & Network

### `database`
- `enabled: true` - `false` = file-only persistence
- `mode: "LOCAL"` - `"LOCAL"` or `"NETWORK"`
- `server-id: "default"` - unique name per backend (NETWORK mode only)
- `mysql-enabled: false`
- `mysql` - host, port, database, username, password, use-ssl, pool-size, connection-timeout
- `location-flush-interval: 30` - seconds between position DB writes
- `session-history.max-rows: 20` - max rows per `/fpp info` query

### `config-sync`
- `mode: "DISABLED"` - `"DISABLED"`, `"MANUAL"`, `"AUTO_PULL"`, `"AUTO_PUSH"`

---

## 6. Chunk Loading

Bots are ordinary players to the server: its own chunk system loads, generates and ticks the area
where a bot stands, exactly as for an AFK player. FPP holds **no chunk tickets** and changes **no server
setting** - a bot only reports a render distance (and other options) the way a real client does.

### `chunk-loading`
- `enabled: true` - off = every bot reports the vanilla minimum render distance (2)
- `view-distance: "auto"` - render distance each bot reports: `"auto"` (server view distance), `0` = minimum, or 2-32. Per-bot override in the bot settings GUI.
- `update-interval: 20` - ticks between re-syncing each bot's options
- `mass-disable-threshold: 100` - at this many active bots every bot drops to `mass-view-distance` (`0` = never)
- `mass-view-distance: 3` - render distance used while the threshold is reached

### `clear`
- `max-cells: 2000000` - largest region one `/fpp clear` may cover
- `progress-every-layers: 8` - progress messages to the starter every N layers
- `default-trash: []` - what a bare `--trash` discards when the inventory fills (empty = built-in list)

### `spawn-actions`
- `enabled: true`, `initial-delay-ticks: 10` (pause between online and first action), `max-count: 10` (`--count` cap)
- `extra-commands: []` / `blocked-commands: []` - widen or restrict the usable action names (any command whose usage starts with `<bot>` already works)
- `allow-run: true` - let admins use `run <command…>` as an action
- `presets:` - named action strings used as `--preset guard` / `preset guard`

### `attack-mob`
- `default-range: 8.0`, `default-priority: nearest` - defaults for new PVE bots
- `default-mode: stand` - mode of a bare `/fpp attack <bot>` (`stand` holds position, `chase` hunts)
- `auto-weapon: true` - equip the best melee weapon (by attack damage × speed) when a fight starts
- `criticals: false` - jump and strike while falling for critical hits
- `line-of-sight: true` - a standing bot only wakes for targets it can see
- `return-to-post: true`, `return-delay-ticks: 60` - chase mode walks back to its start after the fight
- `scan-interval-ticks: 5`, `spare-friendlies: true` - target scan rate; skip tamed pets/villagers unless named

### `bot-client`
The bot's "options menu", sent to the server like a real client's client-information packet:
`language`, `main-hand` (`right`/`left`), `skin-parts` (`all`/`none`/0-127), `particles`, `chat-visibility`, `allow-server-listings`.

---

## 7. Performance

### `performance`
- `position-sync-distance: 128.0` - max distance (blocks) for per-tick position-sync packets. `0` = send to all players regardless of distance.

---

## 8. Heartbeat

### `heartbeat`
- `enabled: true` - controls server liveness publishing to the network database (NETWORK mode only)

---

## 9. Debug & Logging

### `debug.yml` (Separate File)
Debug logging is now controlled by `plugins/FakePlayerPlugin/debug.yml` for better organization.

**Master switch:**
- `enabled: false` - Enable all debug categories at once

**Debug chat broadcast:**
- `debug-chat: false` - When `true`, all debug output is also sent to online **OP / notify** players as in-game chat messages

**NMS debug:**
- `nms.enabled: false` - General NMS operations
- `nms.bot: false` - Bot lifecycle (spawn, despawn, inventory)
- `nms.connection: false` - Network connection/packet events
- `nms.physics: false` - Physics/collision/knockback
- `nms.skin: false` - Skin fetching and application

**Database debug:**
- `database.enabled: false` - General database operations
- `database.connection: false` - Connection pool lifecycle
- `database.operations: false` - SQL queries and transactions
- `database.migration: false` - Schema migration
- `database.persistence: false` - Bot persistence save/restore

**Feature debug:**
- `general: false` - General plugin operations
- `commands: false` - Command execution
- `pathfinding: false` - Navigation diagnostics (stuck/recalculate cycles, no-path failures, `/fpp find` watchdog, mining stalls); also mirrored to the in-game path-debug view
- `skin-pool: false` - Full rarity skin pipeline trace (pool load, per-spawn roll, cache hits/misses, PNG downloads, slim/classic detection, MineSkin signing, application)
- `network: false` - Cross-server network (Velocity/BungeeCord)
- `config-sync: false` - Config synchronization
- `startup: false` - Plugin initialization
- `right-click: false` - Right-click automation
- `right-click-head: false` - Right-click head rotation
- `head-ai: false` - Head AI tracking
- `packets: false` - Packet injection/manipulation
- `rental: false` - Economy provider resolution, every rent purchase/extend/give, and expiry-sweep despawns

> You can toggle any of these at runtime via **`/fpp settings`** → the **🐛 ᴅᴇʙᴜɢ** category. Changes are saved to `debug.yml` immediately. Run `/fpp reload` after manual edits to `debug.yml`.
---

## 10. Economy / Rental

### `economy`
Off by default. See [Economy](Economy) for the full picture (supported providers, custom shop
plugin integration, notifications). Quick reference:
- `enabled: false` - master switch for the self-service `/fpp rent buy`/`extend` purchase path.
  `/fpp rent give` (the console/shop-plugin grant path) works regardless of this setting, since it
  never touches an economy plugin.
- `provider: auto` - `auto` \| `vault` \| `excellenteconomy` \| `none`. `auto` tries Vault first
  (this also covers "Vault2.0" and Vault-bridged ExcellentEconomy automatically), then native
  ExcellentEconomy, then gives up.
- `excellent-economy-currency-id: money` - which ExcellentEconomy currency to charge; only used when
  the resolved provider is ExcellentEconomy (it supports unlimited custom currencies, so there's no
  single fixed default - this must match one you've actually created).
- `rental.price-per-hour: 100.0` - cost per hour of rented bot time.
- `rental.price-per-bot-slot: 0.0` - one-time extra charge for a brand-new rented bot.
- `rental.min-hours: 1` / `rental.max-hours: 72` - bounds on a single buy/extend.
- `rental.max-banked-hours: 168` - hard cap on time a bot can have banked across repeated extensions.
- `rental.warn-minutes-before-expiry: 10` - owner gets a one-time warning this many minutes out (`0` disables).
- `rental.sweep-interval-seconds: 30` - how often the expiry check runs.
- `rental.max-bots-per-player: 3` - cap on rented bots per player (`fpp.rent.unlimited` bypasses this).

---

## 11. Auth

### `auth`
Off by default. Makes bots auto-register/login against an installed login plugin instead of sitting
there unauthenticated - see the [Permissions](Permissions) page for `fpp.auth` and `/fpp auth` on
the [Commands](Commands) page. Needs `database.enabled: true` to remember passwords across joins.
- `enabled: false` - master switch.
- `register-command: "register %password% %password%"` / `login-command: "login %password%"` - sent
  as the bot itself, no leading slash needed. `%password%` is replaced with its generated/remembered
  password. Defaults already match nLogin, AuthMe, LoginSecurity, CrazyLogin, and xAuth.
- `delay-min-ticks: 20` / `delay-max-ticks: 60` - random delay before the command fires, so it
  doesn't fire the instant a bot's connection opens.
- `pending-timeout-ticks: 100` - hard cap on how long a bot stays frozen waiting to detect its own
  register/login outcome; it's released regardless once this elapses.
- `password.length: 12` - generated password length. Stay under your login plugin's max-length
  policy if it has one (some cap around 15).
- `password.uppercase` / `.lowercase` / `.digits` / `.symbols: true` - character classes included in
  a generated password.

All of the above are also editable live from `/fpp settings` → **🔐 ᴀᴜᴛʜ**.

---

The plugin includes a built-in **ConfigMigrator** that:
1. Creates a timestamped backup before any change
2. Automatically upgrades configs when `config-version` is outdated
3. Removes obsolete keys and adds new defaults

Do **not** edit `config-version` manually.
