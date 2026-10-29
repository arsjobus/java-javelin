package com.javelin_ide.audit;

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
 * build enforces. If a future feature genuinely needs the network (for example an optional local-model integration),
 * add it here deliberately, with a comment saying why, in the same change.
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
                for (Pattern p : FORBIDDEN) if (p.matcher(lines.get(i)).find()) violations.add(file + ":" + (i + 1) + " matches " + p.pattern());
        }
        assertEquals(List.of(), violations, "network use must be a deliberate, reviewed decision");
    }

    @Test void theGuardActuallyCatchesViolations() {
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("HttpClient.newHttpClient()").find()));
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("new Socket(\"example.com\", 80)").find()));
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("url = \"https://api.mixpanel.com/track\"").find()));
        assertTrue(FORBIDDEN.stream().noneMatch(p -> p.matcher("// see https://github.com/arsjobus/javelin_ide").find()));
    }
}
