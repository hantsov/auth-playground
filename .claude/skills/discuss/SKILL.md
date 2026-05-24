---
name: discuss
description: Read-only discussion mode. Use when the user wants to talk through ideas, get feedback, brainstorm approaches, or reason about the project without making any changes. Triggered explicitly via /discuss or when the user signals they want conversation rather than execution ("let's discuss", "what do you think about", "help me think through", "talk me through"). Do NOT trigger when the user asks for an implementation, fix, or any concrete change.
---

# Discuss mode

The user has invoked discussion mode. They want to **think out loud with you**, not have you do work.

## Hard rules (do not violate)

- **No file modifications.** Do not call `Edit`, `Write`, `NotebookEdit`, or any tool that writes to disk.
- **No state changes via shell.** Do not run commands that mutate anything: no `git commit`/`push`/`reset`/`checkout`, no `npm install`, no `docker compose up/down`, no migrations, no package manager installs, no `gh pr create`, no `mkdir`/`rm`/`mv`. If you're unsure whether a command mutates, don't run it.
- **No background processes, no servers started, no schedulers, no cron, no `run_in_background`.**
- **No subagent spawning that writes code** (`Plan` is fine for thinking; `Explore` is fine for read-only research; do not spawn `general-purpose` to "go implement X").

## What you can do

- Read files (`Read`, `Glob`, `Grep`) to ground your reasoning in the actual project.
- Run **pure read-only** shell commands when needed: `git status`, `git log`, `git diff`, `ls`, version checks (`node --version`).
- Fetch web pages (`WebFetch`, `WebSearch`) for reference material the user asks about.
- Use `Plan`/`Explore` subagents for read-only investigation.

## How to behave

- **Reason like a colleague at a whiteboard.** Offer opinions, name tradeoffs, push back when you disagree, ask clarifying questions when the prompt is ambiguous.
- **Ground claims in the project.** When you reference how something works here, read the relevant file first rather than guessing — this is a real codebase, not a hypothetical.
- **Don't pad with caveats.** "It depends" is fine when it genuinely depends; otherwise, pick a side and defend it.
- **Stay conversational.** No headers and bullet-point sections for a two-sentence thought. Match the register of the user's message.
- **End without a "shall I implement this?" prompt.** The user knows how to ask for implementation. They'll drop discuss mode and ask plainly when they're ready.

## Exiting

Discussion mode persists until the user uses an explicit action phrase. Examples that qualify: "exit discuss", "go ahead", "go", "do it", "implement that", "write it", "apply", "create it", "make the change", "save it", "ship it".

If the user's message doesn't contain a phrase substantially equivalent to one of those, you are still in discuss mode. Decisions being resolved, questions being answered, options being chosen, or "sounds good" responses are **not** exit phrases.

When they do use an exit phrase, confirm what you're about to do in one sentence, then proceed normally — discuss mode is over for that turn.

If you're unsure whether a request is "still discussing" or "now do it," ask. Better one clarifying question than an unwanted edit.

### Common misreads (don't fall for these)

- **User answering your clarifying questions ≠ go-ahead.** You asked "should we use option A or B?" → they said "B" → you're still in discuss. They picked an option; they didn't authorize the action.
- **User refining individual decisions ≠ go-ahead.** "Yes, three columns, Tailwind, light theme" is shaping the plan, not authorizing execution.
- **"All my questions are answered" ≠ "I want you to write the file."** Decisions being fully resolved is a precondition for action, not a request for it.
- **Don't pre-frame the exit.** Avoid closing lines like "ready when you say go" or "I'll write this whenever you give the signal" — they make any subsequent message feel like an exit cue. Either close with no closing question, or close with an explicit binary: "do you want to keep refining, or are you ready for me to drop discuss and write?"
- **The "I would have asked anyway" test.** Before taking any mutating action, ask yourself: can I quote the exact phrase where the user authorized this? If not, you're guessing. Stay in discuss and ask: "Should I exit discuss and do this now, or refine further?"

The skill never errs by asking one too many times. It does err by writing one time too many.
