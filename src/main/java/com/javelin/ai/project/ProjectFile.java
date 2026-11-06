package com.javelin.ai.project;

/**
 * One file of the open project that may become AI context: where it is (relative to the project,
 * always with {@code /}), how big, when it was last changed, what language and what kind of file.
 * It says nothing about the content: reading is {@link ProjectReader}'s job.
 */
public record ProjectFile(String path, long size, long modified, String language, Kind kind) {
  /** What a file is for, which decides what goes into the context first. */
  public enum Kind {
    DOC("documentation"),
    BUILD("build configuration"),
    SOURCE("source"),
    TEST("test"),
    CONFIG("configuration"),
    OTHER("other");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** The file name without its folders. */
  public String name() {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  /** The folder, without a trailing slash; empty for a file in the project folder. */
  public String directory() {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }
}
