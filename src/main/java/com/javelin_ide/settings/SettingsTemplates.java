package com.javelin_ide.settings;

/** Starter contents for settings files created on request. Everything is commented out, so a fresh file changes nothing. */
public final class SettingsTemplates {
    private SettingsTemplates() {}

    public static String user(Keybindings keys) {
        return """
            # Javelin IDE user settings.
            # Format: one key=value per line, UTF-8. Lines starting with # are comments; values are taken as written.
            # Remove the leading # from a line to enable it. Saving this file in the editor applies it immediately.
            # Project settings (<project>/.javelin-ide/settings.conf) override the editor.* values below.

            # Theme: light (BurpleNurple), dark, high-contrast, or a .css file in themes/.
            #theme=light

            # Application icon: path to a PNG, JPEG or GIF (square, 512x512 or larger looks best). "~" is your home folder.
            # Leave it out to use the built-in icon. Applies to the window, taskbar and macOS Dock.
            #app.icon=~/Pictures/javelin-ide.png

            # Editor
            #editor.fontFamily=Monospaced
            #editor.fontSize=14
            #editor.tabSize=4
            #editor.insertSpaces=true
            #editor.wordWrap=false
            #editor.lineNumbers=true
            #editor.autoIndent=true

            # Keybindings: key.<action id>=<shortcut>. Modifiers: Ctrl, Shift, Alt, Meta, Shortcut (Cmd on macOS).
            # Use "none" to remove a shortcut. Unlisted actions keep their defaults.
            """ + keys.template();
    }

    public static String project() {
        return """
            # Javelin IDE project settings. Same format as the user settings file.
            # These values override the user's for this project only. Commit this file to share them,
            # or ignore the .javelin-ide folder. Remove the leading # from a line to enable it.

            #editor.tabSize=2
            #editor.insertSpaces=true
            #editor.wordWrap=false
            #editor.autoIndent=true

            # Commands. run.command is only pre-filled into the Run Command dialog; build.command replaces the
            # detected build command for Run > Build. The command is echoed in the Output panel when it runs.
            #run.command=
            #build.command=

            # Names (comma separated) hidden from the project tree and Go to File.
            #tree.exclude=.git,target

            # Pre-filled into the Start Language Server dialog, one line per language id (java, python, ...).
            #language.server.java=
            """;
    }
}
