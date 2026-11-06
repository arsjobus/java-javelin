package com.javelin.ai.project;

import java.util.List;

/**
 * The project as the model will see it: one block of text (an overview, the structure and the
 * files, each between markers that name it), which files are in it and why, which are not and why,
 * and the budget it uses. Built by {@link ContextBuilder}; knows nothing of Ollama or JavaFX.
 */
public record ProjectContext(
    String projectName,
    ContextMode mode,
    String text,
    List<ContextEntry> included,
    List<Omitted> omitted,
    int eligibleFiles,
    int textTokens,
    ContextBudget.Usage usage,
    List<String> warnings,
    ProjectSnapshot snapshot) {
  public ProjectContext {
    included = List.copyOf(included);
    omitted = List.copyOf(omitted);
    warnings = List.copyOf(warnings);
  }

  /** The files that were wanted but did not fit the budget. */
  public List<Omitted> didNotFit() {
    return omitted.stream().filter(o -> o.why() == Omitted.Why.DOES_NOT_FIT).toList();
  }

  /**
   * True when the context is not the whole of what was meant to be sent: something did not fit, so
   * the model sees only part of the project.
   */
  public boolean isPartial() {
    return !didNotFit().isEmpty();
  }

  public List<String> includedPaths() {
    return included.stream().map(e -> e.file().path()).toList();
  }

  /** One line that says what this context covers, for the preview. */
  public String coverage() {
    int fit = didNotFit().size();
    long byUser = omitted.stream().filter(o -> o.why() == Omitted.Why.LEFT_OUT_BY_USER).count();
    if (mode == ContextMode.SMART)
      return "Smart Context: "
          + included.size()
          + " of "
          + eligibleFiles
          + " files chosen for your request"
          + (fit > 0 ? "; " + fit + " more were chosen but do not fit" : "")
          + ".";
    if (fit > 0)
      return "PARTIAL: "
          + included.size()
          + " of "
          + eligibleFiles
          + " files fit; "
          + fit
          + " did not and the model will not see them.";
    if (byUser > 0)
      return "Full Project without the "
          + byUser
          + " file(s) you left out: "
          + included.size()
          + " of "
          + eligibleFiles
          + " files.";
    return "Full Project: all " + eligibleFiles + " files are included.";
  }
}
