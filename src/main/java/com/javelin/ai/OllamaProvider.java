package com.javelin.ai;

import com.javelin.language.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Talks to an <a href="https://ollama.com">Ollama</a> server on this computer. This is the only
 * class in the editor that uses the network, and the only address it will use is the loopback one:
 * {@link LoopbackEndpoint} refuses anything else when the provider is created. {@code
 * OfflineGuardTest} allows the HTTP client in this file and nowhere else, and checks that the
 * client has no proxy and follows no redirect, so a request cannot be sent anywhere but where it
 * was aimed.
 *
 * <p>Requests are only made when the user invokes an AI action (or asks for the list of models);
 * the answer is read as it streams in, and a request that goes quiet for the configured time is
 * given up.
 */
public final class OllamaProvider implements AiProvider {
  public static final String ID = "ollama";

  /** A reply longer than this many characters is not a reply a person asked for; reading stops. */
  static final int MAX_REPLY_CHARS = 1_000_000;

  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "ai-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final URI base;
  private final Duration timeout;
  private final String thinkLevel;
  private final HttpClient http;

  /**
   * @throws AiException REFUSED when {@code endpoint} is not an address on this computer
   */
  public OllamaProvider(String endpoint, Duration timeout) throws AiException {
    this(endpoint, timeout, AiConfig.DEFAULT_THINK_LEVEL);
  }

