package com.javelin.ai.plan;

import com.javelin.ai.AiException;
import com.javelin.ai.FailureReason;
import com.javelin.ai.MethodFinder;
import com.javelin.ai.project.ContextBudget;
import com.javelin.ai.project.ProjectFile;
import com.javelin.ai.project.ProjectIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything one step of a plan sends to the model, and nothing else: the method it changes (or the
 * place a new method goes), the file's package and imports, the signatures of the file's other
 * members, and the signatures of the project types the method mentions. No other file body is sent.
 * Built from the file as the earlier steps left it ({@code overlay}), so a step sees the work
 * already done. The same object makes the prompt and the preview in the Send dialog, which is how
 * the preview and what is sent cannot differ.
 */
record StepContext(
    int number,
    int total,
    PlanStep step,
    boolean newFile,
    String fileText,
    String header,
    String body,
    String bodyLabel,
    List<String> ownSignatures,
    Map<String, List<String>> dependencies) {
  /**
   * The most characters of method (or whole file, for a language without methods) one step sends.
   */
  static final int MAX_BODY_CHARS = 8_000;

  static final int MAX_DEPENDENCIES = 6, MAX_SIGNATURES_PER_FILE = 40, MAX_HEADER_LINES = 40;

  private static final Pattern HEADER_LINE = Pattern.compile("^\\s*(?:package|import)\\s.*;\\s*$");
  private static final Pattern TYPE_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b");

  /**
   * @param texts the current text of a file on disk, for files that are not in {@code overlay}
   * @param overlay the text of files that earlier steps changed or created
   * @throws AiException REFUSED with ANCHOR_NOT_FOUND when the method is not in the file as it is
   *     now, WRONG_FILE when the file cannot be read, OVERSIZED when the body is too large to send
   */
  static StepContext build(
      TaskPlan plan,
      int index,
      ProjectIndex project,
      PlanValidator.TextSource texts,
      Map<String, String> overlay)
      throws AiException {
    PlanStep step = plan.steps().get(index);
    String path = step.file();
    String text = overlay.get(path);
    boolean inProject = project.find(path) != null;
    if (text == null && inProject) {
      try {
        text = texts.read(path);
      } catch (IOException ex) {
        throw refused(FailureReason.WRONG_FILE, path + " cannot be read: " + ex.getMessage());
      }
    }
    boolean newFile = text == null;
    if (newFile && !step.isNew())
      throw refused(FailureReason.WRONG_FILE, path + " does not exist at this point of the plan.");
    String body = "";
    String bodyLabel = "";
    List<String> own = List.of();
    String header = "";
    if (!newFile) {
      header = headerOf(text);
      own = limit(MethodFinder.signatures(text), MAX_SIGNATURES_PER_FILE * 2);
      if (!step.isNew()) {
        List<MethodFinder.Found> found = MethodFinder.find(text, step.symbol());
        if (found.stream().anyMatch(MethodFinder.Found::hasBody)) {
          List<String> parts = new ArrayList<>();
          for (var f : found) parts.add(f.text());
          body = String.join("\n\n", parts);
          bodyLabel = "the method to change: " + step.symbol() + " in " + path;
        } else if (MethodFinder.declares(text, step.symbol())) {
          body = text;
          bodyLabel = "the file to change (" + step.symbol() + " is in it): " + path;
        } else {
          throw refused(
              FailureReason.ANCHOR_NOT_FOUND,
              path + " has no method '" + step.symbol() + "' at this point of the plan.");
        }
        if (body.length() > MAX_BODY_CHARS)
          throw refused(
              FailureReason.OVERSIZED,
              step.label()
                  + " is "
                  + body.length()
                  + " characters, more than a step sends ("
                  + MAX_BODY_CHARS
                  + "). Split the work differently.");
      }
    }
    String mentioned = body + " " + step.task() + " " + step.symbol();
    Map<String, List<String>> dependencies =
        dependenciesOf(path, mentioned, project, texts, overlay);
    return new StepContext(
        index + 1,
        plan.steps().size(),
        step,
        newFile,
        newFile ? "" : text,
        header,
        body,
        bodyLabel,
        own,
        dependencies);
  }

  private static Map<String, List<String>> dependenciesOf(
      String target,
      String mentioned,
      ProjectIndex project,
      PlanValidator.TextSource texts,
      Map<String, String> overlay) {
    Map<String, String> byType = new LinkedHashMap<>();
    for (ProjectFile f : project.files())
      if (f.path().endsWith(".java") && !f.path().equals(target))
        byType.put(baseName(f.path()), f.path());
    for (String p : overlay.keySet())
      if (p.endsWith(".java") && !p.equals(target)) byType.put(baseName(p), p);
    Set<String> paths = new LinkedHashSet<>();
    Matcher m = TYPE_NAME.matcher(mentioned);
    while (m.find() && paths.size() < MAX_DEPENDENCIES) {
      String path = byType.get(m.group());
      if (path != null) paths.add(path);
    }
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String path : paths) {
      String text = overlay.get(path);
      if (text == null) {
        try {
          text = texts.read(path);
        } catch (IOException ex) {
          continue; // a file that cannot be read is simply not shown
        }
      }
      List<String> signatures = limit(MethodFinder.signatures(text), MAX_SIGNATURES_PER_FILE);
      if (!signatures.isEmpty()) out.put(path, signatures);
    }
    return out;
  }

  private static String headerOf(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.lines().toList())
      if (HEADER_LINE.matcher(line).matches() && lines.size() < MAX_HEADER_LINES)
        lines.add(line.strip());
    return String.join("\n", lines);
  }

  private static List<String> limit(List<String> lines, int most) {
    return lines.size() <= most ? lines : lines.subList(0, most);
  }

  private static String baseName(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private static AiException refused(FailureReason reason, String message) {
    return new AiException(AiException.Kind.REFUSED, reason, message);
  }

  /** Every text this context puts in a prompt (for the fence to avoid). */
  List<String> texts() {
    List<String> out = new ArrayList<>();
    out.add(header);
    out.add(body);
    out.addAll(ownSignatures);
    for (var e : dependencies.entrySet()) out.addAll(e.getValue());
    return out;
  }

  /** The tokens of what this step sends, roughly. */
  int tokens() {
    int n = ContextBudget.estimateTokens(header) + ContextBudget.estimateTokens(body);
    n += ContextBudget.estimateTokens(String.join("\n", ownSignatures));
    for (var e : dependencies.entrySet())
      n += ContextBudget.estimateTokens(String.join("\n", e.getValue()));
    return n;
  }

  /** One short line for the Send dialog saying what the step sends. */
  String summary() {
    List<String> parts = new ArrayList<>();
    if (newFile) parts.add("no file body (it creates " + step.file() + ")");
    else if (step.isNew()) parts.add("the signatures of " + step.file() + " (a method is added)");
    else
      parts.add(
          (body.length() > 0 && bodyLabel.startsWith("the method")
                  ? "the method " + step.symbol() + " of "
                  : "the whole file ")
              + step.file());
    if (!header.isEmpty()) parts.add("its package and imports");
    if (!dependencies.isEmpty())
      parts.add("signatures of " + String.join(", ", dependencies.keySet()));
    return String.join("; ", parts) + " (about " + ContextBudget.format(tokens()) + " tokens)";
  }
}
