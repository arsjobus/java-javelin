package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalProviderTest {
  @TempDir Path dir;
  private ScriptedTransport transport;
  private ExternalConsent consent;
  private Path project;

  @BeforeEach
  void setUp() throws Exception {
    project = Files.createDirectory(dir.resolve("p"));
    consent = new ExternalConsent(dir.resolve("ai-consent.tsv"));
    consent.grant(project, ExternalProvider.ID, ExternalProvider.HOST);
    transport = new ScriptedTransport();
  }

  private ExternalProvider provider(boolean structured) {
    return new ExternalProvider(
        transport, "sk-test-key", Duration.ofSeconds(5), project, consent, structured);
  }

  @Test
  void aRequestGoesToTheMessagesEndpointOfTheFixedHostWithTheKeyInAHeaderOnly() throws Exception {
    List<String> pieces = new ArrayList<>();
    AiReply reply =
        provider(false)
            .complete(new AiRequest("model-x", "be brief", "fix it"), pieces::add, new AiCancel());
    ScriptedTransport.Call call = transport.calls.get(0);
    assertEquals("POST", call.method());
    assertEquals("https://api.anthropic.com/v1/messages", call.uri().toString());
    assertEquals("sk-test-key", call.headers().get("x-api-key"));
    assertEquals("2023-06-01", call.headers().get("anthropic-version"));
    assertFalse(call.body().contains("sk-test-key"), "the key is never part of the body");
    Object body = Json.parse(call.body());
    assertEquals("model-x", Json.string(body, "model"));
    assertEquals("be brief", Json.string(body, "system"));
    assertEquals(ExternalProvider.MAX_OUTPUT_TOKENS, Json.integer(body, "max_tokens", 0));
    Object message = Json.asArray(Json.get(body, "messages")).get(0);
    assertEquals("user", Json.string(message, "role"));
    assertEquals("fix it", Json.string(message, "content"));
    assertEquals("ok", reply.text());
    assertEquals(List.of("ok"), pieces);
    assertEquals(new AiUsage(10, 3), reply.usage());
    assertFalse(reply.truncated());
  }

  @Test
  void theEditorsOwnSamplingSendsNoTemperatureAndARepeatableRunSendsOne() throws Exception {
    provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, new AiCancel());
    assertNull(Json.get(Json.parse(transport.calls.get(0).body()), "temperature"));
    provider(false)
        .complete(
            new AiRequest("m", "s", "p", 0, Sampling.deterministic(7)), x -> {}, new AiCancel());
    assertEquals(0, Json.integer(Json.parse(transport.calls.get(1).body()), "temperature", -1));
  }

  @Test
  void aSchemaIsSentOnlyWhenTheProviderWasSetUpForIt() throws Exception {
    String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
    AiRequest request = new AiRequest("m", "s", "p").withResponseSchema(schema);
    assertFalse(provider(false).supportsSchemaOutput());
    provider(false).complete(request, x -> {}, new AiCancel());
    assertNull(Json.get(Json.parse(transport.calls.get(0).body()), "output_config"));
    assertTrue(provider(true).supportsSchemaOutput());
    provider(true).complete(request, x -> {}, new AiCancel());
    Object format =
        Json.get(Json.get(Json.parse(transport.calls.get(1).body()), "output_config"), "format");
    assertEquals("json_schema", Json.string(format, "type"));
    assertEquals("object", Json.string(Json.get(format, "schema"), "type"));
  }

  @Test
  void aReplyIsReadFromItsTextBlocksAndATokenLimitMeansTruncated() throws Exception {
    transport.next =
        new ExternalTransport.Response(
            200,
            "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"},{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"text\",\"text\":\"b\"}],\"stop_reason\":\"max_tokens\"}");
    AiReply reply = provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, new AiCancel());
    assertEquals("ab", reply.text());
    assertTrue(reply.truncated());
    assertFalse(reply.usage().isKnown());
  }

  @Test
  void aRefusalByTheModelIsReportedAsRefused() {
    transport.next =
        new ExternalTransport.Response(200, "{\"content\":[],\"stop_reason\":\"refusal\"}");
    AiException ex =
        assertThrows(
            AiException.class,
            () -> provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, new AiCancel()));
    assertEquals(FailureReason.REFUSED, ex.reason());
  }

  @Test
  void failuresAreMappedAndNeverEchoTheKey() {
    record Case(int status, AiException.Kind kind) {}
    for (Case c :
        List.of(
            new Case(401, AiException.Kind.REFUSED),
            new Case(403, AiException.Kind.REFUSED),
            new Case(404, AiException.Kind.MODEL_MISSING),
            new Case(429, AiException.Kind.UNAVAILABLE),
            new Case(529, AiException.Kind.UNAVAILABLE),
            new Case(400, AiException.Kind.BAD_RESPONSE))) {
      transport.next =
          new ExternalTransport.Response(
              c.status(), "{\"error\":{\"type\":\"x\",\"message\":\"nope\\nreally\"}}");
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, new AiCancel()),
              "status " + c.status());
      assertEquals(c.kind(), ex.kind(), "status " + c.status());
      assertFalse(ex.getMessage().contains("sk-test-key"));
      assertTrue(ex.getMessage().contains("nope really"), ex.getMessage());
    }
  }

  @Test
  void anUnreadableReplyIsABadResponse() {
    for (String body : List.of("", "not json", "[1]", "{\"content\":5}")) {
      transport.next = new ExternalTransport.Response(200, body);
      AiException ex =
          assertThrows(
              AiException.class,
              () -> provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, new AiCancel()),
              body);
      assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind(), body);
    }
  }

  @Test
  void aCancelledRequestSendsNothing() {
    AiCancel cancel = new AiCancel();
    cancel.cancel();
    AiException ex =
        assertThrows(
            AiException.class,
            () -> provider(false).complete(new AiRequest("m", "s", "p"), x -> {}, cancel));
    assertEquals(AiException.Kind.CANCELLED, ex.kind());
    assertEquals(0, transport.calls.size());
  }

  @Test
  void theModelListIsSortedNeedsNoConsentAndSendsNoCode() throws Exception {
    consent.revokeAll();
    transport.next =
        new ExternalTransport.Response(
            200, "{\"data\":[{\"id\":\"b-model\"},{\"id\":\"a-model\"},{\"id\":\"\"}]}");
    assertEquals(List.of("a-model", "b-model"), provider(false).models(new AiCancel()));
    ScriptedTransport.Call call = transport.calls.get(0);
    assertEquals("GET", call.method());
    assertEquals(ExternalProvider.HOST, call.uri().getHost());
    assertNull(call.body());
  }

  @Test
  void theProviderDeclaresItsCapabilitiesInsteadOfBeingRecognisedByName() throws Exception {
    ExternalProvider p = provider(false);
    assertFalse(p.isLocal());
    assertTrue(p.reportsTokenCounts());
    assertEquals(ExternalProvider.HOST, p.location());
    assertEquals(
        ExternalProvider.DECLARED_CONTEXT_TOKENS,
        p.contextLimit("any", new AiCancel()).orElseThrow());
  }
}
