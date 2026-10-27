package me.bill.fakePlayerPlugin.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;

public final class BackupManager {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final int MAX_BACKUPS = 10;

    private BackupManager() {}

    public static File createConfigFilesBackup(FakePlayerPlugin plugin, String reason) {
        File backupDir = createBackupDir(plugin, reason);
        File dataFolder = plugin.getDataFolder();

        copyFile(new File(dataFolder, "config.yml"), new File(backupDir, "config.yml"));
        copyFile(new File(dataFolder, "bot-names.yml"), new File(backupDir, "bot-names.yml"));
        copyLanguageFiles(dataFolder, backupDir);

        writeManifest(backupDir, plugin, reason);
        pruneOldBackups(backupDir.getParentFile());
        FppLogger.success("Config-files backup created -> " + relativeBackupPath(plugin, backupDir) + "/");
        return backupDir;
    }

    public static File createFullBackup(FakePlayerPlugin plugin, String reason) {
        return createFullBackup(plugin, reason, true);
    }

    public static File createFullBackup(FakePlayerPlugin plugin, String reason, boolean announce) {
        File backupDir = createBackupDir(plugin, reason);
        File dataFolder = plugin.getDataFolder();

        copyFile(new File(dataFolder, "config.yml"), new File(backupDir, "config.yml"));
        copyFile(new File(dataFolder, "bot-names.yml"), new File(backupDir, "bot-names.yml"));
        copyLanguageFiles(dataFolder, backupDir);

        copyFile(new File(dataFolder, "data/active-bots.yml"), new File(backupDir, "data/active-bots.yml"));
        copyFile(new File(dataFolder, "data/fpp.db"), new File(backupDir, "data/fpp.db"));
        copyFile(new File(dataFolder, "data/fpp.db-wal"), new File(backupDir, "data/fpp.db-wal"));
        copyFile(new File(dataFolder, "data/fpp.db-shm"), new File(backupDir, "data/fpp.db-shm"));

        writeManifest(backupDir, plugin, reason);
        pruneOldBackups(backupDir.getParentFile());

        String path = relativeBackupPath(plugin, backupDir) + "/";
        if (announce) FppLogger.success("Backup created -> " + path);
        else FppLogger.debug("Backup created -> " + path);
        return backupDir;
    }

    public static File createDatabaseBackup(FakePlayerPlugin plugin, String reason) {
        File backupDir = createBackupDir(plugin, reason);
        File dataFolder = plugin.getDataFolder();

        copyFile(new File(dataFolder, "data/fpp.db"), new File(backupDir, "data/fpp.db"));
        copyFile(new File(dataFolder, "data/fpp.db-wal"), new File(backupDir, "data/fpp.db-wal"));
        copyFile(new File(dataFolder, "data/fpp.db-shm"), new File(backupDir, "data/fpp.db-shm"));

        writeManifest(backupDir, plugin, reason);
        pruneOldBackups(backupDir.getParentFile());
        FppLogger.success("Database backup created -> " + relativeBackupPath(plugin, backupDir) + "/");
        return backupDir;
    }

    public static List<String> listBackups(FakePlayerPlugin plugin) {
        File backupsDir = new File(plugin.getDataFolder(), "backup");
        if (!backupsDir.isDirectory()) return List.of();

        List<File> dirs = new ArrayList<>();
        collectBackupDirs(backupsDir, dirs);
        if (dirs.isEmpty()) return List.of();

        dirs.sort(Comparator.comparing(File::getPath).reversed());
        Path root = backupsDir.toPath();
        List<String> names = new ArrayList<>(dirs.size());
        for (File d : dirs) names.add(root.relativize(d.toPath()).toString().replace('\\', '/'));
        return Collections.unmodifiableList(names);
    }

    private static File createBackupDir(FakePlayerPlugin plugin, String reason) {
        String safeReason = reason.replaceAll("[^a-zA-Z0-9_-]", "_");
        String version = plugin.getPluginMeta().getVersion().replaceAll("[^a-zA-Z0-9_.-]", "_");
        String timestamp = LocalDateTime.now().format(DATE_FMT);
        File backupDir = new File(plugin.getDataFolder(), "backup/" + version + "/" + timestamp + "_" + safeReason);
        backupDir.mkdirs();
        return backupDir;
    }

    private static String relativeBackupPath(FakePlayerPlugin plugin, File backupDir) {
        return plugin.getDataFolder()
                .toPath()
                .relativize(backupDir.toPath())
                .toString()
                .replace('\\', '/');
    }

    private static void copyLanguageFiles(File dataFolder, File backupDir) {
        File langDir = new File(dataFolder, "language");
        if (!langDir.isDirectory()) return;
        File langBackup = new File(backupDir, "language");
        langBackup.mkdirs();
        File[] langFiles = langDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (langFiles != null) {
            for (File lf : langFiles) copyFile(lf, new File(langBackup, lf.getName()));
        }
    }

    private static void copyFile(File src, File dst) {
        if (!src.exists()) return;
        try {
            dst.getParentFile().mkdirs();
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            FppLogger.debug("BackupManager: could not copy " + src.getName() + ": " + e.getMessage());
        }
    }

    private static void writeManifest(File backupDir, FakePlayerPlugin plugin, String reason) {
        File manifest = new File(backupDir, "MANIFEST.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(manifest))) {
            pw.println("FakePlayerPlugin Backup Manifest");
            pw.println("================================");
            pw.println("Plugin version : " + plugin.getPluginMeta().getVersion());
            pw.println("Build ID       : " + BuildInfo.summary());
            pw.println("Backup reason  : " + reason);
            pw.println("Timestamp      : " + LocalDateTime.now());
            pw.println("Server version : " + plugin.getServer().getVersion());
        } catch (IOException e) {
            FppLogger.debug("BackupManager: could not write manifest: " + e.getMessage());
        }
    }

    private static void collectBackupDirs(File dir, List<File> out) {
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File child : dirs) {
            if (new File(child, "MANIFEST.txt").isFile()) out.add(child);
            else collectBackupDirs(child, out);
        }
    }

    private static void pruneOldBackups(File versionDir) {
        if (versionDir == null || !versionDir.isDirectory()) return;
        File[] dirs = versionDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= MAX_BACKUPS) return;

        Arrays.sort(dirs, Comparator.comparing(File::getName));
        int toDelete = dirs.length - MAX_BACKUPS;
        for (int i = 0; i < toDelete; i++) {
            deleteDirectory(dirs[i]);
            FppLogger.debug("BackupManager: pruned old backup: " + dirs[i].getName());
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static long dirSize(File dir) {
        if (!dir.exists()) return 0L;
        long size = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) size += f.isDirectory() ? dirSize(f) : f.length();
        }
        return size;
    }
}
