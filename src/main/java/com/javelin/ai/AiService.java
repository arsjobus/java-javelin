package com.javelin.ai;

import com.javelin.ai.verify.RepairPrompt;
import com.javelin.ai.verify.Verification;
import com.javelin.ai.verify.VerificationLoop;
import com.javelin.ai.verify.VerificationReport;
import com.javelin.ai.verify.Verified;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One AI action from start to finish, without a window: {@link #prepare} decides exactly what would
 * be sent (and refuses what must not be), {@link #run} sends it and turns the answer into text or a
 * proposal. It never applies anything.
 */
public final class AiService {
  private final AiProvider provider;
  private final AiConfig config;

  public AiService(AiProvider provider, AiConfig config) {
    this.provider = provider;
    this.config = config;
  }

  public AiProvider provider() {
    return provider;
  }

  /** The models the provider has, for choosing one. */
  public List<String> models(AiCancel cancel) throws AiException {
    return provider.models(cancel);
  }

  /**
   * Works out what a request would send. Nothing leaves the editor here: this is the step before
   * the user's approval.
   */
  public AiPlan prepare(AiAction action, AiSource source, String instruction) throws AiException {
    if (!config.hasModel())
      throw new AiException(
          AiException.Kind.REFUSED, "No AI model is chosen yet. Use AI > Choose Model...");
    if (SensitiveFiles.isSensitive(source.fileName()))
      throw new AiException(
          AiException.Kind.REFUSED,
          source.name() + " is a file for credentials, so it is never sent to an AI model.");
    AiContext context = AiContext.of(action, source, config.maxContextChars());
    AiRequest request =
        PromptBuilder.build(
            action,
            context,
            instruction,
            config.model(),
            config.editBlockMinLines(),
            config.structuredEdits() && provider.supportsSchemaOutput());
    List<String> warnings = new ArrayList<>();
    for (String what : SensitiveFiles.warnings(context.text()))
      warnings.add(
          "The text to send looks like it contains "
              + what
              + ". Check the message below before you send it.");
    String testName =
        action == AiAction.GENERATE_TESTS
            ? TestFileNames.suggest(source.fileName(), source.languageId())
            : null;
    return new AiPlan(
        action,
        context,
        request,
        provider.displayName(),
        provider.location(),
        provider.isLocal(),
        testName,
        warnings);
  }

  /**
   * Sends the plan's request. Text arrives through {@code onText} while the model writes; the
   * returned result is the whole answer.
   */
  public AiResult run(AiPlan plan, Consumer<String> onText, AiCancel cancel) throws AiException {
    return runStreaming(plan, onText::accept, cancel);
  }

  /** Sends a plan and reports answer and thinking output separately as they arrive. */
  public AiResult runStreaming(AiPlan plan, AiProvider.StreamListener listener, AiCancel cancel)
      throws AiException {
    cancel.throwIfCancelled();
    AiReply reply = provider.completeStreaming(plan.request(), listener, cancel);
    cancel.throwIfCancelled();
    String text = reply.text();
    if (text.isBlank())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.NO_EDIT_BLOCK,
          "The model sent an empty answer.");
    if (!plan.action().proposes())
      return new AiResult(
          text,
          null,
          reply.truncated() ? "The answer was cut off by the model; it may be incomplete." : null);
    if (reply.truncated())
      return new AiResult(
          text,
          null,
          "The reply was cut off by the model, so nothing is proposed. Its text is in the AI"
              + " panel.",
          List.of(),
          FailureReason.TRUNCATED);
    try {
      if (plan.action() == AiAction.GENERATE_TESTS) {
        ResponseParser.Parsed parsed =
            ResponseParser.parse(
                text, MarkdownText.is(plan.context().languageId(), plan.context().name()));
        AiProposal proposal =
            AiProposal.newDocument(plan.testFileName(), parsed.code(), parsed.explanation());
        return new AiResult(text, proposal, extraBlocksNote(parsed.extraBlocks()));
      }
      ChangeReply.Interpreted change =
          ChangeReply.interpret(
              text, plan.context().text(), plan.context().name(), plan.context().languageId());
      AiProposal proposal =
          AiProposal.replace(plan.context(), change.code(), change.explanation(), change.anchors());
      List<Finding> findings = new ArrayList<>(change.notes());
      findings.addAll(
          ChangeChecker.check(
              plan.context().languageId(), plan.context().text(), proposal.replacement()));
      return new AiResult(text, proposal, extraBlocksNote(change.extraBlocks()), findings);
    } catch (AiException ex) {
      return new AiResult(text, null, ex.getMessage(), List.of(), ex.reason());
    }
  }

  /**
   * Like {@link #runStreaming}, then checks a proposed change by compiling it in memory ({@link
   * Verifier}; nothing is written) and, when the check fails, asks the model again with the
   * problems, up to {@code retries} more times. The result is the passing attempt, or else the last
   * one with a change, with a failed check added as an error finding, so the review says "Apply
   * anyway". Without a change to check (an answer to Explain, new tests, a file outside the
   * project) it is a single request.
   */
  public Verified<AiResult> runVerified(
      AiPlan plan,
      AiProvider.StreamListener listener,
      AiCancel cancel,
      VerifyTarget target,
      Verifier verifier,
      int retries)
      throws AiException {
    if (target == null || verifier == null || !plan.action().proposes()) {
      AiResult only = runStreaming(plan, listener, cancel);
      return Verified.of(List.of(new VerificationLoop.Attempt<>(1, only, null)));
    }
    var step =
        new VerificationLoop.Step<AiResult>() {
          @Override
          public AiResult ask(AiRequest request, int attempt) throws AiException {
            if (attempt > 1)
              listener.onText(
                  "\n\n----- attempt " + attempt + ": asking again with the problems -----\n\n");
            return runStreaming(plan.withRequest(request), listener, cancel);
          }

          @Override
          public void retryFailed(int attempt, AiException why) {
            listener.onText(
                "\n\n----- attempt " + attempt + " failed: " + why.getMessage() + " -----\n");
          }

          @Override
          public VerificationLoop.Checked check(AiResult result, AiCancel c) {
            AiProposal proposal = result.proposal();
            if (proposal == null
                || proposal.kind() != AiProposal.Kind.REPLACE_RANGE
                || !proposal.isStillValid(target.fullText())) return null;
            String text = proposal.applyTo(target.fullText());
            Verification v = verifier.verify(java.util.Map.of(target.path(), text), c);
            String hunk =
                v.outcome() == Verification.Outcome.COMPILE_FAILED
                    ? RepairPrompt.hunkAround(text, v.problems().get(0).line())
                    : proposal.diff().text();
            return new VerificationLoop.Checked(v, hunk);
          }
        };
    Verified<AiResult> verified =
        Verified.of(VerificationLoop.run(plan.request(), retries, step, cancel));
    var chosen = verified.chosen();
    AiResult shown = chosen.result();
    if (chosen.verification() != null && chosen.verification().failed())
      shown =
          shown.withFinding(Finding.error(VerificationReport.findingText(chosen.verification())));
    return new Verified<>(shown, verified.attempts());
  }

  private static String extraBlocksNote(int extra) {
    return extra > 0
        ? "The reply had " + (extra + 1) + " code blocks; only the first is proposed."
        : null;
  }
}
