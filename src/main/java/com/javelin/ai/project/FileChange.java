package com.javelin.ai.project;

import com.javelin.ai.ChangeReply;
import com.javelin.ai.Finding;
import com.javelin.ai.LineDiff;
import java.util.List;

/**
 * One file of a multi-file proposal. {@code original} is the text the change was worked out against
 * ("" for a new file) and {@code replacement} what the file becomes. {@code rawSection} is the
 * model's text for this file, kept so the change can be re-applied by content if the file has
 * changed since ({@code format} says how it was written; a whole-file replacement cannot be moved
 * onto different text). {@code inContext} says whether the model was shown this file.
 */
public record FileChange(
    String path,
    Kind kind,
    String original,
    String replacement,
    String rawSection,
    ChangeReply.Format format,
    boolean inContext,
    List<Finding> findings) {
  public enum Kind {
    MODIFY,
    CREATE
  }

  public FileChange {
    findings = List.copyOf(findings);
  }

  public LineDiff.Result diff() {
    return LineDiff.unified(original, replacement, 3);
  }

  public boolean hasErrors() {
    return findings.stream().anyMatch(Finding::isError);
  }

  public String summary() {
    LineDiff.Result diff = diff();
    return (kind == Kind.CREATE ? "new " : "")
        + path
        + ": +"
        + diff.added()
        + " -"
        + diff.removed();
  }
}
