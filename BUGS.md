# Bugs

Known issues for the initial foundation.

## Current

- [ ] The project tree: files can be created, renamed, moved (drag onto a folder) and deleted from it, and changes made outside the editor (terminal, build, git, file manager) show up within about two seconds, but it cannot move several entries at once. The check compares names only, so it does not look inside symbolic-linked folders, and the tree is rebuilt (open folders and the selection are kept) rather than updated in place.
- [ ] Syntax highlighting is a lexical scanner (no parsing) for Java, Kotlin, JavaScript/TypeScript, C/C++, Go, Rust, PHP, JSON, Python, HTML and XML, chosen by file extension; other files are shown as plain text. Keywords and types come from a short built-in list per language (no contextual keywords such as `get`/`set`; Rust attributes `#[name]` and `#![name]` are coloured as annotations up to the end of their path, e.g. `#[tokio::main`, with the arguments scanned as ordinary code; Rust lifetimes and loop labels such as `'a` are recognised so they no longer swallow code as a character literal, but they are left uncoloured). Python's soft keywords `match` and `case` are coloured only when they start a line and a `:` follows on the same line outside brackets, so a subject or pattern that continues over several lines is left plain. Python f-string `{...}` expressions are coloured as part of the string. In HTML, `<script>` content is highlighted as JavaScript regardless of its `type`, `<style>` content (CSS) and HTML/XML entities are left uncoloured, and unquoted attribute values are plain. It is switched off above 1 million characters.
- [ ] The integrated terminal is line-oriented (no PTY): full-screen programs and job control do not work.
- [ ] Login-shell environment (macOS/Linux): it is read once per run, with `$SHELL -l -i -c`, so changes to `~/.zshrc` or `~/.zprofile` need a restart of the IDE, and a shell that takes longer than 10 seconds to start (heavy rc files) is skipped in favour of the inherited PATH plus `/opt/homebrew/bin`, `/usr/local/bin` and their `sbin` twins. The terminal tab itself is a plain shell without a PTY, so aliases, functions and prompts from `.zshrc` are not loaded there; only the environment variables are.
- [ ] Windows terminals: Ctrl+C terminates the command rather than interrupting it, and virtualenv detection is untested.
- [ ] Build output is parsed for javac, Maven, gcc/clang, tsc/MSVC, Kotlin, Go, rustc and Python tracebacks; other tools show their output but no problems.
- [ ] Background jobs are recognized for a lone `&` anywhere on the line (not in quotes, comments or `&&`/`&>`/`>&`/`|&`), but not on Windows. For `a & b` one process per `&` is treated as the background job, so a backgrounded pipeline (`a | b & c`) is only partly tracked.
- [ ] Language servers are started manually per language (Language > Start Language Server...). A `language.server.<id>` setting (user or project) pre-fills the command, but servers are never started automatically.
- [ ] LSP: no `workspace/applyEdit`, code actions, range formatting or workspace symbols; servers that rename files as part of a rename are refused.
- [ ] LSP: completion snippets are flattened to plain text (placeholders keep their default text), and results pointing outside `file:` URIs (e.g. jar contents) are not opened.
- [ ] Customization: only menu actions are rebindable; keys handled by the text area itself (cursor movement, Ctrl+Z, copy/paste) are not.
- [ ] Customization: with `editor.insertSpaces=false` the on-screen width of a tab character depends on JavaFX honoring `-fx-tab-size` in the code area; if it does not, tabs show at the default width.
- [ ] Customization: the Settings dialog edits files on disk. If the same settings file is open in a tab with unsaved changes, saving the dialog does not update that tab.
- [ ] Files larger than 32 MB are refused rather than opened, and files that are not UTF-8 text cannot be opened.
- [ ] Crash recovery restores the text, cursor position and relative order of unsaved tabs, but not their undo history, scroll position or selection; saved (unmodified) tabs are not part of recovery at all.
- [ ] The JavaFX user interface (`App`, `ui/*`) has no automated tests; it is covered by manual and smoke checks (see HARDENING.md).
- [ ] Image viewer: shows PNG, JPEG, GIF and BMP only (what JavaFX decodes); WebP, ICO, TIFF and HEIC are not shown and fail with the "not valid UTF-8 text" message. An open image is not reloaded if the file changes on disk.
- [ ] Matching bracket is textual: brackets inside string literals and comments are counted, and only `()`, `[]` and `{}` are matched.
- [ ] There is no Git integration.
- [ ] AI integration is documentation-only at this stage.
- [ ] Packaging: only macOS has a packaging recipe (`packaging/macos/`, a `jpackage` app image plus the `Javelin` terminal command). The app is not code-signed or notarized, so a copy downloaded from elsewhere is blocked by Gatekeeper; one built on the same Mac is not. There is no Windows or Linux installer, and `Javelin` is a macOS-only command. Every `Javelin` call starts a new instance (one window per project, each with its own Dock icon). Opening a folder that another Javelin IDE window has open asks first (the check is advisory and covers Javelin IDE windows only), but instances share one settings file (the last one to save wins), and dropping a folder on the Dock icon is not supported. The icon is built in (drawn in code); `IconExporter` writes `icon.ico`, `icon.icns` and `icon.png`.
- [ ] App icon: a custom `app.icon` must be a PNG, JPEG or GIF (`.ico`/`.icns` cannot be read). The macOS Dock icon is set through `java.awt.Taskbar`, and the window icon through JavaFX; neither has been checked on all three operating systems yet, and a missing Dock integration is ignored silently (the window icon still applies).

## Reporting

When documenting a bug, include:
- Operating system
- Java version
- Maven version
- Project/reproduction steps
- Expected behavior
- Actual behavior
- Relevant logs
