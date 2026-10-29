# Javelin IDE

A local-first Java code editor built around the principles of intentional computing.

![Javelin IDE screenshot cover image](cover.png)

The editor is designed to be a tool for making software, not a platform for consuming information. It should remain useful offline, avoid unnecessary accounts and cloud dependencies, and keep automation and AI explicitly under the programmer's control.

## Current status

Roadmap progress is tracked in `ROADMAP.md`; v0.9 (build and run) is the latest milestone. Note: v0.6 & v0.7 deferred maybe skipped to keep this IDE simple.

Included:
- Maven build
- JavaFX application
- Basic editor window
- File open/save support
- Image viewer: PNG, JPEG, GIF and BMP files open in a read-only tab with fit, 100% and zoom (SVG still opens as text)
- Project documentation and design principles
- Build and run: output panel, problems panel, line-oriented terminal, process management

## Build and run (v0.4)

Open a project directory, then use the **Run** menu:

| Action | Shortcut | What it does |
| --- | --- | --- |
| Build | Cmd+B (Ctrl+B on Windows/Linux) | Runs the detected build command (Maven, Gradle, Make, Cargo, npm) in the project directory. |
| Run Command... | F5 | Shows an editable command (remembered per project), then runs it. |
| Stop All Processes | Cmd+. (Ctrl+. on Windows/Linux) | Stops every process the editor started. |
| Processes... | | Pick one running process to stop. |

Output appears in the **Output** panel. Compiler diagnostics (javac, Maven, gcc-style) are collected in
**Problems**; double-click one to jump to the line. **Terminal** (Cmd+` on macOS, Ctrl+` elsewhere) is a persistent shell fed one line
at a time. Toggle the bottom panel with Ctrl+J.

Terminals:
- **Tabs:** `+ New Terminal` or Ctrl+Shift+` opens another terminal; each tab has its own shell and environment.
- **Long-running commands:** while a command such as `uvicorn api.main:app --reload` runs, the tab stays bound to it. The
  tab title shows a dot, the header shows the command, and typed lines go to the program's stdin.
- **Standard keys:** Ctrl+C interrupts the running command (SIGINT; the shell stays alive; copies instead when text is
  selected), Ctrl+D exits an idle shell, Ctrl+L clears the transcript, Up/Down walk history. Closing a busy tab asks first.
- **Python virtualenvs:** after `. .venv/bin/activate` the prompt shows `(.venv) $` and the tab title shows the venv;
  `deactivate` removes both. To do this the terminal sends one read-only `printf` of `$VIRTUAL_ENV` to the shell after a
  command finishes (never while a command is running) and hides its output.
- **Limits:** there is no PTY, so full-screen programs (vim, top) and job control are unsupported. Python output is
  unbuffered (`PYTHONUNBUFFERED=1`) so servers show logs immediately. On Windows, Ctrl+C terminates the command instead of
  sending SIGINT, and venv detection is untested.

Nothing runs unless you ask for it, commands are echoed before they run, and all child processes are stopped when the editor exits.

See [FEATURES.md](FEATURES.md) for every feature and the test that verifies it.

## Requirements

- Java 21+
- Maven 3.9+

## Run

```bash
mvn javafx:run
```

## Build

```bash
mvn clean package
```

## Install on macOS

Build a real app that starts without a Terminal window, and install the `Javelin` command:

```
packaging/macos/build-app.sh      # needs JDK 21+ (with jpackage) and Maven; writes dist/Javelin IDE.app
packaging/macos/install.sh        # copies the app to /Applications and `Javelin` to /usr/local/bin (or ~/.local/bin)
```

Then, in any folder in the terminal:

```
Javelin            # open the current folder as a project
Javelin ~/code/x   # open that folder
```

Every call opens its own editor window (a new instance), so you can run `Javelin` in several folders to edit several projects at once and switch between them with Cmd+Tab or the Dock. If a folder is already open in another window you are asked before it is opened a second time (Cancel closes the new window). `packaging/macos/install.sh --uninstall` removes both. The command is installed as `Javelin`, and nothing is installed under a lowercase name any more; an older lowercase command is replaced. macOS volumes are usually case-insensitive, so the command also answers to its name typed in lowercase.
To install somewhere else, set `JAVELIN_APPS_DIR` and `JAVELIN_BIN_DIR` (for example `JAVELIN_APPS_DIR=~/Applications`).
Settings are kept in `~/Library/Application Support/javelin-ide`.

## App icon

The icon is drawn in code, so it is the same on Windows, macOS and Linux. To use your own, set `app.icon` in the user
settings to a PNG, JPEG or GIF (see `CUSTOMIZATION.md`). To get the built-in icon as files for a native packager:

```
mvn compile
java -cp target/classes com.javelin_ide.icon.IconExporter packaging/icons
```

This writes `icon.ico` (Windows), `icon.icns` (macOS) and `icon.png` (Linux).

## Project structure

```text
javelin_ide/
├── pom.xml
├── README.md
├── PRINCIPLES.md
├── QUICK_RULES.md
├── ROADMAP.md
├── ARCHITECTURE.md
├── DESIGN.md
├── PRIVACY.md
├── AI.md
├── EXTENSIONS.md
├── SECURITY.md
├── CUSTOMIZATION.md
├── BUGS.md
├── packaging/macos/   (build-app.sh, install.sh, the Javelin command)
└── src/
    ├── main/java/com/javelin_ide/App.java
    ├── main/java/com/javelin_ide/build/   (process, build, and diagnostics services)
    ├── main/java/com/javelin_ide/ui/      (output, problems, and terminal panels)
    └── main/resources/styles/editor.css
```

## Philosophy

The editor should stay quiet, local, understandable, durable, and human-directed. See `PRINCIPLES.md` and `QUICK_RULES.md`.
