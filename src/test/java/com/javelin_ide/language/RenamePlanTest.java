package com.javelin_ide.language;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RenamePlanTest {
    private static Lsp.TextEdit edit(String text) { return new Lsp.TextEdit(new Lsp.Range(new Lsp.Position(0, 0), new Lsp.Position(0, 1)), text); }
    private static File local(URI uri) { return "file".equals(uri.getScheme()) ? new File(uri.getPath()) : null; }

    @Test void editsAreGroupedByLocalFileAndCounted() {
        Map<URI, List<Lsp.TextEdit>> changes = new LinkedHashMap<>();
        changes.put(URI.create("file:///p/A.java"), List.of(edit("x"), edit("x")));
        changes.put(URI.create("file:///p/B.java"), List.of(edit("x")));
        changes.put(URI.create("jar:file:///lib.jar!/C.class"), List.of(edit("x"), edit("x"), edit("x")));
        RenamePlan plan = RenamePlan.of(new Lsp.WorkspaceEdit(changes, false), RenamePlanTest::local);
        assertEquals(List.of(new File("/p/A.java"), new File("/p/B.java")), new ArrayList<>(plan.byFile().keySet()));
        assertEquals(3, plan.total());
        assertEquals(3, plan.skipped());
    }

    @Test void theSummaryNamesFilesAndWhatWillNotHappen() {
        Map<URI, List<Lsp.TextEdit>> changes = new LinkedHashMap<>();
        changes.put(URI.create("file:///p/A.java"), List.of(edit("x"), edit("x")));
        changes.put(URI.create("http://example.com/x"), List.of(edit("x")));
        String s = RenamePlan.of(new Lsp.WorkspaceEdit(changes, false), RenamePlanTest::local).summary("foo", "bar", f -> f.getName());
        assertEquals("Rename foo to bar: 2 edit(s) in 1 file(s).\n\n  A.java  (2)\n\n1 edit(s) in non-file locations will be skipped.\n"
            + "\nFiles that are not open will be opened in tabs. The changes stay unsaved, so you can review and undo them.", s);
    }

    @Test void aLongListOfFilesIsCutOff() {
        Map<URI, List<Lsp.TextEdit>> changes = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) changes.put(URI.create("file:///p/F" + i + ".java"), List.of(edit("x")));
        String s = RenamePlan.of(new Lsp.WorkspaceEdit(changes, false), RenamePlanTest::local).summary("a", "b", File::getName);
        assertTrue(s.contains("17 edit(s) in 17 file(s)"));
        assertTrue(s.contains("  F14.java  (1)\n  ...\n"));
        assertFalse(s.contains("F15.java"));
        assertFalse(s.contains("non-file"));
    }

    @Test void exactlyFifteenFilesAreAllShown() {
        Map<URI, List<Lsp.TextEdit>> changes = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) changes.put(URI.create("file:///p/F" + i + ".java"), List.of(edit("x")));
        String s = RenamePlan.of(new Lsp.WorkspaceEdit(changes, false), RenamePlanTest::local).summary("a", "b", File::getName);
        assertTrue(s.contains("F14.java"));
        assertFalse(s.contains("..."));
    }

    @Test void aProposalWithNoLocalEditsIsEmpty() {
        RenamePlan plan = RenamePlan.of(new Lsp.WorkspaceEdit(Map.of(), false), RenamePlanTest::local);
        assertEquals(0, plan.total());
        assertTrue(plan.byFile().isEmpty());
    }
}
