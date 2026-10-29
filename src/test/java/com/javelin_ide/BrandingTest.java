package com.javelin_ide;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The product is "Javelin" with a capital J wherever it is built, installed or launched. These tests read the real
 * packaging scripts, the POM, the docs and the sources, so a lowercase spelling or a build script that makes a
 * different app name than the install script expects fails the build instead of reaching a user.
 */
class BrandingTest {
    private static final Path ROOT = Path.of("");
    private static final Path SCRIPTS = Path.of("packaging", "macos");

    // The product name written with a lowercase j, as a word on its own ("javelin IDE", `javelin`, "javelin:"). Package
    // names (com.javelin_ide), the settings folder (javelin-ide) and file names with an underscore or hyphen are not it.
    private static final Pattern LOWERCASE_NAME = Pattern.compile("(?<![\\w./_-])javelin(?![\\w_-])");

    private static String read(Path file) throws IOException { return Files.readString(file, StandardCharsets.UTF_8); }

    private static String shellVariable(Path script, String name) throws IOException {
        Matcher m = Pattern.compile("(?m)^" + name + "=\"(?:\\$\\{[A-Z_]+:-)?([^\"}]+)\\}?\"").matcher(read(script));
        assertTrue(m.find(), script + " must define " + name);
        return m.group(1);
    }

    private static List<Path> textFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(ROOT)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".git" + java.io.File.separator) && !p.startsWith("target") && !p.startsWith("dist"))
                .filter(p -> p.toString().matches(".*\\.(java|md|xml|sh|css|command)$") || SCRIPTS.equals(p.getParent()))
                .forEach(files::add);
        }
        return files;
    }

    @Test void theNamesAreSpelledWithACapitalJ() {
        assertEquals("Javelin", Branding.NAME);
        assertEquals("Javelin IDE", Branding.FULL_NAME);
        assertEquals("Javelin", Branding.COMMAND);
    }

    @Test void windowTitlesCarryTheProductNameAndTheProject() {
        assertEquals("Javelin IDE", Branding.windowTitle(null));
        assertEquals("Javelin IDE", Branding.windowTitle("  "));
        assertEquals("Javelin IDE - my-project", Branding.windowTitle("my-project"));
    }

    @Test void theBuildScriptBuildsTheAppTheInstallScriptInstallsAndTheCommandLaunches() throws IOException {
        String built = shellVariable(SCRIPTS.resolve("build-app.sh"), "APP_NAME");
        String installed = shellVariable(SCRIPTS.resolve("install.sh"), "APP_NAME");
        String launched = shellVariable(SCRIPTS.resolve(Branding.COMMAND), "APP_NAME");
        assertEquals(Branding.FULL_NAME, built, "build-app.sh writes dist/<name>.app");
        assertEquals(built, installed, "install.sh must look for exactly the bundle build-app.sh wrote (macOS volumes can be case-sensitive)");
        assertEquals(built, launched, "the command must open the bundle install.sh installed");
    }

    @Test void theTerminalCommandIsInstalledAndRemovedUnderItsCapitalisedName() throws IOException {
        String install = read(SCRIPTS.resolve("install.sh"));
        assertTrue(Files.isRegularFile(SCRIPTS.resolve(Branding.COMMAND)));
        assertTrue(install.contains("cp \"$here/" + Branding.COMMAND + "\" \"$bin/" + Branding.COMMAND + "\""), "installs Javelin");
        assertTrue(install.contains("rm -f \"$bin/" + Branding.COMMAND + "\""), "uninstall removes Javelin");
        assertFalse(Files.exists(SCRIPTS.resolve("javelin")) && !Files.isSameFile(SCRIPTS.resolve("javelin"), SCRIPTS.resolve(Branding.COMMAND)),
            "no second, lowercase command script");
    }

    @Test void theJarTheBuildScriptPackagesIsTheJarMavenWrites() throws IOException {
        String pom = read(ROOT.resolve("pom.xml"));
        Matcher finalName = Pattern.compile("<finalName>([^<]+)</finalName>").matcher(pom);
        assertTrue(finalName.find(), "pom.xml sets the jar's name");
        assertEquals("Javelin-IDE-${project.version}", finalName.group(1));
        assertTrue(read(SCRIPTS.resolve("build-app.sh")).contains("jar=\"Javelin-IDE-$version.jar\""));
        assertTrue(pom.contains("<name>Javelin IDE</name>"));
    }

    @Test void noSourceScriptOrDocumentSpellsTheProductWithALowercaseJ() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : textFiles()) {
            if (file.getFileName().toString().equals("BrandingTest.java")) continue;   // this file has to name the wrong spelling
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("legacy-name")) continue;   // deliberate: cleaning up what an older install left
                if (LOWERCASE_NAME.matcher(lines.get(i)).find()) offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
        assertEquals(List.of(), offenders, "write Javelin / Javelin IDE (see Branding), not javelin");
    }
}
