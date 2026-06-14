# Spindel research — branching, SCI, incremental, and what Clorax can use

Research pass over the full Spindel docs
(<https://github.com/replikativ/spindel/blob/main/docs/README.md>, all
sub-pages) + the `examples/` demos, done 2026-06-14 to inform two decisions:

1. **Git-like branching of spreadsheets** (sheets + DB data together).
2. Which Spindel concepts (infinite scroll, SCI, …) are worth adopting.

Clorax context that constrains the answers: we run Spindel **on the JVM for
computation only**, and render the **UI as server HTML over Datastar SSE** — we
do *not* use Spindel's ClojureScript incremental-DOM layer.

---

## 1. The headline: branch the DATA, not the engine

The canonical branching demo (`examples/versioned_editor_demo.cljs`,
"branching document with diff overlay") **does not use `fork-context`,
`snapshot`, or `serialize` at all.** Branches are plain data maps in signals;
the diff is hand-computed by block id:

```clojure
;; a branch = a copy of the document + the commit it diverged from
{:state (fork-edits head-state prompt) :base-commit head-idx ...}

;; diff = compare base vs overlay by id -> :unchanged | :edited | :added
(defn classify-blocks [prev overlay]
  (let [prev-by-id (into {} (map (juxt :id identity) prev))]
    (mapv (fn [o]
            (if-let [p (prev-by-id (:id o))]
              (if (= (:text p) (:text o))
                (assoc o :diff-kind :unchanged)
                (assoc o :diff-kind :edited :prev-text (:text p)))
              (assoc o :diff-kind :added)))
          overlay)))
;; removed blocks counted separately
```

**Takeaway for Clorax:** a sheet branch = a branch of the **source document**
(the `{addr {:value …}}` cell map), not a fork of the Spindel execution
context. Diff/merge are cell-level operations we own (3-way by address, exactly
like `classify-blocks` by id). Spindel just rebuilds its reactive graph from
whatever branch's source is loaded — **which Clorax already does on every sheet
load** (`store/load-sheet` → replay `set-cell!`). So branching is a **storage /
data-model concern**, and it maps straight onto Datahike.

### Why this resolves the "Datahike + Spindel branching simultaneously" question

- **Datahike** owns the version history: cells become datoms (`sheet`, `addr`,
  `value` …) under a branch; `branch!` forks a sheet, `as-of` reads history,
  diff = datom set difference, merge = our cell-level 3-way policy. DB metadata
  (users/shares) lives in the same store, so a branch can carry *both* sheet
  data and its related DB facts consistently — that's the simultaneity the user
  wants, and it comes from Datahike, not from Spindel.
- **Spindel** stays the compute layer: load a branch's cells → rebuild graph.
  No engine fork needed for version control.

### Where Spindel's own forking *is* still useful (later, optional)

`ctx/fork-context` is **O(1) copy-on-write** of the execution context (overlay
backend; parent-following on shared paths). Its real use is **speculative
recompute**, not version control:

- "Preview this formula edit across the sheet without committing" — fork, set
  the cell, read results, discard.
- Multi-agent / what-if scenarios.

APIs (from `forking.md`): `fork-context`, `snapshot-context` /
`restore-snapshot`, `serialize-context` / `deserialize-context` (**EDN**),
lineage (`get-fork-lineage`, `fork-depth`), lifecycle (`stop-context!`,
`close-context!`).

**Two hard limits to remember:**
1. **No built-in merge or diff** between contexts — forks diverge, they don't
   converge. Any git-style *merge* is ours to write (on the source document).
2. **Continuations are not serializable.** A serialized context is restored by
   **re-executing the model fn** (`with-rebuild-context`: spin bodies run but
   return cached values, rebuilding the dependency graph). This is the *same
   pattern Clorax already uses* — persist source, rebuild graph on load — so
   serialize-context buys us little over our own EDN/Datahike source store.

Spin cache identity is **keyed by source location** (deterministic hash chain),
stable across re-runs, forks, and serialize round-trips — that's what makes
replay/rebuild work.

---

## 2. SCI integration — yes, and it can replace our formula sandbox

`sci-integration.md`: Spindel's SCI bridge (`spindel.sci.macro`) **fully wires
`await` AND `track`** inside SCI-evaluated code via `create-spin-macro-context`.

