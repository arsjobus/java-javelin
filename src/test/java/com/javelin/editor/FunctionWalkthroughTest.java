package com.javelin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionWalkthroughTest {
    @Test
    void explainsMultilineAndNestedControlFlowWithoutTreatingCommentsOrStringsAsCode() {
        String source = """
            int choose(String value, int limit) {
                String note = "if (fake) while (false)";
                // for (int ignored = 0; ignored < 1; ignored++) {}
                if (
                    value.isEmpty()
                    || limit < 0
                ) {
                    return -1;
                }
                for (int i = 0; i < limit; i++) {
                    if (value.charAt(i) == 'x') {
                        return i;
                    }
                }
                return 0;
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.size() >= 7);
        assertTrue(steps.getFirst().explanation().contains("Method `choose`"));
        FunctionWalkthrough.Step condition = containing(source, steps, "value.isEmpty()");
        assertTrue(condition.explanation().contains("If true"));
        assertTrue(source.substring(condition.start(), condition.end()).contains("value.isEmpty()"));
        FunctionWalkthrough.Step loop = containing(source, steps, "i < limit");
        assertTrue(loop.explanation().contains("before each iteration"));
        assertEquals(2, steps.stream().filter(step -> step.explanation().contains("If true")).count());
        assertTrue(steps.stream().noneMatch(step -> step.explanation().contains("ignored")));
        FunctionWalkthrough.Step returnStep = containing(source, steps, "return -1;");
        assertTrue(returnStep.explanation().contains("Return `-1`"));
        assertEquals(source.indexOf("return -1;"), returnStep.start());
    }

    @Test
    void explainsElseIfFallbackAndSwitchBranchesInSourceOrder() {
        String source = """
            String label(int value) {
                if (value < 0) {
                    return "negative";
                } else if (value == 0) {
                    return "zero";
                } else if (value == 1) {
                    return "one";
                } else {
                    return "positive";
                }
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.get(1).explanation().contains("If true"));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("earlier branch did not match")
            && step.explanation().contains("value == 0")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("value == 1")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("fallback branch")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Return `\"positive\"`")));

        List<FunctionWalkthrough.Step> noFallback =
            FunctionWalkthrough.plan("int label(int value) { if (value > 0) return 1; else if (value == 0) return 0; return -1; }");
        assertTrue(noFallback.stream().anyMatch(step -> step.explanation().contains("continues after this branch")));
    }

    @Test
    void explainsPlainElseAssignmentsDeclarationsAndVoidReturns() {
        String source = """
            void update(int[] values, int change) {
                int previous;
                previous = values[0];
                values[0] += change;
                if (values.length == 0) return;
                else return;
                assert previous >= 0;
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Declare local `previous`")));
        assertEquals(2, steps.stream().filter(step -> step.explanation().startsWith("Update this value")).count());
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("fallback branch")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Return from this method")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Run this statement")));
    }

    @Test
    void explainsSwitchCasesAndReturnPaths() {
        String source = """
            int sign(int value) {
                switch (value) {
                    case -1:
                        return -1;
                    case 0:
                    case 1:
                        return 1;
                    default:
                        return 0;
                }
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Choose a `case`")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("value matching `-1`")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("default branch")));
        assertEquals(3, steps.stream().filter(step -> step.explanation().startsWith("Return")).count());

        List<FunctionWalkthrough.Step> ruleSteps =
            FunctionWalkthrough.plan("void route(int value) { switch (value) { case 1 -> run(); default -> { run(); } } }");
        assertTrue(ruleSteps.stream().anyMatch(step -> step.explanation().contains("a value matching `1`")));
        assertTrue(ruleSteps.stream().anyMatch(step -> step.explanation().contains("Call or evaluate `run()`")));

        List<FunctionWalkthrough.Step> patternSteps = FunctionWalkthrough.plan(
            "int kind(Object value) { switch (value) { case String text -> { return text.length(); } case null -> { return 0; } default -> { return -1; } } }");
        assertTrue(patternSteps.stream().anyMatch(step -> step.explanation().contains("value matching `String text`")));
        assertTrue(patternSteps.stream().anyMatch(step -> step.explanation().contains("value matching `null`")));
    }

    @Test
    void rejectsPartialNonMethodAndMalformedSelections() {
        assertTrue(FunctionWalkthrough.plan("return value;").isEmpty());
        assertTrue(FunctionWalkthrough.plan("int choose() { return 1; } trailing").isEmpty());
        assertTrue(FunctionWalkthrough.plan("; int choose() { return 1; }").isEmpty());
        assertTrue(FunctionWalkthrough.plan("int choose() { return 1; } int other() { return 2; }").isEmpty());
        assertTrue(FunctionWalkthrough.plan("abstract int choose();").isEmpty());
        assertTrue(FunctionWalkthrough.plan("int choose() { if (true) {").isEmpty());
        assertTrue(FunctionWalkthrough.plan("class Example { int choose() { return 1; } }").isEmpty());
        assertFalse(FunctionWalkthrough.plan("/** docs */ int choose() { return 1; } // trailing").isEmpty());
        assertFalse(FunctionWalkthrough.plan("// docs\rint choose() { return 1; }").isEmpty());
    }

    @Test
    void explainsLoopExitAndExceptionSteps() {
        String source = """
            int firstPositive(int[] values) {
                try {
                    for (int value : values) {
                        if (value > 0) {
                            return value;
                        }
                        continue;
                    }
                    return 0;
                } catch (RuntimeException error) {
                    throw error;
                } finally {
                    cleanup();
                }
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Visit each value")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Skip to the next iteration")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("matching `RuntimeException error`")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Raise `error`")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Run this cleanup")));
        assertFalse(steps.isEmpty());
    }

    @Test
    void walksTryFinallyWithoutCatch() {
        List<FunctionWalkthrough.Step> steps =
            FunctionWalkthrough.plan("void run() { try { work(); } finally { cleanup(); } }");

        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Run this cleanup")));
    }

    @Test
    void explainsDoWhileInfiniteLoopsBreaksAndSynchronizedBlocks() {
        String source = """
            int count(int[] values) {
                int index = 0;
                do {
                    if (index >= values.length) break;
                    index++;
                } while (index < values.length);
                while (index > 0) {
                    index--;
                }
                for (;;) {
                    break;
                }
                synchronized (values) {
                    notifyChange();
                }
                return index;
            }
            """;

        List<FunctionWalkthrough.Step> steps = FunctionWalkthrough.plan(source);

        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Enter this loop body once")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("After the body")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Repeat this loop until")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("index > 0")
            && step.explanation().contains("before each iteration")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Leave the nearest loop")));
        assertTrue(steps.stream().anyMatch(step -> step.explanation().contains("Only one thread at a time")));
    }

    private static FunctionWalkthrough.Step containing(String source, List<FunctionWalkthrough.Step> steps, String snippet) {
        int offset = source.indexOf(snippet);
        return steps.stream()
            .filter(step -> step.start() >= 0 && step.end() >= step.start())
            .filter(step -> step.start() <= offset && step.end() > offset)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No walkthrough step for " + snippet));
    }
}
