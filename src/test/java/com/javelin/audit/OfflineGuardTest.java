package com.javelin.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Javelin IDE is local-first: it has no telemetry, no update check and no cloud calls. This test makes that a rule the
 * build enforces. If a future feature genuinely needs the network, add it here deliberately, with a comment saying why,
 * in the same change.
 *
 * <p>There is exactly one such feature: the optional local-model integration (v0.7). {@code OllamaProvider} may use the
 * JDK's HTTP client, because Ollama is a server on this computer; it is held to the loopback address by
 * {@code LoopbackEndpoint}, and the tests below check that it keeps no proxy, follows no redirect and that nothing else
 * in the editor may use the network.
 */
class OfflineGuardTest {
    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final List<Pattern> FORBIDDEN = List.of(
        Pattern.compile("java\\.net\\.http"), Pattern.compile("\\bHttpClient\\b"), Pattern.compile("\\bHttpURLConnection\\b"),
        Pattern.compile("\\bURLConnection\\b"), Pattern.compile("\\bjava\\.net\\.(Socket|ServerSocket|DatagramSocket|MulticastSocket)\\b"),
        Pattern.compile("\\bnew\\s+(Socket|ServerSocket|DatagramSocket)\\s*\\("), Pattern.compile("\\bInetAddress\\b"), Pattern.compile("\\bjavax\\.net\\b"),
        Pattern.compile("\\.openConnection\\s*\\("), Pattern.compile("\\.openStream\\s*\\("), Pattern.compile("\\bjava\\.rmi\\b"),
        Pattern.compile("\\bWebEngine\\b"), Pattern.compile("\\bWebView\\b"), // embedded browsers phone home by design
        Pattern.compile("https?://[^\\s\"')]*(analytics|telemetry|sentry|segment|mixpanel|amplitude|google-analytics|crashlytics)", Pattern.CASE_INSENSITIVE));

    /** The one file that may use the HTTP client, and only the HTTP client. Everything else stays forbidden for it too. */
    private static final Path OLLAMA = SOURCES.resolve("com/javelin/ai/OllamaProvider.java");
    private static final List<String> HTTP_CLIENT_PATTERNS = List.of("java\\.net\\.http", "\\bHttpClient\\b");

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) { return files.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    }

    @Test void theSourceTreeIsWhereTheTestExpectsIt() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "run the tests from the project root");
        assertTrue(sourceFiles().size() > 20);
    }

    @Test void noMainSourceUsesTheNetwork() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFiles()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++)
                for (Pattern p : FORBIDDEN) {
                    if (file.equals(OLLAMA) && HTTP_CLIENT_PATTERNS.contains(p.pattern())) continue; // the one allowed use, checked below
                    if (p.matcher(lines.get(i)).find()) violations.add(file + ":" + (i + 1) + " matches " + p.pattern());
                }
        }
        assertEquals(List.of(), violations, "network use must be a deliberate, reviewed decision");
    }

    @Test void theGuardActuallyCatchesViolations() {
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("HttpClient.newHttpClient()").find()));
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("new Socket(\"example.com\", 80)").find()));
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("url = \"https://api.mixpanel.com/track\"").find()));
        assertTrue(FORBIDDEN.stream().noneMatch(p -> p.matcher("// see https://github.com/arsjobus/javelin_ide").find()));
    }

    @Test void onlyTheOllamaProviderUsesTheHttpClient() throws IOException {
        assertTrue(Files.isRegularFile(OLLAMA));
        for (Path file : sourceFiles()) {
            if (file.equals(OLLAMA)) continue;
            String text = Files.readString(file);
            for (String pattern : HTTP_CLIENT_PATTERNS) assertFalse(Pattern.compile(pattern).matcher(text).find(), file + " must not use the HTTP client");
        }
        assertTrue(Pattern.compile("java\\.net\\.http").matcher(Files.readString(OLLAMA)).find(), "the allow-list entry is stale if the provider does not use the client");
    }

    @Test void theOllamaProviderIsHeldToThisComputer() throws IOException {
        String text = Files.readString(OLLAMA);
        assertTrue(text.contains("LoopbackEndpoint.require(endpoint)"), "the address is checked before a client exists");
        assertTrue(text.contains("HttpClient.Builder.NO_PROXY"), "requests must not be routed through a proxy");
        assertTrue(text.contains("HttpClient.Redirect.NEVER"), "a redirect must not send the request somewhere else");
        assertEquals(1, text.split("HttpClient\\.newBuilder\\(\\)", -1).length - 1, "one client, built in one place");
        Pattern uri = Pattern.compile("URI\\.create\\(|new\\s+URI\\(|\\.resolve\\(\\s*\\\"https?:");
        assertFalse(uri.matcher(text).find(), "requests are built from the checked base address only");
    }

    @Test void theProviderIsOnlyCreatedFromTheUsersSettings() throws IOException {
        for (Path file : sourceFiles()) {
            if (file.toString().replace('\\', '/').endsWith("com/javelin/ai/AiProviders.java")) continue;
            if (file.equals(OLLAMA)) continue;
            assertFalse(Files.readString(file).contains("new OllamaProvider("), file + " must get a provider from AiProviders, which reads the user's settings");
        }
    }
}
