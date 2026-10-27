package com.javelin_ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TabHeaderTargetsTest {
    @Test
    void resolvesTheClickedHeaderRatherThanTheSelectedTab() {
        List<String> tabs = List.of("selected", "clicked", "other");

        assertEquals("clicked", TabHeaderTargets.at(tabs, 1).orElseThrow());
    }

    @Test
    void ignoresIndexesOutsideTheCurrentTabs() {
        List<String> tabs = List.of("only tab");

        assertTrue(TabHeaderTargets.at(tabs, -1).isEmpty());
        assertTrue(TabHeaderTargets.at(tabs, 1).isEmpty());
    }

    @Test
    void findsOtherTabsWithoutIncludingTheClickedTab() {
        String clicked = new String("clicked");
        List<String> tabs = List.of("first", clicked, "last");

        assertEquals(List.of("first", "last"), TabHeaderTargets.others(tabs, clicked));
    }
}
