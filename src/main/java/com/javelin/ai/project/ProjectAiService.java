package com.javelin.ai.project;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.FailureReason;
import com.javelin.ai.Finding;
import com.javelin.ai.verify.RepairPrompt;
import com.javelin.ai.verify.Verification;
import com.javelin.ai.verify.VerificationLoop;
import com.javelin.ai.verify.VerificationReport;
import com.javelin.ai.verify.Verified;
import com.javelin.ai.verify.Verifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * A project request from start to finish without a window, next to {@link
 * com.javelin.ai.AiService}: {@link #prepare} decides exactly what would be sent (and refuses what
 * must not be), {@link #run} sends it and reads the answer. It never writes a file; applying a
 * proposal is {@link ProposalApplier}, after the user's approval. Everything provider-specific
 * stays behind {@link AiProvider}.
 */
public final class ProjectAiService {
  private final AiProvider provider;
  private final AiConfig config;
  private final ProjectAiConfig project;
  private final java.util.function.LongSupplier clock;
  private final Map<String, OptionalInt> modelLimits = new HashMap<>();

  public ProjectAiService(AiProvider provider, AiConfig config, ProjectAiConfig project) {
    this(provider, config, project, System::currentTimeMillis);
  }

  ProjectAiService(
      AiProvider provider,
      AiConfig config,
      ProjectAiConfig project,
      java.util.function.LongSupplier clock) {
    this.provider = provider;
    this.config = config;
    this.project = project;
    this.clock = clock;
  }

  public ProjectAiConfig projectConfig() {
    return project;
  }

  /**
   * Works out what a request would send. Nothing of the project leaves the editor here; the one
   * thing asked of the provider is the model's largest context (only the model's name is sent), and
   * only once per model.
   *
   * @throws AiException REFUSED for no model, an empty request, a budget with no room or nothing to
   *     send
   */
  public ProjectPlan prepare(
      ProjectWorkspace workspace,
      ContextMode mode,
      String instruction,
      Adjustments adjustments,
      AiCancel cancel)
      throws AiException {
    if (!config.hasModel())
      throw new AiException(
          AiException.Kind.REFUSED, "No AI model is chosen yet. Use AI > Choose Model...");
    ProjectPromptBuilder.checkInstruction(instruction);
    ContextBudget budget = project.budget(modelLimit(cancel));
    ProjectIndex index = workspace.index();
    if (index.files().isEmpty())
      throw new AiException(
          AiException.Kind.REFUSED,
          "The project has no file the AI may be shown: everything was left out by the scan"
              + " (see ai.project.exclude and .gitignore).");
    List<Pick> picks =
        mode == ContextMode.FULL_PROJECT
            ? FullProjectSelector.select(index)
            : SmartSelector.select(index, workspace.reader(), instruction, workspace.options());
    ProjectContext context =
        ContextBuilder.build(
            index,
            workspace.reader(),
            mode,
            picks,
            adjustments,
            budget,
            ProjectPromptBuilder.fixedTokens(instruction.strip()),
            workspace.options(),
            clock.getAsLong());
    if (context.included().isEmpty())
      throw new AiException(
          AiException.Kind.REFUSED,
          mode == ContextMode.SMART
              ? "No file of the project matched the request, so there is nothing to send. Say which"
                  + " part of the project it is about, or use Full Project."
              : "No project file fits the budget. Raise ai.contextTokens or exclude files with"
                  + " ai.project.exclude.");
    AiRequest request =
        ProjectPromptBuilder.build(
            config.model(),
            context,
            instruction,
            budget,
            config.structuredEdits() && provider.supportsSchemaOutput());
    List<String> warnings = new ArrayList<>();
    if (!provider.isLocal())
      warnings.add(
          "This provider is not on this computer: the project files below would leave it.");
    return new ProjectPlan(
        mode,
        instruction.strip(),
        context,
        index,
        request,
        budget,
        provider.displayName(),
        provider.location(),
        provider.isLocal(),
        warnings);
  }

  private int modelLimit(AiCancel cancel) throws AiException {
    OptionalInt known = modelLimits.get(config.model());
    if (known == null) {
      known = provider.contextLimit(config.model(), cancel);
      modelLimits.put(config.model(), known);
    }
    return known.orElse(0);
  }

  /**
   * Sends the plan's request. Text arrives through {@code onText} while the model writes; the
   * returned result holds the whole answer and, when it held file changes, the proposal.
   */
  public ProjectAiResult run(
      ProjectPlan plan, ProjectWorkspace workspace, Consumer<String> onText, AiCancel cancel)
      throws AiException {
    return runStreaming(plan, workspace, onText::accept, cancel);
  }

  /** Sends a plan and reports answer and thinking output separately as they arrive. */
  public ProjectAiResult runStreaming(
      ProjectPlan plan,
      ProjectWorkspace workspace,
      AiProvider.StreamListener listener,
      AiCancel cancel)
      throws AiException {
    cancel.throwIfCancelled();
    AiReply reply = provider.completeStreaming(plan.request(), listener, cancel);
    cancel.throwIfCancelled();
    String text = reply.text();
    ProjectSnapshot snapshot = plan.context().snapshot();
    if (text.isBlank())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.NO_EDIT_BLOCK,
          "The model sent an empty answer.");
    if (!MultiFileReply.looksLike(text))
      return new ProjectAiResult(
          text,
          null,
          reply.truncated() ? "The answer was cut off by the model; it may be incomplete." : null,
          snapshot,
          reply.truncated() ? FailureReason.TRUNCATED : FailureReason.NO_EDIT_BLOCK);
    if (reply.truncated())
      return new ProjectAiResult(
          text,
          null,
          "The reply was cut off by the model, so nothing is proposed. Its text is in the AI"
              + " panel.",
          snapshot,
          FailureReason.TRUNCATED);
    ProjectProposal proposal =
        MultiFileReply.parse(text, workspace.reader(), snapshot, workspace.options());
    String note = proposal.problems().isEmpty() ? null : String.join("\n", proposal.problems());
    if (proposal.isEmpty())
      return new ProjectAiResult(
          text,
          null,
          note == null
              ? "The reply names files but holds no change that differs from what the files"
                  + " contain."
              : "No change could be used:\n" + note,
          snapshot,
          proposal.failures().isEmpty() ? FailureReason.NO_EDIT_BLOCK : proposal.failures().get(0));
    return new ProjectAiResult(text, proposal, note, snapshot);
  }

  /**
   * Like {@link #runStreaming}, then compiles the proposed files in memory together ({@link
   * Verifier}; nothing is written) and, when that fails, asks the model again with the problems, up
   * to {@code retries} more times. The result is the passing attempt, or else the last one with a
   * change, with a failed check added as an error finding on the file it names, so the review does
   * not tick it by default. Without a proposal it is a single request.
   */
  public Verified<ProjectAiResult> runVerified(
      ProjectPlan plan,
      ProjectWorkspace workspace,
      AiProvider.StreamListener listener,
      AiCancel cancel,
      Verifier verifier,
      int retries)
      throws AiException {
    if (verifier == null) {
      ProjectAiResult only = runStreaming(plan, workspace, listener, cancel);
      return Verified.of(List.of(new VerificationLoop.Attempt<>(1, only, null)));
    }
    var step =
        new VerificationLoop.Step<ProjectAiResult>() {
          @Override
          public ProjectAiResult ask(AiRequest request, int attempt) throws AiException {
            if (attempt > 1)
              listener.onText(
                  "\n\n----- attempt " + attempt + ": asking again with the problems -----\n\n");
            return runStreaming(plan.withRequest(request), workspace, listener, cancel);
          }

          @Override
          public void retryFailed(int attempt, AiException why) {
            listener.onText(
                "\n\n----- attempt " + attempt + " failed: " + why.getMessage() + " -----\n");
          }

          @Override
          public VerificationLoop.Checked check(ProjectAiResult result, AiCancel c) {
            ProjectProposal proposal = result.proposal();
            if (proposal == null) return null;
            Map<String, String> files = new LinkedHashMap<>();
            for (FileChange change : proposal.changes())
              files.put(change.path(), change.replacement());
            Verification v = verifier.verify(files, c);
            String hunk;
            if (v.outcome() == Verification.Outcome.COMPILE_FAILED) {
              var first = v.problems().get(0);
              String text = files.get(first.path());
              hunk =
                  text == null
                      ? ""
                      : first.path() + "\n" + RepairPrompt.hunkAround(text, first.line());
            } else {
              hunk = proposal.changes().isEmpty() ? "" : proposal.changes().get(0).diff().text();
            }
            return new VerificationLoop.Checked(v, hunk);
          }
        };
    Verified<ProjectAiResult> verified =
        Verified.of(VerificationLoop.run(plan.request(), retries, step, cancel));
    var chosen = verified.chosen();
    ProjectAiResult shown = chosen.result();
    Verification v = chosen.verification();
    if (v != null && v.failed() && shown.proposal() != null) {
      String path = v.problems().isEmpty() ? "" : v.problems().get(0).path();
      shown =
          new ProjectAiResult(
              shown.text(),
              shown.proposal().withFinding(path, Finding.error(VerificationReport.findingText(v))),
              shown.note(),
              shown.snapshot(),
              shown.failure());
    }
    return new Verified<>(shown, verified.attempts());
  }
}
