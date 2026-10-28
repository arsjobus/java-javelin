package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectChangeTest {
    @Test
    void openingFirstProjectClosesProjectSessions() {
        AtomicInteger closes = new AtomicInteger();

        ProjectChange.closeSessionsIfChanged(null, new File("project-a"), closes::incrementAndGet);

        assertEquals(1, closes.get());
    }

    @Test
    void switchingToAnotherProjectClosesProjectSessions() {
        AtomicInteger closes = new AtomicInteger();

        ProjectChange.closeSessionsIfChanged(new File("project-a"), new File("project-b"), closes::incrementAndGet);

        assertEquals(1, closes.get());
    }

    @Test
    void reopeningSameNormalizedProjectKeepsProjectSessions() {
        AtomicInteger closes = new AtomicInteger();
        File current = new File("project-a/.");
        File sameProject = Path.of("project-a").toFile();

        ProjectChange.closeSessionsIfChanged(current, sameProject, closes::incrementAndGet);

        assertEquals(0, closes.get());
    }

    @Test
    void onlyADifferentOpenProjectReplacesTheCurrentOne() {
        assertFalse(ProjectChange.replacesOpenProject(null, new File("project-a")));
        assertFalse(ProjectChange.replacesOpenProject(new File("project-a/."), Path.of("project-a").toFile()));
        assertTrue(ProjectChange.replacesOpenProject(new File("project-a"), new File("project-b")));
    }

    @Test
    void closeAllInOrderClosesEveryItemWhenAllAreAccepted() {
        List<String> open = new ArrayList<>(List.of("a", "b", "c"));

        boolean done = ProjectChange.closeAllInOrder(open, item -> true, open::remove);

        assertTrue(done);
        assertTrue(open.isEmpty());
    }

    @Test
    void closeAllInOrderStopsAtTheFirstRefusalAndLeavesTheRestOpen() {
        List<String> open = new ArrayList<>(List.of("a", "dirty", "c"));
        List<String> asked = new ArrayList<>();

        boolean done = ProjectChange.closeAllInOrder(open, item -> { asked.add(item); return !item.equals("dirty"); }, open::remove);

        assertFalse(done);
        assertEquals(List.of("a", "dirty"), asked, "nothing after the refusal is asked about");
        assertEquals(List.of("dirty", "c"), open);
    }

    @Test
    void closeAllInOrderAcceptsAnEmptyList() {
        assertTrue(ProjectChange.closeAllInOrder(List.<String>of(), item -> false, item -> {}));
    }
}
