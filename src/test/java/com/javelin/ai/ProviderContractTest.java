package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the pipeline may rely on from every {@link AiProvider}, run against the Ollama provider (a
 * fake server on a loopback port) and the external provider (a fake transport with consent given).
 * The pipeline reads these answers and never a provider's name, so a provider that keeps this
 * contract can be plugged in. It shows the plumbing; it says nothing about the quality of any
 * model's edits.
 */
class ProviderContractTest {
  /** One provider under test, with a way to script what its service answers. */
  interface Subject extends AutoCloseable {
    AiProvider provider();

    void answerWith(String text);

    void listModels(String... ids);

    int requestsSent();

    @Override
    void close();
  }

  private static final class OllamaSubject implements Subject {
    private final FakeOllama server;
    private final AiProvider provider;

    OllamaSubject() throws Exception {
      server = new FakeOllama();
      provider = new OllamaProvider(server.endpoint(), Duration.ofSeconds(10), "none");
    }

    public AiProvider provider() {
      return provider;
    }

    public void answerWith(String text) {
      List<String> lines = new ArrayList<>();
      lines.add(FakeOllama.chunk(text));
      lines.add(
          "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
              + "\"prompt_eval_count\":11,\"eval_count\":4}");
      server.lines = lines.toArray(new String[0]);
    }

    public void listModels(String... ids) {
      StringBuilder json = new StringBuilder("{\"models\":[");
      for (int i = 0; i < ids.length; i++)
        json.append(i == 0 ? "" : ",").append("{\"name\":\"").append(ids[i]).append("\"}");
      server.rawBody = json.append("]}").toString();
    }

    public int requestsSent() {
      return server.paths.size();
    }

    public void close() {
      server.close();
    }
  }

  private static final class ExternalSubject implements Subject {
    private final ScriptedTransport transport = new ScriptedTransport();
    private final ExternalProvider provider;

    ExternalSubject(Path dir) throws Exception {
      Path project = Files.createDirectory(dir.resolve("project"));
      ExternalConsent consent = new ExternalConsent(dir.resolve("ai-consent.tsv"));
      consent.grant(project, ExternalProvider.ID, ExternalProvider.HOST);
      provider =
          new ExternalProvider(transport, "key", Duration.ofSeconds(10), project, consent, false);
    }

    public AiProvider provider() {
      return provider;
    }

    public void answerWith(String text) {
      transport.next =
          new ExternalTransport.Response(
              200, ScriptedTransport.messageJson(text, "end_turn", 11, 4));
    }

    public void listModels(String... ids) {
      StringBuilder json = new StringBuilder("{\"data\":[");
      for (int i = 0; i < ids.length; i++)
        json.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i]).append("\"}");
      transport.next = new ExternalTransport.Response(200, json.append("]}").toString());
    }

    public int requestsSent() {
      return transport.calls.size();
    }

    public void close() {}
  }

  private static Subject make(String kind, Path dir) throws Exception {
    return kind.equals("ollama") ? new OllamaSubject() : new ExternalSubject(dir);
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aProviderDescribesItself(String kind, @TempDir Path dir) throws Exception {
    try (Subject s = make(kind, dir)) {
      AiProvider p = s.provider();
      assertFalse(p.id().isBlank());
      assertFalse(p.displayName().isBlank());
      assertFalse(p.location().isBlank());
      String host = p.location().replaceAll(":\\d+$", "");
      assertEquals(
          LoopbackEndpoint.isLoopbackHost(host),
          p.isLocal(),
          "isLocal must agree with where requests go");
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void theTextPassedToTheListenerIsTheText(String kind, @TempDir Path dir) throws Exception {
    try (Subject s = make(kind, dir)) {
      s.answerWith("the answer");
      StringBuilder heard = new StringBuilder();
      AiReply reply =
          s.provider().complete(new AiRequest("m", "sys", "prompt"), heard::append, new AiCancel());
      assertEquals("the answer", reply.text());
      assertEquals(reply.text(), heard.toString());
      assertFalse(reply.truncated());
      assertEquals(1, s.requestsSent());
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aProviderThatReportsTokenCountsDoesReportThem(String kind, @TempDir Path dir)
      throws Exception {
    try (Subject s = make(kind, dir)) {
      s.answerWith("x");
      AiReply reply =
          s.provider().complete(new AiRequest("m", "sys", "prompt"), t -> {}, new AiCancel());
      if (s.provider().reportsTokenCounts()) {
        assertTrue(reply.usage().isKnown());
        assertEquals(new AiUsage(11, 4), reply.usage());
      } else {
        assertFalse(reply.usage().isKnown());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aCancelledRequestIsCancelledAndSendsNothing(String kind, @TempDir Path dir)
      throws Exception {
    try (Subject s = make(kind, dir)) {
      s.answerWith("never read");
      AiCancel cancel = new AiCancel();
      cancel.cancel();
      AiException ex =
          assertThrows(
              AiException.class,
              () -> s.provider().complete(new AiRequest("m", "sys", "prompt"), t -> {}, cancel));
      assertEquals(AiException.Kind.CANCELLED, ex.kind());
      assertEquals(0, s.requestsSent());
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void theModelListIsSorted(String kind, @TempDir Path dir) throws Exception {
    try (Subject s = make(kind, dir)) {
      s.listModels("zeta", "alpha", "mid");
      assertEquals(List.of("alpha", "mid", "zeta"), s.provider().models(new AiCancel()));
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aSchemaIsOnlyAskedForWhenTheProviderSaysItCanKeepToOne(String kind, @TempDir Path dir)
      throws Exception {
    try (Subject s = make(kind, dir)) {
      // The pipeline's rule (AiService, ProjectAiService): schema only if supportsSchemaOutput().
      // A request that carries one anyway must not fail on a provider that cannot use it.
      s.answerWith("{}");
      AiRequest request = new AiRequest("m", "s", "p").withResponseSchema("{\"type\":\"object\"}");
      AiReply reply = s.provider().complete(request, t -> {}, new AiCancel());
      assertFalse(reply.text().isEmpty());
    }
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void aProviderWithConsentInPlaceHasNothingMoreToAsk(String kind, @TempDir Path dir)
      throws Exception {
    try (Subject s = make(kind, dir)) {
      assertTrue(s.provider().consentNotice().isEmpty());
    }
  }

  static Stream<String> kinds() {
    return Stream.of("ollama", "external");
  }
}
