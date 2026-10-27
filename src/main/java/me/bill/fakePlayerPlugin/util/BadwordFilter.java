package me.bill.fakePlayerPlugin.util;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.bill.fakePlayerPlugin.config.BotNameConfig;
import me.bill.fakePlayerPlugin.config.Config;

public final class BadwordFilter {

    private static final Set<String> rawWords = new HashSet<>();

    private static final Set<String> normWords = new HashSet<>();

    private static final Set<String> collapsedWords = new HashSet<>();

    private static final Set<String> whitelist = new HashSet<>();

    private static final List<Pattern> customPatterns = new ArrayList<>();
    private static final List<String> customPatternSrc = new ArrayList<>();

    private static final List<Pattern> autoRepeatPatterns = new ArrayList<>();
    private static final List<String> autoRepeatWords = new ArrayList<>();

    private static final List<Pattern> autoSepPatterns = new ArrayList<>();
    private static final List<String> autoSepWords = new ArrayList<>();

    private static final List<Pattern> autoGapPatterns = new ArrayList<>();
    private static final List<String> autoGapWords = new ArrayList<>();

    private static final String REMOTE_CACHE_FILE = "bad-words-remote-cache.txt";

    private static boolean initialized = false;

    private BadwordFilter() {}

    public static void reload(@Nullable Plugin plugin) {
        rawWords.clear();
        normWords.clear();
        collapsedWords.clear();
        whitelist.clear();
        customPatterns.clear();
        customPatternSrc.clear();
        autoRepeatPatterns.clear();
        autoRepeatWords.clear();
        autoSepPatterns.clear();
        autoSepWords.clear();
        autoGapPatterns.clear();
        autoGapWords.clear();

        if (Config.isBadwordGlobalListEnabled()) {
            loadRemoteGlobalBadWords(plugin);
        }

        for (String w : Config.getBadwords()) addWord(w);

        if (plugin != null) loadBadWordsFile(plugin);

        Config.getBadwordWhitelist().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::toLowerCase)
                .forEach(whitelist::add);

        buildAutoPatterns();

