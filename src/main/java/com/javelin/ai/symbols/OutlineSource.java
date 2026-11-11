package com.javelin.ai.symbols;

import com.javelin.ai.SourceOutline;
import java.util.Optional;

/**
 * Where the members of a file come from. The index asks this and never cares how the answer was
 * made: {@link #PATTERNS} reads the text with the editor's own lightweight scanner ({@link
 * SourceOutline}); a source backed by a running language server can be given in its place. An empty
 * answer means "no outline for this file", and the file is then treated as plain text.
 */
@FunctionalInterface
public interface OutlineSource {
  Optional<SourceOutline.Outline> outline(String path, String text);

  /** The lightweight scanner: no server, no parser, the same answer for the same text. */
  OutlineSource PATTERNS = (path, text) -> Optional.of(SourceOutline.of(text));
}
