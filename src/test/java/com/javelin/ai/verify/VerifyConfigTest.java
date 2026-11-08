package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyConfigTest {
  @TempDir Path dir;

  @Test
  void defaultsCompileOnlyWithTheSmallProfilesBudget() {
    VerifyConfig c = VerifyConfig.from(new Settings(dir.resolve("s.conf")));
    assertTrue(c.enabled());
    assertEquals(2, c.retries());
    assertFalse(c.runTests(), "running a model's code is opt-in");
    assertEquals(VerifyConfig.defaults(), c);
  }

  @Test
  void valuesAreReadAndClamped() {
    Settings s = new Settings(dir.resolve("s.conf"));
    s.put(Scope.USER, VerifyConfig.ENABLED, "false");
    s.put(Scope.USER, VerifyConfig.RETRIES, "99");
    s.put(Scope.USER, VerifyConfig.TESTS, "true");
    s.put(Scope.USER, VerifyConfig.TEST_SECONDS, "1");
    VerifyConfig c = VerifyConfig.from(s);
    assertFalse(c.enabled());
    assertEquals(VerifyConfig.MAX_RETRIES, c.retries());
    assertTrue(c.runTests());
    assertEquals(VerifyConfig.MIN_TEST_SECONDS, c.testSeconds());
    assertEquals(0, new VerifyConfig(true, -4, false, 30).retries());
  }

  @Test
  void theKeysAreUserOnlyLikeEveryAiSetting() {
    for (String key :
        new String[] {
          VerifyConfig.ENABLED, VerifyConfig.RETRIES, VerifyConfig.TESTS, VerifyConfig.TEST_SECONDS
        }) assertTrue(Settings.isUserOnly(key), key);
  }

  @Test
  void theSendDialogSaysWhatWillRunAfterTheAnswer() {
    assertEquals("", new VerifyConfig(false, 2, true, 30).announcement());
    String compileOnly = VerifyConfig.defaults().announcement();
    assertTrue(
        compileOnly.contains("compiled in memory")
            && compileOnly.contains("up to 2 more requests"));
    assertFalse(compileOnly.contains("tests"));
    String withTests = new VerifyConfig(true, 1, true, 45).announcement();
    assertTrue(
        withTests.contains("tests")
            && withTests.contains("45 s")
            && withTests.contains("1 more request "));
  }
}
