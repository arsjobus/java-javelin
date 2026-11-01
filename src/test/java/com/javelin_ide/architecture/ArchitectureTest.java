package com.javelin_ide.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ARCHITECTURE.md as failing tests. The shape is three layers, and dependencies only point inward:
 *
 * <pre>
 *   Bootstrap (App, Launcher)  ->  ui (JavaFX, one package per feature)  ->  core (JavaFX-free services)
 * </pre>
 *
 * <p>{@code Branding} (the product's spelling) is a shared constant that every layer may use. Inside {@code ui}, each
 * feature package talks to the others only through {@code ui.shell} (shared window state and the listener interface) and
 * {@code ui.editor} (the document model of the window), so {@code App} can stay a list of "create it, connect it".
 */
class ArchitectureTest {
    private static final String ROOT = "com.javelin_ide";
    /** JavaFX-free services: they can be tested without a display and must never know a window exists. */
    private static final String[] CORE = {ROOT + ".build..", ROOT + ".editor..", ROOT + ".io..", ROOT + ".language..", ROOT + ".launch..",
        ROOT + ".settings..", ROOT + ".terminal..", ROOT + ".icon.."};
    private static final String[] TOOLKIT = {"javafx..", "org.fxmisc..", "org.reactfx.."};

    private static JavaClasses classes;

    @BeforeAll static void importProductionClasses() {
        classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
        assertTrue(classes.size() > 100, "the production classes were not found; run the tests through Maven");
    }

    /** {@code App} and {@code Launcher}: the root package without {@code Branding}. */
    private static final DescribedPredicate<JavaClass> BOOTSTRAP = new DescribedPredicate<>("App or Launcher") {
        @Override public boolean test(JavaClass c) { return c.getPackageName().equals(ROOT) && !c.getName().startsWith(ROOT + ".Branding"); }
    };

    // ---- the three layers ----

    @Test void coreDoesNotKnowTheToolkit() {
        noClasses().that().resideInAnyPackage(CORE).should().dependOnClassesThat().resideInAnyPackage(TOOLKIT)
            .because("core services run and are tested without JavaFX or a display").check(classes);
    }

    @Test void coreDoesNotKnowTheWindow() {
        noClasses().that().resideInAnyPackage(CORE).should().dependOnClassesThat().resideInAnyPackage(ROOT + ".ui..")
            .orShould().dependOnClassesThat(BOOTSTRAP).because("dependencies point from the window to the services, never back").check(classes);
    }

    @Test void onlyTheWindowTouchesTheToolkit() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".ui..").and(DescribedPredicate.not(BOOTSTRAP)).should().dependOnClassesThat().resideInAnyPackage(TOOLKIT)
            .because("JavaFX belongs to ui/ and the application class").check(classes);
    }

    @Test void nothingDependsOnTheBootstrap() {
        noClasses().that(DescribedPredicate.not(BOOTSTRAP)).should().dependOnClassesThat(BOOTSTRAP)
            .because("App wires everything together, so nothing may reach back into it").check(classes);
    }

    @Test void theUiLayerDoesNotReachIntoTheApplicationOrTheOtherWayAround() {
        noClasses().that().resideInAPackage(ROOT + ".ui..").should().dependOnClassesThat(BOOTSTRAP).check(classes);
    }

    // ---- no tangles ----

    @Test void corePackagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().because("a cycle between packages means they cannot be understood or tested apart").check(classes);
    }

    @Test void uiFeaturePackagesAreFreeOfCycles() {
        slices().matching(ROOT + ".ui.(*)..").should().beFreeOfCycles().check(classes);
    }

    // ---- inside the ui layer: shell <- editor <- features <- menu ----

    private static ArchRule onlyDependOn(String feature, String... allowed) {
        List<String> others = new ArrayList<>(List.of("shell", "editor", "project", "customize", "language", "run", "recovery", "menu"));
        others.remove(feature);
        others.removeAll(List.of(allowed));
        return noClasses().that().resideInAPackage(ROOT + ".ui." + feature + "..").should().dependOnClassesThat()
            .resideInAnyPackage(others.stream().map(o -> ROOT + ".ui." + o + "..").toArray(String[]::new));
    }

    @Test void theShellKnowsNoFeature() { onlyDependOn("shell").check(classes); }
    @Test void theEditorKnowsOnlyTheShell() { onlyDependOn("editor", "shell").check(classes); }
    @Test void languageKnowsOnlyTheShellAndTheEditor() { onlyDependOn("language", "shell", "editor").check(classes); }
    @Test void runKnowsOnlyTheShellAndTheEditor() { onlyDependOn("run", "shell", "editor").check(classes); }
    @Test void recoveryKnowsOnlyTheShellAndTheEditor() { onlyDependOn("recovery", "shell", "editor").check(classes); }
    @Test void customizationKnowsOnlyTheShellAndTheEditor() { onlyDependOn("customize", "shell", "editor").check(classes); }
    @Test void theProjectKnowsTheShellTheEditorAndSettings() { onlyDependOn("project", "shell", "editor", "customize").check(classes); }

    @Test void onlyTheApplicationUsesTheMenu() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".ui.menu..").and(DescribedPredicate.not(BOOTSTRAP)).should().dependOnClassesThat().resideInAPackage(ROOT + ".ui.menu..").check(classes);
    }

    @Test void sharedWidgetsDoNotDependOnFeatures() {
        noClasses().that().resideInAPackage(ROOT + ".ui").should().dependOnClassesThat().resideInAnyPackage(
            ROOT + ".ui.shell..", ROOT + ".ui.editor..", ROOT + ".ui.project..", ROOT + ".ui.customize..", ROOT + ".ui.language..",
            ROOT + ".ui.run..", ROOT + ".ui.recovery..", ROOT + ".ui.menu..").because("panels and dialogs in ui/ are plain widgets that features use").check(classes);
    }

    @Test void theRulesSeeTheRealPackages() {
        assertTrue(anyClass(resideInAnyPackage(ROOT + ".ui.editor")), "ui.editor");
        assertTrue(anyClass(resideInAnyPackage(ROOT + ".language")), "language");
        assertTrue(anyClass(BOOTSTRAP), "the bootstrap classes");
    }

    private static boolean anyClass(DescribedPredicate<JavaClass> predicate) {
        return java.util.stream.StreamSupport.stream(classes.spliterator(), false).anyMatch(predicate);
    }

    // ---- staying small ----

    private static final Path SOURCES = Path.of("src", "main", "java", "com", "javelin_ide");

    @Test void theApplicationClassStaysAWiringList() throws IOException {
        long lines = Files.readAllLines(SOURCES.resolve("App.java")).size();
        assertTrue(lines <= 200, "App.java has " + lines + " lines; behaviour belongs in a feature package under ui/ (see ARCHITECTURE.md)");
    }

    @Test void noSourceFileGrowsBeyondWhatCanBeReviewed() throws IOException {
        List<String> tooLong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                long lines = Files.readAllLines(file).size();
                if (lines > 900) tooLong.add(SOURCES.relativize(file) + " (" + lines + " lines)");
            }
        }
        assertEquals(List.of(), tooLong, "split these by responsibility");
    }
}
