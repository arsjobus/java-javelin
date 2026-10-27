package com.javelin_ide;

import java.util.List;
import java.util.Optional;

/** Resolves a tab header's position to the tab it represents. */
final class TabHeaderTargets {
    private TabHeaderTargets() {}

    static <T> Optional<T> at(List<T> tabs, int headerIndex) {
        return headerIndex >= 0 && headerIndex < tabs.size()
            ? Optional.ofNullable(tabs.get(headerIndex))
            : Optional.empty();
    }

    static <T> List<T> others(List<T> tabs, T keep) {
        return tabs.stream().filter(tab -> tab != keep).toList();
    }
}
