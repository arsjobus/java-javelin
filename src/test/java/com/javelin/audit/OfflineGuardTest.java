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
 * Javelin IDE is local-first: it has no telemetry, no update check and no cloud calls. This test
 * makes that a rule the build enforces. If a future feature genuinely needs the network, add it
 * here deliberately, with a comment saying why, in the same change.
 *
 * <p>There are exactly two such features. The local-model integration (v0.7): {@code
 * OllamaProvider} may use the JDK's HTTP client, because Ollama is a server on this computer; it is
 * held to the loopback address by {@code LoopbackEndpoint}. And the external AI provider
 * (ROADMAP-AI-RELIABILITY.md, Phase 5): {@code JdkExternalTransport} is the one client allowed to
 * leave the computer. It sends to one fixed host over HTTPS, it exists only while {@code
 * ai.provider} is {@code external}, a project is open and a key was found, and {@code
 * ExternalProvider} refuses every request until the project has consented. The tests below check
 * that both keep no proxy, follow no redirect and that nothing else in the editor may use the
 * network.
 */
class OfflineGuardTest {
  private static final Path SOURCES = Path.of("src", "main", "java");

  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("java\\.net\\.http"),
          Pattern.compile("\\bHttpClient\\b"),
          Pattern.compile("\\bHttpURLConnection\\b"),
          Pattern.compile("\\bURLConnection\\b"),
          Pattern.compile(
              "\\bjava\\.net\\.(Socket|ServerSocket|DatagramSocket|MulticastSocket)\\b"),
          Pattern.compile("\\bnew\\s+(Socket|ServerSocket|DatagramSocket)\\s*\\("),
          Pattern.compile("\\bInetAddress\\b"),
          Pattern.compile("\\bjavax\\.net\\b"),
          Pattern.compile("\\.openConnection\\s*\\("),
          Pattern.compile("\\.openStream\\s*\\("),
          Pattern.compile("\\bjava\\.rmi\\b"),
          Pattern.compile("\\bWebEngine\\b"),
          Pattern.compile("\\bWebView\\b"), // embedded browsers phone home by design
          Pattern.compile(
              "https?://[^\\s\"')]*(analytics|telemetry|sentry|segment|mixpanel|amplitude|google-analytics|crashlytics)",
              Pattern.CASE_INSENSITIVE));

  /**
   * The one file that may use the HTTP client, and only the HTTP client. Everything else stays
   * forbidden for it too.
   */
  private static final Path OLLAMA = SOURCES.resolve("com/javelin/ai/OllamaProvider.java");

  /** The second and last one: the external provider's transport, held to one host. */
  private static final Path EXTERNAL_TRANSPORT =
      SOURCES.resolve("com/javelin/ai/JdkExternalTransport.java");

  private static final Path EXTERNAL_PROVIDER =
      SOURCES.resolve("com/javelin/ai/ExternalProvider.java");
  private static final Path PROVIDERS = SOURCES.resolve("com/javelin/ai/AiProviders.java");

  private static boolean mayUseHttpClient(Path file) {
    return file.equals(OLLAMA) || file.equals(EXTERNAL_TRANSPORT);
  }

  private static final List<String> HTTP_CLIENT_PATTERNS =
      List.of("java\\.net\\.http", "\\bHttpClient\\b");

  private static List<Path> sourceFiles() throws IOException {
    try (Stream<Path> files = Files.walk(SOURCES)) {
      return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  @Test
  void theSourceTreeIsWhereTheTestExpectsIt() throws IOException {
    assertTrue(Files.isDirectory(SOURCES), "run the tests from the project root");
    assertTrue(sourceFiles().size() > 20);
  }

  @Test
  void noMainSourceUsesTheNetwork() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : sourceFiles()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++)
        for (Pattern p : FORBIDDEN) {
          if (mayUseHttpClient(file) && HTTP_CLIENT_PATTERNS.contains(p.pattern()))
            continue; // the two allowed uses, checked below
          if (p.matcher(lines.get(i)).find())
            violations.add(file + ":" + (i + 1) + " matches " + p.pattern());
        }
    }
    assertEquals(List.of(), violations, "network use must be a deliberate, reviewed decision");
  }

  @Test
  void theGuardActuallyCatchesViolations() {
    assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher("HttpClient.newHttpClient()").find()));
    assertTrue(
        FORBIDDEN.stream().anyMatch(p -> p.matcher("new Socket(\"example.com\", 80)").find()));
    assertTrue(
        FORBIDDEN.stream()
            .anyMatch(p -> p.matcher("url = \"https://api.mixpanel.com/track\"").find()));
    assertTrue(
        FORBIDDEN.stream()
            .noneMatch(p -> p.matcher("// see https://github.com/arsjobus/javelin_ide").find()));
  }

  @Test
  void onlyTheTwoProviderClientsUseTheHttpClient() throws IOException {
    assertTrue(Files.isRegularFile(OLLAMA));
    assertTrue(Files.isRegularFile(EXTERNAL_TRANSPORT));
    for (Path file : sourceFiles()) {
      if (mayUseHttpClient(file)) continue;
      String text = Files.readString(file);
      for (String pattern : HTTP_CLIENT_PATTERNS)
        assertFalse(
            Pattern.compile(pattern).matcher(text).find(), file + " must not use the HTTP client");
    }
    for (Path allowed : List.of(OLLAMA, EXTERNAL_TRANSPORT))
      assertTrue(
          Pattern.compile("java\\.net\\.http").matcher(Files.readString(allowed)).find(),
          "the allow-list entry " + allowed + " is stale if the file does not use the client");
  }

  @Test
  void theOllamaProviderIsHeldToThisComputer() throws IOException {
    String text = Files.readString(OLLAMA);
    assertTrue(
        text.contains("LoopbackEndpoint.require(endpoint)"),
        "the address is checked before a client exists");
    assertTrue(
        text.contains("HttpClient.Builder.NO_PROXY"),
        "requests must not be routed through a proxy");
    assertTrue(
        text.contains("HttpClient.Redirect.NEVER"),
        "a redirect must not send the request somewhere else");
    assertEquals(
        1,
        text.split("HttpClient\\.newBuilder\\(\\)", -1).length - 1,
        "one client, built in one place");
    Pattern uri = Pattern.compile("URI\\.create\\(|new\\s+URI\\(|\\.resolve\\(\\s*\\\"https?:");
    assertFalse(uri.matcher(text).find(), "requests are built from the checked base address only");
  }

  @Test
  void theProviderIsOnlyCreatedFromTheUsersSettings() throws IOException {
    for (Path file : sourceFiles()) {
      if (file.toString().replace('\\', '/').endsWith("com/javelin/ai/AiProviders.java")) continue;
      if (file.equals(OLLAMA)) continue;
      assertFalse(
          Files.readString(file).contains("new OllamaProvider("),
          file + " must get a provider from AiProviders, which reads the user's settings");
    }
  }

  @Test
  void theExternalTransportIsHeldToOneHostOverHttps() throws IOException {
    String text = Files.readString(EXTERNAL_TRANSPORT);
    assertTrue(text.contains("HttpClient.Builder.NO_PROXY"), "no proxy");
    assertTrue(text.contains("HttpClient.Redirect.NEVER"), "no redirect");
    assertEquals(
        1,
        text.split("HttpClient\\.newBuilder\\(\\)", -1).length - 1,
        "one client, built in one place");
    assertEquals(
        2,
        text.split("requireDestination\\(uri\\);", -1).length - 1,
        "every request, get and post, is checked before a request is built");
    Pattern uri = Pattern.compile("URI\\.create\\(|new\\s+URI\\(");
    assertFalse(uri.matcher(text).find(), "the transport only sends to addresses it is handed");
  }

  @Test
  void exactlyOneFileNamesTheExternalHostAndItIsAConstant() throws IOException {
    List<Path> naming = new ArrayList<>();
    for (Path file : sourceFiles())
      if (Files.readString(file).contains("api.anthropic.com")) naming.add(file);
    assertEquals(List.of(EXTERNAL_PROVIDER), naming);
    String text = Files.readString(EXTERNAL_PROVIDER);
    assertTrue(text.contains("public static final String HOST = \"api.anthropic.com\";"));
    assertEquals(
        1,
        text.split("api\\.anthropic\\.com", -1).length - 1 - countOccurrencesInComments(text),
        "the host is written once outside comments");
  }

  private static int countOccurrencesInComments(String text) {
    int count = 0;
    for (String line : text.split("\n")) {
      String t = line.strip();
      if ((t.startsWith("*") || t.startsWith("//") || t.startsWith("/*"))
          && t.contains("api.anthropic.com")) count++;
    }
    return count;
  }

  @Test
  void theExternalClassesAreOnlyCreatedFromTheUsersSettings() throws IOException {
    for (Path file : sourceFiles()) {
      if (file.equals(PROVIDERS)
          || file.equals(EXTERNAL_PROVIDER)
          || file.equals(EXTERNAL_TRANSPORT)) continue;
      String text = Files.readString(file);
      assertFalse(
          text.contains("new ExternalProvider("), file + " must get a provider from AiProviders");
      assertFalse(
          text.contains("new JdkExternalTransport("), file + " must not build the external client");
    }
    String providers = Files.readString(PROVIDERS);
    int branch = providers.indexOf("config.provider().equals(ExternalProvider.ID)");
    assertTrue(branch > 0, "the external provider is created only when ai.provider names it");
    assertTrue(providers.indexOf("new JdkExternalTransport(") > branch);
  }

  @Test
  void theExternalProviderLooksForConsentBeforeItTouchesTheTransport() throws IOException {
    String text = Files.readString(EXTERNAL_PROVIDER);
    int complete = text.indexOf("public AiReply complete(");
    assertTrue(complete > 0);
    int gate = text.indexOf("consent.granted(projectRoot, ID, HOST)", complete);
    int send = text.indexOf("transport.post(", complete);
    assertTrue(gate > 0 && send > 0 && gate < send, "the consent check comes first");
  }

  @Test
  void withDefaultSettingsNoExternalClientExists(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    var settings = new com.javelin.settings.Settings(dir.resolve("settings.conf"));
    var config = com.javelin.ai.AiConfig.from(settings);
    assertEquals("ollama", com.javelin.ai.AiProviders.create(config).id());
    assertEquals(
        "ollama",
        com.javelin.ai.AiProviders.create(config, com.javelin.ai.ExternalAccess.NONE).id());
  }
}
