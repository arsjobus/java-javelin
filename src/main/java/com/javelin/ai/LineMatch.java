package com.javelin.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a run of lines inside a longer list of lines, the way a person would: first exactly, then ignoring spaces at the
 * end of lines, then ignoring indentation. Models copy code well but often lose its indentation, so a change that has
 * to match "exactly" fails for reasons that do not matter. JavaFX-free.
 */
final class LineMatch {
    private LineMatch() {}

    /** Where the run was found ({@code index}) and how loosely ({@code tier}: 0 exact, 1 trailing spaces ignored, 2 indentation ignored). */
    record Found(int index, int tier) {}

    /** Every place at or after {@code from} where {@code needle} matches, at the strictest tier that matches anywhere. Empty when it matches at none. */
    static List<Found> candidates(List<String> hay, List<String> needle, int from) {
        for (int tier = 0; tier <= 2; tier++) {
            List<Found> found = new ArrayList<>();
            for (int i = Math.max(0, from); i + needle.size() <= hay.size(); i++)
                if (matches(hay, needle, i, tier)) found.add(new Found(i, tier));
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static boolean matches(List<String> hay, List<String> needle, int at, int tier) {
        for (int k = 0; k < needle.size(); k++) if (!same(hay.get(at + k), needle.get(k), tier)) return false;
        return true;
    }

    private static boolean same(String a, String b, int tier) {
        return switch (tier) {
            case 0 -> a.equals(b);
            case 1 -> a.stripTrailing().equals(b.stripTrailing());
            default -> a.strip().equals(b.strip());
        };
    }

    /** The leading spaces and tabs of a line. */
    static String indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return line.substring(0, n);
    }

    /** The indentation of the first line that has text, or "" when there is none. */
    static String firstIndent(List<String> lines) {
        for (String line : lines) if (!line.isBlank()) return indentOf(line);
        return "";
    }

    /**
     * Lines written for the indentation {@code want} moved to {@code have}: an indentation is added to, or taken from the
     * start of each line, when one of the two begins with the other. Anything else (tabs against spaces) is left alone.
     */
    static List<String> shiftIndent(List<String> lines, String have, String want) {
        if (have.equals(want)) return lines;
        List<String> out = new ArrayList<>(lines.size());
        if (have.startsWith(want)) {
            String extra = have.substring(want.length());
            for (String line : lines) out.add(line.isBlank() ? line : extra + line);
        } else if (want.startsWith(have)) {
            String extra = want.substring(have.length());
            for (String line : lines) out.add(line.startsWith(extra) ? line.substring(extra.length()) : line);
        } else {
            return lines;
        }
        return out;
    }

    static List<String> split(String text) { return new ArrayList<>(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))); }
}
