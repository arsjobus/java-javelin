package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAiConfigTest {
  @TempDir Path dir;

  private Settings settings() {
    return new Settings(dir.resolve("settings.conf"));
  }

  @Test
  void defaults() {
    ProjectAiConfig c = ProjectAiConfig.from(settings());
    assertEquals(32_768, c.contextTokens());
    assertEquals(4_096, c.reserveTokens());
    assertEquals(ScanOptions.defaults(), c.scan());
    assertTrue(ProjectAiConfig.validate(settings()).isEmpty());
    assertEquals(new ContextBudget(8_192, 32_768, 4_096), c.budget(8_192));
  }

  @Test
  void valuesAreReadAndClamped() {
    Settings s = settings();
    s.put(Scope.USER, "ai.contextTokens", "100000");
    s.put(Scope.USER, "ai.reserveTokens", "100");
    s.put(Scope.USER, "ai.project.maxFileBytes", "5");
    s.put(Scope.USER, "ai.project.maxFiles", "999999999");
    s.put(Scope.USER, "ai.project.exclude", " *.sql , docs/old/ ,, ");
    s.put(Scope.USER, "ai.project.gitignore", "false");
    ProjectAiConfig c = ProjectAiConfig.from(s);
    assertEquals(100_000, c.contextTokens());
    assertEquals(ContextBudget.MIN_RESERVE_TOKENS, c.reserveTokens());
    assertEquals(ScanOptions.MIN_MAX_FILE_BYTES, c.scan().maxFileBytes());
    assertEquals(ScanOptions.MAX_MAX_FILES, c.scan().maxFiles());
    assertEquals(List.of("*.sql", "docs/old/"), c.scan().exclude());
    assertFalse(c.scan().useGitignore());
  }

  @Test
  void problemsAreNamedByKey() {
    Settings s = settings();
    s.put(Scope.USER, "ai.contextTokens", "10");
    s.put(Scope.USER, "ai.reserveTokens", "lots");
    s.put(Scope.USER, "ai.project.maxFileBytes", "99999999999");
    s.put(Scope.USER, "ai.project.maxFiles", "1");
    s.put(Scope.USER, "ai.project.gitignore", "maybe");
    List<String> problems = ProjectAiConfig.validate(s);
    assertEquals(5, problems.size(), problems.toString());
    assertTrue(problems.get(0).startsWith("ai.contextTokens"));
    assertTrue(
        problems.get(1).startsWith("ai.reserveTokens") && problems.get(1).contains("whole number"));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("ai.project.gitignore")));
    ProjectAiConfig c = ProjectAiConfig.from(s);
    assertEquals(ContextBudget.DEFAULT_RESERVE_TOKENS, c.reserveTokens());
  }

  @Test
  void aProjectFileCannotChangeTheseSettings() throws IOException {
    Settings s = settings();
    Path project = Files.createDirectories(dir.resolve("proj"));
    s.setProject(project);
    Files.createDirectories(project.resolve(Settings.PROJECT_DIRECTORY));
    Files.writeString(
        project.resolve(Settings.PROJECT_DIRECTORY).resolve(Settings.FILE_NAME),
        "ai.contextTokens=262144\nai.project.exclude=nothing\nai.project.gitignore=false\n");
    s.reload();
    assertNull(s.getProject("ai.contextTokens"));
    ProjectAiConfig c = ProjectAiConfig.from(s);
    assertEquals(32_768, c.contextTokens());
    assertTrue(c.scan().useGitignore());
    assertTrue(Settings.isUserOnly("ai.project.exclude"));
    assertTrue(Settings.isUserOnly("ai.contextTokens"));
  }
}
