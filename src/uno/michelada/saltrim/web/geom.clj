(ns uno.michelada.saltrim.web.geom
  "Pure grid geometry + small stateless helpers shared across the web layer."
  (:require
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.constants :refer [MAX-COLS MAX-ROWS WIN-COLS WIN-ROWS MIN-COLS MIN-ROWS BUF-COLS BUF-ROWS OVER]]))

(defn view-base
  "[col-base row-base] = top-left index of the rendered (overscanned) window."
  [{:keys [r0 c0]}]
  [(max 0 (- (long c0) OVER)) (max 0 (- (long r0) OVER))])

(defn window
  "Visible cell coords [ci-range ri-range] for first row/col r0 c0 (clamped)."
  [r0 c0]
  (let [[cb rb] (view-base {:r0 r0 :c0 c0})]
    [(range cb (min MAX-COLS (+ cb WIN-COLS)))
     (range rb (min MAX-ROWS (+ rb WIN-ROWS)))]))

;; Axis sizing: columns/rows default to CW/RH but carry sparse per-index px
;; overrides (sheet :cols/:rows). Absolute offset of an index = uniform base
;; plus the (override-base) deltas of every sized index BEFORE it. The same
;; arithmetic runs in /app.js (from the maps in #meta) so client + server agree.

(defn col-w [sh ci] (or (sheet/col-width sh ci) (sheet/default-col-w sh)))
(defn row-h [sh ri] (or (sheet/row-height sh ri) (sheet/default-row-h sh)))

(defn- axis-off
  "Absolute start px of `i` along an axis whose default size is `base` and whose
   sparse overrides are `om` (index -> size)."
  [om base i]
  (reduce-kv (fn [acc k v] (cond-> acc (< (long k) (long i)) (+ (- (long v) base))))
             (* (long i) base) om))

(defn axis-x [sh ci] (axis-off (sheet/col-widths sh) (sheet/default-col-w sh) ci))
(defn axis-y [sh ri] (axis-off (sheet/row-heights sh) (sheet/default-row-h sh) ri))

;; --- colors -------------------------------------------------------------

(defn rgba [hex a]
  (let [h (subs hex 1)]
    (format "rgba(%d,%d,%d,%s)"
            (Integer/parseInt (subs h 0 2) 16)
            (Integer/parseInt (subs h 2 4) 16)
            (Integer/parseInt (subs h 4 6) 16) a)))

(defn- used-max
  "[max-ci max-ri] over non-empty cells (-1 if none)."
  [sh]
  (reduce (fn [[cm rm] a] (let [{:keys [ci ri]} (addr/parse a)]
                            [(max cm ci) (max rm ri)]))
          [-1 -1] (sheet/cells sh)))

(defn total-px
  "Logical scroll extent [w h] px (cells area only, no gutter/header): covers the
   used range and the current view plus a buffer. Just numbers for the custom
   scrollbar — no DOM element is this big."
  [sh r0 c0]
  (let [[cm rm] (used-max sh)
        cols (min MAX-COLS (+ (max MIN-COLS (inc cm) (+ (long c0) WIN-COLS)) BUF-COLS))
        rows (min MAX-ROWS (+ (max MIN-ROWS (inc rm) (+ (long r0) WIN-ROWS)) BUF-ROWS))]
    ;; total extent = absolute offset of the index just past the buffered range
    ;; (folds in any sparse width/height overrides)
    [(axis-x sh cols) (axis-y sh rows)]))

(defn in-window? [{:keys [r0 c0]} addr]
  (let [{:keys [ci ri]} (addr/parse addr)]
    (and (<= (- (long c0) OVER) ci (+ (long c0) WIN-COLS))
         (<= (- (long r0) OVER) ri (+ (long r0) WIN-ROWS)))))

;; --- rendering ----------------------------------------------------------

(defn pretty-err [msg]
  (let [m (str msg)]
    (cond
      (re-find #"cannot be cast.*?(Number|Long|Double|Integer|Ratio|BigDecimal|BigInt)" m)
      "type error (number expected)"
      (re-find #"cannot be cast" m)    "type error"
      (re-find #"Divide by zero" m)    "divide by zero"
      (re-find #"Could not resolve symbol" m) "unknown name or function in the formula"
      (re-find #"circular" m)          "circular reference"
      (re-find #"locked by another" m) "cell is being edited by another collaborator"
      :else m)))

(defn qparam [req k]
  (some->> (:query-string req)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]+)")))
           second))

;; --- auth routes (login page, OAuth redirects, logout) -------------------

(defn url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
(defn url-decode [s] (java.net.URLDecoder/decode (str s) "UTF-8"))

