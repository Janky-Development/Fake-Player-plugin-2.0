package me.bill.fakePlayerPlugin.command;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import me.bill.fakePlayerPlugin.util.BotDataYaml;

public final class StorageStore {

    private static final String ROOT = "storages.by-bot";

    private final JavaPlugin plugin;

    private final Map<String, LinkedHashMap<String, StoragePoint>> storages = new ConcurrentHashMap<>();
    private File file;

    public StorageStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "data/storages.yml");
        YamlConfiguration unified = BotDataYaml.load(plugin);
        ConfigurationSection root = unified.getConfigurationSection(ROOT);
        if (root == null && file.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
            root = legacy;
            if (!legacy.getKeys(false).isEmpty()) {
                final YamlConfiguration migrated = legacy;
                try {
                    BotDataYaml.replaceSection(plugin, ROOT, section -> {
                        for (String botKey : migrated.getKeys(false)) {
                            ConfigurationSection botSec = migrated.getConfigurationSection(botKey);
                            if (botSec == null) continue;
                            for (String storageKey : botSec.getKeys(false)) {
                                ConfigurationSection sec = botSec.getConfigurationSection(storageKey);
                                if (sec == null) continue;
                                for (String key : sec.getKeys(false)) {
                                    section.set(botKey + "." + storageKey + "." + key, sec.get(key));
                                }
                            }
                        }
                    });
                    if (file.exists()) file.delete();
                } catch (IOException ex) {
                    plugin.getLogger().warning("[FPP] Failed to migrate storages.yml: " + ex.getMessage());
                }
            }
        }
        if (root == null) return;

        for (String botKey : root.getKeys(false)) {
            ConfigurationSection botSec = root.getConfigurationSection(botKey);
            if (botSec == null) continue;

            LinkedHashMap<String, StoragePoint> botMap = new LinkedHashMap<>();
            for (String storageKey : botSec.getKeys(false)) {
                ConfigurationSection sec = botSec.getConfigurationSection(storageKey);
                if (sec == null) continue;
                String worldName = sec.getString("world");
                World world = worldName != null ? Bukkit.getWorld(worldName) : null;
                if (world == null) continue;
                Location loc = new Location(
                        world,
                        sec.getDouble("x"),
                        sec.getDouble("y"),
                        sec.getDouble("z"),
                        (float) sec.getDouble("yaw"),
                        (float) sec.getDouble("pitch"));
                String displayName = sec.getString("display-name", storageKey);
                boolean enabled = sec.getBoolean("enabled", true);
                botMap.put(storageKey.toLowerCase(Locale.ROOT), new StoragePoint(displayName, loc, enabled));
            }
            if (!botMap.isEmpty()) {
                storages.put(botKey.toLowerCase(Locale.ROOT), botMap);
            }
        }
    }

    public void save() {
        try {
            BotDataYaml.replaceSection(plugin, ROOT, section -> {
                for (Map.Entry<String, LinkedHashMap<String, StoragePoint>> botEntry : storages.entrySet()) {
                    String botKey = botEntry.getKey();
                    for (Map.Entry<String, StoragePoint> storageEntry :
                            botEntry.getValue().entrySet()) {
                        String key = botKey + "." + storageEntry.getKey() + ".";
                        StoragePoint point = storageEntry.getValue();
                        Location loc = point.location();
                        section.set(key + "display-name", point.name());
                        section.set(
                                key + "world",
                                loc.getWorld() != null ? loc.getWorld().getName() : null);
                        section.set(key + "x", loc.getX());
                        section.set(key + "y", loc.getY());
                        section.set(key + "z", loc.getZ());
                        section.set(key + "yaw", (double) loc.getYaw());
                        section.set(key + "pitch", (double) loc.getPitch());
                        section.set(key + "enabled", point.enabled());
                    }
                }
            });
            if (file != null && file.exists()) file.delete();
        } catch (IOException ex) {
            plugin.getLogger()
                    .warning("[FPP] Could not save " + BotDataYaml.FILE_NAME + " storages section: " + ex.getMessage());
        }
    }

    public void setStorage(String botName, String storageName, Location loc) {
        String botKey = botName.toLowerCase(Locale.ROOT);
        String storageKey = storageName.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, StoragePoint> botMap = storages.computeIfAbsent(botKey, k -> new LinkedHashMap<>());
        botMap.put(storageKey, new StoragePoint(storageName, loc.clone(), true));
        save();
    }

    public List<StoragePoint> getStorages(String botName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        if (botMap == null || botMap.isEmpty()) return List.of();
        List<StoragePoint> out = new ArrayList<>();
        for (StoragePoint point : botMap.values()) if (point.enabled()) out.add(point);
        out.sort(Comparator.comparingInt((StoragePoint p) -> numericName(p.name()))
                .thenComparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(out);
    }

    public Set<String> getStorageNames(String botName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        if (botMap == null || botMap.isEmpty()) return Set.of();
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (StoragePoint point : botMap.values()) out.add(point.name());
        return Collections.unmodifiableSet(out);
    }

    public List<StoragePoint> getAllStorages(String botName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        if (botMap == null || botMap.isEmpty()) return List.of();
        return List.copyOf(botMap.values());
    }

    public boolean setEnabled(String botName, String storageName, boolean enabled) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        if (botMap == null) return false;
        String key = storageName.toLowerCase(Locale.ROOT);
        StoragePoint point = botMap.get(key);
        if (point == null) return false;
        botMap.put(key, new StoragePoint(point.name(), point.location(), enabled));
        save();
        return true;
    }

    public boolean removeStorage(String botName, String storageName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        if (botMap == null) return false;
        boolean removed = botMap.remove(storageName.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            if (botMap.isEmpty()) storages.remove(botName.toLowerCase(Locale.ROOT));
            save();
        }
        return removed;
    }

    public int clearStorages(String botName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.remove(botName.toLowerCase(Locale.ROOT));
        int count = botMap != null ? botMap.size() : 0;
        if (count > 0) save();
        return count;
    }

    public int renameBot(String oldName, String newName) {
        String oldKey = oldName.toLowerCase(Locale.ROOT);
        String newKey = newName.toLowerCase(Locale.ROOT);
        if (oldKey.equals(newKey)) return 0;
        LinkedHashMap<String, StoragePoint> botMap = storages.remove(oldKey);
        if (botMap == null || botMap.isEmpty()) return 0;

        storages.merge(newKey, botMap, (existing, incoming) -> {
            existing.putAll(incoming);
            return existing;
        });
        save();
        return botMap.size();
    }

    public String nextAutoName(String botName) {
        LinkedHashMap<String, StoragePoint> botMap = storages.get(botName.toLowerCase(Locale.ROOT));
        int next = 1;
        if (botMap != null) {
            while (botMap.containsKey(String.valueOf(next))) next++;
        }
        return String.valueOf(next);
    }

    private static int numericName(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    public record StoragePoint(String name, Location location, boolean enabled) {}
}
