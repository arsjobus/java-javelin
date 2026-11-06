package com.javelin.ai.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The open project as the AI features see it: its folder, what the last scan found and the reader
 * with its cache. One per open project; {@link #refresh} looks at the folder again without
 * re-reading what did not change. Not thread safe: the window uses it from one worker at a time.
 */
public final class ProjectWorkspace {
  private final ProjectReader reader;
  private ProjectIndex index;
  private ScanOptions options;

  public ProjectWorkspace(Path root, ScanOptions options) throws IOException {
    this.options = options;
    this.reader = new ProjectReader(root);
    this.index = ProjectScanner.scan(reader.root(), options);
  }

  public Path root() {
    return reader.root();
  }

  public ProjectIndex index() {
    return index;
  }

  public ProjectReader reader() {
    return reader;
  }

  public ScanOptions options() {
    return options;
  }

  /** Looks at the folder again, with the given options (a change of options is a new scan). */
  public ProjectIndex refresh(ScanOptions newOptions) throws IOException {
    if (newOptions.equals(options)) index = ProjectScanner.refresh(index, newOptions);
    else {
      index = ProjectScanner.scan(reader.root(), newOptions);
      reader.clear();
    }
    options = newOptions;
    return index;
  }
}
