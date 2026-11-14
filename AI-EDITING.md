# AI editing: making a model's change fit the file

Why a local model's "refactor this" often does not splice in cleanly, what Javelin does about it, and whether Git should
take over the job. Written for the maintainer; the rules every AI feature follows are in [AI.md](AI.md).

## What goes wrong

A model asked to rewrite code is asked to do three jobs at once: decide the change, copy everything that stays, and
format it so it fits the file. Small models fail at the second and third far more than at the first. The failures seen
in practice, and what Javelin does about each:

| Failure | What it looks like | What Javelin does |
| --- | --- | --- |
| Lost indentation | The code lands flush left in the middle of a method | Propose Change sends whole lines (so the model sees the indentation); if the reply starts unindented, the indentation of the selection is put back (`AiContext`, `ChangeReply.restoreIndentation`) |
| Elision | `// ... rest of the method` stands where code was, and Apply deletes the code | `ChangeChecker` reports a placeholder as an error |
| Truncation or dropped lines | Half the method is gone, or a closing brace | Bracket balance (outside strings and comments), Java syntax, and "much shorter" are checked before the review |
| Rewriting what should not change | A long copy that quietly alters unrelated lines | Long selections ask for edit blocks: the model writes only the lines that change |
| Wrong line numbers in a diff | `git apply` rejects it | A diff is applied by its content, line numbers are only a hint (`UnifiedPatch`) |
| Reasoning mixed into the reply | Code tried out while "thinking" is taken as the answer | `<think>` sections are dropped before the reply is read |
| Quoting a long run just to delete it | A SEARCH part that copies 250 lines costs more output than the model has left, and one wrong line in it matches nowhere | A RANGE block names only the first and last line (`FROM:` / `TO:`, `WHICH:` when the code repeats itself); the prompt says never to copy more than a few lines into a SEARCH part (`EditBlocks`) |
| Reply cut off in the middle of a block | The window was spent on thinking and the long quote, so the last block never closes and nothing is proposed | The window leaves room for thinking (`OllamaProvider.thinkingReserve`); a cut-off reply is asked again once with twice the window (`AiService.withMoreRoom`) |
| Our own scaffolding | `===== BEGIN CODE`, line numbers or SEARCH markers inside the code | Reported as an error |

## What the research says (and does not)

I could not run Qwen 3.5 9B here, so nothing below is a measurement of your model. What is published:

- Search/replace edits (the form used here) did best for stronger models in a benchmark of code-diff formats, with
  structured unified-diff variants close behind; for smaller open models, no format helped much
  ([Diff-XYZ, arXiv 2510.12487](https://arxiv.org/abs/2510.12487)).
- Another study found whole-file output and search/replace about equal in success and token use for direct code
  editing, and noted that scoped formats pay a "context tax": the model has to write the old code as an anchor
  ([Cascaded Code Editing, arXiv 2604.19201](https://arxiv.org/pdf/2604.19201)).
- Aider moved GPT-4 Turbo to unified diffs because that reduced "lazy" coding (placeholders in place of code), and keeps a
  different edit format per model ([aider edit formats](https://aider.chat/docs/more/edit-formats.html)).

So no format is a cure for a 9B model. That is why Javelin does not bet on one: it **accepts all three** (edit blocks, a
diff, a whole block) whatever the prompt asked for, **applies tolerantly** (by content, not by position), and **checks the
result** before you see it. The one choice it makes for the model is when to ask for edit blocks (`ai.editBlockMinLines`,
default 40 lines); that is a guess to be measured, not a result.

## Should Git do this instead?

Not instead; Git is the wrong tool for the first step and a good one for later steps.

- **`git apply` and `patch` are strict.** They need correct `@@ -a,b +c,d @@` counts and exact context. Models write those
  numbers badly, so a model-written diff that `git apply` refuses is common. A tolerant applier (`UnifiedPatch`) that finds a
  hunk by its content does better for a single open file, and it is JavaFX-free and tested.
- **Git adds a safety net, which an editor buffer already has in part.** Applying a change is an ordinary edit, unsaved,
  and Undo takes it back. What Git would add is protection once files are saved: a checkpoint before a multi-file change,
  and `git diff` to review what really changed on disk.
- **A real "Apply Patch..." command is worth having, but it is a separate feature.** It would apply a `.patch` file (the kind
  delivered with this project) to the project: `git apply --check` first, then apply, with `UnifiedPatch` as the fallback
  when the patch is a model's. It needs Git integration, which does not exist yet (see BUGS.md), and the rules in AI.md
  (explicit action, review first).

## Next steps, in the order I would do them

1. **Measure.** A small harness: 20 to 30 refactor tasks on real files, each run with the model in each of the three
   formats, recording whether the reply was understood, applied, passed the checks and compiled. Without numbers, the
   edit-block limit is a guess. This needs your Ollama and model, so it is not in this change.
2. **Ask again with the problems.** When a check reports an error, offer "Ask again" that sends the model its own reply
   and the findings. It must go through the same preview and Send click as any request (AI.md: per-action permission).
3. **Apply Patch...** from a file or the clipboard, as described above.
4. **Compile or test as a check.** Running the project's own build command on the result is the strongest check, but it
   executes code, so it would be an explicit, separate step with its own confirmation.
