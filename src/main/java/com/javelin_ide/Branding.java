package com.javelin_ide;

/**
 * The one place that spells the product's name. The name is "Javelin" with a capital J everywhere it is built,
 * installed or launched: the macOS app bundle, the terminal command, window titles and messages. Keep the spelling
 * here so the build scripts, the app and the tests cannot drift apart ({@code BrandingTest} checks them against it).
 */
public final class Branding {
    /** The product name. */
    public static final String NAME = "Javelin";

    /** The full product name: window titles, the macOS app bundle ({@code Javelin IDE.app}) and messages. */
    public static final String FULL_NAME = NAME + " IDE";

    /** The terminal command that opens a folder as a project (macOS, {@code packaging/macos/Javelin}). */
    public static final String COMMAND = NAME;

    private Branding() {}

    /** A window title for a project: the product name alone when no project is open, otherwise name and project. */
    public static String windowTitle(String project) {
        return project == null || project.isBlank() ? FULL_NAME : FULL_NAME + " - " + project;
    }
}
