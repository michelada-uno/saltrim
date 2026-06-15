# SaltRim

A simple-but-powerful collaborative spreadsheet: a reactive **Clojure** engine
(on [Spindel](https://github.com/replikativ/spindel)) with a hypermedia
**[Datastar](https://data-star.dev)** UI (server HTML over SSE), file
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

Examples:

```clojure
=(+ #cell A1 #cell B1)        ; sum two cells
=(reduce + #cells A1:A3)      ; sum a range
=(if (> #cell A1 0) "ok" "no")
```

Formulas that depend on other cells recompute automatically when those cells
change. Circular references are rejected. Errors show as `#ERR` in the cell and
a toast message describing what went wrong.

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
the minimum to reset toward the default.

### Navigation

- **Click** a cell to select it; **double-click** or **Enter** to edit.
- **Arrows** / **Tab** move the selection; **Esc** cancels an edit.
- The address box (e.g. `A1`) jumps to a cell.

### Sharing & collaboration

Owners get a link/lock button in the top bar to share a sheet by **capability
link** (an unguessable URL, rotatable) or with **specific people**, at view or
edit level. Multiple people can edit the same sheet at once — you'll see each
other's cursors and edit locks live.

## Running & development

```bash
clojure -M:web        # dev server on http://localhost:8080  (open ?s=<sheet>)
clojure -X:test       # engine / format / store / auth test suites
```

Architecture and engine internals are documented in
[`SPEC.md`](SPEC.md); contributor conventions and gotchas live in
[`CLAUDE.md`](CLAUDE.md).
