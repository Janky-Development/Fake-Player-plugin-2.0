package me.bill.fakePlayerPlugin.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;

public final class YamlFileSyncer {

    private YamlFileSyncer() {}

    public record SyncResult(String fileName, List<String> keysAdded) {



        public int count() {
            return keysAdded.size();
        }
    }

    public static SyncResult syncMissingKeys(FakePlayerPlugin plugin, String diskRelPath, String jarResourcePath) {
        File diskFile = new File(plugin.getDataFolder(), diskRelPath);
        String fileName = diskFile.getName();

        if (!diskFile.exists()) {
            diskFile.getParentFile().mkdirs();
            plugin.saveResource(jarResourcePath, false);
            FppLogger.debug("YamlFileSyncer: extracted " + fileName + " from JAR (first run).");
            return new SyncResult(fileName, List.of());
        }

        InputStream jarStream = plugin.getResource(jarResourcePath);
        if (jarStream == null) {
            FppLogger.warn("YamlFileSyncer: JAR resource not found: " + jarResourcePath);
            return new SyncResult(fileName, List.of());
        }

        YamlConfiguration jarCfg;
        YamlConfiguration diskCfg;
        try (jarStream) {
            jarCfg = YamlConfiguration.loadConfiguration(new InputStreamReader(jarStream, StandardCharsets.UTF_8));
            diskCfg = YamlConfiguration.loadConfiguration(diskFile);
        } catch (IOException e) {
            FppLogger.warn("YamlFileSyncer: failed to read files for " + fileName + ": " + e.getMessage());
            return new SyncResult(fileName, List.of());
        } catch (RuntimeException e) {

            FppLogger.warn("YamlFileSyncer: could not parse disk file '"
                    + fileName
                    + "' - it may be corrupted or contain unknown serializable types"
                    + " (e.g. from another plugin). Skipping sync. Cause: "
                    + e.getMessage());
            return new SyncResult(fileName, List.of());
        }

        List<String> missing = new ArrayList<>();
        for (String key : jarCfg.getKeys(true)) {
            if (jarCfg.isConfigurationSection(key)) continue;
            if (!diskCfg.contains(key)) missing.add(key);
        }

        if (missing.isEmpty()) {
            FppLogger.debug("YamlFileSyncer: " + fileName + " is up to date - no keys missing.");
            return new SyncResult(fileName, List.of());
        }

        for (String key : missing) {
            diskCfg.set(key, jarCfg.get(key));
        }

        try {
            diskCfg.save(diskFile);
        } catch (IOException e) {
            FppLogger.warn("YamlFileSyncer: failed to save " + fileName + ": " + e.getMessage());
            return new SyncResult(fileName, List.of());
        }

        FppLogger.info("YamlFileSyncer: "
                + fileName
                + " - added "
                + missing.size()
                + " new key(s) from JAR defaults: "
                + String.join(", ", missing));
        return new SyncResult(fileName, Collections.unmodifiableList(missing));
    }

    public static int countMissingKeys(FakePlayerPlugin plugin, String diskRelPath, String jarResourcePath) {
        File diskFile = new File(plugin.getDataFolder(), diskRelPath);
        if (!diskFile.exists()) return -1;

        InputStream jarStream = plugin.getResource(jarResourcePath);
        if (jarStream == null) return 0;

        YamlConfiguration jarCfg;
        YamlConfiguration diskCfg;
        try (jarStream) {
            jarCfg = YamlConfiguration.loadConfiguration(new InputStreamReader(jarStream, StandardCharsets.UTF_8));
            diskCfg = YamlConfiguration.loadConfiguration(diskFile);
        } catch (IOException e) {
            return 0;
        } catch (RuntimeException e) {
            FppLogger.warn("YamlFileSyncer: could not parse disk file for countMissingKeys"
                    + " - skipping. Cause: "
                    + e.getMessage());
            return 0;
        }

        int count = 0;
        for (String key : jarCfg.getKeys(true)) {
            if (jarCfg.isConfigurationSection(key)) continue;
            if (!diskCfg.contains(key)) count++;
        }
        return count;
    }


}
