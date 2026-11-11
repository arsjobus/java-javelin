package com.javelin.ai;

import com.javelin.language.Json;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The external provider: one frontier API (the Anthropic Messages API), reached only after the
 * person agreed, for the open project, to send code to this host. Without that agreement {@link
 * #complete} refuses before anything is sent, whatever the window did. The destination is a
 * constant, not a setting; the key comes from {@link ExternalKey}; the HTTP is in {@link
 * JdkExternalTransport}. A different vendor would be another class like this one, chosen in {@link
 * AiProviders}.
 *
 * <p>Replies are read whole (no streaming), so the answer arrives as one piece. The wire format was
 * checked against the vendor's documentation and tested with recorded-shape fakes; it has not been
 * run against the live service from this repository's test suite, and nothing here claims the model
 * writes better edits (ROADMAP-AI-RELIABILITY.md, Phase 5 exit).
 */
public final class ExternalProvider implements AiProvider {
  public static final String ID = "external";
  public static final String HOST = "api.anthropic.com";
  public static final String DISPLAY_NAME = "Anthropic API";

  static final String API_VERSION = "2023-06-01";
  static final int MAX_OUTPUT_TOKENS = 8_192;

  /** What the provider declares as its window; the model in use may allow more. */
  static final int DECLARED_CONTEXT_TOKENS = 200_000;

  static final int MAX_REPLY_CHARS = 1_000_000;

  private final ExternalTransport transport;
  private final String apiKey;
  private final Duration timeout;
  private final Path projectRoot;
  private final ExternalConsent consent;
  private final boolean structured;

  ExternalProvider(
      ExternalTransport transport,
      String apiKey,
      Duration timeout,
      Path projectRoot,
      ExternalConsent consent,
      boolean structured) {
    this.transport = transport;
    this.apiKey = apiKey;
    this.timeout = timeout;
    this.projectRoot = projectRoot;
    this.consent = consent;
    this.structured = structured;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return DISPLAY_NAME;
  }

  @Override
  public String location() {
    return HOST;
  }

  @Override
  public boolean isLocal() {
    return false;
  }

  @Override
  public boolean supportsSchemaOutput() {
    return structured;
  }

  @Override
  public boolean reportsTokenCounts() {
    return true;
  }

  @Override
  public OptionalInt contextLimit(String model, AiCancel cancel) {
    return OptionalInt.of(DECLARED_CONTEXT_TOKENS);
  }

  @Override
  public Optional<String> consentNotice() {
    if (consent.granted(projectRoot, ID, HOST)) return Optional.empty();
    return Optional.of(
        "FIRST USE OF AN EXTERNAL PROVIDER IN THIS PROJECT\n"
            + "Provider:    "
            + DISPLAY_NAME
            + " (an outside service, not this computer)\n"
            + "Destination: https://"
            + HOST
            + "\n"
            + "Project:     "
            + projectRoot
            + "\n\n"
            + "What is sent: exactly the message shown below, and nothing else. When the editor"
            + " checks a proposed change, a repeat request carries the compiler's first messages"
            + " and the part of the change they point at; each request is shown before it is"
            + " sent. The provider's own terms and logs apply to what it receives.\n\n"
            + "Choosing \"Allow for this project and Send\" lets requests from this project go to"
            + " this destination. Each request is still shown first, and nothing is applied"
            + " without your review. To take the consent back, delete this project's line in "
            + consent.file()
            + ".");
  }

  @Override
  public void recordConsent() {
    consent.grant(projectRoot, ID, HOST);
  }

  /**
   * Lists the models the account can use. Sends the key and nothing from the project, so it needs
   * no consent; it is made only when the person asks for the list.
   */
  @Override
  public List<String> models(AiCancel cancel) throws AiException {
    ExternalTransport.Response response =
        transport.get(uri("/v1/models?limit=1000"), headers(), timeout, cancel);
    check(response);
    List<String> ids = new ArrayList<>();
    try {
      for (Object entry : Json.asArray(Json.get(parse(response.body()), "data"))) {
        String id = Json.string(entry, "id");
        if (id != null && !id.isBlank()) ids.add(id);
      }
    } catch (RuntimeException ex) {
      throw badResponse("The list of models could not be read.");
    }
    Collections.sort(ids);
    return ids;
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    cancel.throwIfCancelled();
    if (!consent.granted(projectRoot, ID, HOST))
      throw new AiException(
          AiException.Kind.REFUSED,
          "Nothing was sent: this project has not agreed to send code to "
              + DISPLAY_NAME
              + " ("
              + HOST
              + ").");
    ExternalTransport.Response response =
        transport.post(uri("/v1/messages"), headers(), body(request), timeout, cancel);
    check(response);
    AiReply reply = read(response.body());
    if (!reply.text().isEmpty()) onText.accept(reply.text());
    return reply;
  }

  String body(AiRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", request.model());
    payload.put("max_tokens", MAX_OUTPUT_TOKENS);
    payload.put("system", request.system());
    payload.put("messages", List.of(Json.obj("role", "user", "content", request.prompt())));
    // The editor's own sampling is the provider's default; only a run that asked for something
    // else (the eval's temperature 0) sends one. A seed has no equivalent here and is not sent.
    if (!Sampling.PRODUCT.equals(request.sampling()))
      payload.put("temperature", request.sampling().temperature());
    if (structured && request.responseSchema() != null)
      payload.put(
          "output_config",
          Json.obj(
              "format",
              Json.obj("type", "json_schema", "schema", Json.parse(request.responseSchema()))));
    return Json.write(payload);
  }

  private Map<String, String> headers() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("content-type", "application/json");
    headers.put("x-api-key", apiKey);
    headers.put("anthropic-version", API_VERSION);
    return headers;
  }

  private static URI uri(String pathAndQuery) {
    return URI.create("https://" + HOST + pathAndQuery);
  }

  private static void check(ExternalTransport.Response response) throws AiException {
    int status = response.status();
    if (status >= 200 && status < 300) return;
    String detail = errorMessage(response.body());
    switch (status) {
      case 401, 403 ->
          throw new AiException(
              AiException.Kind.REFUSED,
              DISPLAY_NAME + " did not accept the API key (HTTP " + status + ")." + detail);
      case 404 ->
          throw new AiException(
              AiException.Kind.MODEL_MISSING,
              "The model is not available to this account (HTTP 404)." + detail);
      case 429 ->
          throw new AiException(
              AiException.Kind.UNAVAILABLE,
              DISPLAY_NAME + " is rate limiting this account (HTTP 429)." + detail);
      default -> {
        if (status >= 500)
          throw new AiException(
              AiException.Kind.UNAVAILABLE,
              DISPLAY_NAME + " had a problem (HTTP " + status + ")." + detail);
        throw badResponse("The request was not accepted (HTTP " + status + ")." + detail);
      }
    }
  }

  private static String errorMessage(String body) {
    try {
      String message = Json.string(Json.get(parse(body), "error"), "message");
      if (message == null || message.isBlank()) return "";
      String one = message.strip().replaceAll("\\s+", " ");
      return " " + (one.length() > 300 ? one.substring(0, 300) + "..." : one);
    } catch (RuntimeException | AiException ex) {
      return "";
    }
  }

  private static AiReply read(String body) throws AiException {
    if (body.length() > MAX_REPLY_CHARS)
      throw badResponse("The reply is longer than " + MAX_REPLY_CHARS + " characters.");
    Object root = parse(body);
    StringBuilder text = new StringBuilder();
    try {
      for (Object block : Json.asArray(Json.get(root, "content"))) {
        if ("text".equals(Json.string(block, "type"))) {
          String piece = Json.string(block, "text");
          if (piece != null) text.append(piece);
        }
      }
    } catch (RuntimeException ex) {
      throw badResponse("The reply has no readable content.");
    }
    String stop = Json.string(root, "stop_reason");
    if ("refusal".equals(stop))
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.REFUSED,
          "The model declined to answer this request.");
    AiUsage usage = AiUsage.UNKNOWN;
    Object u = Json.get(root, "usage");
    if (u != null) {
      int in = Json.integer(u, "input_tokens", -1);
      int out = Json.integer(u, "output_tokens", -1);
      if (in >= 0 && out >= 0) usage = new AiUsage(in, out);
    }
    return new AiReply(text.toString(), "max_tokens".equals(stop), usage);
  }

  private static Object parse(String body) throws AiException {
    try {
      Object value = Json.parse(body);
      if (value == null) throw badResponse("The reply is empty.");
      return value;
    } catch (RuntimeException ex) {
      throw badResponse("The reply is not valid JSON.");
    }
  }

  private static AiException badResponse(String message) {
    return new AiException(AiException.Kind.BAD_RESPONSE, message);
  }
}
