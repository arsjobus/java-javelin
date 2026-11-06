package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.language.Json;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** What the model supports at most, and the window a project request asks for. */
class OllamaContextLimitTest {
  private static OllamaProvider provider(FakeOllama server) throws AiException {
    return new OllamaProvider(server.endpoint(), Duration.ofSeconds(10));
  }

  @Test
  void theModelsLargestContextIsReadFromShowAndOnlyItsNameIsSent() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.rawBody =
          "{\"model_info\":{\"general.architecture\":\"qwen3\",\"qwen3.context_length\":262144,\"qwen3.block_count\":36}}";
      OptionalInt limit = provider(server).contextLimit("qwen3.5:9b", new AiCancel());
      assertEquals(OptionalInt.of(262_144), limit);
      assertEquals(List.of("POST /api/show"), server.paths);
      assertEquals(Json.write(Json.obj("model", "qwen3.5:9b")), server.requestBodies.get(0));
    }
  }

  @Test
  void theLargestOfSeveralContextLengthsCounts() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.rawBody =
          "{\"model_info\":{\"a.context_length\":4096,\"b.context_length\":32768,\"c.other\":999999}}";
      assertEquals(OptionalInt.of(32_768), provider(server).contextLimit("m", new AiCancel()));
    }
  }

  @Test
  void anAnswerWithoutALimitIsUnknownNotAnError() throws Exception {
    for (String body :
        new String[] {
          "{}",
          "{\"model_info\":{}}",
          "{\"model_info\":{\"x.context_length\":\"big\"}}",
          "not json at all",
          "{\"model_info\":[1]}"
        }) {
      try (FakeOllama server = new FakeOllama()) {
        server.rawBody = body;
        assertEquals(OptionalInt.empty(), provider(server).contextLimit("m", new AiCancel()), body);
      }
    }
    try (FakeOllama server = new FakeOllama()) {
      server.status = 500;
      server.rawBody = "{\"error\":\"boom\"}";
      assertEquals(OptionalInt.empty(), provider(server).contextLimit("m", new AiCancel()));
    }
  }

  @Test
  void aModelThatIsNotInstalledIsReportedAsSuch() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.status = 404;
      server.rawBody = "{\"error\":\"model 'x' not found\"}";
      AiException ex =
          assertThrows(AiException.class, () -> provider(server).contextLimit("x", new AiCancel()));
      assertEquals(AiException.Kind.MODEL_MISSING, ex.kind());
    }
  }

  @Test
  void aCancelledLookupSendsNothingAndNothingListeningIsUnavailable() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      AiCancel cancel = new AiCancel();
      cancel.cancel();
      AiException ex =
          assertThrows(AiException.class, () -> provider(server).contextLimit("m", cancel));
      assertEquals(AiException.Kind.CANCELLED, ex.kind());
      assertTrue(server.paths.isEmpty());
    }
    OllamaProvider dead = new OllamaProvider("http://127.0.0.1:1", Duration.ofSeconds(5));
    assertEquals(
        AiException.Kind.UNAVAILABLE,
        assertThrows(AiException.class, () -> dead.contextLimit("m", new AiCancel())).kind());
  }

  @Test
  void theDefaultProviderDoesNotKnowTheLimit() throws Exception {
    AiProvider plain =
        new AiProvider() {
          public String id() {
            return "p";
          }

          public String displayName() {
            return "P";
          }

          public String location() {
            return "x";
          }

          public boolean isLocal() {
            return true;
          }

          public List<String> models(AiCancel c) {
            return List.of();
          }

          public AiReply complete(AiRequest r, java.util.function.Consumer<String> t, AiCancel c) {
            return new AiReply("", false);
          }
        };
    assertFalse(plain.contextLimit("m", new AiCancel()).isPresent());
  }

  @Test
  void aRequestThatCarriesItsOwnWindowIsAskedForExactlyThat() throws Exception {
    assertEquals(65_536, OllamaProvider.contextTokens(new AiRequest("m", "s", "p", 65_536)));
    assertEquals(2_048, OllamaProvider.contextTokens(new AiRequest("m", "s", "p", 100)));
    assertEquals(4_096, OllamaProvider.contextTokens(new AiRequest("m", "s", "p", 0)));
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("ok");
      provider(server).complete(new AiRequest("m", "s", "p", 100_000), x -> {}, new AiCancel());
      Object options = Json.get(Json.parse(server.requestBodies.get(0)), "options");
      assertEquals(100_000, Json.integer(options, "num_ctx", -1));
    }
  }

  @Test
  void aRequestWithoutAWindowKeepsTheOldSizingAndSize() {
    AiRequest r = new AiRequest("m", "abc", "defg");
    assertEquals(0, r.contextTokens());
    assertEquals(7, r.size());
  }
}