  /**
   * @throws AiException REFUSED when {@code endpoint} is not an address on this computer
   */
  public OllamaProvider(String endpoint, Duration timeout, String thinkLevel) throws AiException {
    this.base = LoopbackEndpoint.require(endpoint);
    this.timeout = timeout;
    this.thinkLevel = thinkLevel;
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String displayName() {
    return "Ollama";
  }

  @Override
  public String location() {
    return base.getHost() + (base.getPort() < 0 ? "" : ":" + base.getPort());
  }

  @Override
  public boolean isLocal() {
    return true;
  }

  @Override
  public List<String> models(AiCancel cancel) throws AiException {
    cancel.throwIfCancelled();
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/api/tags"))
            .header("Accept", "application/json")
            .GET()
            .build();
    try (Call call = new Call(cancel)) {
      HttpResponse<InputStream> response = call.send(request);
      String body;
      try {
        body = new String(response.body().readNBytes(1 << 20), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw call.failure(ex);
      }
      if (response.statusCode() != 200) throw httpError(response.statusCode(), body, "");
      Object json;
      try {
        json = Json.parse(body);
      } catch (IllegalArgumentException ex) {
        throw new AiException(
            AiException.Kind.BAD_RESPONSE,
            "The server at "
                + location()
                + " did not answer like Ollama (is something else using that port?).");
      }
      List<String> names = new ArrayList<>();
      List<Object> models = Json.asArray(Json.get(json, "models"));
      if (models != null)
        for (Object model : models) {
          String name = Json.string(model, "name");
          if (name == null) name = Json.string(model, "model");
          if (name != null && !name.isBlank()) names.add(name);
        }
      Collections.sort(names);
      return names;
    }
  }

  /**
   * Asks Ollama what the model's largest context is ({@code /api/show}); only the model's name is
   * sent. Empty when Ollama does not say.
   */
  @Override
  public OptionalInt contextLimit(String model, AiCancel cancel) throws AiException {
    cancel.throwIfCancelled();
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/api/show"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    Json.write(Json.obj("model", model)), StandardCharsets.UTF_8))
            .build();
    try (Call call = new Call(cancel)) {
      HttpResponse<InputStream> response = call.send(request);
      String body;
      try {
        body = new String(response.body().readNBytes(4 << 20), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw call.failure(ex);
      }
      if (response.statusCode() == 404) throw httpError(404, body, model);
      if (response.statusCode() != 200) return OptionalInt.empty();
      Object json;
      try {
        json = Json.parse(body);
      } catch (IllegalArgumentException ex) {
        return OptionalInt.empty();
      }
      var info = Json.asObject(Json.get(json, "model_info"));
      if (info == null) return OptionalInt.empty();
      int best = 0;
      for (var entry : info.entrySet())
        if (entry.getKey().endsWith(".context_length") && entry.getValue() instanceof Number n)
          best = Math.max(best, n.intValue());
      return best > 0 ? OptionalInt.of(best) : OptionalInt.empty();
    }
  }

  @Override
  public AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    cancel.throwIfCancelled();
    String body =
        Json.write(
            Json.obj(
                "model", request.model(),
                "stream", true,
                "messages",
                    List.of(
                        Json.obj("role", "system", "content", request.system()),
                        Json.obj("role", "user", "content", request.prompt())),
                "think",
                    thinkLevel == null || thinkLevel.equalsIgnoreCase("none") ? false : thinkLevel,
                "options", Json.obj("temperature", 0.2, "num_ctx", contextTokens(request))));
    HttpRequest http =
        HttpRequest.newBuilder(base.resolve("/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try (Call call = new Call(cancel)) {
      HttpResponse<InputStream> response = call.send(http);
      if (response.statusCode() != 200) {
        String error;
        try {
          error = new String(response.body().readNBytes(8192), StandardCharsets.UTF_8);
        } catch (IOException ex) {
          throw call.failure(ex);
        }
        throw httpError(response.statusCode(), error, request.model());
      }
      StringBuilder text = new StringBuilder();
      boolean done = false;
      boolean truncated = false;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while (!done && (line = reader.readLine()) != null) {
          call.touch();
          cancel.throwIfCancelled();
          if (line.isBlank()) continue;
          Object json;
          try {
            json = Json.parse(line);
          } catch (IllegalArgumentException ex) {
            throw new AiException(
                AiException.Kind.BAD_RESPONSE,
                "The server at "
                    + location()
                    + " did not answer like Ollama (is something else using that port?).");
          }
          String error = Json.string(json, "error");
          if (error != null)
            throw new AiException(
                AiException.Kind.BAD_RESPONSE, "Ollama reported an error: " + error);
          String piece = Json.string(Json.get(json, "message"), "content");
          if (piece != null && !piece.isEmpty()) {
            if (text.length() + piece.length() > MAX_REPLY_CHARS)
              throw new AiException(
                  AiException.Kind.BAD_RESPONSE,
                  "The reply is longer than "
                      + MAX_REPLY_CHARS
                      + " characters; reading was stopped.");
            text.append(piece);
            onText.accept(piece);
            cancel.throwIfCancelled();
          }
          if (Json.bool(json, "done")) {
            done = true;
            truncated = "length".equals(Json.string(json, "done_reason"));
          }
        }
      } catch (IOException ex) {
        throw call.failure(ex);
      }
      if (!done) {
        AiException stopped = call.interruption();
        throw stopped != null
            ? stopped
            : new AiException(
                AiException.Kind.BAD_RESPONSE, "Ollama stopped before the answer was finished.");
      }
      return new AiReply(text.toString(), truncated);
    }
  }

  /**
   * The context window to ask Ollama for. Ollama silently cuts a prompt that does not fit its
   * window, which would make the model answer about code it was only half shown; so the window is
   * sized from the request, with room for the answer. A request that carries its own window (a
   * project request, whose budget was worked out against it) is asked for exactly that.
   */
  static int contextTokens(AiRequest request) {
    if (request.contextTokens() > 0) return Math.max(2048, request.contextTokens());
    int estimate = request.size() / 3 + 2048;
    int rounded = ((estimate + 1023) / 1024) * 1024;
    return Math.max(4096, Math.min(32768, rounded));
  }

  private AiException httpError(int status, String body, String model) {
    String message = errorText(body);
    if (status == 404 && !model.isEmpty())
      return new AiException(
          AiException.Kind.MODEL_MISSING,
          "The model '"
              + model
              + "' is not installed in Ollama. Install it with  ollama pull "
              + model
              + "  or choose another one with AI > Choose Model..."
              + (message.isEmpty() ? "" : " (Ollama said: " + message + ")"));
    return new AiException(
        AiException.Kind.BAD_RESPONSE,
        "Ollama answered with HTTP " + status + (message.isEmpty() ? "" : ": " + message));
  }

  private static String errorText(String body) {
    String text = body == null ? "" : body.strip();
    if (text.isEmpty()) return "";
    try {
      String error = Json.string(Json.parse(text), "error");
      if (error != null) return error.strip();
    } catch (IllegalArgumentException ignored) {
      /* not JSON: show the start of it */
    }
    return text.length() > 200 ? text.substring(0, 200) + "..." : text;
  }

  /** One request in flight: its cancel hook, its inactivity watchdog and the way both end it. */
  private final class Call implements AutoCloseable {
    private final AiCancel cancel;
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final Runnable unregister;
    private final ScheduledFuture<?> watchdog;
    private volatile long lastActivity = System.nanoTime();
    private volatile CompletableFuture<HttpResponse<InputStream>> future;
    private volatile InputStream stream;

    Call(AiCancel cancel) {
      this.cancel = cancel;
      this.unregister = cancel.onCancel(this::abort);
      long period = Math.max(25, Math.min(1000, timeout.toMillis() / 4));
      this.watchdog =
          WATCHDOG.scheduleAtFixedRate(
              () -> {
                if (System.nanoTime() - lastActivity > timeout.toNanos()) {
                  timedOut.set(true);
                  abort();
                }
              },
              period,
              period,
              TimeUnit.MILLISECONDS);
    }

    void touch() {
      lastActivity = System.nanoTime();
    }

    void abort() {
      CompletableFuture<?> pending = future;
      if (pending != null) pending.cancel(true);
      InputStream open = stream;
      if (open != null) {
        try {
          open.close();
        } catch (IOException ignored) {
          /* already gone */
        }
      }
    }

    HttpResponse<InputStream> send(HttpRequest request) throws AiException {
      try {
        future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        if (cancel.isCancelled() || timedOut.get()) abort();
        HttpResponse<InputStream> response = future.get();
        stream = response.body();
        if (cancel.isCancelled() || timedOut.get()) abort();
        touch();
        return response;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        abort();
        throw new AiException(AiException.Kind.CANCELLED, "The AI request was cancelled.");
      } catch (CancellationException ex) {
        AiException stopped = interruption();
        throw stopped != null
            ? stopped
            : new AiException(AiException.Kind.CANCELLED, "The AI request was cancelled.");
      } catch (ExecutionException ex) {
        throw failure(ex.getCause() == null ? ex : ex.getCause());
      }
    }

    /**
     * The exception for a request that was stopped on purpose (cancelled or timed out), or null if
     * it was not.
     */
    AiException interruption() {
      if (cancel.isCancelled())
        return new AiException(AiException.Kind.CANCELLED, "The AI request was cancelled.");
      if (timedOut.get())
        return new AiException(
            AiException.Kind.TIMEOUT,
            "Ollama stopped answering for "
                + timeout.toSeconds()
                + " seconds. The model may still be loading; try again, or raise ai.timeoutSeconds in your user settings.");
      return null;
    }

    /**
     * The exception for an I/O failure: a stop on purpose, a server that is not there, or a
     * connection that broke.
     */
    AiException failure(Throwable error) {
      AiException stopped = interruption();
      if (stopped != null) return stopped;
      if (error instanceof ConnectException || error instanceof HttpConnectTimeoutException)
        return new AiException(
            AiException.Kind.UNAVAILABLE,
            "Cannot reach Ollama at " + location() + ". Is it running? Start it with  ollama serve",
            error);
      return new AiException(
          AiException.Kind.UNAVAILABLE,
          "The connection to Ollama at "
              + location()
              + " failed"
              + (error.getMessage() == null ? "" : ": " + error.getMessage()),
          error);
    }

    @Override
    public void close() {
      watchdog.cancel(false);
      unregister.run();
      abort();
    }
  }
}
