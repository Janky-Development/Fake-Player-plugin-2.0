package me.bill.fakePlayerPlugin.mining;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Parses what a player types for "which block(s)": a material name, a comma list, or a group alias
 * ({@code ores}, {@code logs}, {@code leaves}, {@code stone}, {@code dirt}, {@code sand}).
 */
public final class MaterialSelector {

    private MaterialSelector() {}

    /** @return the selected materials, or null when any token is unknown (see {@link #firstUnknown}). */
    public static @Nullable Set<Material> parse(String input) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String raw : input.split(",")) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            Set<Material> group = alias(t);
            if (group != null) {
                out.addAll(group);
                continue;
            }
            Material m = Material.matchMaterial(t);
            if (m == null || m.isAir() || !m.isBlock()) return null;
            out.add(m);
        }
        return out.isEmpty() ? null : out;
    }

    public static @Nullable String firstUnknown(String input) {
        for (String raw : input.split(",")) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty() || alias(t) != null) continue;
            Material m = Material.matchMaterial(t);
            if (m == null || m.isAir() || !m.isBlock()) return raw.trim();
        }
        return null;
    }

    private static @Nullable Set<Material> alias(String t) {
        switch (t) {
            case "ore", "ores" -> {
                Set<Material> s = EnumSet.noneOf(Material.class);
                for (Material m : Material.values()) {
                    if (m.isBlock() && (m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS)) s.add(m);
                }
                return s;
            }
            case "log", "logs", "wood" -> {
                return toSet(Tag.LOGS.getValues());
            }
            case "leaves" -> {
                return toSet(Tag.LEAVES.getValues());
            }
            case "stone" -> {
                return EnumSet.of(
                        Material.STONE,
                        Material.COBBLESTONE,
                        Material.DEEPSLATE,
                        Material.COBBLED_DEEPSLATE,
                        Material.ANDESITE,
                        Material.DIORITE,
                        Material.GRANITE,
                        Material.TUFF);
            }
            case "dirt" -> {
                return EnumSet.of(
                        Material.DIRT,
                        Material.GRASS_BLOCK,
                        Material.COARSE_DIRT,
                        Material.PODZOL,
                        Material.ROOTED_DIRT,
                        Material.MYCELIUM,
                        Material.DIRT_PATH);
            }
            case "sand" -> {
                return EnumSet.of(Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE);
            }
            default -> {
                return null;
            }
        }
    }

    private static Set<Material> toSet(Iterable<Material> values) {
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (Material m : values) s.add(m);
        return s;
    }

    /** Names of the group aliases (tab completion). */
    public static final java.util.List<String> ALIASES =
            java.util.List.of("ores", "logs", "leaves", "stone", "dirt", "sand");
}
