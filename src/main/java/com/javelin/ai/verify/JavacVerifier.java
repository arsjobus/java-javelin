package com.javelin.ai.verify;

import com.javelin.ai.AiCancel;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles a proposed change against the project in memory with the JDK's compiler, and when asked
 * runs the tests that use the changed classes. Nothing is written to the project: changed files are
 * given to javac as text, the rest of the project is read from disk through the source path, and
 * the compiler's output stays in memory. Only when tests run do class files go to a temporary
 * folder outside the project (the child JVM needs files), which is deleted afterwards.
 *
 * <p>Annotation processors are off ({@code -proc:none}), so compiling runs none of the project's
 * code. Tests do run code, in a child JVM with a time limit, and only when the user turned {@code
 * ai.verify.tests} on. Errors that the project already had before the change are not blamed on it.
 * JavaFX-free.
 */
public final class JavacVerifier implements Verifier {
  /** The most problems kept, so a retry message and a dialog stay short. */
  static final int MAX_PROBLEMS = 12;

  private static final int MAX_TESTS = 20;
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final Path root;
  private final ProjectClasspath.Resolved classpath;
  private final VerifyConfig config;
  private final JavaCompiler compiler;

  public JavacVerifier(Path root, ProjectClasspath.Resolved classpath, VerifyConfig config) {
    this(root, classpath, config, ToolProvider.getSystemJavaCompiler());
  }

  /** For tests: {@code compiler} is null on a JRE without one. */
  JavacVerifier(
      Path root, ProjectClasspath.Resolved classpath, VerifyConfig config, JavaCompiler compiler) {
    this.compiler = compiler;
    this.root = root.toAbsolutePath().normalize();
    this.classpath = classpath;
    this.config = config;
  }

  @Override
  public Verification verify(Map<String, String> changed, AiCancel cancel) {
    long started = System.nanoTime();
    try {
      Map<String, String> java = new LinkedHashMap<>();
      for (var e : changed.entrySet())
        if (e.getKey().endsWith(".java")) java.put(e.getKey(), e.getValue());
      if (java.isEmpty()) return Verification.notChecked("no Java file was changed");
      if (compiler == null)
        return Verification.notChecked("this Java has no compiler (run Javelin on a JDK)");
      if (!classpath.complete()) return Verification.notChecked(classpath.note());
      List<String> dependents = dependents(java.keySet());
      Compiled first = compile(java, dependents, null);
      if (!first.ok()) {
        List<VerifyProblem> blamed = subtractBaseline(first.errors(), java, dependents);
        if (!blamed.isEmpty())
          return new Verification(
              Verification.Outcome.COMPILE_FAILED,
              blamed.subList(0, Math.min(MAX_PROBLEMS, blamed.size())),
              0,
              config.runTests(),
              millis(started),
              "");
      }
      if (!config.runTests()) return Verification.passed(0, false, millis(started), "");
      if (cancel != null && cancel.isCancelled())
        return Verification.notChecked("cancelled before the tests ran");
      return runTests(java, dependents, started, cancel);
    } catch (IOException | RuntimeException ex) {
      return Verification.notChecked("the check could not run: " + ex.getMessage());
    }
  }

  private static long millis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  // ---- compiling ----

  private record Compiled(boolean ok, List<VerifyProblem> errors, Map<String, byte[]> classes) {}

  private List<Path> sourceRoots() {
    List<Path> roots = new ArrayList<>();
    for (String dir : List.of("src/main/java", "src/test/java")) {
      Path p = root.resolve(dir);
      if (Files.isDirectory(p)) roots.add(p);
    }
    if (roots.isEmpty()) roots.add(root);
    return roots;
  }

  /** In-memory source text. */
  private static final class MemSource extends SimpleJavaFileObject {
    private final String text;

