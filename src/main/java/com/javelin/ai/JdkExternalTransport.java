package com.javelin.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The HTTP client of the external provider, and the only code besides {@link OllamaProvider} that
 * may use the network ({@code OfflineGuardTest}). It sends to exactly one host over HTTPS: any
 * other address is refused before a connection exists, there is no proxy and no redirect is
 * followed, so a settings line or a server's answer cannot send code anywhere else.
 */
final class JdkExternalTransport implements ExternalTransport {
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .proxy(HttpClient.Builder.NO_PROXY)
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  /** Refuses anything but https to the provider's own host, on its default port. */
  static void requireDestination(URI uri) throws AiException {
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || !ExternalProvider.HOST.equalsIgnoreCase(uri.getHost())
        || uri.getPort() != -1
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null)
      throw new AiException(
          AiException.Kind.REFUSED,
          "Requests of this provider go to https://"
              + ExternalProvider.HOST
              + " and nowhere else.");
  }

  @Override
  public Response get(URI uri, Map<String, String> headers, Duration timeout, AiCancel cancel)
      throws AiException {
    requireDestination(uri);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
    headers.forEach(request::header);
    return send(request, timeout, cancel);
  }

  @Override
  public Response post(
      URI uri, Map<String, String> headers, String body, Duration timeout, AiCancel cancel)
      throws AiException {
    requireDestination(uri);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    headers.forEach(request::header);
    return send(request, timeout, cancel);
  }

  private Response send(HttpRequest.Builder request, Duration timeout, AiCancel cancel)
      throws AiException {
    cancel.throwIfCancelled();
    CompletableFuture<HttpResponse<String>> future =
        http.sendAsync(
            request.timeout(timeout).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Runnable withdraw = cancel.onCancel(() -> future.cancel(true));
    try {
      HttpResponse<String> response = future.get();
      return new Response(response.statusCode(), response.body());
    } catch (CancellationException ex) {
      throw cancelled();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw cancelled();
    } catch (ExecutionException ex) {
      if (cancel.isCancelled()) throw cancelled();
      Throwable cause = ex.getCause();
      if (cause instanceof HttpTimeoutException)
        throw new AiException(
            AiException.Kind.TIMEOUT, ExternalProvider.HOST + " did not answer in time.");
      String what = cause instanceof IOException ? "the connection failed" : "the request failed";
      throw new AiException(
          AiException.Kind.UNAVAILABLE,
          "Could not reach " + ExternalProvider.HOST + ": " + what + ".");
    } finally {
      withdraw.run();
    }
  }

  private static AiException cancelled() {
    return new AiException(AiException.Kind.CANCELLED, "The AI request was cancelled.");
  }
}
