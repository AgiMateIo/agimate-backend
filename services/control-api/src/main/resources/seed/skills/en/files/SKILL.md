---
name: files
title: Documents and files
description: Writing text files and keeping them up to date — reports, notes, HTML pages, CSV and JSON; editing an existing file in place, reading it by lines, finding a file from the conversation; the file goes to the user as an attachment.
disclosure: lazy
connectors: [files]
category: platform
tags: [writing]
---

# Skill: files

The files connector lets you write a text file once and keep working on it: a report the user will download, a draft you refine over several messages, an HTML page, a table as CSV. A file is addressed by its id `agf_…`; the id never changes, and every write becomes a new version under it.

## Tools: what to call when

- `save_file(content, name)` — a new file. The name decides the type: `.html`, `.md`, `.csv`, `.json`, anything else is plain text. Put the **whole** text into `content` — nothing is appended for you.
- `save_file(content, fileId, name?)` — replace the whole text of an existing file with a new version. Use it when most of the text changes; `name` renames the file.
- `edit_file(fileId, edits)` — change part of a file: `[{oldText, newText}]`, applied in order. `oldText` is copied from the file **exactly** — whitespace and line breaks included — and must occur **once**; if it occurs twice, take more surrounding text. A refused edit changes nothing.
- `read_file(fileId, offset?, limit?)` — read a text file by lines. A long file comes in windows: continue with `offset = nextOffset` until it is `null`. For pictures use `read_image` from the media skill.
- `list_files(allConversations?, name?)` — find a file when its id is no longer in front of you: by default within this conversation, `allConversations: true` — across the whole account.

`save_file` and `edit_file` answer `{ file: { id, name, mime, size, version } }`.

## How to work with a file

- **Read before you edit.** `edit_file` works on the current text, not on what you remember writing: after a few edits, or when the user may have changed the file, read it first.
- **Small change — `edit_file`, large one — `save_file`.** Rewriting a long file to fix a paragraph wastes the step budget and risks losing text on the way.
- **"The file is being written by another call or has changed"** means someone else wrote it at the same moment: read it again and repeat your change on the new text. Don't overwrite blindly.
- The limit is 1 MB of text per write. For a table the user wants to analyse, the sheets skill fits better than a CSV file.

## How to hand the file to the user

Put a marker in your final reply: `The report is ready: [[attach:agf_…]]` — the marker is stripped from the text and the file goes out as an attachment. **Without the marker the user never sees the file.**

An attachment carries the version that was current when you replied: if you edit the file later, the earlier message still shows the earlier text — attach the file again to show the new one.

Not every conversation can take attachments: in a run with no chat (a scheduled task, a board event) or in an IDE session nothing will reach the user. There, say in words what you wrote and where to find it.

## Important

- **Files expire** after a while, and every write moves the date further out. An old `agf_…` may come back as "file not found".
- Binary files (images, PDF, archives) are neither read nor edited here — they can only be forwarded by id or attached.
