# Hardening (v0.9)

This release adds no new editor features. It makes the existing ones trustworthy: it protects your work, bounds
worst-case behaviour, proves the "local-first, no telemetry" promise with tests, and puts the logic under a
100%-line-coverage gate.

## Running the checks

```
mvn verify          # unit + end-to-end tests, then the coverage gate
open target/site/jacoco/index.html   # the coverage report
```

`mvn verify` fails if line coverage of the core packages drops below 100% (or branch coverage below 90%). The core is
everything except the JavaFX user interface: `build`, `editor`, `io`, `language`, `settings`, `terminal`.

## What each roadmap item means

| Item | What was done | Where it is verified |
| --- | --- | --- |
| Crash recovery | Unsaved buffers are copied to `<config>/recovery/` every 5 seconds (only when changed). A copy is deleted when its buffer is saved or deliberately discarded, so anything found at the next start came from a session that did not end cleanly, and javelin IDE offers to restore it. With several instances running (one per project), an instance's snapshots count as leftovers only once its process is gone, so a second window never offers or discards a first window's live work. Uncaught exceptions write a local report to `<config>/crashes/` (stack trace, Java and OS version; never document text). | `RecoveryStoreTest`, `RecoverySessionTest`, `CrashLogTest` |
| Safe saving | Saves write a temporary file, flush it to disk and atomically replace the original, so a crash or full disk mid-save can no longer truncate your file. Symbolic links and file permissions are preserved. Settings files use the same code. | `FileIOTest`, `SettingsTest` |
| Large-file testing | Files over 32 MB are refused with a clear message, files that are not UTF-8 get a clear error instead of garbled text, and syntax highlighting switches off above 1 million characters so typing stays responsive. Every parser is tested on megabyte-scale and deliberately pathological input. | `FileIOTest`, `SyntaxHighlighterTest`, `JsonTest`, `ProblemParserTest`, `TextDocumentTest` |
| Performance profiling | See the numbers below. Tests enforce generous time ceilings so a regression to quadratic behaviour fails the build. | the large-input and pathological-input tests in each test class |
| Offline testing | The core has no network code at all, and a test fails the build if any appears. The application was also started inside a network namespace with no connectivity and stayed up normally. | `OfflineGuardTest` |
| Privacy audit | See below. | `OfflineGuardTest`, `CrashLogTest`, `RecoveryStoreTest` |
| Dependency audit | Direct dependencies are an allow-list in a test: JavaFX, RichTextFX and (test scope) JUnit. Versions must be pinned exactly and no custom repositories may be configured. | `DependencyAuditTest` |
| End-to-end tests | A real language-server subprocess speaks LSP over stdio to the real `LanguageService`; a real shell drives `TerminalSession`; a real `javac` run flows through `ProcessManager` into `ProblemParser`. | `LanguageServiceTest`, `TerminalSessionTest`, `BuildPipelineE2ETest` |

## Performance (measured)

Best of five warmed runs on a single-core cloud VM, Java 21. These measure the pure-Java work; RichTextFX's own
rendering cost (applying styles to a very large document) is separate and was not measured here.

| Operation | Time |
| --- | --- |
| Highlight a ~1 MB document | 19 ms |
| Highlight a typical 10 KB file (one keystroke) | under 1 ms |
| Parse a 7 MB JSON message (200,000 objects) | 82 ms |
| Parse one 400 KB line of build output | 3 ms |
| 200 edits to a 200,000-line document | under 1 ms |
| Load a 100,000-line settings file | 24 ms |
| Atomic save of a 4 MB file, fsync included | 7 ms |
| Read it back with strict UTF-8 validation | 4 ms |
| Recovery snapshot of a 4 MB buffer, changed | 10 ms |
| Recovery snapshot tick when nothing changed | under 1 ms |

## Privacy audit

javelin IDE has no telemetry, no update check and no cloud calls, and nothing in the build can add them quietly.

What it writes to disk, all under your own account:

- `<config>/settings.conf` (preferences and key bindings) and `<config>/themes/`: plain text you can read and edit.
- `<project>/.javelin-ide/settings.conf`: only when you save project settings.
- `<config>/recovery/`: copies of unsaved text, owner-only permissions where the OS supports them, deleted when no longer needed and purged after 30 days. Each running instance also keeps one empty `.session-*.lock` file locked, so other instances can tell its snapshots are live and leave them alone.
- `<config>/crashes/`: at most the 20 newest crash reports; they contain a stack trace and version numbers, never document contents.
- Java `Preferences` (the OS-level per-user store): recent project paths and the run, build and language-server commands you typed into dialogs.

Language servers and build commands are programs you start yourself; javelin IDE passes them your files and nothing else.
Whatever network access those programs have is theirs, not javelin IDE's.

`<config>` is the platform's usual per-user directory (see `Settings.defaultUserDirectory()`).

## What this release found and fixed

Writing the tests exposed real defects, all fixed in this release and covered by regression tests:

