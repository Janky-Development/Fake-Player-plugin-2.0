package me.bill.fakePlayerPlugin.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.file.YamlConfiguration;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.DatabaseManager;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;

public final class ConfigSyncManager {

    private final FakePlayerPlugin plugin;
    private final DatabaseManager db;

    private final Map<String, Long> knownVersions = new ConcurrentHashMap<>();

    private static final List<String> SYNCABLE_FILES = List.of("config.yml", "bot-names.yml", "language/en.yml");

    private static final Set<String> SERVER_SPECIFIC_KEYS = Set.of(
            "database.server-id",
            "server.id",
            "database.mysql.host",
            "database.mysql.port",
            "database.mysql.database",
            "database.mysql.username",
            "database.mysql.password",
            "database.mysql.use-ssl",
            "database.mysql.pool-size",
            "database.mysql.connection-timeout",
            "debug");

    public ConfigSyncManager(FakePlayerPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void init() {
        if (db == null || !Config.isNetworkMode()) {
            Config.debugConfigSync("[ConfigSync] Disabled (not in NETWORK mode or DB unavailable).");
            return;
        }

        createTables();
        Config.debugConfigSync("[ConfigSync] Initialized. Mode: " + Config.configSyncMode());

        if (Config.configSyncMode().equalsIgnoreCase("AUTO_PULL")
                || Config.configSyncMode().equalsIgnoreCase("AUTO_PUSH")) {
            FppScheduler.runSyncLater(
                    plugin,
                    () -> {
                        try {
                            pullAll(true);
                            Config.debugConfigSync("[ConfigSync] Auto-pulled latest configs from" + " network.");
                        } catch (Exception e) {
                            FppLogger.warn("[ConfigSync] Auto-pull failed: " + e.getMessage());
                        }
                    },
                    40L);
        }
    }

    private void createTables() {
        String sql = db.isMysql()
                ? "CREATE TABLE IF NOT EXISTS fpp_config_sync ("
                        + "  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "  config_file   VARCHAR(128) NOT NULL,"
                        + "  server_id     VARCHAR(64)  NOT NULL,"
                        + "  content_hash  VARCHAR(64)  NOT NULL,"
                        + "  content       LONGTEXT     NOT NULL,"
                        + "  pushed_at     BIGINT       NOT NULL,"
                        + "  pushed_by     VARCHAR(64)  NOT NULL,"
                        + "  INDEX idx_config_file (config_file),"
                        + "  INDEX idx_server_id (server_id),"
                        + "  INDEX idx_pushed_at (pushed_at)"
                        + ")"
                : "CREATE TABLE IF NOT EXISTS fpp_config_sync ("
                        + "  id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "  config_file   VARCHAR(128) NOT NULL,"
                        + "  server_id     VARCHAR(64)  NOT NULL,"
                        + "  content_hash  VARCHAR(64)  NOT NULL,"
                        + "  content       TEXT         NOT NULL,"
                        + "  pushed_at     BIGINT       NOT NULL,"
                        + "  pushed_by     VARCHAR(64)  NOT NULL"
                        + ")";

        try (Connection conn = db.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            Config.debugConfigSync("[ConfigSync] Database table created/verified.");
        } catch (SQLException e) {
            FppLogger.error("[ConfigSync] Failed to create table: " + e.getMessage());
        }
    }

    public boolean push(String fileName, String pushedBy) {
        if (!SYNCABLE_FILES.contains(fileName)) {
            FppLogger.warn("[ConfigSync] File '" + fileName + "' is not syncable.");
            return false;
        }

        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            FppLogger.warn("[ConfigSync] File not found: " + fileName);
            return false;
        }

        try {
            String content = readFile(file);

            if (fileName.equals("config.yml")) {
                content = stripServerSpecificKeys(content);
            }

            String hash = computeHash(content);
            long now = Instant.now().toEpochMilli();

            String sql = "INSERT INTO fpp_config_sync (config_file, server_id, content_hash, content,"
                    + " pushed_at, pushed_by) VALUES (?, ?, ?, ?, ?, ?)";

            try (Connection conn = db.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fileName);
                ps.setString(2, Config.serverId());
                ps.setString(3, hash);
                ps.setString(4, content);
                ps.setLong(5, now);
                ps.setString(6, pushedBy);
                ps.executeUpdate();
            }

            knownVersions.put(fileName, now);
            FppLogger.info("Config sync: pushed '" + fileName + "'.");

            var vc = plugin.getVelocityChannel();
            if (vc != null) {
                FppScheduler.runSync(plugin, () -> vc.broadcastConfigUpdated(fileName));
            }

            return true;

        } catch (Exception e) {
            FppLogger.error("[ConfigSync] Push failed for '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    public int pushAll(String pushedBy) {
        int count = 0;
        for (String fileName : SYNCABLE_FILES) {
            if (push(fileName, pushedBy)) count++;
        }
        return count;
    }

    public boolean pull(String fileName, boolean silent) {
        if (!SYNCABLE_FILES.contains(fileName)) {
            if (!silent) FppLogger.warn("[ConfigSync] File '" + fileName + "' is not syncable.");
            return false;
        }

        try {

            String sql = "SELECT content, content_hash, pushed_at, server_id, pushed_by "
                    + "FROM fpp_config_sync "
                    + "WHERE config_file = ? "
                    + "ORDER BY pushed_at DESC LIMIT 1";

            String content = null;
            String hash = null;
            long pushedAt = 0;
            String sourceServerId = null;
            String pushedBy = null;

            try (Connection conn = db.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fileName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        content = rs.getString("content");
                        hash = rs.getString("content_hash");
                        pushedAt = rs.getLong("pushed_at");
                        sourceServerId = rs.getString("server_id");
                        pushedBy = rs.getString("pushed_by");
                    }
                }
            }

            if (content == null) {
                if (!silent) FppLogger.info("Config sync: no network version found for '" + fileName + "'.");
                return false;
            }

            Long known = knownVersions.get(fileName);
            if (known != null && known >= pushedAt) {
                if (!silent) Config.debugConfigSync("[ConfigSync] Already have latest version of '" + fileName + "'.");
                return false;
            }

            File file = new File(plugin.getDataFolder(), fileName);
            if (file.exists()) {
                File backup = new File(plugin.getDataFolder(), fileName + ".sync-backup");
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (fileName.equals("config.yml")) {
                content = mergeServerSpecificKeys(content, file);
            }

            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }

            knownVersions.put(fileName, pushedAt);
            if (!silent) {
                FppLogger.info("Config sync: pulled '"
                        + fileName
                        + "' from "
                        + sourceServerId
                        + " (pushed by "
                        + pushedBy
                        + ").");
            }
            return true;

        } catch (Exception e) {
            FppLogger.error("[ConfigSync] Pull failed for '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    public int pullAll(boolean silent) {
        int count = 0;
        for (String fileName : SYNCABLE_FILES) {
            if (pull(fileName, silent)) count++;
        }
        return count;
    }







    private String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String stripServerSpecificKeys(String yamlContent) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(yamlContent);

            for (String key : SERVER_SPECIFIC_KEYS) {
                yaml.set(key, null);
            }

            return yaml.saveToString();
        } catch (Exception e) {
            FppLogger.warn("[ConfigSync] Failed to strip keys: " + e.getMessage());
            return yamlContent;
        }
    }

    private String mergeServerSpecificKeys(String networkContent, File localFile) {
        try {

            YamlConfiguration networkYaml = new YamlConfiguration();
            networkYaml.loadFromString(networkContent);

            if (localFile.exists()) {
                YamlConfiguration localYaml = YamlConfiguration.loadConfiguration(localFile);

                for (String key : SERVER_SPECIFIC_KEYS) {
                    Object localValue = localYaml.get(key);
                    if (localValue != null) {
                        networkYaml.set(key, localValue);
                    }
                }
            }

            return networkYaml.saveToString();
        } catch (Exception e) {
            FppLogger.warn("[ConfigSync] Failed to merge keys: " + e.getMessage());
            return networkContent;
        }
    }

    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    public record SyncStatus(String fileName, String hash, long pushedAt, String serverId, String pushedBy) {



    }
}
