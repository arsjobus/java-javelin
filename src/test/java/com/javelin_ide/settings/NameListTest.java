package com.javelin_ide.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NameListTest {
    @Test void splitsAndStripsInOrder() {
        assertEquals(List.of(".git", "target", "node_modules"), List.copyOf(NameList.parse(" .git , target,node_modules ")));
    }

    @Test void dropsBlanksAndDuplicates() {
        assertEquals(List.of("a", "b"), List.copyOf(NameList.parse(",a,, b ,a,")));
    }

    @Test void emptyInputGivesNothing() {
        assertTrue(NameList.parse("").isEmpty());
        assertTrue(NameList.parse("  ,  ").isEmpty());
    }
}
