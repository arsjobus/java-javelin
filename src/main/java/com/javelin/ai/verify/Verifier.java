package com.javelin.ai.verify;

import com.javelin.ai.AiCancel;
import java.util.Map;

/**
 * Checks a proposed change without writing it: {@code changed} maps project-relative paths (forward
 * slashes) to the text each file would have. Implementations must leave the project untouched.
 */
@FunctionalInterface
public interface Verifier {
  Verification verify(Map<String, String> changed, AiCancel cancel);
}
