package com.javelin_ide.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * FEATURES.md promises that every feature has a test that proves it. This makes the promise checkable: each row's
 * "Verified by" cell must name tests that exist (a class, or {@code Class.method}), unless the row says it is a
 * manual check. A feature cannot be documented without a test, and a renamed test cannot leave the document lying.
 */
class FeaturesDocTest {
    private static final Path TESTS = Path.of("src", "test", "java");
    private static final Pattern TOKEN = Pattern.compile("`([^`]+)`");
    private static final Pattern TEST_REFERENCE = Pattern.compile("([A-Z]\\w*Test)(?:\\.(\\w+))?");

    /** Test class simple name -> source text. */
    private static final Map<String, String> testSources = new HashMap<>();

    @BeforeAll static void indexTests() throws IOException {
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.java$", "");
                testSources.put(name, Files.readString(file));
            }
        }
        assertTrue(testSources.size() > 50, "run the tests from the project root");
    }

    private record Row(int line, String text, String verifiedBy) {}

    private static List<Row> rows() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("FEATURES.md"));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.startsWith("|") || line.matches("\\|[\\s|:-]+\\|?")) continue;
            String[] cells = line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length()).split("(?<!\\\\)\\|");
            String first = cells[0].strip();
            if (first.equals("Feature") || first.equals("Guarantee")) continue; // table headers
            rows.add(new Row(i + 1, first, cells[cells.length - 1].strip()));
        }
        return rows;
    }

    @Test void theDocumentHasFeatureRows() throws IOException {
        assertTrue(rows().size() > 30, "FEATURES.md should list the features; found " + rows().size());
    }

    @Test void everyFeatureNamesATestThatExists() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Row row : rows()) {
            boolean manual = row.verifiedBy().toLowerCase().contains("manual check");
            int found = 0;
            Matcher token = TOKEN.matcher(row.verifiedBy());
            while (token.find()) {
                Matcher reference = TEST_REFERENCE.matcher(token.group(1));
                if (!reference.matches()) continue; // scripts, commands and patterns such as `yaml*` are not test names
                String source = testSources.get(reference.group(1));
                if (source == null) { problems.add("FEATURES.md:" + row.line() + " names " + reference.group(1) + ", which does not exist"); continue; }
                String method = reference.group(2);
                if (method != null && !Pattern.compile("\\bvoid\\s+" + Pattern.quote(method) + "\\s*\\(").matcher(source).find()) {
                    problems.add("FEATURES.md:" + row.line() + " names " + reference.group(1) + "." + method + ", which does not exist");
                    continue;
                }
                found++;
            }
            if (found == 0 && !manual) problems.add("FEATURES.md:" + row.line() + " has no test that verifies it: " + abbreviate(row.text()));
        }
        assertEquals(List.of(), problems);
    }

    @Test void theCheckRecognisesWhatItShouldReject() {
        assertTrue(TEST_REFERENCE.matcher("ProjectFilesTest").matches());
        assertTrue(TEST_REFERENCE.matcher("ProjectWorkflowE2ETest.createEditSaveAndReopenAFile").matches());
        assertFalse(TEST_REFERENCE.matcher("build-app.sh").matches());
        assertFalse(TEST_REFERENCE.matcher("yaml*").matches());
        assertFalse(testSources.containsKey("NoSuchTest"));
    }

    private static String abbreviate(String text) { return text.length() > 70 ? text.substring(0, 67) + "..." : text; }
}
