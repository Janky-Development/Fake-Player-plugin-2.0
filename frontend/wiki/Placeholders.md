# Placeholders

FPP provides an extensive set of placeholders via PlaceholderAPI (requires the PlaceholderAPI plugin).

All identifiers are prefixed with `%fpp_`.

## Server-Wide (16 placeholders)

| Placeholder | Description |
|-------------|-------------|
| `%fpp_count%` | Total bots (local + remote in NETWORK mode) |
| `%fpp_local_count%` | Bots on this server only |
| `%fpp_network_count%` | Bots on other proxy servers (NETWORK mode only) |
| `%fpp_max%` | Global bot cap (`∞` if unlimited) |
| `%fpp_real%` | Real players online |
| `%fpp_total%` | Real players on this server - **bots are never counted as players** |
| `%fpp_online%` | Same as `%fpp_total%` |
| `%fpp_network_total%` | Real players across ALL servers (NETWORK mode only; bots excluded) |
| `%fpp_network_real%` | Total real players across ALL servers (NETWORK mode only) |
| `%fpp_network_bots%` | Total bots across ALL servers (NETWORK mode only) |
| `%fpp_frozen%` | Number of frozen bots |
| `%fpp_names%` | Comma-separated bot names (includes remote in NETWORK mode) |
| `%fpp_network_names%` | Comma-separated remote bot names |
| `%fpp_version%` | Plugin version string |
| `%fpp_build%` | Build ID of the running jar (e.g. `D261013-140738-f3c1`) |
| `%fpp_config_version%` | Config version number |
| `%fpp_uptime%` | Plugin uptime (e.g. `4h 12m`) |

## Plugin Settings / Toggles

| Placeholder | Description |
|-------------|-------------|
| `%fpp_body%` | Always `on` (bodies always enabled since v1.6.6.12) |
| `%fpp_pushable%` | `on` or `off` |
| `%fpp_damageable%` | `on` or `off` |
| `%fpp_tab%` | `on` or `off` (tab list enabled) |
| `%fpp_max_health%` | Bot max health value |
| `%fpp_network%` | `on` or `off` (NETWORK mode) |
| `%fpp_network_mode%` | Same as `%fpp_network%` |
| `%fpp_server_id%` | Current server ID |
| `%fpp_persistence%` | `on` or `off` (persist on restart) |
| `%fpp_spawn_cooldown%` | Spawn cooldown in seconds |
| `%fpp_chunk_loading%` | `on` or `off` |
| `%fpp_chunk_loading_radius%` | Chunk loading radius value |
| `%fpp_head_ai%` | `on` or `off` |
| `%fpp_swim_ai%` | `on` or `off` |
| `%fpp_auto_eat%` | `on` or `off` |
| `%fpp_auto_place_bed%` | `on` or `off` |
| `%fpp_auto_milk%` | `on` or `off` |
| `%fpp_prevent_bad_omen%` | `on` or `off` |
| `%fpp_fall_damage%` | `on` or `off` |
| `%fpp_respawn_on_death%` | `on` or `off` |
| `%fpp_hurt_sound%` | `on` or `off` |
| `%fpp_death_message%` | `on` or `off` |
| `%fpp_metrics%` | `on` or `off` |
| `%fpp_update_checker%` | `on` or `off` |
| `%fpp_badword_filter%` | `on` or `off` |
| `%fpp_database%` | `on` or `off` |
| `%fpp_database_mode%` | Database mode: `SQLite`, `MySQL`, or `none` |

## Server Performance (2 placeholders)

| Placeholder | Description |
|-------------|-------------|
| `%fpp_server_tps%` | Server TPS (current) |
| `%fpp_server_uptime%` | Server uptime (e.g. `4h 12m`) |

## Ping (3 placeholders)

> Bots never report a fabricated latency, so bot ping placeholders always read `0`.

| Placeholder | Description |
|-------------|-------------|
| `%fpp_ping_all%` | If sender is a bot, returns bot's ping; otherwise sender's real ping |
| `%fpp_avg_ping%` | Average ping across all local bots |
| `%fpp_player_ping%` | Sender's real player ping |

## Per-World (3 placeholders)

| Placeholder | Description |
|-------------|-------------|
| `%fpp_count_<world>%` | Bots in a specific world |
| `%fpp_real_<world>%` | Real players in a specific world |
| `%fpp_total_<world>%` | Real players in a specific world (bots are never counted) |

## Player-Relative (13 placeholders)

