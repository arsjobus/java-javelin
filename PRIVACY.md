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

## Language servers

Javelin IDE itself sends nothing over the network for language features. It talks to a language server over that process's stdin/stdout, and only after the user has chosen to start one. What the server does beyond that (for example downloading indexes) is up to the server the user selected.

## Diagnostics

Crash reports and diagnostic collection should be opt-in.

## Credentials

Credentials should not be stored in project files. The editor should avoid becoming a credential manager.