> **This contradicts our current SPEC note** ("SCI integration only wires
> `await`, not spindel's `track`, so we use the real `spin` macro +
> whitelist"). With `create-spin-macro-context {:expose-track? true}`, track
> works in SCI. The SPEC's rationale for *not* using SCI is stale.

Three-layer sandbox, ideal for user formulas:

| layer | protection |
|-------|-----------|
| `fork-context` | copy-on-write state isolation per evaluation |
| SCI interpreter | only explicitly exposed fns reachable |
| `:native-spins` allowlist | exact control over which spins user code can `await` |

```clojure
(macro/create-spin-macro-context
  {:runtime forked-rt
   :native-spins {'sum sum-spin ...}      ; the only spins formulas may await
   :expose-track? true})
```

Cost: **~7× interpretation overhead** vs native (boundary crossing itself
~10ns). Formulas aren't individually hot and recompute is incremental, so this
is likely acceptable.

**Candidate (not now):** replace Clorax's `eval` + symbol-whitelist formula
sandbox with the SCI macro context. Upside: a real sandbox (no `eval`, no RCE
surface), explicit native-spin allowlist instead of a symbol blocklist. This is
a formula-engine change, orthogonal to auth/DB — worth its own spike + PR.

---

## 3. Incremental / infinite scroll — validating, but not directly droppable

`incremental.md` + `examples/infinite_scroll_demo.cljs`: `islice` keeps a
sliding window over a large collection and emits **only entering/exiting items**
as `:seq-diff` deltas; `ifor-each`/`imap`/`ifilter` reconcile keyed. 2D grids
nest two `islice`s (rows × cols) for true cell-window virtualization with
O(delta) DOM ops. Window math is identical to ours:

```clojure
(defn compute-window [scroll-top total]
  {:start (max 0 (- (quot scroll-top ITEM_HEIGHT) OVERSCAN))
   :end   (min total (+ start VISIBLE (* 2 OVERSCAN)))})
```

**Honest verdict:** `islice` + `ifor-each` and the delta vocabulary
(`{:degree :grow :shrink :permutation :change :freeze}`) live in Spindel's
**ClojureScript incremental-DOM** layer. Clorax renders **server HTML over
Datastar**, so we can't drop `islice` into our client. It's strong *validation*
that our logical-scroll windowing is the right shape, not reusable code —
**unless** we ever move cell rendering to Spindel's cljs DOM (we deliberately
don't). Mild future option: use the delta combinators **server-side** to
compute minimal cell patches for SSE instead of re-rendering the window +
dependents; only worth it if patch size becomes a problem.

---

## 4. JVM-side combinators & primitives we could actually use now

These are engine-level (JVM-capable), independent of the cljs DOM:

- **`batch`** (`concepts`/`getting-started`): group several `swap!`s into **one
  propagation pass**. Clorax fit: bulk source load (`load-document!`), applying
  a set of dependent recomputes, or future multi-cell paste — one drain instead
  of N.
- **Rate control** (`combinators.md`): `debounce`, `throttle`, `sample`,
  `relieve`. Clorax fit: presence chattiness (debounce `/presence`), autosave
  cadence (`sample`/`throttle` the file write), cursor pushes. All server-side.
- **`parallel` / `race` / `timeout`**: concurrent OAuth token+userinfo calls
  with a deadline (the auth layer currently does them sequentially).
- **Pub/Sub** (`pubsub.md`): `Mult` fan-out + `Pub` topic routing with
  backpressure and sliding/dropping buffers. Clorax fit: our hand-rolled
  per-sheet broadcast-to-sessions is exactly a Mult; a per-sheet topic could
  replace the manual `doseq` over `sessions*` and give backpressure for slow
  SSE clients. Bigger refactor — note for later.
- **Fork-safe atoms** (`atoms.md`), **Semaphore**, **Supervisor**
  (`api-reference.md`): relevant only if/when we adopt fork-based speculative
  recompute.

---

## 5. Per-page index (full coverage)

| page | gist | Clorax relevance |
|------|------|------------------|
| `concepts.md` | spins = bodies of checkpoints; signals; effects; drain queue; caching; exec context; `batch` | core mental model; `batch` usable now |
| `getting-started.md` | minimal setup, common mistakes (`@` vs `track`/`await`), batching | confirms our gotchas |
| `effects.md` | `await`/`track`/`yield`, async seqs (`gen-aseq`), Deferred/Mailbox, custom effects | yield/aseq not needed yet |
| `combinators.md` | parallel/race/timeout, debounce/throttle/sample/relieve/accumulate, Result type, cancellation, semaphore | **rate-control + parallel usable now** |
| `incremental.md` | `islice` windowing, `:seq-diff`, delta records | validates logical scroll; cljs-DOM bound |
| `forking.md` | O(1) CoW fork, snapshot, serialize (EDN), **no merge/diff**, rebuild-on-restore | speculative recompute; **not** the branching mechanism |
| `distributed.md` | P2P `register-context!`, signal replication; **no persistence** | not now |
| `sci-integration.md` | SCI wires await+track; 3-layer sandbox; ~7× cost | **formula-sandbox upgrade candidate** |
| `pubsub.md` | Mult/Pub, buffer types, backpressure | broadcast refactor candidate |
| `atoms.md` | fork-safe atoms vs core atom | only with forks |
| `custom-effects.md` | register your own effect → checkpoint | unlikely needed |
| `engine.md` | CPS/trampoline, addressing, drain thread, overlay backend | JVM internals; we run on JVM ✓ |
| `engine-formalism.md` | comonad/monad laws, glitch-freedom, determinism/replay | theory; replay law underpins rebuild |
| `api-reference.md` | full namespace/fn catalog | reference |

---

## 6. Recommendation summary

- **Branching = data branching in Datahike**, diff/merge ours on the cell map.
  Spindel reloads graph from the checked-out branch (already how we load). Do
  **not** build branching on `fork-context`.
- **This DB pass** (users / tokens / sheets-metadata / shares in Datahike,
  cells stay file EDN) is on the path: design the `sheet` entity so cell datoms
  can move into Datahike per-branch later without touching identity/ACL.
- **Adopt opportunistically now:** `batch` on bulk source load; `debounce`/
  `sample` for presence + autosave.
- **Spike later, own PRs:** SCI formula sandbox; Pub/Sub broadcast; speculative
  `fork-context` preview.
- **Don't chase:** `islice`/incremental-DOM (cljs, conflicts with Datastar);
  `serialize-context` (rebuild-from-source is what we already do).
