package me.bill.fakePlayerPlugin.util;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads every class in the plugin jar up front, while the jar is guaranteed to still be the one the
 * server started this plugin from.
 *
 * <p>Hot-reload managers (PlugManX, PlugMan, ServerUtils, ...) overwrite the jar on disk with the
 * new build <em>before</em> disabling the running plugin. The old plugin classloader then reads
 * from a file that no longer has the same contents, so any class it had not loaded yet (typically
 * inner classes first touched on shutdown, like {@code BotPersistence$XpEntry}) fails with
 * {@code NoClassDefFoundError} and the save/cleanup in {@code onDisable} is skipped. Loading
 * everything eagerly removes that window.
 */
public final class ClassPreloader {

    private ClassPreloader() {}

    /** @return number of classes loaded; never throws. */
    public static int preload(File jar, ClassLoader loader) {
        if (jar == null || !jar.isFile() || loader == null) return 0;
        int loaded = 0;
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.equals("module-info.class")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                try {
                    // initialize=false: only defines the class, runs no static initialisers.
                    Class.forName(cn, false, loader);
                    loaded++;
                } catch (Throwable ignored) {
                    // Optional/absent soft-dependencies - not an error.
                }
            }
        } catch (Throwable t) {
            FppLogger.debug("ClassPreloader: " + t);
        }
        return loaded;
    }
}
