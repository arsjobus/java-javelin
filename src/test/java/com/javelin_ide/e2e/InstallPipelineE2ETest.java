package com.javelin_ide.e2e;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The macOS release path end to end, with the real packaging scripts and stand-ins for the tools that only exist on a
 * Mac (uname, mvn, jpackage, open): build the app, install it, launch a project with the terminal command, uninstall.
 * Every step must use the product's name with a capital J, and each step must find what the one before it produced.
 */
class InstallPipelineE2ETest {
    private static final Path REAL_SCRIPTS = Path.of("packaging", "macos");

    @TempDir Path work;
    Path checkout, stubs, apps, bin, home, openLog, jpackageLog;

    private record Outcome(int exit, String out, String err) {}

    @BeforeEach void setUp() throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "shell scripts");
        assertTrue(Files.isDirectory(REAL_SCRIPTS), "run the tests from the project root");
        // A copy of the scripts in a throw-away checkout: build-app.sh writes target/ and dist/ next to itself.
        checkout = Files.createDirectories(work.resolve("checkout"));
        Path scripts = Files.createDirectories(checkout.resolve("packaging/macos"));
        try (var files = Files.list(REAL_SCRIPTS)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path copy = scripts.resolve(file.getFileName());
                Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        stubs = Files.createDirectory(work.resolve("stubs"));
        apps = work.resolve("Applications");
        bin = work.resolve("bin");
        home = Files.createDirectory(work.resolve("home"));
        openLog = work.resolve("open.log");
        jpackageLog = work.resolve("jpackage.log");
        stub("uname", "echo Darwin");
        stub("java", "exit 0");   // the icon export
        stub("mvn", """
            case "$*" in
              *help:evaluate*) echo 0.1.0-SNAPSHOT ;;
              *dependency:copy-dependencies*) for a in "$@"; do case "$a" in -DoutputDirectory=*) mkdir -p "${a#-DoutputDirectory=}" ;; esac; done ;;
              *) mkdir -p target/classes && : > target/Javelin-IDE-0.1.0-SNAPSHOT.jar ;;
            esac
            """);
        stub("jpackage", """
            printf '%s\\n' "$@" >> "$STUB_JPACKAGE_LOG"
            name=; dest=
            while [ "$#" -gt 0 ]; do
              case "$1" in --name) name=$2; shift ;; --dest) dest=$2; shift ;; esac
              shift
            done
            mkdir -p "$dest/$name.app/Contents/MacOS" && : > "$dest/$name.app/Contents/MacOS/$name"
            """);
        stub("open", "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$STUB_OPEN_LOG\"; done");
    }

    private void stub(String name, String body) throws IOException {
        Path file = stubs.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Outcome sh(Path script, Path cwd, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("sh", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        pb.environment().put("PATH", stubs + ":/usr/bin:/bin");
        pb.environment().put("HOME", home.toString());
        pb.environment().put("JAVELIN_APPS_DIR", apps.toString());
        pb.environment().put("JAVELIN_BIN_DIR", bin.toString());
        pb.environment().put("STUB_OPEN_LOG", openLog.toString());
        pb.environment().put("STUB_JPACKAGE_LOG", jpackageLog.toString());
        pb.environment().putAll(env);
        Path out = work.resolve("out.txt"), err = work.resolve("err.txt");
        pb.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "script hung: " + script);
        return new Outcome(p.exitValue(), Files.readString(out), Files.readString(err));
    }

    private Path script(String name) { return checkout.resolve("packaging/macos").resolve(name); }

    private List<String> lines(Path log) throws IOException { return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : List.of(); }

    private Outcome build() throws Exception { return sh(script("build-app.sh"), checkout, Map.of("SKIP_TESTS", "1")); }

    private Outcome install() throws Exception { return sh(script("install.sh"), checkout, Map.of()); }

    @Test void buildingWritesAnAppNamedJavelinIdeFromTheJarMavenProduces() throws Exception {
        Outcome built = build();
        assertEquals(0, built.exit(), built.err());
        assertTrue(Files.isDirectory(checkout.resolve("dist/Javelin IDE.app")), "dist holds Javelin IDE.app");
        try (var dist = Files.list(checkout.resolve("dist"))) {
            assertEquals(List.of("Javelin IDE.app"), dist.map(p -> p.getFileName().toString()).toList(), "no second, differently spelled bundle");
        }
        List<String> jpackage = lines(jpackageLog);
        assertEquals("Javelin IDE", jpackage.get(jpackage.indexOf("--name") + 1));
        assertEquals("Javelin-IDE-0.1.0-SNAPSHOT.jar", jpackage.get(jpackage.indexOf("--main-jar") + 1));
        assertTrue(built.out().contains("Javelin IDE.app"), built.out());
    }

    @Test void installingPlacesTheAppAndTheCommandUnderTheirCapitalisedNames() throws Exception {
        assertEquals(0, build().exit());
        Outcome installed = install();
        assertEquals(0, installed.exit(), installed.err());
        assertTrue(Files.isDirectory(apps.resolve("Javelin IDE.app")));
        assertTrue(Files.isExecutable(bin.resolve("Javelin")));
        assertTrue(installed.out().contains("Javelin IDE.app") && installed.out().contains("/Javelin"), installed.out());
        assertFalse(installed.out().matches("(?s).*(?<![\\w-])javelin(?![\\w-]).*"), "no lowercase name in what the user is told: " + installed.out());   // legacy-name
    }

    @Test void installingWithoutBuildingFirstExplainsWhatToDo() throws Exception {
        Outcome installed = install();
        assertEquals(1, installed.exit());
        assertTrue(installed.err().contains("Javelin IDE.app does not exist") && installed.err().contains("build-app.sh"), installed.err());
    }

    @Test void theInstalledCommandLaunchesTheInstalledAppForAProject() throws Exception {
        assertEquals(0, build().exit());
        assertEquals(0, install().exit());
        Path project = Files.createDirectory(work.resolve("my project"));
        Outcome launched = sh(bin.resolve("Javelin"), project, Map.of());
        assertEquals(0, launched.exit(), launched.err());
        assertEquals(List.of("-n", "-a", "Javelin IDE", "--args", project.toRealPath().toString()), lines(openLog));
        assertTrue(Files.isDirectory(apps.resolve(lines(openLog).get(2) + ".app")), "the app the command asks macOS for is the one that was installed");
    }

    @Test void anOlderLowercaseCommandIsReplacedAndUninstallRemovesEverything() throws Exception {
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("javelin"), "#!/bin/sh\necho old\n");   // legacy-name: left by an install made before the rename
        assertEquals(0, build().exit());
        assertEquals(0, install().exit());
        try (var entries = Files.list(bin)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.contains("Javelin"), names.toString());
            assertEquals(1, names.size(), "the old lowercase command does not linger beside the new one: " + names);
        }
        assertNotEquals("echo old", Files.readAllLines(bin.resolve("Javelin")).get(1), "the installed command is the new one");
        Outcome removed = sh(script("install.sh"), checkout, Map.of(), "--uninstall");
        assertEquals(0, removed.exit(), removed.err());
        assertFalse(Files.exists(apps.resolve("Javelin IDE.app")));
        try (var entries = Files.list(bin)) { assertEquals(List.of(), entries.toList()); }
        assertTrue(removed.out().contains("settings") || removed.out().contains("Settings"), "tells the user their settings stay: " + removed.out());
    }
}
