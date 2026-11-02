package com.javelin.language;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * JSON-RPC 2.0 over a byte stream with {@code Content-Length} framing, as used by the Language Server Protocol.
 * Incoming notifications and server-to-client requests are delivered on the reader thread.
 */
public final class JsonRpc implements AutoCloseable {
    /** Handles requests the server sends to the client; the return value becomes the response result. */
    public interface RequestHandler { Object handle(String method, Object params) throws Exception; }

    private final InputStream in;
    private final OutputStream out;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final BiConsumer<String, Object> onNotification;
    private final RequestHandler onRequest;
    private final Consumer<Throwable> onClosed;
    private volatile boolean closed;

    public JsonRpc(InputStream in, OutputStream out, BiConsumer<String, Object> onNotification, RequestHandler onRequest, Consumer<Throwable> onClosed) {
        this.in = in; this.out = out; this.onNotification = onNotification; this.onRequest = onRequest; this.onClosed = onClosed;
    }

    public void start() {
        Thread reader = new Thread(this::readLoop, "language-server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends a request. The future fails with {@link RpcException} if the server answers with an error. */
    public CompletableFuture<Object> request(String method, Object params) {
        long id = ids.incrementAndGet();
        CompletableFuture<Object> future = new CompletableFuture<>();
        pending.put(id, future);
        try { send(message(id, method, params)); }
        catch (IOException ex) { pending.remove(id); future.completeExceptionally(ex); }
        return future;
    }

    public void notify(String method, Object params) {
        try { send(message(null, method, params)); }
        catch (IOException ignored) { /* The connection is gone; onClosed has been or will be called. */ }
    }

    private static Map<String, Object> message(Long id, String method, Object params) {
        Map<String, Object> message = Json.obj("jsonrpc", "2.0");
        if (id != null) message.put("id", id);
        message.put("method", method);
        if (params != null) message.put("params", params); // some servers reject an explicit null
        return message;
    }

    private synchronized void send(Map<String, Object> message) throws IOException {
        if (closed) throw new IOException("Connection closed");
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void readLoop() {
        Throwable reason = null;
        try {
            while (!closed) {
                String message = readMessage();
                if (message == null) break;
                try { dispatch(Json.asObject(Json.parse(message))); }
                catch (RuntimeException ex) { /* A malformed message must not kill the connection. */ }
            }
        } catch (IOException ex) { reason = ex; }
        shutdown(reason);
    }

    private String readMessage() throws IOException {
        int length = -1;
        while (true) {
            String header = readHeaderLine();
            if (header == null) return null;
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { length = Integer.parseInt(header.substring(colon + 1).trim()); }
                catch (NumberFormatException ex) { throw new IOException("Bad Content-Length header: " + header); }
            }
        }
        if (length < 0) throw new IOException("Message without Content-Length");
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b < 0 && line.size() == 0) return null;
        return line.toString(StandardCharsets.US_ASCII);
    }

    private void dispatch(Map<String, Object> message) {
        if (message == null) return;
        Object id = message.get("id");
        String method = Json.string(message, "method");
        if (method == null) { // a response to one of our requests
            if (!(id instanceof Number n)) return;
            CompletableFuture<Object> future = pending.remove(n.longValue());
            if (future == null) return;
            Object error = message.get("error");
            if (error != null) future.completeExceptionally(new RpcException(Json.integer(error, "code", 0), String.valueOf(Json.get(error, "message"))));
            else future.complete(message.get("result"));
        } else if (id == null) {
            onNotification.accept(method, message.get("params"));
        } else {
            Object result = null; Map<String, Object> error = null;
            try { result = onRequest.handle(method, message.get("params")); }
            catch (RpcException ex) { error = Json.obj("code", ex.code(), "message", String.valueOf(ex.getMessage())); }
            catch (Exception ex) { error = Json.obj("code", -32603, "message", String.valueOf(ex.getMessage())); }
            try {
                if (error != null) send(Json.obj("jsonrpc", "2.0", "id", id, "error", error));
                else send(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
            } catch (IOException ignored) { /* closed */ }
        }
    }

    private void shutdown(Throwable reason) {
        boolean first;
        synchronized (this) { first = !closed; closed = true; }
        RpcException gone = new RpcException(-32000, "Language server connection closed");
        pending.values().forEach(f -> f.completeExceptionally(gone));
        pending.clear();
        if (first) onClosed.accept(reason);
    }

    @Override public void close() {
        shutdown(null);
        try { out.close(); } catch (IOException ignored) { }
    }

    public static final class RpcException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int code;
        public RpcException(int code, String message) { super(message); this.code = code; }
        public int code() { return code; }
    }
}
