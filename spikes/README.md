# spikes/

REPL walkthroughs that prove the risky engine assumptions SaltRim is built on.

These are **not** application code and **not** on the classpath — they're
executable documentation. Each file is a narrative plus a `(comment …)` rich
block whose forms you evaluate **one at a time at a dev REPL**, reading the
inline `;; => expected` annotations as you go.

## How to run one

```bash
clojure -M:nrepl --port 7888      # dev REPL (auto-loads dev/user.clj)
```

Then open a spike file in your editor (or paste into the REPL) and evaluate the
forms inside its `(comment …)` block in order. Application namespaces
(`uno.michelada.saltrim.*`) are on the classpath, so the `require`s resolve; the
spike's own `ns` form just gives you a scratch namespace to work in.

Why REPL walkthroughs instead of `clojure -M:spike` mains? A cold JVM per proof
throws away the very thing Clojure is good at — a live image you poke at. Reading
a value, mutating a signal, and re-reading interactively is the point.

## The spikes

| File | Proves |
|------|--------|
| `00-signal-spin-lifecycle.clj` | Signals auto-propagate to dependent spins via the executor — no manual drain; spins are pull/lazy + cached. |
| `01-formula-string-to-spin.clj` | A formula *string* compiles to a reactive Spin wired by address, recomputes on change, and the whitelist sandbox rejects disallowed symbols. |
| `02-await-uniform-model.clj` | Every cell is a Spin; cross-cell refs use `await` (handles Spins, unlike `track`), enabling formula→formula cascades. |
| `03-sci-formula-eval.clj` | SCI can evaluate the *pure* user body while the `spin`/`await` wrapper stays host-compiled — the basis of the formula-engine → SCI migration. |
