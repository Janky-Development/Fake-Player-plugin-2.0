package me.bill.fakePlayerPlugin.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.util.BackupManager;
import me.bill.fakePlayerPlugin.util.FppLogger;

public class DatabaseManager {

    private static final int SCHEMA_VERSION = 27;

    public static int getCurrentSchemaVersion() {
        return SCHEMA_VERSION;
    }

    private static final String CREATE_SESSIONS_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_bot_sessions ("
            + "  id              INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "  bot_name        VARCHAR(16)  NOT NULL,"
            + "  bot_display     VARCHAR(128) DEFAULT NULL,"
            + "  bot_uuid        VARCHAR(36)  NOT NULL,"
            + "  spawned_by      VARCHAR(16)  NOT NULL,"
            + "  spawned_by_uuid VARCHAR(36)  NOT NULL,"
            + "  world_name      VARCHAR(64)  NOT NULL,"
            + "  spawn_x         DOUBLE NOT NULL,"
            + "  spawn_y         DOUBLE NOT NULL,"
            + "  spawn_z         DOUBLE NOT NULL,"
            + "  spawn_yaw       FLOAT  NOT NULL DEFAULT 0,"
            + "  spawn_pitch     FLOAT  NOT NULL DEFAULT 0,"
            + "  last_world      VARCHAR(64),"
            + "  last_x          DOUBLE,"
            + "  last_y          DOUBLE,"
            + "  last_z          DOUBLE,"
            + "  last_yaw        FLOAT,"
            + "  last_pitch      FLOAT,"
            + "  entity_type     VARCHAR(32)  NOT NULL DEFAULT 'MANNEQUIN',"
            + "  spawned_at      BIGINT NOT NULL,"
            + "  removed_at      BIGINT,"
            + "  remove_reason   VARCHAR(32),"
            + "  server_id       VARCHAR(64)  NOT NULL DEFAULT 'default'"
            + ")";

    private static final String CREATE_SESSIONS_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_bot_sessions ("
            + "  id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
            + "  bot_name        VARCHAR(16)  NOT NULL,"
            + "  bot_display     VARCHAR(128) DEFAULT NULL,"
            + "  bot_uuid        VARCHAR(36)  NOT NULL,"
            + "  spawned_by      VARCHAR(16)  NOT NULL,"
            + "  spawned_by_uuid VARCHAR(36)  NOT NULL,"
            + "  world_name      VARCHAR(64)  NOT NULL,"
            + "  spawn_x         DOUBLE NOT NULL,"
            + "  spawn_y         DOUBLE NOT NULL,"
            + "  spawn_z         DOUBLE NOT NULL,"
            + "  spawn_yaw       FLOAT  NOT NULL DEFAULT 0,"
            + "  spawn_pitch     FLOAT  NOT NULL DEFAULT 0,"
            + "  last_world      VARCHAR(64),"
            + "  last_x          DOUBLE,"
            + "  last_y          DOUBLE,"
            + "  last_z          DOUBLE,"
            + "  last_yaw        FLOAT,"
            + "  last_pitch      FLOAT,"
            + "  entity_type     VARCHAR(32)  NOT NULL DEFAULT 'MANNEQUIN',"
            + "  spawned_at      BIGINT NOT NULL,"
            + "  removed_at      BIGINT,"
            + "  remove_reason   VARCHAR(32),"
            + "  server_id       VARCHAR(64)  NOT NULL DEFAULT 'default'"
            + ")";

    private static final String CREATE_ACTIVE_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_active_bots ("
            + "  bot_uuid        VARCHAR(36)  NOT NULL PRIMARY KEY,"
            + "  bot_name        VARCHAR(16)  NOT NULL,"
            + "  bot_display     VARCHAR(128) DEFAULT NULL,"
            + "  luckperms_group VARCHAR(64) DEFAULT NULL,"
            + "  chat_enabled    BOOLEAN DEFAULT 1,"
            + "  chat_tier       VARCHAR(16) DEFAULT NULL,"
            + "  right_click_cmd VARCHAR(256) DEFAULT NULL,"
            + "  ai_personality  VARCHAR(64) DEFAULT NULL,"
            + "  spawned_by      VARCHAR(16)  NOT NULL,"
            + "  spawned_by_uuid VARCHAR(36)  NOT NULL,"
            + "  world_name      VARCHAR(64)  NOT NULL,"
            + "  pos_x           DOUBLE NOT NULL,"
            + "  pos_y           DOUBLE NOT NULL,"
            + "  pos_z           DOUBLE NOT NULL,"
            + "  pos_yaw         FLOAT  NOT NULL DEFAULT 0,"
            + "  pos_pitch       FLOAT  NOT NULL DEFAULT 0,"
            + "  updated_at      BIGINT NOT NULL,"
            + "  server_id       VARCHAR(64)  NOT NULL DEFAULT 'default',"
            + "  frozen          BOOLEAN DEFAULT 0,"
            + "  pickup_items    BOOLEAN DEFAULT 0,"
            + "  pickup_xp       BOOLEAN DEFAULT 1,"
            + "  head_ai_enabled BOOLEAN DEFAULT 1,"
            + "  nav_parkour     BOOLEAN DEFAULT 0,"
            + "  nav_break_blocks BOOLEAN DEFAULT 0,"
            + "  nav_place_blocks BOOLEAN DEFAULT 0,"
            + "  nav_avoid_water BOOLEAN DEFAULT 0,"
            + "  nav_avoid_lava  BOOLEAN DEFAULT 0,"
            + "  swim_ai_enabled  BOOLEAN DEFAULT 1,"
            + "  chunk_load_radius INT     DEFAULT -1,"
            + "  ping             INT DEFAULT -1,"
            + "  ping_user_set    BOOLEAN DEFAULT 0,"
            + "  pve_enabled      BOOLEAN DEFAULT 0,"
            + "  pve_range        DOUBLE  DEFAULT 16.0,"
            + "  pve_priority     VARCHAR(16) DEFAULT NULL,"
            + "  pve_mob_type     VARCHAR(64) DEFAULT NULL,"
            + "  pve_smart_attack_mode VARCHAR(16) DEFAULT 'OFF',"
            + "  auto_milk_enabled BOOLEAN DEFAULT 1,"
            + "  prevent_bad_omen  BOOLEAN DEFAULT 1,"
            + "  respawn_on_death   BOOLEAN DEFAULT 0,"
            + "  skin_texture   TEXT DEFAULT NULL,"
            + "  skin_signature TEXT DEFAULT NULL,"
            + "  rental_expires_at BIGINT DEFAULT NULL,"
            + "  left_click_interval_ticks  INT DEFAULT -1,"
            + "  right_click_interval_ticks INT DEFAULT -1"
            + ")";

    private static final String CREATE_ACTIVE_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_active_bots ("
            + "  bot_uuid        VARCHAR(36)  NOT NULL PRIMARY KEY,"
            + "  bot_name        VARCHAR(16)  NOT NULL,"
            + "  bot_display     VARCHAR(128) DEFAULT NULL,"
            + "  luckperms_group VARCHAR(64) DEFAULT NULL,"
            + "  chat_enabled    BOOLEAN DEFAULT 1,"
            + "  chat_tier       VARCHAR(16) DEFAULT NULL,"
            + "  right_click_cmd VARCHAR(256) DEFAULT NULL,"
            + "  ai_personality  VARCHAR(64) DEFAULT NULL,"
            + "  spawned_by      VARCHAR(16)  NOT NULL,"
            + "  spawned_by_uuid VARCHAR(36)  NOT NULL,"
            + "  world_name      VARCHAR(64)  NOT NULL,"
            + "  pos_x           DOUBLE NOT NULL,"
            + "  pos_y           DOUBLE NOT NULL,"
            + "  pos_z           DOUBLE NOT NULL,"
            + "  pos_yaw         FLOAT  NOT NULL DEFAULT 0,"
            + "  pos_pitch       FLOAT  NOT NULL DEFAULT 0,"
            + "  updated_at      BIGINT NOT NULL,"
            + "  server_id       VARCHAR(64)  NOT NULL DEFAULT 'default',"
            + "  frozen          BOOLEAN DEFAULT 0,"
            + "  pickup_items    BOOLEAN DEFAULT 0,"
            + "  pickup_xp       BOOLEAN DEFAULT 1,"
            + "  head_ai_enabled BOOLEAN DEFAULT 1,"
            + "  nav_parkour     BOOLEAN DEFAULT 0,"
            + "  nav_break_blocks BOOLEAN DEFAULT 0,"
            + "  nav_place_blocks BOOLEAN DEFAULT 0,"
            + "  nav_avoid_water BOOLEAN DEFAULT 0,"
            + "  nav_avoid_lava  BOOLEAN DEFAULT 0,"
            + "  swim_ai_enabled  BOOLEAN DEFAULT 1,"
            + "  chunk_load_radius INT     DEFAULT -1,"
            + "  ping             INT DEFAULT -1,"
            + "  ping_user_set    BOOLEAN DEFAULT 0,"
            + "  pve_enabled      BOOLEAN DEFAULT 0,"
            + "  pve_range        DOUBLE  DEFAULT 16.0,"
            + "  pve_priority     VARCHAR(16) DEFAULT NULL,"
            + "  pve_mob_type     VARCHAR(64) DEFAULT NULL,"
            + "  pve_smart_attack_mode VARCHAR(16) DEFAULT 'OFF',"
            + "  auto_milk_enabled BOOLEAN DEFAULT 1,"
            + "  prevent_bad_omen  BOOLEAN DEFAULT 1,"
            + "  respawn_on_death   BOOLEAN DEFAULT 0,"
            + "  skin_texture   TEXT DEFAULT NULL,"
            + "  skin_signature TEXT DEFAULT NULL,"
            + "  rental_expires_at BIGINT DEFAULT NULL,"
            + "  left_click_interval_ticks  INT DEFAULT -1,"
            + "  right_click_interval_ticks INT DEFAULT -1"
            + ")";

    private static final String CREATE_IDENTITIES_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_bot_identities ("
            + "  bot_name   VARCHAR(16) NOT NULL,"
            + "  server_id  VARCHAR(64) NOT NULL DEFAULT 'default',"
            + "  bot_uuid   VARCHAR(36) NOT NULL,"
            + "  created_at BIGINT      NOT NULL,"
            + "  PRIMARY KEY (bot_name, server_id)"
            + ")";

    private static final String CREATE_IDENTITIES_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_bot_identities ("
            + "  bot_name   VARCHAR(16) NOT NULL,"
            + "  server_id  VARCHAR(64) NOT NULL DEFAULT 'default',"
            + "  bot_uuid   VARCHAR(36) NOT NULL,"
            + "  created_at BIGINT      NOT NULL,"
            + "  PRIMARY KEY (bot_name, server_id)"
            + ")";

    private static final String CREATE_TASKS_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_bot_tasks ("
            + "  bot_uuid    VARCHAR(36)  NOT NULL,"
            + "  server_id   VARCHAR(64)  NOT NULL DEFAULT 'default',"
            + "  task_type   VARCHAR(16)  NOT NULL,"
            + "  world_name  VARCHAR(64)  DEFAULT NULL,"
            + "  pos_x       DOUBLE       DEFAULT 0,"
            + "  pos_y       DOUBLE       DEFAULT 0,"
            + "  pos_z       DOUBLE       DEFAULT 0,"
            + "  pos_yaw     FLOAT        DEFAULT 0,"
            + "  pos_pitch   FLOAT        DEFAULT 0,"
            + "  once_flag   BOOLEAN      DEFAULT 0,"
            + "  extra_str   VARCHAR(256) DEFAULT NULL,"
            + "  extra_bool  BOOLEAN      DEFAULT 0,"
            + "  PRIMARY KEY (bot_uuid, server_id, task_type)"
            + ")";

    private static final String CREATE_TASKS_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_bot_tasks ("
            + "  bot_uuid    VARCHAR(36)  NOT NULL,"
            + "  server_id   VARCHAR(64)  NOT NULL DEFAULT 'default',"
            + "  task_type   VARCHAR(16)  NOT NULL,"
            + "  world_name  VARCHAR(64)  DEFAULT NULL,"
            + "  pos_x       DOUBLE       DEFAULT 0,"
            + "  pos_y       DOUBLE       DEFAULT 0,"
            + "  pos_z       DOUBLE       DEFAULT 0,"
            + "  pos_yaw     FLOAT        DEFAULT 0,"
            + "  pos_pitch   FLOAT        DEFAULT 0,"
            + "  once_flag   BOOLEAN      DEFAULT 0,"
            + "  extra_str   VARCHAR(256) DEFAULT NULL,"
            + "  extra_bool  BOOLEAN      DEFAULT 0,"
            + "  PRIMARY KEY (bot_uuid, server_id, task_type)"
            + ")";

    private static final String CREATE_RENTAL_AUTO = "CREATE TABLE IF NOT EXISTS fpp_rental_auto ("
            + "  bot_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "  hours    INT         NOT NULL"
            + ")";

    private static final String CREATE_DESPAWN_SNAPSHOTS = "CREATE TABLE IF NOT EXISTS fpp_despawn_snapshots ("
            + "  bot_name       VARCHAR(64)  NOT NULL,"
            + "  server_id      VARCHAR(64)  NOT NULL DEFAULT 'default',"
            + "  inventory_data TEXT         DEFAULT NULL,"
            + "  xp_total       INTEGER      DEFAULT 0,"
            + "  xp_level       INTEGER      DEFAULT 0,"
            + "  xp_progress    REAL         DEFAULT 0.0,"
            + "  saved_at       BIGINT       NOT NULL,"
            + "  PRIMARY KEY (bot_name, server_id)"
            + ")";

    private static final String CREATE_META = "CREATE TABLE IF NOT EXISTS fpp_meta ("
            + "  key_name VARCHAR(64)  NOT NULL PRIMARY KEY,"
            + "  value    VARCHAR(256) NOT NULL"
            + ")";

    private static final String CREATE_EXTENSION_DATA = "CREATE TABLE IF NOT EXISTS fpp_bot_extension_data ("
            + "  bot_uuid      VARCHAR(36)  NOT NULL,"
            + "  extension_key VARCHAR(64)  NOT NULL,"
            + "  data_key      VARCHAR(128) NOT NULL,"
            + "  data_value    TEXT DEFAULT NULL,"
            + "  updated_at    BIGINT NOT NULL,"
            + "  PRIMARY KEY (bot_uuid, extension_key, data_key)"
            + ")";

    private static final String CREATE_NETWORK_BOTS = "CREATE TABLE IF NOT EXISTS fpp_network_bots ("
            + "  bot_uuid     VARCHAR(36)  NOT NULL PRIMARY KEY,"
            + "  bot_name     VARCHAR(16)  NOT NULL,"
            + "  bot_display  VARCHAR(128) DEFAULT NULL,"
            + "  server_id    VARCHAR(64)  NOT NULL,"
            + "  spawned_by   VARCHAR(16)  NOT NULL,"
            + "  world_name   VARCHAR(64)  DEFAULT NULL,"
            + "  pos_x        DOUBLE       DEFAULT 0,"
            + "  pos_y        DOUBLE       DEFAULT 0,"
            + "  pos_z        DOUBLE       DEFAULT 0,"
            + "  ping         INT          DEFAULT 0,"
            + "  frozen       BOOLEAN      DEFAULT 0,"
            + "  updated_at   BIGINT       NOT NULL"
            + ")";

    private static final String CREATE_SERVER_HEARTBEAT = "CREATE TABLE IF NOT EXISTS fpp_server_heartbeat ("
            + "  server_id     VARCHAR(64)  NOT NULL PRIMARY KEY,"
            + "  real_players  INT          DEFAULT 0,"
            + "  bot_count     INT          DEFAULT 0,"
            + "  last_seen     BIGINT       NOT NULL"
            + ")";

    // Password stored here is always the AES-GCM ciphertext (see auth.AuthCipher), keyed by a
    // secret that lives only in auth.key on disk - never a plaintext column.
    private static final String CREATE_BOT_AUTH = "CREATE TABLE IF NOT EXISTS fpp_bot_auth ("
            + "  bot_name      VARCHAR(16)  NOT NULL PRIMARY KEY,"
            + "  password_enc  TEXT         NOT NULL,"
            + "  created_at    BIGINT       NOT NULL,"
            + "  updated_at    BIGINT       NOT NULL"
            + ")";

    private static final String CREATE_NETWORK_TASKS_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_network_tasks ("
            + "  id            INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "  target_bot    VARCHAR(36)  NOT NULL,"
            + "  source_server VARCHAR(64)  NOT NULL,"
            + "  target_server VARCHAR(64)  NOT NULL,"
            + "  task_type     VARCHAR(16)  NOT NULL,"
            + "  task_data     TEXT         DEFAULT NULL,"
            + "  created_at    BIGINT       NOT NULL,"
            + "  claimed_at    BIGINT       DEFAULT NULL,"
            + "  claimed_by    VARCHAR(64)  DEFAULT NULL,"
            + "  status        VARCHAR(16)  DEFAULT 'PENDING',"
            + "  result        TEXT         DEFAULT NULL"
            + ")";

    private static final String CREATE_NETWORK_TASKS_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_network_tasks ("
            + "  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
            + "  target_bot    VARCHAR(36)  NOT NULL,"
            + "  source_server VARCHAR(64)  NOT NULL,"
            + "  target_server VARCHAR(64)  NOT NULL,"
            + "  task_type     VARCHAR(16)  NOT NULL,"
            + "  task_data     TEXT         DEFAULT NULL,"
            + "  created_at    BIGINT       NOT NULL,"
            + "  claimed_at    BIGINT       DEFAULT NULL,"
            + "  claimed_by    VARCHAR(64)  DEFAULT NULL,"
            + "  status        VARCHAR(16)  DEFAULT 'PENDING',"
            + "  result        TEXT         DEFAULT NULL"
            + ")";

    private static final String CREATE_SKIN_CACHE_SQLITE = "CREATE TABLE IF NOT EXISTS fpp_skin_cache ("
            + "  skin_name         VARCHAR(16)  NOT NULL PRIMARY KEY,"
            + "  texture_value     TEXT         NOT NULL,"
            + "  texture_signature TEXT         DEFAULT NULL,"
            + "  source            VARCHAR(64)  DEFAULT NULL,"
            + "  cached_at         BIGINT       NOT NULL"
            + ")";

    private static final String CREATE_SKIN_CACHE_MYSQL = "CREATE TABLE IF NOT EXISTS fpp_skin_cache ("
            + "  skin_name         VARCHAR(16)  NOT NULL PRIMARY KEY,"
            + "  texture_value     TEXT         NOT NULL,"
            + "  texture_signature TEXT         DEFAULT NULL,"
            + "  source            VARCHAR(64)  DEFAULT NULL,"
            + "  cached_at         BIGINT       NOT NULL"
            + ")";

    private static final String[][] MIGRATIONS = {
        {
            "ALTER TABLE fpp_bot_sessions ADD COLUMN last_yaw   FLOAT",
            "ALTER TABLE fpp_bot_sessions ADD COLUMN last_pitch FLOAT"
        },
        {},
        {
            "ALTER TABLE fpp_bot_sessions ADD COLUMN bot_display VARCHAR(128) DEFAULT NULL",
            "ALTER TABLE fpp_active_bots  ADD COLUMN bot_display VARCHAR(128) DEFAULT NULL",
            "CREATE INDEX IF NOT EXISTS idx_sessions_bot_name    ON fpp_bot_sessions(bot_name)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_spawned_by  ON fpp_bot_sessions(spawned_by)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_removed_at  ON fpp_bot_sessions(removed_at)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_spawned_at  ON fpp_bot_sessions(spawned_at)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_bot_uuid    ON fpp_bot_sessions(bot_uuid)"
        },
        {
            "ALTER TABLE fpp_bot_sessions ADD COLUMN luckperms_group VARCHAR(64) DEFAULT NULL",
            "ALTER TABLE fpp_active_bots  ADD COLUMN luckperms_group VARCHAR(64) DEFAULT NULL"
        },
        {
            "ALTER TABLE fpp_bot_sessions ADD COLUMN server_id VARCHAR(64) NOT NULL DEFAULT" + " 'default'",
            "ALTER TABLE fpp_active_bots  ADD COLUMN server_id VARCHAR(64) NOT NULL DEFAULT" + " 'default'",
            "CREATE INDEX IF NOT EXISTS idx_sessions_server_id ON fpp_bot_sessions(server_id)",
            "CREATE INDEX IF NOT EXISTS idx_active_server_id   ON fpp_active_bots(server_id)"
        },
        {},
        {
            "CREATE TABLE IF NOT EXISTS fpp_bot_identities ("
                    + "  bot_name   VARCHAR(16) NOT NULL,"
                    + "  server_id  VARCHAR(64) NOT NULL DEFAULT 'default',"
                    + "  bot_uuid   VARCHAR(36) NOT NULL,"
                    + "  created_at BIGINT      NOT NULL,"
                    + "  PRIMARY KEY (bot_name, server_id)"
                    + ")"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN frozen            BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN chat_enabled      BOOLEAN DEFAULT 1",
            "ALTER TABLE fpp_active_bots ADD COLUMN chat_tier         VARCHAR(16) DEFAULT NULL",
            "ALTER TABLE fpp_active_bots ADD COLUMN right_click_cmd   VARCHAR(256) DEFAULT NULL"
        },
        {"ALTER TABLE fpp_active_bots ADD COLUMN ai_personality    VARCHAR(64) DEFAULT NULL"},
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN pickup_items      BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN pickup_xp         BOOLEAN DEFAULT 1"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN head_ai_enabled   BOOLEAN DEFAULT 1",
            "ALTER TABLE fpp_active_bots ADD COLUMN nav_parkour       BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN nav_break_blocks  BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN nav_place_blocks  BOOLEAN DEFAULT 0"
        },
        {
            "CREATE TABLE IF NOT EXISTS fpp_bot_tasks ("
                    + "  bot_uuid    VARCHAR(36)  NOT NULL,"
                    + "  server_id   VARCHAR(64)  NOT NULL DEFAULT 'default',"
                    + "  task_type   VARCHAR(16)  NOT NULL,"
                    + "  world_name  VARCHAR(64)  DEFAULT NULL,"
                    + "  pos_x       DOUBLE       DEFAULT 0,"
                    + "  pos_y       DOUBLE       DEFAULT 0,"
                    + "  pos_z       DOUBLE       DEFAULT 0,"
                    + "  pos_yaw     FLOAT        DEFAULT 0,"
                    + "  pos_pitch   FLOAT        DEFAULT 0,"
                    + "  once_flag   BOOLEAN      DEFAULT 0,"
                    + "  extra_str   VARCHAR(256) DEFAULT NULL,"
                    + "  extra_bool  BOOLEAN      DEFAULT 0,"
                    + "  PRIMARY KEY (bot_uuid, server_id, task_type)"
                    + ")"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN swim_ai_enabled   BOOLEAN DEFAULT 1",
            "ALTER TABLE fpp_active_bots ADD COLUMN chunk_load_radius  INT     DEFAULT -1"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN nav_avoid_water   BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN nav_avoid_lava    BOOLEAN DEFAULT 0"
        },
        {"ALTER TABLE fpp_active_bots ADD COLUMN ping INT DEFAULT -1"},
        {
            "CREATE TABLE IF NOT EXISTS fpp_skin_cache ("
                    + "  skin_name         VARCHAR(16)  NOT NULL PRIMARY KEY,"
                    + "  texture_value     TEXT         NOT NULL,"
                    + "  texture_signature TEXT         DEFAULT NULL,"
                    + "  source            VARCHAR(64)  DEFAULT NULL,"
                    + "  cached_at         BIGINT       NOT NULL"
                    + ")",
            "CREATE INDEX IF NOT EXISTS idx_skin_cache_cached_at ON fpp_skin_cache(cached_at)"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN pve_enabled      BOOLEAN DEFAULT 0",
            "ALTER TABLE fpp_active_bots ADD COLUMN pve_range        DOUBLE  DEFAULT 16.0",
            "ALTER TABLE fpp_active_bots ADD COLUMN pve_priority     VARCHAR(16) DEFAULT NULL",
            "ALTER TABLE fpp_active_bots ADD COLUMN pve_mob_type     VARCHAR(64) DEFAULT NULL"
        },
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN skin_texture   TEXT DEFAULT NULL",
            "ALTER TABLE fpp_active_bots ADD COLUMN skin_signature TEXT DEFAULT NULL"
        },
        {
            "CREATE TABLE IF NOT EXISTS fpp_despawn_snapshots ("
                    + "  bot_name       VARCHAR(64)  NOT NULL,"
                    + "  server_id      VARCHAR(64)  NOT NULL DEFAULT 'default',"
                    + "  inventory_data TEXT         DEFAULT NULL,"
                    + "  xp_total       INTEGER      DEFAULT 0,"
                    + "  xp_level       INTEGER      DEFAULT 0,"
                    + "  xp_progress    REAL         DEFAULT 0.0,"
                    + "  saved_at       BIGINT       NOT NULL,"
                    + "  PRIMARY KEY (bot_name, server_id)"
                    + ")"
        },
        {"ALTER TABLE fpp_active_bots ADD COLUMN pve_smart_attack_mode VARCHAR(16) DEFAULT 'OFF'"},
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN auto_milk_enabled BOOLEAN DEFAULT 1",
            "ALTER TABLE fpp_active_bots ADD COLUMN prevent_bad_omen BOOLEAN DEFAULT 1",
            "ALTER TABLE fpp_active_bots ADD COLUMN respawn_on_death BOOLEAN DEFAULT 0"
        },
        {"ALTER TABLE fpp_active_bots ADD COLUMN ping_user_set BOOLEAN DEFAULT 0"},
        {
            "ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_texture   TEXT DEFAULT NULL",
            "ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_signature TEXT DEFAULT NULL"
        },
        {
            "CREATE TABLE IF NOT EXISTS fpp_network_bots ("
                    + "  bot_uuid     VARCHAR(36)  NOT NULL PRIMARY KEY,"
                    + "  bot_name     VARCHAR(16)  NOT NULL,"
                    + "  bot_display  VARCHAR(128) DEFAULT NULL,"
                    + "  server_id    VARCHAR(64)  NOT NULL,"
                    + "  spawned_by   VARCHAR(16)  NOT NULL,"
                    + "  world_name   VARCHAR(64)  DEFAULT NULL,"
                    + "  pos_x        DOUBLE       DEFAULT 0,"
                    + "  pos_y        DOUBLE       DEFAULT 0,"
                    + "  pos_z        DOUBLE       DEFAULT 0,"
                    + "  ping         INT          DEFAULT 0,"
                    + "  frozen       BOOLEAN      DEFAULT 0,"
                    + "  updated_at   BIGINT       NOT NULL,"
                    + "  INDEX idx_server_id (server_id),"
                    + "  INDEX idx_updated_at (updated_at)"
                    + ")",
            "CREATE TABLE IF NOT EXISTS fpp_server_heartbeat ("
                    + "  server_id     VARCHAR(64)  NOT NULL PRIMARY KEY,"
                    + "  real_players  INT          DEFAULT 0,"
                    + "  bot_count     INT          DEFAULT 0,"
                    + "  last_seen     BIGINT       NOT NULL"
                    + ")",
            "CREATE TABLE IF NOT EXISTS fpp_network_tasks ("
                    + "  id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "  target_bot   VARCHAR(36)  NOT NULL,"
                    + "  source_server VARCHAR(64) NOT NULL,"
                    + "  target_server VARCHAR(64) NOT NULL,"
                    + "  task_type    VARCHAR(16)  NOT NULL,"
                    + "  task_data    TEXT         DEFAULT NULL,"
                    + "  created_at   BIGINT       NOT NULL,"
                    + "  claimed_at   BIGINT       DEFAULT NULL,"
                    + "  claimed_by   VARCHAR(64)  DEFAULT NULL,"
                    + "  status       VARCHAR(16)  DEFAULT 'PENDING',"
                    + "  result       TEXT         DEFAULT NULL,"
                    + "  INDEX idx_target_bot (target_bot),"
                    + "  INDEX idx_status (status),"
                    + "  INDEX idx_target_server (target_server)"
                    + ")"
        },
        {"ALTER TABLE fpp_active_bots ADD COLUMN rental_expires_at BIGINT DEFAULT NULL"},
        {
            "ALTER TABLE fpp_active_bots ADD COLUMN left_click_interval_ticks  INT DEFAULT -1",
            "ALTER TABLE fpp_active_bots ADD COLUMN right_click_interval_ticks INT DEFAULT -1"
        }
    };

    private volatile Connection connection;
    private boolean isMysql = false;
    private File dataFolder;
    private FakePlayerPlugin plugin;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FPP-Database-Writer");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, BotRecord> activeRecords = new ConcurrentHashMap<>();
    private final Map<String, PendingLocation> pendingLocations = new ConcurrentHashMap<>();

    public boolean init(FakePlayerPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        if (!connect()) return false;
        boolean freshInstall = !tableExists("fpp_bot_sessions");
        createTables();
        if (freshInstall) {

            setSchemaVersion(SCHEMA_VERSION);
            Config.debugDatabase("DB: fresh install - schema initialized at v" + SCHEMA_VERSION + ".");
        } else {
            migrate();
        }
        repairSchema();
        backfillIdentities();
        return true;
    }

    private boolean connect() {
        if (Config.mysqlEnabled()) {
            if (tryMysql()) {
                isMysql = true;
                return true;
            }
            FppLogger.warn("MySQL connection failed - falling back to SQLite.");
        }
        return trySqlite();
    }

    private boolean tryMysql() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            int connTimeout = Config.mysqlConnTimeout();
            String url = "jdbc:mysql://"
                    + Config.mysqlHost()
                    + ":"
                    + Config.mysqlPort()
                    + "/"
                    + Config.mysqlDatabase()
                    + "?useSSL="
                    + Config.mysqlUseSSL()
                    + "&autoReconnect=true&characterEncoding=utf8"
                    + "&connectionTimeout="
                    + connTimeout
                    + "&socketTimeout="
                    + (connTimeout * 2);
            connection = DriverManager.getConnection(url, Config.mysqlUsername(), Config.mysqlPassword());
            Config.debug("MySQL pool-size advisory: " + Config.mysqlPoolSize());
            FppLogger.info("Database connected via MySQL ("
                    + Config.mysqlHost()
                    + ":"
                    + Config.mysqlPort()
                    + "/"
                    + Config.mysqlDatabase()
                    + "). Connection object: " + connection);
            Config.debugDbConn("MySQL connection established - valid=" + isConnectionValid());
            return true;
        } catch (Exception e) {
            FppLogger.warn("MySQL init error: " + e.getMessage());
            return false;
        }
    }

    private boolean trySqlite() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbDir = new File(dataFolder, "data");
            dbDir.mkdirs();
            File dbFile = new File(dbDir, "fpp.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            Config.debugDbConn("SQLite connection created - object=" + connection + ", path=" + dbFile.getPath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA cache_size=-8000");
                st.execute("PRAGMA temp_store=MEMORY");
            }
            isMysql = false;
            FppLogger.info("Database connected via SQLite (" + dbFile.getPath() + ").");
            Config.debugDbConn("SQLite connection established - valid=" + isConnectionValid());
            return true;
        } catch (Exception e) {
            FppLogger.error("SQLite init error: " + e.getMessage());
            return false;
        }
    }

    private boolean tableExists(String tableName) {
        try (PreparedStatement ps = connection.prepareStatement(
                isMysql
                        ? "SELECT 1 FROM information_schema.tables WHERE" + " table_schema=DATABASE() AND table_name=?"
                        : "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void createTables() {
        exec(isMysql ? CREATE_SESSIONS_MYSQL : CREATE_SESSIONS_SQLITE);
        exec(isMysql ? CREATE_ACTIVE_MYSQL : CREATE_ACTIVE_SQLITE);
        exec(isMysql ? CREATE_IDENTITIES_MYSQL : CREATE_IDENTITIES_SQLITE);
        exec(isMysql ? CREATE_TASKS_MYSQL : CREATE_TASKS_SQLITE);
        exec(isMysql ? CREATE_SKIN_CACHE_MYSQL : CREATE_SKIN_CACHE_SQLITE);
        exec(CREATE_DESPAWN_SNAPSHOTS);
        exec(CREATE_RENTAL_AUTO);
        exec(CREATE_EXTENSION_DATA);
        exec(CREATE_META);
        exec(CREATE_NETWORK_BOTS);
        exec(CREATE_SERVER_HEARTBEAT);
        exec(CREATE_BOT_AUTH);
        exec(isMysql ? CREATE_NETWORK_TASKS_MYSQL : CREATE_NETWORK_TASKS_SQLITE);
    }

    private void migrate() {
        int current = getSchemaVersion();

        if (current < 1) {
            FppLogger.warn("DB schema_version="
                    + current
                    + " is invalid (expected >= 1). Treating as v1 to avoid a migration"
                    + " crash. All schema steps will be applied.");
            current = 1;
        }

        if (current >= SCHEMA_VERSION) {
            Config.debugDatabase("DB schema is current (v" + current + "). No migration needed.");
            return;
        }

        if (plugin != null) {
            BackupManager.createDatabaseBackup(plugin, "pre-db-migration-v" + current + "-to-v" + SCHEMA_VERSION);
        }

        FppLogger.info("Applying DB schema migration v"
                + current
                + " → v"
                + SCHEMA_VERSION
                + " ("
                + (SCHEMA_VERSION - current)
                + " step(s))…");

        for (int v = current; v < SCHEMA_VERSION; v++) {
            String[] migration = v - 1 < MIGRATIONS.length ? MIGRATIONS[v - 1] : new String[0];
            for (String sql : migration) {
                if (!sql.isEmpty()) execSilent(sql);
            }
            setSchemaVersion(v + 1);
            FppLogger.info("  DB schema: v" + v + " → v" + (v + 1));
        }

        keepExtensionOwnedDatabaseState();
        restoreSkinPersistenceColumns();
        restoreExtensionPersistenceColumns();

        FppLogger.info("DB schema migration complete (now at v" + SCHEMA_VERSION + ").");
    }

    private void repairSchema() {
        createTables();
        execSilent("ALTER TABLE fpp_bot_sessions ADD COLUMN last_yaw         FLOAT");
        execSilent("ALTER TABLE fpp_bot_sessions ADD COLUMN last_pitch       FLOAT");
        execSilent("ALTER TABLE fpp_bot_sessions ADD COLUMN bot_display      VARCHAR(128) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_bot_sessions ADD COLUMN server_id        VARCHAR(64)  NOT NULL DEFAULT 'default'");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN bot_display       VARCHAR(128) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN luckperms_group   VARCHAR(64) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN chat_enabled      BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN chat_tier         VARCHAR(16) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN right_click_cmd   VARCHAR(256) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ai_personality    VARCHAR(64) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN server_id         VARCHAR(64)  NOT NULL DEFAULT 'default'");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN frozen            BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pickup_items      BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pickup_xp         BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN head_ai_enabled   BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN nav_parkour       BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN nav_break_blocks  BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN nav_place_blocks  BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN swim_ai_enabled   BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN chunk_load_radius INT     DEFAULT -1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN nav_avoid_water   BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN nav_avoid_lava    BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pve_enabled      BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pve_range        DOUBLE  DEFAULT 16.0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pve_priority     VARCHAR(16) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pve_mob_type     VARCHAR(64) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN pve_smart_attack_mode VARCHAR(16) DEFAULT 'OFF'");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN auto_milk_enabled  BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN prevent_bad_omen   BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN respawn_on_death    BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ping               INT DEFAULT -1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ping_user_set      BOOLEAN DEFAULT 0");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN skin_texture        TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN skin_signature      TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN rental_expires_at   BIGINT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN left_click_interval_ticks  INT DEFAULT -1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN right_click_interval_ticks INT DEFAULT -1");
        execSilent("ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_texture   TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_signature TEXT DEFAULT NULL");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_bot_name    ON fpp_bot_sessions(bot_name)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_spawned_by  ON fpp_bot_sessions(spawned_by)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_removed_at  ON fpp_bot_sessions(removed_at)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_spawned_at  ON fpp_bot_sessions(spawned_at)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_bot_uuid    ON fpp_bot_sessions(bot_uuid)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_sessions_server_id   ON fpp_bot_sessions(server_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_active_server_id     ON fpp_active_bots(server_id)");
        execSilent("CREATE INDEX IF NOT EXISTS idx_skin_cache_cached_at ON fpp_skin_cache(cached_at)");
        execSilent(CREATE_EXTENSION_DATA);
    }

    private void keepExtensionOwnedDatabaseState() {
        FppLogger.info("  DB schema: preserving extension-owned columns for optional extension reinstalls.");
    }

    private void restoreSkinPersistenceColumns() {
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN skin_texture   TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN skin_signature TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_texture   TEXT DEFAULT NULL");
        execSilent("ALTER TABLE fpp_despawn_snapshots ADD COLUMN skin_signature TEXT DEFAULT NULL");
        FppLogger.info("  DB schema: restored persisted skin texture columns.");
    }

    private void restoreExtensionPersistenceColumns() {
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN luckperms_group VARCHAR(64) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN chat_enabled BOOLEAN DEFAULT 1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN chat_tier VARCHAR(16) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN right_click_cmd VARCHAR(256) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ai_personality VARCHAR(64) DEFAULT NULL");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ping INT DEFAULT -1");
        execSilent("ALTER TABLE fpp_active_bots ADD COLUMN ping_user_set BOOLEAN DEFAULT 0");
        execSilent(CREATE_EXTENSION_DATA);
        FppLogger.info("  DB schema: restored extension persistence columns.");
    }

    private int getSchemaVersion() {
        try (PreparedStatement ps =
                        connection.prepareStatement("SELECT value FROM fpp_meta WHERE key_name='schema_version'");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Integer.parseInt(rs.getString(1));
        } catch (Exception ignored) {
        }
        return 1;
    }

    private void setSchemaVersion(int v) {
        String sql = isMysql
                ? "INSERT INTO fpp_meta(key_name,value) VALUES('schema_version',?) ON" + " DUPLICATE KEY UPDATE value=?"
                : "INSERT OR REPLACE INTO fpp_meta(key_name,value)" + " VALUES('schema_version',?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(v));
            if (isMysql) ps.setString(2, String.valueOf(v));
            ps.executeUpdate();
        } catch (SQLException e) {
            FppLogger.error("DB setSchemaVersion: " + e.getMessage());
        }
    }

    private String serverCond() {
        return Config.isNetworkMode() ? "1=1" : "server_id=?";
    }

    private int bindServer(PreparedStatement ps, int idx) throws SQLException {
        if (!Config.isNetworkMode()) ps.setString(idx++, Config.serverId());
        return idx;
    }

    private String serverWhere() {
        if (Config.isNetworkMode()) return "";
        return " WHERE server_id='" + Config.serverId().replace("'", "''") + "'";
    }

    private String serverAnd() {
        if (Config.isNetworkMode()) return "";
        return " AND server_id='" + Config.serverId().replace("'", "''") + "'";
    }

    public void close() {
        boolean isClosed = false;
        try {
            isClosed = (connection != null && connection.isClosed());
        } catch (SQLException e) {
            isClosed = true;
        }
        Config.debugDbConn("Database close() called - connection=" + connection + ", closed=" + isClosed);
        flushPendingLocations();
        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                FppLogger.warn("Database writer did not finish within 5 seconds; closing anyway.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !isClosed) {
                Config.debugDbConn("Closing active database connection");
                connection.close();
                FppLogger.info("Database connection closed.");
            } else {
                Config.debugDbConn("Database connection already closed or null");
            }
        } catch (SQLException e) {
            FppLogger.error("Error closing DB: " + e.getMessage());
        }
    }

