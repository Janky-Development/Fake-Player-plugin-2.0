package me.bill.fakePlayerPlugin.util;

import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

public final class AttributionManager {

    private static boolean integrityValid = true;

    private AttributionManager() {}

    private static final int[] _A = {70, 95, 80, 80};

    private static final int[] _M = {
        84, 104, 105, 115, 32, 112, 108, 117, 103, 105, 110, 32, 105, 115, 32, 70, 82, 69, 69,
        32, 97, 110, 100, 32, 111, 112, 101, 110, 45, 115, 111, 117, 114, 99, 101, 46, 32, 73,
        102, 32, 121, 111, 117, 32, 112, 97, 105, 100, 32, 102, 111, 114, 32, 105, 116, 44, 32,
        121, 111, 117, 32, 119, 101, 114, 101, 32, 115, 99, 97, 109, 109, 101, 100, 46
    };

    private static final int[] _L1 = {
        104, 116, 116, 112, 115, 58, 47, 47, 109, 111, 100, 114, 105, 110, 116, 104, 46, 99,
        111, 109, 47, 112, 108, 117, 103, 105, 110, 47, 102, 97, 107, 101, 45, 112, 108, 97,
        121, 101, 114, 45, 112, 108, 117, 103, 105, 110, 45, 40, 102, 112, 112, 41
    };

    private static final int[] _L2 = {
        104, 116, 116, 112, 115, 58, 47, 47, 103, 105, 116, 104, 117, 98, 46, 99, 111, 109, 47, 101,
        108, 45, 112, 101, 112, 101, 115, 47, 70, 97, 107, 101, 80, 108, 97, 121, 101, 114, 80, 108,
        117, 103, 105, 110
    };

    private static String _d(int[] c) {
        char[] r = new char[c.length];
        for (int i = 0; i < c.length; i++) r[i] = (char) c[i];
        return new String(r);
    }

    private static volatile String cachedAuthor;
    private static volatile String cachedMessage;
    private static volatile String cachedModrinth;
    private static volatile String cachedGithub;

    public static String getOriginalAuthor() {

        if (AttributionApiManager.hasData()) {
            String api = AttributionApiManager.getAuthor();
            if (api != null && !api.isBlank()) return api;
        }
        String v = cachedAuthor;
        if (v == null) {
            v = _d(_A);
            cachedAuthor = v;
        }
        return v;
    }

    public static String getAttributionMessage() {
        if (AttributionApiManager.hasData()) {
            String api = AttributionApiManager.getMessage();
            if (api != null && !api.isBlank()) return api;
        }
        String v = cachedMessage;
        if (v == null) {
            v = _d(_M);
            cachedMessage = v;
        }
        return v;
    }

    public static String getModrinthLink() {
        if (AttributionApiManager.hasData()) {
            String api = AttributionApiManager.getModrinthLink();
            if (api != null && !api.isBlank()) return api;
        }
        String v = cachedModrinth;
        if (v == null) {
            v = _d(_L1);
            cachedModrinth = v;
        }
        return v;
    }

    public static String getGithubLink() {
        if (AttributionApiManager.hasData()) {
            String api = AttributionApiManager.getGithubLink();
            if (api != null && !api.isBlank()) return api;
        }
        String v = cachedGithub;
        if (v == null) {
            v = _d(_L2);
            cachedGithub = v;
        }
        return v;
    }

    public static boolean validate(JavaPlugin plugin) {
        integrityValid = true;

        if (!validateOriginalAuthor(plugin)) integrityValid = false;
        if (!validateAttributionMessage()) integrityValid = false;
        if (!validateLinks()) integrityValid = false;

        return integrityValid;
    }

    public static boolean validateOriginalAuthor(JavaPlugin plugin) {
        try {
            String expected = getOriginalAuthor();
            List<String> authors = plugin.getPluginMeta().getAuthors();
            for (String a : authors) {
                if (a.equalsIgnoreCase(expected)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean validateAttributionMessage() {
        String m = getAttributionMessage();
        if (m == null || m.length() < 20) {
            return false;
        }
        return true;
    }

    public static boolean validateLinks() {
        String l1 = getModrinthLink();
        String l2 = getGithubLink();
        if (l1 == null || l1.length() < 10 || l2 == null || l2.length() < 10) {
            return false;
        }
        return true;
    }

    public static boolean quickAuthorCheck() {

        if (_A.length != 4) return false;

        int sum = 0;
        for (int c : _A) sum += c;
        return sum == 325;
    }

    public static String formatAuthors(List<String> pluginAuthors) {
        String orig = getOriginalAuthor();
        if (pluginAuthors == null || pluginAuthors.isEmpty()) return orig;

        StringBuilder sb = new StringBuilder(orig);
        for (String a : pluginAuthors) {
            if (!a.equalsIgnoreCase(orig)) {
                sb.append(", ").append(a);
            }
        }
        return sb.toString();
    }
}
