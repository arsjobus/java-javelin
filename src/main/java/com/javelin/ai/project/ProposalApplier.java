package com.javelin.ai.project;

import com.javelin.ai.AiException;
import com.javelin.ai.ChangeReply;
import com.javelin.ai.LineDiff;
import com.javelin.ai.ResponseParser;
import com.javelin.io.FileIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks a multi-file proposal against the project as it is now, and applies the files the user
 * approved. Nothing is written until {@link #apply}; before that, {@link #check} says for each file
 * whether the change still fits: unchanged since the model's text was read, changed but still
 * matching by content ({@link Status#REBASED}), or in conflict. A file is never overwritten when
 * the proposal says it is new, a file with unsaved edits in the editor is never touched, and every
 * write is atomic. If a write fails, the files written before it are put back, so a change set is
 * applied whole or not at all.
 */
public final class ProposalApplier {
  private ProposalApplier() {}

  public enum Status {
    /** The file is as it was when the change was worked out. */
    OK(true),
    /** The file changed since, but the change still fits its current text. */
    REBASED(true),
    /** The file changed and the change no longer fits. */
    CONFLICT(false),
    /** The file to change is gone. */
    MISSING(false),
    /** A new file would overwrite one that exists. */
    ALREADY_EXISTS(false),
    /** The file has unsaved edits in an editor tab. */
    UNSAVED(false),
    /** The path is not a file the project may change. */
    REFUSED(false);

    private final boolean applicable;

    Status(boolean applicable) {
      this.applicable = applicable;
    }

    public boolean applicable() {
      return applicable;
    }
  }

  /**
   * What would happen to one file: {@code newText} is what is written when {@link
   * Status#applicable}.
   */
  public record Check(
      FileChange change, Status status, String message, String current, String newText) {
    /** The diff of what applying would really do, against the file as it is now. */
    public LineDiff.Result diff() {
      return LineDiff.unified(current, newText, 3);
    }
  }

  /**
   * @param unsavedPaths project-relative paths with unsaved edits in an open editor tab
   */
  public static List<Check> check(
      ProjectReader reader,
      ProjectProposal proposal,
      Set<String> unsavedPaths,
      ScanOptions options) {
    List<Check> out = new ArrayList<>();
    for (FileChange change : proposal.changes())
      out.add(check(reader, change, unsavedPaths, options));
    return out;
  }

  private static Check check(
      ProjectReader reader, FileChange change, Set<String> unsavedPaths, ScanOptions options) {
    Path file;
    try {
      MultiFileReply.refuseIfNotChangeable(change.path());
      file = reader.resolve(change.path());
    } catch (AiException | IOException ex) {
      return new Check(change, Status.REFUSED, ex.getMessage(), "", "");
    }
    if (unsavedPaths.contains(change.path()))
      return new Check(
          change,
          Status.UNSAVED,
          "has unsaved changes in the editor. Save or close it first, then apply again.",
          "",
          "");
    boolean exists = Files.exists(file);
    if (change.kind() == FileChange.Kind.CREATE) {
      if (exists)
        return new Check(
            change,
            Status.ALREADY_EXISTS,
            "now exists, and a new file never replaces one that does.",
            "",
            "");
      return new Check(change, Status.OK, "new file", "", change.replacement());
    }
    if (!exists)
      return new Check(change, Status.MISSING, "is gone since the proposal was made.", "", "");
    String current;
    try {
      current = reader.readNow(change.path(), options.maxFileBytes());
    } catch (IOException ex) {
      return new Check(change, Status.MISSING, "cannot be read: " + ex.getMessage(), "", "");
    }
    if (ProjectReader.hash(current).equals(ProjectReader.hash(change.original())))
      return new Check(
          change, Status.OK, "unchanged since the proposal", current, change.replacement());
    if (change.format() == ChangeReply.Format.WHOLE_BLOCK)
      return new Check(
          change,
          Status.CONFLICT,
          "changed since the proposal, and a whole-file replacement cannot be moved onto different"
              + " text.",
          current,
          "");
    try {
      ChangeReply.Interpreted again =
          ChangeReply.interpret(
              change.rawSection(), current, change.path(), FileKinds.languageOf(change.path()));
      String text =
          ResponseParser.matchLineEndings(
              current, ResponseParser.matchTrailingNewline(current, again.code()));
      return new Check(
          change,
          Status.REBASED,
          "changed since the proposal, but the change still fits its current text.",
          current,
          text);
    } catch (AiException ex) {
      return new Check(
          change,
          Status.CONFLICT,
          "changed since the proposal and the change no longer fits: " + ex.getMessage(),
          current,
          "");
    }
  }

  /**
   * Writes the approved files that can be applied; the others are left alone. All or nothing.
   *
   * @throws IOException when a write failed; the files written before it were restored
   */
  public static AppliedChanges apply(ProjectReader reader, List<Check> checks, Set<String> approved)
      throws IOException {
    List<AppliedChanges.Entry> done = new ArrayList<>();
    try {
      for (Check c : checks) {
        if (!approved.contains(c.change().path()) || !c.status().applicable()) continue;
        Path file = reader.resolve(c.change().path());
        boolean created = !Files.exists(file);
        List<Path> newFolders = created ? missingParents(reader.root(), file) : List.of();
        String previous = created ? null : FileIO.readText(file, Long.MAX_VALUE);
        FileIO.writeAtomic(file, c.newText());
        done.add(new AppliedChanges.Entry(c.change().path(), previous, c.newText(), newFolders));
      }
    } catch (IOException | RuntimeException ex) {
      AppliedChanges.of(done).rollback(reader);
      throw new IOException(
          "A file could not be written ("
              + ex.getMessage()
              + "). The files written before it were put back; nothing was changed.",
          ex);
    }
    return AppliedChanges.of(done);
  }

  private static List<Path> missingParents(Path root, Path file) {
    List<Path> missing = new ArrayList<>();
    Path parent = file.getParent();
    while (parent != null && !parent.equals(root) && !Files.exists(parent)) {
      missing.add(parent);
      parent = parent.getParent();
    }
    return missing;
  }
}
