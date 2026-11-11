package com.javelin.ai.profile;

import com.javelin.ai.plan.PlanConfig;
import com.javelin.ai.symbols.SymbolBudget;
import com.javelin.ai.verify.VerifyConfig;

/**
 * What a kind of model is asked to do and how often it may try again: one row of the profile table
 * in ROADMAP-AI-RELIABILITY.md. The pipeline reads a profile and the provider's declared
 * capabilities; it never looks at which vendor is behind a provider.
 *
 * <p>Four fields drive the pipeline today: {@code retries} (the verification budget), {@code
 * maxSteps} (the planner's hard limit), {@code symbolBudget} (what symbol-level context may take)
 * and {@code prefersStructuredOutput} (edits as JSON when the provider can be held to a schema). An
 * explicit setting ({@code ai.verify.retries}, {@code ai.plan.maxSteps}, {@code
 * ai.symbols.profile}, {@code ai.structuredEdits}) always wins over the profile. {@code
 * editsPerProposal}, {@code editFormat} and {@code planning} are recorded so the table is complete
 * and tests can pin it, but nothing enforces them yet: enforcing one hunk for the small profile
 * would change what the Phase 0 corpus measures, so it waits for an eval run.
 *
 * @param id {@code small}, {@code frontier} or {@code custom}
 * @param editsPerProposal hunks one proposal may carry ({@link #SEVERAL} for no limit)
 * @param editFormat the format asked for first
 * @param prefersStructuredOutput ask for JSON edits when the provider supports it
 * @param retries repeat requests after a failed check
 * @param planning who produces the plan of a multi-file request
 * @param maxSteps steps in a plan
 * @param symbolBudget limits for symbol-level context
 */
public record ModelProfile(
    String id,
    int editsPerProposal,
    EditFormat editFormat,
    boolean prefersStructuredOutput,
    int retries,
    Planning planning,
    int maxSteps,
    SymbolBudget symbolBudget) {

  /** "No limit" for {@link #editsPerProposal}. */
  public static final int SEVERAL = Integer.MAX_VALUE;

  public enum EditFormat {
    /** Search-replace blocks, anchored by content. */
    SEARCH_REPLACE,
    /** A whole method, or search-replace blocks. */
    WHOLE_METHOD_OR_SEARCH_REPLACE
  }

  public enum Planning {
    /** An outside plan or manual steps (Phase 3's planner is optional). */
    EXTERNAL_OR_MANUAL,
    /** The model plans for itself. */
    MODEL
  }

  /**
   * The editor as it has been measured: 9B-class local models. Every value equals the default the
   * matching setting already had, so choosing this profile changes nothing.
   */
  public static final ModelProfile SMALL =
      new ModelProfile(
          "small",
          1,
          EditFormat.SEARCH_REPLACE,
          false,
          VerifyConfig.DEFAULT_RETRIES,
          Planning.EXTERNAL_OR_MANUAL,
          PlanConfig.SMALL_STEPS,
          SymbolBudget.SMALL);

  /** Larger models: bigger windows, fewer retries, longer plans. Not measured. */
  public static final ModelProfile FRONTIER =
      new ModelProfile(
          "frontier",
          SEVERAL,
          EditFormat.WHOLE_METHOD_OR_SEARCH_REPLACE,
          true,
          1,
          Planning.MODEL,
          PlanConfig.FRONTIER_STEPS,
          SymbolBudget.FRONTIER);

  public ModelProfile {
    retries = Math.max(0, Math.min(VerifyConfig.MAX_RETRIES, retries));
    maxSteps = Math.max(1, Math.min(PlanConfig.FRONTIER_STEPS, maxSteps));
    editsPerProposal = Math.max(1, editsPerProposal);
  }
}
