package com.javelin.ai.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a fixture project and runs its tests, the way the eval needs to know "does the changed
 * code work". The compiler runs in this JVM (it only reads and writes files). The tests run in a
 * child JVM that is killed after a time limit, because the code under test may have been written by
 * a model: it can loop forever or call {@code System.exit}, and neither may take the eval down.
 *
 * <p>The tests are JUnit 5 sources, so they run under Maven too, but they are run here by a 40-line
 * runner instead of the JUnit launcher: no new dependency, and fixtures stay plain {@code @Test}
 * methods with {@code Assertions}.
 */
final class FixtureBuild {
  private FixtureBuild() {}

  /** What compiling a project gave. */
  record Compiled(boolean ok, List<String> errors, Path classes) {}

  /**
   * What running tests gave. {@code failed} maps {@code Class#method} to the first line of the
   * failure. {@code crash} is set when the runner itself broke (a class that does not load).
   */
  record TestRun(
      List<String> passed,
      Map<String, String> failed,
      boolean timedOut,
      boolean noTests,
      String crash) {
    boolean ok() {
      return failed.isEmpty() && !timedOut && !noTests && crash == null && !passed.isEmpty();
    }

    /** One line saying what went wrong, or "" when {@link #ok}. */
    String describe() {
      if (ok()) return "";
      if (timedOut) return "the tests did not finish in time";
      if (crash != null) return crash;
      if (noTests) return "no test method was found";
      if (failed.isEmpty()) return "no test ran";
      Map.Entry<String, String> first = failed.entrySet().iterator().next();
      return failed.size() + " test(s) failed, first " + first.getKey() + ": " + first.getValue();
    }
  }

  static final long TEST_TIMEOUT_SECONDS = 30;

  /**
   * The runner's lines are told from anything the code under test prints by a token that is new for
   * every run and given to the child process only; code written by a model cannot know it.
   */
  private static String newMarker() {
    return "@@EVAL-" + java.util.UUID.randomUUID() + "@@";
  }

  private static final String RUNNER =
      """
      package evalrunner;

      import java.lang.reflect.*;
      import java.util.*;

      public final class MiniRunner {
        @SuppressWarnings("unchecked")
        private static Class<? extends java.lang.annotation.Annotation> ann(String name) {
          try {
            return (Class<? extends java.lang.annotation.Annotation>) Class.forName(name);
          } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
          }
        }

        private static String oneLine(Throwable t) {
          String m = String.valueOf(t.getMessage()).replaceAll("\\\\s+", " ").strip();
          String s = t.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + m);
          return s.length() > 300 ? s.substring(0, 300) : s;
        }

        public static void main(String[] args) {
          final String mark = System.getProperty("eval.marker");
          var test = ann("org.junit.jupiter.api.Test");
          var before = ann("org.junit.jupiter.api.BeforeEach");
          var after = ann("org.junit.jupiter.api.AfterEach");
          var disabled = ann("org.junit.jupiter.api.Disabled");
          for (String name : args) {
            Class<?> c;
            try {
              c = Class.forName(name);
            } catch (Throwable t) {
              System.out.println(mark + "\\tCRASH\\t" + name + "\\t" + oneLine(t));
              continue;
            }
            List<Method> methods = new ArrayList<>();
            for (Method m : c.getDeclaredMethods())
              if (m.isAnnotationPresent(test) && !m.isAnnotationPresent(disabled)) methods.add(m);
            methods.sort(Comparator.comparing(Method::getName));
            if (methods.isEmpty()) System.out.println(mark + "\\tNOTESTS\\t" + name);
            for (Method m : methods) {
              String id = name + "#" + m.getName();
              try {
                Constructor<?> k = c.getDeclaredConstructor();
                k.setAccessible(true);
                Object o = k.newInstance();
                for (Method b : c.getDeclaredMethods())
                  if (b.isAnnotationPresent(before)) { b.setAccessible(true); b.invoke(o); }
                try {
                  m.setAccessible(true);
                  m.invoke(o);
                } finally {
                  for (Method a : c.getDeclaredMethods())
                    if (a.isAnnotationPresent(after)) { a.setAccessible(true); a.invoke(o); }
                }
                System.out.println(mark + "\\tPASS\\t" + id);
              } catch (InvocationTargetException e) {
                System.out.println(mark + "\\tFAIL\\t" + id + "\\t" + oneLine(e.getCause()));
              } catch (Throwable t) {
                System.out.println(mark + "\\tFAIL\\t" + id + "\\t" + oneLine(t));
              }
            }
          }
          System.out.flush();
          System.exit(0);
        }
      }
      """;

