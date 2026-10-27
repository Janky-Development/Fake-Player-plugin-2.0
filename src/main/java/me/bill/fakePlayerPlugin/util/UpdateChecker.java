package me.bill.fakePlayerPlugin.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.permission.Perm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class UpdateChecker {

    private static final String MODRINTH_SLUG = "fake-player-plugin-fpp";

    /** Newest first. A handful is enough to find the latest release even if betas are mixed in. */
    private static final String MODRINTH_API =
            "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version?limit=10";

    private static final String MODRINTH_PAGE = "https://modrinth.com/plugin/" + MODRINTH_SLUG;

    /** Only ever downloaded from Modrinth's own CDN, over https. */
    private static final String MODRINTH_CDN_HOST = "cdn.modrinth.com";

    private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

    private static final String VERCEL_API_BASE = "https://fpp.wtf";

    private static final Pattern VERSION_REGEX = Pattern.compile("v?\\d+(?:\\.\\d+)+");

    private static volatile UpdateInfo cachedResult = null;
    private static volatile long cacheTimestamp = 0L;
    private static final long CACHE_TTL_MS = 5L * 60L * 1000L;

    private UpdateChecker() {}

    public static void check(Plugin plugin) {
        if (!Config.updateCheckerEnabled()) {
            Config.debug("Update checker disabled in config.");
            return;
        }
        FppScheduler.runAsync(plugin, () -> {
            UpdateInfo info = fetchOrCached(plugin);
            FppScheduler.runSync(plugin, () -> handleResultOnMainThread(plugin, info));
        });
    }

    public static void handleResultOnMainThread(Plugin plugin, UpdateInfo info) {
        if (info == null) return;

        if (info.error != null) {

            Component failMsg = Lang.get("update-failed");
            FppLogger.warn(
                    stripLangPrefix(PlainTextComponentSerializer.plainText().serialize(failMsg))
                            + " ("
                            + info.error
                            + ")");

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (Perm.has(p, Perm.OP)) p.sendMessage(failMsg);
            }
            return;
        }

        String latestClean = stripV(info.latest);
        String currentClean = stripV(info.current);

        int cmp = compareVersions(info.current, info.latest);
        boolean updateAvailable = cmp < 0;
        boolean isBeta = cmp > 0;

        if (updateAvailable) {

            FppLogger.warn(
                    "Update available! v" + currentClean + " → v" + latestClean + " | Download: " + MODRINTH_PAGE);

            Component msg = Lang.get("update-available", "current", currentClean, "latest", latestClean);

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (Perm.has(p, Perm.OP)) p.sendMessage(msg);
            }

            if (plugin instanceof FakePlayerPlugin fpp) {
                fpp.setUpdateNotification(msg);
                fpp.setLatestKnownVersion(latestClean);
            }

            if (Config.updateCheckerAutoUpdate()) stageUpdate(plugin, info, latestClean);

        } else if (isBeta) {

            FppLogger.warn("Running BETA v"
                    + currentClean
                    + " (latest stable: v"
                    + latestClean
                    + "). "
                    + "Download stable: "
                    + MODRINTH_PAGE);

            Component msg = Lang.get("update-beta", "current", currentClean, "latest", latestClean);

            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (Perm.has(p, Perm.OP)) p.sendMessage(msg);
            }

            if (plugin instanceof FakePlayerPlugin fpp) {
                fpp.setUpdateNotification(msg);
                fpp.setLatestKnownVersion(latestClean);
                fpp.setRunningBeta(true);
            }

        } else {

            Component ok = Lang.get("update-up-to-date", "current", currentClean);
            FppLogger.success(
                    stripLangPrefix(PlainTextComponentSerializer.plainText().serialize(ok)));

            if (plugin instanceof FakePlayerPlugin fpp) {
                fpp.setUpdateNotification(null);
                fpp.setRunningBeta(false);
            }
        }
    }

    // ── auto update ──────────────────────────────────────────────────────────────────────────

    private static final java.util.concurrent.atomic.AtomicReference<String> stagedVersion =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Downloads the latest release into the server's update folder under the <em>same file name as
     * the running jar</em>. Paper/Bukkit replace a plugin jar with the same-named file from
     * {@code plugins/update} on the next start, so the new version goes live on the next restart
     * without anything being swapped while the server is running.
     *
     * <p>Safety: the file must come from Modrinth's CDN over https, be a plain {@code .jar} within a
     * size limit, and match the SHA-512 Modrinth publishes for it; anything else is discarded.
     */
    private static void stageUpdate(Plugin plugin, UpdateInfo info, String latestClean) {
        if (!(plugin instanceof FakePlayerPlugin fpp)) return;
        if (info.sha512 == null || info.sha512.isBlank() || info.downloadUrl == null) {
            FppLogger.warn("Auto-update skipped: no verifiable file in the Modrinth response.");
            return;
        }
        if (latestClean.equals(stagedVersion.get())) return; // already downloaded this run
        final java.net.URI uri;
        try {
            uri = URI.create(info.downloadUrl);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !MODRINTH_CDN_HOST.equalsIgnoreCase(uri.getHost())) {
            FppLogger.warn("Auto-update skipped: download host '" + uri.getHost() + "' is not " + MODRINTH_CDN_HOST);
            return;
        }
        if (info.fileSize > MAX_JAR_BYTES) {
            FppLogger.warn("Auto-update skipped: file is unexpectedly large (" + info.fileSize + " bytes).");
            return;
        }

        FppScheduler.runAsync(plugin, () -> {
            try {
                java.io.File running = fpp.getPluginJar();
                java.io.File updateDir = org.bukkit.Bukkit.getUpdateFolderFile();
                if (running == null || updateDir == null) return;
                if (!updateDir.isDirectory() && !updateDir.mkdirs()) return;

                java.io.File target = new java.io.File(updateDir, running.getName());
                java.io.File tmp = new java.io.File(updateDir, running.getName() + ".part");

                // Already staged by an earlier run (same content) - nothing to do.
                if (target.isFile() && info.sha512.equalsIgnoreCase(sha512Of(target))) {
                    stagedVersion.set(latestClean);
                    FppLogger.info("Update v" + latestClean + " is already staged; it installs on the next restart.");
                    return;
                }

                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty(
                        "User-Agent", "FakePlayerPlugin-AutoUpdate/" + info.current + " (" + MODRINTH_PAGE + ")");
                if (conn.getResponseCode() != 200) {
                    FppLogger.warn("Auto-update download failed: HTTP " + conn.getResponseCode());
                    return;
                }

                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
                long total = 0;
                try (java.io.InputStream in = conn.getInputStream();
                        java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_JAR_BYTES) throw new java.io.IOException("download exceeds size limit");
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }

                String actual = java.util.HexFormat.of().formatHex(md.digest());
                if (!actual.equalsIgnoreCase(info.sha512)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    FppLogger.warn("Auto-update discarded: SHA-512 of the download did not match Modrinth's.");
                    return;
                }

                java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                stagedVersion.set(latestClean);
                FppLogger.success("Update v" + latestClean + " downloaded and verified - it will be installed"
                        + " when the server restarts (plugins/update/" + target.getName() + ").");
                FppScheduler.runSync(plugin, () -> {
                    Component note = Component.text(
                            "FPP v" + latestClean + " was downloaded and will be installed on the next restart.");
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (Perm.has(p, Perm.OP)) p.sendMessage(note);
                    }
                });
            } catch (Exception e) {
                FppLogger.warn("Auto-update failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static String sha512Of(java.io.File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    public static void invalidateCache() {
        cachedResult = null;
        cacheTimestamp = 0L;
        Config.debug("UpdateChecker: cache invalidated.");
    }

    public static final class UpdateInfo {
        public String latest;
        public boolean latestStartsWithV;
        public String current;
        public boolean currentStartsWithV;
        public String error;
        public String downloadUrl;
        /** Primary file of the latest release - set only for Modrinth results. */
        public String fileName;

        public String sha512;
        public long fileSize;
    }

    private static UpdateInfo fetchOrCached(Plugin plugin) {
        long now = System.currentTimeMillis();
        if (cachedResult != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            cachedResult.current = plugin.getPluginMeta().getVersion();
            cachedResult.currentStartsWithV = cachedResult.current.startsWith("v");
            Config.debug("UpdateChecker: using cached result (age " + ((now - cacheTimestamp) / 1000) + "s).");
            return cachedResult;
        }
        UpdateInfo fresh = fetchUpdateInfo(plugin);
        if (fresh != null && fresh.error == null) {
            cachedResult = fresh;
            cacheTimestamp = now;
        }
        return fresh;
    }

    static int compareVersions(String a, String b) {
        int[] pa = parseVersionParts(stripV(a));
        int[] pb = parseVersionParts(stripV(b));
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? pa[i] : 0;
            int nb = i < pb.length ? pb[i] : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int[] parseVersionParts(String v) {
        if (v == null || v.isBlank()) return new int[0];
        String[] raw = v.split("\\.", -1);
        int[] parts = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            Matcher m = Pattern.compile("^(\\d+)").matcher(raw[i]);
            parts[i] = m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
        return parts;
    }

    private static String stripV(String v) {
        return (v != null && v.startsWith("v")) ? v.substring(1) : (v != null ? v : "");
    }

    private static String stripLangPrefix(String plain) {
        try {
            String prefixRaw = Lang.raw("prefix");
            String prefixPlain = PlainTextComponentSerializer.plainText().serialize(TextUtil.colorize(prefixRaw));
            if (plain.startsWith(prefixPlain))
                return plain.substring(prefixPlain.length()).strip();
        } catch (Throwable ignored) {
        }
        return plain;
    }

    private static UpdateInfo fetchUpdateInfo(Plugin plugin) {
        UpdateInfo info = new UpdateInfo();
        info.current = plugin.getPluginMeta().getVersion();
        info.currentStartsWithV = info.current.startsWith("v");

        UpdateInfo modrinthResult = tryFetch(info.current, MODRINTH_API, true);
        if (modrinthResult != null && modrinthResult.error == null) {
            Config.debug("UpdateChecker: Modrinth → latest=" + stripV(modrinthResult.latest));
            return modrinthResult;
        }
        Config.debug("UpdateChecker: Modrinth unavailable - "
                + (modrinthResult != null ? modrinthResult.error : "null result"));

        String[] vercelCandidates = {
            VERCEL_API_BASE + "/api/check-update", VERCEL_API_BASE + "/api/status", VERCEL_API_BASE + "/api/latest",
        };
        for (String url : vercelCandidates) {
            UpdateInfo result = tryFetch(info.current, url, false);
            if (result != null && result.error == null) {
                Config.debug("UpdateChecker: Vercel → latest=" + stripV(result.latest) + " from " + url);
                return result;
            }
        }

        info.error = "could not reach Modrinth or Vercel API";
        return info;
    }

    private static UpdateInfo tryFetch(String currentVersion, String url, boolean arrayResponse) {
        UpdateInfo info = new UpdateInfo();
        info.current = currentVersion;
        info.currentStartsWithV = currentVersion.startsWith("v");
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(6_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty(
                    "User-Agent", "FakePlayerPlugin-UpdateChecker/" + currentVersion + " (" + MODRINTH_PAGE + ")");

            int code = conn.getResponseCode();
            if (code != 200) {
                info.error = "HTTP " + code + " from " + url;
                return info;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String body = sb.toString().trim();
            if (body.isEmpty()) {
                info.error = "empty response from " + url;
                return info;
            }

            String latest;
            String downloadUrl;

            if (arrayResponse) {

                com.google.gson.JsonObject chosen = pickLatestRelease(body);
                if (chosen == null) {
                    info.error = "no usable version in response from " + url;
                    return info;
                }
                latest = chosen.get("version_number").getAsString();
                downloadUrl = MODRINTH_PAGE;
                com.google.gson.JsonObject file = pickPrimaryJar(chosen);
                if (file != null) {
                    downloadUrl = file.get("url").getAsString();
                    info.fileName = file.has("filename") ? file.get("filename").getAsString() : null;
                    info.fileSize = file.has("size") ? file.get("size").getAsLong() : 0L;
                    if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha512")) {
                        info.sha512 =
                                file.getAsJsonObject("hashes").get("sha512").getAsString();
                    }
                }
            } else {

                latest = extractVersion(body);
                downloadUrl = extractDownloadUrl(body);
            }

            if (latest == null || latest.isBlank()) {
                info.error = "no version found in response from " + url;
                return info;
            }

            info.latest = latest;
            info.latestStartsWithV = latest.startsWith("v");
            info.downloadUrl = downloadUrl;
            return info;

        } catch (SocketTimeoutException e) {
            info.error = "timeout connecting to " + url;
        } catch (Exception e) {
            info.error = e.getClass().getSimpleName() + ": " + e.getMessage() + " - " + url;
        }
        return info;
    }

    /** First "release" in the (newest-first) list; the newest entry if the list has no release. */
    private static com.google.gson.JsonObject pickLatestRelease(String body) {
        try {
            com.google.gson.JsonArray arr =
                    com.google.gson.JsonParser.parseString(body).getAsJsonArray();
            com.google.gson.JsonObject first = null;
            for (com.google.gson.JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("version_number")) continue;
                if (first == null) first = o;
                if (o.has("version_type")
                        && "release".equals(o.get("version_type").getAsString())) return o;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private static com.google.gson.JsonObject pickPrimaryJar(com.google.gson.JsonObject version) {
        if (!version.has("files")) return null;
        com.google.gson.JsonObject firstJar = null;
        for (com.google.gson.JsonElement el : version.getAsJsonArray("files")) {
            if (!el.isJsonObject()) continue;
            com.google.gson.JsonObject f = el.getAsJsonObject();
            String name = f.has("filename") ? f.get("filename").getAsString() : "";
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") || !f.has("url")) continue;
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
            if (firstJar == null) firstJar = f;
        }
        return firstJar;
    }

    private static String extractVersion(String body) {
        for (String key : new String[] {
            "remoteVersion", "remote_version", "version_number", "tag_name", "version", "latest", "name"
        }) {
            String val = extractJsonString(body, key);
            if (val != null && VERSION_REGEX.matcher(val).find()) return val;
        }
        for (String wrapper : new String[] {"remote", "data"}) {
            String nested = extractNestedObject(body, wrapper);
            if (nested != null) {
                for (String key : new String[] {"version", "version_number", "tag_name", "latest"}) {
                    String val = extractJsonString(nested, key);
                    if (val != null && VERSION_REGEX.matcher(val).find()) return val;
                }
            }
        }
        Matcher m = VERSION_REGEX.matcher(body);
        return m.find() ? m.group() : null;
    }

    private static String extractDownloadUrl(String body) {
        for (String key : new String[] {"downloadUrl", "download_url", "website"}) {
            String val = extractJsonString(body, key);
            if (val != null && !val.isBlank()) return val;
        }
        for (String wrapper : new String[] {"remote", "data"}) {
            String nested = extractNestedObject(body, wrapper);
            if (nested != null) {
                for (String key : new String[] {"downloadUrl", "download_url", "website"}) {
                    String val = extractJsonString(nested, key);
                    if (val != null && !val.isBlank()) return val;
                }
            }
        }
        return null;
    }

    private static String extractJsonString(String body, String key) {
        int idx = body.indexOf('"' + key + '"');
        if (idx < 0) return null;
        int colon = body.indexOf(':', idx + key.length() + 2);
        if (colon < 0) return null;
        int q1 = colon + 1;
        while (q1 < body.length() && body.charAt(q1) != '"') {
            char c = body.charAt(q1);
            if (c == '{' || c == '[') return null;
            q1++;
        }
        if (q1 >= body.length()) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2).trim();
    }

    private static String extractNestedObject(String body, String key) {
        int idx = body.indexOf('"' + key + '"');
        if (idx < 0) return null;
        int start = body.indexOf('{', idx + key.length() + 2);
        if (start < 0) return null;
        int end = findMatchingBrace(body, start);
        return end > start ? body.substring(start, end + 1) : null;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }
}
