(ns uno.michelada.saltrim.web.render
  "Server-rendered HTML/SVG: the grid window, the page shell, every modal, the
   share/branch/graph/history fragments, and the auth pages. Pure output — reads
   state for presence, never pushes (that is `collab`)."
  (:require
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.fmt :as fmt]
            [uno.michelada.saltrim.graph :as graph]
            [uno.michelada.saltrim.merge :as mrg]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.constants :refer [CW RH GUT HDR OVER BAR]]
            [uno.michelada.saltrim.web.geom :refer [axis-x axis-y col-w in-window? rgba row-h total-px url-decode view-base window]]
            [uno.michelada.saltrim.web.state :refer [def-editor-of now owner-of palette session-view sessions* sheets*]]))

(defn- display [sh a]
  (let [v    (sheet/value sh a)
        mask (sheet/style-value sh a :format)]   ; nil / string / {:error}
    (cond (nil? v) ""
          (map? v) "#ERR"
          (string? mask) (fmt/apply-mask mask v)
          :else (str v))))

(defn- cell-id [a] (str "c_" a))

(def style-css
  "Reactive style prop -> CSS declaration. The whole supported set lives here;
   adding a property is one entry (+ a control in the style panel). Each value is
   a literal or an =-formula computed per cell into a CSS string."
  (array-map :bg     "background-color"
             :fg     "color"
             :weight "font-weight"
             :slant  "font-style"
             :align  "text-align"))

(def value-props
  "Presentational props consumed by transforming the displayed VALUE (not CSS).
   :format is a number mask (see the fmt ns). Same reactive literal-or-=formula
   model as style props — just applied in `display` instead of as a CSS decl."
  [:format])

(def meta-props
  "Per-cell METADATA props: stored + persisted like a style prop (so they ride
   the per-property datom model — branch/merge/as-of/undo for free) but neither
   rendered as CSS nor applied to the value. `:label` is a human-readable name
   for the cell, used as its node label in the dependency-graph view."
  [:label])

(defn prop-allowed? [p]
  (or (contains? style-css p) (some #{p} value-props) (some #{p} meta-props)))

(defn- cell-style-decls
  "Inline CSS for `a`'s style props (only those resolving to a string; errors /
   blanks are skipped here and reported via the toast)."
  [sh a]
  (apply str (keep (fn [[prop css]]
                     (let [v (sheet/style-value sh a prop)]
                       (when (string? v) (str ";" css ":" v))))
                   style-css)))

(defn- cell-input
  "Minimal per-cell HTML: a DISPLAY div (not an input), positioned WINDOW-RELATIVE
   to (cbase,rbase). data-raw carries the source for the floating editor. A single
   click selects; Enter/double-click opens the editor (handled in /app.js). No
   per-cell input -> no 500 live <input>s."
  [sh a ci ri cbase rbase]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)
        srcs (sheet/style-srcs sh a)]      ; {prop raw} -> echoed into the style bar
    ;; width/height always emitted (override OR the sheet default) so geometry is
    ;; fully data-driven: a default-size change re-renders the window and applies
    ;; live for everyone — no stale CSS, no reload.
    [:div {:id (cell-id a) :class "cell" :data-raw raw
           :data-sty (when (seq srcs) (json/write-value-as-string srcs))
           :style (str (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx"
                               (- (axis-x sh ci) (axis-x sh cbase))
                               (- (axis-y sh ri) (axis-y sh rbase))
                               (dec (col-w sh ci)) (dec (row-h sh ri)))
                       (cell-style-decls sh a))}
     disp]))

(defn cells-html [sh cis ris]
  (let [cb (first cis) rb (first ris)]
    (str (h/html (for [ri ris ci cis] (cell-input sh (addr/make ci ri) ci ri cb rb))))))

(defn colhead-html [sh cis]
  (let [xb (axis-x sh (first cis))]
    (str (h/html
          (for [ci cis :let [w (col-w sh ci)]]
            [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-x sh ci) xb) w HDR HDR)}
             (addr/idx->col ci)
             ;; drag handle on the right edge -> resize this column (/app.js)
             [:div {:class "colgrip" :data-ci ci}]])))))

(defn rowhead-html [sh ris]
  (let [yb (axis-y sh (first ris))]
    (str (h/html
          (for [ri ris :let [h (row-h sh ri)]]
            [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-y sh ri) yb) GUT h h)}
             (inc ri)
             [:div {:class "rowgrip" :data-ri ri}]])))))

(defn meta-html
  "Hidden element carrying, to /app.js: the logical scroll totals (size the
   scrollbars) and the rendered window's base index cb/rb. /app.js translates the
   layers relative to cb/rb — patched together with #cells, so the transform
   always matches the displayed content (no jump while a fetch is in flight)."
  [sh r0 c0]
  (let [[tw th] (total-px sh r0 c0)
        [cb rb] (view-base {:r0 r0 :c0 c0})]
    (str (h/html [:div {:id "meta" :data-tw tw :data-th th :data-cb cb :data-rb rb
                        ;; per-sheet default axis sizes (client geometry base)
                        :data-dcw (sheet/default-col-w sh) :data-drh (sheet/default-row-h sh)
                        ;; sparse axis-size overrides so /app.js computes offsets
                        :data-colw (json/write-value-as-string (sheet/col-widths sh))
                        :data-rowh (json/write-value-as-string (sheet/row-heights sh))
                        :style "display:none;"}]))))

