(ns uno.michelada.saltrim.constants
  "Grid geometry shared by the server renderer (web.clj) and the browser
   logical-scroll engine (app.cljs) — one source of truth so client and server
   agree on cell sizes, window size, overscan and scrollbar thickness.")

;; --- geometry -----------------------------------------------------------

(def CW 112)            ; cell width  px
(def RH 26)             ; cell height px
(def GUT 48)            ; row-header gutter px
(def HDR 26)            ; col-header height px
(def MAX-COLS 16384)    ; hard cap for clamping jumps
;; ~600k keeps the spacer div under Firefox's ~17.9M px element limit
;; (600000 * 26 = 15.6M px). See TECHDEBT.md — the giant-spacer scroll model
;; is the real ceiling; want a logical scrollbar that needs no huge div.
(def MAX-ROWS 600000)
(def WIN-COLS 16)       ; window size (+overscan)
(def WIN-ROWS 34)
(def OVER 2)            ; overscan cells
(def MIN-COLS 26)       ; spacer never smaller than this
(def MIN-ROWS 100)
(def BUF-COLS 6)        ; scrollable buffer past used/visible range
(def BUF-ROWS 30)
(def BAR 12)            ; custom scrollbar thickness px
