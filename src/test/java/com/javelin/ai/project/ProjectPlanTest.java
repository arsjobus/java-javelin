package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPlanTest {
  @TempDir Path dir;

  @Test
  void aLongListOfFilesThatAreNotSentIsCutInThePreviewWithTheCount()
      throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    for (int i = 0; i < 350; i++) ProjectFixture.write(dir, "many/F" + i + ".txt", "x");
    Settings settings = new Settings(dir.resolveSibling("plan-settings.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    ProjectAiService service =
        new ProjectAiService(
            new ScriptedProvider(), AiConfig.from(settings), ProjectAiConfig.from(settings));
    ProjectWorkspace ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    ProjectPlan plan =
        service.prepare(ws, ContextMode.SMART, "string utility", Adjustments.NONE, new AiCancel());
    String preview = plan.preview();
    assertTrue(
        preview.contains("... and "),
        preview.substring(preview.indexOf("NOT sent"), preview.indexOf("NOT sent") + 200));
    assertTrue(preview.contains(" more\n"));
  }
}
