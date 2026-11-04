package com.javelin.ai;

import java.io.IOException;

/**
 * Lets the end-to-end tests (another package) use {@link FakeOllama} without making it public API.
 */
public final class FakeOllamaAccess implements AutoCloseable {
  private final FakeOllama server;

  private FakeOllamaAccess(FakeOllama server) {
    this.server = server;
  }

  public static FakeOllamaAccess start() throws IOException {
    return new FakeOllamaAccess(new FakeOllama());
  }

  public String endpoint() {
    return server.endpoint();
  }

  /** Answer {@code /api/chat} with these pieces of text. */
  public void chat(String... pieces) {
    server.lines = FakeOllama.reply(pieces);
    server.rawBody = null;
  }

  /** Answer every request with this body (used for the model list). */
  public void models(String json) {
    server.rawBody = json;
  }

  public int chatCalls() {
    return (int) server.paths.stream().filter(p -> p.equals("POST /api/chat")).count();
  }

  public String lastChatBody() {
    return server.requestBodies.get(server.requestBodies.size() - 1);
  }

  @Override
  public void close() {
    server.close();
  }
}
