package me.bill.fakePlayerPlugin.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FlagParser {

    private final String[] rawArgs;

    private final Map<String, String> deprecations = new LinkedHashMap<>();
    private final List<String[]> conflictGroups = new ArrayList<>();

    private final Map<String, String> flagValues = new LinkedHashMap<>();
    private final Set<String> presentFlags = new LinkedHashSet<>();
    private final List<String> positionals = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private boolean parsed = false;

    public FlagParser(String[] args) {
        this.rawArgs = args;
    }

    public FlagParser deprecate(String old, String canonical) {
        deprecations.put(old.toLowerCase(), canonical.toLowerCase());
        return this;
    }

    public FlagParser parse() {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < rawArgs.length; i++) {
            String token = rawArgs[i];
            if (!token.startsWith("--")) {
                positionals.add(token);
                continue;
            }

            String flag = token.toLowerCase();

            if (deprecations.containsKey(flag)) {
                String canonical = deprecations.get(flag);
                warnings.add("⚠ <yellow>"
                        + token
                        + "</yellow><gray> is deprecated, use <white>"
                        + canonical
                        + "</white><gray> instead.");
                flag = canonical;
            }

            if (seen.contains(flag)) {
                errors.add("<red>Duplicate flag: <white>" + flag);
                continue;
            }
            seen.add(flag);
            presentFlags.add(flag);

            if (i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith("--")) {
                flagValues.put(flag, rawArgs[++i]);
            } else {
                flagValues.put(flag, null);
            }
        }

        for (String[] group : conflictGroups) {
            List<String> found = new ArrayList<>();
            for (String f : group) {
                if (presentFlags.contains(f)) found.add(f);
            }
            if (found.size() > 1) {
                errors.add("<red>Cannot use " + String.join(" <dark_gray>and </dark_gray>", found) + " together.");
            }
        }

        parsed = true;
        return this;
    }

    public boolean hasErrors() {
        assertParsed();
        return !errors.isEmpty();
    }

    public String errorMessage() {
        assertParsed();
        return errors.isEmpty() ? "" : errors.get(0);
    }

    public List<String> errors() {
        assertParsed();
        return Collections.unmodifiableList(errors);
    }

    public List<String> warnings() {
        assertParsed();
        return Collections.unmodifiableList(warnings);
    }

    public boolean hasFlag(String flag) {
        assertParsed();
        return presentFlags.contains(flag.toLowerCase());
    }

    public String flag(String flag, String defaultValue) {
        assertParsed();
        String key = flag.toLowerCase();
        if (!presentFlags.contains(key)) return defaultValue;
        String v = flagValues.get(key);
        return v != null ? v : defaultValue;
    }

    public int intFlag(String flag, int defaultValue) {
        String raw = flag(flag, null);
        if (raw == null) return defaultValue;
        try {
            int v = Integer.parseInt(raw);
            if (v < 1) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            errors.add("<red>Invalid number for "
                    + flag.toLowerCase()
                    + ": <white>"
                    + raw
                    + "<red>. Expected a positive integer.");
            return defaultValue;
        }
    }

    public Optional<String> positional(int index) {
        assertParsed();
        return index < positionals.size() ? Optional.of(positionals.get(index)) : Optional.empty();
    }

    public List<String> positionals() {
        assertParsed();
        return Collections.unmodifiableList(positionals);
    }

    private void assertParsed() {
        if (!parsed) throw new IllegalStateException("FlagParser.parse() not yet called");
    }
}
