package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No request without consent, and consent belongs to one project, one provider, one host. */
class ConsentGateTest {
  @TempDir Path dir;
  private Path projectA;
  private Path projectB;
  private ExternalConsent consent;
  private ScriptedTransport transport;

  @BeforeEach
  void setUp() throws Exception {
    projectA = Files.createDirectory(dir.resolve("a"));
    projectB = Files.createDirectory(dir.resolve("b"));
    consent = new ExternalConsent(dir.resolve("config").resolve("ai-consent.tsv"));
    transport = new ScriptedTransport();
  }

  private ExternalProvider providerFor(Path project) {
    return new ExternalProvider(transport, "key", Duration.ofSeconds(5), project, consent, false);
  }

  private static AiRequest request() {
    return new AiRequest("m", "system", "prompt");
  }

  private void send(ExternalProvider provider) throws AiException {
    provider.complete(request(), piece -> {}, new AiCancel());
  }

  @Test
  void noRequestIsSentWithoutConsent() {
    ExternalProvider provider = providerFor(projectA);
    AiException ex = assertThrows(AiException.class, () -> send(provider));
    assertEquals(AiException.Kind.REFUSED, ex.kind());
    assertEquals(0, transport.calls.size(), "nothing may reach the transport");
  }

  @Test
  void afterConsentTheRequestGoesToTheTransport() throws Exception {
    ExternalProvider provider = providerFor(projectA);
    assertTrue(provider.consentNotice().isPresent());
    provider.recordConsent();
    assertTrue(provider.consentNotice().isEmpty());
    send(provider);
    assertEquals(1, transport.calls.size());
  }

  @Test
  void consentIsPerProject() throws Exception {
    providerFor(projectA).recordConsent();
    send(providerFor(projectA));
    ExternalProvider other = providerFor(projectB);
    assertTrue(other.consentNotice().isPresent(), "another project is asked again");
    assertThrows(AiException.class, () -> send(other));
    assertEquals(1, transport.calls.size());
  }

  @Test
  void consentIsForOneProviderAndOneHost() {
    consent.grant(projectA, ExternalProvider.ID, ExternalProvider.HOST);
    assertTrue(consent.granted(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertFalse(consent.granted(projectA, "someone-else", ExternalProvider.HOST));
    assertFalse(consent.granted(projectA, ExternalProvider.ID, "api.example.com"));
  }

  @Test
  void consentSurvivesARestartAndIsReadFromTheFile() {
    assertTrue(consent.grant(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    ExternalConsent reopened = new ExternalConsent(consent.file());
    assertTrue(reopened.granted(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertFalse(reopened.granted(projectB, ExternalProvider.ID, ExternalProvider.HOST));
  }

  @Test
  void deletingTheLineOrTheFileTakesConsentBackAtOnce() throws Exception {
    consent.grant(projectA, ExternalProvider.ID, ExternalProvider.HOST);
    consent.grant(projectB, ExternalProvider.ID, ExternalProvider.HOST);
    List<String> kept = new ArrayList<>();
    for (String line : Files.readAllLines(consent.file()))
      if (line.startsWith("#") || line.endsWith(projectB.toRealPath().toString())) kept.add(line);
    Files.write(consent.file(), kept);
    assertFalse(consent.granted(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertTrue(consent.granted(projectB, ExternalProvider.ID, ExternalProvider.HOST));
    Files.delete(consent.file());
    assertFalse(consent.granted(projectB, ExternalProvider.ID, ExternalProvider.HOST));
  }

  @Test
  void revokeAndRevokeAllWork() {
    consent.grant(projectA, ExternalProvider.ID, ExternalProvider.HOST);
    consent.grant(projectB, ExternalProvider.ID, ExternalProvider.HOST);
    consent.revoke(projectA, ExternalProvider.ID, ExternalProvider.HOST);
    assertFalse(consent.granted(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertTrue(consent.granted(projectB, ExternalProvider.ID, ExternalProvider.HOST));
    consent.revokeAll();
    assertFalse(consent.granted(projectB, ExternalProvider.ID, ExternalProvider.HOST));
  }

  @Test
  void aNoProjectCanNeverBeGrantedAnything() {
    assertFalse(consent.grant(null, ExternalProvider.ID, ExternalProvider.HOST));
    assertFalse(consent.granted(null, ExternalProvider.ID, ExternalProvider.HOST));
  }

  @Test
  void aGrantThatCannotBeWrittenHoldsForTheSessionOnly() throws Exception {
    Path blocker = Files.writeString(dir.resolve("file"), "not a folder");
    ExternalConsent unwritable = new ExternalConsent(blocker.resolve("ai-consent.tsv"));
    assertFalse(unwritable.grant(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertTrue(unwritable.granted(projectA, ExternalProvider.ID, ExternalProvider.HOST));
    assertFalse(
        new ExternalConsent(blocker.resolve("ai-consent.tsv"))
            .granted(projectA, ExternalProvider.ID, ExternalProvider.HOST),
        "a later session starts without it");
  }

  @Test
  void theNoticeNamesTheProviderTheHostTheProjectAndHowToTakeConsentBack() {
    String notice = providerFor(projectA).consentNotice().orElseThrow();
    assertTrue(notice.contains(ExternalProvider.DISPLAY_NAME));
    assertTrue(notice.contains("https://" + ExternalProvider.HOST));
    assertTrue(notice.contains(projectA.toString()));
    assertTrue(notice.contains(consent.file().toString()));
    assertTrue(notice.contains("exactly the message shown below"));
  }

  @Test
  void aProviderWithoutAProjectNeverHasConsent() {
    ExternalProvider provider = providerFor(null);
    provider.recordConsent();
    assertTrue(provider.consentNotice().isPresent());
    assertThrows(AiException.class, () -> send(provider));
    assertEquals(0, transport.calls.size());
  }
}
