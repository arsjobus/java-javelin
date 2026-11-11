package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalSettingsTest {
  @TempDir Path dir;
  private Settings settings;
  private Path project;

  @BeforeEach
  void setUp() throws Exception {
    settings = new Settings(dir.resolve("settings.conf"));
    project = Files.createDirectory(dir.resolve("project"));
  }

  private AiConfig external() {
    settings.put(Scope.USER, "ai.provider", "external");
    return AiConfig.from(settings);
  }

  @Test
  void theExternalProviderIsListedButNeverTheDefault() {
    assertTrue(AiProviders.available().contains("external"));
    assertEquals("ollama", AiConfig.from(settings).provider());
  }

  @Test
  void withoutAnOpenProjectTheExternalProviderIsRefused() {
    AiConfig config = external();
    for (ExternalAccess access : new ExternalAccess[] {null, ExternalAccess.NONE}) {
      AiException ex = assertThrows(AiException.class, () -> AiProviders.create(config, access));
      assertEquals(AiException.Kind.REFUSED, ex.kind());
      assertTrue(ex.getMessage().contains("open project"));
    }
    assertThrows(AiException.class, () -> AiProviders.create(config), "the old entry point too");
  }

  @Test
  void withoutAKeyItIsRefusedAndSaysWhereTheKeyGoes() {
    ExternalAccess access =
        new ExternalAccess(
            project,
            ExternalConsent.forSettings(settings),
            ExternalConfig.from(settings),
            name -> null);
    AiConfig config = external();
    AiException ex = assertThrows(AiException.class, () -> AiProviders.create(config, access));
    assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
    assertTrue(ex.getMessage().contains(ExternalConfig.KEY_FILE));
  }

  @Test
  void aKeyInTheEnvironmentCreatesAProviderThatStillNeedsConsent() throws Exception {
    ExternalAccess access =
        new ExternalAccess(
            project,
            ExternalConsent.forSettings(settings),
            ExternalConfig.from(settings),
            Map.of("ANTHROPIC_API_KEY", " sk-abc ")::get);
    AiProvider provider = AiProviders.create(external(), access);
    assertEquals("external", provider.id());
    assertFalse(provider.isLocal());
    assertTrue(provider.consentNotice().isPresent());
  }

  @Test
  void theEnvironmentVariableCanBeNamedInTheSettings() throws Exception {
    settings.put(Scope.USER, ExternalConfig.KEY_ENV, "MY_KEY");
    ExternalAccess access =
        new ExternalAccess(
            project,
            ExternalConsent.forSettings(settings),
            ExternalConfig.from(settings),
            Map.of("MY_KEY", "k")::get);
    assertEquals("external", AiProviders.create(external(), access).id());
  }

  @Test
  void aKeyFileOutsideTheProjectIsReadAndOneInsideItIsRefused() throws Exception {
    Path outside = Files.writeString(dir.resolve("key.txt"), "sk-from-file\nignored\n");
    ExternalConfig config = new ExternalConfig("NOPE", outside.toString(), false);
    assertEquals("sk-from-file", ExternalKey.read(config, n -> null, project));
    Path inside = Files.writeString(project.resolve("key.txt"), "sk-in-project");
    AiException ex =
        assertThrows(
            AiException.class,
            () ->
                ExternalKey.read(
                    new ExternalConfig("NOPE", inside.toString(), false), n -> null, project));
    assertTrue(ex.getMessage().contains("inside the open project"));
    assertFalse(ex.getMessage().contains("sk-in-project"));
  }

  @Test
  void aKeyWithControlCharactersOrASpaceIsRefusedAndNotEchoed() {
    for (String bad : new String[] {"sk a", "sk\u0001a"}) {
      AiException ex =
          assertThrows(
              AiException.class,
              () -> ExternalKey.read(new ExternalConfig("K", "", false), n -> bad, project));
      assertFalse(ex.getMessage().contains(bad));
    }
  }

  @Test
  void aRelativeOrEmptyKeyFileIsRefused() throws Exception {
    assertThrows(
        AiException.class,
        () -> ExternalKey.read(new ExternalConfig("NOPE", "key.txt", false), n -> null, project));
    Path empty = Files.writeString(dir.resolve("empty.txt"), "\n");
    assertThrows(
        AiException.class,
        () ->
            ExternalKey.read(
                new ExternalConfig("NOPE", empty.toString(), false), n -> null, project));
  }

  @Test
  void settingsAreValidatedAndAreUserOnly() {
    assertEquals(java.util.List.of(), ExternalConfig.validate(settings));
    settings.put(Scope.USER, ExternalConfig.KEY_ENV, "not a name");
    settings.put(Scope.USER, ExternalConfig.KEY_FILE, "relative/key");
    settings.put(Scope.USER, ExternalConfig.STRUCTURED, "maybe");
    assertEquals(3, ExternalConfig.validate(settings).size());
    for (String key :
        new String[] {
          ExternalConfig.KEY_ENV, ExternalConfig.KEY_FILE, ExternalConfig.STRUCTURED, "ai.profile"
        }) assertTrue(Settings.isUserOnly(key), key);
  }

  @Test
  void theTransportRefusesAnyDestinationButTheProvidersOwnHostOverHttps() {
    for (String bad :
        new String[] {
          "http://api.anthropic.com/v1/messages",
          "https://example.com/v1/messages",
          "https://api.anthropic.com.evil.test/v1/messages",
          "https://api.anthropic.com:8443/v1/messages",
          "https://user@api.anthropic.com/v1/messages",
          "https://127.0.0.1/v1/messages"
        }) {
      AiException ex =
          assertThrows(
              AiException.class,
              () -> JdkExternalTransport.requireDestination(URI.create(bad)),
              bad);
      assertEquals(AiException.Kind.REFUSED, ex.kind());
    }
    assertDoesNotThrow(
        () ->
            JdkExternalTransport.requireDestination(
                URI.create("https://api.anthropic.com/v1/messages?limit=1")));
  }
}
