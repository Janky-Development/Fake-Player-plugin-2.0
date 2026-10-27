package me.bill.fakePlayerPlugin.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.bukkit.plugin.Plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class AttributionApiManager {

    private static final int[] _EP = {
        104, 116, 116, 112, 115, 58, 47, 47, 102, 97, 107, 101, 112, 108, 97, 121, 101, 114,
        112, 108, 117, 103, 105, 110, 46, 120, 121, 122, 47, 97, 112, 105, 47, 97, 116, 116,
        114, 105, 98, 117, 116, 105, 111, 110
    };

    private static final int[] _AK = {
        102, 112, 112, 95, 108, 105, 118, 101, 95, 65, 116, 116, 114, 75, 101, 121, 95, 50, 48, 50, 54
    };

    private static String _d(int[] c) {
        char[] r = new char[c.length];
        for (int i = 0; i < c.length; i++) r[i] = (char) c[i];
        return new String(r);
    }

    public static String getEndpoint() {
        return _d(_EP);
    }

    static String getApiKey() {
        return _d(_AK);
    }

    private static volatile AttributionData apiData;
    private static volatile boolean apiFetched = false;
    private static volatile boolean apiReachable = false;

    private static final String CACHE_FILE = "data/attribution-cache.json";
    private static final long CONNECT_TIMEOUT_MS = 5_000;
    private static final long READ_TIMEOUT_MS = 5_000;

    private AttributionApiManager() {}

    public static void init(Plugin plugin) {
        FppScheduler.runAsync(plugin, () -> {
            AttributionData data = fetchFromApi();
            if (data != null) {
                apiData = data;
                apiFetched = true;
                apiReachable = true;
                saveCache(plugin, data);
                FppScheduler.runSync(plugin, () -> compareAndRestore(data));
            } else {

                AttributionData cached = loadCache(plugin);
                if (cached != null) {
                    apiData = cached;
                    apiFetched = true;
                    apiReachable = false;
                    FppScheduler.runSync(plugin, () -> compareAndRestore(cached));
                } else {
                    apiFetched = false;
                    apiReachable = false;
                }
            }
        });
    }

    public static boolean hasData() {
        return apiFetched && apiData != null;
    }

    public static String getAuthor() {
        if (apiData != null && apiData.author != null && !apiData.author.isBlank()) {
            return apiData.author;
        }
        return AttributionManager.getOriginalAuthor();
    }

    public static String getMessage() {
        if (apiData != null && apiData.message != null && !apiData.message.isBlank()) {
            return apiData.message;
        }
        return AttributionManager.getAttributionMessage();
    }

    public static String getModrinthLink() {
        if (apiData != null && apiData.modrinthLink != null && !apiData.modrinthLink.isBlank()) {
            return apiData.modrinthLink;
        }
        return AttributionManager.getModrinthLink();
    }

    public static String getGithubLink() {
        if (apiData != null && apiData.githubLink != null && !apiData.githubLink.isBlank()) {
            return apiData.githubLink;
        }
        return AttributionManager.getGithubLink();
    }

    public static boolean quickEndpointCheck() {
        if (_EP.length < 10) return false;
        int sum = 0;
        for (int c : _EP) sum += c;
        return sum == 4452;
    }

    private static AttributionData fetchFromApi() {
        HttpURLConnection conn = null;
        try {
            String endpoint = getEndpoint();
            conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
            conn.setRequestProperty("User-Agent", "FakePlayerPlugin");
            conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            conn.setReadTimeout((int) READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) return null;

            String body;
            try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                body = sb.toString();
            }

            return parseResponse(body);
        } catch (Exception e) {

            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static AttributionData parseResponse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String author = getStr(obj, "author");
            String message = getStr(obj, "message");
            String modrinth = getStr(obj, "modrinth_link");
            String github = getStr(obj, "github_link");
            String signature = getStr(obj, "signature");

            if (author == null || message == null) return null;

            if (signature == null || signature.isBlank()) {
                return null;
            }

            return new AttributionData(author, message, modrinth, github, signature);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static void compareAndRestore(AttributionData data) {
        // Silently compare and restore without logging warnings.
    }

    private static void saveCache(Plugin plugin, AttributionData data) {
        try {
            File file = new File(plugin.getDataFolder(), CACHE_FILE);
            file.getParentFile().mkdirs();
            JsonObject obj = new JsonObject();
            obj.addProperty("author", data.author);
            obj.addProperty("message", data.message);
            if (data.modrinthLink != null) obj.addProperty("modrinth_link", data.modrinthLink);
            if (data.githubLink != null) obj.addProperty("github_link", data.githubLink);
            if (data.signature != null) obj.addProperty("signature", data.signature);
            obj.addProperty("cached_at", System.currentTimeMillis());
            Files.writeString(file.toPath(), obj.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {

        }
    }

    private static AttributionData loadCache(Plugin plugin) {
        try {
            File file = new File(plugin.getDataFolder(), CACHE_FILE);
            if (!file.exists()) return null;
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return parseResponse(json);
        } catch (Exception e) {
            return null;
        }
    }

    public record AttributionData(
            String author, String message, String modrinthLink, String githubLink, String signature) {}
}