    MemSource(String path, String text) {
      super(URI.create("mem:///" + path.replace(" ", "%20")), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  private static final class MemOutput extends SimpleJavaFileObject {
    private final ByteArrayOutputStream bytes;

    MemOutput(String className, Kind kind, ByteArrayOutputStream bytes) {
      super(URI.create("mem:///out/" + className.replace('.', '/') + kind.extension), kind);
      this.bytes = bytes;
    }

    @Override
    public OutputStream openOutputStream() {
      return bytes;
    }
  }

  private static final class MemFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
    final Map<String, ByteArrayOutputStream> out = new LinkedHashMap<>();

    MemFiles(StandardJavaFileManager delegate) {
      super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        JavaFileManager.Location location,
        String className,
        JavaFileObject.Kind kind,
        FileObject sibling) {
      return new MemOutput(
          className, kind, out.computeIfAbsent(className, k -> new ByteArrayOutputStream()));
    }
  }

  /**
   * Compiles {@code overlay} (path to text) plus the project files named in {@code diskUnits}, and
   * {@code runnerSource} when not null. Everything else the sources use comes from the source path.
   */
  private Compiled compile(Map<String, String> overlay, List<String> diskUnits, String runnerSource)
      throws IOException {
    List<JavaFileObject> units = new ArrayList<>();
    for (var e : overlay.entrySet()) units.add(new MemSource(e.getKey(), e.getValue()));
    for (String path : diskUnits)
      if (!overlay.containsKey(path))
        units.add(
            new MemSource(path, Files.readString(root.resolve(path), StandardCharsets.UTF_8)));
    if (runnerSource != null)
      units.add(new MemSource("javelinverify/MiniRunner.java", runnerSource));
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager standard =
            compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        MemFiles files = new MemFiles(standard)) {
      standard.setLocation(
          StandardLocation.SOURCE_PATH, sourceRoots().stream().map(Path::toFile).toList());
      standard.setLocation(
          StandardLocation.CLASS_PATH, classpath.entries().stream().map(Path::toFile).toList());
      List<String> options = List.of("-proc:none", "-Xlint:none", "-encoding", "UTF-8");
      boolean ok = compiler.getTask(null, files, diagnostics, options, null, units).call();
      List<VerifyProblem> errors = new ArrayList<>();
      for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
        if (d.getKind() == Diagnostic.Kind.ERROR)
          errors.add(
              new VerifyProblem(
                  pathOf(d.getSource()),
                  (int) Math.max(0, d.getLineNumber()),
                  d.getMessage(Locale.ROOT).replaceAll("\\s+", " ").strip()));
      Map<String, byte[]> classes = new LinkedHashMap<>();
      for (var e : files.out.entrySet()) classes.put(e.getKey(), e.getValue().toByteArray());
      return new Compiled(ok && errors.isEmpty(), errors, classes);
    }
  }

  private String pathOf(JavaFileObject source) {
    if (source == null) return "";
    URI uri = source.toUri();
    if ("mem".equals(uri.getScheme())) return uri.getPath().substring(1).replace("%20", " ");
    try {
      Path realRoot = root.toRealPath();
      Path realSource = Path.of(uri).toRealPath();
      if (realSource.startsWith(realRoot))
        return realRoot.relativize(realSource).toString().replace('\\', '/');
      return source.getName();
    } catch (IOException | RuntimeException ex) {
      return source.getName();
    }
  }

