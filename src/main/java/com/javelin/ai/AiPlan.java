package com.javelin.ai;

import java.util.List;

/**
 * Everything about one request, decided before anything is sent: what is sent, to which provider
 * and model, and what to be careful about ({@code testFileName} is the name suggested for generated
 * tests, otherwise null). {@link #preview()} is the text the user reads before approving; it is the
 * request itself, not a summary of it.
 */
public record AiPlan(
    AiAction action,
    AiContext context,
    AiRequest request,
    String providerName,
    String location,
    boolean local,
    String testFileName,
    List<String> warnings) {
  /** The same plan with another request (a repeat that carries the check's problems). */
  public AiPlan withRequest(AiRequest other) {
    return new AiPlan(
        action, context, other, providerName, location, local, testFileName, warnings);
  }

  /** The provider and model for the indicator ("Ollama, llama3, on this computer"). */
  public String indicator() {
    return providerName
        + " · "
        + request.model()
        + " · "
        + (local ? "on this computer (" + location + ")" : "remote: " + location);
  }

  public String preview() {
    StringBuilder out = new StringBuilder();
    out.append("Action:    ").append(action.label()).append('\n');
    out.append("Provider:  ").append(providerName).append('\n');
    out.append("Model:     ").append(request.model()).append('\n');
    out.append("Network:   ")
        .append(
            local
                ? "none: the request goes to " + location + ", on this computer"
                : "SENDS YOUR CODE to " + location + ", which is not this computer")
        .append('\n');
    out.append("Sends:     ")
        .append(context.scope().label())
        .append(" of ")
        .append(context.name())
        .append(", ")
        .append(context.text().length())
        .append(" characters");
    if (context.truncated())
      out.append(" (cut from ").append(context.totalChars()).append(" to fit the limit)");
    out.append('\n');
    out.append("Nothing else is sent: no other files, no project settings, no history.\n");
    for (String warning : warnings) out.append("\nWARNING: ").append(warning).append('\n');
    out.append("\n----- system message -----\n").append(request.system());
    out.append("\n\n----- message -----\n").append(request.prompt()).append('\n');
    return out.toString();
  }
}
