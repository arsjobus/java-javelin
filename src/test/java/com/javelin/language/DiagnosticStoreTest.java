package com.javelin.language;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticStoreTest {
    private static final File FILE = new File("A.java");

    private static Lsp.Diagnostic error(int line, int from, int to) {
        return new Lsp.Diagnostic(new Lsp.Range(new Lsp.Position(line, from), new Lsp.Position(line, to)), Lsp.Diagnostic.ERROR, "boom", "test");
    }

    @Test void aFileWithoutDiagnosticsHasNone() {
        assertNull(new DiagnosticStore().get(FILE));
        assertNull(new DiagnosticStore().get(null), "an unsaved document has no file and no diagnostics");
    }

    @Test void replacingStoresWhatTheServerPublished() {
        DiagnosticStore store = new DiagnosticStore();
        List<Lsp.Diagnostic> reported = List.of(error(0, 2, 5));
        store.replace(FILE, reported);
        assertEquals(reported, store.get(FILE));
    }

    @Test void anEmptyPublishClearsTheFile() {
        DiagnosticStore store = new DiagnosticStore();
        store.replace(FILE, List.of(error(0, 2, 5)));
        store.replace(FILE, List.of());
        assertNull(store.get(FILE));
    }

    @Test void editsMoveTheStoredRanges() {
        DiagnosticStore store = new DiagnosticStore();
        store.replace(FILE, List.of(error(0, 4, 8)));
        store.shift(FILE, new Lsp.Position(0, 0), "", "xx"); // two characters typed before the diagnostic
        assertEquals(List.of(error(0, 6, 10)), store.get(FILE));
    }

    @Test void deletingTheWholeDiagnosticDropsIt() {
        DiagnosticStore store = new DiagnosticStore();
        store.replace(FILE, List.of(error(0, 4, 8)));
        store.shift(FILE, new Lsp.Position(0, 4), "abcd", "");
        assertNull(store.get(FILE), "a file with nothing left is forgotten, not kept as an empty list");
    }

    @Test void shiftingAFileWithoutDiagnosticsChangesNothing() {
        DiagnosticStore store = new DiagnosticStore();
        store.shift(FILE, new Lsp.Position(0, 0), "", "x");
        assertNull(store.get(FILE));
    }
}
