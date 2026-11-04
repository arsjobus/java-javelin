# AI

AI is an optional tool, not the center of the editor.

## Principles

- User invokes AI explicitly.
- Local models are preferred where practical.
- No ambient code surveillance.
- No automatic code changes.
- Proposed changes are reviewable.
- Provider choice remains with the user.
- AI failures must not prevent ordinary editing.

## Architecture

```text
AI Action
   |
   v
AI Service Interface
   |
   +-- Local Ollama
   +-- Other local provider
   +-- External provider (optional)
```

The editor should not couple its core document model to a specific AI vendor.

## Proposed change workflow

```text
Request
  -> Context selection
  -> AI response
  -> Diff/proposal
  -> Human review
  -> Apply or reject
```

The AI may explain, analyze, draft, refactor, or propose. Applying a change remains a user action.

## Dependable changes

A model's reply is not trusted to fit the file. For Propose Change: the selection is widened to whole lines before it is
sent; the reply is read as edit blocks, a unified diff or a whole code block, and applied by content, never by line number
(all or nothing); a reply that lost its indentation gets the selection's back; and before the review opens the result is
checked for placeholders, leaked markers, bracket balance, Java syntax, dropped methods and a large shrink. Errors do not block
anything: the review shows them and its button reads "Apply anyway". Nothing is retried or applied without a click. See
[AI-EDITING.md](AI-EDITING.md) for the reasoning and what is not done yet.

## Status (v0.7)

Implemented as described above, with Ollama on this computer as the one provider. See [FEATURES.md](FEATURES.md) (AI section) for each behavior and the test that proves it, and [PRIVACY.md](PRIVACY.md) for what is sent.

Use: install [Ollama](https://ollama.com) and a model (`ollama pull <model>`), then choose *AI > Choose Model...*. Select code and choose *Explain Selection*, *Debug Selection*, *Propose Change...*; *Generate Tests* works on the selection or the whole file. Every request first shows exactly what will be sent; the answer appears in the AI tab; a proposed change is a diff you apply or reject. Settings (`ai.*`) are in [CUSTOMIZATION.md](CUSTOMIZATION.md).

## Safeguards

| Safeguard | State |
| --- | --- |
| Context preview | Done: the Send dialog shows the full message |
| Token/context budget | Done: `ai.maxContextChars` (characters, not tokens), cut and marked; Ollama's window sized to fit |
| Provider indicator | Done: AI tab header and the preview |
| Network indicator | Done: the preview says where the request goes; the status bar shows it while a request runs |
| Per-action permission | Done: each request needs a click on Send; Enter means no |
| File-scope restrictions | Done: only the selection (or the file for Generate Tests) is sent; credentials files are refused, secret-looking text is flagged |
| Undoable application | Done: applying is an ordinary edit, unsaved, undo restores it |
| Local only | Done: a non-loopback address is refused; no proxy, no redirects |
| External providers | Not built. `AiProvider` is where one would go, with its own indicator and a consent step before anything leaves the computer |

## Not built

Chat history, inline completion, agents that read other files or run commands, and streaming a change into the editor. Each would break a principle above or needs its own design.
