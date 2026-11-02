package com.javelin;

/**
 * Entry point of the packaged macOS app (and of any build that runs from a plain classpath). The JavaFX
 * {@code Application} subclass cannot be the main class there, because the JavaFX launcher then looks for the
 * JavaFX modules on the module path and stops with "JavaFX runtime components are missing". This class does not
 * extend {@code Application}, so the classpath launcher starts normally and hands over to {@link App}.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) { App.main(args); }
}