    public Connection getConnection() {
        return connection;
    }

    private boolean isAlive() {
        try {
            if (connection == null) {
                Config.debugDbConn("isAlive: connection is null");
                return false;
            }
            if (connection.isClosed()) {
                Config.debugDbConn("isAlive: connection is closed");
                return false;
            }
            if (isMysql) {
                try (Statement st = connection.createStatement()) {
                    st.execute("SELECT 1");
                } catch (SQLException e) {
                    Config.debugDbConn("isAlive: MySQL ping failed - " + e.getMessage());
                    return false;
                }
            }
            Config.debugDbConn("isAlive: connection is valid");
            return true;
        } catch (SQLException e) {
            Config.debugDbConn("isAlive: exception - " + e.getMessage());
            return false;
        }
    }

    public boolean isConnectionValid() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public void recordSpawn(BotRecord record) {
        recordSpawn(record, null);
    }

    public void recordSpawn(BotRecord record, String displayName) {
        activeRecords.put(record.getBotUuid().toString(), record);
        final String display = displayName;
        enqueue(() -> {
            if (!isAlive()) return;

            String sql = "INSERT INTO fpp_bot_sessions "
                    + "(bot_name,bot_display,bot_uuid,spawned_by,spawned_by_uuid,world_name,spawn_x,spawn_y,spawn_z,spawn_yaw,spawn_pitch,entity_type,spawned_at,server_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, record.getBotName());
                ps.setString(2, display);
                ps.setString(3, record.getBotUuid().toString());
                ps.setString(4, record.getSpawnedBy());
                ps.setString(5, record.getSpawnedByUuid().toString());
                ps.setString(6, record.getWorldName());
                ps.setDouble(7, record.getSpawnX());
                ps.setDouble(8, record.getSpawnY());
                ps.setDouble(9, record.getSpawnZ());
                ps.setFloat(10, record.getSpawnYaw());
                ps.setFloat(11, record.getSpawnPitch());
                ps.setString(12, "MANNEQUIN");
                ps.setLong(13, record.getSpawnedAt().toEpochMilli());
                ps.setString(14, record.getServerId());
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB recordSpawn: " + e.getMessage());
            }

            upsertActiveBotSync(
                    record.getBotUuid().toString(),
                    record.getBotName(),
                    display,
                    record.getSpawnedBy(),
                    record.getSpawnedByUuid().toString(),
                    record.getWorldName(),
                    record.getSpawnX(),
                    record.getSpawnY(),
                    record.getSpawnZ(),
                    record.getSpawnYaw(),
                    record.getSpawnPitch(),
                    null,
                    false,
                    true,
                    null,
                    null,
                    null,
                    Config.bodyPickUpItems(),
                    Config.bodyPickUpXp(),
                    true,
                    Config.pathfindingParkour(),
                    Config.pathfindingBreakBlocks(),
                    Config.pathfindingPlaceBlocks());
        });
    }

    public void updateLastLocation(UUID botUuid, String world, double x, double y, double z, float yaw, float pitch) {
        String key = botUuid.toString();
        pendingLocations.put(key, new PendingLocation(world, x, y, z, yaw, pitch));
        BotRecord rec = activeRecords.get(key);
        if (rec != null) rec.setLastLocation(world, x, y, z, yaw, pitch);
    }

    public void flushPendingLocations() {
        if (pendingLocations.isEmpty()) return;
        Map<String, PendingLocation> snap = new HashMap<>(pendingLocations);
        pendingLocations.clear();
        enqueue(() -> batchUpdateLocationsSync(snap));
    }

    public void recordRemoval(UUID botUuid, String reason) {
        Instant now = Instant.now();
        String key = botUuid.toString();
        BotRecord rec = activeRecords.remove(key);
        if (rec != null) {
            rec.setRemovedAt(now);
            rec.setRemoveReason(reason);
        }
        pendingLocations.remove(key);
        enqueue(() -> {
            if (!isAlive()) return;
            long ts = now.toEpochMilli();
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE fpp_bot_sessions SET removed_at=?,remove_reason=? WHERE"
                            + " bot_uuid=? AND removed_at IS NULL")) {
                ps.setLong(1, ts);
                ps.setString(2, reason);
                ps.setString(3, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB recordRemoval (session): " + e.getMessage());
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_active_bots WHERE bot_uuid=?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB recordRemoval (active): " + e.getMessage());
            }
        });
    }

    public void recordAllShutdown() {
        if (!pendingLocations.isEmpty()) {
            batchUpdateLocationsSync(new HashMap<>(pendingLocations));
            pendingLocations.clear();
        }
        activeRecords.clear();
        if (!isAlive()) return;
        try (PreparedStatement ps =
                connection.prepareStatement("UPDATE fpp_bot_sessions SET removed_at=?,remove_reason='SHUTDOWN' "
                        + "WHERE removed_at IS NULL AND server_id=?")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setString(2, Config.serverId());
            int rows = ps.executeUpdate();
            Config.debug("DB shutdown: closed " + rows + " open session(s).");
        } catch (SQLException e) {
            FppLogger.error("DB recordAllShutdown: " + e.getMessage());
        }
    }

    public void clearActiveBots() {
        final String sid = Config.serverId();
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_active_bots WHERE server_id=?")) {
                ps.setString(1, sid);
                int rows = ps.executeUpdate();
                Config.debug("DB cleared " + rows + " active_bot(s) for server='" + sid + "'.");
            } catch (SQLException e) {
                FppLogger.error("DB clearActiveBots: " + e.getMessage());
            }
        });
    }

    public void deleteActiveBot(String botUuid) {
        if (botUuid == null || botUuid.isBlank()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_active_bots WHERE bot_uuid=?")) {
                ps.setString(1, botUuid);
                int rows = ps.executeUpdate();
                if (rows > 0) Config.debugDatabase("DB deleted stale active_bot row for uuid=" + botUuid);
            } catch (SQLException e) {
                FppLogger.error("DB deleteActiveBot: " + e.getMessage());
            }
        });
    }

    public List<ActiveBotRow> getActiveBots() {
        List<ActiveBotRow> list = new ArrayList<>();
        if (!isAlive()) return list;
        String sql = "SELECT * FROM fpp_active_bots WHERE " + serverCond() + " ORDER BY updated_at ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindServer(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapActiveBotRow(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getActiveBots: " + e.getMessage());
        }
        return list;
    }

    public List<ActiveBotRow> getActiveBotsForThisServer() {
        List<ActiveBotRow> list = new ArrayList<>();
        if (!isAlive()) return list;

        String sql = "SELECT * FROM fpp_active_bots WHERE server_id=? ORDER BY updated_at ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapActiveBotRow(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getActiveBotsForThisServer: " + e.getMessage());
        }
        return list;
    }

    public List<ActiveBotRow> getActiveBotsFromOtherServers() {
        List<ActiveBotRow> list = new ArrayList<>();
        if (!Config.isNetworkMode() || !isAlive()) return list;
        String sql = "SELECT * FROM fpp_active_bots WHERE server_id != ? ORDER BY updated_at ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapActiveBotRow(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getActiveBotsFromOtherServers: " + e.getMessage());
        }
        return list;
    }

    /**
     * Returns remote bots from the shared fpp_network_bots table.
     * Used at startup to pre-populate RemoteBotCache in proxy-merged setups.
     */
    public List<NetworkBotPreview> getNetworkBotsFromOtherServers() {
        List<NetworkBotPreview> list = new ArrayList<>();
        if (!Config.isNetworkMode() || !isAlive()) return list;
        String sql = "SELECT bot_uuid, bot_name, bot_display, server_id FROM fpp_network_bots WHERE server_id != ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new NetworkBotPreview(
                            rs.getString("bot_uuid"),
                            rs.getString("bot_name"),
                            rs.getString("bot_display"),
                            rs.getString("server_id")));
                }
            }
        } catch (SQLException e) {
            FppLogger.error("DB getNetworkBotsFromOtherServers: " + e.getMessage());
        }
        return list;
    }

    public record NetworkBotPreview(String botUuid, String botName, String botDisplay, String serverId) {}

    private ActiveBotRow mapActiveBotRow(ResultSet rs) throws SQLException {
        String lpGroup = null;
        try {
            lpGroup = rs.getString("luckperms_group");
        } catch (SQLException ignored) {
        }
        String sid = null;
        try {
            sid = rs.getString("server_id");
        } catch (SQLException ignored) {
        }
        if (sid == null || sid.isBlank()) sid = Config.serverId();
        String aiPers = null;
        try {
            aiPers = rs.getString("ai_personality");
        } catch (SQLException ignored) {
        }
        boolean pickUpItems = Config.bodyPickUpItems();
        try {
            pickUpItems = rs.getBoolean("pickup_items");
        } catch (SQLException ignored) {
        }
        boolean pickUpXp = Config.bodyPickUpXp();
        try {
            pickUpXp = rs.getBoolean("pickup_xp");
        } catch (SQLException ignored) {
        }
        boolean frozen = false;
        try {
            frozen = rs.getBoolean("frozen");
        } catch (SQLException ignored) {
        }
        boolean chatEnabled = true;
        try {
            chatEnabled = rs.getBoolean("chat_enabled");
        } catch (SQLException ignored) {
        }
        String chatTier = null;
        try {
            chatTier = rs.getString("chat_tier");
        } catch (SQLException ignored) {
        }
        String rightClickCmd = null;
        try {
            rightClickCmd = rs.getString("right_click_cmd");
        } catch (SQLException ignored) {
        }
        boolean headAiEnabled = true;
        try {
            headAiEnabled = rs.getBoolean("head_ai_enabled");
        } catch (SQLException ignored) {
        }
        boolean navParkour = Config.pathfindingParkour();
        try {
            navParkour = rs.getBoolean("nav_parkour");
        } catch (SQLException ignored) {
        }
        boolean navBreakBlocks = Config.pathfindingBreakBlocks();
        try {
            navBreakBlocks = rs.getBoolean("nav_break_blocks");
        } catch (SQLException ignored) {
        }
        boolean navPlaceBlocks = Config.pathfindingPlaceBlocks();
        try {
            navPlaceBlocks = rs.getBoolean("nav_place_blocks");
        } catch (SQLException ignored) {
        }
        boolean navAvoidWater = false;
        try {
            navAvoidWater = rs.getBoolean("nav_avoid_water");
        } catch (SQLException ignored) {
        }
        boolean navAvoidLava = false;
        try {
            navAvoidLava = rs.getBoolean("nav_avoid_lava");
        } catch (SQLException ignored) {
        }
        boolean swimAiEnabled = Config.swimAiEnabled();
        try {
            swimAiEnabled = rs.getBoolean("swim_ai_enabled");
        } catch (SQLException ignored) {
        }
        int chunkLoadRadius = -1;
        try {
            chunkLoadRadius = rs.getInt("chunk_load_radius");
        } catch (SQLException ignored) {
        }
        int ping = -1;
        try {
            ping = rs.getInt("ping");
        } catch (SQLException ignored) {
        }
        boolean pveEnabled = false;
        try {
            pveEnabled = rs.getBoolean("pve_enabled");
        } catch (SQLException ignored) {
        }
        double pveRange = Config.attackMobDefaultRange();
        try {
            pveRange = rs.getDouble("pve_range");
        } catch (SQLException ignored) {
        }
        String pvePriority = null;
        try {
            pvePriority = rs.getString("pve_priority");
        } catch (SQLException ignored) {
        }
        String pveMobType = null;
        try {
            pveMobType = rs.getString("pve_mob_type");
        } catch (SQLException ignored) {
        }
        String pveSmartAttackMode = pveEnabled ? "ON_NO_MOVE" : "OFF";
        try {
            String raw = rs.getString("pve_smart_attack_mode");
            if (raw != null && !raw.isBlank()) pveSmartAttackMode = raw;
        } catch (SQLException ignored) {
        }
        String skinTexture = null;
        try {
            skinTexture = rs.getString("skin_texture");
        } catch (SQLException ignored) {
        }
        String skinSignature = null;
        try {
            skinSignature = rs.getString("skin_signature");
        } catch (SQLException ignored) {
        }
        boolean autoMilkEnabled = Config.autoMilkEnabled();
        try {
            autoMilkEnabled = rs.getBoolean("auto_milk_enabled");
        } catch (SQLException ignored) {
        }
        boolean preventBadOmen = Config.preventBadOmen();
        try {
            preventBadOmen = rs.getBoolean("prevent_bad_omen");
        } catch (SQLException ignored) {
        }
        boolean respawnOnDeath = Config.respawnOnDeath();
        try {
            respawnOnDeath = rs.getBoolean("respawn_on_death");
        } catch (SQLException ignored) {
        }
        boolean pingUserSet = false;
        try {
            pingUserSet = rs.getBoolean("ping_user_set");
        } catch (SQLException ignored) {
        }
        Long rentalExpiresAt = null;
        try {
            long raw = rs.getLong("rental_expires_at");
            if (!rs.wasNull()) rentalExpiresAt = raw;
        } catch (SQLException ignored) {
        }
        int leftClickIntervalTicks = -1;
        try {
            leftClickIntervalTicks = rs.getInt("left_click_interval_ticks");
        } catch (SQLException ignored) {
        }
        int rightClickIntervalTicks = -1;
        try {
            rightClickIntervalTicks = rs.getInt("right_click_interval_ticks");
        } catch (SQLException ignored) {
        }
        return new ActiveBotRow(
                rs.getString("bot_uuid"),
                rs.getString("bot_name"),
                rs.getString("bot_display"),
                rs.getString("spawned_by"),
                rs.getString("spawned_by_uuid"),
                rs.getString("world_name"),
                rs.getDouble("pos_x"),
                rs.getDouble("pos_y"),
                rs.getDouble("pos_z"),
                rs.getFloat("pos_yaw"),
                rs.getFloat("pos_pitch"),
                lpGroup,
                sid,
                aiPers,
                pickUpItems,
                pickUpXp,
                frozen,
                chatEnabled,
                chatTier,
                rightClickCmd,
                headAiEnabled,
                navParkour,
                navBreakBlocks,
                navPlaceBlocks,
                navAvoidWater,
                navAvoidLava,
                swimAiEnabled,
                chunkLoadRadius,
                ping,
                pveEnabled,
                pveRange,
                pvePriority,
                pveMobType,
                pveSmartAttackMode,
                skinTexture,
                skinSignature,
                autoMilkEnabled,
                preventBadOmen,
                respawnOnDeath,
                pingUserSet,
                rentalExpiresAt,
                leftClickIntervalTicks,
                rightClickIntervalTicks);
    }

    public List<BotRecord> getActiveSessions() {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM fpp_bot_sessions WHERE removed_at IS NULL AND " + serverCond())) {
            bindServer(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getActiveSessions: " + e.getMessage());
        }
        return list;
    }

    public List<BotRecord> getRecentSessions(int limit) {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM fpp_bot_sessions WHERE " + serverCond() + " ORDER BY spawned_at DESC LIMIT ?")) {
            int next = bindServer(ps, 1);
            ps.setInt(next, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getRecentSessions: " + e.getMessage());
        }
        return list;
    }

    public List<BotRecord> getSessionsBySpawner(String playerName) {
        return getSessionsBySpawner(playerName, Integer.MAX_VALUE);
    }

    public List<BotRecord> getSessionsBySpawner(String playerName, int limit) {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM fpp_bot_sessions WHERE spawned_by=?"
                + serverAnd()
                + " ORDER BY spawned_at DESC LIMIT ?")) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getSessionsBySpawner: " + e.getMessage());
        }
        return list;
    }

    public List<BotRecord> getSessionsByBot(String botName) {
        return getSessionsByBot(botName, Integer.MAX_VALUE);
    }

    public List<BotRecord> getSessionsByBot(String botName, int limit) {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM fpp_bot_sessions WHERE bot_name=?"
                + serverAnd()
                + " ORDER BY spawned_at DESC LIMIT ?")) {
            ps.setString(1, botName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getSessionsByBot: " + e.getMessage());
        }
        return list;
    }

    public List<BotRecord> getSessionsByUuid(UUID botUuid) {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM fpp_bot_sessions WHERE bot_uuid=?" + serverAnd() + " ORDER BY spawned_at DESC")) {
            ps.setString(1, botUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getSessionsByUuid: " + e.getMessage());
        }
        return list;
    }

    public List<BotRecord> getSessionsByReason(String reason, int limit) {
        List<BotRecord> list = new ArrayList<>();
        if (!isAlive()) return list;

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM fpp_bot_sessions WHERE remove_reason=?"
                + serverAnd()
                + " ORDER BY removed_at DESC LIMIT ?")) {
            ps.setString(1, reason);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSession(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getSessionsByReason: " + e.getMessage());
        }
        return list;
    }

    public int getTotalSessionCount() {
        if (!isAlive()) return 0;
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fpp_bot_sessions" + serverWhere())) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            FppLogger.error("DB getTotalSessionCount: " + e.getMessage());
        }
        return 0;
    }

    public int getActiveSessionCount() {
        if (!isAlive()) return 0;
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM fpp_bot_sessions WHERE removed_at IS NULL" + serverAnd())) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            FppLogger.error("DB getActiveSessionCount: " + e.getMessage());
        }
        return 0;
    }

    public int getUniqueBotCount() {
        if (!isAlive()) return 0;
        try (Statement st = connection.createStatement();
                ResultSet rs =
                        st.executeQuery("SELECT COUNT(DISTINCT bot_name) FROM fpp_bot_sessions" + serverWhere())) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            FppLogger.error("DB getUniqueBotCount: " + e.getMessage());
        }
        return 0;
    }

    public int getUniqueSpawnerCount() {
        if (!isAlive()) return 0;
        try (Statement st = connection.createStatement();
                ResultSet rs =
                        st.executeQuery("SELECT COUNT(DISTINCT spawned_by) FROM fpp_bot_sessions" + serverWhere())) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            FppLogger.error("DB getUniqueSpawnerCount: " + e.getMessage());
        }
        return 0;
    }

    public Map<String, Integer> getTopSpawners(int limit) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!isAlive()) return result;
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT spawned_by, COUNT(*) AS cnt FROM fpp_bot_sessions WHERE "
                        + serverCond()
                        + " GROUP BY spawned_by ORDER BY cnt DESC LIMIT ?")) {
            int next = bindServer(ps, 1);
            ps.setInt(next, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("spawned_by"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getTopSpawners: " + e.getMessage());
        }
        return result;
    }

    public DbStats getStats() {
        if (!isAlive()) return new DbStats(0, 0, 0, 0, 0L, isMysql ? "MySQL" : "SQLite");
        int total = 0, active = 0, unique = 0, uniqueSpawners = 0;
        long totalUptimeMs = 0L;
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fpp_bot_sessions" + serverWhere())) {
                if (rs.next()) total = rs.getInt(1);
            }
            try (ResultSet rs =
                    st.executeQuery("SELECT COUNT(*) FROM fpp_bot_sessions WHERE removed_at IS NULL" + serverAnd())) {
                if (rs.next()) active = rs.getInt(1);
            }
            try (ResultSet rs =
                    st.executeQuery("SELECT COUNT(DISTINCT bot_name) FROM fpp_bot_sessions" + serverWhere())) {
                if (rs.next()) unique = rs.getInt(1);
            }
            try (ResultSet rs =
                    st.executeQuery("SELECT COUNT(DISTINCT spawned_by) FROM fpp_bot_sessions" + serverWhere())) {
                if (rs.next()) uniqueSpawners = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT SUM(removed_at - spawned_at) FROM fpp_bot_sessions "
                    + "WHERE removed_at IS NOT NULL"
                    + serverAnd())) {
                if (rs.next()) totalUptimeMs = rs.getLong(1);
            }
        } catch (SQLException e) {
            FppLogger.error("DB getStats: " + e.getMessage());
        }
        return new DbStats(total, active, unique, uniqueSpawners, totalUptimeMs, isMysql ? "MySQL" : "SQLite");
    }

    private void upsertActiveBotSync(
            String uuid,
            String name,
            String display,
            String spawnedBy,
            String spawnedByUuid,
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String luckpermsGroup,
            boolean frozen,
            boolean chatEnabled,
            String chatTier,
            String rightClickCmd,
            String aiPersonality,
            boolean pickUpItems,
            boolean pickUpXp,
            boolean headAiEnabled,
            boolean navParkour,
            boolean navBreakBlocks,
            boolean navPlaceBlocks) {
        long now = Instant.now().toEpochMilli();
        String serverId = Config.serverId();
        String sql = isMysql
                ? "INSERT INTO"
                        + " fpp_active_bots(bot_uuid,bot_name,bot_display,spawned_by,spawned_by_uuid,"
                        + "world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,updated_at,server_id,"
                        + "frozen,pickup_items,pickup_xp,head_ai_enabled,nav_parkour,nav_break_blocks,nav_place_blocks)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY"
                        + " UPDATE bot_name=VALUES(bot_name),bot_display=VALUES(bot_display),"
                        + "spawned_by=VALUES(spawned_by),spawned_by_uuid=VALUES(spawned_by_uuid),"
                        + "world_name=VALUES(world_name),pos_x=VALUES(pos_x),pos_y=VALUES(pos_y),"
                        + "pos_z=VALUES(pos_z),pos_yaw=VALUES(pos_yaw),pos_pitch=VALUES(pos_pitch),"
                        + "updated_at=VALUES(updated_at),server_id=VALUES(server_id),frozen=VALUES(frozen),"
                        + "pickup_items=VALUES(pickup_items),pickup_xp=VALUES(pickup_xp),"
                        + "head_ai_enabled=VALUES(head_ai_enabled),nav_parkour=VALUES(nav_parkour),"
                        + "nav_break_blocks=VALUES(nav_break_blocks),nav_place_blocks=VALUES(nav_place_blocks)"
                : "INSERT OR REPLACE INTO"
                        + " fpp_active_bots(bot_uuid,bot_name,bot_display,spawned_by,spawned_by_uuid,"
                        + "world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,updated_at,server_id,"
                        + "frozen,pickup_items,pickup_xp,head_ai_enabled,nav_parkour,nav_break_blocks,nav_place_blocks)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, display);
            ps.setString(4, spawnedBy);
            ps.setString(5, spawnedByUuid);
            ps.setString(6, world);
            ps.setDouble(7, x);
            ps.setDouble(8, y);
            ps.setDouble(9, z);
            ps.setFloat(10, yaw);
            ps.setFloat(11, pitch);
            ps.setLong(12, now);
            ps.setString(13, serverId);
            ps.setBoolean(14, frozen);
            ps.setBoolean(15, pickUpItems);
            ps.setBoolean(16, pickUpXp);
            ps.setBoolean(17, headAiEnabled);
            ps.setBoolean(18, navParkour);
            ps.setBoolean(19, navBreakBlocks);
            ps.setBoolean(20, navPlaceBlocks);
            ps.executeUpdate();
        } catch (SQLException e) {
            FppLogger.error("DB upsertActiveBot: " + e.getMessage());
        }
    }

    private void batchUpdateLocationsSync(Map<String, PendingLocation> snapshot) {
        if (!isAlive() || snapshot.isEmpty()) return;
        long now = Instant.now().toEpochMilli();
        String s1 = "UPDATE fpp_bot_sessions SET"
                + " last_world=?,last_x=?,last_y=?,last_z=?,last_yaw=?,last_pitch=? WHERE"
                + " bot_uuid=? AND removed_at IS NULL";
        String s2 = "UPDATE fpp_active_bots SET"
                + " world_name=?,pos_x=?,pos_y=?,pos_z=?,pos_yaw=?,pos_pitch=?,updated_at=?"
                + " WHERE bot_uuid=?";
        try (PreparedStatement ps1 = connection.prepareStatement(s1);
                PreparedStatement ps2 = connection.prepareStatement(s2)) {
            for (Map.Entry<String, PendingLocation> e : snapshot.entrySet()) {
                PendingLocation l = e.getValue();
                ps1.setString(1, l.world);
                ps1.setDouble(2, l.x);
                ps1.setDouble(3, l.y);
                ps1.setDouble(4, l.z);
                ps1.setFloat(5, l.yaw);
                ps1.setFloat(6, l.pitch);
                ps1.setString(7, e.getKey());
                ps1.addBatch();
                ps2.setString(1, l.world);
                ps2.setDouble(2, l.x);
                ps2.setDouble(3, l.y);
                ps2.setDouble(4, l.z);
                ps2.setFloat(5, l.yaw);
                ps2.setFloat(6, l.pitch);
                ps2.setLong(7, now);
                ps2.setString(8, e.getKey());
                ps2.addBatch();
            }
            ps1.executeBatch();
            ps2.executeBatch();
            Config.debug("DB flushed " + snapshot.size() + " location(s).");
        } catch (SQLException e) {
            FppLogger.error("DB batchUpdateLocations: " + e.getMessage());
        }
    }

    private void exec(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            FppLogger.error("DB exec: " + e.getMessage());
        }
    }

    private void execSilent(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    private void enqueue(Runnable task) {
        writeExecutor.execute(() -> {
            synchronized (this) {
                try {
                    task.run();
                } catch (Exception e) {
                    FppLogger.error("DB write error: " + e.getMessage());
                }
            }
        });
    }

    private BotRecord mapSession(ResultSet rs) throws SQLException {
        long rm = rs.getLong("removed_at");
        Instant removedAt = rs.wasNull() ? null : Instant.ofEpochMilli(rm);

        double lx = rs.getDouble("last_x");
        if (rs.wasNull()) lx = rs.getDouble("spawn_x");
        double ly = rs.getDouble("last_y");
        if (rs.wasNull()) ly = rs.getDouble("spawn_y");
        double lz = rs.getDouble("last_z");
        if (rs.wasNull()) lz = rs.getDouble("spawn_z");
        String lw = rs.getString("last_world");
        if (lw == null) lw = rs.getString("world_name");
        float lyaw, lpitch;
        try {
            lyaw = rs.getFloat("last_yaw");
            if (rs.wasNull()) lyaw = rs.getFloat("spawn_yaw");
        } catch (SQLException ignored) {
            lyaw = 0f;
        }
        try {
            lpitch = rs.getFloat("last_pitch");
            if (rs.wasNull()) lpitch = rs.getFloat("spawn_pitch");
        } catch (SQLException ignored) {
            lpitch = 0f;
        }

        String sid = null;
        try {
            sid = rs.getString("server_id");
        } catch (SQLException ignored) {
        }
        if (sid == null || sid.isBlank()) sid = Config.serverId();

        return new BotRecord(
                rs.getLong("id"),
                rs.getString("bot_name"),
                UUID.fromString(rs.getString("bot_uuid")),
                rs.getString("spawned_by"),
                UUID.fromString(rs.getString("spawned_by_uuid")),
                rs.getString("world_name"),
                rs.getDouble("spawn_x"),
                rs.getDouble("spawn_y"),
                rs.getDouble("spawn_z"),
                rs.getFloat("spawn_yaw"),
                rs.getFloat("spawn_pitch"),
                lw,
                lx,
                ly,
                lz,
                lyaw,
                lpitch,
                Instant.ofEpochMilli(rs.getLong("spawned_at")),
                removedAt,
                rs.getString("remove_reason"),
                sid);
    }

    private void backfillIdentities() {
        if (!isAlive()) return;
        String sql = isMysql
                ? "INSERT IGNORE INTO"
                        + " fpp_bot_identities(bot_name,server_id,bot_uuid,created_at) SELECT"
                        + " bot_name, server_id, (SELECT s2.bot_uuid FROM fpp_bot_sessions s2"
                        + "  WHERE s2.bot_name=s1.bot_name AND s2.server_id=s1.server_id "
                        + " ORDER BY s2.spawned_at ASC LIMIT 1), MIN(spawned_at) FROM"
                        + " fpp_bot_sessions s1 GROUP BY bot_name, server_id"
                : "INSERT OR IGNORE INTO"
                        + " fpp_bot_identities(bot_name,server_id,bot_uuid,created_at) SELECT"
                        + " bot_name, server_id, (SELECT s2.bot_uuid FROM fpp_bot_sessions s2"
                        + "  WHERE s2.bot_name=s1.bot_name AND s2.server_id=s1.server_id "
                        + " ORDER BY s2.spawned_at ASC LIMIT 1), MIN(spawned_at) FROM"
                        + " fpp_bot_sessions s1 GROUP BY bot_name, server_id";
        try (Statement st = connection.createStatement()) {
            int rows = st.executeUpdate(sql);
            if (rows > 0) {
                FppLogger.info("Bot identity registry: backfilled "
                        + rows
                        + " identit"
                        + (rows == 1 ? "y" : "ies")
                        + " from session history.");
            }
        } catch (SQLException e) {

            FppLogger.debug("Bot identity backfill skipped (no session history yet): " + e.getMessage());
        }
    }

    public UUID lookupBotUuid(String botName, String serverId) {
        if (!isAlive()) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bot_uuid FROM fpp_bot_identities WHERE bot_name=? AND" + " server_id=?")) {
            ps.setString(1, botName);
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString(1);
                    if (raw != null && !raw.isBlank()) return UUID.fromString(raw);
                }
            }
        } catch (SQLException e) {
            FppLogger.error("DB lookupBotUuid(" + botName + "): " + e.getMessage());
        }
        return null;
    }

    public void registerBotUuid(String botName, UUID uuid, String serverId) {
        final String sid = serverId;
        final long now = Instant.now().toEpochMilli();
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = isMysql
                    ? "INSERT IGNORE INTO"
                            + " fpp_bot_identities(bot_name,server_id,bot_uuid,created_at)"
                            + " VALUES(?,?,?,?)"
                    : "INSERT OR IGNORE INTO"
                            + " fpp_bot_identities(bot_name,server_id,bot_uuid,created_at)"
                            + " VALUES(?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botName);
                ps.setString(2, sid);
                ps.setString(3, uuid.toString());
                ps.setLong(4, now);
                ps.executeUpdate();
                Config.debug("DB registered identity: " + botName + " → " + uuid + " (server=" + sid + ")");
            } catch (SQLException e) {
                FppLogger.error("DB registerBotUuid(" + botName + "): " + e.getMessage());
            }
        });
    }

    public record BotIdentityRow(String botName, String serverId, String botUuid, String createdAt) {}

    public List<BotIdentityRow> getBotIdentityRows() {
        List<BotIdentityRow> rows = new ArrayList<>();
        if (!isAlive()) return rows;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bot_name, server_id, bot_uuid, created_at FROM fpp_bot_identities")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new BotIdentityRow(
                        rs.getString("bot_name"),
                        rs.getString("server_id"),
                        rs.getString("bot_uuid"),
                        rs.getString("created_at")));
            }
        } catch (SQLException e) {
            FppLogger.error("DB getBotIdentityRows: " + e.getMessage());
        }
        return rows;
    }

    public boolean migrateBotUuid(String botName, String serverId, UUID oldUuid, UUID newUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fpp_bot_identities SET bot_uuid=? WHERE bot_name=? AND server_id=? AND bot_uuid=?")) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, botName);
            ps.setString(3, serverId);
            ps.setString(4, oldUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            FppLogger.error("DB migrateBotUuid(" + botName + "): " + e.getMessage());
            return false;
        }
    }

    public void mergeSessionRow(
            String botName,
            String botDisplay,
            String botUuid,
            String spawnedBy,
            String spawnedByUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            float spawnPitch,
            String lastWorld,
            double lastX,
            double lastY,
            double lastZ,
            float lastYaw,
            float lastPitch,
            String entityType,
            long spawnedAtMs,
            Long removedAtMs,
            String removeReason,
            String serverId) {

        if (!isAlive()) return;

        if (lastWorld == null) lastWorld = worldName;
        if (entityType == null) entityType = "MANNEQUIN";
        if (serverId == null || serverId.isBlank()) serverId = Config.serverId();

        String sql = isMysql
                ? "INSERT IGNORE INTO fpp_bot_sessions"
                        + "(bot_name,bot_display,bot_uuid,spawned_by,spawned_by_uuid,world_name,"
                        + "spawn_x,spawn_y,spawn_z,spawn_yaw,spawn_pitch,"
                        + "last_world,last_x,last_y,last_z,last_yaw,last_pitch,entity_type,spawned_at,removed_at,remove_reason,server_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT OR IGNORE INTO fpp_bot_sessions"
                        + "(bot_name,bot_display,bot_uuid,spawned_by,spawned_by_uuid,world_name,"
                        + "spawn_x,spawn_y,spawn_z,spawn_yaw,spawn_pitch,"
                        + "last_world,last_x,last_y,last_z,last_yaw,last_pitch,entity_type,spawned_at,removed_at,remove_reason,server_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, botName);
            ps.setString(2, botDisplay);
            ps.setString(3, botUuid);
            ps.setString(4, spawnedBy);
            ps.setString(5, spawnedByUuid);
            ps.setString(6, worldName);
            ps.setDouble(7, spawnX);
            ps.setDouble(8, spawnY);
            ps.setDouble(9, spawnZ);
            ps.setFloat(10, spawnYaw);
            ps.setFloat(11, spawnPitch);
            ps.setString(12, lastWorld);
            ps.setDouble(13, lastX);
            ps.setDouble(14, lastY);
            ps.setDouble(15, lastZ);
            ps.setFloat(16, lastYaw);
            ps.setFloat(17, lastPitch);
            ps.setString(18, entityType);
            ps.setLong(19, spawnedAtMs);
            if (removedAtMs != null) ps.setLong(20, removedAtMs);
            else ps.setNull(20, Types.BIGINT);
            ps.setString(21, removeReason);
            ps.setString(22, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            FppLogger.error("DB mergeSessionRow: " + e.getMessage());
        }
    }

    public void mergeActiveBotRow(
            String botUuid,
            String botName,
            String botDisplay,
            String spawnedBy,
            String spawnedByUuid,
            String worldName,
            double posX,
            double posY,
            double posZ,
            float posYaw,
            float posPitch,
            long updatedAt,
            String serverId) {

        if (!isAlive()) return;
        if (serverId == null || serverId.isBlank()) serverId = Config.serverId();

        String sql = isMysql
                ? "INSERT IGNORE INTO fpp_active_bots"
                        + "(bot_uuid,bot_name,bot_display,spawned_by,spawned_by_uuid,world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,updated_at,server_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT OR IGNORE INTO fpp_active_bots"
                        + "(bot_uuid,bot_name,bot_display,spawned_by,spawned_by_uuid,world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,updated_at,server_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, botUuid);
            ps.setString(2, botName);
            ps.setString(3, botDisplay);
            ps.setString(4, spawnedBy);
            ps.setString(5, spawnedByUuid);
            ps.setString(6, worldName);
            ps.setDouble(7, posX);
            ps.setDouble(8, posY);
            ps.setDouble(9, posZ);
            ps.setFloat(10, posYaw);
            ps.setFloat(11, posPitch);
            ps.setLong(12, updatedAt);
            ps.setString(13, serverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            FppLogger.error("DB mergeActiveBotRow: " + e.getMessage());
        }
    }



    public int countActiveBotRows() {
        if (!isAlive()) return 0;
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fpp_active_bots")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            FppLogger.error("DB countActiveBotRows: " + e.getMessage());
        }
        return 0;
    }

    public void updateBotAiPersonality(String uuid, String personality) {
        updateNullableString("ai_personality", uuid, personality, "DB updateBotAiPersonality");
    }

    public void updateBotPickupSettings(String uuid, boolean pickUpItems, boolean pickUpXp) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = "UPDATE fpp_active_bots SET pickup_items=?, pickup_xp=? WHERE" + " bot_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBoolean(1, pickUpItems);
                ps.setBoolean(2, pickUpXp);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotPickupSettings: " + e.getMessage());
            }
        });
    }

    public void updateBotFrozen(String uuid, boolean frozen) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = "UPDATE fpp_active_bots SET frozen=? WHERE bot_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBoolean(1, frozen);
                ps.setString(2, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotFrozen: " + e.getMessage());
            }
        });
    }

    public void updateBotChatSettings(String uuid, boolean chatEnabled, String chatTier) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fpp_active_bots SET chat_enabled=?,chat_tier=? WHERE bot_uuid=?")) {
                ps.setBoolean(1, chatEnabled);
                if (chatTier != null && !chatTier.isBlank()) ps.setString(2, chatTier);
                else ps.setNull(2, Types.VARCHAR);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotChatSettings: " + e.getMessage());
            }
        });
    }

    public void updateBotRightClickCommand(String uuid, String cmd) {
        updateNullableString("right_click_cmd", uuid, cmd, "DB updateBotRightClickCommand");
    }

    public void updateBotLuckPermsGroup(String uuid, String group) {
        updateNullableString("luckperms_group", uuid, group, "DB updateBotLuckPermsGroup");
    }

    public void updateBotPingSettings(String uuid, int ping, boolean userSet) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE fpp_active_bots SET ping=?,ping_user_set=? WHERE bot_uuid=?")) {
                ps.setInt(1, ping);
                ps.setBoolean(2, userSet);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotPingSettings: " + e.getMessage());
            }
        });
    }

    private void updateNullableString(String column, String uuid, String value, String context) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE fpp_active_bots SET " + column + "=? WHERE bot_uuid=?")) {
                if (value != null && !value.isBlank()) ps.setString(1, value);
                else ps.setNull(1, Types.VARCHAR);
                ps.setString(2, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error(context + ": " + e.getMessage());
            }
        });
    }

    public void updateBotHeadAiEnabled(String uuid, boolean enabled) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = "UPDATE fpp_active_bots SET head_ai_enabled=? WHERE bot_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBoolean(1, enabled);
                ps.setString(2, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotHeadAiEnabled: " + e.getMessage());
            }
        });
    }

    public void updateBotNavSettings(String uuid, boolean navParkour, boolean navBreakBlocks, boolean navPlaceBlocks) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = "UPDATE fpp_active_bots SET nav_parkour=?, nav_break_blocks=?,"
                    + " nav_place_blocks=? WHERE bot_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBoolean(1, navParkour);
                ps.setBoolean(2, navBreakBlocks);
                ps.setBoolean(3, navPlaceBlocks);
                ps.setString(4, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotNavSettings: " + e.getMessage());
            }
        });
    }

    public void updateBotAllSettings(
            String uuid,
            boolean frozen,
            boolean chatEnabled,
            String chatTier,
            String rightClickCmd,
            String aiPersonality,
            boolean pickUpItems,
            boolean pickUpXp,
            boolean headAiEnabled,
            boolean navParkour,
            boolean navBreakBlocks,
            boolean navPlaceBlocks,
            boolean navAvoidWater,
            boolean navAvoidLava,
            boolean swimAiEnabled,
            int chunkLoadRadius,
            int ping,
            boolean pveEnabled,
            double pveRange,
            String pvePriority,
            String pveMobType,
            String pveSmartAttackMode,
            boolean autoMilkEnabled,
            boolean preventBadOmen,
            boolean respawnOnDeath,
            boolean pingUserSet,
            String luckpermsGroup) {
        if (!isAlive()) return;
        final String pvePri = pvePriority, pveMob = pveMobType, pveMode = pveSmartAttackMode;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = "UPDATE fpp_active_bots SET frozen=?,pickup_items=?,pickup_xp=?,head_ai_enabled=?,"
                    + "nav_parkour=?,nav_break_blocks=?,nav_place_blocks=?,nav_avoid_water=?,nav_avoid_lava=?,swim_ai_enabled=?,chunk_load_radius=?,"
                    + "pve_enabled=?,pve_range=?,pve_priority=?,pve_mob_type=?,pve_smart_attack_mode=?,"
                    + "auto_milk_enabled=?,prevent_bad_omen=?,respawn_on_death=?,chat_enabled=?,chat_tier=?,right_click_cmd=?,"
                    + "ai_personality=?,ping=?,ping_user_set=?,luckperms_group=? WHERE bot_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBoolean(1, frozen);
                ps.setBoolean(2, pickUpItems);
                ps.setBoolean(3, pickUpXp);
                ps.setBoolean(4, headAiEnabled);
                ps.setBoolean(5, navParkour);
                ps.setBoolean(6, navBreakBlocks);
                ps.setBoolean(7, navPlaceBlocks);
                ps.setBoolean(8, navAvoidWater);
                ps.setBoolean(9, navAvoidLava);
                ps.setBoolean(10, swimAiEnabled);
                ps.setInt(11, chunkLoadRadius);
                ps.setBoolean(12, pveEnabled);
                ps.setDouble(13, pveRange);
                if (pvePri != null) ps.setString(14, pvePri);
                else ps.setNull(14, Types.VARCHAR);
                if (pveMob != null) ps.setString(15, pveMob);
                else ps.setNull(15, Types.VARCHAR);
                if (pveMode != null) ps.setString(16, pveMode);
                else ps.setString(16, "OFF");
                ps.setBoolean(17, autoMilkEnabled);
                ps.setBoolean(18, preventBadOmen);
                ps.setBoolean(19, respawnOnDeath);
                ps.setBoolean(20, chatEnabled);
                if (chatTier != null && !chatTier.isBlank()) ps.setString(21, chatTier);
                else ps.setNull(21, Types.VARCHAR);
                if (rightClickCmd != null && !rightClickCmd.isBlank()) ps.setString(22, rightClickCmd);
                else ps.setNull(22, Types.VARCHAR);
                if (aiPersonality != null && !aiPersonality.isBlank()) ps.setString(23, aiPersonality);
                else ps.setNull(23, Types.VARCHAR);
                ps.setInt(24, ping);
                ps.setBoolean(25, pingUserSet);
                if (luckpermsGroup != null && !luckpermsGroup.isBlank()) ps.setString(26, luckpermsGroup);
                else ps.setNull(26, Types.VARCHAR);
                ps.setString(27, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotAllSettings: " + e.getMessage());
            }
        });
    }

    public void updateBotSkin(String uuid, String skinTexture, String skinSignature) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fpp_active_bots SET skin_texture=?,skin_signature=? WHERE bot_uuid=?")) {
                if (skinTexture != null && !skinTexture.isBlank()) ps.setString(1, skinTexture);
                else ps.setNull(1, Types.CLOB);
                if (skinSignature != null && !skinSignature.isBlank()) ps.setString(2, skinSignature);
                else ps.setNull(2, Types.CLOB);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotSkin: " + e.getMessage());
            }
        });
    }

    public void updateBotDisplay(String uuid, String display) {
        updateNullableString("bot_display", uuid, display, "DB updateBotDisplay");
    }

    /** {@code null} clears the rental expiry (bot becomes permanent / not rented). */
    public void updateBotRentalExpiry(String uuid, @Nullable Long rentalExpiresAt) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE fpp_active_bots SET rental_expires_at=? WHERE bot_uuid=?")) {
                if (rentalExpiresAt != null) ps.setLong(1, rentalExpiresAt);
                else ps.setNull(1, Types.BIGINT);
                ps.setString(2, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotRentalExpiry: " + e.getMessage());
            }
        });
    }

    /** Persists a bot's rental auto-renew choice; {@code hours <= 0} removes it. */
    public void setRentalAutoRenew(String botUuid, int hours) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement del =
                    connection.prepareStatement("DELETE FROM fpp_rental_auto WHERE bot_uuid=?")) {
                del.setString(1, botUuid);
                del.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB setRentalAutoRenew(delete): " + e.getMessage());
                return;
            }
            if (hours <= 0) return;
            try (PreparedStatement ins =
                    connection.prepareStatement("INSERT INTO fpp_rental_auto (bot_uuid,hours) VALUES (?,?)")) {
                ins.setString(1, botUuid);
                ins.setInt(2, hours);
                ins.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB setRentalAutoRenew(insert): " + e.getMessage());
            }
        });
    }

    /** Synchronous read of every bot's auto-renew hours, keyed by bot UUID string. */
    public Map<String, Integer> loadRentalAutoRenew() {
        Map<String, Integer> out = new java.util.HashMap<>();
        if (!isAlive()) return out;
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT bot_uuid,hours FROM fpp_rental_auto")) {
            while (rs.next()) out.put(rs.getString("bot_uuid"), rs.getInt("hours"));
        } catch (SQLException e) {
            FppLogger.error("DB loadRentalAutoRenew: " + e.getMessage());
        }
        return out;
    }

    /** {@code -1} for either value means "use the config default" - matches FakePlayer's own sentinel. */
    public void updateBotClickIntervals(String uuid, int leftClickIntervalTicks, int rightClickIntervalTicks) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fpp_active_bots SET left_click_interval_ticks=?,right_click_interval_ticks=? WHERE bot_uuid=?")) {
                ps.setInt(1, leftClickIntervalTicks);
                ps.setInt(2, rightClickIntervalTicks);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotClickIntervals: " + e.getMessage());
            }
        });
    }

    public void updateBotOwner(String uuid, String ownerName, String ownerUuid) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fpp_active_bots SET spawned_by=?,spawned_by_uuid=? WHERE bot_uuid=?")) {
                ps.setString(1, ownerName);
                ps.setString(2, ownerUuid);
                ps.setString(3, uuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB updateBotOwner: " + e.getMessage());
            }
        });
    }

    public boolean isMysql() {
        return isMysql;
    }



    public void setBotExtensionData(String botUuid, String extensionKey, String dataKey, String dataValue) {
        if (!isAlive()) return;
        final String ext = normalizeExtensionKey(extensionKey);
        final String key = dataKey != null ? dataKey.trim() : "";
        if (botUuid == null || botUuid.isBlank() || ext.isBlank() || key.isBlank()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = isMysql
                    ? "INSERT INTO fpp_bot_extension_data(bot_uuid,extension_key,data_key,data_value,updated_at)"
                            + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE data_value=VALUES(data_value),updated_at=VALUES(updated_at)"
                    : "INSERT OR REPLACE INTO fpp_bot_extension_data(bot_uuid,extension_key,data_key,data_value,updated_at)"
                            + " VALUES(?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botUuid);
                ps.setString(2, ext);
                ps.setString(3, key);
                if (dataValue != null) ps.setString(4, dataValue);
                else ps.setNull(4, Types.CLOB);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB setBotExtensionData: " + e.getMessage());
            }
        });
    }

    public void removeBotExtensionData(String botUuid, String extensionKey, String dataKey) {
        if (!isAlive()) return;
        final String ext = normalizeExtensionKey(extensionKey);
        final String key = dataKey != null ? dataKey.trim() : "";
        if (botUuid == null || botUuid.isBlank() || ext.isBlank() || key.isBlank()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM fpp_bot_extension_data WHERE bot_uuid=? AND extension_key=? AND data_key=?")) {
                ps.setString(1, botUuid);
                ps.setString(2, ext);
                ps.setString(3, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB removeBotExtensionData: " + e.getMessage());
            }
        });
    }

    public Map<String, String> loadBotExtensionData(String botUuid, String extensionKey) {
        Map<String, String> out = new HashMap<>();
        if (!isAlive()) return out;
        String ext = normalizeExtensionKey(extensionKey);
        if (botUuid == null || botUuid.isBlank() || ext.isBlank()) return out;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data_key,data_value FROM fpp_bot_extension_data WHERE bot_uuid=? AND extension_key=?")) {
            ps.setString(1, botUuid);
            ps.setString(2, ext);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString("data_key"), rs.getString("data_value"));
            }
        } catch (SQLException e) {
            FppLogger.error("DB loadBotExtensionData: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Map<String, String>> loadAllBotExtensionData(String botUuid) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (!isAlive() || botUuid == null || botUuid.isBlank()) return out;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT extension_key,data_key,data_value FROM fpp_bot_extension_data WHERE bot_uuid=?")) {
            ps.setString(1, botUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString("extension_key"), ignored -> new HashMap<>())
                            .put(rs.getString("data_key"), rs.getString("data_value"));
                }
            }
        } catch (SQLException e) {
            FppLogger.error("DB loadAllBotExtensionData: " + e.getMessage());
        }
        return out;
    }

    private static String normalizeExtensionKey(String extensionKey) {
        return extensionKey == null ? "" : extensionKey.trim().toLowerCase(Locale.ROOT);
    }

    public void saveBotTasks(List<BotTaskRow> rows) {
        if (!isAlive()) return;
        final List<BotTaskRow> snap = new ArrayList<>(rows);
        final String sid = Config.serverId();
        enqueue(() -> {
            if (!isAlive()) return;

            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_bot_tasks WHERE server_id=?")) {
                ps.setString(1, sid);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB saveBotTasks (clear): " + e.getMessage());
                return;
            }
            if (snap.isEmpty()) return;

            String sql = isMysql
                    ? "INSERT INTO fpp_bot_tasks"
                            + " (bot_uuid,server_id,task_type,world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,once_flag,extra_str,extra_bool)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                            + " world_name=VALUES(world_name),pos_x=VALUES(pos_x),"
                            + "pos_y=VALUES(pos_y),pos_z=VALUES(pos_z),pos_yaw=VALUES(pos_yaw),"
                            + "pos_pitch=VALUES(pos_pitch),once_flag=VALUES(once_flag),"
                            + "extra_str=VALUES(extra_str),extra_bool=VALUES(extra_bool)"
                    : "INSERT OR REPLACE INTO fpp_bot_tasks"
                            + " (bot_uuid,server_id,task_type,world_name,pos_x,pos_y,pos_z,pos_yaw,pos_pitch,once_flag,extra_str,extra_bool)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BotTaskRow row : snap) {
                    ps.setString(1, row.botUuid());
                    ps.setString(2, row.serverId());
                    ps.setString(3, row.taskType());
                    if (row.worldName() != null) ps.setString(4, row.worldName());
                    else ps.setNull(4, Types.VARCHAR);
                    ps.setDouble(5, row.posX());
                    ps.setDouble(6, row.posY());
                    ps.setDouble(7, row.posZ());
                    ps.setFloat(8, row.posYaw());
                    ps.setFloat(9, row.posPitch());
                    ps.setBoolean(10, row.onceFlag());
                    if (row.extraStr() != null) ps.setString(11, row.extraStr());
                    else ps.setNull(11, Types.VARCHAR);
                    ps.setBoolean(12, row.extraBool());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                FppLogger.error("DB saveBotTasks (insert): " + e.getMessage());
            }
            Config.debug("DB saved " + snap.size() + " bot task row(s) for server='" + sid + "'.");
        });
    }

    public List<BotTaskRow> loadBotTasksForThisServer() {
        List<BotTaskRow> list = new ArrayList<>();
        if (!isAlive()) return list;
        String sql = "SELECT * FROM fpp_bot_tasks WHERE server_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapBotTaskRow(rs));
            }
        } catch (SQLException e) {
            FppLogger.error("DB loadBotTasksForThisServer: " + e.getMessage());
        }
        return list;
    }

    public void clearBotTasks() {
        final String sid = Config.serverId();
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_bot_tasks WHERE server_id=?")) {
                ps.setString(1, sid);
                int rows = ps.executeUpdate();
                Config.debug("DB cleared " + rows + " bot task row(s) for server='" + sid + "'.");
            } catch (SQLException e) {
                FppLogger.error("DB clearBotTasks: " + e.getMessage());
            }
        });
    }

    // ── Despawn snapshots ────────────────────────────────────────────────────

    /**
     * Saves (upserts) a despawn snapshot for a bot so it can be restored on the next same-name
     * spawn, even across server restarts. {@code inventoryData} is the pipe-separated slot encoding
     * produced by {@code FakePlayerManager.serializeSlots()}.
     */
    public void saveDespawnSnapshot(
            String botName,
            String serverId,
            String inventoryData,
            int xpTotal,
            int xpLevel,
            float xpProgress,
            String skinTexture,
            String skinSignature) {
        if (!isAlive()) return;
        final String skinTex = skinTexture;
        final String skinSig = skinSignature;
        final long savedAt = System.currentTimeMillis();
        enqueue(() -> {
            if (!isAlive()) return;
            String sql = isMysql
                    ? "INSERT INTO fpp_despawn_snapshots"
                            + " (bot_name,server_id,inventory_data,xp_total,xp_level,xp_progress,skin_texture,skin_signature,saved_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                            + " inventory_data=VALUES(inventory_data),xp_total=VALUES(xp_total),"
                            + " xp_level=VALUES(xp_level),xp_progress=VALUES(xp_progress),"
                            + " skin_texture=VALUES(skin_texture),skin_signature=VALUES(skin_signature),saved_at=VALUES(saved_at)"
                    : "INSERT OR REPLACE INTO fpp_despawn_snapshots"
                            + " (bot_name,server_id,inventory_data,xp_total,xp_level,xp_progress,skin_texture,skin_signature,saved_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, botName);
                ps.setString(2, serverId);
                ps.setString(3, inventoryData);
                ps.setInt(4, xpTotal);
                ps.setInt(5, xpLevel);
                ps.setFloat(6, xpProgress);
                if (skinTex != null) ps.setString(7, skinTex);
                else ps.setNull(7, Types.CLOB);
                if (skinSig != null) ps.setString(8, skinSig);
                else ps.setNull(8, Types.CLOB);
                ps.setLong(9, savedAt);
                ps.executeUpdate();
                Config.debugDatabase("DB saved despawn snapshot for '" + botName + "' on server '" + serverId + "'.");
            } catch (SQLException e) {
                FppLogger.error("DB saveDespawnSnapshot: " + e.getMessage());
            }
        });
    }

    /**
     * Removes the despawn snapshot for a bot (called after inventory is restored on respawn).
     */
    public void deleteDespawnSnapshot(String botName, String serverId) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps =
                    connection.prepareStatement("DELETE FROM fpp_despawn_snapshots WHERE bot_name=? AND server_id=?")) {
                ps.setString(1, botName);
                ps.setString(2, serverId);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB deleteDespawnSnapshot: " + e.getMessage());
            }
        });
    }

    /**
     * Synchronous read - returns all despawn snapshots for the current server. Called once at
     * startup to re-populate the in-memory map after a restart.
     */
    public List<DespawnSnapshotRow> loadDespawnSnapshotsForServer(String serverId) {
        List<DespawnSnapshotRow> list = new ArrayList<>();
        if (!isAlive()) return list;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bot_name,server_id,inventory_data,xp_total,xp_level,xp_progress,skin_texture,skin_signature,saved_at"
                        + " FROM fpp_despawn_snapshots WHERE server_id=?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DespawnSnapshotRow(
                            rs.getString("bot_name"),
                            rs.getString("server_id"),
                            rs.getString("inventory_data"),
                            rs.getInt("xp_total"),
                            rs.getInt("xp_level"),
                            rs.getFloat("xp_progress"),
                            rs.getString("skin_texture"),
                            rs.getString("skin_signature"),
                            rs.getLong("saved_at")));
                }
            }
        } catch (SQLException e) {
            FppLogger.error("DB loadDespawnSnapshotsForServer: " + e.getMessage());
        }
        return list;
    }

    private BotTaskRow mapBotTaskRow(ResultSet rs) throws SQLException {
        String worldName = null;
        try {
            worldName = rs.getString("world_name");
        } catch (SQLException ignored) {
        }
        String extraStr = null;
        try {
            extraStr = rs.getString("extra_str");
        } catch (SQLException ignored) {
        }
        boolean extraBool = false;
        try {
            extraBool = rs.getBoolean("extra_bool");
        } catch (SQLException ignored) {
        }
        return new BotTaskRow(
                rs.getString("bot_uuid"),
                rs.getString("server_id"),
                rs.getString("task_type"),
                worldName,
                rs.getDouble("pos_x"),
                rs.getDouble("pos_y"),
                rs.getDouble("pos_z"),
                rs.getFloat("pos_yaw"),
                rs.getFloat("pos_pitch"),
                rs.getBoolean("once_flag"),
                extraStr,
                extraBool);
    }

    private record PendingLocation(String world, double x, double y, double z, float yaw, float pitch) {}

    public record ActiveBotRow(
            String botUuid,
            String botName,
            String botDisplay,
            String spawnedBy,
            String spawnedByUuid,
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String luckpermsGroup,
            String serverId,
            String aiPersonality,
            boolean pickUpItems,
            boolean pickUpXp,
            boolean frozen,
            boolean chatEnabled,
            String chatTier,
            String rightClickCmd,
            boolean headAiEnabled,
            boolean navParkour,
            boolean navBreakBlocks,
            boolean navPlaceBlocks,
            boolean navAvoidWater,
            boolean navAvoidLava,
            boolean swimAiEnabled,
            int chunkLoadRadius,
            int ping,
            boolean pveEnabled,
            double pveRange,
            String pvePriority,
            String pveMobType,
            String pveSmartAttackMode,
            String skinTexture,
            String skinSignature,
            boolean autoMilkEnabled,
            boolean preventBadOmen,
            boolean respawnOnDeath,
            boolean pingUserSet,
            @Nullable Long rentalExpiresAt,
            int leftClickIntervalTicks,
            int rightClickIntervalTicks) {}

    public record DespawnSnapshotRow(
            String botName,
            String serverId,
            String inventoryData,
            int xpTotal,
            int xpLevel,
            float xpProgress,
            String skinTexture,
            String skinSignature,
            long savedAt) {}

    public record BotTaskRow(
            String botUuid,
            String serverId,
            String taskType,
            String worldName,
            double posX,
            double posY,
            double posZ,
            float posYaw,
            float posPitch,
            boolean onceFlag,
            String extraStr,
            boolean extraBool) {}

    public record DbStats(
            int totalSessions,
            int activeSessions,
            int uniqueBots,
            int uniqueSpawners,
            long totalUptimeMs,
            String backend) {

        public String formattedUptime() {
            long secs = totalUptimeMs / 1000;
            long days = secs / 86400;
            long hours = (secs % 86400) / 3600;
            long mins = (secs % 3600) / 60;
            if (days > 0) return days + "d " + hours + "h " + mins + "m";
            if (hours > 0) return hours + "h " + mins + "m";
            return mins + "m";
        }
    }

    // ── Bot auth credentials (encrypted password, keyed by bot name) ───────────────────────────
    // Written/read by auth.BotAuthManager - see that class's own doc for the full register/login
    // flow. The password itself is AES-GCM encrypted (auth.AuthCipher) before it ever reaches this
    // table, keyed by a secret that lives only in auth.key on disk, never in the database itself.

    public record BotAuthRow(String passwordEncrypted, long createdAt, long updatedAt) {}

    /** Blocking - callers off the main thread only (see BotAuthManager#handleBotJoin). {@code null} if nothing's on record for {@code name}. */
    public BotAuthRow getBotAuth(String name) {
        if (!isAlive()) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password_enc, created_at, updated_at FROM fpp_bot_auth WHERE bot_name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new BotAuthRow(
                            rs.getString("password_enc"), rs.getLong("created_at"), rs.getLong("updated_at"));
                }
            }
        } catch (SQLException e) {
            FppLogger.error("DB getBotAuth: " + e.getMessage());
        }
        return null;
    }

    /** Fire-and-forget, like the other update* methods - {@code onComplete} (main-thread) fires once the write has actually landed, so a caller that needs to guarantee "persisted before used" (see BotAuthManager#registerNew) can wait for it instead of racing the write. */
    public void upsertBotAuth(String name, String passwordEncrypted, @Nullable Runnable onComplete) {
        if (!isAlive()) {
            if (onComplete != null && plugin != null) Bukkit.getScheduler().runTask(plugin, onComplete);
            return;
        }
        long now = System.currentTimeMillis();
        enqueue(() -> {
            if (isAlive()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        isMysql
                                ? "INSERT INTO fpp_bot_auth (bot_name, password_enc, created_at, updated_at) VALUES (?,?,?,?) "
                                        + "ON DUPLICATE KEY UPDATE password_enc=VALUES(password_enc), updated_at=VALUES(updated_at)"
                                : "INSERT INTO fpp_bot_auth (bot_name, password_enc, created_at, updated_at) VALUES (?,?,?,?) "
                                        + "ON CONFLICT(bot_name) DO UPDATE SET password_enc=excluded.password_enc,"
                                        + " updated_at=excluded.updated_at")) {
                    ps.setString(1, name);
                    ps.setString(2, passwordEncrypted);
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    FppLogger.error("DB upsertBotAuth: " + e.getMessage());
                }
            }
            if (onComplete != null && plugin != null) Bukkit.getScheduler().runTask(plugin, onComplete);
        });
    }

    public void deleteBotAuth(String name) {
        if (!isAlive()) return;
        enqueue(() -> {
            if (!isAlive()) return;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM fpp_bot_auth WHERE bot_name=?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                FppLogger.error("DB deleteBotAuth: " + e.getMessage());
            }
        });
    }
}
