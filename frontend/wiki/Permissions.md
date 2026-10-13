# Permissions

FPP uses a two-tier permission system with granular sub-nodes. Every check goes through
`CommandSender#hasPermission()` only - there is no hard-coded operator bypass anywhere in the
plugin, so a permissions plugin such as LuckPerms can grant or deny any `fpp.*` node, including for
server operators, and FPP will always respect it.

## Wildcard Tiers

| Node | Default | Description |
|------|---------|-------------|
| `fpp.admin` | `op` | Full access (identical to `fpp.op`) |
| `fpp.op` | `op` | Full admin wildcard - all commands |
| `fpp.use` | `true` | User-tier access - basic commands for all players |

## Core Permissions

### Command Visibility
- `fpp.command` - makes `/fpp` visible and usable (default: `true`)
- `fpp.plugininfo` - show full info panel on bare `/fpp` (default: `op`)

### Spawn
> `/fpp spawn` creates exactly **one** bot per command (auto-named or `--name <name>`), by default at
> the commanding player's own location. Console and command-block senders can use it too, but only at
> the admin tier, and only with `--location <x> <y> <z> <world>` - they have no location of their own.
- `fpp.spawn` - admin spawn (ignores personal limits); also gates `--location <x> <y> <z> <world>` to
  spawn somewhere other than the sender's own position, and is the only tier console/command blocks
  can use
- `fpp.spawn.user` - user spawn (limited by personal bot cap); always spawns at the sender's own
  location, `--location` is not available at this tier, and it requires a real player - console and
  command blocks can't use it
  - `fpp.spawn.limit.1` through `fpp.spawn.limit.100` - personal bot limit

### Despawn
- `fpp.despawn` - despawn bots (grants `fpp.delete` and `fpp.despawn.bulk`)
- `fpp.despawn.bulk` - despawn multiple bots (`--count`, `--random`)
- `fpp.despawn.own` - despawn only bots the sender spawned
- `fpp.delete` - legacy alias for `fpp.despawn`
- `fpp.delete.all` - legacy alias for bulk despawn

### Info / Teleport
- `fpp.list` - open the bot list GUI
- `fpp.info` - full admin session query
- `fpp.info.user` - user info (own bots only)
- `fpp.tp` - teleport to a bot
- `fpp.tph` - teleport bot(s) to sender
- `fpp.tph.all` - teleport all accessible bots
- `fpp.xp` - collect XP from own bots

### Movement
- `fpp.move` - pathfinding movement (`--stop`)
  - `fpp.move.to` - walk to another bot or player (follows live)
  - `fpp.move.coords` - walk to fixed coordinates
- `fpp.sneak` - toggle or set bot sneaking state

### Automation & Tasks
- `fpp.left-click` - left-click automation (break blocks / attack)
  - `fpp.left-click.start`, `.once`, `.repeat`, `.hold`, `.stop`
- `fpp.right-click` - right-click automation (use items / interact)
  - `fpp.right-click.start`, `.once`, `.repeat`, `.hold`, `.stop`
- `fpp.attack` - basic swing/attack
- `fpp.clear` - `/fpp clear` layer-by-layer excavation (destructive; op by default)
- `fpp.find` - search-and-mine automation (`/fpp find`)
- `fpp.storage` - set/manage bot storage targets and deposits
- `fpp.stop` - cancel all active tasks

### Management
- `fpp.freeze` - freeze/unfreeze bots
- `fpp.inventory` - open bot inventory GUI
  - `fpp.inventory.cmd` - via command
  - `fpp.inventory.rightclick` - via right-click entity
  - `fpp.inventory.own` - open the inventory of bots you personally own, without needing `.cmd`/`.rightclick`
- `fpp.setowner` - transfer bot ownership
- `fpp.rename` - rename a bot's display name (owners/admins can also rename from the per-bot settings GUI)
- `fpp.save` - force-save all active bots
- `fpp.settings` - open the settings GUIs (global **and** per-bot)

> **Per-bot GUI systems** - the per-bot settings GUI (general, PVE, pathfinding, skin, **auto-eat**,
> rename, danger) is one surface governed by `fpp.settings`. A player who **owns** (or has been
> shared) a bot can always open that bot's settings - including toggling auto-eat, choosing allowed
> foods and setting the hunger threshold - without needing `fpp.settings`. The node is only required
> to manage bots you don't own. None of these individual per-bot toggles have their own node.

### Economy / Rental
- `fpp.rent` - buy/extend a rented bot with real economy currency (`/fpp rent buy`/`extend`); included in `fpp.use`
- `fpp.rent.info` - check remaining rental time (`/fpp rent info`); included in `fpp.use`
- `fpp.rent.give` - grant rental time without charging (`/fpp rent give`/`clear`) - the console/shop-plugin integration point
- `fpp.rent.unlimited` - this player's rented bots never expire from time running out
- `fpp.rent.free` - rent without being charged; `fpp.rent.auto` - auto-renew (in `fpp.use`); `fpp.rent.admin` - list/act on everyone's rentals
- `fpp.rent.discount.<1-100>` - percent off rental prices (highest wins); `fpp.rent.limit.<1-50>` - max rented bots
- `fpp.spawn.free` - waive `economy.spawn.price`

### System
- `fpp.reload` - hot-reload config, lang, and skin pools
- `fpp.check` - run `/fpp check` system diagnostics
- `fpp.perf` - performance dashboard, history, and benchmarks
- `fpp.auth` - manage bot auto-register/login with `/fpp auth on|off|status|reset|setpassword`

### Bypass
- `fpp.bypass.max` - bypass global bot cap
- `fpp.bypass.cooldown` - skip spawn cooldown

### Notify
- `fpp.notify` - receive update notifications on join **and debug chat broadcasts** (when `debug-chat` is enabled)

## Quick Setup Examples

```
# Full admin
/lp group admin permission set fpp.admin true

# User access
/lp group member permission set fpp.use true

# Personal bot limit (5)
/lp user Alice permission set fpp.spawn.limit.5 true

# Bypass cooldown for VIPs
/lp group vip permission set fpp.bypass.cooldown true

# Hide /fpp from guests
/lp group guest permission set fpp.command false
```

## Legacy Nodes

These still work and map to their modern equivalents:
- `fpp.op` → identical to `fpp.admin`
- `fpp.delete` → identical to `fpp.despawn`
- `fpp.delete.all` → identical to `fpp.despawn.bulk`

> `fpp.spawn.multiple` / `fpp.spawn.mass` were **removed** along with multi-bot spawning -
> `/fpp spawn` always creates one bot per command now.