| Placeholder | Description |
|-------------|-------------|
| `%fpp_user_count%` | Player's bot count |
| `%fpp_user_max%` | Player's bot limit (respects permission overrides) |
| `%fpp_user_names%` | Comma-separated names of player's bots |
| `%fpp_user_ping%` | Ping of player's first bot |
| `%fpp_user_ping_avg%` | Average ping of player's bots |
| `%fpp_user_frozen%` | Number of player's frozen bots |
| `%fpp_user_oldest%` | Name of player's oldest bot |
| `%fpp_user_newest%` | Name of player's newest bot |
| `%fpp_user_uptime%` | Combined uptime of player's bots (e.g. `1h 23m`) |
| `%fpp_user_total_uptime%` | Same as `%fpp_user_uptime%` |
| `%fpp_user_total_damage%` | Total damage taken by player's bots |
| `%fpp_user_deaths%` | Total deaths of player's bots |
| `%fpp_user_count_<world>%` | Player's bot count in a specific world |
| `%fpp_rental_count%` | Number of the player's currently rented (paid) bots |
| `%fpp_rent_package_<id>_price%` / `_hours` / `_label` / `_discount` | Configured rental package price (for this player), length, label, discount % |
| `%fpp_rent_price_<hours>%` / `%fpp_rent_price_new_<hours>%` | Price to extend / to rent a new bot for N hours (player's discounts applied) |
| `%fpp_rent_balance%` / `%fpp_rent_discount%` / `%fpp_rent_limit%` / `%fpp_rent_slots_left%` / `%fpp_rental_autorenew%` | Economy balance, discount %, rented-bot limit, free rental slots, auto-renew state |
| `%fpp_rental_remaining%` | Time left on the player's soonest-expiring rented bot (e.g. `3h 20m`), empty if none |

## Per-Bot (22 placeholders)

| Placeholder | Description |
|-------------|-------------|
| `%fpp_ping_<bot_name>%` | Specific bot's ping |
| `%fpp_health_<bot_name>%` | Specific bot's current health |
| `%fpp_health_max_<bot_name>%` | Specific bot's max health |
| `%fpp_world_<bot_name>%` | Specific bot's current world |
| `%fpp_loc_x_<bot_name>%` | Specific bot's X coordinate |
| `%fpp_loc_y_<bot_name>%` | Specific bot's Y coordinate |
| `%fpp_loc_z_<bot_name>%` | Specific bot's Z coordinate |
| `%fpp_frozen_<bot_name>%` | `yes` or `no` |
| `%fpp_sleeping_<bot_name>%` | `yes` or `no` |
| `%fpp_owner_<bot_name>%` | Username who spawned the bot |
| `%fpp_spawned_by_<bot_name>%` | Same as `%fpp_owner_<bot_name>%` |
| `%fpp_pve_<bot_name>%` | `yes` or `no` (bot attack mob enabled) |
| `%fpp_displayname_<bot_name>%` | Bot's display name |
| `%fpp_uuid_<bot_name>%` | Bot's UUID |
| `%fpp_spawn_time_<bot_name>%` | Bot's spawn time (ISO format) |
| `%fpp_following_<bot_name>%` | Who/what the bot is following |
| `%fpp_task_<bot_name>%` | Bot's current task (e.g. `idle`, `mining`, `placing`) |
| `%fpp_damage_<bot_name>%` | Total damage taken by bot |
| `%fpp_deaths_<bot_name>%` | Bot's death count |
| `%fpp_type_<bot_name>%` | Bot's type (e.g. `AFK`, `MINER`, `BUILDER`) |
| `%fpp_skin_<bot_name>%` | Bot's skin name |

## Examples

```
# Tab list header
&bBots: %fpp_count% | Real: %fpp_real% | Total: %fpp_online% | TPS: %fpp_server_tps%

# Scoreboard
'Bot Count': %fpp_count% / %fpp_max%
'Your Bots': %fpp_user_count% / %fpp_user_max%
'Server Uptime': %fpp_server_uptime%

# Per-bot info display
'Bot: %fpp_displayname_testbot%'
'Health: %fpp_health_testbot% / %fpp_health_max_testbot%'
'Location: %fpp_loc_x_testbot%, %fpp_loc_y_testbot%, %fpp_loc_z_testbot%'
'World: %fpp_world_testbot%'
'Task: %fpp_task_testbot%'
```

## Notes

- All per-bot placeholders require the exact bot name (case-sensitive)
- World-specific placeholders use the world name as the suffix (e.g., `%fpp_count_world%` for the "world" world)
- Network placeholders only work when NETWORK mode is enabled in config
- Player-relative placeholders (`user_*`) work for both online and offline players
