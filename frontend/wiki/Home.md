# FakePlayerPlugin Wiki

> Advanced NPC / Bot Plugin for Paper/Purpur/Folia 1.21+

Welcome to the FakePlayerPlugin (FPP) wiki. FPP spawns server-side bot entities that behave like
players - useful for **AFK farms, automated tasks, testing, and NPC simulations**. It is **not** a
fake-online-count or player-spoofing tool: bots are hidden from the tab list by default (listed only
as `[Bot] - name` if an admin enables it), are never counted as players, always wear a mandatory
"ʙᴏᴛ ʙʏ {owner}" nametag, and can never take a real account's identity.

---

## Getting Started

| Page | Description |
|------|-------------|
| [Getting Started](Getting-Started) | Installation, first setup, and quick start |
| [Commands](Commands) | Full command reference with examples |
| [Permissions](Permissions) | Permission nodes and setup guide |
| [Configuration](Configuration) | config.yml reference and tuning |

## Systems

| Page | Description |
|------|-------------|
| [Economy](Economy) | Bot rental - pay real currency for a bot and hours; Vault / ExcellentEconomy / custom shop plugins |
| [Placeholders](Placeholders) | PlaceholderAPI integration reference |
| [Database](Database) | SQLite / MySQL setup, network tables, and proxy-merged architecture |
| [Proxy Support](Proxy-Support) | Velocity / BungeeCord multi-server networks with shared MySQL |
| [Config Sync](Config-Sync) | Synchronize configs across proxy backends |

## Reference

| Page | Description |
|------|-------------|
| [FAQ](FAQ) | Common questions and troubleshooting |
| [Changelog](Changelog) | Version history and release notes |

---

## Quick Links

- **Source:** https://github.com/Pepe-tf/Fake-Player-plugin-2.0
- **Discord:** https://discord.gg/Q9cd9frzRt
- **Modrinth:** https://modrinth.com/plugin/fake-player-plugin-fpp
- **License:** MIT

---

## Latest Version: v2.1.6

**Highlights:**
- 🖱️ **Client-authentic actions** - left/right-click are a port of the 26.3 client's own click logic: real
  crosshair pick, attack-charge gating, the dig state machine, main/off-hand use order
- 🗡️ **`/fpp attack`** - one-line guard / hunter setup with real client attacks, best-weapon pick and
  return-to-post for AFK mob farms
- ⛏️ **Legit `/fpp find`** - only blocks with an exposed face are detected (no X-ray); follows veins
- 🧱 **`/fpp clear`** - self-running layer-by-layer excavation of a chunk/area; seals fluids, deposits or
  discards loot, recovers when stuck and builds up
- 🧭 **New pathfinder** - tick-costed A* over real collision shapes: climb, swim, doors, parkour,
  tool-aware digging, bridging and pillar-up; time-sliced and Folia-safe
- 🗺️ **Client-authentic chunk loading** - no plugin tickets; bots report a render distance like a client
- 🚀 **Spawn actions** - `/fpp spawn --name Guard attack --chase`, `--count`, `--preset`, `then`/`wait`
- 💰 **Rent GUI & shop integration** - built-in rental shop, packages, `/fpp rent package <id>`,
  placeholders and a veto/re-price event; auto-renew, discounts, limits
- 🧭 **`/fpp menu`** - main-menu hub; every menu reworked with centred cards and back navigation; the creator head with version and build ID sits at the bottom-left of every menu

---

## Previous Version: v2.1.0 (Part 1)

**Highlights:**
- 🛡️ **Network Protocol Error fix** - a per-viewer packet guard keeps scoreboard teams consistent
- 🏷️ **Honest bots** - tab list off by default (`[Bot] - name` when on), no fake ping, real-player-only counts
- 🪪 **Three-row name tag** with vanilla-style plates
- 🚤 **Auto mount** - boats/minecarts pick bots up; bots re-spawn in their vehicle like a returning player
- ⬆️ **Auto update** from Modrinth on restart (SHA-512 verified)

---

## Previous Version: v2.0.6.1

**Highlights:**
- 📍 **`/fpp spawn --location`** - admins can spawn a bot at any `<x> <y> <z> <world>` instead of only
  their own location, and can now do it from the console or a command block too (`--location` required)
