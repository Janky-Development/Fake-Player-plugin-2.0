package me.bill.fakePlayerPlugin.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Identity of the exact jar that is running. The Gradle build stamps {@code fpp-build.properties} into
 * the jar with a fresh build ID on every build, so a console line, a bug report or a performance report
 * pins down which jar produced it - a version number alone can't, since several different jars share one.
 *
 * <p>Run from an IDE or any source without the stamped resource, everything reads "dev".
 */
public final class BuildInfo {

    private static final String RESOURCE = "/fpp-build.properties";

    private static final String ID;
    private static final String TYPE;
    private static final String TIME;

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // falls through to the dev defaults below
        }
        ID = p.getProperty("build-id", "dev");
        TYPE = p.getProperty("build-type", "dev");
        TIME = p.getProperty("build-time", "unknown");
    }

    private BuildInfo() {}

    /** This jar's build ID, e.g. {@code D261013-201530-a3f9}. */
    public static String id() {
        return ID;
    }

    /** Build type, {@code dev} for a plain Gradle build. */
    public static String type() {
        return TYPE;
    }

    /** UTC time the build ID was generated (ISO-8601), or {@code unknown}. */
    public static String builtAt() {
        return TIME;
    }

    /** Compact one-liner for banners and reports, e.g. {@code D261013-201530-a3f9 (dev)}. */
    public static String summary() {
        return ID + " (" + TYPE + ")";
    }
}
