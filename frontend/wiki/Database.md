# Database

FPP uses a database for persistence, session tracking, and network features.

## Modes

### LOCAL (SQLite)
- **File:** `plugins/FakePlayerPlugin/data/fpp.db`
- **Use case:** Single-server setups
- **Pros:** No setup required, zero configuration
- **Cons:** Not shared across servers

### NETWORK (MySQL)
- **Use case:** Multi-server proxy networks
- **Pros:** Shared bot registry, cross-server placeholders, config sync
- **Cons:** Requires MySQL server setup

## Setup

### SQLite (Default)

```yaml
database:
  enabled: true
  mode: "LOCAL"
  mysql-enabled: false
```

No additional setup required.

### MySQL

1. **Create Database:**
```sql
CREATE DATABASE fpp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'fpp_user'@'%' IDENTIFIED BY 'secure_password';
GRANT ALL PRIVILEGES ON fpp.* TO 'fpp_user'@'%';
FLUSH PRIVILEGES;
```

2. **Configure FPP:**
```yaml
database:
  enabled: true
  mode: "NETWORK"  # or "LOCAL" for single-server MySQL
  server-id: "survival"  # Unique per backend (required for NETWORK mode)
  mysql-enabled: true
  mysql:
    host: "localhost"
    port: 3306
    database: "fpp"
    username: "fpp_user"
    password: "secure_password"
    use-ssl: false
    pool-size: 5
    connection-timeout: 30000
```

3. **Restart Server**
   - FPP auto-creates tables on startup
   - Schema version: **25** (latest)

## Tables

### Core Tables (All Modes)
- `fpp_sessions` - Bot spawn/death session history
- `fpp_bot_data` - Persistent bot settings (XP, inventory, tasks)
- `fpp_rental_auto` - per-bot rental auto-renew choice (hours), survives restarts
- `fpp_skin_cache` - legacy skin cache (dormant - skins are now cached in `data/skin-cache.yml`)

### Network Tables (NETWORK Mode Only)
- `fpp_network_bots` - Live bot registry across all backends
- `fpp_server_heartbeat` - Server liveness tracking (pruned after 60s stale)
- `fpp_network_tasks` - Cross-server task persistence

## Persistence

When `persistence.enabled: true`:
- Bot positions saved on shutdown
- Tasks (mine/use/place) restored on restart
- Inventories and XP preserved
- Rolled pool skins preserved (a bot keeps its skin across restarts)

## Migration

There is no longer a `/fpp migrate` command. Config-file and database-schema migrations run **automatically** on startup (`ConfigMigrator`, `DatabaseManager` schema versioning), each creating a backup first via `BackupManager`. Manual SQLite ↔ MySQL export/backup/restore is not currently exposed via any command.

## Troubleshooting

### Connection Fails
- Verify MySQL credentials and firewall rules
- Ensure MySQL user has CREATE/ALTER permissions (needed for schema migrations)
- Check `use-ssl` matches your MySQL configuration

### SQLite Lock Errors
- Ensure no other process is accessing `fpp.db`
- Check file permissions on `data/` folder

### Schema Migration Errors
- Check logs for specific SQL errors
- Ensure MySQL version is 8.0+ (older versions may lack required features)
- Backup before manual schema changes

## Performance Tips

- Use connection pooling (default: 5 connections)
- Increase `pool-size` for high-traffic networks (10-20 recommended)
- Set `connection-timeout` to 30000ms or higher for slow networks
- Run database operations async (FPP does this by default)
