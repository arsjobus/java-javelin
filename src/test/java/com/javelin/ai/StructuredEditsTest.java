package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.Json;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When edits are asked for as JSON, and what is sent to the provider when they are. */
class StructuredEditsTest {
  @TempDir Path dir;

  private static final String DOC = "class Foo {\n    int add(int a, int b) { return a - b; }\n}\n";
  private static final AiSource SOURCE =
      new AiSource("src/Foo.java", "Foo.java", "java", DOC, 0, DOC.length());

  private AiService service(FakeAiProvider provider, boolean setting) {
    Settings settings = new Settings(dir.resolve("settings.conf"));
    settings.put(Scope.USER, "ai.model", "fake-model");
    if (setting) settings.put(Scope.USER, AiConfig.STRUCTURED_EDITS, "true");
    return new AiService(provider, AiConfig.from(settings));
  }

  @Test
  void textIsTheDefault() throws Exception {
    FakeAiProvider provider = new FakeAiProvider();
    provider.schemaOutput = true;
    assertFalse(AiConfig.from(new Settings(dir.resolve("none.conf"))).structuredEdits());
    AiRequest request =
        service(provider, false).prepare(AiAction.PROPOSE_CHANGE, SOURCE, "fix add").request();
    assertNull(request.responseSchema());
    assertFalse(request.prompt().contains("Reply with JSON only"));
  }

  @Test
  void theSettingAndAProviderThatCanKeepToAShapeAreBothNeeded() throws Exception {
    FakeAiProvider cannot = new FakeAiProvider();
    assertNull(
        service(cannot, true)
            .prepare(AiAction.PROPOSE_CHANGE, SOURCE, "fix add")
            .request()
            .responseSchema(),
        "a provider that cannot hold an answer to a shape is asked in text");
    FakeAiProvider can = new FakeAiProvider();
    can.schemaOutput = true;
    AiRequest request =
        service(can, true).prepare(AiAction.PROPOSE_CHANGE, SOURCE, "fix add").request();
    assertEquals(JsonEdits.SCHEMA, request.responseSchema());
    assertTrue(request.prompt().contains("Reply with JSON only"));
    assertTrue(request.prompt().contains("\"file\": \"src/Foo.java\""), request.prompt());
    assertTrue(request.prompt().contains("fix add"));
    assertFalse(request.prompt().contains("<<<<<<< SEARCH"), "one format is asked for, not two");
  }

  @Test
  void onlyProposeChangeAsksForJson() throws Exception {
    FakeAiProvider can = new FakeAiProvider();
    can.schemaOutput = true;
    AiService service = service(can, true);
    for (AiAction action : AiAction.values()) {
      if (action == AiAction.PROPOSE_CHANGE) continue;
      String instruction = action.needsInstruction() ? "do it" : null;
      AiRequest request = service.prepare(action, SOURCE, instruction).request();
      assertNull(request.responseSchema(), action.name());
    }
  }

  @Test
  void aJsonAnswerFromTheModelBecomesAProposal() throws Exception {
    FakeAiProvider can = new FakeAiProvider();
    can.schemaOutput = true;
    can.answer =
        Json.write(
            Json.obj(
                "explanation",
                "Add instead of subtract.",
                "edits",
                java.util.List.of(
                    Json.obj(
                        "file", "Foo.java",
                        "anchor", "int add(int a, int b) { return a - b; }",
                        "old", "    int add(int a, int b) { return a - b; }",
                        "new", "    int add(int a, int b) { return a + b; }"))));
    AiService service = service(can, true);
    AiResult r =
        service.run(
            service.prepare(AiAction.PROPOSE_CHANGE, SOURCE, "fix add"), s -> {}, new AiCancel());
    assertNotNull(r.proposal(), r.note());
    assertEquals(DOC.replace("a - b", "a + b"), r.proposal().replacement());
  }

  @Test
  void theRequestSchemaCanBeReplacedAndBlankMeansNone() {
    AiRequest base = new AiRequest("m", "s", "p");
    assertNull(base.responseSchema());
    assertEquals("{}", base.withResponseSchema("{}").responseSchema());
    assertNull(base.withResponseSchema("  ").responseSchema());
    assertEquals(
        "{}", base.withResponseSchema("{}").withSampling(Sampling.PRODUCT).responseSchema());
  }

  @Test
  void ollamaSendsTheShapeAsFormatOnlyWhenOneIsGiven() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("ok");
      Settings settings = new Settings(dir.resolve("o.conf"));
      settings.put(Scope.USER, AiConfig.ENDPOINT, server.endpoint());
      AiProvider ollama = AiProviders.create(AiConfig.from(settings));
      assertTrue(ollama.supportsSchemaOutput());
      ollama.complete(new AiRequest("m", "s", "p"), s -> {}, new AiCancel());
      assertNull(Json.get(Json.parse(server.requestBodies.get(0)), "format"));
      ollama.complete(
          new AiRequest("m", "s", "p").withResponseSchema(JsonEdits.SCHEMA),
          s -> {},
          new AiCancel());
      Object format = Json.get(Json.parse(server.requestBodies.get(1)), "format");
      assertEquals("object", Json.string(format, "type"));
      assertNotNull(Json.get(Json.get(format, "properties"), "edits"));
    }
  }

  @Test
  void aProviderSaysNoUnlessItIsToldToSayYes() {
    assertFalse(new FakeAiProvider().supportsSchemaOutput());
  }
}
