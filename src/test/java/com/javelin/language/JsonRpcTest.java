package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.JsonRpc.RpcException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JsonRpcTest {
  /** A client wired to in-memory pipes so the test plays the server. */
  private final PipedOutputStream serverToClient = new PipedOutputStream();

  private final PipedInputStream clientIn;
  private final ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
  private final List<String> notifications = new CopyOnWriteArrayList<>();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final AtomicReference<Throwable> closeReason = new AtomicReference<>();
  private JsonRpc rpc;

  JsonRpcTest() throws IOException {
    clientIn = new PipedInputStream(serverToClient, 1 << 20);
  }

  private JsonRpc client(JsonRpc.RequestHandler handler) {
    rpc =
        new JsonRpc(
            clientIn,
            clientOut,
            (m, p) -> notifications.add(m + " " + Json.write(p)),
            handler,
            reason -> {
              closeReason.set(reason);
              closed.countDown();
            });
    rpc.start();
    return rpc;
  }

  private void serverSends(String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    serverToClient.write(
        ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    serverToClient.write(body);
    serverToClient.flush();
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "timed out");
  }

  private String sent() {
    return clientOut.toString(StandardCharsets.UTF_8);
  }

  @AfterEach
  void tearDown() {
    if (rpc != null) rpc.close();
  }

  @Test
  void requestsAreFramedWithContentLengthInBytes() throws Exception {
    client((m, p) -> null).request("x/é", Json.obj("s", "é"));
    String out = sent();
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x/é\",\"params\":{\"s\":\"é\"}}";
    assertEquals(
        "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body, out);
  }

  @Test
  void nullParamsAreOmittedAndNotificationsHaveNoId() throws Exception {
    client((m, p) -> null).notify("exit", null);
    assertTrue(sent().endsWith("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}"));
  }

  @Test
  void responsesCompleteTheMatchingFuture() throws Exception {
    CompletableFuture<Object> a = client((m, p) -> null).request("a", null),
        b = rpc.request("b", null);
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"second\"}");
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
    assertEquals("second", b.get(10, TimeUnit.SECONDS));
    assertEquals(Json.obj("ok", true), a.get(10, TimeUnit.SECONDS));
  }

  @Test
  void errorResponsesFailTheFutureWithTheCode() throws Exception {
    CompletableFuture<Object> f = client((m, p) -> null).request("a", null);
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\"}}");
    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
    RpcException cause = (RpcException) ex.getCause();
    assertEquals(-32601, cause.code());
    assertEquals("nope", cause.getMessage());
  }

  @Test
  void strayAndMistypedResponsesAreIgnored() throws Exception {
    client((m, p) -> null);
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":1}");
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":\"str\",\"result\":1}");
    serverSends("{\"jsonrpc\":\"2.0\",\"result\":1}");
    serverSends("[1,2]");
    serverSends("null");
    serverSends("{not json");
    serverSends("{\"method\":\"ping\"}");
    await(() -> notifications.contains("ping null"));
    assertEquals(1, closed.getCount(), "garbage must not take the connection down");
  }

  @Test
  void notificationsAreDelivered() throws Exception {
    client((m, p) -> null);
    serverSends(
        "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"message\":\"hi\"}}");
    await(() -> notifications.size() == 1);
    assertEquals("window/logMessage {\"message\":\"hi\"}", notifications.get(0));
  }

  @Test
  void aThrowingNotificationHandlerDoesNotKillTheReader() throws Exception {
    rpc =
        new JsonRpc(
            clientIn,
            clientOut,
            (m, p) -> {
              if (m.equals("bad")) throw new IllegalStateException();
              notifications.add(m);
            },
            (m, p) -> null,
            r -> closed.countDown());
    rpc.start();
    serverSends("{\"method\":\"bad\"}");
    serverSends("{\"method\":\"good\"}");
    await(() -> notifications.contains("good"));
  }

  @Test
  void serverRequestsGetResultsOrErrors() throws Exception {
    client(
        (method, params) ->
            switch (method) {
              case "ok" -> Json.obj("echo", params);
              case "rpc" -> throw new RpcException(-32601, "unsupported");
              default -> throw new IllegalStateException("kaput");
            });
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ok\",\"params\":[1]}");
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"rpc\"}");
    serverSends("{\"jsonrpc\":\"2.0\",\"id\":\"s9\",\"method\":\"other\"}");
    await(() -> sent().contains("kaput"));
    String out = sent();
    assertTrue(out.contains("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"echo\":[1]}}"), out);
    assertTrue(
        out.contains("\"id\":8,\"error\":{\"code\":-32601,\"message\":\"unsupported\"}"), out);
    assertTrue(
        out.contains("\"id\":\"s9\",\"error\":{\"code\":-32603,\"message\":\"kaput\"}"), out);
  }

  @Test
  void headersAreCaseInsensitiveAndExtraHeadersIgnored() throws Exception {
    client((m, p) -> null);
    byte[] body = "{\"method\":\"hdr\"}".getBytes(StandardCharsets.UTF_8);
    serverToClient.write(
        ("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\ncontent-length : "
                + body.length
                + "\n\n")
            .getBytes(StandardCharsets.US_ASCII));
    serverToClient.write(body);
    serverToClient.flush();
    await(() -> notifications.contains("hdr null"));
  }

  @Test
  void closingTheStreamFailsPendingRequestsAndReportsOnce() throws Exception {
    CompletableFuture<Object> f = client((m, p) -> null).request("a", null);
    serverToClient.close();
    assertTrue(closed.await(10, TimeUnit.SECONDS));
    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
    assertEquals(-32000, ((RpcException) ex.getCause()).code());
    ExecutionException late =
        assertThrows(
            ExecutionException.class, () -> rpc.request("b", null).get(10, TimeUnit.SECONDS));
    assertTrue(late.getCause() instanceof IOException);
    rpc.notify("after-close", null);
    rpc.close();
  }

  @Test
  void badFramingClosesTheConnectionWithAReason() throws Exception {
    client((m, p) -> null);
    serverToClient.write("Content-Length: abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    serverToClient.flush();
    assertTrue(closed.await(10, TimeUnit.SECONDS));
    assertTrue(closeReason.get().getMessage().contains("Bad Content-Length"));
  }

  @Test
  void aMessageWithoutContentLengthClosesTheConnection() throws Exception {
    client((m, p) -> null);
    serverToClient.write("X-Other: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    serverToClient.flush();
    assertTrue(closed.await(10, TimeUnit.SECONDS));
    assertTrue(closeReason.get().getMessage().contains("without Content-Length"));
  }

  @Test
  void aTruncatedBodyEndsTheConnectionCleanly() throws Exception {
    client((m, p) -> null);
    serverToClient.write(
        "Content-Length: 100\r\n\r\n{\"method\"".getBytes(StandardCharsets.US_ASCII));
    serverToClient.close();
    assertTrue(closed.await(10, TimeUnit.SECONDS));
    assertNull(closeReason.get());
  }

  @Test
  void endOfStreamInTheMiddleOfAHeaderEndsTheConnection() throws Exception {
    client((m, p) -> null);
    serverToClient.write("Content-Len".getBytes(StandardCharsets.US_ASCII));
    serverToClient.close();
    assertTrue(closed.await(10, TimeUnit.SECONDS));
  }

  @Test
  void deeplyNestedMessagesDoNotKillTheReader() throws Exception {
    client((m, p) -> null);
    serverSends("{\"method\":\"deep\",\"params\":" + "[".repeat(50_000) + "]".repeat(50_000) + "}");
    serverSends("{\"method\":\"after\"}");
    await(() -> notifications.contains("after null"));
  }

  @Test
  void largeMessagesSurviveFraming() throws Exception {
    client((m, p) -> null);
    String big = "x".repeat(900_000);
    serverSends("{\"method\":\"big\",\"params\":{\"s\":\"" + big + "\"}}");
    await(() -> notifications.size() == 1);
    assertTrue(notifications.get(0).length() > 900_000);
  }

  @Test
  void writeFailuresFailTheRequestInsteadOfThrowing() throws Exception {
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("pipe broken");
          }
        };
    InputStream empty = new ByteArrayInputStream(new byte[0]);
    JsonRpc dead = new JsonRpc(empty, broken, (m, p) -> {}, (m, p) -> null, r -> {});
    ExecutionException ex =
        assertThrows(
            ExecutionException.class, () -> dead.request("a", null).get(5, TimeUnit.SECONDS));
    assertTrue(ex.getCause() instanceof IOException);
    dead.notify("quiet", null);
    dead.close();
  }

  @Test
  void rpcExceptionCarriesItsCode() {
    assertEquals(5, new RpcException(5, "x").code());
  }

  @Test
  void objectsAreAcceptedAsParams() throws Exception {
    client((m, p) -> null).request("m", Map.of("k", List.of(1, 2)));
    assertTrue(sent().contains("\"params\":{\"k\":[1,2]}"));
  }
}
