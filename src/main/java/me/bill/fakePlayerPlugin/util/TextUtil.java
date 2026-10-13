package me.bill.fakePlayerPlugin.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TextUtil {

    private TextUtil() {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** FPP 2.0 "Bot Console" theme - secondary accent (lime), used as the fallback color for untagged text. */

    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ" // a–z (26)
            + "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"; // A–Z (26)

    public static String toSmallCaps(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int idx = NORMAL.indexOf(c);
            sb.append(idx >= 0 ? SMALL_CAPS.charAt(idx) : c);
        }
        return sb.toString();
    }

    private static final Pattern TRAILING_HEX_TAG_SPACE = Pattern.compile("<#[0-9A-Fa-f]{6}>\\s*$");

    private static final Pattern TRAILING_HEX_TAG = Pattern.compile("<#[0-9A-Fa-f]{6}>$");

    private static final Pattern HEX_TAG_ANYWHERE = Pattern.compile("<#[0-9A-Fa-f]{6}>");

    private static final Pattern MINI_TAG_ANYWHERE = Pattern.compile("<[a-z_]+>");

    private static final Pattern BARE_HEX_SOLID = Pattern.compile("\\{(#[0-9A-Fa-f]{6})}");

    private static final Pattern OPEN_3DIGIT_HEX = Pattern.compile("<#([0-9A-Fa-f]{3})>");

    private static final Pattern CLOSE_3DIGIT_HEX = Pattern.compile("</#([0-9A-Fa-f]{3})>");

    public static Component colorize(String text) {
        if (text == null) return Component.empty();

        String converted = legacyToMiniMessage(text);
        return MM.deserialize(converted);
    }



    public static Component format(String text) {
        return colorize(toSmallCaps(text));
    }

    public static String legacyToMiniMessage(String s) {
        if (s == null || s.isEmpty()) return s;

        s = expand3DigitHexCodes(s);

        if (s.contains("{#")) {
            s = convertLpColorTags(s);
        }

        s = TRAILING_HEX_TAG_SPACE.matcher(s).replaceAll("");
        s = TRAILING_HEX_TAG.matcher(s).replaceAll("");

        boolean hasMiniMessageTags = s.indexOf('<') >= 0
                && (s.contains("<rainbow>")
                        || s.contains("<gradient")
                        || HEX_TAG_ANYWHERE.matcher(s).find()
                        || MINI_TAG_ANYWHERE.matcher(s).find());

        if (hasMiniMessageTags && (s.indexOf('&') >= 0 || s.indexOf('§') >= 0)) {
            s = convertMixedFormat(s);
            return s;
        }

        if (s.indexOf('§') >= 0) {
            Component legacy = LegacyComponentSerializer.legacySection().deserialize(s);
            return MM.serialize(legacy);
        }
        if (s.indexOf('&') >= 0) {
            Component legacy = LegacyComponentSerializer.legacyAmpersand().deserialize(s);
            return MM.serialize(legacy);
        }

        return s;
    }

    private static String convertMixedFormat(String s) {

        StringBuilder result = new StringBuilder();
        boolean inMiniMessageTag = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '<') {
                inMiniMessageTag = true;
                result.append(c);
                continue;
            } else if (c == '>') {
                inMiniMessageTag = false;
                result.append(c);
                continue;
            }

            if (inMiniMessageTag) {
                result.append(c);
                continue;
            }

            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                char code = s.charAt(i + 1);
                String miniTag = legacyCodeToMiniMessage(code);
                if (miniTag != null) {
                    result.append(miniTag);
                    i++;
                    continue;
                }
            }

            result.append(c);
        }

        return result.toString();
    }

    private static String legacyCodeToMiniMessage(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a', 'A' -> "<green>";
            case 'b', 'B' -> "<aqua>";
            case 'c', 'C' -> "<red>";
            case 'd', 'D' -> "<light_purple>";
            case 'e', 'E' -> "<yellow>";
            case 'f', 'F' -> "<white>";
            case 'k', 'K' -> "<obfuscated>";
            case 'l', 'L' -> "<bold>";
            case 'm', 'M' -> "<strikethrough>";
            case 'n', 'N' -> "<underlined>";
            case 'o', 'O' -> "<italic>";
            case 'r', 'R' -> "<reset>";
            default -> null;
        };
    }

    private static String expand3DigitHexCodes(String s) {
        if (s == null || s.indexOf('#') < 0) return s;

        Matcher m = OPEN_3DIGIT_HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex3 = m.group(1);
            String hex6 = String.format(
                    "%c%c%c%c%c%c",
                    hex3.charAt(0), hex3.charAt(0), hex3.charAt(1), hex3.charAt(1), hex3.charAt(2), hex3.charAt(2));
            m.appendReplacement(sb, "<#" + hex6 + ">");
        }
        m.appendTail(sb);
        s = sb.toString();

        m = CLOSE_3DIGIT_HEX.matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            String hex3 = m.group(1);
            String hex6 = String.format(
                    "%c%c%c%c%c%c",
                    hex3.charAt(0), hex3.charAt(0), hex3.charAt(1), hex3.charAt(1), hex3.charAt(2), hex3.charAt(2));
            m.appendReplacement(sb, "</#" + hex6 + ">");
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private static final Pattern LP_GRADIENT =
            Pattern.compile("\\{(#[0-9A-Fa-f]{6})>}(.*?)\\{(#[0-9A-Fa-f]{6})<}", Pattern.DOTALL);

    private static final Pattern LP_MALFORMED_TAG = Pattern.compile("\\{#[0-9A-Fa-f]{0,5}[<>]}");

    private static String convertLpColorTags(String s) {

        s = LP_MALFORMED_TAG.matcher(s).replaceAll("");

        s = convertLpGradients(s);

        s = BARE_HEX_SOLID.matcher(s).replaceAll("<$1>");

        return s;
    }

    private static String convertLpGradients(String s) {
        Matcher m = LP_GRADIENT.matcher(s);
        if (!m.find()) return s;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String startColor = m.group(1);
            String content = convertLpGradients(m.group(2));
            String endColor = m.group(3);
            m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(
                            "<gradient:" + startColor + ":" + endColor + ">" + content + "</gradient>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
