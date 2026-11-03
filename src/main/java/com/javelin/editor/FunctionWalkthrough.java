package com.javelin.editor;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Plans a structural walkthrough of one complete Java method without executing it. */
public final class FunctionWalkthrough {
    private static final String PREFIX = "class __Walkthrough {\n";
    private static final String SUFFIX = "\n}";

    public record Step(int start, int end, String explanation) {}

    private FunctionWalkthrough() {}

    /** Offsets in each step are relative to {@code source}; an unsupported selection returns no steps. */
    public static List<Step> plan(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return List.of();

        String wrapped = PREFIX + source + SUFFIX;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Walkthrough.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return wrapped;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
        try {
            var unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return List.of();
            Tree classTree = unit.getTypeDecls().stream().filter(t -> t.getKind() == Tree.Kind.CLASS).findFirst().orElse(null);
            if (!(classTree instanceof com.sun.source.tree.ClassTree type) || type.getMembers().size() != 1
                || !(type.getMembers().getFirst() instanceof MethodTree method)
                || method.getReturnType() == null || method.getBody() == null) return List.of();

            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            long methodStart = positions.getStartPosition(unit, method);
            long methodEnd = positions.getEndPosition(unit, method);
            int start = toSourceOffset(methodStart);
            int end = toSourceOffset(methodEnd);
            if (start < 0 || end < start || !isTrivia(source, 0, start) || !isTrivia(source, end, source.length())) {
                return List.of();
            }

            Planner planner = new Planner(unit, positions, source);
            planner.addRange(methodStart, positions.getStartPosition(unit, method.getBody()),
                "Method `" + method.getName() + "` starts here. Its parameters provide input; follow the branches below to see how it uses that input.");
            planner.walkBlock(method.getBody());
            return List.copyOf(planner.steps);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not parse the selected Java method", exception);
        }
    }

    private static int toSourceOffset(long wrappedOffset) {
        return (int) (wrappedOffset - PREFIX.length());
    }

