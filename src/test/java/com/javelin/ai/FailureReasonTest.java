package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.language.Json;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared failure vocabulary: every place that rejects a model's answer says why in the same
 * words, and the eval and later phases count by them.
 */
class FailureReasonTest {
  @Test
  void everyReasonHasASentenceForReports() {
    for (FailureReason r : FailureReason.values())
      assertTrue(r.description().endsWith("."), r + ": " + r.description());
  }

  @Test
  void anExceptionNamesItsReasonOrGetsOneFromItsKind() {
    assertEquals(
        FailureReason.WRONG_FILE,
        new AiException(AiException.Kind.REFUSED, FailureReason.WRONG_FILE, "x").reason());
    assertEquals(FailureReason.REFUSED, new AiException(AiException.Kind.REFUSED, "x").reason());
    assertEquals(FailureReason.TIMEOUT, new AiException(AiException.Kind.TIMEOUT, "x").reason());
    assertEquals(
        FailureReason.UNPARSEABLE, new AiException(AiException.Kind.BAD_RESPONSE, "x").reason());
    assertEquals(
        FailureReason.UNPARSEABLE,
        new AiException(AiException.Kind.BAD_RESPONSE, "x", new RuntimeException()).reason());
  }

  @Test
  void aProviderThatIsNotThereIsNotAFailureOfTheModel() {
    assertNull(new AiException(AiException.Kind.UNAVAILABLE, "x").reason());
    assertNull(new AiException(AiException.Kind.MODEL_MISSING, "x").reason());
    assertNull(new AiException(AiException.Kind.CANCELLED, "x").reason());
  }

  @Test
  void theParsersSayWhyTheyRefuse() {
    assertEquals(FailureReason.NO_EDIT_BLOCK, reason(() -> ResponseParser.parse("just words")));
    assertEquals(FailureReason.TRUNCATED, reason(() -> ResponseParser.parse("```java\nint x;\n")));
    assertEquals(
        FailureReason.NO_EDIT_BLOCK, reason(() -> ResponseParser.parse("```java\n\n```\n")));
    assertEquals(
        FailureReason.UNPARSEABLE, reason(() -> UnifiedPatch.apply("a\n", "no hunks here")));
    assertEquals(
        FailureReason.ANCHOR_NOT_FOUND,
        reason(() -> UnifiedPatch.apply("a\nb\n", "@@ -1,1 +1,1 @@\n-zzz\n+yyy\n")));
  }

  @Test
  void aSelectionOverTheLimitIsOversized() {
    AiSource source = new AiSource("A.java", "A.java", "java", "x".repeat(50), 0, 50);
    AiException ex =
        assertThrows(AiException.class, () -> AiContext.of(AiAction.PROPOSE_CHANGE, source, 10));
    assertEquals(FailureReason.OVERSIZED, ex.reason());
    assertEquals(AiException.Kind.REFUSED, ex.kind());
  }

  @Test
  void aResultThatCouldNotBeUsedCarriesTheReasonAndOneThatWorkedCarriesNone() throws Exception {
    AiConfig config =
        new AiConfig("ollama", "http://127.0.0.1:1", "m", 12_000, Duration.ofSeconds(5), 8, "none");
    AiSource source = new AiSource("A.java", "A.java", "java", "int x;\n", 0, 7);
    AiService silent = new AiService(fake("Sorry, no."), config);
    AiPlan plan = silent.prepare(AiAction.PROPOSE_CHANGE, source, "rename x");
    AiResult none = silent.run(plan, s -> {}, new AiCancel());
    assertNull(none.proposal());
    assertEquals(FailureReason.NO_EDIT_BLOCK, none.failure());

    AiService good = new AiService(fake("```java\nint y;\n```"), config);
    AiResult ok =
        good.run(
            good.prepare(AiAction.PROPOSE_CHANGE, source, "rename x"), s -> {}, new AiCancel());
    assertNull(ok.failure());
    assertEquals(
        FailureReason.TRUNCATED,
        new AiResult("t", null, "n", List.of(), FailureReason.TRUNCATED).failure());
    assertNull(new AiResult("t", null, "n").failure());
  }