- **Data loss on Exit.** *File > Exit* called `stage.close()`, which JavaFX does not route through the close-request handler, so the "save changes?" prompt was skipped. It now fires the close request. (This is documented JavaFX behaviour; the fix is covered by the smoke checks, not an automated test, because it lives in the untested UI layer.)
- **Non-atomic saves.** A crash during a save could leave a truncated file. Fixed (see Safe saving).
- **Highlighter crashes and stalls.** The tokenizer regex threw `StackOverflowError` on a long string literal and took quadratic time on lines of escaped quotes; emptying a document threw from `StyleSpansBuilder`; a stray quote or unterminated `/*` coloured the rest of the file. Replaced by a linear scanner with a size cap.
- **JSON.** Deeply nested input (or a cyclic structure being written) overflowed the stack and would have silently killed the language-server reader thread. Nesting is now limited to 256 levels.
- **Terminal.** Finished background jobs lingered as zombie processes that Java reports as alive, so the terminal could show phantom background jobs.
- **Keybindings.** `Ctrl++` was normalized to `CTRL+`, colliding with other bindings; modifier-only bindings gained a dangling `+`.
- **Output cleanup.** Hyperlink-style escape sequences (OSC terminated by `ESC \`) and charset escapes were left in build output.
- **LSP snippets.** `\$5` lost its text, and nested placeholders left stray braces.
- **Language servers.** Stopping from an interrupted thread swallowed the interrupt and could leave the server running; the "server exited" log line sometimes omitted the exit code.
- **Process pump.** An exception thrown by an output listener stopped the pump, so the process was never reaped and never reported as exited.
- **Tab key.** With `editor.insertSpaces=true` Tab inserted spaces *and* a stray `\t`: JavaFX delivers Tab as a separate `KEY_TYPED` event that consuming `KEY_PRESSED` does not stop. Tab/Shift+Tab are now handled by `editor/Indentation` (pure text edits, fully tested) and the stray typed `\t` is swallowed. Tab on a multi-line selection indents every line; Shift+Tab outdents the selected lines (or the caret's line). The key wiring itself lives in the untested UI layer, so smoke-check it: with 2-space indentation press Tab (exactly two spaces, no tab character), select several lines and press Tab, then Shift+Tab.
- **File tree.** The project tree was read-only. Right-clicking a row now offers *New File...*, *New Folder...* and *Delete...* (right-clicking empty sidebar space adds to the project folder; the Delete key also deletes the selected row). The rules live in `io/ProjectFiles` and are fully tested: creating never overwrites, names with path separators or `..` are refused, deleting refuses the project folder and anything outside it (including through a symbolic link), and a link is removed without touching what it points at. The menu wiring lives in the untested UI layer, so smoke-check it: right-click a folder and make a file (it opens and the tree keeps its other folders expanded); right-click a file and make a folder (it appears beside the file); try an existing name and `a/b` (an error, then the dialog returns with what you typed); delete a folder with a modified, open file inside (the dialog counts the contents and warns about the unsaved tab; Cancel changes nothing); delete it for real (the tab closes, the tree updates); right-click empty sidebar space and add a file to the project folder.
- **File tree: rename and move.** Right-click a row > *Rename...* (or press F2), or drag a row onto a folder (onto a file means the folder it is in). The rules live in `io/ProjectFiles` (`rename`, `moveInto`, `move`, `relocated`) and are tested: nothing is ever overwritten (a letter-case-only change such as `readme.md` to `README.md` is allowed on case-insensitive file systems like macOS), names with `/`, `\\` or `..` are refused, the project folder and anything outside it are refused as source or destination (including through a symbolic link), a folder cannot move into itself, and a link is moved without touching what it points at. Open tabs inside a renamed or moved entry follow it (title, language server, highlighting). The wiring is in the untested UI layer, so smoke-check it: rename an open, modified file (the tab shows the new name, keeps its text and stays modified); rename a folder holding open files; rename `a.txt` to `a.java` and see highlighting switch on; try an existing name and `a/b` (an error, then the dialog returns with what you typed); drag a file onto a folder, onto another file, and onto the folder it is already in (nothing happens); drag a folder onto its own subfolder (refused); try a name differing only in case on macOS.

- **Tools found when launched from Applications.** `ShellEnvironment` asks the login shell for its environment and `ProcessManager` and the language servers apply it (before this, `ShellEnvironment` existed but nothing called it, so a Finder-launched app ran with launchd's `/usr/bin:/bin:/usr/sbin:/sbin`). The chain is tested with a fake login shell (`FinderLaunchEnvironmentE2ETest`); smoke-check it on a Mac: quit every Javelin window, open *Javelin IDE* from Applications (not `javelin` in a terminal), open a Maven project, run `which mvn java git` and `echo $JAVA_HOME` in the terminal tab and run Build. All should resolve exactly as in Terminal.app. Then add a line to `~/.zprofile` (e.g. `export JAVELIN_TEST=1`), restart the IDE and confirm `echo $JAVELIN_TEST` prints `1`.

## Known limits

- The JavaFX interface (`App`, `ui/*`) has no automated tests. It was checked by launching the real application under a virtual display, confirming it starts, shows the crash-recovery prompt for a seeded snapshot, and runs with no network. The coverage gate excludes it deliberately; extracting more logic out of `App` into the tested packages is the way to grow the gate.
- Branch coverage of the core is about 92%; the remaining branches are mostly defensive error paths and platform switches.
- Tests that need a POSIX shell skip themselves on Windows. Windows and macOS have not been run; the macOS `ps` fallback for zombie detection is exercised on Linux through an injected launcher.
- Recovery restores text only, not cursor position, undo history or tab order.
- The transitive dependency tree is not audited by a test. Run `mvn dependency:tree` after any dependency change and review new entries.
