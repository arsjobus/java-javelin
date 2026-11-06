package com.javelin.ai.project;

import java.util.Set;

/**
 * The user's changes to what a selector picked: files to take in whatever the selector thought, and
 * files to leave out. Leaving out wins when a path is in both.
 */
public record Adjustments(Set<String> include, Set<String> exclude) {
  public static final Adjustments NONE = new Adjustments(Set.of(), Set.of());

  public Adjustments {
    include = Set.copyOf(include);
    exclude = Set.copyOf(exclude);
  }

  public boolean isEmpty() {
    return include.isEmpty() && exclude.isEmpty();
  }

  public Adjustments including(String path) {
    var in = new java.util.HashSet<>(include);
    var out = new java.util.HashSet<>(exclude);
    in.add(path);
    out.remove(path);
    return new Adjustments(in, out);
  }

  public Adjustments excluding(String path) {
    var in = new java.util.HashSet<>(include);
    var out = new java.util.HashSet<>(exclude);
    out.add(path);
    in.remove(path);
    return new Adjustments(in, out);
  }
}