  @Test
  void aReplyTheModelCutOffIsATruncatedResult() throws Exception {
    AiConfig config =
        new AiConfig("ollama", "http://127.0.0.1:1", "m", 12_000, Duration.ofSeconds(5), 8, "none");
    AiSource source = new AiSource("A.java", "A.java", "java", "int x;\n", 0, 7);
    FakeAiProvider cut = fake("```java\nint y;\n```");
    cut.truncated = true;
    AiService service = new AiService(cut, config);
    AiResult result =
        service.run(
            service.prepare(AiAction.PROPOSE_CHANGE, source, "rename x"), s -> {}, new AiCancel());
    assertEquals(FailureReason.TRUNCATED, result.failure());
  }

  @Test
  void productSamplingIsTheDefaultAndDeterministicSamplingCarriesItsSeed() {
    AiRequest plain = new AiRequest("m", "s", "p");
    assertEquals(Sampling.PRODUCT, plain.sampling());
    assertEquals(0.2, Sampling.PRODUCT.temperature());
    assertNull(Sampling.PRODUCT.seed());
    assertEquals(Sampling.PRODUCT, new AiRequest("m", "s", "p", 1000).sampling());
    assertEquals(Sampling.PRODUCT, new AiRequest("m", "s", "p", 0, null).sampling());
    AiRequest seeded = plain.withSampling(Sampling.deterministic(7));
    assertEquals(0.0, seeded.sampling().temperature());
    assertEquals(7L, seeded.sampling().seed());
    assertEquals("p", seeded.prompt());
  }

  @Test
  void usageIsKnownOnlyWhenBothCountsAre() {
    assertFalse(AiUsage.UNKNOWN.isKnown());
    assertEquals(-1, AiUsage.UNKNOWN.total());
    assertEquals(30, new AiUsage(10, 20).total());
    assertTrue(new AiUsage(0, 0).isKnown());
    assertEquals(AiUsage.UNKNOWN, new AiReply("t", false).usage());
    assertEquals(AiUsage.UNKNOWN, new AiReply("t", false, null).usage());
  }

  @Test
  void ollamaIsAskedForTheSamplingAndReportsTheTokensItUsed() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines =
          new String[] {
            FakeOllama.chunk("ok"),
            "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
                + "\"prompt_eval_count\":321,\"eval_count\":45}"
          };
      OllamaProvider provider = new OllamaProvider(server.endpoint(), Duration.ofSeconds(10));
      AiReply reply =
          provider.complete(
              new AiRequest("m", "s", "p").withSampling(Sampling.deterministic(99)),
              s -> {},
              new AiCancel());
      assertEquals(new AiUsage(321, 45), reply.usage());
      Object options = Json.get(Json.parse(server.requestBodies.get(0)), "options");
      assertEquals(0, Json.integer(options, "temperature", -1));
      assertEquals(99, Json.integer(options, "seed", -1));
    }
  }

  @Test
  void theEditorsOwnRequestsKeepTheirTemperatureAndSendNoSeed() throws Exception {
    try (FakeOllama server = new FakeOllama()) {
      server.lines = FakeOllama.reply("ok");
      OllamaProvider provider = new OllamaProvider(server.endpoint(), Duration.ofSeconds(10));
      AiReply reply = provider.complete(new AiRequest("m", "s", "p"), s -> {}, new AiCancel());
      assertEquals(AiUsage.UNKNOWN, reply.usage());
      Object options = Json.get(Json.parse(server.requestBodies.get(0)), "options");
      assertEquals(0.2, ((Number) Json.get(options, "temperature")).doubleValue(), 1e-9);
      assertNull(Json.get(options, "seed"));
    }
  }

  private static FakeAiProvider fake(String answer) {
    FakeAiProvider provider = new FakeAiProvider();
    provider.answer = answer;
    return provider;
  }

  private interface Parse {
    void run() throws AiException;
  }

  private static FailureReason reason(Parse parse) {
    return assertThrows(AiException.class, parse::run).reason();
  }
}
