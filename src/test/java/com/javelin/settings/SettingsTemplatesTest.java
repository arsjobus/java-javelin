package com.javelin.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTemplatesTest {
    @TempDir Path dir;

    private static boolean everyLineIsCommentedOrBlank(String text) {
        return text.lines().allMatch(l -> l.isBlank() || l.startsWith("#"));
    }

    @Test void templatesChangeNothingUntilEdited() {
        Keybindings keys = new Keybindings(new Settings(dir.resolve("s.conf")));
        keys.register("file.save", "File > Save", "Ctrl+S");
        String user = SettingsTemplates.user(keys);
        assertTrue(everyLineIsCommentedOrBlank(user));
        assertTrue(user.contains("#key.file.save=Ctrl+S"));
        assertTrue(user.contains("#theme=light"));
        assertTrue(user.contains("#app.icon="));
        assertTrue(user.contains("#ai.model=") && user.contains("#ai.ollama.endpoint=http://127.0.0.1:11434"));
        assertTrue(everyLineIsCommentedOrBlank(SettingsTemplates.project()));
        assertTrue(SettingsTemplates.project().contains("#tree.exclude=.git,target"));
    }
}
