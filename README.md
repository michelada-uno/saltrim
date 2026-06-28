# SaltRim

A simple-but-powerful collaborative spreadsheet: a reactive **Clojure** engine
(on [Spindel](https://github.com/replikativ/spindel)) with a hypermedia
**[Datastar](https://data-star.dev)** UI (server HTML over SSE), Datahike
persistence, authentication, sharing, and live multi-user editing.

The same guide is available in the app itself — click the **?** button in the
top toolbar.

## User guide

### Cells & formulas

Type a value into a cell, or start with `=` to write a formula. Formulas are
restricted **Clojure s-expressions** (not infix). Reference other cells with
reader tags:

| Tag | Meaning |
|-----|---------|
| `#cell A1` | the value of A1 |
| `#cells A1:A3` | a vector of a column range `[A1 A2 A3]` |
| `#cells A1:C1` | a row range `[A1 B1 C1]` |
| `#cells A1:B2` | a rectangle, row-major `[A1 B1 A2 B2]` |

Or use the shorter `$` form — `$A1` is the same as `#cell A1`, and `$A3:D8` the
same as `#cells A3:D8` (it's just shorthand; it shifts on paste like any other
reference).

**Relative references** point to a cell by *offset from the cell itself*, written
`$<col><row>` where each of col/row is `_` (same index), `+N`, or `-N`. They are
resolved per cell, so they **survive copy/paste unchanged** — copy one down or
across and each copy points relative to its own position. That makes series
trivial:

```clojure
; B2 = 1, then B3 = =(inc $_-1) copied down B4:B11  ->  B2:B11 = 1,2,…,10
=(inc $_-1)                  ; "the cell one row up, same column"
; A1 = 0, B1 = 1, then C1 = =(+ $-2_ $-1_) copied right  ->  0,1,1,2,3,5,8,…
=(+ $-2_ $-1_)               ; "two cols left + one col left, same row"
```

Examples:

```clojure
=(+ #cell A1 #cell B1)        ; sum two cells
=(+ $A1 $B1)                  ; the same, shorter
=(reduce + #cells A1:A3)      ; sum a range
=(sum $A1:A3)                 ; the same, shorter
=(if (> $A1 0) "ok" "no")
```

Formulas that depend on other cells recompute automatically when those cells
change. Circular references are rejected. Errors show as `#ERR` in the cell and
a toast message describing what went wrong.

A **stdlib** is available bare in every formula: math (`sum`, `product`, `round`,
`sqrt`, `pow`, `sign`, …), stats (`mean`/`avg`, `median`, `variance`, `stdev`),
text (`upper`, `lower`, `trim`, `join`, `split`, `str-replace`, `includes?`, …),
and date over ISO `yyyy-MM-dd` strings (`today`, `year`, `month`, `day`,
`days-between`).

### Reusable functions (the `ƒ` library)

The `ƒ` button (top bar) opens this sheet's **definitions library**: your own
functions and constants, kept as separate entries, callable from any cell. They
run in the same sandbox as formulas (pure, no host interop) and are saved with
the sheet.

```clojure
;; one entry:
(defn margin [rev cost] (/ (- rev cost) rev))
;; another entry:
(def vat 1.16)
```

```clojure
;; then in cells:
=(margin #cell A1 #cell B1)
=(* #cell A1 vat)
```

Each entry collapses to **badges** of the names it declares plus its last-edit
time; **Edit** expands it into a textarea, and **⤢** opens a full-size editor.
While one collaborator is editing an entry it is **locked** for everyone else
(their view shows a lock badge). All entries merge, in order, into the sheet's
program; **Save** recompiles every cell against it (for you and any
collaborators). The built-in functions (above) are shown read-only.

The same **⤢ big editor** sits next to the formula bar and the style bar, for
composing longer formulas or style expressions in a roomy modal.

### Styling a cell

The third toolbar row styles the **selected** cell. Pick a property, type a
value (or an `=`-formula), and press **Apply** (or Enter). Inside a style
formula, `$val` is the selected cell's own computed value — so styling can react
to the data:

```clojure
=(if (> $val 100) "tomato" "white")   ; bg: red when above 100
```

| Property | Controls | Example values |
|----------|----------|----------------|
| `bg` | background color | `tomato`, `#eef`, an `=`-formula |
| `fg` | text color | `navy`, `#333` |
| `weight` | font weight | `bold`, `600` |
| `slant` | font style | `italic` |
| `align` | text alignment | `left`, `right`, `center` |

Style formulas are reactive too: a style that reads another cell updates when
that cell changes. A broken style formula is reported in the toast and simply
isn't applied.

**Styling a whole selection:** select a range (or several), then apply a property
— it sets that property on every cell in the selection at once.

### Insert rows & columns

In the format row (**🎨**), the **insert** buttons add a blank row or column next
to the selected cell — **⤒ row** above, **⤓ row** below, **⇤ col** left, **⇥ col**
right. Existing cells shift out of the way and **formula references follow the
shift** (a range that the new line falls inside grows to include it). An insert
is a single **Undo** step (Ctrl/⌘+Z puts everything back).

### Number format

The `format` property applies a display **mask** to a cell's numeric value
(text is left untouched):

| Mask | input → output | |
|------|----------------|---|
| `0.00` | `1234.5` → `1234.50` | fixed decimals |
| `#,##0` | `1234567` → `1,234,567` | thousands grouping |
| `$#,##0.00` | `1234.5` → `$1,234.50` | literal prefix/suffix |
| `0.0%` | `0.25` → `25.0%` | percent (scales ×100) |

Tokens: `0` required digit · `#` optional digit · `.` decimal point · `,`
thousands grouping · `%` scale by 100 and append `%`. Any other characters are
literal text.

### Column & row size

Drag the trailing edge of a **column header**, or the bottom edge of a **row
number**, to resize it. Sizes are saved with the sheet. Drag back to (or past)
the minimum to reset toward the default. Dragging **snaps** to multiples of the
sheet default (1×, 2×, 3×…) — hold **Alt** to size freely.

If you own the sheet, the **⚙ Sheet properties** panel (top bar) sets the
sheet-wide default column width and row height.

### Navigation

- **Click** a cell to select it; **double-click** or **Enter** to edit.
- **Arrows** / **Tab** move the selection; **Esc** cancels an edit.
- The address box (e.g. `A1`) jumps to a cell.

### Selecting ranges

- **Shift+click** or **Shift+arrows** extends a rectangular range.
- **Ctrl/⌘+click** adds another range (multi-range selection).
- **Delete** / **Backspace** clears the selected cells (undoable).

### Copy / cut / paste

- **Ctrl/⌘+C** copy · **Ctrl/⌘+X** cut · **Ctrl/⌘+V** paste at the selected cell.
- Pasted **formulas shift their references** relative to the move — copy
  `=(+ #cell A1 1)` down a row and it pastes `=(+ #cell A2 1)`.

### Undo / redo

- **Ctrl/⌘+Z** undoes your last edit; **Ctrl/⌘+Shift+Z** (or **Ctrl+Y**) redoes.
- Undo is **per-user**: it only rolls back *your own* edits, and a cell a
  collaborator changed after you is left untouched.

### Branches

A **branch** is a parallel version of a sheet you can edit independently — like
git, for spreadsheets.

- The **🌿 picker** in the top bar switches branches (the address bar gains
  `&b=<branch>`). Every sheet starts on `main`.
- People working on **different branches don't see each other's cells** — each
  branch is its own live, collaborative copy.
- The owner's **⑂ button** opens a small panel to **fork** the current branch
  into a new one (it starts as an exact copy, then the two diverge), **delete**
  a non-main branch, or **merge** another branch into this one.
- **Merge** is a 3-way merge against the point the branches diverged: changes
  that only one side made are merged automatically; where both sides changed the
  same cell, you get a **conflict list** — tick the ones you want to take from
  the other branch (unticked keeps your current version), then Apply.

### History (time-travel)

The **🕘 button** opens a list of past revisions of the current branch. Pick one
to view the sheet **as it was** at that moment — a read-only snapshot you can
scroll around. A banner shows the timestamp; **Back to live** returns you to the
current sheet. (Editing is disabled while viewing history.)

### Dependency graph

The **🕸** button opens a diagram of how cells feed each other: an arrow points
from a cell to the cells whose formulas read it, laid out left-to-right by
dependency depth. Click a node to select that cell.

To make nodes readable, give a cell a **label**: open the format row (**🎨**),
pick `label` in the property dropdown, and type a name (e.g. `revenue`). The
graph then shows the name instead of the address (`A1`). Labels are display-only
for now (you still reference cells by address / `$A1` in formulas).

> On large real-world tables the graph gets dense — it's intentionally a simple
> first version (capped, basic layout); zoom/filtering are future polish.

### Sharing & collaboration

Owners get a link/lock button in the top bar to share a sheet by **capability
link** (an unguessable URL, rotatable) or with **specific people**, at view or
edit level. Multiple people can edit the same sheet at once — you'll see each
other's cursors and edit locks live.

### Export to Excel

The **⬇ xlsx** button (top bar) downloads the sheet as an `.xlsx` file. It is a
**static snapshot**: every cell exports its current **computed value**, carrying
its styling (fill, font colour, bold/italic, alignment) and number format — but
**not** its formula. SaltRim formulas are Clojure expressions, not Excel syntax,
so the exported file has **no live formulas and no reactivity**: changing a value
in Excel won't recompute anything. Each formula's original source is attached as
a **cell comment** so the logic isn't lost. The export respects what you're
viewing — the current branch, or a read-only history snapshot.

## Running & development

```bash
clojure -M:web        # dev server on http://localhost:8080  (open ?s=<sheet>)
clojure -X:test       # engine / format / store / auth test suites
clojure -T:build cljs # compile the ClojureScript client -> resources/public/app.js
clojure -T:build uber # standalone uberjar (compiles the client first)
```

The browser client is **ClojureScript** (`src/.../app.cljs`, compiled with the
plain CLJS compiler — no node/npm). The compiled `resources/public/app.js` is a
build artifact (gitignored). The preferred dev loop is the nREPL
(`clojure -M:nrepl --port 7888` then `(start)`), which watch-compiles `app.js`
on every save; before a bare `clojure -M:web` on a fresh checkout, run
`clojure -T:build cljs` once to produce it.

Architecture and engine internals are documented in
[`SPEC.md`](SPEC.md); contributor conventions and gotchas live in
[`CLAUDE.md`](CLAUDE.md).
