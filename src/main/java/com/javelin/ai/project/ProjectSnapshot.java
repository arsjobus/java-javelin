package com.javelin.ai.project;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the project looked like when a context was built: the size and time of every eligible file,
 * and the fingerprint of the text of each file that was actually sent. An answer is tied to the
 * snapshot it was given, so the window can say "the project has changed since this answer" and a
 * proposal is never applied to a file that is no longer what the model saw. Same inputs give the
 * same {@code id}; the time is information only. {@code partialPaths} names the files of which only
 * some symbols were sent (symbol-level context): their hash in {@code sentHashes} is still that of
 * the whole file, so a change to any part of them is noticed, but a reply may not rewrite one as a
 * whole, because the model never saw all of it.
 */
public record ProjectSnapshot(
    String id,
    long takenAtMillis,
    Map<String, Stamp> stamps,
    Map<String, String> sentHashes,
    Set<String> partialPaths) {
  /** A file's size and last-modified time. */
  public record Stamp(long size, long modified) {}

  public ProjectSnapshot {
    stamps = java.util.Collections.unmodifiableMap(new TreeMap<>(stamps));
    sentHashes = java.util.Collections.unmodifiableMap(new TreeMap<>(sentHashes));
    partialPaths = java.util.Collections.unmodifiableSet(new TreeSet<>(partialPaths));
  }

  /** A snapshot in which every sent file was sent whole. */
  public ProjectSnapshot(
      String id, long takenAtMillis, Map<String, Stamp> stamps, Map<String, String> sentHashes) {
    this(id, takenAtMillis, stamps, sentHashes, Set.of());
  }

  /** The files that were sent, in path order. */
  public List<String> sentPaths() {
    return List.copyOf(sentHashes.keySet());
  }

  /** What changed in the project since this snapshot. */
  public record Changes(List<String> added, List<String> modified, List<String> deleted) {
    public boolean isEmpty() {
      return added.isEmpty() && modified.isEmpty() && deleted.isEmpty();
    }

    /** One line for the window: "2 modified, 1 added". Empty text when nothing changed. */
    public String describe() {
      List<String> parts = new java.util.ArrayList<>();
      if (!modified.isEmpty()) parts.add(modified.size() + " modified");
      if (!added.isEmpty()) parts.add(added.size() + " added");
      if (!deleted.isEmpty()) parts.add(deleted.size() + " deleted");
      return String.join(", ", parts);
    }

    /** True when something this snapshot actually sent was modified or deleted. */
    public boolean touches(ProjectSnapshot snapshot) {
      return modified.stream().anyMatch(snapshot.sentHashes()::containsKey)
          || deleted.stream().anyMatch(snapshot.sentHashes()::containsKey);
    }
  }

  /**
   * What differs between this snapshot and the project as {@code current} lists it. Files are
   * compared by size and time. That cannot see an edit that keeps both, which is why the files that
   * were sent are also checked by content ({@link ProjectRefresh}).
   */
  public Changes changesSince(ProjectIndex current) {
    List<String> added = new java.util.ArrayList<>();
    List<String> modified = new java.util.ArrayList<>();
    Map<String, Stamp> now = new TreeMap<>();
    for (ProjectFile f : current.files()) now.put(f.path(), new Stamp(f.size(), f.modified()));
    for (Map.Entry<String, Stamp> e : now.entrySet()) {
      Stamp before = stamps.get(e.getKey());
      if (before == null) added.add(e.getKey());
      else if (!before.equals(e.getValue())) modified.add(e.getKey());
    }
    List<String> deleted = new java.util.ArrayList<>();
    for (String path : stamps.keySet()) if (!now.containsKey(path)) deleted.add(path);
    return new Changes(added, modified, deleted);
  }
}