    private static boolean isTrivia(String source, int start, int end) {
        for (int index = start; index < end;) {
            if (Character.isWhitespace(source.charAt(index))) {
                index++;
            } else if (index + 1 < end && source.charAt(index) == '/' && source.charAt(index + 1) == '/') {
                int lineEnd = index + 2;
                while (lineEnd < end && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') lineEnd++;
                if (lineEnd < end && source.charAt(lineEnd) == '\r' && lineEnd + 1 < end
                    && source.charAt(lineEnd + 1) == '\n') lineEnd++;
                index = lineEnd < end ? lineEnd + 1 : end;
            } else if (index + 1 < end && source.charAt(index) == '/' && source.charAt(index + 1) == '*') {
                int close = source.indexOf("*/", index + 2);
                if (close < 0 || close + 2 > end) return false;
                index = close + 2;
            } else {
                return false;
            }
        }
        return true;
    }

    private static final class Planner {
        private final com.sun.source.tree.CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String source;
        private final List<Step> steps = new ArrayList<>();

        private Planner(com.sun.source.tree.CompilationUnitTree unit, SourcePositions positions, String source) {
            this.unit = unit;
            this.positions = positions;
            this.source = source;
        }

        private void walkBlock(BlockTree block) {
            for (StatementTree statement : block.getStatements()) walk(statement);
        }

        private void walk(StatementTree statement) {
            if (statement instanceof BlockTree block) {
                walkBlock(block);
            } else if (statement instanceof IfTree branch) {
                walkIf(branch, null);
            } else if (statement instanceof ForLoopTree loop) {
                addHeader(loop, loop.getStatement(),
                    loop.getCondition() == null ? "Repeat this loop until a `break` or `return` leaves it."
                        : "Check `" + text(loop.getCondition()) + "` before each iteration; the body repeats while it is true.");
                walk(loop.getStatement());
            } else if (statement instanceof EnhancedForLoopTree loop) {
                addHeader(loop, loop.getStatement(), "Visit each value from `" + text(loop.getExpression())
                    + "` in turn, assigning it to `" + loop.getVariable().getName() + "`.");
                walk(loop.getStatement());
            } else if (statement instanceof WhileLoopTree loop) {
                addHeader(loop, loop.getStatement(),
                    "Check `" + text(loop.getCondition()) + "` before each iteration; the body repeats while it is true.");
                walk(loop.getStatement());
            } else if (statement instanceof DoWhileLoopTree loop) {
                addHeader(loop, loop.getStatement(), "Enter this loop body once before checking its condition.");
                walk(loop.getStatement());
                add(loop.getCondition(), "After the body, check `" + text(loop.getCondition()) + "`; repeat if it is true.");
            } else if (statement instanceof SwitchTree selection) {
                add(selection.getExpression(), "Choose a `case` by matching this value: `" + text(selection.getExpression()) + "`.");
                for (CaseTree branch : selection.getCases()) walkCase(branch);
            } else if (statement instanceof TryTree attempt) {
                addHeader(attempt, attempt.getBlock(), "Run this block; a handled exception transfers control to a matching `catch`.");
                walk(attempt.getBlock());
                for (CatchTree handler : attempt.getCatches()) {
                    addHeader(handler, handler.getBlock(), "Handle an exception matching `" + text(handler.getParameter()) + "`.");
                    walk(handler.getBlock());
                }
                if (attempt.getFinallyBlock() != null) {
                    addBetween(attempt.getCatches().isEmpty() ? attempt.getBlock() : attempt.getCatches().getLast(),
                        attempt.getFinallyBlock(), "Run this cleanup after the try/catch path, whether it succeeded or failed.");
                    walk(attempt.getFinallyBlock());
                }
            } else if (statement instanceof SynchronizedTree lock) {
                addHeader(lock, lock.getBlock(), "Only one thread at a time can execute this block while holding `" + text(lock.getExpression()) + "`.");
                walk(lock.getBlock());
            } else if (statement instanceof ReturnTree returned) {
                add(returned, returned.getExpression() == null
                    ? "Return from this method now; no later statement in it runs."
                    : "Return `" + text(returned.getExpression()) + "` from this method; no later statement in it runs.");
            } else if (statement instanceof ThrowTree thrown) {
                add(thrown, "Raise `" + text(thrown.getExpression()) + "`; normal execution leaves this path.");
            } else if (statement.getKind() == Tree.Kind.BREAK) {
                add(statement, "Leave the nearest loop or switch now.");
            } else if (statement.getKind() == Tree.Kind.CONTINUE) {
                add(statement, "Skip to the next iteration of the nearest loop.");
            } else if (statement instanceof VariableTree variable) {
                String explanation = variable.getInitializer() == null
                    ? "Declare local `" + variable.getName() + "` here."
                    : "Initialize `" + variable.getName() + "` using `" + text(variable.getInitializer()) + "`.";
                add(statement, explanation);
            } else if (statement instanceof ExpressionStatementTree expression) {
                add(statement, expressionExplanation(expression));
            } else {
                add(statement, "Run this statement, then continue with the next step.");
            }
        }

        private void walkIf(IfTree branch, Tree previousBranch) {
            String explanation = previousBranch == null ? ifExplanation(branch) : elseIfExplanation(branch);
            if (previousBranch == null) {
                add(branch.getCondition(), explanation);
            } else {
                addRange(positions.getEndPosition(unit, previousBranch),
                    positions.getEndPosition(unit, branch.getCondition()), explanation);
            }
            walk(branch.getThenStatement());
            if (branch.getElseStatement() instanceof IfTree next) {
                walkIf(next, branch.getThenStatement());
            } else if (branch.getElseStatement() != null) {
                addBetween(branch.getThenStatement(), branch.getElseStatement(),
                    "The condition above was false, so execution takes this fallback branch.");
                walk(branch.getElseStatement());
            }
        }

        private void walkCase(CaseTree branch) {
            List<? extends StatementTree> statements = branch.getStatements();
            Tree body = branch.getBody();
            long end = statements != null && !statements.isEmpty()
                ? positions.getStartPosition(unit, statements.getFirst())
                : body != null ? positions.getStartPosition(unit, body) : positions.getEndPosition(unit, branch);
            List<? extends CaseLabelTree> labels = branch.getLabels();
            boolean isDefault = labels.stream().anyMatch(label -> label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL);
            String label = isDefault ? "the default branch"
                : "a value matching `" + labels.stream().map(this::text).collect(java.util.stream.Collectors.joining(", ")) + "`";
            addRange(positions.getStartPosition(unit, branch), end, "Continue here when the switch value matches " + label + ".");
            if (statements != null) statements.forEach(this::walk);
            if (body instanceof StatementTree statement) walk(statement);
        }

        private String expressionExplanation(ExpressionStatementTree statement) {
            Tree expression = statement.getExpression();
            if (expression.getKind().name().endsWith("_ASSIGNMENT") || expression.getKind() == Tree.Kind.ASSIGNMENT) {
                return "Update this value using `" + text(expression) + "`; the right-hand expression determines its new value.";
            }
            return "Call or evaluate `" + text(expression) + "` here; inspect its arguments and return value for what changes.";
        }

        private String ifExplanation(IfTree branch) {
            String otherPath = branch.getElseStatement() == null
                ? "otherwise execution continues after this branch."
                : branch.getElseStatement() instanceof IfTree
                    ? "otherwise it checks the next branch."
                    : "otherwise execution takes the fallback branch.";
            return "Check `" + text(branch.getCondition()) + "`. If true, execution enters this branch; " + otherPath;
        }

        private String elseIfExplanation(IfTree branch) {
            String otherPath = branch.getElseStatement() == null
                ? "if false, execution continues after this branch."
                : branch.getElseStatement() instanceof IfTree
                    ? "if false, it checks the next branch."
                    : "if false, execution takes the fallback branch.";
            return "The earlier branch did not match; check `" + text(branch.getCondition())
                + "`. If true, execution enters this branch; " + otherPath;
        }

        private void addHeader(Tree tree, Tree body, String explanation) {
            addRange(positions.getStartPosition(unit, tree), positions.getStartPosition(unit, body), explanation);
        }

        private void addBetween(Tree previous, Tree next, String explanation) {
            addRange(positions.getEndPosition(unit, previous), positions.getStartPosition(unit, next), explanation);
        }

        private void add(Tree tree, String explanation) {
            addRange(positions.getStartPosition(unit, tree), positions.getEndPosition(unit, tree), explanation);
        }

        private void addRange(long start, long end, String explanation) {
            int relativeStart = toSourceOffset(start);
            int relativeEnd = toSourceOffset(end);
            if (relativeStart >= 0 && relativeEnd >= relativeStart && relativeEnd <= source.length()) {
                steps.add(new Step(relativeStart, relativeEnd, explanation));
            }
        }

        private String text(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            int relativeStart = toSourceOffset(start);
            int relativeEnd = toSourceOffset(end);
            return relativeStart >= 0 && relativeEnd >= relativeStart && relativeEnd <= source.length()
                ? source.substring(relativeStart, relativeEnd).strip()
                : tree.toString();
        }
    }
}
