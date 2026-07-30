---
name: sheets
title: Sheets and charts
description: Keeping the user's tabular data — sheets with a declared schema, filters and summaries over any column, PNG charts, import and export as xlsx/csv.
connectors: [sheets]
---

# Skill: sheets

You keep the user's tabular data: expenses, readings, measurements, sales — anything that naturally falls into rows and columns. Your sheets are private: only you can see them.

A sheet's schema is declared up front — every column has a machine name, a human-readable title, a type (`number`, `text`, `date`, `bool`) and a unit. **The column names from the `sheets` block in your context (or from `list_sheets`) are the only ones the other tools accept.**

## Tools: what to call when

- `list_sheets` — which sheets I have and with which columns. The schema already arrives as the `sheets` block in your context, so call this only if the block is missing or you suspect it's stale.
- `create_sheet` — a new sheet. Create it **once**, then write into it.
- `add_columns` — you need another column. This, rather than a second sheet about the same thing.
- `add_rows` — write data. One row = one observation or entry.
- `query` — read rows with a filter and sorting. **Returns row `id`s**, without which `update_rows` and `delete_rows` don't work.
- `aggregate` — sums, averages, minimums, breakdowns by category and by period.
- `render_chart` — a PNG picture from the data.
- `export` — hand the user a real file (`csv` or `xlsx`).
- `import_file` — the user sent their own sheet as a file.
- `update_rows` / `delete_rows` — edit and delete by `id`.
- `delete_sheet` — delete a whole sheet. Irreversible, **ask the user first**.

## The one rule: don't compute yourself

Sums, averages, shares, "how much this month", "what's the biggest" — **always through `aggregate`**, even when there are three rows and adding them up in your head looks easier. You get arithmetic wrong more often than you think, and the price of a mistake in money or health is high. The tool returns a number; quote that number.

```
aggregate(sheet: "budget", metrics: [{"func":"sum","column":"amount"}],
          groupBy: "category",
          filter: [{"column":"date","op":"between","values":["2026-07-01","2026-07-31"]}])
```

A breakdown by category is `groupBy` on a text column. A report by period is `groupBy` on a date column plus `bucket` (`day`, `week`, `month`, `year`).

## Filters

Conditions are joined with AND. `op`: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `contains`, `in`, `between`, `is_null`, `not_null`. Scalar operations take the value in `value`; `in` and `between` take a list in `values`.

## Writing

Write **everything the user dictated in a single** `add_rows` call — up to 500 rows at a time. Don't call the tool per row: half the data gets lost on the way.

A missing column is simply an empty cell, and that's fine. Numbers are accepted both as `1200` and as `"1 200.50"`; dates as `2026-07-24`, `2026-07-24T08:30` or `24.07.2026`.

## Charts

**You cannot see the picture you built.** Along with the file, the tool returns a `summary` — count, minimum, maximum, average and sum per series. Comment on the chart **only from those numbers**, never from your memory of what you sent into it.

For the user to see the picture, put `[[attach:agf_…]]` in your reply text — without the marker the file never arrives.

```
render_chart(sheet: "pressure", x: "date", y: ["sys","dia"])              → a trend
render_chart(sheet: "budget", x: "category", y: ["amount"], aggregate: "sum", type: "pie")  → shares
```

`aggregate` in a chart groups by `x` first and then draws. For `pie` it is required.

## Files

- The user asks to "export it", "send me the table", "I'll forward it to my accountant" → `export`. `csv` opens in Excel; use `xlsx` when they specifically want an Excel file.
- The user sent a sheet as a file → `import_file`. The first row of the file is treated as headers, and column types are inferred from the data. Don't interrogate them about the schema — import first, then show what came out.

The result of `export`/`import_file` is also attached with the `[[attach:agf_…]]` marker.

## Patterns

The first entry on a new topic:
```
create_sheet(name: "pressure", title: "Blood pressure",
             columns: [{"name":"date","title":"Date","type":"date","unit":""},
                       {"name":"sys","title":"Systolic","type":"number","unit":"mmHg"},
                       {"name":"dia","title":"Diastolic","type":"number","unit":"mmHg"}])
→ add_rows(...)
```

Fixing the user's mistake ("not 180, it was 130"): `query` with a filter → take the `id` of the row → `update_rows`.

A monthly report: `aggregate` (the numbers) → `render_chart` (the picture) → a reply with the numbers from `aggregate` and an `[[attach:]]` marker.

## Important

- **One topic, one sheet.** Before creating anything, look at the `sheets` block: if a suitable sheet exists, write into it; if a column is missing, `add_columns`.
- If a tool answers with an error listing the existing sheets or columns, that's a hint rather than a dead end: take the right name from it and retry.
- `query` returns at most 500 rows. If `truncated: true` comes back, narrow the filter or move to `aggregate` instead of dumping everything.
- Don't invent `agf_` references: only ids returned by tools.
- Deleting a sheet or rows is irreversible — confirm with the user.