- ✅ **Minecraft 26.3 Support** - the year-based `26.1.x`–`26.3.x` releases are now accepted
- 🏷️ **Vanilla-name fix** - the hidden vanilla over-head name no longer shows through the custom nametag
- 🛠️ **Smarter Left/Right-Click** - `left-click` auto-equips the best available tool before mining,
  and `right-click` now falls back to the off-hand item when the main hand does nothing, matching a
  real client's own hand-loop
- 🔑 **Bot Auth System** - bots auto-register/login against an installed login plugin (nLogin, AuthMe,
  LoginSecurity, and friends), remembering an encrypted per-bot password so only the first join ever
  registers; manage it via `/fpp auth` or the settings GUI's new **🔐 ᴀᴜᴛʜ** category
- 🎨 **"Bot Console" Color Theme** - every message, GUI, and console log now uses one consistent
  violet/lime palette instead of scattered ad-hoc colors
- 💰 **Bot Rental Economy** - pay real currency (Vault, "Vault2.0", or ExcellentEconomy) for a bot and
  hours of runtime via `/fpp rent buy`/`extend`; `/fpp rent give` is a zero-economy-required entry
  point for wiring up your own shop plugin instead - see [Economy](Economy)
- 🧵 **Multitasking** - `move`, `find`, `left-click`, `right-click`, `attack`, and PVE auto-combat can
  all run at once on the same bot instead of one cancelling the others; movement (the one true
  single-body limit) is arbitrated by priority instead of by hijacking
- ⏱️ **Configurable click pacing** - `/fpp left-click`/`/fpp right-click` `--repeat`/`--hold` interval
  is a server-wide default (`config.yml`) with a per-bot override in the settings GUI
- 🔌 **ViaVersion-Aware** - bots register with ViaVersion (if installed) as running the server's own
  native protocol version, so it never treats them as an unrecognized connection
- 🧭 **Real Pathfinding Engine** - in-house tick-costed A* with climbing, swimming, doors, parkour,
  tool-aware block breaking, bridging and pillar-up, partial routes, stuck recovery and a
  Baritone-style particle debug view (per bot or globally)
- 🗡️ **PVE Combat** - `/fpp attack` guard/hunter mode with mob-type selection, range, priority, best-weapon
  pick, real attack-charge pacing and pathfinding-linked chasing
- ⛏️ **`/fpp find` / `/fpp clear` Automation** - visible-only block search and layer-by-layer
  excavation with tool auto-equip, anti-stuck recovery and inventory-aware deposits into storages
- 🎯 **Precise Clicking** - `/fpp left-click` / `/fpp right-click` aim at the exact point you're
  looking at, walk to a reachable vantage (preferring your standing spot) when out of reach, and
  only trigger a button/lever when actually aimed at its hit box
- 🍗 **Auto-Eat** - per bot: eats from its inventory when hungry (off-hand → hotbar → inventory),
  configurable food list + hunger threshold; off-hand eating runs fully in parallel with everything
  else, main-hand eating still pauses briefly and switches back to the held item afterward
- 🎨 **Rarity Skin Pools** - bots roll their skin from configurable rarity tiers
  (`skins/1-<N>%.txt`), signed once via MineSkin and cached forever; slim/classic model
  auto-detection
- 🆔 **Readable Bot UUIDs** - deterministic `fb07…` UUIDs with the bot number embedded
  (`bot2` → `fb070000-…-000000000002`); zero collision risk with real accounts
- 🏷️ **Live Nametags** - three-row mannequin-style tag with a real-time activity line
  (idle / moving / mining / fighting / eating / searching / sneaking …)
- 🖥️ **GUI Suite** - bot list hub (search, sort, live refresh, spawn & settings shortcuts),
  per-bot settings (general, PVE, pathfinding, skin, auto-eat, rename, danger zone), global settings
  with runtime debug toggles, and a categorized help GUI
- 🫥 **Invisible to Players** - no tab-list entry by default (`[Bot] - name` when enabled), no
  server-list ping count/sample entry, no advancements, no join/leave/death chat noise
- 🚤 **Auto Mount** - boats and minecarts pick bots up like villagers; a bot that logs out in a
  vehicle returns to it, like a player
- ✅ **Folia Support** - region-thread-safe ticking, nametag rendering, and Head-AI on Folia's
  regionised architecture
- 🔐 **LuckPerms-Faithful Permissions** - no hard-coded operator bypasses anywhere; every `fpp.*`
  node (including for server operators) is decided purely by your permissions plugin
- 📊 **Performance Tooling** - `/fpp perf` dashboard, history, benchmark reports, and Spark
  integration; hot per-tick NMS paths call directly instead of through reflection
