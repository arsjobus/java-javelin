package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole project request from the user's side without a window: ask for a feature, see exactly
 * what would be sent, get a multi-file proposal, survive a change made meanwhile, apply it, take it
 * back. The provider is scripted, so no model is needed.
 */
class ProjectAiWorkflowTest {
  @TempDir Path dir;
  static final String EDITOR = "src/main/java/demo/editor/Editor.java";
  static final String UML = "src/main/java/demo/diagram/UmlGenerator.java";
  static final String UML_TEST = "src/test/java/demo/diagram/UmlGeneratorTest.java";
  static final String REQUEST = "Add a UML generator to the editor";

  private static String edit(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  private String answer() {
    return "I add UmlGenerator, wire it into the Editor and test it.\n\n"
        + "=== FILE: "
        + EDITOR
        + " ===\n"
        + edit(
            "  public String highlight() {",
            "  public String uml(demo.parse.ClassModel model) {\n"
                + "    return new demo.diagram.UmlGenerator().generate(model);\n"
                + "  }\n\n"
                + "  public String highlight() {")
        + "\n=== NEW FILE: "
        + UML
        + " ===\n"
        + "```java\n"
        + "package demo.diagram;\n\n"
        + "import demo.parse.ClassModel;\n\n"
        + "public class UmlGenerator {\n"
        + "  public String generate(ClassModel model) {\n"
        + "    return \"@startuml\\n"
        + "class \" + model.name() + \"\\n"
        + "@enduml\\n"
        + "\";\n"
        + "  }\n"
        + "}\n"
        + "```\n\n"
        + "=== NEW FILE: "
        + UML_TEST
        + " ===\n```java\npackage demo.diagram;\n\nclass UmlGeneratorTest {}\n```\n";
  }

  @Test
  void featureRequestToReviewedMultiFileChangeAndBack() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ScriptedProvider provider = new ScriptedProvider();
    provider.limit = java.util.OptionalInt.of(262_144);
    Settings settings = new Settings(dir.resolveSibling("wf-settings.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    ProjectAiService service =
        new ProjectAiService(provider, AiConfig.from(settings), ProjectAiConfig.from(settings));
    ProjectWorkspace ws = new ProjectWorkspace(dir, ProjectAiConfig.from(settings).scan());

    // 1. what would be sent is decided and shown before anything leaves
    ProjectPlan plan =
        service.prepare(ws, ContextMode.SMART, REQUEST, Adjustments.NONE, new AiCancel());
    assertTrue(provider.requests.isEmpty());
    assertTrue(
        plan.context().includedPaths().contains("src/main/java/demo/diagram/DiagramRenderer.java"));
    assertTrue(plan.context().includedPaths().contains("ARCHITECTURE.md"));
    assertFalse(plan.preview().contains("API_KEY"));

    // 2. the model answers with changes to several files; nothing is written
    provider.answer = answer();
    ProjectAiResult result = service.run(plan, ws, s -> {}, new AiCancel());
    ProjectProposal proposal = result.proposal();
    assertNotNull(proposal);
    assertEquals(
        List.of(EDITOR, UML, UML_TEST), proposal.changes().stream().map(FileChange::path).toList());
    assertFalse(Files.exists(dir.resolve(UML)));

    // 3. meanwhile the user edits another part of Editor.java
    String editorBefore = Files.readString(dir.resolve(EDITOR));
    String edited =
        editorBefore.replace(
            "public class Editor {", "public class Editor { // the user's own comment");
    Files.writeString(dir.resolve(EDITOR), edited);
    ProjectRefresh.Result refreshed =
        ProjectRefresh.check(result.snapshot(), ws.index(), ws.reader(), ws.options());
    assertTrue(refreshed.isStale(), "the answer was given for a file that has changed");
    assertEquals(List.of(EDITOR), refreshed.changedSentFiles());

    // 4. the review: the change to Editor still fits by content, the new files are fine
    List<ProposalApplier.Check> checks =
        ProposalApplier.check(ws.reader(), proposal, Set.of(), ws.options());
    assertEquals(ProposalApplier.Status.REBASED, checks.get(0).status());
    assertEquals(ProposalApplier.Status.OK, checks.get(1).status());
    assertEquals(ProposalApplier.Status.OK, checks.get(2).status());

    // 5. the user approves two of the three files
    AppliedChanges applied = ProposalApplier.apply(ws.reader(), checks, Set.of(EDITOR, UML));
    String editorNow = Files.readString(dir.resolve(EDITOR));
    assertTrue(
        editorNow.contains("the user's own comment") && editorNow.contains("public String uml("));
    assertTrue(Files.readString(dir.resolve(UML)).contains("@startuml"));
    assertFalse(Files.exists(dir.resolve(UML_TEST)), "not approved, not written");

    // 6. the project's picture is refreshed and sees the new file
    ws.refresh(ws.options());
    assertNotNull(ws.index().find(UML));

    // 7. undo: the AI's changes go, the user's comment stays
    AppliedChanges.Rollback rollback = applied.rollback(ws.reader());
    assertEquals(2, rollback.restored().size());
    assertEquals(edited, Files.readString(dir.resolve(EDITOR)));
    assertFalse(Files.exists(dir.resolve(UML)));
  }

  @Test
  void aFailedRequestLeavesTheProjectExactlyAsItWas() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ScriptedProvider provider = new ScriptedProvider();
    provider.failure = new AiException(AiException.Kind.UNAVAILABLE, "Ollama is not running");
    Settings settings = new Settings(dir.resolveSibling("wf2-settings.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    ProjectAiService service =
        new ProjectAiService(provider, AiConfig.from(settings), ProjectAiConfig.from(settings));
    ProjectWorkspace ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    ProjectPlan plan =
        service.prepare(ws, ContextMode.FULL_PROJECT, REQUEST, Adjustments.NONE, new AiCancel());
    String before = Files.readString(dir.resolve(EDITOR));
    try {
      service.run(plan, ws, s -> {}, new AiCancel());
      org.junit.jupiter.api.Assertions.fail("the provider was down");
    } catch (AiException expected) {
      assertEquals(AiException.Kind.UNAVAILABLE, expected.kind());
    }
    assertEquals(before, Files.readString(dir.resolve(EDITOR)));
    assertEquals(plan.context().snapshot().sentHashes().size(), plan.context().included().size());
  }
}
