package com.javelin.ai.verify;

import com.javelin.settings.Settings;

/**
 * The {@code ai.verify.*} settings. User file only, like every {@code ai.*} key. Compiling is on by
 * default because it runs no project code ({@code -proc:none}); running tests runs code a model
 * wrote, so it is off until the user turns it on, and the Send dialog says so when it is on.
 */
public record VerifyConfig(boolean enabled, int retries, boolean runTests, int testSeconds) {
  public static final String ENABLED = "ai.verify";
  public static final String RETRIES = "ai.verify.retries";
  public static final String TESTS = "ai.verify.tests";
  public static final String TEST_SECONDS = "ai.verify.testSeconds";

  /** The small profile's budget in ROADMAP-AI-RELIABILITY.md. */
  public static final int DEFAULT_RETRIES = 2, MAX_RETRIES = 5;

  public static final int DEFAULT_TEST_SECONDS = 30, MIN_TEST_SECONDS = 5, MAX_TEST_SECONDS = 300;

  public VerifyConfig {
    retries = Math.max(0, Math.min(MAX_RETRIES, retries));
    testSeconds = Math.max(MIN_TEST_SECONDS, Math.min(MAX_TEST_SECONDS, testSeconds));
  }

  public static VerifyConfig defaults() {
    return new VerifyConfig(true, DEFAULT_RETRIES, false, DEFAULT_TEST_SECONDS);
  }

  public static VerifyConfig from(Settings settings) {
    return new VerifyConfig(
        settings.getBoolean(ENABLED, true),
        settings.getInt(RETRIES, DEFAULT_RETRIES),
        settings.getBoolean(TESTS, false),
        settings.getInt(TEST_SECONDS, DEFAULT_TEST_SECONDS));
  }

  /** The text of a Send dialog with what happens after the answer added at the end. */
  public String appendedTo(String preview, boolean willCheck) {
    String after = willCheck ? announcement() : "";
    return after.isEmpty() ? preview : preview + "\n----- after the answer -----\n" + after;
  }

  /** What the Send dialog says will happen after the answer arrives; empty when nothing does. */
  public String announcement() {
    if (!enabled) return "";
    return "After the answer: the proposed change is compiled in memory (nothing is written to the"
        + " project)"
        + (runTests
            ? " and the project's tests that use the changed classes are run in a separate Java"
                + " process (time limit "
                + testSeconds
                + " s)"
            : "")
        + ". If that fails, up to "
        + retries
        + " more request"
        + (retries == 1 ? "" : "s")
        + " go to the same model with the compiler's messages.\n";
  }
}
