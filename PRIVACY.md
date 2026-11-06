# Privacy

## Default position

Privacy is the default, not a premium mode.

The editor should not require:
- An account
- Cloud synchronization
- Telemetry
- Advertising
- Analytics
- Remote project storage

## Network

The application should not make unexpected network requests.

Features that require network access should be:
- Optional
- Explicit
- Documented
- Easy to disable

## AI

Local AI should be preferred where practical. External AI providers must never be silently selected.

Before sending source code externally, the user should know:
- What is being sent
- Which provider receives it
- What scope is being sent
- What action will occur

### What the AI menu sends (v0.7)

AI is off until you choose a command in the AI menu, and it only talks to [Ollama](https://ollama.com) on this computer (the address must be `localhost`, `127.x.y.z` or `[::1]`; anything else is refused, there is no proxy and no redirect is followed). Nothing is sent when a file is opened, while you type, or in the background.

Before each request a dialog shows the provider, the model, where the request goes, the scope (the selection, or the whole file for Generate Tests), the number of characters and the complete message. Nothing leaves the editor until you choose Send. The message holds the selected text, the file's name as shown in the project, its language, and a short task; it holds no other file, no settings and no earlier request. The answer is shown to you and is never applied without your review. The model that answers is whatever you installed: what it does with the text is up to it, and Ollama's own settings and logs are Ollama's.

Files made for credentials (`.env`, private keys, key stores, cloud credential files) are refused by name, and text that looks like a key or token is flagged in the preview. These checks catch the common cases only: read the preview. The `ai.*` settings are read from your user settings file only, so a project you open cannot choose the model, the address or the limits.

### What a project request sends (v0.9)

*AI > Project Request...* sends more than the AI commands above, so it asks more first. The Send dialog lists every project file that would go, why, and what it costs, and shows the complete message; nothing is sent until you choose Send, and you can untick any file. The files are chosen from the open project folder only. Never sent, whatever the request says: files `.gitignore` ignores (unless `ai.project.gitignore` is off), build output, caches and version-control folders, binary files, files over `ai.project.maxFileBytes`, anything in `ai.project.exclude`, symbolic links, and files made for credentials. Text inside a sent file that looks like a key or token is flagged in the preview; read it.

Before you reach the dialog, Javelin asks Ollama once per model for the model's largest context (`/api/show`); that request carries only the model's name. Answers are tied to a snapshot of what was sent. Files the AI proposes to change are written only after you approve them one by one, and only inside the project.

## Language servers

Javelin IDE itself sends nothing over the network for language features. It talks to a language server over that process's stdin/stdout, and only after the user has chosen to start one. What the server does beyond that (for example downloading indexes) is up to the server the user selected.

## Diagnostics

Crash reports and diagnostic collection should be opt-in.

## Credentials

Credentials should not be stored in project files. The editor should avoid becoming a credential manager.
