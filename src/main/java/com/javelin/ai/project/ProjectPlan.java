package com.javelin.ai.project;

import com.javelin.ai.AiRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything about one project request, decided before anything is sent: the mode, the files and
 * why, what was left out, the budget, where it goes. {@link #preview()} is the text the user reads
 * before approving; the message in it is the request itself, not a summary.
 */
public record ProjectPlan(
    ContextMode mode,
    String instruction,
    ProjectContext context,
    ProjectIndex index,
    AiRequest request,
    ContextBudget budget,
    String providerName,
    String location,
    boolean local,
    List<String> warnings) {
  public ProjectPlan {
    warnings = List.copyOf(warnings);
  }

  /** The same plan with another request (a repeat that carries the check's problems). */
  public ProjectPlan withRequest(AiRequest other) {
    return new ProjectPlan(
        mode, instruction, context, index, other, budget, providerName, location, local, warnings);
  }

  /** The provider and model for the indicator. */
  public String indicator() {
    return providerName
        + " · "
        + request.model()
        + " · "
        + (local ? "on this computer (" + location + ")" : "remote: " + location);
  }

  /** The files list the user adjusts: every eligible file, in context first. */
  public List<String> allPaths() {
    List<String> out = new ArrayList<>(context.includedPaths());
    for (Omitted o : context.omitted()) out.add(o.file().path());
    return out;
  }

  /** What a file sent in part shows of each of its symbols, and why: every symbol is named. */
  private static void appendSymbols(StringBuilder out, ContextEntry entry) {
    List<String> plain = new ArrayList<>();
    List<String> notShown = new ArrayList<>();
    for (ShownSymbol s : entry.symbols()) {
      if (s.isFull())
        out.append("             in full: ")
            .append(s.name())
            .append(" - ")
            .append(s.why())
            .append('\n');
      else if (ShownSymbol.NOT_SHOWN.equals(s.how())) notShown.add(s.name());
      else if (DEFAULT_WHY.contains(s.why())) plain.add(s.name());
      else
        out.append("             signature only: ")
            .append(s.name())
            .append(" - ")
            .append(s.why())
            .append('\n');
    }
    if (!plain.isEmpty())
      out.append("             signature only: ").append(String.join(", ", plain)).append('\n');
    if (!notShown.isEmpty())
      out.append("             not shown: ").append(String.join(", ", notShown)).append('\n');
  }

  private static final java.util.Set<String> DEFAULT_WHY =
      java.util.Set.of("field", "declaration", "its body is not needed for this request");

  public String preview() {
    StringBuilder out = new StringBuilder();
    out.append("Action:    Project Request (").append(mode.label()).append(")\n");
    out.append("Provider:  ").append(providerName).append('\n');
    out.append("Model:     ").append(request.model()).append('\n');
    out.append("Network:   ")
        .append(
            local
                ? "none: the request goes to " + location + ", on this computer"
                : "SENDS YOUR CODE to " + location + ", which is not this computer")
        .append('\n');
    out.append("Sends:     ")
        .append(context.included().size())
        .append(" project file(s), about ")
        .append(ContextBudget.format(context.textTokens()))
        .append(" tokens of project text\n");
    out.append("Covers:    ").append(context.coverage()).append('\n');
    out.append("Budget:    ").append(context.usage().describe()).append('\n');
    out.append("Snapshot:  ").append(context.snapshot().id()).append('\n');
    out.append("Never sent: ")
        .append(index.excludedTotal())
        .append(" file(s) and folder(s) the scan leaves out");
    Map<ProjectIndex.Reason, Long> byReason = index.excludedByReason();
    if (!byReason.isEmpty()) {
      List<String> parts = new ArrayList<>();
      for (var e : byReason.entrySet()) parts.add(e.getValue() + " " + e.getKey().label());
      out.append(" (").append(String.join("; ", parts)).append(')');
    }
    out.append(".\n");
    for (String w : warnings) out.append("\nWARNING: ").append(w).append('\n');
    for (String w : context.warnings()) out.append("\nWARNING: ").append(w).append('\n');
    out.append("\n----- files that are sent -----\n");
    for (ContextEntry e : context.included()) {
      out.append(
          String.format(
              "%8s tokens  %s  [%s]%s%n",
              ContextBudget.format(e.tokens()),
              e.file().path(),
              e.reason(),
              e.isPartial() ? "  (partial view)" : ""));
      if (e.isPartial()) appendSymbols(out, e);
    }
    for (String note : context.notes()) out.append("\nNote: ").append(note).append('\n');
    List<Omitted> left = context.omitted();
    if (!left.isEmpty()) {
      out.append("\n----- files that are NOT sent -----\n");
      int shown = 0;
      for (Omitted o : left) {
        if (shown++ >= 300) {
          out.append("... and ").append(left.size() - 300).append(" more\n");
          break;
        }
        out.append(o.file().path()).append("  [").append(o.reason()).append("]\n");
      }
    }
    out.append("\n----- system message -----\n").append(request.system());
    out.append("\n\n----- message -----\n").append(request.prompt()).append('\n');
    return out.toString();
  }
}
