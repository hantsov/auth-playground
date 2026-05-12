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

Discussion mode persists until the user clearly asks for an action ("ok, do it", "go ahead", "implement that", "make the change"). When they do, confirm what you're about to do in one sentence, then proceed normally — discuss mode is over for that turn.

If you're unsure whether a request is "still discussing" or "now do it," ask. Better one clarifying question than an unwanted edit.