        initialized = true;
    }

    public static void reload() {
        reload(null);
    }

    public static boolean isAllowed(@NotNull String name) {
        if (!initialized) reload();
        if (!Config.isBadwordFilterEnabled()) return true;
        if (rawWords.isEmpty() && customPatterns.isEmpty()) return true;

        String lower = name.toLowerCase();
        if (whitelist.contains(lower)) return true;

        for (String bw : rawWords) {
            if (lower.contains(bw)) return false;
        }

        String leet = normalize(lower);
        for (String bw : normWords) {
            if (leet.contains(bw)) return false;
        }

        if (Config.isBadwordAutoDetectionEnabled()
                && !Config.getBadwordAutoDetectionMode().equals("off")) {
            String collapsed = collapseRepeats(leet);
            for (String bw : collapsedWords) {
                if (collapsed.contains(bw)) return false;
            }

            for (Pattern p : customPatterns) {
                if (p.matcher(lower).find() || p.matcher(leet).find()) return false;
            }

            for (Pattern p : autoRepeatPatterns) {
                if (p.matcher(leet).find()) return false;
            }

            for (Pattern p : autoSepPatterns) {
                if (p.matcher(lower).find()) return false;
            }

            if (Config.getBadwordAutoDetectionMode().equals("strict")) {
                for (Pattern p : autoGapPatterns) {
                    if (p.matcher(lower).find()) return false;
                }
            }
        }

        return true;
    }

    public static @Nullable String sanitize(@NotNull String name) {
        if (!initialized) reload();
        if (!Config.isBadwordFilterEnabled()) return null;
        if (rawWords.isEmpty() && customPatterns.isEmpty()) return null;
        if (isAllowed(name)) return null;

        List<String> pool = BotNameConfig.getNames();
        if (pool.isEmpty()) return null;

        Random rand = new Random();
        for (int i = 0; i < Math.min(10, pool.size()); i++) {
            String candidate = pool.get(rand.nextInt(pool.size()));
            if (candidate.length() <= 16 && candidate.matches("[a-zA-Z0-9_]+") && isAllowed(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    public static @Nullable String findBadword(@NotNull String name) {
        if (!initialized) reload();
        if (!Config.isBadwordFilterEnabled() || (rawWords.isEmpty() && customPatterns.isEmpty())) return null;

        String lower = name.toLowerCase();

        for (String bw : rawWords) {
            if (lower.contains(bw)) return bw;
        }

        String leet = normalize(lower);
        for (String bw : rawWords) {
            if (leet.contains(normalize(bw))) return bw;
        }

        if (Config.isBadwordAutoDetectionEnabled()
                && !Config.getBadwordAutoDetectionMode().equals("off")) {
            String collapsed = collapseRepeats(leet);
            for (String bw : rawWords) {
                if (collapsed.contains(collapseRepeats(normalize(bw)))) return bw;
            }

            for (int i = 0; i < customPatterns.size(); i++) {
                if (customPatterns.get(i).matcher(lower).find()
                        || customPatterns.get(i).matcher(leet).find()) return customPatternSrc.get(i);
            }
            for (int i = 0; i < autoRepeatPatterns.size(); i++) {
                if (autoRepeatPatterns.get(i).matcher(leet).find()) return autoRepeatWords.get(i);
            }
            for (int i = 0; i < autoSepPatterns.size(); i++) {
                if (autoSepPatterns.get(i).matcher(lower).find()) return autoSepWords.get(i);
            }
            if (Config.getBadwordAutoDetectionMode().equals("strict")) {
                for (int i = 0; i < autoGapPatterns.size(); i++) {
                    if (autoGapPatterns.get(i).matcher(lower).find()) return autoGapWords.get(i);
                }
            }
        }

        return null;
    }

    public static int getBadwordCount() {
        if (!initialized) reload();
        return rawWords.size() + customPatterns.size();
    }

    private static void addWord(@Nullable String word) {
        if (word == null || word.isBlank()) return;
        String lower = word.trim().toLowerCase();
        rawWords.add(lower);
        normWords.add(normalize(lower));
        collapsedWords.add(collapseRepeats(normalize(lower)));
    }

    private static void loadRemoteGlobalBadWords(@Nullable Plugin plugin) {
        String content = fetchRemoteGlobalList();
        if (content != null) {
            loadPlainTextWords(content);
            saveRemoteCache(plugin, content);
            return;
        }

        String cached = loadRemoteCache(plugin);
        if (cached != null) {
            FppLogger.warn("BadwordFilter: using cached remote global badword list (latest fetch" + " failed).");
            loadPlainTextWords(cached);
        }
    }

    private static @Nullable String fetchRemoteGlobalList() {
        String url = Config.badwordGlobalListUrl();
        if (url == null || url.isBlank()) {
            FppLogger.warn("BadwordFilter: global-list-url is blank; skipping remote badword fetch.");
            return null;
        }
        try {
            int timeout = Config.badwordGlobalListTimeoutMs();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", "FakePlayerPlugin/1.6.0")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            FppLogger.warn("BadwordFilter: failed to fetch remote global list - HTTP " + response.statusCode());
        } catch (Exception e) {
            FppLogger.warn("BadwordFilter: failed to fetch remote global list - " + e.getMessage());
        }
        return null;
    }

    private static void loadPlainTextWords(@Nullable String content) {
        if (content == null || content.isBlank()) return;
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String word = line == null ? "" : line.trim();
            if (word.isEmpty()) continue;
            if (word.startsWith("#") || word.startsWith(";")) continue;
            addWord(word);
        }
    }

    private static void saveRemoteCache(@Nullable Plugin plugin, @NotNull String content) {
        if (plugin == null) return;
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            File cacheFile = new File(plugin.getDataFolder(), REMOTE_CACHE_FILE);
            Files.writeString(cacheFile.toPath(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            FppLogger.warn("BadwordFilter: failed to save remote badword cache - " + e.getMessage());
        }
    }

    private static @Nullable String loadRemoteCache(@Nullable Plugin plugin) {
        if (plugin == null) return null;
        try {
            File cacheFile = new File(plugin.getDataFolder(), REMOTE_CACHE_FILE);
            if (!cacheFile.exists()) return null;
            return Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FppLogger.warn("BadwordFilter: failed to load remote badword cache - " + e.getMessage());
            return null;
        }
    }

    private static void loadBadWordsFile(@NotNull Plugin plugin) {
        try {
            File file = new File(plugin.getDataFolder(), "bad-words.yml");
            if (!file.exists()) {
                plugin.saveResource("bad-words.yml", false);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            loadWordsAndPatterns(yaml, file.getName());
        } catch (Exception e) {
            FppLogger.warn("BadwordFilter: failed to load bad-words.yml - " + e.getMessage());
        }
    }

    private static void loadWordsAndPatterns(@NotNull YamlConfiguration yaml, @NotNull String sourceName) {
        List<String> fileWords = yaml.getStringList("words");
        for (String w : fileWords) addWord(w);

        List<String> filePatterns = yaml.getStringList("patterns");
        for (String ps : filePatterns) {
            if (ps == null || ps.isBlank()) continue;
            try {
                customPatterns.add(Pattern.compile(ps, Pattern.CASE_INSENSITIVE));
                customPatternSrc.add(ps);
            } catch (PatternSyntaxException e) {
                FppLogger.warn("BadwordFilter: invalid pattern '" + ps + "' in " + sourceName + " - " + e.getMessage());
            }
        }
    }

    private static void buildAutoPatterns() {
        autoRepeatPatterns.clear();
        autoRepeatWords.clear();
        autoSepPatterns.clear();
        autoSepWords.clear();
        autoGapPatterns.clear();
        autoGapWords.clear();

        for (String word : rawWords) {
            String collapsed = collapseRepeats(normalize(word));
            if (collapsed.length() < 3) continue;
            try {
                autoRepeatPatterns.add(buildRepeatPattern(collapsed));
                autoRepeatWords.add(word);
                autoSepPatterns.add(buildSeparatedPattern(collapsed));
                autoSepWords.add(word);
                autoGapPatterns.add(buildSingleGapPattern(collapsed));
                autoGapWords.add(word);
            } catch (PatternSyntaxException e) {
                FppLogger.warn("BadwordFilter: failed to compile auto-pattern for '" + word + "': " + e.getMessage());
            }
        }
    }

    private static Pattern buildRepeatPattern(@NotNull String collapsedNorm) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < collapsedNorm.length(); i++) {
            sb.append(Pattern.quote(String.valueOf(collapsedNorm.charAt(i)))).append('+');
        }
        return Pattern.compile(sb.toString());
    }

    private static Pattern buildSeparatedPattern(@NotNull String collapsedNorm) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < collapsedNorm.length(); i++) {
            if (i > 0) sb.append("[^a-z]*");
            sb.append(Pattern.quote(String.valueOf(collapsedNorm.charAt(i)))).append('+');
        }
        return Pattern.compile(sb.toString());
    }

    private static Pattern buildSingleGapPattern(@NotNull String collapsedNorm) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < collapsedNorm.length(); i++) {
            if (i > 0) sb.append("[a-z0-9_]?");
            sb.append(Pattern.quote(String.valueOf(collapsedNorm.charAt(i)))).append('+');
        }
        return Pattern.compile(sb.toString());
    }

    static String normalize(@NotNull String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '0' -> sb.append('o');
                case '1' -> sb.append('i');
                case '2' -> sb.append('z');
                case '3' -> sb.append('e');
                case '4' -> sb.append('a');
                case '5' -> sb.append('s');
                case '6' -> sb.append('g');
                case '7' -> sb.append('t');
                case '8' -> sb.append('b');
                case '9' -> sb.append('g');
                case '_' -> {}
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String collapseRepeats(@NotNull String input) {
        if (input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder(input.length());
        char prev = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != prev) {
                sb.append(c);
                prev = c;
            }
        }
        return sb.toString();
    }
}
