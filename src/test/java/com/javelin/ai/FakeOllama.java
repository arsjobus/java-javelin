package com.javelin.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** A stand-in for Ollama on a loopback port, so the real HTTP client is tested without Ollama installed. */
final class FakeOllama implements AutoCloseable {
    final HttpServer server;
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String[] lines = {};
    volatile String rawBody;
    volatile boolean stall;
    final CountDownLatch requestArrived = new CountDownLatch(1);

    FakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static String chunk(String text) { return "{\"message\":{\"role\":\"assistant\",\"content\":" + com.javelin.language.Json.write(text) + "},\"done\":false}"; }

    static String done(String reason) { return "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"" + reason + "\"}"; }

    static String[] reply(String... pieces) {
        List<String> out = new ArrayList<>();
        for (String piece : pieces) out.add(chunk(piece));
        out.add(done("stop"));
        return out.toArray(new String[0]);
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestArrived.countDown();
        if (stall) { try { Thread.sleep(30_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } exchange.close(); return; }
        byte[] body = rawBody != null ? rawBody.getBytes(StandardCharsets.UTF_8) : (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            if (rawBody != null || status != 200) { out.write(body); return; }
            for (String line : lines) { out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
        } catch (IOException clientWentAway) { /* a cancelled request closes the connection */ }
    }

    @Override public void close() { server.stop(0); }
}
