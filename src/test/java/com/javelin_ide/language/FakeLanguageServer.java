package com.javelin_ide.language;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A scriptable language server used by the end-to-end tests: {@code FakeLanguageServer <log file> <mode>}.
 * Every message it receives is appended to the log as {@code N <method> <params>} (notification),
 * {@code R <method>} (request) or {@code S <method> <result>} (the client's answer to a request it sent).
 * Modes: full, fullsync, syncobj, nosync, none, probe (full + server-initiated traffic), crash, die-on-init,
 * stubborn (initializes normally, then never answers shutdown and never exits), workspace (full; a rename answers with
 * edits for the document itself and for a sibling B.java in the same folder, to be applied to real files).
 */
public final class FakeLanguageServer {
    private static final String RANGE = "{\"start\":{\"line\":1,\"character\":2},\"end\":{\"line\":1,\"character\":5}}";
    private static Path log;
    private static JsonRpc rpc;

    private FakeLanguageServer() {}

    private static synchronized void log(String line) {
        try { Files.writeString(log, line.replace('\n', ' ') + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static void main(String[] args) throws Exception {
        log = Path.of(args[0]);
        String mode = args[1];
        CountDownLatch done = new CountDownLatch(1);
        rpc = new JsonRpc(System.in, System.out, (method, params) -> {
            log("N " + method + " " + Json.write(params));
            if (method.equals("initialized") && mode.equals("probe")) probe();
            if (method.equals("textDocument/didOpen")) {
                String uri = Json.string(Json.get(params, "textDocument"), "uri");
                rpc.notify("textDocument/publishDiagnostics", Json.obj("uri", uri, "diagnostics", List.of(Json.obj("range", Json.parse(RANGE), "message", "fake problem", "severity", 2, "source", "fake"))));
            }
            if (method.equals("exit")) done.countDown();
        }, (method, params) -> handle(mode, method, params), reason -> done.countDown());
        rpc.start();
        if (mode.equals("stubborn")) Thread.sleep(Long.MAX_VALUE);
        done.await();
        System.exit(0);
    }

    private static Object handle(String mode, String method, Object params) throws Exception {
        log("R " + method);
        switch (method) {
            case "initialize" -> {
                if (mode.equals("die-on-init")) System.exit(3);
                if (mode.equals("crash")) new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } System.exit(9); }).start();
                System.err.println("fake server starting");
                return Json.obj("capabilities", capabilities(mode));
            }
            case "shutdown" -> {
                if (mode.equals("stubborn")) Thread.sleep(Long.MAX_VALUE);
                return null;
            }
            case "textDocument/completion" -> { return Json.parse("{\"items\":[{\"label\":\"foo\",\"insertText\":\"foo($1)\",\"insertTextFormat\":2}]}"); }
            case "textDocument/hover" -> { return Json.parse("{\"contents\":{\"kind\":\"markdown\",\"value\":\"**hover** text\"}}"); }
            case "textDocument/definition", "textDocument/references" -> { return Json.parse("[{\"uri\":\"file:///def.java\",\"range\":" + RANGE + "}]"); }
            case "textDocument/rename" -> {
                if (mode.equals("workspace")) return workspaceRename(params);
                return Json.parse("{\"changes\":{\"file:///a.java\":[{\"range\":" + RANGE + ",\"newText\":\"" + Json.string(params, "newName") + "\"}]}}");
            }
            case "textDocument/signatureHelp" -> { return Json.parse("{\"signatures\":[{\"label\":\"f(a)\",\"parameters\":[{\"label\":\"a\"}]}]}"); }
            case "textDocument/documentSymbol" -> { return Json.parse("[{\"name\":\"Main\",\"kind\":5,\"range\":" + RANGE + ",\"selectionRange\":" + RANGE + "}]"); }
            case "textDocument/formatting" -> { return Json.parse("[{\"range\":" + RANGE + ",\"newText\":\"formatted\"}]"); }
            default -> throw new JsonRpc.RpcException(-32601, "unknown " + method);
        }
    }

    private static Map<String, Object> capabilities(String mode) {
        Object sync = switch (mode) {
            case "fullsync" -> 1;
            case "syncobj" -> Json.obj("change", 1, "save", true);
            case "nosync" -> 0;
            case "none" -> null;
            default -> Json.obj("openClose", true, "change", 2, "save", Json.obj("includeText", true));
        };
        Map<String, Object> caps = Json.obj();
        if (mode.equals("none")) return caps;
        caps.put("textDocumentSync", sync);
        for (String p : new String[] {"completionProvider", "hoverProvider", "definitionProvider", "referencesProvider", "renameProvider", "signatureHelpProvider", "documentSymbolProvider", "documentFormattingProvider"})
            caps.put(p, p.equals("hoverProvider") ? (Object) true : Json.obj());
        return caps;
    }

    /** The document's own occurrences plus a sibling file's, all at {@link #RANGE}, and a header line inserted at the top. */
    private static Object workspaceRename(Object params) {
        String uri = Json.string(Json.get(params, "textDocument"), "uri");
        String sibling = uri.substring(0, uri.lastIndexOf('/') + 1) + "B.java";
        String name = Json.string(params, "newName");
        Object occurrence = Json.obj("range", Json.parse(RANGE), "newText", name);
        Object header = Json.obj("range", Json.parse("{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}}"), "newText", "// renamed to " + name + "\n");
        return Json.obj("changes", Json.obj(uri, List.of(occurrence, header), sibling, List.of(occurrence)));
    }

    /** Server-initiated traffic: requests the client must answer and notifications it must tolerate. */
    private static void probe() {
        System.err.println("probing the client");
        rpc.notify("window/logMessage", Json.obj("message", "log from server"));
        rpc.notify("window/showMessage", Json.obj("message", "show from server"));
        rpc.notify("window/logMessage", Json.obj());
        rpc.notify("$/progress", Json.obj("token", 1));
        rpc.notify("textDocument/publishDiagnostics", Json.obj("diagnostics", List.of()));
        rpc.notify("textDocument/publishDiagnostics", Json.obj("uri", "not a uri", "diagnostics", List.of()));
        rpc.notify("textDocument/publishDiagnostics", Json.obj("uri", "file:///other.java", "diagnostics", List.of(Json.obj("range", Json.parse(RANGE), "message", "from probe"))));
        ask("workspace/configuration", Json.obj("items", List.of(Json.obj(), Json.obj())));
        ask("workspace/configuration", Json.obj());
        ask("workspace/workspaceFolders", null);
        ask("workspace/applyEdit", Json.obj("edit", Json.obj()));
        ask("client/registerCapability", Json.obj("registrations", List.of()));
        ask("client/unregisterCapability", Json.obj("unregisterations", List.of()));
        ask("window/workDoneProgress/create", Json.obj("token", 1));
        ask("window/showMessageRequest", Json.obj("message", "?"));
        ask("made/up", null);
    }

    private static void ask(String method, Object params) {
        rpc.request(method, params).whenComplete((result, error) -> log("S " + method + " " + (error == null ? Json.write(result) : "error " + error.getMessage())));
    }
}