(defn- grid-layers
  "Logical-scroll viewport: fixed-size, overflow hidden. Clipped header strips +
   a cell area, each holding an absolutely-positioned layer that /app.js
   translates by the sub-cell offset. Plus the corner, the totals #meta, and two
   custom scrollbars. No giant spacer -> no row cap, no precision wobble."
  [sh {:keys [r0 c0] :as view}]
  (let [[cis ris] (window r0 c0)
        clip "position:absolute;overflow:hidden;"]
    (h/html
     [:div {:id "viewport"
            :data-cw CW :data-rh RH :data-gut GUT :data-hdr HDR
            :data-over OVER :data-bar BAR
            ;; Selection (single + range + multi-range), double-click-to-edit and
            ;; keyboard all live in app.cljs now — it owns the selection state that
            ;; the #selrange overlay + clipboard read. A plain click still ends up
            ;; setting $sel + mirroring the cell into the bars + posting presence,
            ;; via the sr-select bridge; Shift extends a range, Ctrl/⌘ adds one.
            :style "position:relative;height:78vh;border:1px solid #ccc;overflow:hidden;"}
      (h/raw (meta-html sh r0 c0))
      ;; corner
      [:div {:id "corner"
             :style (format (str "position:absolute;left:0;top:0;z-index:4;width:%dpx;height:%dpx;"
                                 "background:#e8e8e8;border:1px solid #e0e0e0;box-sizing:border-box;")
                            GUT HDR)}]
      ;; column header strip (clipped; translated in X)
      [:div {:id "colclip" :style (format "%sleft:%dpx;top:0;right:%dpx;height:%dpx;z-index:3;"
                                          clip GUT BAR HDR)}
       [:div {:id "colstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "colhead"} (h/raw (colhead-html sh cis))]]]
      ;; row header strip (clipped; translated in Y)
      [:div {:id "rowclip" :style (format "%sleft:0;top:%dpx;bottom:%dpx;width:%dpx;z-index:3;"
                                          clip HDR BAR GUT)}
       [:div {:id "rowstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "rowhead"} (h/raw (rowhead-html sh ris))]]]
      ;; cell area (clipped; translated in X+Y)
      [:div {:id "cellclip" :style (format "%sleft:%dpx;top:%dpx;right:%dpx;bottom:%dpx;"
                                           clip GUT HDR BAR BAR)}
       [:div {:id "cells" :style "position:absolute;left:0;top:0;will-change:transform;"}
        (h/raw (cells-html sh cis ris))]
       ;; multi-cell selection highlight — drawn CLIENT-side by app.cljs from its
       ;; selection state (ranges + multi-range), translated with #cells. Local
       ;; only: peers see your active cell via presence, not the whole marquee.
       [:div {:id "selrange" :style (str "position:absolute;left:0;top:0;z-index:1;"
                                         "pointer-events:none;will-change:transform;")}]
       ;; THIS user's own selection / editing marker — server-rendered from the
       ;; session's :cursor/:editing (no per-cell client JS). pointer-events:none
       ;; so it never blocks typing in the cell beneath. Translated with #cells.
       [:div {:id "self" :style (str "position:absolute;left:0;top:0;z-index:2;"
                                     "pointer-events:none;will-change:transform;")}]
       ;; collaborator cursors / edit-locks; translated with #cells by /app.js.
       ;; container ignores pointer events — only an editing marker re-enables
       ;; them to block the cell beneath.
       [:div {:id "peers" :style (str "position:absolute;left:0;top:0;z-index:3;"
                                      "pointer-events:none;will-change:transform;")}]
       ;; the single floating editor, translated with #cells. app.cljs positions
       ;; it over the active cell and moves focus in; everything else is
       ;; declarative: data-bind:v shares $v with the formula bar, data-show
       ;; reveals it on $edit, Enter/blur commit (@post '/cell' + drop the edit
       ;; lock), Esc cancels. preventDefault is INLINE for Enter/Esc only — a
       ;; `__prevent` MODIFIER fires on every keydown and would block all typing.
       ;; `__stop` keeps these keys from the document-level nav handler.
       [:div {:id "editlayer" :style "position:absolute;left:0;top:0;will-change:transform;"}
        ;; Visibility is $celledit (set ONLY by start-edit!, which also positions +
        ;; sizes it over the cell) — NOT $edit, which the formula bar also sets for
        ;; presence/peer-lock. Sharing $edit would pop this box, unpositioned and
        ;; at its default size, on every formula-bar focus.
        [:input {:id "editor" :data-bind:v "" :data-show "$celledit"
                 :data-on:keydown__stop
                 (str "evt.key==='Enter' ? (evt.preventDefault(),$cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                      " : evt.key==='Escape' ? (evt.preventDefault(),$edit=false,$celledit=false,@post('/presence')) : null")
                 :data-on:blur "$celledit && ($cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                 :style "display:none;"}]]]
      ;; custom scrollbars
      [:div {:id "vbar" :style (format (str "position:absolute;right:0;top:%dpx;bottom:%dpx;width:%dpx;"
                                            "background:#f0f0f0;z-index:5;") HDR BAR BAR)}
       [:div {:id "vthumb"
              :style "position:absolute;left:1px;right:1px;top:0;height:30px;background:#bbb;border-radius:6px;"}]]
      [:div {:id "hbar" :style (format (str "position:absolute;left:%dpx;bottom:0;right:%dpx;height:%dpx;"
                                            "background:#f0f0f0;z-index:5;") GUT BAR BAR)}
       [:div {:id "hthumb"
              :style "position:absolute;top:1px;bottom:1px;left:0;width:30px;background:#bbb;border-radius:6px;"}]]
      ;; single moving guide line shown while dragging a header grip
      [:div {:id "rzguide"}]])))

(declare share-html)

(defn- help-html
  "In-app end-user reference, toggled by $help. Mirrors README's user guide.
   Pure server-rendered HTML shown/hidden by Datastar data-show — no app.js."
  []
  (let [h3  "margin:.8rem 0 .25rem;font:600 13px sans-serif;"
        p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);"
        kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"]
    (str (h/html
          [:div {:id "helpwrap" :data-show "$help"
                 :data-on:click "$help=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:38rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "SaltRim — quick guide"]
             [:button {:class "btn" :data-on:click "$help=false" :title "close"} "✕"]]

            [:div {:style h3} "Cells & formulas"]
            [:p {:style p} "Type a value, or start with " [:span {:style kbd} "="]
             " for a formula (Clojure s-expressions). Reference cells with "
             [:span {:style kbd} "#cell A1"] " and ranges with " [:span {:style kbd} "#cells A1:A3"]
             " — or the short form " [:span {:style kbd} "$A1"] " / " [:span {:style kbd} "$A3:D8"] "."]
            [:p {:style p} "e.g. " [:span {:style kbd} "=(+ $A1 $B1)"] " · "
             [:span {:style kbd} "=(reduce + $A1:A3)"]]
            [:p {:style p} "Built-in functions: math (" [:span {:style kbd} "sum"] ", "
             [:span {:style kbd} "round"] ", " [:span {:style kbd} "sqrt"] "…), stats ("
             [:span {:style kbd} "mean"] ", " [:span {:style kbd} "median"] ", "
             [:span {:style kbd} "stdev"] "), text (" [:span {:style kbd} "upper"] ", "
             [:span {:style kbd} "join"] ", " [:span {:style kbd} "split"] "…), date ("
             [:span {:style kbd} "today"] ", " [:span {:style kbd} "year"] ", "
             [:span {:style kbd} "days-between"] ")."]

            [:div {:style h3} "Reusable functions (ƒ)"]
            [:p {:style p} "The " [:span {:style kbd} "ƒ"] " button (top bar) opens this sheet's "
             "definitions library: write your own functions/constants as separate entries and call "
             "them from any cell. e.g. add "
             [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"] " then use "
             [:span {:style kbd} "=(margin #cell A1 #cell B1)"] ". Each entry collapses to name "
             "badges; Edit expands it and " [:span {:style kbd} "⤢"] " opens a big editor (also next "
             "to the formula and style bars). While you edit one it's locked for other "
             "collaborators; saving recompiles every cell."]

            [:div {:style h3} "Styling a cell"]
            [:p {:style p} "Use the third toolbar row: pick a property, type a value or an "
             [:span {:style kbd} "="] "-formula, press Apply (or Enter). "
             [:span {:style kbd} "$val"] " is the selected cell's own value, so styles can react to it."]
            [:p {:style p} "Properties: " [:span {:style kbd} "bg"] " (background), "
             [:span {:style kbd} "fg"] " (text color), " [:span {:style kbd} "weight"] " (e.g. bold), "
             [:span {:style kbd} "slant"] " (e.g. italic), " [:span {:style kbd} "align"] " (left/right/center)."]
            [:p {:style p} "e.g. bg " [:span {:style kbd} "=(if (> $val 100) \"tomato\" \"white\")"]]

            [:div {:style h3} "Dependency graph"]
            [:p {:style p} "The " [:span {:style kbd} "🕸"] " button draws how cells feed each other "
             "(an arrow points from a cell to the cells that use it); click a node to select it. "
             "Set a cell's " [:span {:style kbd} "label"] " (style row) to name its node."]

            [:div {:style h3} "Number format"]
            [:p {:style p} "Property " [:span {:style kbd} "format"] " takes a mask applied to numeric values: "
             [:span {:style kbd} "0.00"] " → 1234.50 · " [:span {:style kbd} "#,##0"] " → 1,234,567 · "
             [:span {:style kbd} "0.0%"] " → 25.0% · " [:span {:style kbd} "$#,##0.00"] " → $1,234.50"]

            [:div {:style h3} "Column & row size"]
            [:p {:style p} "Drag the trailing edge of a column header (or the bottom edge of a row "
             "number) to resize it. Sizes are saved with the sheet. Dragging "
             [:b "snaps"] " to multiples of the sheet default (1×, 2×, 3×…); hold "
             [:span {:style kbd} "Alt"] " while dragging to size freely."]
            [:p {:style p} "Owners can set the sheet-wide default column width / row height in "
             [:span {:style kbd} "⚙"] " (Sheet properties, top bar)."]

            [:div {:style h3} "Navigation"]
            [:p {:style p} "Click to select · double-click or " [:span {:style kbd} "Enter"]
             " to edit · arrows / " [:span {:style kbd} "Tab"] " to move · the address box jumps to a cell."]

            [:div {:style h3} "Selecting ranges"]
            [:p {:style p} [:span {:style kbd} "Shift"] "+click or " [:span {:style kbd} "Shift"]
             "+arrows extends a range · " [:span {:style kbd} "Ctrl/⌘"] "+click adds another range · "
             [:span {:style kbd} "Delete"] " clears the selected cells (undoable)."]

            [:div {:style h3} "Copy / paste"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+C"] " copy · " [:span {:style kbd} "Ctrl/⌘+X"]
             " cut · " [:span {:style kbd} "Ctrl/⌘+V"] " paste at the selected cell. Pasted formulas "
             "shift their references relative to the move (copy " [:span {:style kbd} "=(+ #cell A1 1)"]
             " down a row pastes " [:span {:style kbd} "=(+ #cell A2 1)"] ")."]

            [:div {:style h3} "Undo / redo"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+Z"] " undoes your last edit · "
             [:span {:style kbd} "Ctrl/⌘+Shift+Z"] " (or " [:span {:style kbd} "Ctrl+Y"] ") redoes. "
             "Undo only affects your own edits — a cell a collaborator changed after you is left alone."]

            [:div {:style h3} "Branches"]
            [:p {:style p} "A branch is a parallel version of the sheet you can edit without touching the "
             "others — like git for spreadsheets. The " [:span {:style kbd} "🌿"] " picker (top bar) "
             "switches branches; people on different branches don't see each other's cells. The owner's "
             [:span {:style kbd} "⑂"] " button forks the current branch into a new one, deletes a "
             "non-main branch, or merges another branch in — a 3-way merge that auto-applies "
             "non-overlapping changes and lets you resolve conflicts cell by cell. The "
             [:span {:style kbd} "🕘"] " button opens an earlier revision as a read-only snapshot "
             "(time-travel); Back to live returns to the current sheet."]

            [:div {:style h3} "Sharing"]
            [:p {:style p} "The link / lock button (top bar, owner only) shares the sheet by capability "
             "link or with specific people, at view or edit level."]]]))))

(declare deflib-html bigedit-html)

(def ^:private stdlib-reference
  "Read-only reference of the built-in functions (always available, can't be
   edited), grouped by category."
  [["math"  "sum product abs ceil floor round sqrt pow exp ln log10 sign"]
   ["stats" "mean avg median variance stdev"]
   ["text"  "upper lower trim join split str-replace starts-with? ends-with? includes? blank?"]
   ["date"  "today year month day days-between  (ISO yyyy-MM-dd strings)"]])

(defn- defs-html
  "The definitions LIBRARY modal, toggled by $defspanel. The editable library
   (#deflib) is a server-rendered fragment of chunks — each edited and locked
   independently for collaboration, all merged into the sheet's program. Below it
   is the read-only built-in stdlib reference. Pure server HTML + Datastar."
  [storage-id]
  (let [p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);"
        kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"]
    (str (h/html
          [:div {:id "defswrap" :data-show "$defspanel"
                 :data-on:click "$defspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:44rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Definitions library"]
             [:button {:class "btn" :data-on:click "$defspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Functions and constants reusable by every formula in this sheet, kept as "
             "separate entries. Editing one locks it for other collaborators; they all merge (in order) "
             "into the sheet's program. Same sandbox as formulas — pure, no host interop."]
            [:p {:style p} "e.g. " [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"]
             " → in a cell " [:span {:style kbd} "=(margin #cell A1 #cell B1)"]]
            ;; dynamic, per-session library fragment (pushed on changes)
            [:div {:id "deflib"} (h/raw (deflib-html nil storage-id))]
            ;; read-only built-ins
            [:details {:style "margin-top:.9rem;"}
             [:summary {:style "font:600 13px sans-serif;cursor:pointer;color:var(--muted);"}
              "Built-in functions (read-only)"]
             (for [[cat names] stdlib-reference]
               [:p {:style (str p "margin-left:.4rem;")}
                [:b cat] ": " [:span {:style kbd} names]])]]]))))

(defn- props-html
  "Owner-only Sheet properties modal, toggled by $propspanel. Today: the sheet's
   DEFAULT column width / row height (px) — the size of any unsized column/row.
   Built as a labelled grid so more sheet-wide settings slot in as new rows.
   $pcw/$prh are server-seeded with the current values; Apply posts /props."
  []
  (let [p     "margin:.2rem 0 .7rem;font:13px sans-serif;color:var(--muted);"
        lbl   "font:13px sans-serif;color:var(--fg);align-self:center;"
        num   "font:13px monospace;padding:5px 6px;border:1px solid var(--line);border-radius:var(--radius);background:var(--panel);width:6rem;"]
    (str (h/html
          [:div {:id "propswrap" :data-show "$propspanel"
                 :data-on:click "$propspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:30rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Sheet properties"]
             [:button {:class "btn" :data-on:click "$propspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Sheet-wide defaults. Individual columns/rows you've dragged keep their own size."]
            [:div {:style "display:grid;grid-template-columns:auto 1fr;gap:.55rem .8rem;align-items:center;"}
             [:label {:style lbl :for "pcw"} "Default column width"]
             [:input {:id "pcw" :type "number" :min "24" :max "2000" :step "1"
                      :data-bind:pcw "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]
             [:label {:style lbl :for "prh"} "Default row height"]
             [:input {:id "prh" :type "number" :min "16" :max "1000" :step "1"
                      :data-bind:prh "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]]
            [:div {:style "margin-top:1rem;text-align:right;"}
             [:button {:class "btn primary" :data-on:click "@post('/props'), $propspanel=false"} "Apply"]]]]))))

(defn- sheet-picker
  "Dropdown for switching sheets, grouped into 'your sheets' (👤) and 'shared
   with you' (✎ edit / 👁 view). Selecting one navigates to it. A foreign sheet
   reached by a public link (in neither group) shows as a leading ↗ option."
  [uid storage-id sname]
  (let [names       (store/list-names uid)
        [owner _]   (store/split-id storage-id)
        own?        (= owner uid)
        mine        (if own? (distinct (cons sname names)) names)
        shared      (db/sheets-shared-with uid)
        cur-shared? (some #(= storage-id (:sheet-id %)) shared)]
    [:select {:id "sheetpicker" :class "tool" :title "your sheets"
              :data-on:change "el.value && (location.href='/?'+el.value)"
              :style "max-width:11rem;"}
     (when (and (not own?) (not cur-shared?))
       [:option {:value "" :selected true} (str "↗ " sname)])
     [:optgroup {:label "your sheets"}
      (for [n mine]
        [:option {:value (str "s=" n) :selected (and own? (= n sname))} (str "👤 " n)])]
     (when (seq shared)
       [:optgroup {:label "shared with you"}
        (for [{:keys [sheet-id name level]} shared
              :let [[o nm] (store/split-id sheet-id)
                    icon   (if (= level :read-write) "✎" "👁")]]
          [:option {:value (str "u=" o "&s=" nm) :selected (= sheet-id storage-id)}
           (str icon " " (if (str/blank? name) nm name))])])]))

(defn- branch-bar
  "Branch picker + owner-only fork/delete (a 🌿 dropdown + ⑂ modal). A branch is
   a parallel copy of the sheet you edit independently. Switching navigates (full
   reload, like the sheet picker), preserving the sheet URL. Fork/delete post to
   /branch; on success the server sets $goto and the page navigates."
  [uid storage-id sname branch link-token owner?]
  (let [names (db/branch-names storage-id)
        ;; the sheet's own URL (owners reach theirs by ?s=; a link visitor keeps
        ;; ?t=; a shared viewer keeps ?u=&s=). The picker appends &b=.
        base  (cond link-token (str "/?t=" link-token)
                    owner?      (str "/?s=" sname)
                    :else       (str "/?u=" (first (store/split-id storage-id)) "&s=" sname))
        overlay (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")
        modal   (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                     "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:24rem;width:100%;padding:1rem 1.1rem;"
                     "font:13px sans-serif;color:var(--fg);")
        field   (str "font:13px sans-serif;padding:6px 8px;border:1px solid var(--line);"
                     "border-radius:var(--radius);box-sizing:border-box;")]
    (list
     [:select {:id "branchpicker" :class "tool" :title "branch — a parallel version of this sheet"
               ;; navigate to the picked branch (main → no &b=, keeps URLs clean)
               :data-on:change (str "el.value && (location.href='" base "'"
                                    " + (el.value==='" db/MAIN "' ? '' : '&b='+el.value))")
               :style "max-width:9rem;"}
      (for [n names]
        [:option {:value n :selected (= n branch)} (str "🌿 " n)])]
     (when owner?
       (list
        [:button {:class "btn" :data-on:click "$branchpanel=true"
                  :title "branches: fork / delete"} "⑂"]
        [:div {:data-show "$branchpanel" :data-on:click "$branchpanel=false" :style overlay}
         [:div {:data-on:click "evt.stopPropagation()" :style modal}
          [:div {:style "display:flex;align-items:center;margin-bottom:.5rem;"}
           [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Branches"]
           [:button {:class "btn" :data-on:click "$branchpanel=false" :title "close"} "✕"]]
          [:p {:style "color:var(--muted);margin:.2rem 0 .7rem;"}
           "On branch " [:strong (str "🌿 " branch)] ". A fork copies it into a new "
           "parallel branch you can edit without touching this one."]
          [:label {:style "display:block;font-size:12px;color:var(--muted);margin-bottom:.2rem;"}
           "New branch name"]
          [:div {:style "display:flex;gap:.4rem;"}
           [:input {:data-bind:bname "" :placeholder "feature-x" :autocomplete "off"
                    :data-on:keydown "evt.key==='Enter' && ($branchact='fork', @post('/branch'))"
                    :style (str field "flex:1;")}]
           [:button {:class "btn primary" :data-on:click "$branchact='fork', @post('/branch')"}
            (str "Fork from " branch)]]
          ;; ── merge another branch INTO this one (3-way) ──────────────────
          (when-let [others (seq (remove #(= % branch) names))]
            [:div {:style "border-top:1px solid var(--grid);margin-top:.8rem;padding-top:.7rem;"}
             [:label {:style "display:block;font-size:12px;color:var(--muted);margin-bottom:.2rem;"}
              (str "Merge another branch into 🌿 " branch)]
             [:div {:style "display:flex;gap:.4rem;"}
              [:select {:class "tool" :data-bind:mergefrom "" :style "flex:1;"}
               [:option {:value ""} "choose a branch…"]
               (for [n others] [:option {:value n} (str "🌿 " n)])]
              [:button {:class "btn"
                        :data-on:click "$mergetake='', $branchact='preview', @post('/merge')"}
               "Preview"]]
             ;; preview result + conflict picker + Apply land here (patched by id)
             [:div {:id "mergeresult"}]])
          (when (not= branch db/MAIN)
            [:div {:style "border-top:1px solid var(--grid);margin-top:.8rem;padding-top:.7rem;"}
             [:button {:class "btn"
                       :data-on:click (str "confirm('Delete branch \\u201c" branch "\\u201d? "
                                           "This removes its cells and cannot be undone.') && "
                                           "($branchact='delete', @post('/branch'))")
                       :style "color:var(--danger);border-color:var(--danger);"}
              (str "Delete “" branch "”")]])]])))))

;; --- as-of / history viewing (PR C) ---------------------------------------

(defn- fmt-ts
  "An #inst (java.util.Date) -> \"yyyy-MM-dd HH:mm:ss\" local, or nil."
  [inst]
  (when inst
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             (java.time.LocalDateTime/ofInstant (.toInstant ^java.util.Date inst)
                                                (java.time.ZoneId/systemDefault)))))

(defn- sheet-href
  "The sheet's own branch-aware URL (owners reach theirs by ?s=; a link visitor
   keeps ?t=; a shared viewer keeps ?u=&s=; non-main adds &b=). History links
   append &at=<tx>."
  [storage-id sname branch link-token owner?]
  (str (cond link-token (str "/?t=" link-token)
             owner?      (str "/?s=" sname)
             :else       (str "/?u=" (first (store/split-id storage-id)) "&s=" sname))
       (when (not= branch db/MAIN) (str "&b=" branch))))

(defn- revision-select
  "A <select> of revisions (newest first) that navigates on change; `cur` = the
   tx currently viewed (nil = live, selects the 'current' option)."
  [href revisions cur]
  [:select {:class "tool" :title "view an earlier revision (read-only)"
            :data-on:change "el.value && (location.href = el.value)"
            :style "max-width:14rem;"}
   [:option {:value href :selected (nil? cur)} "● current (live)"]
   (for [{:keys [tx inst]} revisions]
     [:option {:value (str href "&at=" tx) :selected (= tx cur)}
      (str "🕘 " (fmt-ts inst))])])

(defn- asof-banner
  "Read-only banner shown while viewing a past revision: what/when + a revision
   picker + Back-to-live."
  [storage-id sname branch at revisions link-token owner?]
  (let [href (sheet-href storage-id sname branch link-token owner?)
        cur  (parse-long (str at))
        when-s (some->> revisions (filter #(= (:tx %) cur)) first :inst fmt-ts)]
    [:div {:style (str "display:flex;align-items:center;gap:.5rem;flex:1;"
                       "background:#fff8e1;border:1px solid #e6c200;border-radius:var(--radius);"
                       "padding:4px 8px;font:12px sans-serif;color:#7a5b00;")}
     [:span "🕘 " [:strong (str "🌿 " branch)] " as of "
      [:strong (or when-s (str "tx " cur))] " — read-only."]
     (revision-select href revisions cur)
     [:a {:class "btn" :href href :style "text-decoration:none;"} "Back to live"]]))

(defn- history-modal
  "The 🕘 history modal (live page): a list of revisions, each opening a read-only
   as-of view. Toggled by $histpanel."
  [storage-id sname branch revisions link-token owner?]
  (let [href (sheet-href storage-id sname branch link-token owner?)]
    [:div {:data-show "$histpanel" :data-on:click "$histpanel=false"
           :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                       "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")}
     [:div {:data-on:click "evt.stopPropagation()"
            :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                        "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:26rem;width:100%;"
                        "padding:1rem 1.1rem;font:13px sans-serif;color:var(--fg);")}
      [:div {:style "display:flex;align-items:center;margin-bottom:.5rem;"}
       [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} (str "History — 🌿 " branch)]
       [:button {:class "btn" :data-on:click "$histpanel=false" :title "close"} "✕"]]
      [:p {:style "color:var(--muted);margin:.2rem 0 .6rem;"}
       "Open this branch as it was at an earlier point (read-only)."]
      (if (empty? revisions)
        [:p {:style "color:var(--muted);"} "No history yet — make some edits first."]
        [:div {:style "max-height:40vh;overflow:auto;"}
         (for [{:keys [tx inst]} revisions]
           [:a {:href (str href "&at=" tx)
                :style (str "display:block;padding:.4rem .2rem;border-top:1px solid var(--grid);"
                            "text-decoration:none;color:var(--fg);font:12px monospace;")}
            (str "🕘 " (fmt-ts inst))])])]]))

(defn- graph-modal-html
  "The 🕸 dependency-graph modal shell, toggled by $graphpanel. Its #graphview
   inner is server-rendered by /graph when the modal opens (so it's always
   fresh)."
  []
  [:div {:data-show "$graphpanel" :data-on:click "$graphpanel=false"
         :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:8vh 1rem;")}
   [:div {:data-on:click "evt.stopPropagation()"
          :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                      "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:52rem;width:100%;"
                      "padding:1rem 1.1rem;font:13px sans-serif;color:var(--fg);")}
    [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
     [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Dependency graph"]
     [:button {:class "btn" :data-on:click "$graphpanel=false" :title "close"} "✕"]]
    [:p {:style "color:var(--muted);margin:.2rem 0 .5rem;"}
     "An arrow points from a cell to the cells whose formulas read it. Click a node to select it. "
     "Name a cell (style row → " [:span {:style "font-family:monospace;"} "label"] ") for a friendlier node."]
    [:div {:id "graphview" :style "overflow:auto;max-height:64vh;"}]]])

(defn page [sh storage-id sname branch at uid link-token]
  ;; one session id seeds BOTH $sid (sent on /stream, registers the session) and
  ;; #ctl's data-sid (read by the unload beacon) — they must be the same value.
  (let [sid    (str (random-uuid))
        owner? (= uid (first (store/split-id storage-id)))
        asof?  (boolean at)                       ; read-only historical view?
        revisions (db/branch-revisions storage-id branch)]
   (str
    "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "SaltRim"]
      [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
      ;; Cells are display <div class="cell"> (not inputs); the floating editor
      ;; is the single #editor input. Both are absolutely positioned (cells by
      ;; their inline left/top, #editor by app.js) — without this the left/top
      ;; are ignored and everything stacks in flow at the top-left.
      [:style (h/raw
               (str
                ;; design tokens — centralize colors/geometry so a merge can't
                ;; silently drift them and so inline styles can share them.
                ;; palette tuned to the SaltRim logo: blue grid/parens, lime
                ;; slice, slate wordmark — softer neutrals than the old grey/blue.
                ":root{--bg:#fefefe;--panel:#f4f6f8;--line:#c7ccd1;--grid:#e2e6ea;"
                "--fg:#3a4149;--muted:#7a828b;--accent:#2f8fd8;--accent2:#9ec9ee;"
                "--accent-bg:#eaf4fc;--lime:#7cc62e;--danger:#c0392b;--radius:4px;}"
                ;; toolbar: two rows. row 1 = picker/new/share/identity,
                ;; row 2 = cell-ref + formula bar. .tool/.btn unify the inputs
                ;; and buttons that used to repeat the same inline style string.
                ".toolrow{display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;}"
                ".tool{font:13px sans-serif;padding:5px 6px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);}"
                ".tool.mono{font-family:monospace;}"
                ".btn{font:12px sans-serif;padding:5px 8px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);cursor:pointer;}"
                ".btn:hover{border-color:var(--accent);}"
                ;; toggled-on section chip (e.g. 🎨 format) — tinted, not loud
                ".btn.active{background:var(--accent-bg);border-color:var(--accent);color:var(--accent);}"
                ;; primary action button (Save / Apply) — the brand accent
                ".btn.primary{background:var(--accent);color:#fff;border-color:var(--accent);}"
                ".btn.primary:hover{filter:brightness(1.06);}"
                ;; definition name badges (collapsed library cards)
                ".badge{display:inline-block;font:600 11px/1.5 monospace;color:var(--accent);"
                "background:var(--accent-bg);border:1px solid var(--accent2);"
                "border-radius:10px;padding:0 .5rem;}"
                ".spacer{flex:1;}"
                ;; resize grips: a thin hit-zone on a header's trailing edge that
                ;; /app.js drags. The #rzguide is the single moving guide line.
                ".colgrip{position:absolute;top:0;right:-3px;width:6px;height:100%;"
                "cursor:col-resize;z-index:5;}"
                ".rowgrip{position:absolute;left:0;bottom:-3px;height:6px;width:100%;"
                "cursor:row-resize;z-index:5;}"
                "#rzguide{position:absolute;display:none;background:var(--accent);"
                "z-index:7;pointer-events:none;}"
                ;; default cell/editor box = this SHEET's default axis sizes (a
                ;; sized column/row overrides inline per cell). Server-rendered so
                ;; changing the sheet default reflows the grid on reload.
                (let [dw (dec (sheet/default-col-w sh)) dh (dec (sheet/default-row-h sh))]
                  (format (str ".cell{position:absolute;width:%dpx;height:%dpx;"
                               "box-sizing:border-box;border:1px solid var(--grid);"
                               "padding:2px 4px;font:13px monospace;overflow:hidden;"
                               "white-space:nowrap;background:var(--bg);}"
                               "#editor{position:absolute;width:%dpx;height:%dpx;"
                               "box-sizing:border-box;border:1px solid var(--accent);"
                               "padding:2px 4px;font:13px monospace;outline:none;z-index:6;}")
                          dw dh dw dh))
                ;; selection / editing OVERLAY (#self), server-rendered. Literal %
                ;; in the gradients -> kept OUT of the format call above.
                ;; calm "you are here" selection box:
                ".selfcell{position:absolute;box-sizing:border-box;pointer-events:none;"
                "border:2px solid var(--accent2);}"
                ;; actively editing: animated 'marching ants' border (four gradient
                ;; edges whose position scrolls). pointer-events stays none so the
                ;; cell beneath is still typable.
                ".selfcell.editing{border-color:transparent;"
                "background-image:"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%);"
                "background-repeat:repeat-x,repeat-x,repeat-y,repeat-y;"
                "background-size:8px 2px,8px 2px,2px 8px,2px 8px;"
                "background-position:0 0,0 100%,0 0,100% 0;"
                "animation:cc-ants .6s infinite linear;}"
                "@keyframes cc-ants{to{background-position:8px 0,-8px 100%,0 -8px,100% 8px;}}"
                "@media(prefers-reduced-motion:reduce){.selfcell.editing{animation:none;}}"
                ;; collaborator cursor overlays (#peers):
                ".peer{position:absolute;box-sizing:border-box;border:2px solid #888;border-radius:2px;}"
                ".peer.editing{cursor:not-allowed;}"
                ".peer .peertag{position:absolute;top:-15px;left:-2px;"
                "font:10px/14px sans-serif;color:#fff;padding:0 4px;"
                "border-radius:3px 3px 3px 0;white-space:nowrap;}"))]
      [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js" #_"/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals:cell "''"
             :data-signals:v "''"
             :data-signals:err "''"
             :data-signals:sel "''"
             :data-signals:edit "false"
             ;; floating in-cell editor visibility (distinct from $edit: only the
             ;; cell dblclick/Enter path shows it, after start-edit! positions it)
             :data-signals:celledit "false"
             :data-signals:r0 "0"
             :data-signals:c0 "0"
             :data-signals:sheet (format "'%s'" storage-id)
             ;; the working branch (rides in every POST so the server routes to
             ;; the right (sheet,branch) room); seeded from the resolved &b=.
             :data-signals:branch (format "'%s'" branch)
             ;; as-of (PR C): the tx being viewed, or "" for live. When set, every
             ;; POST carries it and the server forces read-only access.
             :data-signals:at (format "'%s'" (or at ""))
             :data-signals:histpanel "false"     ; 🕘 history modal open? (live page)
             :data-signals:bname "''"            ; new-branch name (fork modal)
             :data-signals:branchact "''"        ; fork | delete | merge-preview/apply
             :data-signals:branchpanel "false"   ; 🌿 modal open?
             ;; merge (PR B): source branch + the space-separated set of conflict
             ;; keys ("addr|prop") the owner chose to take from source.
             :data-signals:mergefrom "''"
             :data-signals:mergetake "''"
             ;; server sets $goto on a fork/delete to navigate (full reload) to
             ;; the resulting branch — the #goto effect element below watches it.
             :data-signals:goto "''"
             :data-signals:sid (format "'%s'" sid)
             :data-signals:link (format "'%s'" (or link-token ""))
             :data-signals:sharepanel "false"
             :data-signals:shareact "''"
             :data-signals:plevel "''"
             :data-signals:gtarget "''"
             :data-signals:glevel "'read-write'"
             :data-signals:grantee "''"
             :data-signals:styleprop "'bg'"
             :data-signals:stylesrc "''"
             ;; collapsible toolbar sections (formula bar stays; others toggle)
             :data-signals:fmtbar "false"
             ;; current multi-selection as space-separated "TL:BR" ranges (set by
             ;; app.cljs before a selection-wide action, e.g. Delete -> /clear)
             :data-signals:selcells "''"
             :data-signals:rzcmd "''"
             ;; definitions library (ƒ modal)
             :data-signals:defspanel "false"
             :data-signals:defid "''"
             :data-signals:defsrc "''"
             ;; shared big-editor modal (formula bar / style bar / definitions)
             :data-signals:bigedit "false"
             :data-signals:bigwhat "''"
             :data-signals:big "''"
             :data-signals:help "false"
             ;; dependency-graph view (🕸 modal) — server renders #graphview on open
             :data-signals:graphpanel "false"
             ;; sheet properties (⚙ modal, owner-only) — seeded with current defaults
             :data-signals:propspanel "false"
             :data-signals:pcw (str (sheet/default-col-w sh))
             :data-signals:prh (str (sheet/default-row-h sh))
             :style "font-family:sans-serif;margin:0;padding:.6rem;min-height:100vh;background:var(--bg);color:var(--fg);"}
      [:div {:id "toast" :data-show "$err != ''" :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;background:#c0392b;"
                         "color:#fff;padding:.6rem .9rem;border-radius:6px;font:13px sans-serif;"
                         "cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:20;")}]
      (h/raw (help-html))
      (when-not asof? (h/raw (defs-html storage-id)))
      (when-not asof? (h/raw (bigedit-html)))
      (when (and owner? (not asof?)) (h/raw (props-html)))
      (when-not asof? (history-modal storage-id sname branch revisions link-token owner?))
      (when-not asof? (graph-modal-html))
      ;; ── toolbar row 1: sheet management + sharing + identity ───────────
      [:div {:class "toolrow"}
       (sheet-picker uid storage-id sname)
       ;; the branch picker stays in as-of (you can switch branch); its owner
       ;; fork/merge/delete controls are hidden while viewing read-only history.
       (branch-bar uid storage-id sname branch link-token (and owner? (not asof?)))
       (if asof?
         ;; read-only history view: a banner + revision picker + Back-to-live
         (asof-banner storage-id sname branch at revisions link-token owner?)
         ;; live: new-sheet, sharing, format/defs/props, help, history
         (list
          [:input {:id "sheetbox" :class "tool" :placeholder "new sheet…"
                   :data-on:keydown "evt.key==='Enter' && el.value && (location.href='/?s='+el.value)"
                   :title "type a name + Enter to create/open one of your sheets"
                   :style "width:6rem;"}]
          ;; navigate (full reload) when the server sets $goto (fork/delete result)
          [:div {:id "goto" :data-effect "$goto && window.location.assign($goto)" :style "display:none;"}]
          (h/raw (share-html uid storage-id link-token))
          [:span {:class "spacer"}]
          [:button {:class "btn" :data-class:active "$fmtbar"
                    :data-on:click "$fmtbar = !$fmtbar" :title "format / style controls"} "🎨"]
          [:button {:class "btn" :data-on:click "$defspanel=true" :title "sheet definitions (reusable functions)"} "ƒ"]
          [:button {:class "btn" :data-on:click "$graphpanel=true, @post('/graph')" :title "dependency graph"} "🕸"]
          (when owner?
            [:button {:class "btn" :data-on:click "$propspanel=true" :title "sheet properties"} "⚙"])
          [:button {:class "btn" :data-on:click "$histpanel=true" :title "history — view an earlier revision"} "🕘"]
          [:button {:class "btn" :data-on:click "$help=true" :title "help / quick guide"} "?"]))
       ;; who am I + sign out
       [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
        (or (:name (auth/user-info uid)) uid)]
       [:form {:method "post" :action "/logout" :style "margin:0;"}
        [:button {:class "btn"} "sign out"]]]
      ;; ── toolbar row 2: cell reference + formula bar (live only) ─────────
      (when-not asof?
       [:div {:class "toolrow"}
       ;; address box: $sel via data-bind; Enter jumps (app.cljs scrolls there +
       ;; selects). The keydown listener is attached in app.cljs (a scroll action).
       [:input {:id "addrbox" :class "tool mono" :data-bind:sel "" :placeholder "A1"
                :style "width:5rem;text-align:center;"}]
       ;; editing via the formula bar still drives presence on the SELECTED cell
       ;; (so it shows the marching-ants self marker and locks it for peers).
       ;; formula bar shares $v with the floating #editor, so the two stay live-
       ;; synced: typing in either updates $v and the other reflects it.
       [:input {:id "fbar" :class "tool mono" :data-bind:v "" :placeholder "value or =formula"
                :data-on:focus "$edit=true, @post('/presence')"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/cell'))"
                :data-on:blur "$cell=$sel, @post('/cell'), $edit=false, @post('/presence')"
                :style "flex:1;"}]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$v, $bigwhat='v', $bigedit=true"} "⤢"]])
      ;; ── toolbar row 3: style of the selected cell (collapsible) ────────
      ;; prop dropdown + a literal-or-=formula source, applied to $sel on Enter
      ;; (like the formula bar — no separate button). $val is the cell's own
      ;; value, e.g. =(if (> $val 100) "tomato" "white"). Hidden until the 🎨
      ;; toggle ($fmtbar) reveals it — keeps the default bar lean.
      (when-not asof?
       [:div {:class "toolrow" :data-show "$fmtbar"}
       [:select {:id "stylepropbox" :class "tool" :data-bind:styleprop ""
                 :data-on:change
                 (str "const c=document.getElementById('c_'+$sel);"
                      "c && ($v=c.dataset.raw||'',"
                      "$stylesrc=c.dataset.sty?(JSON.parse(c.dataset.sty)[$styleprop]||''):'')")
                 :title "style / format property of the selected cell"}
        (for [p (concat (keys style-css) value-props meta-props)] [:option {:value (name p)} (name p)])]
       [:input {:id "stylesrcbox" :class "tool mono" :data-bind:stylesrc ""
                :placeholder "color / mask / =formula (use $val) — Enter to apply"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/style'))"
                :style "flex:1;"}]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$stylesrc, $bigwhat='style', $bigedit=true"} "⤢"]])
      ;; logical-scroll viewport (custom wheel + scrollbars in /app.js)
      (grid-layers sh {:r0 0 :c0 0})

      ;; ── client ⇆ server bridge (no hidden trigger buttons) ─────────────
      ;; app.cljs does the imperative work (scroll / edit / resize / keyboard)
      ;; and, when the server must hear about it, dispatches a `sr-*` CustomEvent
      ;; on window; these declarative handlers turn each into the Datastar action,
      ;; pulling the carried data off evt.detail. The persistent collaboration
      ;; stream lives on its OWN element (#streamer) so app.cljs can pick its
      ;; datastar-fetch lifecycle apart from the @posts for reconnect.
      ;; live only: the persistent collaboration stream + the full control bridge.
      ;; In a read-only as-of view there is no live room, so we open no stream and
      ;; expose ONLY scroll (→ /viewat, which renders the historical window). Every
      ;; mutating sr-* event is simply absent, so nothing can edit the past — and
      ;; the server also forces read-only when $at is set (belt and suspenders).
      (when asof?
        [:div {:id "ctl" :data-sid sid :style "display:none;"
               :data-on:sr-view__window "$r0=evt.detail.r0, $c0=evt.detail.c0, @post('/viewat')"}])
      (when-not asof?
       (list
      [:div {:id "streamer" :data-on:sr-open__window "@get('/stream')"
             :style "display:none;"} ""]
      [:div {:id "ctl" :data-sid sid :style "display:none;"
             :data-on:sr-view__window "$r0=evt.detail.r0, $c0=evt.detail.c0, @post('/view')"
             :data-on:sr-size__window "$rzcmd=evt.detail.cmd, @post('/size')"
             ;; per-user undo / redo (Ctrl+Z / Ctrl+Shift+Z|Ctrl+Y from app.cljs)
             :data-on:sr-undo__window "@post('/undo')"
             :data-on:sr-redo__window "@post('/redo')"
             ;; clear the current selection (Delete/Backspace from app.cljs)
             :data-on:sr-clear__window "$selcells=evt.detail.ranges, @post('/clear')"
             ;; clipboard (Ctrl/⌘ C / X / V from app.cljs) — selection rides in $selcells
             :data-on:sr-copy__window  "$selcells=evt.detail.ranges, @post('/copy')"
             :data-on:sr-cut__window   "$selcells=evt.detail.ranges, @post('/cut')"
             :data-on:sr-paste__window "$selcells=evt.detail.ranges, @post('/paste')"
             ;; commit an in-progress edit (app.cljs fires this before a resize
             ;; drag, whose preventDefault would otherwise swallow the blur)
             :data-on:sr-commit__window "$edit && ($cell=$sel, @post('/cell'), $edit=false, $celledit=false, @post('/presence'))"
             ;; select: move cursor + mirror the cell's value/style into the bars
             :data-on:sr-select__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "$stylesrc=c&&c.dataset.sty?(JSON.parse(c.dataset.sty)[$styleprop]||''):'';"
                  "$edit=false; $celledit=false; @post('/presence')")
             ;; start editing in-cell: load the cell's source into $v, take the edit
             ;; lock, and reveal the floating editor (app.cljs already positioned it)
             :data-on:sr-edit__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "$edit=true; $celledit=true; @post('/presence')")} ""]))]]))))

;; --- SSE (official Datastar SDK) ----------------------------------------

;; Optional SSE tracing: set SALTRIM_SSE_DEBUG=1 to log every server-sent event
;; (type + a snippet of its data lines) to the console. Implemented as a Datastar
;; write profile — the SDK's designed seam for this — so it sees every event the
;; server emits through the SDK, on both the one-shot @post responses and the
;; persistent /stream. (The raw WebKit flush comment bypasses the SDK, so
;; `flush-tick!` logs itself.) Off by default = zero overhead.
(defn render-cells
  "Cell-input HTML for addrs, positioned window-relative to view (cbase,rbase)."
  [sh addrs view]
  (let [[cb rb] (view-base view)]
    (apply str (map #(let [{:keys [ci ri]} (addr/parse %)]
                       (str (h/html (cell-input sh % ci ri cb rb))))
                    addrs))))

(defn- peer-marker
  "Overlay div for one peer's cursor, positioned window-relative to `view`. An
   editing peer's marker captures pointer events (locks the cell beneath)."
  [sh view {:keys [cursor editing color uname]}]
  (let [{:keys [ci ri]} (addr/parse cursor)
        [cb rb] (view-base view)
        editing? (= editing cursor)
        tag (or uname "•")
        ;; the tag normally floats above the cell (CSS top:-15px); on the top
        ;; rendered row that's clipped by #cellclip's overflow, so flip it below.
        top-row? (zero? (- ri rb))
        tag-style (str "background:" color
                       (when top-row? (str ";top:" (dec (row-h sh ri)) "px;border-radius:0 3px 3px 3px")))
        base (format (str "left:%dpx;top:%dpx;width:%dpx;height:%dpx;border-color:%s;")
                     (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                     (dec (col-w sh ci)) (dec (row-h sh ri)) color)]
    (str (h/html
          [:div {:class (str "peer" (when editing? " editing"))
                 :style (if editing?
                          (str base "background:" (rgba color "0.16")
                               ";pointer-events:auto;cursor:not-allowed;")
                          base)}
           [:span {:class "peertag" :style tag-style}
            (if editing? (str tag " editing…") tag)]]))))

(defn peers-html
  "Overlay markers for every OTHER session in the viewer's ROOM whose cursor
   falls in viewer-sid's window. Rendered relative to the viewer's own view so
   coords line up. `room` = [sheet-id branch]."
  [viewer-sid room]
  (let [view (session-view viewer-sid)
        sh   (:sh (@sheets* room))]
    (apply str
           (for [[sid s] @sessions*
                 :when (and (not= sid viewer-sid) (= room (:room s))
                            (:cursor s) (in-window? view (:cursor s)))]
             (peer-marker sh view s)))))

(defn self-html
  "THIS session's own selection / editing marker for its #self overlay, rendered
   window-relative to its own view. Empty when there is no cursor or it scrolled
   out of the window. `room` = [sheet-id branch]."
  [sid room]
  (let [s    (@sessions* sid)
        view (session-view sid)
        a    (:cursor s)
        sh   (:sh (@sheets* room))]
    (if (and s sh (= room (:room s)) a (in-window? view a))
      (let [{:keys [ci ri]} (addr/parse a)
            [cb rb] (view-base view)]
        (str (h/html
              [:div {:class (str "selfcell" (when (= (:editing s) a) " editing"))
                     :style (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx;"
                                    (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                                    (dec (col-w sh ci)) (dec (row-h sh ri)))}])))
      "")))

(defn- def-names
  "The symbols a chunk declares — the names of every top-level (def…)/(defn…)
   form — shown as badges on the collapsed card. Empty source -> [\"untitled\"]."
  [src]
  (let [ns (map second (re-seq #"\(def[a-z]*\s+([A-Za-z0-9*+!?<>=_.%/-]+)" (str src)))]
    (if (seq ns) (vec ns) ["untitled"])))

(defn- fmt-edited
  "Epoch-ms -> \"yyyy-MM-dd HH:mm\" (local), or nil."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli (long ms))
                                                (java.time.ZoneId/systemDefault)))))

(defn deflib-html
  "Inner HTML of #deflib for session `sid`. An ACCORDION: each chunk shows
   collapsed (tier 1) as its declared-name badges + last-edit time, with Edit /
   delete. Edit (`/deflock`) expands it (tier 2) into a textarea bound to $defsrc
   with Save / Cancel and a ⤢ that opens the shared big editor (tier 3). A chunk
   held by another session shows a lock badge and stays collapsed. `sid` may be
   nil (initial page render: all read-only, no own-edit). `room` = [sheet-id branch]."
  [sid room]
  (let [sh     (:sh (@sheets* room))
        chunks (when sh (sheet/defs sh))
        card   "border:1px solid var(--grid);border-radius:6px;padding:.45rem .6rem;margin:.4rem 0;"
        row    "display:flex;align-items:center;gap:.4rem;flex-wrap:wrap;"
        when-s "font:11px sans-serif;color:var(--muted);white-space:nowrap;"
        ta     (str "width:100%;box-sizing:border-box;min-height:7rem;margin:.3rem 0;"
                    "white-space:pre;font:13px/1.4 monospace;resize:vertical;")
        badges (fn [src] (for [n (def-names src)] [:span {:class "badge"} n]))]
    (str (h/html
          [:div
           (if (empty? chunks)
             [:p {:style "font:13px sans-serif;color:var(--muted);margin:.3rem 0;"}
              "No definitions yet — add one below."]
             (for [{:keys [id src edited]} chunks
                   :let [editor (def-editor-of room id)
                         mine?  (and sid (= editor sid))]]
               [:div {:style card}
                (cond
                  ;; tier 2/3 — this session is editing: textarea + ⤢ big editor
                  mine?
                  [:div
                   [:div {:style row}
                    (badges src) [:span {:style "flex:1;"}]
                    [:span {:style "font:11px sans-serif;color:var(--accent);"} "editing"]
                    [:button {:class "btn" :title "open the big editor"
                              :data-on:click "$big=$defsrc, $bigwhat='def', $bigedit=true"} "⤢"]]
                   [:textarea {:class "tool mono" :data-bind:defsrc "" :spellcheck "false"
                               :placeholder "(defn double [x] (* 2 x))" :style ta}]
                   [:div {:style "display:flex;gap:.4rem;"}
                    [:button {:class "btn primary" :data-on:click "@post('/defsave')"} "Save"]
                    [:button {:class "btn" :data-on:click "@post('/defunlock')"} "Cancel"]]]

                  ;; locked by another collaborator: collapsed, no Edit
                  editor
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   [:span {:style when-s}
                    (str "🔒 " (or (get-in @sessions* [editor :uname]) "someone") " editing")]]

                  ;; tier 1 collapsed: name badges + last-edit time + Edit / delete
                  :else
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   (when-let [w (fmt-edited edited)] [:span {:style when-s} (str "edited " w)])
                   [:button {:class "btn" :data-on:click (str "$defid='" id "', @post('/deflock')")} "Edit"]
                   [:button {:class "btn" :data-on:click (str "$defid='" id "', @post('/defdel')")
                             :title "delete this definition"} "🗑"]])]))
           [:button {:class "btn" :data-on:click "@post('/defadd')" :style "margin-top:.3rem;"}
            "+ Add definition"]]))))

(defn- bigedit-html
  "A large shared editor modal (#bigedit). Opened from the formula bar, the style
   bar, or a definition card by setting $big (the text to edit), $bigwhat (which
   target: 'v' | 'style' | 'def') and $bigedit=true. Apply writes $big back to the
   target signal and posts to the matching endpoint — entirely declarative."
  []
  (let [ta (str "width:100%;box-sizing:border-box;min-height:52vh;"
                "font:13px/1.5 monospace;white-space:pre;resize:vertical;"
                "border:1px solid var(--line);border-radius:var(--radius);padding:.5rem .6rem;")]
    (str (h/html
          [:div {:id "bigeditwrap" :data-show "$bigedit" :data-on:click "$bigedit=false"
                 :style (str "position:fixed;inset:0;z-index:60;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:48rem;width:100%;padding:1rem 1.1rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
             [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"
                   :data-text (str "$bigwhat==='v' ? 'Edit value / formula' : "
                                   "($bigwhat==='style' ? 'Edit style source' : 'Edit definition')")}]
             [:button {:class "btn" :data-on:click "$bigedit=false" :title "close"} "✕"]]
            [:textarea {:id "bigbox" :class "mono" :data-bind:big "" :spellcheck "false" :style ta}]
            [:div {:style "display:flex;gap:.4rem;margin-top:.5rem;justify-content:flex-end;"}
             [:button {:class "btn" :data-on:click "$bigedit=false"} "Cancel"]
             [:button {:class "btn primary"
                       :data-on:click
                       (str "($bigwhat==='v' ? ($v=$big, $cell=$sel, @post('/cell')) : "
                            "$bigwhat==='style' ? ($stylesrc=$big, $cell=$sel, @post('/style')) : "
                            "($defsrc=$big, @post('/defsave'))), $bigedit=false")} "Apply"]]]]))))

(defn- level-label [lvl]
  (case lvl :read-write "can edit" :read "can view" "no access"))

(defn share-html
  "The #sharebar fragment. Visitors see a read-only badge (their effective
   level, given the link `token` they arrived with). The owner gets a popover
   (toggled by $sharepanel) to set the capability-link level, copy/rotate the
   secret link, and grant/revoke direct per-user shares (name in dev, email in
   prod)."
  [uid sheet-id token]
  (let [owner     (owner-of sheet-id)
        owner?    (= uid owner)
        link      (db/link-grant sheet-id)             ; {:token :level} | nil
        lvl       (:level link)
        grants    (->> (db/sheet-grants sheet-id)
                       (filter #(= :user (:kind %)))
                       (sort-by :grantee))
        url       (str (auth/base-url) "/?t=" (:token link))   ; self-contained capability
        badge     (cond (= lvl :read-write) "🔗 link" (= lvl :read) "🔗 link" :else "🔒 private")
        add-ph    (if (auth/dev-auth?) "name to share with…" "email to share with…")
        row-style "display:flex;align-items:center;gap:.4rem;margin-bottom:.45rem;"]
    (str (h/html
          [:div {:id "sharebar" :style "display:flex;align-items:center;gap:.4rem;position:relative;"}
           (if-not owner?
             [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
              (str "shared by " (or (:name (auth/user-info owner)) owner)
                   " · " (level-label (db/access-level uid sheet-id token)))]
             (list
              [:button {:class "btn" :data-on:click "$sharepanel = !$sharepanel" :title "sharing"}
               badge]
              [:div {:data-show "$sharepanel"
                     :style (str "position:absolute;top:118%;left:0;z-index:30;width:24rem;"
                                 "background:var(--bg);border:1px solid var(--line);border-radius:6px;"
                                 "box-shadow:0 4px 16px rgba(0,0,0,.18);padding:.7rem;"
                                 "font:12px sans-serif;color:var(--fg);")}
               ;; capability link
               [:div {:style row-style}
                [:span {:style "flex:1;"} "Anyone with the link"]
                [:select {:class "tool"
                          :data-on:change "$shareact='link', $plevel=el.value, @post('/share')"}
                 [:option {:value "none"       :selected (nil? lvl)}          "no access"]
                 [:option {:value "read"       :selected (= lvl :read)}       "can view"]
                 [:option {:value "read-write" :selected (= lvl :read-write)} "can edit"]]]
               (when link
                 [:div {:style "display:flex;gap:.3rem;margin-bottom:.5rem;"}
                  [:input {:readonly true :value url :title "secret share link"
                           :style (str "flex:1;box-sizing:border-box;font:11px monospace;"
                                       "padding:4px 6px;border:1px solid var(--grid);"
                                       "border-radius:var(--radius);color:var(--muted);")}]
                  [:button {:class "btn" :title "make a new link (invalidates the old one)"
                            :data-on:click "$shareact='rotate', @post('/share')"} "↻"]])
               ;; per-user grants
               [:div {:style "border-top:1px solid var(--grid);padding-top:.5rem;"}
                (if (seq grants)
                  (for [{:keys [grantee level]} grants]
                    [:div {:style row-style}
                     [:span {:style "flex:1;"} (or (:name (auth/user-info grantee)) grantee)]
                     [:span {:style "color:var(--muted);"} (level-label level)]
                     [:button {:class "btn" :title "remove"
                               :data-on:click (str "$shareact='revoke', $grantee='" grantee "', @post('/share')")}
                      "✕"]])
                  [:div {:style "color:var(--muted);margin-bottom:.45rem;"} "not shared with anyone yet"])
                ;; add person
                [:div {:style "display:flex;gap:.3rem;margin-top:.4rem;"}
                 [:input {:class "tool" :data-bind:gtarget "" :placeholder add-ph :style "flex:1;"}]
                 [:select {:class "tool" :data-bind:glevel ""}
                  [:option {:value "read-write"} "edit"]
                  [:option {:value "read"} "view"]]
                 [:button {:class "btn" :data-on:click "$shareact='grant', @post('/share')"} "share"]]]]))]))))

(defn- merge-val
  "Compact display of a property's source for the conflict list ((empty) for a
   deletion / absence; truncated if long)."
  [s]
  (cond (nil? s)          [:em {:style "color:var(--muted);"} "(empty)"]
        (> (count s) 48)  (str (subs s 0 48) "…")
        :else             s))

(defn merge-result-html
  "Inner #mergeresult fragment for a merge PREVIEW of `source` → `target`: the
   clean-merge count, and for each conflict a checkbox that toggles its key in
   $mergetake (take source) plus the two competing values. Empty when already up
   to date. Ends with the Apply button."
  [source target {:keys [take conflicts]}]
  (let [nt (count take) nc (count conflicts)
        mono "font:12px monospace;white-space:pre-wrap;word-break:break-all;"]
    (str (h/html
          [:div {:id "mergeresult" :style "margin-top:.6rem;"}
           (if (and (zero? nt) (zero? nc))
             [:p {:style "color:var(--muted);margin:.2rem 0;"}
              (str "✓ 🌿 " target " is already up to date with 🌿 " source ".")]
             (list
              [:p {:style "margin:.2rem 0;"}
               [:strong (str nt)] (str " cell-" (if (= nt 1) "property" "properties") " merge cleanly")
               (when (pos? nc) [:span (str " · " nc " conflict" (when (> nc 1) "s"))])]
              (when (pos? nc)
                [:div {:style (str "max-height:30vh;overflow:auto;border:1px solid var(--grid);"
                                   "border-radius:6px;padding:.2rem .5rem;margin:.3rem 0;")}
                 [:p {:style "color:var(--muted);font-size:11px;margin:.2rem 0 .3rem;"}
                  "Both branches changed these. Tick to take " [:strong (str "🌿 " source)]
                  "'s version; unticked keeps " [:strong (str "🌿 " target)] "'s."]
                 (for [{k :key csrc :source ctgt :target} conflicts
                       :let [ks (mrg/key->str k) [a p] k]]
                   [:label {:style (str "display:flex;gap:.4rem;align-items:baseline;"
                                        "padding:.25rem 0;border-top:1px solid var(--grid);")}
                    [:input {:type "checkbox"
                             :data-on:change (str "$mergetake = evt.target.checked"
                                                  " ? ($mergetake+' " ks "').trim()"
                                                  " : $mergetake.split(' ').filter(x=>x&&x!=='" ks "').join(' ')")}]
                    [:span {:style "flex:1;"}
                     [:strong (str a)] " " [:span {:style "color:var(--muted);font-size:11px;"} (name p)]
                     [:div {:style mono} "↱ " (merge-val csrc)]
                     [:div {:style (str mono "color:var(--muted);")} "= " (merge-val ctgt)]]])])
              [:button {:class "btn primary" :style "margin-top:.4rem;"
                        :data-on:click "$branchact='apply', @post('/merge')"}
               "Apply merge"]))]))))

(defn- node-label
  "A cell's display name in the graph: its `:label` meta-prop if set, else the
   address."
  [sh a]
  (let [l (sheet/style-value sh a :label)]
    (if (and (string? l) (not (str/blank? l))) l (str a))))

(defn graph-svg
  "Render the layered DAG (`graph/build` output) as an inline SVG: nodes placed
   left→right by dependency depth, edges arrow from a cell to the cells that read
   it. A node click selects the cell (and closes the modal)."
  [sh {:keys [nodes edges layer]}]
  (let [COLW 168 ROWH 40 NW 132 NH 26 PAD 16
        by-layer (->> nodes (group-by layer) (into (sorted-map)))
        pos (into {}
                  (for [[lyr ns] by-layer
                        [i a] (map-indexed
                               vector
                               (sort-by (fn [a] (let [{:keys [ci ri]} (addr/parse a)] [ri ci])) ns))]
                    [a [(+ PAD (* (long lyr) COLW)) (+ PAD (* i ROWH))]]))
        nlayers (inc (long (apply max 0 (vals layer))))
        maxrows (apply max 1 (map count (vals by-layer)))
        W (+ PAD (* nlayers COLW))
        H (+ PAD (* maxrows ROWH))]
    (str (h/html
          [:svg {:viewBox (format "0 0 %d %d" W H) :width W :height H
                 :style "font:11px sans-serif;min-width:100%;"}
           [:defs
            [:marker {:id "arr" :viewBox "0 0 10 10" :refX "9" :refY "5"
                      :markerWidth "7" :markerHeight "7" :orient "auto-start-reverse"}
             [:path {:d "M0,0 L10,5 L0,10 z" :fill "#9ec9ee"}]]]
           (for [[f t] edges :let [[fx fy] (pos f) [tx ty] (pos t)]]
             [:line {:x1 (+ fx NW) :y1 (+ fy (quot NH 2)) :x2 tx :y2 (+ ty (quot NH 2))
                     :stroke "#9ec9ee" :stroke-width "1.5" :marker-end "url(#arr)"}])
           (for [a nodes :let [[x y] (pos a) lbl (node-label sh a)]]
             [:g {:data-on:click (str "$sel='" a "', $graphpanel=false") :style "cursor:pointer;"}
              [:title (str a)]
              [:rect {:x x :y y :width NW :height NH :rx 4
                      :fill "#f4f6f8" :stroke "#2f8fd8" :stroke-width "1"}]
              [:text {:x (+ x 8) :y (+ y 17) :fill "#3a4149"}
               (let [s (str lbl)] (if (> (count s) 19) (str (subs s 0 18) "…") s))]])]))))

(defn login-page [err]
  (let [field (str "font:13px sans-serif;padding:6px 8px;border:1px solid #c7ccd1;"
                   "border-radius:4px;")]
    (str
     "<!doctype html>"
     (h/html
      [:html
       [:head [:meta {:charset "utf-8"}] [:title "SaltRim — sign in"]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]]
       ;; explicit light bg so an OS dark theme can't black out the page; the
       ;; centered column lives in an inner wrapper.
       [:body {:style "font-family:sans-serif;margin:0;min-height:100vh;background:#fefefe;color:#3a4149;"}
        [:div {:style "max-width:24rem;margin:0 auto;padding:14vh 1rem 0;"}
        [:img {:src "/SaltRim.png" :alt "SaltRim"
               :style "display:block;margin:0 auto .6rem;width:180px;height:auto;"}]
        [:p {:style "color:#7a828b;text-align:center;margin-top:0;"} "Sign in to open your sheets."]
        (when err
          [:p {:style "color:#c0392b;font:13px sans-serif;"} (url-decode err)])
        [:div {:style "display:flex;flex-direction:column;gap:.6rem;"}
         (for [[k p] (auth/providers)]
           [:a {:href (str "/auth/" (name k))
                :style (str field "text-align:center;text-decoration:none;"
                            "background:#f4f6f8;color:#3a4149;display:block;")}
            (str "Continue with " (:label p))])
         (when (auth/dev-auth?)
           [:form {:method "get" :action "/auth/dev"
                   :style "display:flex;gap:.4rem;"}
            [:input {:name "name" :placeholder "your name (dev login)"
                     :autofocus true :style (str field "flex:1;")}]
            [:button {:style field} "Sign in"]])]]]]))))

(defn denied-page [uid]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head [:meta {:charset "utf-8"}] [:title "SaltRim — no access"]]
     [:body {:style "font-family:sans-serif;max-width:24rem;margin:14vh auto;"}
      [:h1 {:style "font-weight:600;"} "No access"]
      [:p {:style "color:#666;"}
       "This sheet doesn't exist or isn't shared with you."]
      [:p [:a {:href "/"} "Back to your sheets"]]]])))

