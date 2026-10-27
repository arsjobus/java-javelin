package com.javelin_ide.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
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
}
