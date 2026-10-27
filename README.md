<div align="center">

<img src="https://janky.fpp.wtf/assets/plugins/fpp-2-0-banner-2000x400.png" alt="FakePlayerPlugin 2.0" width="100%" />

# FakePlayerPlugin 2.0

### Server-side bots that *actually* behave like players

**AFK farms · mining · mob grinders · automation · bot rentals**

[![Version](https://img.shields.io/badge/version-2.1.6-A78BFA?style=for-the-badge)](https://modrinth.com/plugin/fake-player-plugin-fpp)
[![Paper](https://img.shields.io/badge/Paper%20·%20Purpur%20·%20Folia-1.21+-BAFF4F?style=for-the-badge&labelColor=1a1626)](https://modrinth.com/plugin/fake-player-plugin-fpp)
[![Java](https://img.shields.io/badge/Java-21+-A78BFA?style=for-the-badge&labelColor=1a1626)](#-requirements)
[![License](https://img.shields.io/badge/license-MIT-BAFF4F?style=for-the-badge&labelColor=1a1626)](https://github.com/Pepe-tf/Fake-Player-plugin-2.0)

[**Download**](https://modrinth.com/plugin/fake-player-plugin-fpp) &nbsp;•&nbsp;
[**Source**](https://github.com/Pepe-tf/Fake-Player-plugin-2.0) &nbsp;•&nbsp;
[**Wiki**](https://janky.fpp.wtf/wiki/fpp-2-0) &nbsp;•&nbsp;
[**Discord**](https://discord.gg/Q9cd9frzRt)

</div>

---

> [!TIP]
> **You log off. Your farms stop. Your mines go quiet. Not anymore.**
> FPP 2.0 spawns real server-side bots that keep your farms, mines and grinders running while you're away.
> Free, open source, no license key, no paywall.

FPP spawns server-side bot entities for AFK farms, automated tasks, testing and NPC simulations, with real A\* pathfinding,
PvE combat, precise clicking and a full GUI suite. It is deliberately **not** a fake-online-count tool: no tab list,
no server-list entry, no player spoofing, ever.

<div align="center">

| 🧩 **19** systems | ⌨️ **25** commands | 📜 **MIT** open source | 🌿 **1.21+** Folia ready |
|:---:|:---:|:---:|:---:|

</div>

---

## ⚔️ PvE combat for AFK mob farms

<img src="https://janky.fpp.wtf/media/fpp-2-0/pve-mob-farms.webp" alt="A bot guarding a mob farm" width="100%" />

Guard a post and hit every mob that comes into reach, with real client combat.

- 🎯 Mob-type selection, detect range and target priority
- ⏱️ Real attack-charge pacing, best weapon, line of sight
- 🛡️ `--stand` holds a post for farms, `--chase` hunts and returns to its post

```
/fpp attack Guard --stand --mobs zombie,husk
```

## ⛏️ Find & mine, no X-ray

<img src="https://janky.fpp.wtf/media/fpp-2-0/find-and-mine.webp" alt="A bot mining a vein of ore" width="100%" />

Search, path to and mine ores, logs or any block list, ranked by walking effort. **Only blocks the bot could actually
see get targeted.**

- 🔧 Tool auto-equip and real client dig logic
- 🎒 Inventory-aware: deposits at storage when full
- 🚧 Anti-stuck watchdogs

```
/fpp find Miner ores --radius 16
```

## 🧱 Clear areas layer by layer

<img src="https://janky.fpp.wtf/media/fpp-2-0/clear-areas.webp" alt="A bot clearing a pit layer by layer" width="100%" />

Clear a chunk, an area, a selection or a radius. The bot digs down from inside, seals water and lava, collects the drops,
deposits them at storage and builds itself up when it gets stuck.

## 🖱️ Click automation & multitasking

<img src="https://janky.fpp.wtf/media/fpp-2-0/click-automation.webp" alt="Bots mining and eating at the same time" width="100%" />

Spawn bots with their actions already running.

- 🎯 Left or right-click at the **exact aim point**, only on what is under the hit box
- 🔁 Hold, or repeat on your own pacing
- 🍖 Auto-eat from the off-hand, in parallel with mining, moving and combat
- 🧠 Move, find, click and attack all at once, arbitrated by priority instead of cancelling each other

```
/fpp spawn --name Cobble left-click --hold
```

## 💰 Bot rental economy

<img src="https://janky.fpp.wtf/media/fpp-2-0/bot-rental.webp" alt="The bot rental shop GUI" width="100%" />

Let players pay real currency for a bot and hours of runtime.

| | |
|---|---|
| **Economy** | Vault, Vault2.0 or ExcellentEconomy |
| **Billing** | Per hour, tracked live, extendable mid-rental |
| **Packages** | Bulk tiers, rank discounts, auto-renew, built-in shop GUI |
| **Expiry** | Auto-despawns the instant paid time runs out, inventory saved |
| **No economy?** | `/fpp rent give` needs no economy plugin at all |
| **Default** | Off. Opt in only if you want it |

## 🏷️ Live nametags & rarity skins

<img src="https://janky.fpp.wtf/media/fpp-2-0/nametags-skins.webp" alt="A three-row nametag and a legendary skin roll" width="100%" />

- 🪪 A three-row tag: **live activity** (idle, moving, mining, fighting, eating), **custom name** and the mandatory
  **owner tag**
- 🎲 Skins roll from configurable **rarity pools**, up to a 1 in 100,000 legendary roll
- 💾 Signed once via MineSkin and cached forever

---

## 🧰 And everything else

<img src="https://janky.fpp.wtf/media/fpp-2-0/more-systems.webp" alt="Every other system" width="100%" />

| | System | What it does |
|:---:|---|---|
| 🧭 | **A\* Pathfinding** | Parkour, bridging and block-breaking, every path verified before the first step |
| 🔀 | **Multitasking** | Movement arbitrated by priority, not by cancelling other tasks |
| 🍎 | **Auto-Eat** | Off-hand eating that never interrupts work |
| 🖥️ | **GUI Suite** | Bot list hub, per-bot settings, global settings with debug toggles, help menu |
| 👻 | **Invisible to Players** | No tab-list entry, no server-list count, no join / leave / death noise |
| 💽 | **Persistence** | Bots, inventories, XP, rentals and tasks survive restarts. SQLite or MySQL |
| 🌐 | **Proxy Networks** | Velocity and BungeeCord with shared MySQL and cross-server placeholders |
| 🔄 | **Config Sync** | Identical configuration across every backend on a proxy network |
| 🌿 | **Folia Support** | Ticking, nametags and pathfinding are all region-thread safe |
| 🔑 | **LuckPerms-Faithful** | No hard-coded operator bypasses; your permissions plugin decides |
| 🧬 | **ViaVersion-Aware** | Bots register the native protocol, so ViaVersion never flags them |
| 🆔 | **Readable Bot UUIDs** | Deterministic UUIDs with the bot number embedded, zero collision risk with real accounts |
| 📊 | **Performance Tooling** | `/fpp perf` dashboard, history, benchmark reports and Spark integration |
| 🏷️ | **PlaceholderAPI** | Placeholders for bots, owners and rentals |

---

## ⌨️ Commands

Everything runs off one root command: **`/fpp`** (aliases `/fakeplayer`, `/fp`).

<details>
<summary><b>Show the command reference</b></summary>

| Command | Usage | Permission | Description |
|---|---|---|---|
| `/fpp spawn` | `--name <name>` | `fpp.spawn` | Spawn one bot at your location |
| `/fpp despawn` | `<name>\|all\|own` | `fpp.despawn` | Despawn bots by name, owner or count |
| `/fpp list` | `[page]` | `fpp.list` | List all currently active bots |
| `/fpp move` | `<bot> --to <target>` | `fpp.move` | Pathfind to a bot, player or coordinates |
| `/fpp left-click` | `<bot> --repeat\|hold` | `fpp.left-click` | Break blocks / attack at the exact aim point |
| `/fpp right-click` | `<bot> --repeat\|hold` | `fpp.right-click` | Use items / interact at the exact aim point |
| `/fpp find` | `<bot> <block> -r <n>` | `fpp.find` | Search, path to and mine nearby blocks |
| `/fpp storage` | `<bot> [--deposit]` | `fpp.storage` | Set or manage bot storage targets |
| `/fpp attack` | `<bot\|all> [--once]` | `fpp.attack` | Swing / attack, guard or chase mobs |
| `/fpp sneak` | `<bot> [on\|off]` | `fpp.sneak` | Toggle a bot's sneaking state |
| `/fpp freeze` | `<bot\|all> [on\|off]` | `fpp.freeze` | Freeze or unfreeze a bot in place |
| `/fpp inventory` | `<bot>` | `fpp.inventory` | Open a bot's full inventory |
| `/fpp rename` | `<bot> <name>` | `fpp.rename` | Change a bot's display name |
| `/fpp info` | `[bot\|spawner] <name>` | `fpp.info` | Query a bot's session history |
| `/fpp check` | `--all` | `fpp.check` | Run a full system health check |
| `/fpp settings` | `[bot]` | `fpp.settings` | Open the interactive settings GUI |
| `/fpp rent` | `buy\|extend\|info\|give` | `fpp.rent` | Rent a bot with real economy currency |
| `/fpp reload` | `[all\|config\|lang]` | `fpp.reload` | Reload plugin configuration |
| `/fpp help` | `[page]` | `fpp.help` | Show the command help menu |

> [!NOTE]
> `fpp.admin` and `fpp.op` grant full access, `fpp.use` is the default user tier, and every command breaks down further
> into `fpp.<command>[.<action>][.<scope>]` nodes, down to per-permission bot limits like `fpp.spawn.limit.5`.

</details>

---

## 📋 Requirements

| | |
|---|---|
| **Server** | Paper or Purpur 1.21+ (up to 1.21.11 and the 26.1.x to 26.3.x releases), or Folia. Not Spigot or CraftBukkit |
| **Java** | 21 or newer |
| **Optional** | Vault / Vault2.0 / ExcellentEconomy (rentals), LuckPerms, PlaceholderAPI, ViaVersion, Spark |

## 💜 Free & open source

- ✅ No license key, no paywall, runs unconditionally
- ✅ Full source: audit it, fork it, self-host it
- ✅ Pull requests welcome under the contributing guidelines

> [!IMPORTANT]
> Player spoofing, bot chat, a runtime addon loader and license / DRM checks are deliberately excluded and won't be re-added.

---

<div align="center">

### Join the community

Setup help, bug reports and feature discussion happen in the open, not behind a support ticket.

[![Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/Q9cd9frzRt)
[![GitHub](https://img.shields.io/badge/GitHub-Source-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Pepe-tf/Fake-Player-plugin-2.0)
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-1BD96A?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/plugin/fake-player-plugin-fpp)
[![Hangar](https://img.shields.io/badge/Hangar-Download-1F2937?style=for-the-badge)](https://hangar.papermc.io/Pepe-tf/FakePlayerPlugin)

<sub>Made by **Janky Dev** · NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.</sub>

</div>