  /**
   * The project files that mention a changed class by its simple name: the callers a changed
   * signature can break. javac only compiles what the given files reach, so without these a broken
   * caller that nothing in the change refers to would go unseen. At most {@link #MAX_DEPENDENTS}.
   */
  List<String> dependents(Set<String> changedPaths) throws IOException {
    Set<String> names = new HashSet<>();
    for (String path : changedPaths) names.add(Pattern.quote(baseName(path)));
    Pattern uses = Pattern.compile("\\b(" + String.join("|", names) + ")\\b");
    Set<String> found = new java.util.TreeSet<>();
    for (Path sourceRoot : sourceRoots()) {
      try (Stream<Path> walk = Files.walk(sourceRoot)) {
        for (Path file :
            (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
          String path =
              root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
          if (changedPaths.contains(path) || found.size() >= MAX_DEPENDENTS) continue;
          if (uses.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) found.add(path);
        }
      }
    }
    return List.copyOf(found);
  }

  static final int MAX_DEPENDENTS = 300;

  /**
   * Errors the project already had without the change are not the model's: compile the files as
   * they are on disk (the changed files as they were, the same dependents, and any other file an
   * error was found in) and drop errors with the same file and message (line numbers move).
   */
  private List<VerifyProblem> subtractBaseline(
      List<VerifyProblem> errors, Map<String, String> java, List<String> units) throws IOException {
    Map<String, String> before = new LinkedHashMap<>();
    for (String path : java.keySet()) {
      Path file = root.resolve(path);
      if (Files.isRegularFile(file))
        before.put(path, Files.readString(file, StandardCharsets.UTF_8));
    }
    List<String> disk = new ArrayList<>(units);
    for (VerifyProblem p : errors)
      if (!p.path().isEmpty()
          && !java.containsKey(p.path())
          && !disk.contains(p.path())
          && Files.isRegularFile(root.resolve(p.path()))) disk.add(p.path());
    Set<String> known = new HashSet<>();
    for (VerifyProblem p : compile(before, disk, null).errors())
      known.add(p.path() + "|" + p.message());
    List<VerifyProblem> blamed = new ArrayList<>();
    for (VerifyProblem p : errors) if (!known.contains(p.path() + "|" + p.message())) blamed.add(p);
    return blamed;
  }

  // ---- tests ----

  /**
   * The test classes to run: changed tests, and tests under src/test/java whose text uses the
   * simple name of a changed class. At most {@link #MAX_TESTS}.
   */
  List<String> selectTests(Map<String, String> java) throws IOException {
    Path testRoot = root.resolve("src/test/java");
    Set<String> names = new HashSet<>();
    for (String path : java.keySet()) names.add(baseName(path));
    Set<String> candidates = new java.util.TreeSet<>();
    if (Files.isDirectory(testRoot))
      try (Stream<Path> walk = Files.walk(testRoot)) {
        for (Path file :
            (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator)
          candidates.add(
              root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
      }
    for (String path : java.keySet()) if (path.startsWith("src/test/java/")) candidates.add(path);
    List<String> selected = new ArrayList<>();
    for (String path : candidates) {
      boolean changed = java.containsKey(path);
      if (!changed && !looksLikeTest(baseName(path))) continue;
      String text =
          changed ? java.get(path) : Files.readString(root.resolve(path), StandardCharsets.UTF_8);
      boolean uses = changed;
      for (String name : names)
        if (!uses && Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find())
          uses = true;
      if (uses && !hasNoTests(text)) selected.add(path);
      if (selected.size() >= MAX_TESTS) break;
    }
    return selected;
  }

  private static boolean hasNoTests(String text) {
    return !text.contains("@Test");
  }

  private static boolean looksLikeTest(String base) {
    return base.endsWith("Test")
        || base.endsWith("Tests")
        || base.endsWith("IT")
        || base.startsWith("Test");
  }

  private static String baseName(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
  }

  private static String classNameOf(String path, String text) {
    Matcher pkg = PACKAGE.matcher(text);
    return (pkg.find() ? pkg.group(1) + "." : "") + baseName(path);
  }

  private Verification runTests(
      Map<String, String> java, List<String> dependents, long started, AiCancel cancel)
      throws IOException {
    boolean junit =
        classpath.entries().stream()
            .anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("junit"));
    List<String> tests = selectTests(java);
    if (tests.isEmpty())
      return Verification.passed(0, true, millis(started), "no test uses the changed classes");
    if (!junit)
      return Verification.passed(
          0, false, millis(started), "tests not run: no JUnit jar on the class path");
    List<String> units = new ArrayList<>(dependents);
    for (String t : tests) if (!units.contains(t)) units.add(t);
    Compiled compiled = compile(java, units, RUNNER);
    if (!compiled.ok()) {
      List<VerifyProblem> blamed = subtractBaseline(compiled.errors(), java, units);
      if (!blamed.isEmpty())
        return new Verification(
            Verification.Outcome.COMPILE_FAILED,
            blamed.subList(0, Math.min(MAX_PROBLEMS, blamed.size())),
            0,
            true,
            millis(started),
            "while compiling the tests");
    }
    List<String> classNames = new ArrayList<>();
    for (String path : tests) {
      String text =
          java.containsKey(path)
              ? java.get(path)
              : Files.readString(root.resolve(path), StandardCharsets.UTF_8);
      classNames.add(classNameOf(path, text));
    }
    Path temp = Files.createTempDirectory("javelin-verify-");
    try {
      for (var e : compiled.classes().entrySet()) {
        Path file = temp.resolve(e.getKey().replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, e.getValue());
      }
      return execute(temp, classNames, started, cancel);
    } finally {
      deleteTree(temp);
    }
  }

  private Verification execute(Path classes, List<String> classNames, long started, AiCancel cancel)
      throws IOException {
    String marker = "@@VERIFY-" + java.util.UUID.randomUUID() + "@@";
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(
        List.of(
            "-Xmx256m",
            "-XX:+UseSerialGC",
            "-Djava.awt.headless=true",
            "-Dverify.marker=" + marker));
    StringBuilder cp = new StringBuilder(classes.toString());
    for (Path p : classpath.entries()) cp.append(java.io.File.pathSeparator).append(p);
    command.add("-cp");
    command.add(cp.toString());
    command.add("javelinverify.MiniRunner");
    command.addAll(classNames);
    Process process =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    List<String> lines = new ArrayList<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  synchronized (lines) {
                    if (line.startsWith(marker)) lines.add(line);
                    else if (lines.size() < 30)
                      lines.add(line.length() > 300 ? line.substring(0, 300) : line);
                  }
                }
              } catch (IOException ex) {
                // killed
              }
            },
            "verify-output");
    reader.setDaemon(true);
    reader.start();
    boolean finished = false;
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.testSeconds());
      while (!finished && System.nanoTime() < deadline && !(cancel != null && cancel.isCancelled()))
        finished = process.waitFor(100, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    if (!finished) process.destroyForcibly();
    try {
      reader.join(2000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    int passed = 0;
    boolean runnerFinished = false;
    List<VerifyProblem> failures = new ArrayList<>();
    synchronized (lines) {
      for (String line : lines) {
        if (!line.startsWith(marker)) continue;
        String[] f = line.split("\t", 4);
        String kind = f.length > 1 ? f[1] : "";
        switch (kind) {
          case "PASS" -> passed++;
          case "DONE" -> runnerFinished = true;
          case "FAIL" ->
              failures.add(
                  new VerifyProblem(
                      "", 0, "test " + f[2] + " failed: " + (f.length > 3 ? f[3] : "")));
          case "CRASH" ->
              failures.add(
                  new VerifyProblem(
                      "", 0, f[2] + " could not be loaded: " + (f.length > 3 ? f[3] : "")));
          default -> {}
        }
      }
    }
    if (!finished)
      failures.add(
          new VerifyProblem(
              "",
              0,
              "the tests did not finish within "
                  + config.testSeconds()
                  + " s (a loop, or code waiting for input?)"));
    else if (!runnerFinished || process.exitValue() != 0) {
      String output;
      synchronized (lines) {
        output =
            lines.stream()
                .filter(line -> !line.startsWith(marker))
                .limit(10)
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
      }
      failures.add(
          new VerifyProblem(
              "",
              0,
              "the test runner "
                  + (runnerFinished
                      ? "exited with code " + process.exitValue()
                      : "did not complete")
                  + (output.isEmpty() ? "" : ": " + output)));
    }
    if (failures.isEmpty()) {
      if (passed == 0) return Verification.passed(0, true, millis(started), "no test method ran");
      return Verification.passed(passed, true, millis(started), "");
    }
    return new Verification(
        Verification.Outcome.TESTS_FAILED,
        failures.subList(0, Math.min(MAX_PROBLEMS, failures.size())),
        passed,
        true,
        millis(started),
        "");
  }

  private static void deleteTree(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ex) {
      // a temporary folder that stays is harmless
    }
  }

  /** Runs JUnit 5 and JUnit 4 {@code @Test} methods by reflection; no launcher needed. */
  private static final String RUNNER =
      """
      package javelinverify;

      import java.lang.reflect.*;
      import java.util.*;

      public final class MiniRunner {
        @SuppressWarnings("unchecked")
        private static Class<? extends java.lang.annotation.Annotation> ann(String name) {
          try {
            return (Class<? extends java.lang.annotation.Annotation>) Class.forName(name);
          } catch (Throwable e) {
            return null;
          }
        }

        private static boolean has(Method m, List<Class<? extends java.lang.annotation.Annotation>> a) {
          for (var x : a) if (x != null && m.isAnnotationPresent(x)) return true;
          return false;
        }

        private static String oneLine(Throwable t) {
          String m = String.valueOf(t.getMessage()).replaceAll("\\\\s+", " ").strip();
          String s = t.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + m);
          return s.length() > 300 ? s.substring(0, 300) : s;
        }

        public static void main(String[] args) {
          final String mark = System.getProperty("verify.marker");
          var test = Arrays.asList(ann("org.junit.jupiter.api.Test"), ann("org.junit.Test"));
          var before = Arrays.asList(ann("org.junit.jupiter.api.BeforeEach"), ann("org.junit.Before"));
          var after = Arrays.asList(ann("org.junit.jupiter.api.AfterEach"), ann("org.junit.After"));
          var disabled = Arrays.asList(ann("org.junit.jupiter.api.Disabled"), ann("org.junit.Ignore"));
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
              if (has(m, test) && !has(m, disabled)) methods.add(m);
            methods.sort(Comparator.comparing(Method::getName));
            for (Method m : methods) {
              String id = name + "#" + m.getName();
              try {
                Constructor<?> k = c.getDeclaredConstructor();
                k.setAccessible(true);
                Object o = k.newInstance();
                for (Method b : c.getDeclaredMethods())
                  if (has(b, before)) { b.setAccessible(true); b.invoke(o); }
                try {
                  m.setAccessible(true);
                  m.invoke(o);
                } finally {
                  for (Method a : c.getDeclaredMethods())
                    if (has(a, after)) { a.setAccessible(true); a.invoke(o); }
                }
                System.out.println(mark + "\\tPASS\\t" + id);
              } catch (InvocationTargetException e) {
                System.out.println(mark + "\\tFAIL\\t" + id + "\\t" + oneLine(e.getCause()));
              } catch (Throwable t) {
                System.out.println(mark + "\\tFAIL\\t" + id + "\\t" + oneLine(t));
              }
            }
          }
          System.out.println(mark + "\\tDONE");
          System.out.flush();
          System.exit(0);
        }
      }
      """;
}
