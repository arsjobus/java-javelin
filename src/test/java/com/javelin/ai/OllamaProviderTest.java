package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.Json;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real HTTP client against a fake Ollama on a loopback port. */
class OllamaProviderTest {
  @TempDir Path dir;

  private static final AiRequest REQUEST = new AiRequest("llama3", "system text", "user text");

  private static OllamaProvider provider(FakeOllama server, int seconds) throws AiException {
    return new OllamaProvider(server.endpoint(), Duration.ofSeconds(seconds));
  }

  @Test
  void theAnswerArrivesPieceByPiece() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("Hel", "lo ", "wörld \uD83D\uDE00");
      List<String> pieces = new ArrayList<>();
      AiReply reply = provider(server, 10).complete(REQUEST, pieces::add, new AiCancel());
      assertEquals(List.of("Hel", "lo ", "wörld \uD83D\uDE00"), pieces);
      assertEquals("Hello wörld \uD83D\uDE00", reply.text());
      assertFalse(reply.truncated());
    }
  }

  @Test
  void theRequestIsAChatCallWithBothMessagesAndASizedContext() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("ok");
      provider(server, 10).complete(REQUEST, s -> {}, new AiCancel());
      assertEquals(List.of("POST /api/chat"), server.paths);
      Object json = Json.parse(server.requestBodies.get(0));
      assertEquals("llama3", Json.string(json, "model"));
      assertTrue(Json.bool(json, "stream"));
      List<Object> messages = Json.asArray(Json.get(json, "messages"));
      assertEquals("system", Json.string(messages.get(0), "role"));
      assertEquals("system text", Json.string(messages.get(0), "content"));
      assertEquals("user", Json.string(messages.get(1), "role"));
      assertEquals("user text", Json.string(messages.get(1), "content"));
      assertEquals("medium", Json.string(json, "think"));
      assertNotNull(Json.get(Json.get(json, "options"), "num_ctx"));
    }
  }

  @Test
  void theConfiguredThinkLevelIsSentToOllama() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("ok");
      Settings settings = new Settings(dir.resolve("settings.conf"));
      settings.put(Scope.USER, AiConfig.ENDPOINT, server.endpoint());
      settings.put(Scope.USER, AiConfig.THINK_LEVEL, "xhigh");
      AiProviders.create(AiConfig.from(settings)).complete(REQUEST, s -> {}, new AiCancel());
      Object json = Json.parse(server.requestBodies.get(0));
      assertEquals("xhigh", Json.string(json, "think"));
    }
  }

  @Test
  void theContextWindowIsSizedFromTheRequest() {
    assertEquals(4096, OllamaProvider.contextTokens(new AiRequest("m", "s", "p")));
    assertEquals(
        7168,
        OllamaProvider.contextTokens(
            new AiRequest("m", "s", "x".repeat(15_000)))); // 5000 + 2048, rounded up to 1024s
    assertEquals(32768, OllamaProvider.contextTokens(new AiRequest("m", "s", "x".repeat(500_000))));
  }

  @Test
  void aReplyThatRanOutOfRoomIsMarkedTruncated() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = new String[] {FakeOllama.chunk("partial"), FakeOllama.done("length")};
      assertTrue(provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()).truncated());
    }
  }

  @Test
  void anUninstalledModelSaysHowToInstallIt() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.status = 404;
      server.rawBody = "{\"error\":\"model 'llama3' not found\"}";
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertEquals(AiException.Kind.MODEL_MISSING, ex.kind());
      assertTrue(ex.getMessage().contains("ollama pull llama3"));
    }
  }

  @Test
  void otherServerErrorsAreReportedWithTheirMessage() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.status = 500;
      server.rawBody = "{\"error\":\"out of memory\"}";
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind());
      assertTrue(ex.getMessage().contains("500") && ex.getMessage().contains("out of memory"));
    }
  }

  @Test
  void anErrorInTheMiddleOfTheStreamIsReported() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = new String[] {FakeOllama.chunk("start"), "{\"error\":\"model crashed\"}"};
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertTrue(ex.getMessage().contains("model crashed"));
    }
  }

  @Test
  void aStreamThatEndsBeforeDoneIsNotAnAnswer() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = new String[] {FakeOllama.chunk("half an ans")};
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind());
    }
  }

  @Test
  void somethingThatIsNotOllamaIsRecognised() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.rawBody = "<html>hello</html>";
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind());
      assertTrue(ex.getMessage().contains("did not answer like Ollama"));
    }
  }

  @Test
  void aReplyBeyondTheLimitIsStopped() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      String big = "x".repeat(100_000);
      List<String> lines = new ArrayList<>();
      for (int i = 0; i < 12; i++) lines.add(FakeOllama.chunk(big));
      server.lines = lines.toArray(new String[0]);
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(server, 10).complete(REQUEST, s -> {}, new AiCancel()));
      assertTrue(ex.getMessage().contains("longer than"));
    }
  }

  @Test
  void nothingListeningMeansOllamaIsNotRunning() throws Exception {
    String endpoint;
    try (FakeOllama server = new FakeOllama()) {
      endpoint = server.endpoint();
    }
    AiException ex =
        assertThrows(
            AiException.class,
            () ->
                new OllamaProvider(endpoint, Duration.ofSeconds(5))
                    .complete(REQUEST, s -> {}, new AiCancel()));
    assertEquals(AiException.Kind.UNAVAILABLE, ex.kind());
    assertTrue(ex.getMessage().contains("ollama serve"));
  }

  @Test
  void aServerThatGoesQuietTimesOut() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.stall = true;
      long start = System.nanoTime();
      AiException ex =
          assertThrows(
              AiException.class,
              () ->
                  new OllamaProvider(server.endpoint(), Duration.ofMillis(400))
                      .complete(REQUEST, s -> {}, new AiCancel()));
      assertEquals(AiException.Kind.TIMEOUT, ex.kind());
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
      assertTrue(ex.getMessage().contains("ai.timeoutSeconds"));
    }
  }

  @Test
  void cancellingStopsAWaitingRequest() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.stall = true;
      AiCancel cancel = new AiCancel();
      AtomicReference<AiException> failure = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  provider(server, 60).complete(REQUEST, s -> {}, cancel);
                } catch (AiException ex) {
                  failure.set(ex);
                }
              });
      worker.start();
      assertTrue(server.requestArrived.await(5, java.util.concurrent.TimeUnit.SECONDS));
      cancel.cancel();
      worker.join(5000);
      assertFalse(worker.isAlive(), "cancelling must end the request promptly");
      assertEquals(AiException.Kind.CANCELLED, failure.get().kind());
    }
  }

  @Test
  void cancellingWhileTheAnswerStreamsKeepsWhatArrived() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("a", "b", "c");
      AiCancel cancel = new AiCancel();
      List<String> pieces = new ArrayList<>();
      AiException ex =
          assertThrows(
              AiException.class,
              () ->
                  provider(server, 10)
                      .complete(
                          REQUEST,
                          piece -> {
                            pieces.add(piece);
                            cancel.cancel();
                          },
                          cancel));
      assertEquals(AiException.Kind.CANCELLED, ex.kind());
      assertEquals("a", pieces.get(0));
    }
  }

  @Test
  void anAlreadyCancelledRequestSendsNothing() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      AiCancel cancel = new AiCancel();
      cancel.cancel();
      assertThrows(
          AiException.class, () -> provider(server, 10).complete(REQUEST, s -> {}, cancel));
      assertThrows(AiException.class, () -> provider(server, 10).models(cancel));
      assertEquals(List.of(), server.paths);
    }
  }

  @Test
  void installedModelsAreListedSorted() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.rawBody =
          "{\"models\":[{\"name\":\"qwen2.5-coder:7b\"},{\"name\":\"llama3:latest\"},{\"model\":\"mistral\"},{\"size\":1}]}";
      assertEquals(
          List.of("llama3:latest", "mistral", "qwen2.5-coder:7b"),
          provider(server, 10).models(new AiCancel()));
      assertEquals(List.of("GET /api/tags"), server.paths);
    }
  }

  @Test
  void noModelsInstalledIsAnEmptyList() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.rawBody = "{\"models\":[]}";
      assertEquals(List.of(), provider(server, 10).models(new AiCancel()));
    }
  }

  @Test
  void aRemoteAddressCannotEvenBeCreated() {
    assertEquals(
        AiException.Kind.REFUSED,
        assertThrows(
                AiException.class,
                () -> new OllamaProvider("http://example.com:11434", Duration.ofSeconds(5)))
            .kind());
    assertEquals(
        AiException.Kind.REFUSED,
        assertThrows(
                AiException.class,
                () -> new OllamaProvider("http://192.168.0.9:11434", Duration.ofSeconds(5)))
            .kind());
  }

  @Test
  void theProviderDescribesItselfForTheIndicators() throws Exception {
    OllamaProvider p = new OllamaProvider("http://localhost:11434", Duration.ofSeconds(5));
    assertEquals("ollama", p.id());
    assertEquals("Ollama", p.displayName());
    assertEquals("localhost:11434", p.location());
    assertTrue(p.isLocal());
  }
}
