# Changelog

## v2.1.7 (Beta)

Paper 26.3 support - fixes bot left/right-click doing nothing.

### Fixed
- **Left/right-click did nothing on Paper 26.3** - the plugin was built for 26.1.2. The accept-teleport packet now carries position and rotation in 26.3, so the server refused every bot block click; the dispatcher now sends the new format.
- **`PacketHelper init failed: PLAYER not found in EntityType`** - entity type constants moved from `EntityType` to `EntityTypes` in 26.3. The lookup now falls back to it, so bot tab-list packets work again.
- **Arm swing after storage use** - uses the Bukkit API (`swingMainHand`) since the old NMS swing call no longer exists.
- **Knockback velocity sync** - switched to the renamed `syncVelocity` flag.

### Changed
- **Build target** - compiled against Paper `26.3.build.227-stable`.

### Removed
- **Internal bed-sleep helper** - `NmsPlayerSpawner.startSleepInBed` called a method that no longer exists in 26.3. It had no callers.

---

## v2.1.6 (Beta)

Smarter hunting aim, safer bot persistence and the last of the old blue theme gone.

### Added
- **Persistence autosave** - every bot's tasks (with aim point), inventory, XP and settings are saved every 2 minutes, so a crash loses at most that much. Config: `persistence.autosave-interval-seconds` (default `120`, `0` = off, minimum `30`).
- **Inventory / XP database backup** - bot inventories and XP are also written to the plugin database (`fpp_bot_inventories`) and recovered on restart if they are missing from `bot-data.yml`.

### Changed
- **Hitbox aiming (hunting / PVE)** - bots aim at any exposed part of a mob's hitbox (feet, head, corners) instead of locking onto its center, so they hit mobs in farms that only show their feet. Target scanning uses the same check.
- **No aiming at hidden mobs** - a mob with no visible part of its hitbox is never aimed at or attacked. Chasing bots walk to a better angle; standing bots drop it and look for another target.
- **Head look-back off while tasking** - bots no longer turn their head to look at players while hunting, running a click task or walking.
- **Purple theme** - leftover blue GUI panes (inventory label, Pathfinding and Perf categories) are now purple.

---

## v2.1.5 (Beta)

Bots now act, fight, mine, walk and load chunks like a real client, with a new pathfinder, reworked GUI and automation commands.

### Added
- **`/fpp clear`** - layer-by-layer excavation of a chunk, area, selection or radius. Permission `fpp.clear`.
- **Spawn actions** - `/fpp spawn ... <action> [then|wait t <action> ...]` starts work as soon as the bot is online. Config: `spawn-actions.*`.
- **`/fpp menu`** hub and a **Chunks** settings category.
- **Rent GUI** and shop integration - `/fpp rent` shop, auto-renew, bulk discounts, placeholders, cancellable `FppRentalPurchaseEvent`.
- **Find** `--scan` mode reports what is visible without moving.
- **Build ID** in every jar: banners, GUI credit, `%fpp_build%`.

### Changed
- **Left/right-click** ported from the 26.3 client's click logic (`--once`, `--repeat`, `--hold`).
- **PVE / `/fpp attack`** rebuilt as client combat: `--stand` guard, `--chase` hunter, real attack packets, no damage shortcut. Config `attack-mob.*`.
- **Find** only reports blocks with an exposed face - no X-ray.
- **Pathfinding rebuilt** in-house (Pathetic removed): A* over real collision shapes (parkour, ladders, swimming, doors, bridging); Folia-safe.
- **Chunk loading** is client-authentic: no plugin chunk tickets, bots just report a render distance.
- **GUI overhaul** with card grids, paging, back buttons; creator head at bottom-left; new startup/shutdown banners.
- **Economy** has one price engine; failed spawns refund.
- **No bot chat events**: no join/leave/death/advancement messages or relay. `messages.join-message`/`leave-message` removed.

### Fixed
- **plugin.yml** parse error (`fpp.rent.free`/`fpp.rent.auto`) that stopped the plugin loading.

### Performance
- Viewer snapshot built once per tick; faster pathfinder and Find/Clear.

### Removed
- ~140 unused methods, four dead classes, unit tests, Pathetic and old `pathfinding.*` options.

## v2.1.0 - Part 1 (Beta)

### Fixed
- **Network Protocol Error kick** (`Cannot remove from team ...`): a per-viewer packet guard keeps scoreboard teams consistent, so TAB/LuckPerms/AFK-display teams can no longer desync a client.
- **Hot reload** (PlugManX): classes preloaded, shutdown steps isolated, leftover bots purged.

### Changed
- **Honest bots:** tab list off by default (`tab-list.enabled`; when on, listed only as `[Bot] - name`, enforced for every viewer); no fabricated ping; `%fpp_online%`/`%fpp_total%` count real players only; bots can't take a real account's name.
- **Name tag:** three separate rows with vanilla-style plates (`nametag.second-line.background-opacity`, `row-spacing`); name `#A78BFA`, indicator `#BAFF4F`. Vanilla name can't reappear, and the tag follows a mounted bot without lag.

### Added
- **Auto mount** (`body.auto-mount`, default on): boats and moving minecarts pick bots up like villagers; bots stay seated; a real player boarding a boat takes the first seat.
- **Mount memory:** a bot that despawns in a vehicle returns in it (a shared vehicle is re-boarded), like a player logging back in.
- **Auto update** (`update-checker.auto-update`, default on): Modrinth release is downloaded to `plugins/update/`, SHA-512 verified, installed on restart.

## v2.0.6.1 (Beta)

### Added - Minecraft 26.3 Support

- `CompatibilityChecker.isSupportedVersion` now accepts `26.3.x` alongside `26.1.x` and `26.2.x`. The
  unsupported-version warning and wiki version ranges now read `up to 1.21.11, and 26.1.x–26.3.x`.
- Compile-time Paper dev bundle stays on `26.1.2.build.65-stable`.

### Fixed - Vanilla Bot Name Showing Over the Custom Nametag

- The team packets that hide a bot's vanilla over-head name were gated on `PacketHelper`'s unrelated
  tab-list reflection init, so if that init failed the hide was silently skipped and the vanilla name
  drew on top of the custom text_display tag. Those packets no longer depend on it.
- Failures while sending the hide-team packets are now written to the debug log, and a failure to
  build the hide team logs a warning at startup instead of failing silently.

### Added - Console & Command-Block Spawning

- `/fpp spawn` can now be run from the server console and from command blocks, not just in-game -
  admin-tier (`fpp.spawn`) only, and `--location <x> <y> <z> <world>` is now mandatory for them since
  neither has a location of its own to fall back on. User-tier spawns (`fpp.spawn.user`) still require
  a real player: personal bot ownership, the per-player bot limit, and the spawn cooldown all key off
  a player's UUID, which console/command-block senders don't have.

## v2.0.6 (Beta)

### Added - `/fpp spawn --location`

- Admins (`fpp.spawn`) can now spawn a bot at an arbitrary spot instead of only their own location:
  `/fpp spawn --location <x> <y> <z> <world>`, combinable with `--name`. User-tier spawns
  (`fpp.spawn.user`) are unaffected and still always spawn at the sender's own location. Tab-completes
  coordinates and world names.

### Improved - Left/Right-Click Overhaul

- **Auto tool-switch**: before mining a block, `/fpp left-click` now auto-equips the best available
  tool (highest tier of the right category - pickaxe/axe/shovel/hoe) already in the bot's inventory,
  the way a competent player would. Toggle via `left-click.auto-tool-switch` in `config.yml` (default
  on). Reuses the same tool-selection logic `/fpp find`'s auto-mining already relied on, instead of a
  second copy of it.
- **Off-hand fallback for right-click**: a bot's right-click now tries the main hand first and, only
  if that action had no effect, retries with the off-hand item - mirroring the vanilla client's own
  hand-loop exactly. Bots carrying a shield or food off-hand can now actually use it; previously the
  off-hand was never touched by right-click at all.
- **Fixed:** a bare `/fpp right-click <bot>` (no `--once`/`--repeat`/`--hold` flag) skipped the
  mode-specific permission check entirely - a player holding only `fpp.right-click.stop` could still
  start a default click. Now always gated on the resolved mode's permission, matching `left-click`.
- **Fixed:** `/fpp right-click`'s task-resume path silently did nothing if its cached aim state was
  missing (e.g. after certain restart races) - the task stayed registered but never actually ran. It
  now rebuilds a fresh aim from the bot's own view and resumes normally, matching `left-click`.
- Internal: `LeftClickCommand`/`RightClickCommand` now share one base class for aim geometry,
  stand-location search, and the stop/cleanup/save-restore lifecycle instead of two nearly-identical
  ~1000-line copies; the three parallel `ONCE/REPEAT/HOLD/STOP` mode enums collapsed into one.

## v2.0.5 (Beta)

### Added - Bot Auth System

Bots can now play along with a login-wall plugin instead of sitting there unauthenticated. Off by
default (`auth.enabled: false`); needs `database.enabled: true` to remember passwords across joins.

- **Auto-register / auto-login**: on a bot's first join it generates a strong random password,
  encrypts and stores it (AES-256/GCM, key in `auth.key` in the plugin data folder, never in
  config.yml or logs), then sends `auth.register-command` as the bot after a randomized human-like
  delay. Every later join sends `auth.login-command` with the same remembered password instead.
- **Works against any login plugin** built around a `/register <password> <password>` /
  `/login <password>` command shape - the defaults already match nLogin, AuthMe, LoginSecurity,
  CrazyLogin, and xAuth. Commands are replayed exactly like a real client's typed chat command
  (through `PlayerCommandPreprocessEvent`), not a bare command dispatch, so plugins that hook that
  event directly (most login plugins, including nLogin) still see the attempt.
- **Dedicated nLogin integration**: nLogin's command path throws for a bot's connection no matter
  what, so it's authenticated through nLogin's own public API instead - fully optional, inert if
  nLogin isn't installed.