  /**
   * The JUnit jars that are on this JVM's class path, which the fixtures compile and run against.
   */
  static List<Path> libraries() {
    List<Path> jars = new ArrayList<>();
    for (String name :
        List.of(
            "org.junit.jupiter.api.Test",
            "org.opentest4j.AssertionFailedError",
            "org.junit.platform.commons.JUnitException",
            "org.apiguardian.api.API")) {
      try {
        Path p =
            Path.of(
                Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!jars.contains(p)) jars.add(p);
      } catch (Exception ex) {
        // optional (apiguardian is only an annotation): javac warns, nothing more
      }
    }
    return jars;
  }

  private static String classPath(List<Path> extra) {
    return Stream.concat(libraries().stream(), extra.stream())
        .map(Path::toString)
        .collect(Collectors.joining(java.io.File.pathSeparator));
  }

  /** Compiles every {@code .java} under {@code project}'s src/main/java and src/test/java. */
  static Compiled compile(Path project, Path out) throws IOException {
    List<Path> sources = new ArrayList<>();
    for (String dir : List.of("src/main/java", "src/test/java")) {
      Path root = project.resolve(dir);
      if (!Files.isDirectory(root)) continue;
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
      }
    }
    Path runnerSource = out.resolve("runner-src/evalrunner/MiniRunner.java");
    Files.createDirectories(runnerSource.getParent());
    Files.writeString(runnerSource, RUNNER, StandardCharsets.UTF_8);
    sources.add(runnerSource);
    Path classes = out.resolve("classes");
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IOException("this JVM has no Java compiler (run on a JDK)");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of(
                      "-proc:none",
                      "-Xlint:none",
                      "-d",
                      classes.toString(),
                      "-cp",
                      classPath(List.of())),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      List<String> errors = new ArrayList<>();
      for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
        if (d.getKind() == Diagnostic.Kind.ERROR)
          errors.add(
              (d.getSource() == null
                      ? ""
                      : Path.of(d.getSource().getName()).getFileName()
                          + ":"
                          + d.getLineNumber()
                          + ": ")
                  + d.getMessage(Locale.ROOT).replaceAll("\\s+", " ").strip());
      return new Compiled(ok, errors, classes);
    }
  }

  /** Runs the {@code @Test} methods of the named classes in a child JVM. */
  static TestRun run(Path classes, List<String> classNames) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    String mark = newMarker();
    command.addAll(
        List.of(
            "-Xmx256m", "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-Deval.marker=" + mark));
    command.add("-cp");
    command.add(classPath(List.of(classes)));
    command.add("evalrunner.MiniRunner");
    command.addAll(classNames);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    List<String> lines = new ArrayList<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null)
                  if (line.startsWith(mark))
                    synchronized (lines) {
                      lines.add(line);
                    }
              } catch (IOException ex) {
                // the process was killed
              }
            });
    reader.setDaemon(true);
    reader.start();
    boolean finished;
    try {
      finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while the tests ran", ex);
    }
    if (!finished) process.destroyForcibly();
    try {
      reader.join(2000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    List<String> passed = new ArrayList<>();
    Map<String, String> failed = new LinkedHashMap<>();
    boolean noTests = false;
    String crash = null;
    synchronized (lines) {
      for (String line : lines) {
        String[] f = line.split("\t", 4);
        switch (f.length > 1 ? f[1] : "") {
          case "PASS" -> passed.add(f[2]);
          case "FAIL" -> failed.put(f[2], f.length > 3 ? f[3] : "");
          case "NOTESTS" -> noTests = true;
          case "CRASH" -> crash = f[2] + " could not be loaded: " + (f.length > 3 ? f[3] : "");
          default -> {}
        }
      }
    }
    return new TestRun(passed, failed, !finished, noTests, crash);
  }
}
