---
name: media
title: Working with images
description: Working with images through another model — generating, editing and combining pictures from a prompt, plus "vision" over a file; the result comes back as an agf_ file and is attached to the reply.
connectors: [media]
---

# Skill: AgiMate Media

The media connector gives you abilities your own model may not have: draw a picture (`gen_image`), change an existing one (`edit_image`), build one out of several (`combine_images`), look at an image (`read_image`). Under the hood each tool calls a separate model, picked automatically.

Files travel **by reference** as `agf_…`: you are a courier of references (took an id from one tool, passed it to another or attached it to a reply) — the bytes never pass through you.

## Tools: what to call when

- `gen_image(prompt)` — a new picture. The prompt is your job: fold every detail of the request into it (subject, style, composition, colours, text in the image) — the model sees nothing else.
- `edit_image(fileId, prompt)` — a new picture **based on** an existing one: both fixes (background, style, remove/add an object) and "draw the same but…" / "in this style". The original is untouched; a new file comes back. For feedback-driven fixes prefer this over generating from scratch — you won't lose what already worked.
- `combine_images(fileIds, prompt)` — one picture out of 2–4 sources: a person from one photo in a scene from another, a product on a background, blended styles. The model sees the images **in list order** — refer to them that way in the prompt ("take the person from image 1, the background from image 2"), otherwise it won't know what to take from where.
- `read_image(fileId, question?)` — describe an image or answer a question about it: files from history, screenshots. **Not needed** if the system prompt tells you that you can see attached pictures yourself.

The response from `gen_image`/`edit_image`/`combine_images` is `{ file: { id: "agf_…" }, text }`. If `file` is absent, the model **refused** (the reason is in `text`): relay it to the user instead of repeating the request as-is.

## How to hand the picture to the user

Put a marker in your final reply: `Done: [[attach:agf_…]]` — the marker is stripped from the text and the file goes out as an attachment. **Without the marker the user never sees the picture** — an id on its own sends nothing.

## Iteration discipline: design is a dialogue, not an inner loop

On creative tasks only the user holds the "done" criterion, so:

- **At most 2–3 generations per user request.** After that, show the best of what you have (`[[attach:…]]`, several options is fine) and ask for a direction.
- **Don't inspect every generation of yours through `read_image`.** The user will see the attachment and judge it themselves. Inspection is justified only for a specific, factually checkable doubt (is the text in the image legible, how many objects are there) — and no more than once per iteration.
- **Two failed edits of the same picture in a row is a stop signal**, not a reason to "strengthen the prompt": show the user what you have, describe how it diverges from the intent, offer options. Often it's a limitation of the generative model, and repetition doesn't cure it.
- A run that exhausts its step budget loses everything: an imperfect result the user saw always beats a perfect one they didn't.

## Important

- **Files expire**: an old `agf_…` from distant history may come back as "file not found" — generate it again.
- **The "no model capable…" error** means no suitable model is configured: suggest adding a provider with an image/vision model, or setting one on the agent explicitly.
- **Not every image model can combine.** If one of the source pictures is clearly ignored in the `combine_images` result, that's a model limitation rather than a prompt problem: tell the user after one retry instead of grinding on.
- Generation can take up to several minutes — don't re-call the tool before the result arrives.