- **A bot stands still and doesn't turn its head** from the moment it joins until its register/login
  outcome is known (read from the login plugin's own reply - chat, title, subtitle, or action bar),
  exactly like a real not-yet-authenticated player; `auth.pending-timeout-ticks` is the hard fallback
  so an unrecognized response never leaves a bot stuck frozen.
- **New `/fpp auth` command**: `on`/`off`, `status [bot]`, `reset <bot>`, `setpassword <bot> <password>`.
  New permission `fpp.auth` (default `op`, included in `fpp.op`).
- **New settings GUI category** (`🔐 ᴀᴜᴛʜ`): toggle, register/login command templates, delay/timeout
  ticks, and password length/complexity - see [Configuration](Configuration).

### Added - "Bot Console" Color Theme

Every chat message, GUI, and console log now pulls from one consistent violet/lime palette instead
of the scattered ad-hoc colors (blue accents, plain named colors, mismatched grays) used previously.
Purely visual - no config or permission changes.

- Chat/GUI text: violet (`#A78BFA`) primary accent, lime (`#BAFF4F`) success/highlight, red
  (`#FF6A5C`) errors, plus a consistent ink/mute/mute-2/line neutral scale for body text, captions,
  and dividers.
- Console output (`FppLogger`, the startup/shutdown banners) recolored to match with true-color ANSI.
- `language/en.yml`'s header now documents the full palette for anyone customizing messages by hand.

### Added - Auth & Perf Settings/Help Categories

- The settings GUI (`/fpp settings`) gained a **🔐 ᴀᴜᴛʜ** and a **📈 ᴘᴇʀꜰ** category (general, body,
  auth, perf, debug) - the perf category exposes every `performance.*` config option, including the
  self-profiler, with changes restarting the monitor live instead of requiring `/fpp reload`.
- The help GUI (`/fpp help`) split `auth` and `perf` out of the catch-all **ᴄᴏʀᴇ** category into their
  own tabs, each with a distinct accent color.

## v2.0.4 (Beta)

### Added - Multitasking (Reverses the v2.0.0 Single-Action System)

Bots can now run multiple tasks at once instead of being limited to one. Starting a new task no
longer cancels every other running task for that bot - `move`, `find`, `left-click`, `right-click`,
`attack`, and PVE auto-combat are now independent systems that can all be active on the same bot
simultaneously, the same way `auto-eat` already ran alongside a paused task.

- **PVE always runs.** Auto-combat no longer stands down while a manual task is active; it only
  ever *chases* a target when nothing else currently owns the bot's movement.
- **Movement is arbitrated by priority, not cancellation.** Only one navigation can drive the bot
  at a time: an explicit `/fpp move` always gets it, a manual hand-action's short walk-to-reach
  (`left-click`/`right-click`/`find`) outranks background movement (PVE's chase), and background
  movement never interrupts anything. A request that loses out declines gracefully instead of
  hijacking the bot mid-walk.
- **`/fpp find` no longer collides with `/fpp left-click`** - each now has its own navigation owner.
- **Auto-eat is fully parallel when eating from the off-hand** - mining, moving, and combat all
  continue untouched. Only the main-hand fallback still pauses briefly.
- **Restart persistence now resumes both click tasks** if a bot had `left-click` and `right-click`
  both active when the server stopped, instead of restoring only one.
- **Every command's cleanup is now scoped to what it actually owns**, so stopping one task can no
  longer silently cancel an unrelated concurrent task's navigation.

**What this doesn't change:** a bot still has one head and one main hand - two hand-actions aimed
at different targets will still visibly alternate the bot's aim between them each tick.

### Added - Configurable Left/Right-Click Interval

The pacing of held `/fpp left-click`/`/fpp right-click` automation (`--repeat`/`--hold`) is no
longer a fixed constant - it's a server-wide default that every bot can individually override.

- **New config options**: `left-click.interval-ticks` (default `4`, matches vanilla's own block
  `destroyDelay`) and `right-click.interval-ticks` (default `4`, matches the vanilla client's own
  `rightClickDelay`) in `config.yml`. Entity attacks are unaffected either way - those are still
  paced solely by the held weapon's real attack-speed cooldown, exactly like a real player.
- **Per-bot override**: each bot's `⚙ ɢᴇɴᴇʀᴀʟ` settings category now has **ʟᴇꜰᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ** and
  **ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ɪɴᴛᴇʀᴠᴀʟ** tiles (1–40 ticks); leaving a bot on the global default requires no
  configuration. Overrides persist across restarts (YAML and database, schema bumped to v27).
- No new permission node - gated by the existing `fpp.settings` bot-ownership model, same as
  `chunk-load-radius` and the PVE range tile.

### Fixed - Dead `fpp.mine.*` Permission Nodes

Removed `fpp.mine`, `fpp.mine.start`, `fpp.mine.once`, `fpp.mine.stop`, `fpp.mine.area` - leftovers
from the old `/fpp mine` command that was replaced by `/fpp left-click`/`/fpp right-click` several
versions ago. They were still being silently granted through `fpp.op`'s children list without ever
having their own permission declaration, and nothing in the codebase checked them. A full audit
confirmed every other permission in `Perm.java` now has exactly one plugin.yml declaration with no
dangling wildcard references.

### Added - Bot Rental Economy

Players can now pay real economy currency to rent a bot for a set number of hours; the bot
auto-despawns the moment its paid time runs out. Off by default (`economy.enabled: false`).

- **New `/fpp rent` command family**: `buy <hours>`, `extend <bot> <hours>`, `info [bot]`,
  `give <player> <bot|--new> <hours>` (grant hours without charging - console-usable), `clear <bot>`.
- **Provider-agnostic economy layer**: reflective, soft-dependency support for **Vault**,
  **[Vault2.0](https://github.com/shalom25/Vault2.0)** (same `Economy` interface, transparently
  covered by the Vault provider), and **ExcellentEconomy** (own API, configurable currency id),
  with zero compile-time dependency on any of them.
- **Shop-plugin friendly**: `/fpp rent give` never touches an economy plugin itself - any shop
  plugin can run it as a purchase's console reward command with no FPP-side integration needed.
- **Expiry sweep**: warns the owner before time runs out, then despawns safely at zero.
  `fpp.rent.unlimited` exempts a player's bots from expiring.
- **Settings GUI tile** (`⚙ ɢᴇɴᴇʀᴀʟ` → ʀᴇɴᴛᴀʟ ᴛɪᴍᴇ) and new placeholders `%fpp_rental_count%`,
  `%fpp_rental_remaining%`. Persists across restarts (YAML + DB, schema bumped to v26).
- **New permissions**: `fpp.rent`/`fpp.rent.info` (in `fpp.use`), `fpp.rent.give`,
  `fpp.rent.unlimited` (both default `op`). New wiki page: [Economy](Economy).

### Hardened - Vanilla Nametag Hiding Now Self-Heals Unconditionally

The bot's real name is hidden via a packet-only scoreboard team, but anything that puts a bot on a
*different* team (another plugin, an admin's `/scoreboard teams join`, …) silently evicts it,
bringing the real name back. The re-assertion now fires on every nametag-refresh sweep (~10 ticks)
for every active bot unconditionally, instead of only when the activity label changed - the hide
no longer lapses for more than one sweep interval, regardless of cause.

## v2.0.3 (Beta)

### Fixed - Left-Click Mining Could Get a Bot Kicked ("Invalid Entity Attacked")

- While mining, a held left-click resolves its target with the same combined block+entity ray a real
  client uses - but unlike a real client, nothing stopped it from picking a **dropped item, XP orb, or
  arrow** floating near the block it was breaking and sending an attack packet at it. The vanilla server
  disconnects any client that attacks one of those
  (`multiplayer.disconnect.invalid_entity_attacked`) - a real client's own UI never lets you target them
  with left-click, so this path only existed because a bot drives raw packets directly. The crosshair
  ray trace in `LeftClickCommand` now excludes `Item`, `ExperienceOrb`, and `AbstractArrow` from valid
  attack targets, matching what `AttackCommand`'s ray trace (via `Entity#isAttackable()`) already did.

### Fixed - Bot Nametag Could Be Permanently Lost

- The bot's floating name tag is a real world entity (a `TextDisplay`), so anything capable of removing
  an entity - `/kill`, WorldEdit, another plugin's cleanup pass - could remove it. The periodic refresh
  loop only checked whether its cached reference was `null`, not whether the entity was still *valid*,
  so an externally-removed tag never came back. It now checks `isValid()` and respawns the tag within
  half a second (10 ticks) of it disappearing, making the tag effectively unremovable instead of merely
  "usually present."

### Added - ViaVersion-Aware Bot Connections

- A bot's connection never performs a real handshake/login, so ViaVersion (when installed) had no
  record of it - any lookup of the bot's protocol version came back empty, which is the source of
  console warnings about not being able to determine a bot's client version. Bots are now registered
  with Via's connection manager as running the **server's own native protocol version** (i.e. "no
  translation needed"), using the same `UserConnectionImpl` + `ProtocolPipelineImpl` construction Via's
  own channel initializer uses for a real connection, and unregistered again on despawn. Entirely
  reflective and a no-op if ViaVersion isn't installed or its internals ever change shape.

## v2.0.2 (Beta)

### Fixed - Permissions No Longer Bypass LuckPerms

- Removed the hard-coded `player.isOp()` shortcuts (`Perm.hasOrOp`, `BotAccess.isAdmin`, `BotListGui.isAdmin`,
  `BotSettingGui.isOp`) that granted admin access to server operators even when a permissions plugin like LuckPerms
  explicitly denied the relevant `fpp.*` node. Every permission check now goes through `hasPermission()` only, so
  LuckPerms grants/denies are always respected, operators included.
- Fixed `canUse()` gates that were stricter than the granular permission nodes they were meant to allow - players
  granted only `fpp.left-click.once`/`.stop`, `fpp.right-click.once`/`.stop`, `fpp.despawn.own`,
  `fpp.move.to`/`.coords`, or `fpp.inventory.own` were previously blocked at the command dispatcher before those
  fine-grained nodes were ever checked.
- Added the missing `fpp.inventory.own` permission constant and wired it into the `/fpp inventory` command and the
  right-click-to-open-inventory listener - this node was documented in `plugin.yml` but had no effect at all.

### Fixed - Folia Compatibility

- Fixed a `TickThread` crash ("Accessing entity state off owning region's thread") when refreshing a bot's floating
  name-tag activity line. The nametag refresh loop now dispatches to each bot's owning region thread on Folia instead
  of running on the global region thread.
- Fixed bots showing their vanilla over-head name doubled up with the custom name-tag display on Folia. Folia's
  Scoreboard/Team API is a documented gap (global state the project hasn't implemented for regionised ticking), so
  hiding the vanilla name no longer touches `Bukkit.getScoreboardManager()`/`Team` at all - a `PlayerTeam` is built
  directly from NMS and its `ClientboundSetPlayerTeamPacket`s are sent straight down each viewer's connection.
- Fixed a client-crashing bug in that same rework: sending a "remove from team" packet for a bot whose name had never
  actually been added (e.g. a second bot's very first spawn) made the vanilla client throw and disconnect
  ("Network Protocol Error"). The plugin now only sends a removal for a name it actually tracked as hidden.
- Fixed `onDisable()` crashing on Folia with `IllegalPluginAccessException` ("Plugin attempted to register task while
  disabled") when despawning bots during shutdown. `FppScheduler` now detects a disabled plugin and runs the work
  directly instead of scheduling it - safe, since Folia halts all region ticking before `onDisable()` runs.
- Fixed Head-AI being hard-disabled on Folia entirely. The restriction predated the current per-bot region-thread
  dispatch and was never removed after the rest of the tick loop became Folia-safe; it now runs on Folia the same as
  on Paper.

### Changed - `/fpp spawn` Is Now In-Game Only

- `/fpp spawn` can no longer be run from the server console (or dispatched as console) - it requires a real player
  sender, matching most other bot commands.
- Removed world/coordinate targeting (`/fpp spawn <world> [x y z]`) - a bot always spawns at the commanding player's
  own location now. The `fpp.spawn.coords` permission node is gone along with it.

### Performance

- Removed reflective `Method.invoke()` calls from every hot per-tick NMS path in `NmsPlayerSpawner` (`doTick()`,
  `getHandle()`, and everything built on it - `setJumping`, `setHeadYaw`, `setMovementForward`,
  `setMovementStrafe`, `applyServerVelocity`, and more) in favor of direct calls. `ServerPlayer.tickPhysics()`'s
  `doTick()` invocation alone accounted for ~69% of the plugin's own profiled self-time in benchmark reports; this
  scales linearly with bot count, so the win grows with server size.

## v2.0.1 (Beta)

### Changed - Left/Right-Click Are Now Real Clicks (Authentic Serverbound Packets)

The bot left-click and right-click systems no longer re-implement what the server does on a click. Every action is now
dispatched as an **authentic serverbound packet** through the bot's real packet handler
(`FakeServerGamePacketListenerImpl`), so the server performs the click exactly as it does for a real player - full
reach/anti-cheat validation, native Bukkit events (`BlockBreakEvent`, `PlayerInteractEvent`,
`PlayerInteractEntityEvent`, …), item durability, hunger, use-timing and every item's own behaviour.

- **New `BotClickDispatcher`** builds and dispatches `ServerboundPlayerActionPacket` (dig), `ServerboundAttackPacket`,
  `ServerboundInteractPacket`, `ServerboundUseItemOnPacket`, `ServerboundUseItemPacket` and `ServerboundSwingPacket`
  through the connection's live `handle*` methods. It first sends a `ServerboundPlayerLoadedPacket` (what a real client
  sends) so the server's `hasClientLoaded()` gate lets bot interactions through.
- **Left-click mining** now sends `START_DESTROY_BLOCK` and lets the server drive destroy progress, sending
  `STOP_DESTROY_BLOCK` on a client-faithful predictor (creative/instant-break complete on the START tick). Removed the
  hand-rolled progress loop and direct `gameMode.destroyBlock` calls.
- **Left-click attacks** go through the real attack packet (respecting the attack-strength cooldown), replacing the
  `performAttack` + forced-attack-ticker hack.
- **Right-click** routes block use, item use and entity interaction through the real handlers, and **deletes** the
  fake shortcuts: directly `setType`-ing planted crops from a hardcoded seed→crop table, `applyBoneMeal`, and the manual
  place/plant/bonemeal branches. The server's `useItemOn` now handles placement, planting, bone meal, hoeing, bucket use,
  etc. natively for any item.
- Protection plugins (WorldGuard, etc.) now block bot clicks the same way they block real players, since the real events
  fire.
- **1:1 vanilla click resolution** - the bot's clicks now resolve targets exactly like a real client's pick: the
  combined block+entity ray from the bot's own eyes, nearest hit wins. Left-click attacks the entity directly under the
  crosshair (paced by the real attack-speed attribute) or digs the block; right-click interacts with the entity under
  the crosshair (armor-stand equip, feeding, breeding, trading, mounting) and, when that PASSes, falls through to the
  in-hand item use - the same order `Minecraft.startUseItem` follows. Removed all non-vanilla targeting: sender-aim
  entity picks (`rayTraceHostileEntity`, `rayTraceEntity`, the right-click `rayTraceTargetPlayer` entity branch), the
  left-click forward-cone auto-attack (`findBestMobTarget`), and target lock-on. An entity behind the aimed block is
  never picked; the sender's crosshair only designates which block to work on.
- **Full debug tracing** - new `left-click` / `left-click-head` debug categories (in `debug.yml`, plus
  `Config.debugLeftClick()/debugLeftClickHead()`) mirroring `right-click`. Both systems now log the whole pipeline:
  target resolution, navigation, every dispatched packet (dig START/STOP/ABORT, attack, useItemOn, useItem, entity
  interact), consume detection and stop/cleanup.

### Fixed - Use Packets Silently Dropped by the Spam Limiter + Aim Drift

Two bugs that made `--hold`/`--repeat` clicks stop working after a few seconds:

- **Spam-limiter drop** - `handleUseItem`/`handleUseItemOn` feed the packet's Spigot-added `timestamp` field into
  Paper's incoming-packet spam limiter (`checkLimit`). Directly-constructed packets leave that field `0`, which looks
  like "no time passed since the last packet", so after ~8 uses the server **permanently dropped every use packet**
  (eating, placing, doors - everything). `BotClickDispatcher` now stamps `System.currentTimeMillis()` onto
  `ServerboundUseItemPacket`/`ServerboundUseItemOnPacket` (reflection-probed, no-op if the field ever disappears), so
  the limiter sees real inter-packet timing exactly as for a networked client.
- **Aim tracking** - the bot's head was rotated once when the command started and could drift off the commanded target
  (knockback, entity interactions), leaving it staring at the wrong block forever. Both click loops now **re-aim at the
  commanded target every tick** (new `aimTarget`/`aimPoint` in the click state, never overwritten by transient per-tick
  picks): a passing entity still gets vanilla nearest-hit treatment, and the moment it's gone the crosshair is back on
  the commanded block.

### Fixed - Left-Click Resumes Mining After the Crosshair Leaves an Entity

Block re-resolution in the left-click loop was gated behind a "dynamic target" flag that was false when the click
started without a block pick (entity in front of the crosshair, a resumed task after restart, or a plain self-view
click) - so once the entity was gone, the bot stopped instead of mining the block now under its crosshair. The loop now
re-resolves the block pick unconditionally every tick, matching a real held click: entity in front → attack it; entity
gone → seamlessly dig whatever the crosshair sees. The dead flag was removed.

### Fixed - Full Weapon Damage & Right-Click Arm Swings

- **Attacks now use the held weapon's real damage** - bots aren't driven by the normal player tick path, so two things
  a real client gets for free never happened: the held weapon's attribute modifiers were never registered
  (`ATTACK_DAMAGE` stayed at fist level) and the attack-strength ticker never advanced (every hit was scaled to the
  ~20% spam-click minimum → a sword dealt ~1 damage). `BotClickDispatcher.attack` now calls `detectEquipmentUpdates()`
  and primes the attack-strength ticker before each attack; since the click loop already paces attacks at the real
  weapon cooldown, every hit lands at full strength - sword/axe damage, sweep, knockback and enchantments all apply.
- **Right-click now swings the arm** - the server only broadcasts SERVER-sourced swings; for most interactions the
  vanilla *client* swings itself, which a bot has no client to do. The bot now sends its own swing on every consumed
  right-click (buttons, levers, bone meal, planting, placing, entity interactions) and on instant item uses (throwing
  pearls/eggs) - but not when a multi-tick use starts (eating, bow draw), matching the vanilla client exactly.

### Fixed - Bots No Longer Lose Inventory & Active Task Across Restarts

Two persistence defects made every server restart / plugin update wipe bot inventories and running tasks:

- **Inventory/XP restore was a one-shot timer** - applied a fixed 10–12 ticks after the restore chain spawned the bot,
  with a silent give-up if the body hadn't finished spawning yet (typical right after startup while chunks load). Both
  now use a retry loop (every 5 ticks, up to 30 s) that waits until the bot is online + valid, and log a warning
  instead of silently dropping if the bot never spawns.
- **Task persistence was hollow since the click-system migration** - the task snapshot hardcoded every field to
  null/0 ("legacy mine/use/place removed") and the restore hook was an empty stub, so the active task was lost by
  design. The persistence layer now saves each bot's **active left/right-click task** (mode + world + exact aim point)
  to both the unified YAML and the `fpp_bot_tasks` DB table (`LEFT_CLICK`/`RIGHT_CLICK` rows), and resumes it after the
  restart: once the bot is fully spawned and its inventory (tools!) is restored, it re-aims at the saved point and
  restarts the same click mode via the normal task pipeline. New `SavedClickTask` snapshot +
  `getSavedTask`/`resumeSavedTask` APIs on both click commands; the dead 30-field legacy task model was replaced with a
  compact one.

### Fixed - Block Interactions Blocked by the Unconfirmed-Teleport Gate

`handleUseItemOn` refuses **every** block interaction while `awaitingPositionFromClient` is armed - and it gets armed by
any teleport/rotation done through the connection (bot spawn, `/fpp tp`, action locks, `CraftPlayer#setRotation`). A
real client confirms teleports instantly; the bot never did, so levers, buttons, bone meal, planting and placement
silently no-oped (the packet fell into the build-limit-message branch). Two-part fix:

- `BotClickDispatcher` now **confirms pending teleports like a real client** before every interaction packet: if the
  connection is awaiting a position confirm, it dispatches a `ServerboundAcceptTeleportationPacket` with the pending id
  (clears the gate, snaps to the awaited position - the same thing a vanilla client does).
- `refreshAim` rotates via NMS `absSnapRotationTo` instead of `CraftPlayer#setRotation`, which internally performs a
  connection teleport and would re-arm the gate every tick.

### Changed - Vanilla Click Timing & Armor-Stand Parity

- **Right-click at vanilla speed** - held right-click now acts once per 4 ticks (the vanilla client's
  `rightClickDelay`), instead of every 8: the extra post-action pulse skip was removed. Bone meal, levers, buttons,
  doors and planting now trigger at real-player pace.
- **No more eating through armor stands** - the vanilla client hardcodes `interactAt` on a non-marker armor stand as
  SUCCESS client-side, so a real player's click is always consumed there and never falls through to eating/using the
  held item. The bot now mirrors that: aiming at an armor stand consumes the right-click even when the server-side
  equipment swap does nothing.
- **Held left-click attacks at exact weapon speed** - entity attacks are now paced solely by the bot's real
  attack-speed attribute (swing + attack packet each cycle); the loop's post-break pause no longer stacks on top
  (previously ≈16 ticks between sword hits instead of 12). Mining keeps its vanilla-like ~4-tick post-break pause.

### Added - Minecraft 26.2 Support

Extended the runtime compatibility gate to cover the year-based `26.2.x` releases (Minecraft 26.2 "Chaos Cubed").

- `CompatibilityChecker.isSupportedVersion` now accepts `26.2.x` alongside `26.1.x` (old `1.x.y` remains supported below `1.21.12`).
- The unsupported-version warning banner and wiki version ranges updated to read `up to 1.21.11, and 26.1.x–26.2.x`.
- Compile-time Paper dev bundle stays on the stable `26.1.2.build.65-stable`; 26.2 runs via the existing version-safe NMS reflection.

## v2.0.0

Major version bump to **2.0.0**, opening the 2.0 release line. No behavioral changes from `1.6.6.12.8` beyond the items below.

- **Version** - `1.6.6.12.8` → `2.0.0` (`build.gradle.kts`, `plugin.yml`, Velocity companion `velocity-plugin.json`).
- **Personality API in core** - the personality API now ships in core (`api/personality/`); first-party extensions updated to consume it.

### Fixed - Right-Click No Longer Activates Buttons/Levers From the Mounting Block's Face

Right-click had a "helper" (`checkForAttachedInteractiveBlock`) that, whenever the bot's ray hit a block face, looked for a button/lever/switch mounted on that face and redirected the interaction to it. The result: the bot would flip a lever or press a button just by aiming anywhere on the block it's attached to (even a corner), without ever aiming at the switch itself.

- Removed that redirect entirely. The interaction ray trace already uses the block **OUTLINE** shape (`ignorePassableBlocks=false`), so aiming at a button/lever's real hit box returns that block directly - the bot must now actually aim at the switch to trigger it, exactly like a real player.
- Aiming at the mounting block's face (or corner) now interacts with that block, not the attached switch.

### Added - Left/Right-Click Walk to a Reachable Vantage (prefer the player's spot)

When the aimed target is out of reach, the bot no longer just searches for a stand spot hugging the block (which could land it somewhere the target isn't actually reachable/visible from). It now first tries to walk to a spot **around the command sender's own standing location** - a vantage the target is provably aim-able from, since the player just aimed at it from there - and only falls back to the old near-the-target search if that fails.

- New `findStandLocationNear` (searches a walkable spot around an arbitrary centre, including that centre block) + `resolveStandLocation` (prefers the sender's location, falls back to near-target) in both click commands.
- Console/bot-issued clicks keep the near-target search (no player vantage to prefer).
- If neither yields a reachable spot, the command still reports "no path" as before.

### Changed - Left/Right-Click Aim at the Exact Point You're Looking At

When a player runs `/fpp left-click` or `/fpp right-click` while looking at a block, the bot now aims its head at the **exact point on the block the player was looking at**, instead of snapping to the geometric center of the block face.

- Both commands capture the sender's precise ray-trace hit position (`RayTraceResult#getHitPosition()`) and aim there from the bot's own eyes; they fall back to the block-face center only when no precise point is available (entity targets, or a ray with no hit position).
- The aim point is a world coordinate, so it stays correct even when the bot has to walk to a stand location before acting.
- Right-click additionally seeds its first interaction with that exact hit point (the per-tick loop still refreshes it from the bot's own view afterward).
- Internally, `lockAndStartClicking` now takes a pre-computed aim `Vector` instead of a `BlockFace` - the block-face-center math moved to the call sites.

### Changed - Documentation & Frontend Refresh

Full pass over the README and wiki/legal docs to match the current plugin:

- **README / Home / Getting-Started** - added the precise-clicking, single-action, and auto-eat highlights; refreshed the nametag activity list (now includes eating) and the per-bot GUI category list (adds auto-eat + rename); corrected optional soft-depends (PlaceholderAPI / WorldEdit / Spark) and clarified there is no built-in LuckPerms integration; fixed the source-repo link.
- **Commands / FAQ** - documented single-action (no multitasking), auto-eat, precise-aim + vantage walking, and the button/lever hit-box fix.
- **Placeholders** - removed placeholders that no longer exist (`%fpp_chat%`, `%fpp_skin%`, `%fpp_ping%`, `%fpp_swap%`, `%fpp_peak_hours%`).
- **Configuration** - debug-topic list corrected (added `pathfinding` / `skin-pool`, removed the stale `swap` / `chat` entries).
- **Extensions wiki + retired extension policy + ToS §8** - reflect that the extension API is removed; repo links updated to the new repository.
- **AGENTS.md** - added runtime invariants for single-action, pause/resume, and click-aim/button-hit-box so they aren't regressed.

### Changed - Permission Node Audit

A pass over every permission node so a permission manager (LuckPerms, etc.) sees a clean, complete set:

- **Removed the dead `fpp.farm`** node - an orphaned constant with no command, no usage, and no `plugin.yml` declaration.
- **Completed the `fpp.op` / `fpp.admin` wildcard** - added the previously-missing `fpp.tph` and `fpp.xp` so the admin wildcard explicitly grants every command node.
- **Documented the per-bot GUI permission model** in the Permissions wiki: all per-bot settings (general, PVE, pathfinding, skin, **auto-eat**, rename, danger) live behind `fpp.settings`, and a bot's **owner** (or a shared controller) can always manage their own bot's settings without it. No individual per-bot toggle (including auto-eat) has its own node.

### Added - Task Pause/Resume + Single-Action Enforcement (no multitasking)

Bot tasks are now mutually exclusive and interruptible:

- **One action at a time** - starting any user task (`move`, `find`, `left-click`/mine, `right-click`/use, `attack`) now stops every other running task for that bot first, via a central `FakePlayerManager.beginExclusiveAction`. No more a bot mining *and* attacking *and* walking at once.
- **PVE yields to manual control** - the per-bot PVE auto-combat now stands down whenever a user-issued task is active (`hasActiveManualAction`) and re-engages automatically once that task finishes.
- **Pause/resume** - a transient `FakePlayer.actionsPaused` flag is honoured by every action tick (pathfinding movement, find/mine, left-click, right-click, attack, PVE). While set, each loop no-ops **without losing its state**, so the task resumes seamlessly when the flag clears. This is the mechanism auto-eat uses to interrupt and then continue whatever the bot was doing.

### Overhauled - Auto-Eat System (per-bot toggle, food list, hunger threshold, action pause)

The old auto-eat was a bare instant-consume with no controls. It is now a proper `AutoEatController` with a full per-bot **🍖 ᴀᴜᴛᴏ-ᴇᴀᴛ** settings category:

- **Toggle** - enable/disable auto-eat per bot.
- **Hunger threshold** - a chat-input tile to set the hunger level (0-19) at or below which the bot eats. Global default `automation.auto-eat-threshold` (17) in `config.yml`.
- **Allowed foods** - a dedicated paged **food-list GUI** (mirrors the PVE mob selector). Toggle exactly which foods the bot may eat; **none selected = eat any food**. Food data lives in one source of truth (`BotFoods`) that drives both the list and the eating math.
- **Priority** - when hungry the bot reaches for food **off-hand → hotbar → main inventory**, so a food pinned in the off-hand is always eaten first.
- **Realistic eat** - the bot **pauses its current action** (via the new pause/resume system below), holds the food, eats over the vanilla ~1.6s window, applies nutrition + saturation (plus golden-apple / enchanted-apple / honey effects), then **switches back to whatever it was holding** and resumes the paused task.
- **Nametag** - shows a live `ᴇᴀᴛɪɴɢ` activity line while eating.
- **Persistence** - the enabled flag, threshold and allowed-food list persist across restarts (YAML active-bot snapshot).

### Added - `/fpp rename` + Rename Tile in the Per-Bot Settings GUI

Bots can now be renamed. This changes only a bot's **display name** - the floating name-tag name row, its entity display name, the tab-list entry and command output. The login name and deterministic `fb07` UUID (identity) are never touched, and the mandatory "ʙᴏᴛ ʙʏ {owner}" disclosure row on the name-tag is preserved.

- **`/fpp rename <bot> <new name>`** - new command (`fpp.rename`, default op). The new name may contain colour codes and is capped at **32 visible characters**. Admins can rename any bot; non-admins with the permission can rename bots they own or share (`BotAccess.canAdminister`).
- **GUI:** a new **✎ ʀᴇɴᴀᴍᴇ ʙᴏᴛ** tile (name-tag icon) at the top of the **⚙ ɢᴇɴᴇʀᴀʟ** category opens the existing chat-input prompt; type the new name (or `cancel`) to apply.
- **Persistence:** the rename is written to the YAML active-bot snapshot (on its next save) and immediately to the `fpp_active_bots.bot_display` column (new `DatabaseManager.updateBotDisplay`), so it survives restarts.
- The dormant `FakePlayerManager.renameBot(...)` helper was completed to also refresh the name-tag, entity display name and tab list, and to persist - it previously only updated the tab list.

### Open-Source Hardening - Removed Systems Fully Deleted & Blocked

FPP is now open source. The scaffolding for every removed system was deleted outright (not just neutered) so the capabilities can't be trivially re-enabled by dropping code back in, and the policy is documented so contributions reintroducing them are declined.

- **Extension/addon system - scaffolding deleted (~1,500+ lines).** Beyond the earlier "neutered loader" step, the shells are now gone: `extension/ExtensionLoader.java`, `api/FppAddon.java`, `api/FppAddonCommand.java`, `api/FppExtension.java`, `api/FppCommandExtension.java`, `api/FppSettingsTab.java`, `api/FppBotSettingsTab.java`, `api/FppBotDisplayService.java`, `api/FppSpawnLocationProvider.java`, and `fakeplayer/BotSwapController.java` all deleted. Addon-registration machinery removed from `CommandManager`, `FppApi`/`FppApiImpl`, `SettingGui`, `BotSettingGui`; `FppCommandSource` reduced to `CORE` only; `CheckCommand`'s extension probe replaced with a soft-depend check; `BotPersistence`'s `isExtensionLoaded()` replaced with `false` constants. External code can no longer be loaded - integrate via the fired `FppBot*` events and read-only `FppApi` from a normal Bukkit plugin.
- **Player spoofing/impersonation - kept out.** Removed the impersonation-adjacent `FppApi.spawnBot(..., UUID)` overload. Disclosure invariants are enforced in code and documented as non-negotiable: reserved `fb07` offline-derived UUIDs, mandatory `ʙᴏᴛ ʙʏ {owner}` nametag, tab-list + server-list-ping exclusion, and the advancement block.
- **Swap / peak-hours - removed.** `swap.*`/`peak-hours` config accessors, `debugSwap`, the `ConfigValidator` block, the `%fpp_swap%`/`%fpp_peak_hours%` placeholders, the `debug.yml` swap topic, and the SettingGui swap toggle all removed.
- **Licensing - removed** (no phone-home startup gate; the plugin runs unconditionally).
- **Dead language sections removed** (`FAKE CHAT`, `RANK`, `MINE`, `PLACE`, `USE`, `SWAP`, `PEAK HOURS`); dangling key references remapped.
- **Docs:** new `README.md` (reframed as a bot/NPC plugin, **not** a spoofer) and `CONTRIBUTING.md` with a "Deliberately Removed Capabilities" policy; `AGENTS.md` license/extension gotchas rewritten; stale "Spoofer"/extension wording scrubbed from `plugin.yml`, `velocity-plugin.json`, and `config.yml` comments.

### Added - Real Pathfinding System (Pathetic-backed)

- **Core bot navigation is back**, and it's a genuine engine now: `PathfindingService` previously had **no implementation** (`Controller` was `null` unless an extension supplied one - and extensions no longer load), so all six of `left-click`'s/`right-click`'s/`find`'s/`place`'s/`storage`'s "walk to target" fallbacks were silently no-ops. `FakePlayerPlugin` now wires up a real `PathfindingService.Controller` on startup.
- **Search engine:** [Pathetic](https://github.com/bsommerfeld/pathetic) (`de.bsommerfeld.pathetic:engine`/`api:5.5.2`, MIT, bundled in the shaded jar) - a zero-allocation, fully async A* engine. One shared `Pathfinder` serves every bot; FPP supplies the Minecraft-specific pieces Pathetic deliberately doesn't know about:
  - `BukkitNavigationPointProvider` - real block traversability (ground/headroom via `BotNavUtil`, now-public `canStandOn`), plus per-request water/lava avoidance, block-breaking, and gap-bridging (block-placing), all sourced from each bot's `pathfinding.*` config / per-bot nav settings.
  - `NavNeighbors` - 26-directional (`DIAGONAL_3D`) movement plus a small fixed set of 2-block "gap jump" offsets for parkour.
  - `ParkourGapValidator` - only allows those gap-jump transitions when parkour is enabled for the search, and only over a headroom-clear gap.
  - `TerrainCostProcessor` - soft preference for dry, unobstructed ground over water/breaking/bridging when a normal route exists.
  - `PatheticPathfindingController` - the `Controller` itself: dispatches searches async, then drives movement every tick (rotation, sprint past `pathfinding.sprint-distance`, jump-on-step/gap, waypoint advancement, stuck detection + recalculation, arrival, and real in-path block breaking/placing via `NavBlockOps` on the existing `pathfinding.break-ticks`/`place-ticks`/`place-material` config).
- Package: `me.bill.fakePlayerPlugin.fakeplayer.pathfinding`.
- No config migration needed - this fills in the pre-existing (previously unused) `pathfinding.*` config block.

### Added - `/fpp move --to` / `--coords` (Pathfinding-Backed)

Reverses the earlier "core scope reduction" that restricted `/fpp move` to directional input only - the engine to back it now exists, so destination-based movement is back in core:

- **`/fpp move <bot|all> --to <bot>`** - walk to another bot's current location, re-pathing if it moves more than `pathfinding.follow-recalc-distance` away. Gated by `fpp.move.to`.
- **`/fpp move <bot|all> --coords <x> <y> <z> [world]`** - walk to a fixed point (defaults to the bot's current world). Gated by `fpp.move.coords`.
- Both send a start message and, when a `CommandSender` is available, an arrival/no-path follow-up; both are cancellable via the existing `/fpp move <bot|all> --stop`, which now also cancels pathfinding movement (not just directional input) for that bot.
- `--to`/`--coords` on `all` resolves the destination once up front (not per bot) so a `--to` target that's itself part of the "all" batch is skipped, not treated as a batch-aborting error.
- **Not added:** `--pos`/`--roam` (open-ended wandering) - out of scope for this change; `--to`/`--coords` cover explicit-destination movement.

### Overhauled - Left-Click / Right-Click Now Fully Simulate a Real Player

- **Right-click on entities actually does something now.** Previously `tryEntityUse` only fired an internal event and swung the bot's arm - no real interaction happened. It now routes through the real NMS `Player#interactOn(Entity, InteractionHand, Vec3)` (the exact entry point a real client's interact packet uses), trying main hand then off hand. This makes feeding/breeding/taming animals, shearing sheep, milking cows, boarding boats/minecarts/horses, leashing, name-tagging, filling bottles, and opening villager trades all work for real, not just visually.
  - Since fake players have no real client to drive a trade/container screen further, any menu opened by the interaction (villager trade, chest minecart, etc.) is **closed immediately** afterward so the entity isn't left "busy" and blocking real players.
- **Right-click target resolution is now a single combined block+entity ray trace** (matching how a real client picks its target), so whichever is genuinely closer along the look vector wins - an entity standing in front of a wall is no longer skipped in favor of the wall behind it. Entity interaction is tried first; on PASS it correctly falls through to block interaction, then generic item use (eat/drink/potion/shield/bow/throwables) - same order and fallthrough a real client uses.
- **Left-click now insta-breaks blocks in Creative mode** (one click, no progress accumulation) instead of mining at survival speed, matching `ServerPlayerGameMode`'s real creative-destroy short-circuit. Survival/Adventure block-breaking (progress, tool speed, block hardness) and entity attacking (via the real `Player#attack()` - crits, sprint knockback, sweep, weapon cooldowns) were already accurate and are unchanged.

### Removed - Bot Chat System

Bots can no longer talk in chat in any way. The entire chat system was removed: AI/fake-chat output, remote chat relay, and **bot join / leave / death messages** (bots are now completely silent in chat).

- **Deleted classes:** `BotChatController`, `FppBotChatEvent`, `api.personality.ChatFrequency`.
- **`BotBroadcast`** slimmed to a display-name resolver only (all chat/join/leave/kill broadcasting removed).
- **Config:** the entire `fake-chat.*` surface, the `ChatMessageProvider` SPI, and `swap.farewell-chat` / `swap.greeting-chat` getters removed.
- **GUI:** the `💬 Chat` settings category and chat-tier/chat-enabled controls removed.
- **Network:** the `CHAT` plugin-message subchannel (`sendChatToNetwork`) removed. `JOIN`/`LEAVE` subchannels remain but no longer render any message.
- **Removed public API (⚠️ breaks the `fpp-spoof` extension until it is updated):** `FppApi.sayAsBot`, `FppBot.isChatEnabled/setChatEnabled/getChatTier/setChatTier`, `FppBotChatEvent`, `BotChatController` + `FakePlayerPlugin.get/setBotChatAI`, `Config.ChatMessageProvider` + `setChatMessageProvider/clearChatMessageProvider/reloadChatMessages`, all `Config.fakeChat*`, `Config.swapFarewellChat/swapGreetingChat`, `BotProfile.getChatFrequency` + `Builder.chatFrequency`, `Personality.chatFrequencyMultiplier`, `VelocityChannel.sendChatToNetwork`.
- **Retained (inert):** per-bot `chat_enabled` / `chat_tier` persistence (DB columns + YAML) is kept as dormant data to avoid risky schema migration; it has no behavior and is no longer in the public API.

### Added - Two-Row Bot Name Tag (mannequin-style)

- Bots now show a **two-row name tag** - row 1 is the bot's name, row 2 an indicator (default `bot by <owner>`), like a `minecraft:mannequin`.
- **Both rows** are rendered by a per-bot `text_display`; the bot's **vanilla over-head name is hidden** (via a `NAME_TAG_VISIBILITY=NEVER` scoreboard team, `COLLISION_RULE=ALWAYS` so bot collision is unchanged) so it no longer overlays the display.
- **Follow:** the display **rides the bot as a passenger** so the client keeps it perfectly glued (a free-standing display desynced from FPP's packet-driven movement and lagged). Only re-mounts if a teleport dismounts it.
- **Cleanup:** the tag is removed on every despawn/death path via `FakePlayerManager.unregisterBotState` (death removes the body directly and bypassed `removeAll`), fixing tags that lingered after a bot died/despawned.
- Implements the previously-stubbed `FakePlayerBody.spawnNametag`/`removeNametag`/`removeOrphanedNametags`; refreshes on `/fpp setowner`.
- Config block `nametag.second-line`: `enabled` (default `true`), `format` (default `<gray>bot by {owner}</gray>`, `{owner}` placeholder), `y-offset` (default `1.0`, height above the mount point).

### Changed - Bots Hidden From Tab List & Server-List Ping

- **Tab list:** bots are no longer listed in the in-game tab. `Config.tabListEnabled()` now defaults to **false** and spawn sets `listed=false` up front (no flicker).
- **Server-list ping:** new `ServerListPingListener` (Paper `PaperServerListPingEvent`) subtracts online bots from the **player count** and removes bot profiles from the **hover player sample**, so the multiplayer menu never reveals bots.
- Net effect: bots don't appear in the tab list, the server player count, or the server-list player sample.

### Removed - Extension System

- **External extensions no longer load.** `ExtensionLoader.loadExtensions()`/`reload()` are neutered (no JAR scanning/loading), the startup load is gone, and the `plugins/FakePlayerPlugin/extensions/` folder + README are no longer created. Any `fpp-spoof`/third-party addon is inert.
- **`/fpp extension` command removed**; **`/fpp reload extensions`** subcommand removed.
- Retained (inert): the internal API (`FppApi`/`FppApiImpl`, `api/event/*`, `FppBotImpl`, the command-extension map) that **core itself** uses for events/tick hooks/GUI tabs - deleting it would destabilize core, same lesson as chat/skin/LP. The `ExtensionLoader` shell is kept for its read-only references (`%fpp_extensions%` → 0, `/fpp check`, persistence checks).

### Added - `/fpp list` GUI

- `/fpp list` now opens a **GUI** for players (new `BotListGui`) instead of a chat list.
- **Permission-scoped visibility:** a normal user sees **only the bots they own**; an **admin/OP** (`fpp.admin`/`fpp.op` or op) sees **every bot on the server**.
- Each bot is a head showing owner, location, uptime, and frozen/active status; **click a bot to open its management GUI** (`BotSettingGui`). Paginated (45 per page).
- Console/command-block senders still get the text list.

### Changed - Quieter Console

- **Bot join/leave chat messages** stay suppressed; the quit handler is now bot-only (real players' quit messages are untouched again).
- **`NmsPlayerSpawner initialised (...)`** startup line dropped from INFO to debug.
- **Vanilla `<bot>[<ip>] logged in with entity id ...` login spam** is now suppressed for bots via a Log4j filter (`BotLoginLogFilter`) installed on the root logger; real players' login lines are kept.
- **JVM "Final field ... mutated reflectively by SkinProfileInjector" warning** fixed: `createGameProfile` now inserts the texture into the backing multimap **before** wrapping it in the `PropertyMap`/`GameProfile` (a normal `put`, not a reflective final-field mutation). The old `apply()`-on-fresh-profile path is gone.

### Removed - LuckPerms Integration & Mojang UUID System

- **LuckPerms integration removed:** the LP user pre-load (`NmsPlayerSpawner.preLoadLuckPermsUser`) and capability hook are gone, along with the `FppBot.getLuckpermsGroup/setLuckpermsGroup` API and the `net.luckperms:api` build dependency. (Permissions still work via any permission plugin for real players; FPP just no longer hooks LP for prefix/suffix/groups.)
- **UUID/identity system removed:** bots no longer do **Mojang name→UUID lookups** - `resolveUuid` / `refreshIdentity` / restore now always use a deterministic **offline UUID** from the name. A bot named after a real player no longer inherits that account's identity, so it won't pick up that player's data in other plugins.
- **Note:** bots are still real `ServerPlayer`s, so other plugins can still *see* them online - they just no longer map to a real account or to LuckPerms.
- Retained (inert): the `luckperms-group` per-bot persistence + `BotIdentityCache` instance (never queried) - kept to avoid destabilizing persistence, same as the chat/skin lessons.

### Removed - Dynamic Skin System (fixed bot skin)

- The dynamic skin system is gone: no Mojang fetching, no per-bot skins, no skin pool, and the `/fpp skin` command is removed (it was extension-owned; that extension breaks until updated).
- **Every bot now uses one hard-coded, Mojang-signed slim skin** (`BotSkin`), stamped at spawn - not changeable.
- `FakePlayer.getResolvedSkin()` and `SkinManager.resolveEffectiveSkin()` always return the fixed skin; skin placeholder (`%fpp_*_skin%`), the stats "skin mode" row, and the help/tab entries were removed.
- Retained (inert): `SkinProfile`/`SkinProfileInjector` (the apply mechanism), and the now-unreachable fetch classes (`SkinManager`/`SkinRepository`/`SkinFetchService`) + `skin_cache` table - kept to avoid destabilizing spawn/packets, same as the chat-field lesson.

### Changed - Sequential Bot Names

- **Removed the random name generator** (`RandomNameGenerator` deleted) and the `bot-name.mode` (random/pool) option.
- Bots now spawn with **sequential names** `bot`, `bot2`, `bot3`, … (lowest free name; despawned names are reused). This applies to both admin (`/fpp spawn`) and user-tier spawns. Explicit `--name <name>` still works.
- The **name tag** equals the bot name (`bot-name.admin-format` now defaults to `{bot_name}`).
- Removed the `--random-name` spawn flag and the `FakePlayerManager` random/pool naming (`generateUserBotName` / `UserBotName` / `botNameMode`).
- `bot-names.yml` is no longer used for bot naming (it still feeds skin-name selection in non-`player` skin modes).

### Removed - Unused/Dead Commands, Flag Consistency Pass

- **Removed commands:** `/fpp migrate` (backup/config/lang/db migration), `/fpp stats` (live statistics panel), `/fpp badword` (badword filter management). Their permissions (`fpp.migrate`, `fpp.stats`, `fpp.badword`) and `plugin.yml`/help-menu entries are gone; the underlying managers they used (`BackupManager`, `DataMigrator`, `ConfigMigrator`, `BadwordFilter`) are still used elsewhere and are untouched.
- **Removed `PlaceCommand`:** already unregistered/unreachable in core (superseded by `left-click`/`right-click`, per the existing "moved out of the registered core command set" note) - deleted the dead class along with its now-orphaned `fpp.place*` permissions and its dead area-fill sub-system (`--pos1`/`--pos2`/`--block`/`--clear`/`start`/`status`, which had been permanently disabled behind a hardcoded `false` flag with an empty tick method).
- **Flag consistency:** normalized every core command to accept only `--flag`-style sub-command arguments (no bare-word duplicates). `StorageCommand` no longer accepts `list`/`remove`/`clear`/`enable`/`disable`/`deposit` alongside their `--flag` equivalents - `--flag` only. `PlaceCommand`'s `once`/`--once` duplicate is gone with the command.
- **Usage-string fixes:** `left-click`/`right-click` usage now documents the standalone `--stop` form they already supported; `find` usage now documents the `-r`/`-c` short aliases for `--radius`/`--count`; `storage` usage now lists `--enable`/`--disable`/`--deposit`, which were previously undocumented.

### Added - Baritone-Style Pathfinding Path Debug

- New **🧭 Pathfinding** category in the per-bot Settings GUI (`/fpp settings <bot>`), restoring the previously-orphaned `nav_parkour`/`nav_break_blocks`/`nav_place_blocks` toggles (their handlers already existed but had no GUI entry point since an earlier category removal) alongside a new **Show Path (debug)** toggle.
- **Show Path** renders a live particle trail along the bot's active `PatheticPathfindingController` route every 4 ticks while it's navigating - green dust for the general route, orange for the immediate next waypoint, and a red marker (with a short vertical particle stack) at the final destination, similar to Baritone's path render.
- **Per-viewer only:** this is a personal debug preference, not a bot property - toggling it only affects what *you* see (`PathfindingDebugManager`, keyed by viewer UUID → bot UUID), it isn't persisted, and it costs nothing when nobody has it enabled (the render loop bails out immediately if there are no viewers for that bot).
- Cleaned up automatically on player quit and bot despawn/delete.

### Fixed - `/fpp find` and `/fpp storage` Were Never Registered

- Both command classes were fully implemented (and had working permissions, tab-complete, and `CommandManager` task-target wiring) but were never instantiated or registered in `FakePlayerPlugin.java` - `findCommand` stayed a permanently-null field, and `StorageCommand` was never constructed at all. `/fpp find` and `/fpp storage` did not exist in-game despite appearing fully built. Both are now registered on startup.

### Fixed - `/fpp check` False-Positive Warnings

- Config-key validation checked the wrong paths (`max-bots`/`server-id`/`database.type`) instead of the real ones (`limits.max-bots`/`database.server-id`/`database.mode`) - every install reported 3 "missing config key" warnings for keys that were actually present.
- The "Lang directory" checks (both the database section and the dedicated language-file section) looked for a `lang/` folder; the plugin actually uses `language/` - fixed to check the real path.
- Removed the "Extensions directory - MISSING/FAILED" check: external extension loading is inert by design now, so that folder legitimately isn't created and the warning was permanent noise.
- Removed the LuckPerms soft-dependency check: FPP no longer integrates with LuckPerms at all, so checking for it made no sense.

### Changed - `/fpp move` Simplified to Pathfinding-Only

- Removed the directional raw-input mode entirely (`--direction <forward|backward|left|right>`, `--seconds`/`--ticks`) - `--to`/`--coords` cover the real use cases and were the source of user confusion about which flags did what.
- `--to <bot|player>` now also accepts a real online player's name, not just another bot.
- `--to` now actually **follows live**: previously it snapshotted the target's location once at command time and never updated it, even though a `follow-recalc-distance` config option implied live-following - the destination supplier was a constant, so recalculation never triggered. It now re-queries the target's current location on every check.
- New usage: `/fpp move <bot|--all> --to <bot|player>  |  /fpp move <bot|--all> --coords <x> <y> <z> [world]  |  /fpp move <bot|--all> --stop`.

### Fixed - Stale Extension System Documentation & Check Output

- `frontend/wiki/Extensions.md` extensively described JAR-based extension loading as a live, working feature (installation steps, folder structure, bundle loading, troubleshooting) even though external extension loading was disabled entirely in an earlier change this session. Added a prominent notice at the top and annotated every section that describes non-functional install/load steps (Getting Started, Extension Bundles, Troubleshooting, First-Party Extension Bundle) so the page is no longer misleading about what currently works. The API reference itself (`FppApi`, `FppBot`, hooks, events) remains documented since core still uses the same interfaces internally.
- `%fpp_extensions%`/`%fpp_extensions_names%` placeholder docs (`README.md`, `frontend/wiki/Placeholders.md`) now note they always resolve to `0`/empty since external loading is disabled; removed the stale `%fpp_extensions%` line from the example scoreboard config.
- `/fpp check`'s extensions step now explicitly states "0 loaded is expected, not an error" instead of presenting an inert subsystem as if it were a live one being validated.
- Confirmed the settings-tab GUI injection API (`registerSettingsTab`/`registerBotSettingsTab`) has no callers anywhere in core - it's dormant, unreachable scaffolding for a future extension loader, not something rendering an empty "Extensions" GUI tab today, so no GUI code changes were needed there.

### Removed - All Player-Impersonation/Spoofing Capability

Every mechanism that let a bot look, sound, or behave like a specific real player, or like a real
connected client, has been removed. Bots remain fully functional automatable game entities (spawn,
move, fight, mine, etc.) - only the ability to impersonate or be mistaken for a real player is gone.

- **Skin:** the hard-coded skin (a real, signed Mojang skin belonging to a real account) is gone.
  Every bot now renders the client's plain default Steve/Alex model (`BotSkin.fixed()` returns an
  invalid profile, so the NMS injection path falls through to vanilla). No bot can wear a specific
  identifiable real player's actual skin - ever.
- **Naming:** removed every path to an arbitrary or real-player-matching bot name. `/fpp spawn`'s
  `--name` flag is gone (bots are *always* sequentially auto-named `bot`, `bot2`, …), `/fpp rename`
  is deleted entirely, and the Settings GUI's in-GUI rename button is gone along with the shared
  `BotRenameHelper` it used. `--notp` (which only worked in combination with `--name`, to look up a
  specific named bot's last known location) is gone with it - persistence-based restore-on-restart
  is unaffected and still restores all bots automatically.
- **Tab list:** `Config.tabListEnabled()` is now permanently hardcoded to `false` with no override
  mechanism at all (the dead `setTabListEnabledProvider` indirection, which already had zero callers
  anywhere, is removed outright). Bots can never appear in the tab list.
- **Server-list ping:** `ServerListPingListener` (which used to subtract bots from the reported
  player count and clear the hover sample) is deleted. The server list now reports Bukkit's plain,
  true state with no special-casing in either direction - bots are neither hidden nor inflated.
- **Ping/latency simulation:** the entire fake-network-realism subsystem is removed - no more random
  jitter, connection spikes, join ramp-up, or artificial action-latency throttling designed to make a
  bot's connection seem human. An explicit, deliberate ping override (`FppApi`'s custom-ping method)
  still exists for legitimate debug/testing use, but nothing ever auto-generates a "realistic" fake
  value anymore.
- **Nametag disclosure is now mandatory:** the "bot by {owner}" second nametag line can no longer be
  disabled (`nametag.second-line.enabled` config key removed) - every bot must always visibly
  identify itself as a bot.

**Hard constraint, unavoidable:** bots are fundamentally NMS `ServerPlayer` entities and will always
render client-side as a humanoid Minecraft player model - removing that would require rewriting the
entire entity architecture (e.g. ArmorStand/NPC-based bots), breaking inventories, combat,
pathfinding, and nearly everything else this plugin does. That part is out of scope and isn't
changing. Everything else that could make a bot *pass as* or *impersonate* a specific real player is
gone.

### Fixed - Vanilla Bot Death/Kill Message Still Leaked to Chat

- Despite bot chat being fully removed earlier this session, the vanilla `PlayerDeathEvent` death
  message (e.g. "bot1 was slain by Steve") was still showing in chat - `Config.deathMessage()`
  defaulted to `true` and only got nulled out under specific conditions. Bots now unconditionally
  suppress their death message, matching join/leave. Removed the now-fully-dead
  `Config.deathMessage()`/`Config.killMessage()` accessors and the `messages.death-message`/
  `messages.kill-message` config.yml keys (neither had any other consumer).

### Added - Despawn Effect

- Every bot despawn/delete/death-removal path now plays a small poof-cloud particle burst and sound
  at the bot's last location (`FakePlayerManager.playDespawnEffect`, hooked into the shared
  `unregisterBotState` cleanup that all removal paths already funnel through) - a bot vanishing now
  has a visible/audible cue instead of just silently disappearing.

### Fixed - Despawn Effect Silently Skipped on Death, No Death-Despawn Message At All

- The despawn poof/sound effect added earlier this session had a real ordering bug: on the
  death-without-respawn path (and the respawn-failure edge cases), `fp.setPlayer(null)` ran *before*
  the effect's entity lookup, so it silently no-op'd every time. Fixed by capturing the bot's location
  while the entity reference is still valid and threading it through explicitly
  (`FakePlayerManager.removeByName(name, fallbackLoc)`, `unregisterBotState(fp, reason, fallbackLoc)`).
  The effect now fires correctly on `/fpp despawn`, death-without-respawn, and failed respawn attempts.
- Separately: `/fpp despawn <name>` already sent the sender a confirmation message
  (`delete-success`), but the death-without-respawn path sent **no message to anyone** - only a
  debug-level log line invisible unless `debug.yml` is on. Added `bot-died-despawned`, sent privately
  to just the bot's owner and whoever killed it (if online and different from the owner) - no one
  else sees it - when the bot dies and is removed instead of respawned.

### Added - Live Activity Line on Bot Nametags

- The nametag is now three rows instead of two: a new top row shows what the bot is currently doing
  (idle, moving, mining, attacking, using item, searching, frozen - joined if more than one applies),
  above the existing name and "bot by {owner}" rows.
- New `BotActivity.currentLabel(fp)` (`fakeplayer/BotActivity.java`) computes this **live** by directly
  querying each task command's own state (`PathfindingService.isNavigating`/`getOwner`,
  `LeftClickCommand`/`RightClickCommand.isClicking`, `AttackCommand.isAttacking`,
  `FindCommand.isFinding`, `FakePlayer.isFrozen`) rather than mirroring a separately-tracked
  start/stop flag - avoids desync (e.g. `/fpp move --to` doesn't fire a "stop" event on natural
  arrival, so a mirrored-state approach would get stuck showing "moving" forever after arrival).
- Refreshed once a second per bot, and only re-sent to the nametag entity when the label actually
  changed (`FakePlayer.lastRenderedActionLabel`), to avoid needless packet churn.
- **Fixed:** the name row was picking up the action row's color (turning yellow) because Adventure
  components inherit an unset color from whatever they're appended onto, and the name row was nested
  as a child of the colored action row. Fixed by building all three rows under a colorless root and
  giving each row an explicit color instead of relying on inheritance - the name is white again, and
  the action row now matches whatever color the bottom "bot by {owner}" row is configured with
  (read directly from `indicatorRow.color()`, so it stays in sync if that format is customized).

### Overhauled - `/fpp find`: Tool Auto-Equip, Anti-Stuck, Inventory-Aware Deposit

`/fpp find` already had a solid search→path→mine loop and strong loop protection (blocks are marked
visited before navigation starts, plus a cross-bot reservation map so two bots never fight over the
same block). This pass fixes what was actually missing:

- **Real tool auto-equip** - `equipBestTool()` was a literal empty stub with a comment saying it was
  "handled by the addon auto-equipment tick handler," but external extensions can never load anymore,
  so bots mined with whatever was already held, tool or not. Replaced with real logic driven by
  Bukkit's own `Tag.MINEABLE_PICKAXE`/`MINEABLE_AXE`/`MINEABLE_SHOVEL`/`MINEABLE_HOE` (the same data
  vanilla itself uses) to pick the right tool category, then the best tier available in the bot's
  inventory (`ITEMS_PICKAXES`/etc., ranked wooden→netherite) - covers every tool type, not just
  pickaxes.
- **Anti-stuck, mining phase** - tracks ticks-since-last-destroy-progress per block; if it stalls for
  5s (wrong/no tool, block resisting breakage, etc.) the block is abandoned instead of grinding on it
  forever. It's already marked visited, so it's never re-targeted.
- **Anti-stuck, navigation phase** - the shared pathfinder auto-recalculates on physical stuck-detection
  but never abandons a target; added a 45s watchdog scoped to find-jobs only that gives up on a block
  and moves to the next one if navigation genuinely can't get there, without touching the shared
  pathfinding subsystem.
- **Inventory-aware auto-deposit** - when free inventory space drops to 2 slots or fewer, the bot
  detours to its nearest registered, enabled storage (reusing `StorageStore`/`StorageInteractionHelper`,
  the same infrastructure `/fpp storage`'s deposit already uses) and resumes the find job from wherever
  it ends up. Bots with no storage registered keep the previous passive drop-collection behavior - no
  regression.
- **Fixed a real reservation leak**: `FakePlayerManager.unregisterBotState` (the shared despawn cleanup
  every despawn/death path funnels through) called `MoveCommand`/`LeftClickCommand`/`RightClickCommand`
  cleanup on despawn, but never `FindCommand.cleanupBot` - a bot despawned mid-find-job could leave its
  block reservations locked forever, permanently blocking those blocks for every other bot. Now cleaned
  up on every despawn path like the others.

### Added - "Show All Paths" Global Debug Toggle in Settings GUI

- The existing per-bot "Show Path (debug)" particle-trail toggle (`🧭 Pathfinding` category in each
  bot's own settings) now has a global counterpart: `🐛 ᴅᴇʙᴜɢ` → **Show All Paths** in the main
  `/fpp settings` GUI. One click enables (or disables) the pathfinding debug trail for every currently
  active bot at once, visible only to the player who toggled it.
- Backed by two new methods on the existing `PathfindingDebugManager` (`setViewing` - explicit set
  rather than flip, `isViewingAny` - true if the viewer has the trail on for any bot); no new state
  store, no persistence (same as the per-bot toggle, a pure viewer preference).
- Doesn't retroactively apply to bots spawned after the toggle is flipped - re-click to pick them up.

### Added - Chat-Based Pathfinding Debug

The particle trail shows *where* a bot is trying to go, but not *why* it's not getting there. Whoever
has the path-debug view enabled for a bot (per-bot toggle or the global "Show All Paths" toggle) now
also gets chat messages explaining the specific failure when something goes wrong:

- **`pathdebug-stuck`** - the bot hasn't moved far enough for `pathfinding.stuck-ticks` in a row and
  the path is being recalculated (e.g. wedged against terrain, a moving target, an obstacle the
  original path didn't account for).
- **`pathdebug-no-path`** - the search engine genuinely couldn't find a route to the target after
  exhausting its retry budget (destination unreachable, sealed off, out of range).
- **`pathdebug-watchdog`** - `/fpp find`'s 45s navigation watchdog fired, meaning the bot never
  arrived/cancelled/failed in time and the target was abandoned outright (this is the case the
  recalculate-forever behavior above can't catch on its own).
- **`pathdebug-mine-stall`** - `/fpp find`'s mining loop made no destroy progress for 5s straight and
  gave up on the block (almost always a missing/wrong tool, or a block too hard to break).
- New `PatheticPathfindingController.sendDebugChat(botUuid, botName, langKey, args...)` - reuses the
  existing `PathfindingDebugManager` viewer set (the same subscription the particle trail uses), so
  there's exactly one toggle for both the visual and the chat debug output.
- All four new lang keys added under `PATHFINDING DEBUG (chat)` in `language/en.yml`.

### Fixed - Infinite Stuck→Recalculate Loop, Better Terrain Awareness

Bots could get permanently wedged (e.g. inside a tree canopy, tangled in vines/leaves) and spam
"stuck - recalculating" forever without ever giving up, because recalculating against completely
unchanged terrain just returns the same path and gets stuck again immediately - nothing about the
old logic ever broke that cycle.

- **Hard give-up budget** - `pathfinding.max-stuck-cycles` (default `4`): each stuck→recalculate
  cycle that produces zero real forward progress counts against this budget; once exhausted, the
  navigation is abandoned outright (same as a genuine "no path found") instead of looping forever.
  Real movement resets the budget, so this only fires when the bot is truly wedged, not during
  ordinary momentary slowdowns.
- **Jump nudge before recalculating** - halfway through the stuck window, the bot now attempts a
  jump before giving up and recomputing a whole new path. Covers the common case of being stuck on
  a lip/leaf/slab that just needs a hop, which a full recalculation wouldn't fix anyway (the route
  was already fine).
- **Less twitchy stuck detection** - `pathfinding.stuck-ticks` default raised `5` → `10` (0.25s was
  triggering on completely ordinary momentary slowdowns, e.g. foliage friction, not just genuine
  stuck states).
- **Terrain cost now penalizes climbing and canopy routes** - `TerrainCostProcessor` previously had
  no opinion on vines/scaffolding/ladders or leaf-covered ground, so the search treated a vine climb
  through a tree canopy as equal-cost to a clear walking route. Both are still legal (nothing was
  removed from `BukkitNavigationPointProvider`'s hard traversability gate), but now cost more so a
  comparable ground route is preferred whenever one exists - the search only tunnels through
  foliage when it's genuinely the only way.
- `BotNavUtil.isClimbable` made public so the cost processor can reuse the same climbable-block list
  the traversability check already uses (`fakeplayer.pathfinding` and `fakeplayer` are still separate
  packages, so this was `private` before with no reuse path).

### Added - Detailed Pathfinding Diagnostics, Now Logged to Console Too

The chat debug messages added earlier said *that* something failed but not much about *why*. New
`debug.yml` → `pathfinding` toggle (also exposed in `/fpp settings` → `🐛 ᴅᴇʙᴜɢ` → **Pathfinding**)
logs a full diagnostic line to the **server console** - not just in-game chat - for every stuck cycle,
give-up, no-path failure, `/fpp find` navigation watchdog, and mining stall:

- **Stuck / give-up / no-path** (`PatheticPathfindingController`): bot name, nav owner, stuck-cycle
  count vs. the give-up budget, failed-recalculation count, waypoint progress (`index/total`), current
  and target position (world + coords), remaining distance, the actual block at the bot's feet/below/
  head (this is the part that would've explained the tree-canopy screenshot - you'd see
  `blocks{feet=VINE,below=OAK_LEAVES,head=OAK_LEAVES}` directly in the log), and which path options
  were active (parkour/break/place/avoidWater/avoidLava).
- **`/fpp find` navigation watchdog**: bot name, elapsed ticks, bot position, and the specific target
  block (type + coordinates) it gave up walking to.
- **`/fpp find` mining stall**: bot name, the block it was mining (type + coordinates), the tool
  currently held, and how much destroy-progress was actually made before giving up - makes "wrong
  tool" vs. "block genuinely too hard" immediately obvious from the log alone.
- One line per event, `event=...` prefixed and grep-friendly (e.g. `grep "event=STUCK"`), independent
  of whether anyone has the in-game path-debug view open - the whole point is being able to diagnose a
  bot that got stuck hours ago from the console/log file after the fact.
- Same `debug-chat` master switch as every other debug topic also mirrors these lines to online OP/
  notify players if wanted, on top of the existing pathfinding-viewer-only chat messages.

### Added - Path Verification / Simulation Before a Bot Ever Commits to Walking

Previously, once Pathetic's async search returned a route, the bot just started walking it - any
"this route doesn't actually work" discovery only happened at runtime, via the stuck-detection budget
added earlier. That's a wasted trip: the bot walks partway toward something, gets stuck, and only then
gives up. Routes are now re-checked *before* any movement starts:

- **`BukkitNavigationPointProvider.isTraversable`** (the exact rule the live search itself uses to
  decide "can a bot's feet legally occupy this block") is now a shared `static` method instead of
  private, so it can be reused as a single source of truth instead of duplicating the logic.
- **New `PatheticPathfindingController.verifyPath`** re-walks the entire finished waypoint list
  synchronously, on the main thread, against the real current block state (Pathetic's search runs
  async against a snapshot, so chunks can have changed by the time it finishes) and rejects the route
  outright if:
  - any waypoint is no longer traversable right now (`NOT_TRAVERSABLE`),
  - a step rises more than one block without a climbable block (vine/ladder/scaffolding) actually
    present there - an impossible move for the tick-driven walker to execute (`IMPASSABLE_RISE`),
  - a gap-jump slipped through without parkour enabled, or is wider than the neighbor set could have
    legitimately produced (`UNEXPECTED_GAP` / `GAP_TOO_WIDE`) - defense-in-depth on top of
    `ParkourGapValidator`, which already gates this during the search itself.
- **Waypoint 0 is skipped in the re-check** - it's the bot's actual current position, not a
  synthesized grid node, so it's valid by definition. The first version of this re-verification
  re-ran the traversability heuristic against it too, and since that node is never re-validated by
  the live search either (it's the given start, not something the neighbor strategy generated), any
  disagreement between "the bot is physically standing here" and the block-model's opinion of that
  same spot rejected the route outright - on *every single navigation*, since node 0 is present in
  every path. That's what broke `/fpp find` (and pathfinding generally) entirely right after this
  feature landed; fixed before it shipped any further.
- A rejected route is treated exactly like a failed search (`onPathFailed`) - it plugs into the
  existing recalculation/give-up budget rather than adding new state, and gets a
  `event=PATH_REJECTED reason=...` line in the console when pathfinding debug logging is on.
- Fixed a latent bug found while making this change: if the bot went offline in the moment between
  starting a search and it completing, the old code silently did nothing (never started the movement
  task, never reported failure) instead of calling the request's failure/cancel callback.

### Fixed - Bots Still Counted in the Server-List Player Count / Showed as "Anonymous Player"

The wiki already documented bots being subtracted from the multiplayer menu's player count and hover
sample, but the listener that was supposed to do it (`ServerListPingListener`) never actually existed
in the codebase - bots were counting toward "X/20" and appearing in the hover list as "Anonymous
Player" (the client's fallback when it can't resolve a sample entry to a real profile) the entire
time. Implemented for real this time:

- New `ServerListPingListener` (`PaperServerListPingEvent`, `com.destroystokyo.paper.event.server`)
  registered in `FakePlayerPlugin.onEnable`.
- The hover sample is **rebuilt from real online players** rather than filtered by bot UUID. The
  first attempt filtered `getListedPlayers()` by each bot's logical UUID - that fixed the count but
  the "Anonymous Player" hover entry survived, because the profile a bot contributes to the sample
  isn't guaranteed to carry that UUID (skin injection can swap the profile identity, and an
  anonymized sample entry carries no identity at all). Rebuilding sidesteps the matching problem
  entirely: clear the sample, re-add only genuine players (excluded by both UUID - logical *and*
  live-entity - and name), and set `numPlayers` to the count of what was re-added.
- Runs at `EventPriority.LOW` so other plugins (vanish, etc.) can still adjust the list after us.
- Net effect: bots are invisible to the server-list ping entirely - not in the player count, not in
  the hover sample - same as they already were in the in-game tab list.

### Changed - Readable, Numbered Bot UUIDs (`fb07` Prefix)

Bot UUIDs are no longer vanilla offline-mode hashes (`MD5("OfflinePlayer:"+name)`) - they now carry
a fixed, recognizable `fb07` prefix with the bot's identity readable straight out of the UUID:

- **Sequential names embed their number**: `bot` → `fb070000-0000-0000-0000-000000000001`,
  `bot2` → `…-000000000002`, `bot123` → `…-00000000007b` (hex). The number comes from the name
  itself, so the scheme stays fully stateless - same name, same UUID, every restart, nothing stored.
- **Custom names** get the same prefix with a 64-bit hash of the lowercase name in the low bits
  (UUIDs are hex-only, so a name like `Steve` can't be embedded literally), plus a marker bit in
  the high half so a bot custom-named `bot1` or `bot02` can never collide with a canonical
  sequential bot's UUID.
- **Why `fb07` and not all-zero high bits**: `getMostSignificantBits() == 0` is the standard idiom
  plugins use to detect Geyser/Floodgate Bedrock players - bots with `00000000-…-0001` UUIDs would
  be misclassified as Bedrock players. The fixed non-zero prefix avoids that and gives a trivial
  "is this a bot?" check: new `BotIdentityCache.isBotUuid(uuid)`.
- **Zero collision risk with real accounts**: premium UUIDs are random v4, offline-mode ones are
  MD5 v3 - neither can land in a fixed-prefix range.
- **Automatic migration**: on restore, persisted bots still carrying their legacy offline-mode UUID
  are remapped to the new scheme, and their saved inventory/XP/task state follows them to the new
  key (via the existing `remapLoadedState` machinery). Anything with a non-legacy stored UUID (e.g.
  an explicit-UUID API spawn) is trusted as-is and untouched. Old `world/playerdata/<legacy-uuid>.dat`
  files become inert orphans.
- New unit tests (`BotUuidTest`) pin the exact mappings, case-insensitivity, the `bot1`/`bot02`
  no-collision guarantees, and the Floodgate/`isBotUuid` boundary behavior.

### Added - Rarity-Based Bot Skin Pools + Slim/Classic Model Detection

Bots previously spawned with no skin at all - `SkinManager` was never instantiated in core (it was
created by the removed FPP-Skin extension), so every bot fell back to default Steve/Alex. Core now
owns the skin system, built around rarity pools:

- **Skin pools** live in `plugins/FakePlayerPlugin/skins/` (seeded from bundled defaults on first
  run) as text files of NameMC skin URLs, one per line:
  - `main_skin.txt` - the default skin every bot spawns with.
  - `1-<N>%.txt` - a "1 in N" pool: each fresh spawn rolls a 1/N chance to draw a random skin from
    that file instead of the main skin. Ships with `1-1000%`, `1-5000%`, `1-10000%`, and `1-100000%`
    tiers; add or remove pool files freely. Rarest pools roll first, so a lucky spawn always claims
    the best tier it hit.
- **Signing pipeline** (new `SkinPoolService`): NameMC only hosts the PNG, and skins need a
  Mojang-signed texture to render on clients - so on first use the PNG is downloaded, its player
  model is detected, and it's run through the MineSkin API for signing (serialized, ≥6s apart, to
  respect MineSkin's anonymous rate limit). The signed result is cached **forever** in
  `data/skin-cache.yml` - each skin touches the network exactly once, ever. If a rare skin can't be
  resolved (network down), the bot degrades to the main skin, then to vanilla default.
- **Slim/classic model detection** (new `SkinModelDetector`, unit-tested): two strategies -
  authoritative `textures.SKIN.metadata.model` from a signed texture's base64 JSON, and raw-pixel
  analysis for bare PNGs (the 4th arm-pixel column at x=54–55 is transparent on slim skins; legacy
  64x32 skins are always classic; multiple sample rows so one stray pixel can't flip the result).
  The detected variant is passed to MineSkin so the signed skin renders with the correct arm model,
  and the model is stored in the cache alongside the texture.
- **Roll-once semantics**: `SkinManager.resolveEffectiveSkin` short-circuits when the bot already
  carries a resolved skin - restores from persistence, respawn snapshots, and manual skin applies
  all keep their existing skin. The rarity roll happens once per brand-new spawn only.
- **Rare-roll announcement**: the spawner gets a chat message when their bot hits a rare pool
  (new `skin-rare-rolled` lang key), plus a console log line.
- **Config**: `skin.rare-pools: true` in `config.yml` - set `false` to disable the entire system
  (bots then use the vanilla default skin as before).
- **Fixed shortly after landing**: the PNG download used NameMC's legacy `texture.namemc.com`
  mirror, which now fails behind Cloudflare (HTTP 526, invalid origin SSL) - switched to the direct
  `https://s.namemc.com/i/<id>.png` endpoint (verified live), with the legacy mirror kept as an
  automatic fallback. Also added a 10-minute per-skin failure cooldown so an unresolvable skin
  can't repeatedly burn MineSkin quota on every spawn attempt, and the main skin is now prewarmed
  asynchronously at startup (cache-first - a no-op on every start after the first), so the first
  spawn never waits on the signing API.
- **Fixed: bundled pool files could never be extracted.** Bukkit resolves plugin resources through
  a URL, where `%` is an escape character - so bundled names like `skins/1-1000%.txt` failed to
  extract (`%.t` is an invalid escape), the failure was only visible at debug level, and installs
  ended up with a permanently empty `skins/` folder (seeding skipped because the directory
  existed). Bundled resources are now `%`-free (`1-1000.txt`), copied manually to their
  `1-1000%.txt` data-folder names, seeding self-heals a `skins/` directory that exists but holds
  no `.txt` files, extraction failures log at WARN, and a missing/empty `main_skin.txt` now warns
  loudly instead of silently spawning default-skin bots.
- **Fixed the actual "skin never applies" root cause** (found via the new debug tracing):
  `FakePlayer.getResolvedSkin()` was a leftover stub from the old skin-system removal that
  unconditionally returned the invalid `BotSkin.fixed()` placeholder - the setter stored the
  resolved pool skin, but every read got the placeholder back, so the apply step always concluded
  "no valid resolved skin" and left the bot on the vanilla default. The getter now returns the
  stored field again.
- **Full skin-pipeline debug tracing** - new `debug.yml` → `skin-pool` toggle (also in
  `/fpp settings` → `🐛 ᴅᴇʙᴜɢ` → **Skin Pool**) logs every step of the pipeline with a
  `[DEBUG/SKIN-POOL]` prefix: pool loading (per file, with skin counts), the roll each spawn makes
  (main vs rare tier), cache hit/miss, PNG download attempts per mirror with byte counts, the
  pixel-analysis model verdict, the MineSkin request (variant, URL, rate-limit waits) and its HTTP
  response code, response-shape parsing, cache writes, prewarm activity, and the final
  `resolveEffectiveSkin` decision per bot (existing skin kept / pool skin applied / fell back to
  vanilla and why). MineSkin HTTP errors now log the response body at WARN unconditionally, so a
  quota rejection is visible without any debug flag.

### Changed - GUI Organization & Polish Pass

- **New per-bot `🎨 ꜱᴋɪɴ` category** in a bot's settings GUI (between Pathfinding and Danger):
  - **Current Skin** - shows the skin's source (main pool / ✨ rare tier with odds / custom /
    restored) and the player model (slim/classic), detected live from the signed texture via
    `SkinModelDetector`. Clicking echoes the summary to the action bar.
  - **Re-roll Skin** - clears the bot's resolved skin and rolls the pools again with identical
    odds to a fresh spawn (rare tiers included); the new skin applies and persists immediately.
- **`/fpp settings` → Debug category reorganized** - entries were in accretion order (each new
  toggle appended wherever); now grouped logically: core switches (master, debug-chat, general,
  startup) → bot systems (pathfinding, skin pool, commands, head AI, swap, chat, right-click ×2) →
  NMS internals (master, bot, connection, damage, physics, skin) → storage & network (database ×5,
  packets, network, config-sync).
- **`/fpp list` bot entries enriched** - each bot's lore now shows its **live activity** (idle /
  moving / mining / …, sharing the nametag activity engine, updated by the list's existing 1 Hz
  refresh) and a **✨ ʀᴀʀᴇ 1/N skin line** when the bot wears a rare pool skin (no line for
  main/custom skins, keeping common bots uncluttered).
- `HelpGui` reviewed - already fully on the shared `GuiKit` utility (colors, fillers, click
  sounds, text wrapping); no changes needed.

### Changed - Full Text & Color Consistency Pass

Every player-facing string now follows one style: small-caps lettering, `{prefix}` branding, gray
body text, `#0079FF` accent for names/commands/values, white for emphasis, red for errors.

- **`/fpp perf` section rewritten** (~25 keys in `language/en.yml`) - the entire performance command
  output was plain English ("Provider:", "Benchmark started for 5 minutes…") while the rest of the
  plugin used small-caps; now fully matched, including the `perf top` dashboard rows.
- **Version-unsupported messages** converted to the same style (brand names, MC versions, and links
  stay readable plain text).
- **Nine hardcoded English messages moved into the lang system** - `/fpp save` (3), `/fpp setowner`
  (3), `/fpp storage` deposit (2), and the bulk-task dispatch confirmation were built inline in Java
  with raw `NamedTextColor`s and no prefix, so they were unthemeable and looked foreign next to
  everything else. All now use new lang keys (`save-*`, `setowner-*`, `storage-none-enabled`,
  `storage-walking`, `task-dispatched`) - server owners can retheme them like any other message.
- **`/fpp tp` cleaned up** - it had its own homemade `[ꜰᴘᴘ]` prefix and three inline English
  messages; converted to lang keys (`tp-no-body`, `tp-success`, `tp-active-bots`).
- **`inv-busy`** was the one lang key still in plain English - converted.
- **Glyph consistency**: 26+ occurrences of the wrong "g" codepoint (`ɡ` LATIN SMALL LETTER SCRIPT G
  instead of `ɢ` LATIN LETTER SMALL CAPITAL G) fixed across `en.yml`, `SettingGui`, and
  `BotSettingGui` - they render visibly differently in-game.
- **Typos fixed in GUI text**: `ꜰᴏʟʟᴡ`→follow, `ꜰɪʜᴇᴅ`→fixed, `ɪɴᴠᴇɴᴛᴏʏ`→inventory, a stray `ɘ`
  in "the", and syncronization→synchronization.
- **Default nametag format** (`config.yml` `nametag.second-line.format`) now small-caps
  (`ʙᴏᴛ ʙʏ {owner}`) to match the activity row above it - existing configs keep their current value.

### Removed - Dead Code Sweep (~2,800 Lines)

Full scan for code with zero remaining callers, mostly orphans of the removed extension/skin/identity
systems. Everything below was verified unreachable before deletion; the shaded jar shrinks accordingly.

- **`SkinManager` rewritten from ~1,700 to ~180 lines** - the entire player-name skin resolution
  system was unreachable: the 1,000-name fallback account pool, Mojang name→skin fetching,
  `applySkinByPlayerName/Username/Url`, `applySkinFromPlayer/OfflinePlayer`, `preloadSkin`, the
  profile cache, and the guaranteed-skin fallback chain. What remains is exactly the live surface:
  pool-based `resolveEffectiveSkin`, `applySkinFromProfile`/`applySkinFromTextures`,
  `resetToDefaultSkin`, and `reload`.
- **`SkinRepository` deleted** (~420 lines) - only reachable from the deleted SkinManager paths.
- **`SkinFetchService` interface deleted** (+ the plugin's field/getter/setter) - its only real
  implementation lived in the removed FPP-Skin extension; core only ever saw the NOOP.
- **`BotIdentityCache` stripped to its static UUID helpers** (~460 → ~120 lines) - the stateful
  name→UUID cache (YAML/DB loading, Mojang premium-UUID resolution, rate limiting, migrations) had
  zero callers since the identity-system removal; only `deterministicBotUuid`/`isBotUuid`/
  `offlineModeUuid` are live. The old `identities.by-name` YAML data and `bot_identities` DB rows
  stay on disk untouched but are no longer read or written - one less startup I/O pass.
- **Eight dead `Config` skin accessors deleted** (`skinMode`, `skinGuaranteed`, `skinCustomPool`,
  `skinCustomByName`, `skinUseSkinFolder`, `skinClearCacheOnReload`, `skinMineSkinUrlUploadEnabled`,
  `skinMineSkinVisibility`) - all read config keys that no shipped config contains, for systems that
  no longer exist.
- **`TpCommand`** unused color constants and stale `@SuppressWarnings` removed.

### Added/Fixed alongside the sweep

- **MineSkin API key support** - the one dead accessor worth keeping, `skinMineSkinApiKey`, is now
  actually wired in: set `skin.mineskin-api-key` in `config.yml` (get one at mineskin.org/apikey)
  and the skin-pool signer sends it as a Bearer token for a much larger quota than anonymous use.
- **`/fpp reload` now reloads the skin system** - previously neither `reload config` nor
  `reload all` re-read the skin pool files or cache; editing `skins/*.txt` required a full restart.
- **Fixed: skin resolution failed entirely when NameMC's CDN blocks the server** - NameMC sits
  behind Cloudflare bot protection, which 403s plain Java downloads from many hosting/datacenter
  IPs even when the same URL works from a browser. The local PNG download only feeds slim/classic
  pre-detection, and MineSkin fetches the texture URL itself from its own (unblocked) servers - so
  a blocked local download is no longer fatal: the signer proceeds with `variant=auto` (MineSkin
  runs the same pixel detection server-side) and the authoritative model is still read from the
  signed texture afterward. The local download also now sends browser-like headers so it succeeds
  on more networks in the first place.

### Changed - `/fpp spawn`: One Bot Per Command, `--name` Support

- **The bulk `[amount]` argument is gone** - `/fpp spawn 50` no longer exists; every invocation
  spawns exactly one bot. The `fpp.spawn.multiple` / `fpp.spawn.mass` permissions and the
  `limits.spawn-presets` config accessor died with it and were removed.
- **The bot-type tag is gone** - the leading `afk` token is no longer accepted; bots always spawn
  as the default type internally.
- **New `--name <name>` flag** - `/fpp spawn --name Miner` spawns one custom-named bot. Names are
  validated (1-16 chars, letters/numbers/underscores, badword filter), rejected when a bot already
  uses them (`spawn-name-taken`), invalid (`spawn-invalid-name`), or when a real player with that
  name is online (`spawn-name-online`) - three new lang keys. Works for both admin (`fpp.spawn`)
  and user (`fpp.spawn.user`) tiers; the mandatory "ʙᴏᴛ ʙʏ {owner}" nametag row still marks every
  bot as a bot regardless of name.
- **World/coordinate arguments unchanged** (`/fpp spawn world_nether 100 64 -200`, `~ ~ ~`
  relatives) - but `fpp.spawn.coords` is now **actually enforced**: the permission was declared in
  plugin.yml and never checked in code, so user-tier spawners could place bots at arbitrary
  coordinates despite the docs saying they can't.
- Tab completion reworked: suggests `--name`, then worlds/coords; no more count presets.
- `Commands.md` wiki page updated to match.

### Removed - Licensing System

The entire license verification system is gone: the `license` package (`LicenseManager`,
`LicenseCredentialsApi`), the startup credential fetch + verify + license heartbeat, the
"verification failed → disable plugin" kill switch, the shutdown hook, and the `debugLicense`
config/debug plumbing. The plugin now enables unconditionally with no license checks and no
license-related network calls. (The separate opt-in stats heartbeat, `heartbeat.enabled` in
config.yml, is unrelated and remains.) Old `logging.debug.license` config-migration steps are kept
so historical config upgrades still replay cleanly; the key is simply unused.

### Changed - GUI Navigation Hub (cross-menu shortcuts)

The GUIs were internally consistent but isolated - moving between them meant closing and retyping
commands. `/fpp list` is now the hub:

- **`/fpp list` gained two control-row buttons** (permission-gated, hidden without access):
  **＋ ꜱᴘᴀᴡɴ ʙᴏᴛ** (slot 47) spawns one auto-named bot at your location without leaving the GUI -
  the list's live refresh picks the new bot up within a second; **⚙ ɢʟᴏʙᴀʟ ꜱᴇᴛᴛɪɴɢꜱ** (slot 51)
  jumps straight to the plugin-wide settings GUI.
- **Shift-click on ✕ ᴄʟᴏꜱᴇ returns to the bot list** in both the per-bot settings GUI and the
  global settings GUI (plain click still closes; global settings still save either way since
  switching inventories fires the same close-and-save path). Both close buttons' lore now spells
  out the two actions.
- All shortcuts route through `player.performCommand(...)`, so every permission check still applies.

### Fixed - PVE System Actually Works Now (new core engine, pathfinding-linked)

The per-bot "🗡 ᴘᴠᴇ" settings (smart attack, mob selector, range, priority) stored values and fired
a `pve_restart` event that only the **removed extension system** used to consume - nothing in core
ever scanned for mobs or attacked. New core `PveController`:

- **Supervisor + per-bot combat tasks** - a 1 s supervisor attaches a per-entity, every-tick combat
  task to each bot whose smart-attack mode is on (Folia-safe: combat runs at the bot's region), and
  detaches it on disable/despawn/shutdown. GUI toggles apply instantly via a direct refresh hook;
  bots restored from persistence with PVE enabled resume automatically within a second.
- **Targeting** - honors every existing setting: the mob-type selector (empty selection = all
  hostiles via Bukkit's `Enemy` interface, so Slimes/Phantoms/Ghasts count), detect range, and
  target priority (nearest / lowest-health). Sticky targeting with a grace margin prevents target
  flapping at the range boundary. Never targets players or other bots.
- **Combat** - faces the target (body + head), swings, and attacks through the same
  `NmsPlayerSpawner.performAttack` path as `/fpp attack` (real crits/knockback), paced by the same
  per-weapon cooldown table (now shared from `AttackCommand`). The cancellable `FppBotAttackEvent`
  still fires per swing.
- **Pathfinding link** - `ON_MOVE` mode chases the target through the shared pathfinding engine
  (`Owner.ATTACK`): live destination supplier re-paths as the mob moves, navigation cancels on
  arrival-in-reach or target loss, and PVE **never hijacks** a navigation owned by another system
  (mining/moving/deposit) - it keeps attacking whatever wanders into reach instead. The nametag
  activity line already labels this state "ᴄʜᴀꜱɪɴɢ ᴛᴀʀɢᴇᴛ", and `pathfinding` debug logs
  `event=PVE_START` lines.
- Wired into the shared despawn cleanup (`unregisterBotState`) like Move/Click/Find, so a bot
  despawned mid-fight leaves no orphaned task or navigation.

### Fixed - Nametag Activity Line: All Systems Now Report Their State

The live activity row above bots said "ɪᴅʟᴇ" during several real actions because those systems were
never wired into `BotActivity`:

- **PVE combat now reports** - new `PveController.isEngaged()` feeds a "ꜰɪɢʜᴛɪɴɢ" label whenever
  the bot has an acquired target. In-range combat previously showed idle (only the chase phase
  surfaced, via the navigation owner); a hunting bot now shows "ᴄʜᴀꜱɪɴɢ ᴛᴀʀɢᴇᴛ · ꜰɪɢʜᴛɪɴɢ" while
  closing in and "ꜰɪɢʜᴛɪɴɢ" while swinging.
- **Sneaking now reports** - `/fpp sneak` previously had no activity representation at all.
- **Refresh loop hardened + faster** - each bot's refresh is individually guarded so one failure
  can't kill the repeating task (a dead loop silently froze every nametag on its last label
  forever - the likely cause of persistent "ɪᴅʟᴇ"), and the cadence doubled from 1 s to 0.5 s so
  labels react noticeably quicker. Failures log under the `general` debug topic.
- `/fpp list`'s activity lore shares the same engine, so it picks all of this up automatically.

### Added - Live "ᴘᴠᴇ ꜱᴛᴀᴛᴜꜱ" Entry in the PVE Category

First tile of the `🗡 ᴘᴠᴇ` category now shows the engine's live state - `✘ ᴏꜰꜰ` (gray dye) /
`◌ ꜱᴄᴀɴɴɪɴɢ ꜰᴏʀ ᴛᴀʀɢᴇᴛꜱ` (spyglass) / `⚔ ꜰɪɢʜᴛɪɴɢ` (diamond sword) - so it's verifiable at a
glance that the PVE controller picked the bot up after toggling smart attack. Click to re-read.

### Changed - Help GUI: Last Addon Traces Removed

The dead command-extension "ᴀᴅᴅᴏɴꜱ" lore section and its plumbing (`HelpEntry.modifiers`,
the per-command extension query) were stripped - extensions can't load, so the block could never
render.

### Changed - Help GUI: Addon Category Removed, Categories Updated

- **The "ᴀᴅᴅᴏɴꜱ" category tab is gone** - extensions can no longer load, so the tab could never
  contain anything; it also carried a dead legacy-name mapping (chat, personality, rank, swap,
  waypoints, …) for commands that no longer exist. The addon-command feed into the help list and
  the `addon` flag on help entries were removed with it.
- **Category mapping refreshed for the real command set** - `sneak` now files under ᴀᴄᴛɪᴏɴꜱ,
  `check`/`perf`/`delete` under ᴄᴏʀᴇ; removed mappings for long-deleted commands (`follow`,
  `sleep`, `mine`, `place`, `use`). The ᴀᴄᴛɪᴏɴꜱ tab tooltip now describes what's actually there
  (nav, mining, find, combat, storage).
- Four category tabs (ᴀʟʟ · ᴄᴏʀᴇ · ʙᴏᴛꜱ · ᴀᴄᴛɪᴏɴꜱ) now sit in the bottom bar with the freed slot
  falling back to filler glass.

### Changed - Full Wiki Refresh + Dead-Code Follow-Up

**Wiki brought in line with the current plugin:**
- **Deleted `frontend/wiki/fpp-spoof/`** (19 pages) - documented the removed spoof extension and
  its sub-extensions (chat, skins, LuckPerms, personality, swap, peaks, ping, AI chat).
- **`Extensions.md` rewritten** from an 890-line developer guide for the removed loader into a
  short page explaining what was removed and where each former extension feature lives now
  (pathfinding/PVE/skins/tool-equip → core; chat/LuckPerms/spoofing → gone).
- **`Home.md` rewritten** - v2.0.0 highlights now describe the actual feature set (pathfinding
  engine, PVE, find automation, skin pools, fb07 UUIDs, live nametags, GUI suite, player
  invisibility, perf tooling); marketplace/license/extension marketing removed.
- **`Permissions.md` rewritten** against the real `Perm` node set - removed `fpp.spawn.multiple`/
  `mass`, added `fpp.find`/`fpp.storage`/`fpp.perf`/`fpp.move.to`/`fpp.move.coords`, corrected the
  spawn/movement descriptions.
- **`FAQ.md` rewritten** - dropped all license/extension answers; added current answers for PVE,
  pathfinding, skins, UUIDs, activity nametags, and `/fpp find`.
- **`Configuration.md`** - old `skin.mode/pool/overrides/mineskin.*` section replaced with the real
  `skin.rare-pools` / `skin.mineskin-api-key` keys + pool-folder docs; the "Extension-Only
  Settings" section (13 nonexistent extensions) deleted.
- **`Getting-Started.md`** - data-folder listing corrected (`skins/` added, `extensions/` removed),
  first-steps move example updated to `--to`/`--coords`.
- **`Commands.md` / `Database.md` / `Config-Sync.md` / `Placeholders.md`** - stale extension
  references removed; `fpp_skin_cache` documented as dormant; placeholder docs match the code.

**Dead code removed alongside:**
- `DatabaseManager` skin-cache accessors (`getCachedSkin`, `cacheSkin`, `cleanExpiredSkinCache`,
  `getSkinCacheSize`, `SkinCacheEntry`) and the startup cache-cleanup call - unreachable since the
  SkinManager rewrite; the table itself stays (schema/migration chain untouched, now dormant).
- Placeholders for removed systems: `%fpp_extensions%`, `%fpp_extensions_names%` (always 0/empty),
  `%fpp_chat_<bot>%` (read the deleted chat system).

### Added - Bots Can No Longer Earn Advancements

New `BotAdvancementBlocker` listener cancels Paper's `PlayerAdvancementCriterionGrantEvent`
(at `LOWEST` priority) for every bot, blocking advancement progress at the source - no completion,
no toast, no chat announcement, and nothing accumulating in the bot's
`world/advancements/<uuid>.json`. Real players are unaffected.

## v1.6.6.12.8 (Performance Optimization)

### Performance Optimizations
- **Rotation broadcast cache** - `lastSentVisualRotation` map caches last broadcast yaw/pitch per bot; rotation packets dropped when delta < 0.5°, eliminating redundant head-rotation broadcasts under heavy load.
- **Direct NMS fast-paths** - `sendRotationDirect()` and `sendPositionSync()` call `PacketSendListener` directly on the NMS `ServerGamePacketListenerImpl` instead of Bukkit's `sendPacket(Player)` wrapper.
- **Tab-list batching** - `broadcastTabListRemove()` sends a single `ClientboundPlayerInfoRemovePacket` per bot instead of N individual packet sends. Applied to all despawn paths.
- **Frozen bot early return** - Skips all per-bot work (AI, physics, handlers, fall damage, position sync) for frozen bots at the top of the tick lambda.
- **Location reuse** - `before` Location captured once per tick and reused across head-AI target distance, mining-lock check, gaze vector, fall-damage delta, and position-sync threshold - eliminates redundant `bot.getLocation()` calls.
- **Throttled subsystems** - Auto-eat runs every ~4 ticks per bot. Fall damage runs every other tick (accumulated fall distance is still tracked via NMS `getFallDistance()`).
- **Active-bot UUID snapshot** - `activeBotUuids` set built once per tick for O(1) `contains()` in head-AI filtering and tab-list remove.
- **Mining-lock optimization** - Reuses `before` Location for `distanceSquared` check instead of calling `bot.getLocation()` again.

### Bugfixes
- **Position sync dependency on Head AI** - `onlineSnapshot` and player-position arrays now always populated regardless of `doHeadAi`. Previously, when Head AI was disabled, position sync packets were never sent - bots appeared frozen on other clients.
- **Swim AI jumping-field reset** - Removed incorrect `&& (isNavigating || isInWaterOrBubbleColumn(bot))` guard that skipped `tickSwimAi()`. The `jumping` field stuck at `true` after a bot exited water because `setJumping(bot, false)` was never called.
- **Ground detection for partial blocks** - `isBotOnGround()` restored to `loc.clone().subtract(0, 0.08, 0).getBlock().isPassable()`. The `getBlockAt(getBlockX(), getBlockY()-1, ...)` replacement misdetected slabs and stairs.
- **`isInBubbleColumn()` deprecation** - Replaced deprecated `Player.isInBubbleColumn()` with block-type check. `-Xlint:deprecation` added to `compileJava`.

### Performance Subsystem
- **`/fpp perf` command** - `check`/`top`/`report`/`history`/`spark` subcommands. Background monitor samples TPS, MSPT, CPU, GC, memory every `sample-interval-ticks`, keeps rolling `history-minutes`, warns on consecutive threshold breaches.
- **Built-in self-profiler** - `BuiltinFppProfiler` with lock-free `LongAdder` sampling, thread-local call stack, adaptive detail reduction. Enabled via `performance.self-profiler.enabled`.
- **Benchmark sessions** - `/fpp perf report` starts a 10-minute method-level benchmark, reminds every 2 minutes, exports Spark-style call tree to `plugins/FakePlayerPlugin/performance-report/`.
- **Perf providers** - `SparkPerfProvider` (preferred, reads Spark-API snapshots) and `BuiltinPerfProvider` (fallback via CraftServer + JMX).
- **Auto-export** - `PerformanceReportExporter` writes `.txt` reports on: benchmark finish, threshold warning (`export-on-warning: true`), plugin disable, and fatal exceptions.
- **Perf placeholders** - `%fpp_perf_tps%`, `%fpp_perf_mspt%`, `%fpp_perf_cpu_process%`, `%fpp_perf_cpu_system%`, `%fpp_perf_gc_avg_time%`, `%fpp_perf_gc_avg_frequency%`, `%fpp_perf_health%`.
- **Profiling instrumentation** - Hot paths in `FakePlayerManager.tick()`, `NmsPlayerSpawner.tickPhysics()`, `PacketHelper`, `tickSwimAi()`, `tickAutoEat()`, `fireTickHandlers()`, `tickFallDamage()` profiled when self-profiler is active.
- **Language keys** - Full `perf-*` set in `en.yml` for all `/fpp perf` output.

### Config
- **New `performance:` block** - `enabled`, `spark-enabled`, `placeholders`, `sample-interval-ticks`, `history-minutes`, `warn-mspt`, `warn-tps`, `warn-consecutive-samples`, `warn-cooldown-minutes`, `auto-profiler-timeout-seconds`, `self-profiler.enabled`, `self-profiler.method-level`, `self-profiler.export-on-warning`.
- **Swap player-aware settings** - New `swap.player-aware.*` keys for nearby-player detection radius, idle threshold, idle bonus percent, active penalty percent.
- **`ConfigMigrator`** - Updated for config-version 76 to handle new keys.
- **`FppMetrics` integration** - FastStats metrics startup with graceful fallback when FastStats is unavailable.

### API & Internal
- **`FppApi`** - Added `getOnlineCount()`, `removePlayerBody(UUID)` (shutdown-safe), `disableAllAddons()`.
- **`FppScheduler`** - `runAtEntityRepeatingWithId()` returns `int taskId` for per-entity repeating tasks.
- **`CommandManager`** - `/fpp perf` registered with `fpp.perf` permission (child of `fpp.op` in `plugin.yml`).
- **`NmsPlayerSpawner`** - `tickPhysics()` reverted to original `doTickMethod.invoke()` via reflection; guard restored to `|| doTickMethod == null`.
- **`FakePlayerEntityListener`** - Removed exact damage-canceller detector/tracer in favor of simpler `body.damageable` switch.
- **`FakeChannelPipeline`** - Handler insertion refactored for channel-active vs connection-set path.
- **`BotPersistence`** - `saveForShutdown()` saves snapshot without clearing `active-bots` to prevent destructive overwrite on restart.
- **Shutdown lifecycle** - Profiler stopped before monitor; extension class loaders closed; `disableAllAddons()` called; `saveForShutdown()` runs before body removal.

## v1.6.6.12.7 (nLogin Compatibility & Heavy Listener Suppression)

### Core Updates
- **nLogin compatibility** - `NmsPlayerSpawner` now suppresses nLogin (`com.nickuc.*`) `PlayerJoinEvent` listeners for fake players alongside the existing SimpleVoiceChat suppression. Auth plugins that expect normal client login pipelines no longer kick/despawn FPP bots during spawn.

---

## v1.6.6.12.6 (Synthetic Quit Kick Fix)

### Core Updates
- **Synthetic quit kick handling** - `FakePlayerKickListener` now marks kicked bots as synthetic quits via `FakePlayerManager.addSyntheticQuit(UUID)` before despawning. This ensures the manager treats server kicks as synthetic quits and despawns the bot with consistent quit-event semantics instead of treating it as a raw deletion.
- **`addSyntheticQuit(UUID)` helper** - added null-checked `addSyntheticQuit(UUID)` to `FakePlayerManager` so callers can safely record synthetic quit UUIDs without duplicating null-guard logic.

---

## v1.6.6.12.5 (Core Scope Reduction & Click API)

### Core Updates
- **Version bump** - plugin metadata and Gradle version are now `1.6.6.12.5`.
- **Core scope reduction** - advanced pathfinding movement, follow, sleep, and rich combat behavior moved out of core and into extension-owned systems.
- **Directional move only** - core `/fpp move` now accepts `--direction forward|backward|left|right` with optional `--seconds` / `--ticks` and `--stop`.
- **Basic attack only** - core `/fpp attack` reduced to `--once` / `--stop`. Rich hunting/mob targeting is extension-owned.
- **Sneak command** - added core `/fpp sneak <bot> [on|off|toggle]` with `fpp.sneak` permission.
- **Click API** - added public `FppClickMode` and `FppApi.leftClick/rightClick` overloads so extensions can trigger core click actions without dispatching commands.
- **Tab completion hardening** - `CommandManager` now guards tab completion against exceptions from `canUse()` and `tabComplete()` in both core and addon commands.
- **Spawn location correctness** - normal `/fpp spawn` reasserts requested spawn location after join/spawn redirects; early join handling consumes pending locations at LOWEST priority.
- **Shutdown persistence** - non-destructive shutdown save: empty snapshots do not clear `persistence.active-bots`; addon shutdown runs before final bot persistence save.
- **Inventory persistence fix** - empty inventories saved with `__empty` marker; restore clears slots before applying saved data so old items do not survive.
- **Damage/knockback** - preserved Paper/Bukkit damage event semantics; explicit FPP knockback restored for allowed damage; suppressed for cancelled events; cross-world teleports reset transient damage state.
- **Removed broad core protection gates** - WorldGuard helper removed from core; external protection plugins own cancellation decisions.
- **Removed core commands** - `FollowCommand` and `SleepCommand` removed from core; follow/sleep are extension-owned.
- **Build output** - `shadowJar` copies the shaded runnable jar to workspace root as `fake-player-plugin-1.6.6.12.5.jar`; plain `jar` refreshes `build/fpp.jar` without overwriting the deployable root jar.
- **FastStats packaging** - `shadowJar` verifies FastStats classes are present; metrics initialization is fail-safe so a thin jar cannot break startup.
- **Config** - `config-version` is `74`; reorganized and heavily documented; debug settings moved to `debug.yml`.

### First-Party Extensions
- **Gradle extension build** - `fpp-extensions` is a Gradle multi-project that builds individual module jars into workspace `builds/`.
- **Bundle rename** - aggregate first-party bundle is now `fpp-spoof.jar` instead of `fpp-extensions-bundle.jar`.
- **Current modules** - `fpp-aichat`, `fpp-chat`, `fpp-command`, `fpp-groups`, `fpp-list`, `fpp-luckperms`, `fpp-nametag`, `fpp-pathfinder`, `fpp-peaks`, `fpp-ping`, `fpp-skin`, `fpp-swap`, and `fpp-waypoints`.

---

## v1.6.6.12.4 (Debug GUI, Left-Click Combat & Stability)

### 🎯 Main Focus
- **Fix bot despawn after spawn bug** - bots no longer instantly despawn due to stale spawn-protection checks or missing WorldGuard session state after teleport/respawn
- **PacketEvents fail injection** - suppressed kicks caused by `"packetevents"` + `"inject"` errors that triggered an infinite despawn loop on every bot join
- **LuckPerms patch** - pre-caches LuckPerms user data before `placeNewPlayer()` to prevent `ServerThreadLookupException` on Folia and ensure Vault/WG hooks resolve correctly at spawn time

### 🐛 Debug GUI & Chat Broadcasting
- **Debug Settings GUI** - `/fpp settings` now has a **🐛 ᴅᴇʙᴜɢ** category with 23 clickable toggles for every `debug.yml` category (master, general, startup, NMS, database, packets, network, config-sync, chat, swap, commands, head-ai, right-click, etc.)
- **Debug Chat Broadcast** - new `debug-chat: false` key in `debug.yml`. When enabled, all `FppLogger.debug()` output is sent to online players with `fpp.op` or `fpp.notify` as in-game chat messages (gray prefix: `[ꜰᴘᴘ DEBUG/<topic>] <message>`)
- **Runtime debug toggling** - debug categories can be flipped on/off without restarting via the GUI; changes are saved to `debug.yml` immediately

### 🖱️ Left-Click Command Improvements
- **Auto-target hostile mobs** - bots now automatically detect and attack hostile mobs (Monsters, Slimes, Ghasts, Phantoms, Hoglins, Shulkers, EnderDragon) in their forward cone when no block is targeted
- **Auto-aiming** - bot head and body smoothly rotate to face the targeted mob
- **Multi-flag parsing** - fixed `--once`, `--repeat`, `--hold`, and `--stop` flag handling so multiple flags can be specified correctly in a single command

### 🔧 Bug Fixes & Stability
- **LuckPerms cache warmup** - `NmsPlayerSpawner` pre-loads LuckPerms user data before `placeNewPlayer()` to prevent `ServerThreadLookupException` on Folia
- **WorldGuard session refresh** - complete rewrite using cold re-initialization via reflection (`tryRemoveSession` + `Session.initialize()`) to prevent stale region data after bot teleports/world changes
- **Teleport/respawn WG refresh** - `FakePlayerEntityListener` adds `PlayerTeleportEvent.MONITOR` and `PlayerRespawnEvent` handlers with delayed (1-2 tick) WG session refresh
- **Spawn protection teleport fix** - `BotSpawnProtectionListener` now allows `PLUGIN` and `COMMAND` teleports during the grace window so `/fpp tph` and cross-world moves work correctly; portals are still blocked
- **Despawn reason tracking** - all `removeBot()` calls now pass descriptive reasons (`spawn_body_failed`, `command_despawn`, `gui_delete`, `badword_cleanup`, `packetevents_kick`, `kicked_by_server`, `api_despawn`, `rename_swap`, `body_remove`, etc.) instead of `"unspecified"`
- **PacketEvents kick suppression** - `FakePlayerKickListener` silently cancels kicks containing `"packetevents"` + `"inject"` instead of despawning the bot, preventing instant-despawn loops
- **Attribution/logging cleanup** - silenced license heartbeat, JSON response, and integrity check logs unless explicitly enabled via `debug.yml`
- **Placeholder formatting** - cleaned up `formatUptime` one-liner in `FppPlaceholderExpansion`
- **Help GUI formatting** - fixed indentation in lore builder

---

## v1.6.6.12.3

### 🔧 Folia Config Patch
- **Folia config issue patched** - formatting normalization across `build.gradle.kts`, `Config.java`, and `plugin.yml` to resolve Folia-related configuration loading problems

---

## v1.6.6.12.2

### ⚡ Performance & Cleanup
- **Silent License Verification** - No more startup spam (Team ID, challenge, JSON response removed)
- **Debug Logging Fixed** - All NMS-BOT messages now respect `debug.yml` (17 calls fixed)
- **Cleaner Startup Logs** - Removed backups count, name pool size, debug section from banner
- **Minimal Shutdown Log** - Reduced from 7 lines to 4 lines

### 🖱️ Click Commands
- **Left-Click Command** - Replaced MineCommand (`/fpp left-click`)
- **Right-Click Command** - Replaced UseCommand (`/fpp right-click`)
- **Legacy Removed** - 2162 lines of mine/use/place code deleted
- **Net Reduction** - ~500 lines of code removed overall

### 🔧 Config System
- **debug.yml** - All debug settings moved to separate file
- **Config v75** - Auto-migrates and removes `logging.debug.*` keys from config.yml
- **License Category Removed** - No longer needed (silent verification)

### 📦 Other Changes
- **Folia Support** - Full compatibility with region-threaded spawning
- **Permission Checks** - Bot ownership validation for `/fpp attack --all`, `/fpp follow --all`
- **New Flags** - `/fpp despawn --own`, `/fpp delete --own`
- **PlaceholderAPI** - Updated to 2.12.2

### 📝 Documentation
- Updated: Changelog, Configuration, FAQ, Getting-Started, Home
- AGENTS.md added for development reference

---

## v1.6.6.12.1

### License System Updates
- **License server migration** - Switched license verification from `license.fpp.wtf` to `app.lukittu.com`
- **Frontend credential fetch** - Credentials now fetched from `fpp.wtf/api/license/free` with HMAC signature verification
- **Improved license logging** - Better error messages and debug logging for license verification failures
- **API key authentication** - Added Bearer token authentication for frontend API requests

### Bug Fixes
- **License credentials fetch** - Fixed API key encoding for frontend authentication

---

## v1.6.6.12

### Breaking Changes
- **Folia support restored** - FPP now fully supports Folia with region-threaded bot spawning
- **Body disable system removed** - `body.enabled` config option removed. Bots always spawn with physical bodies (tab-list only mode no longer available).
- **SpigotMC distribution removed** - Plugin no longer distributed on SpigotMC. Download from Modrinth, PaperMC Hangar, or BuiltByBit.

### Features Removed
- **`%fpp_body%` placeholder** - Removed along with body disable system.
- **Body toggle in GUI** - Removed from Settings GUI (body category).
- **Skin system toggle** - Removed from Settings GUI.

### New Features & Improvements
- **Pathfinding overhaul** - Major improvements to `BotPathfinder.java` and `PathfindingService.java` with better A* navigation, gap walking, block break/place support, and stuck detection.
- **Mine command improvements** - Added actual block breaking via `nms.gameMode.destroyBlock()`, improved progress tracking, and pickup flow.
- **Use command enhancements** - Combined Use+Place functionality with `UseMode` enum, flexible targeting from bot look direction, and better ray-tracing.
- **Head AI action locking** - Added `actingBots` concurrent set to fully disable head AI while bots perform actions (mining, using, placing).

### Bug Fixes
- **PacketEvents injection error** - Added try-catch wrapper around PacketEvents registration to prevent GrimAC/ViaVersion compatibility issues from breaking bot spawns.
- **UseCommand NPE** - Fixed null pointer when storing ray-trace targets; only stores non-null targets.
- **Head AI during actions** - Bots now properly disable head rotation while performing mine/use/place actions.
- **Mining not breaking blocks** - MineCommand now actually breaks blocks via NMS game mode.

### Code Quality
- Removed `spawnBody()` config method and all references to body disable logic
- Cleaned up `FakePlayerManager.java` spawn logic (no more bodyless mode)
- Updated startup banner, metrics, and placeholders to remove body enable references
- Removed unused custom metrics from `FppMetrics.java`
- Removed outdated `AGENTS.md` file
- Added `note.md` development tracking document

### Documentation
- Updated all wiki pages to reflect Paper/Purpur/Folia support
- Updated FAQ to explicitly state Folia is supported
- Updated legal documents (copyright, privacy-policy, extensions, terms-of-service)
- Updated README.md with platform changes

---

## v1.6.6.11

### Bug Fixes
- **Online player count** - bots now correctly subtracted from real-player count in `/fpp stats` and network totals (commit `6afca8a`)
- **Database flush** - runs outside the main thread to prevent server lag spikes (`f671781`)
- **Batching logic** - added proper batching for DB writes and network heartbeats (`528cf0e`)
- Removed dead writer/health-check logic that caused unnecessary DB overhead (`fcbe072`)
- Removed pointless bot record update before clearing the list on shutdown (`8c1eb56`)

### Code Quality
- Removed unnecessarily fully qualified class names across codebase (`001416d`)
- General cleanup of dead code, unused fields, and redundant calls (`14d1803`)

### Documentation
- Updated command reference with `extension --list`, `spawn --notp`, and `attack --once` flags
- Synced config docs with `pathfinding.*`, `skin.*`, `help.*`, `ping.*`, `metrics.debug`, and `heartbeat.enabled`

---

## v1.6.6.10.1

### Attribution & Author Updates
- Hardcoded original author updated from `el_pepes` to `F_PP` across codebase

### FastStats Metrics System Overhaul
- **ErrorTracker** - context-aware error tracking via FastStats API
- **Debug toggle** - `metrics.debug` option in `config.yml` (default `false`)
- **onFlush callback** - logs at debug level when metrics are flushed to FastStats
- **New metrics added**: `active_features` (string array), feature flags, installed plugins (LuckPerms, PlaceholderAPI, WorldGuard, WorldEdit, NameTag), server info, PvE settings, automation toggles
- **trackError() helpers** - two public overloads (`Throwable` and `String`) for external error reporting
- Added `getFppMetrics()` public getter on `FakePlayerPlugin.java`

### Bug Fixes
- **FakeChannelPipeline deprecation warning** - added `@SuppressWarnings("deprecation")` to suppress unavoidable Netty `ChannelPipeline` API deprecation warnings for `EventExecutorGroup` overloads
- **PluginRemapper duplicate entries** - `pom.xml` now properly excludes Mojang-mapped `paper-server` NMS classes from shaded JAR, fixing Paper 1.21.11 runtime remapping crash
- **SQLite AUTO_INCREMENT syntax** - split `fpp_network_tasks` table creation into SQLite (`INTEGER PRIMARY KEY AUTOINCREMENT`) and MySQL (`BIGINT AUTO_INCREMENT`) variants, fixing `SQLITE_ERROR near "AUTO_INCREMENT": syntax error`

### Documentation
- Full wiki sync: added missing `pathfinding.*`, `skin.*`, `help.*`, `ping.*`, `metrics.debug`, `heartbeat.enabled`, and `body.drop-items-on-despawn` config keys
- Added missing commands (`extension`, `extension --list`) and flags (`spawn --notp`, `spawn <bottype>`, `attack --once`, `find --prefer-visible`, short flags `-r`/`-c`)
- Added missing permissions (`fpp.mine.wesel`, `fpp.place.wesel`)
- Added extension-dependency notes for placeholders (`peak_hours`, `swap`, etc.) and config keys (`fake-chat`, `swap`, `peak-hours`)

### Deprecations & Removals
- None

---

## v1.6.6.10

**Requires MySQL for cross-server features.**

### Network Architecture  
**Proxy-merged database** - all backends share live bot registry and player counts via MySQL.
- Schema v25: `fpp_network_bots`, `fpp_server_heartbeat`, `fpp_network_tasks`
- **NetworkHeartbeatManager** - publishes local bots / reads remote bots every 5s, stale pruning every 60s
- Proxy companions (Velocity + Bungee) push `NETWORK_STATS` to all backends independently of players
- `RemoteBotCache` now survives restarts via DB (no longer messaging-only)

### PlaceholderAPI - 70+ placeholders  
New cross-server placeholders: `%fpp_network_total%`, `%fpp_network_real%`, `%fpp_network_bots%`  
Also added: server performance, extensions, 30+ config toggles, player-relative per-world, per-bot dynamic lookups.

### Extension System  
- `/fpp extension` bare command → marketplace link  
- `/fpp extension --list` → loaded extensions detail table  
- Extension data folders fixed (`getName()` instead of JAR filename)

### Deprecations & Fixes  
- `getServers()` → `getServersCopy()`, `FixedMetadataValue` → `PersistentDataContainer`, unchecked warnings cleaned
- Startup banner shows extension count  
- Authors updated to `F_PP`

### Legal  
Added `frontend/legal/` pages (copyright, extension policy, privacy, ToS)

---

## v1.6.6.9
- Fall damage tracking + config
- Skin injector fixes
- Config migrator v71→v72
- Extension bundles, API additions
- Wiki marketplace links

## v1.6.6.8
- Spoofing/chat-related features moved out of core into first-party extensions (chat, AI, swap, peak-hours, ping, groups, stored cmds)
- PvE Smart Attack Mode (OFF / ON_NO_MOVE / ON_MOVE)
- `/fpp save`, `/fpp setowner`
- Per-bot overrides: respawn-on-death, auto-eat, auto-place-bed
- BotSettingGui PvE + Pathfinding tabs, share control
- DB schema v22: PvE, automation, ping, LuckPerms

## v1.6.6.6
- Folia scheduling guards
- Water-path stability fixes
- Spawn grace-period protection

## v1.6.6.2
- BungeeCord companion plugin support
- `AttributeCompat` fix

## v1.6.6
- `/fpp follow`
- Skin persistence
- Server-list config additions
- DB schema v17

## v1.6.5
- `/fpp ping`
- `/fpp attack`
- Permission restructure
- Skin mode rename
- `FlagParser` utility

## Older Versions
https://github.com/Pepe-tf/Fake-Player-plugin-2.0/commits/master

---

> **Note:** The built-in ConfigMigrator handles upgrades transparently. Current default config version: **74**. Always back up `plugins/FakePlayerPlugin/` before major updates.

---

## Migration Notes (v1.6.6.12.4)

### New `debug-chat` Key
If you are upgrading from an older version, `debug.yml` will be recreated from the template. The new `debug-chat: false` key controls whether debug output is broadcast to OP/notify players in-game. You can also toggle it via `/fpp settings` → **🐛 ᴅᴇʙᴜɢ**.

### `debug.yml` Runtime Editing
Prior to v1.6.6.12.4, `debug.yml` could only be edited by hand. The Settings GUI now lists every debug category as a clickable toggle. Changes are saved to disk immediately.

---

## Migration Notes (v1.6.6.12)

### From Folia to Paper/Purpur (or vice versa)
FPP now supports both Paper/Purpur and Folia. If you were running FPP on Folia:
1. FPP will work out of the box on both platforms
2. Bot spawning automatically detects Folia and uses region scheduler
3. No migration needed - FPP handles both seamlessly

### Body Disable System Removed
If you were using `body.enabled: false` for tab-list only mode:
- This option has been removed
- All bots now spawn with physical bodies
- Consider using `body.damageable: false` and `body.pushable: false` for invulnerable/immobile bots
