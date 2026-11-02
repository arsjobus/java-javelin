package com.javelin.language;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * Maps file extensions to language ids and offers a starting-point server command for each.
 * The suggestions are only pre-filled into a dialog: nothing is started until the user confirms a command.
 */
public final class Languages {
    private Languages() {}

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
        Map.entry("java", "java"), Map.entry("py", "python"), Map.entry("pyi", "python"),
        Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"), Map.entry("jsx", "javascriptreact"),
        Map.entry("ts", "typescript"), Map.entry("tsx", "typescriptreact"),
        Map.entry("go", "go"), Map.entry("rs", "rust"),
        Map.entry("c", "c"), Map.entry("h", "c"),
        Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"),
        Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
        Map.entry("json", "json"), Map.entry("sh", "shellscript"), Map.entry("bash", "shellscript")
    );

    private static final Map<String, String> SUGGESTED = Map.ofEntries(
        Map.entry("java", "jdtls"), Map.entry("python", "pylsp"),
        Map.entry("javascript", "typescript-language-server --stdio"), Map.entry("javascriptreact", "typescript-language-server --stdio"),
        Map.entry("typescript", "typescript-language-server --stdio"), Map.entry("typescriptreact", "typescript-language-server --stdio"),
        Map.entry("go", "gopls"), Map.entry("rust", "rust-analyzer"), Map.entry("c", "clangd"), Map.entry("cpp", "clangd"),
        Map.entry("kotlin", "kotlin-language-server"), Map.entry("ruby", "solargraph stdio"), Map.entry("php", "intelephense --stdio"),
        Map.entry("json", "vscode-json-language-server --stdio"), Map.entry("shellscript", "bash-language-server start")
    );

    /** The language id for a file, or null when the extension is not recognized. */
    public static String idFor(File file) {
        if (file == null) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String suggestedCommand(String languageId) { return SUGGESTED.getOrDefault(languageId, ""); }
}
