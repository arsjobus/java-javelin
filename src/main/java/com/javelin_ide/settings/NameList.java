package com.javelin_ide.settings;

import java.util.LinkedHashSet;
import java.util.Set;

/** Reads comma-separated setting values such as {@code tree.exclude}. JavaFX-free. */
public final class NameList {
    private NameList() {}

    /** The non-blank, whitespace-stripped entries in order, without duplicates. */
    public static Set<String> parse(String commaSeparated) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : commaSeparated.split(",")) if (!name.isBlank()) names.add(name.strip());
        return names;
    }
}
