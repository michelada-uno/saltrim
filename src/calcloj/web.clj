(ns calcloj.web
  "Step 2 — minimal editable grid over the sheet engine, driven by Datastar.

   Interaction:
   - Each cell is an <input> showing its COMPUTED value.
   - On change, two shared signals ($cell, $v) carry address+value and we
     @post('/cell'). Server updates the sheet, settles, and SSE-patches the
     whole grid body (MVP — Step 3 scopes this to the viewport).

   Run:  clj -M:web   then open http://localhost:8080"
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [calcloj.addr :as addr]
            [calcloj.sheet :as sheet]))

(def ^:private n-cols 10)
(def ^:private n-rows 10)

(defonce ^:private sheet* (atom nil))
(defn- the-sheet [] (or @sheet* (reset! sheet* (sheet/create-sheet))))

;; --- rendering ----------------------------------------------------------

(defn- display
  "Computed value of a cell as a display string."
  [sh a]
  (let [v (sheet/value sh a)]
    (cond
      (nil? v)          ""
      (map? v)          "#ERR"          ; {:error ...}
      (double? v)       (str v)
      :else             (str v))))

(defn- cell-id
  "Selector-safe element id for a cell (no ':')."
  [a] (str "c_" (str/replace a ":" "_")))

(defn- cell-input [sh a]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)]      ; formula text or literal raw
    [:input
     {:id (cell-id a)
      :value disp                              ; shows computed value...
      :data-raw raw                            ; ...but focus reveals raw for editing
      :data-val disp
      :data-on:focus "el.value=el.dataset.raw"
      :data-on:blur  "el.value=el.dataset.val"
      :data-on:change
      (format "$cell='%s'; $v=el.value; @post('/cell')" a)
      :style "width:7rem;border:1px solid #ddd;padding:2px 4px;font:13px monospace;"}]))

(defn- grid-rows [sh]
  (h/html
   (list
    ;; header row
    [:tr
     [:th {:style "width:2rem;background:#f3f3f3;"} ""]
     (for [ci (range n-cols)]
       [:th {:style "background:#f3f3f3;font:12px sans-serif;"} (addr/idx->col ci)])]
    ;; body rows
    (for [ri (range n-rows)]
      [:tr
       [:th {:style "background:#f3f3f3;font:12px sans-serif;"} (inc ri)]
       (for [ci (range n-cols)]
         [:td (cell-input sh (addr/make ci ri))])]))))

(defn- page [sh]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "calcloj"]
      [:script {:type "module" :src "/datastar.js"}]]
     [:body {:data-signals "{cell:'', v:'', err:''}"
             :style "font-family:sans-serif;padding:1rem;"}
      ;; toast: shows $err, click to dismiss
      [:div {:id "toast"
             :data-show "$err != ''"
             :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;"
                         "background:#c0392b;color:#fff;padding:.6rem .9rem;"
                         "border-radius:6px;font:13px sans-serif;cursor:pointer;"
                         "box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:10;")}]
      [:h2 "calcloj"]
      [:p {:style "color:#666;"} "Edit a cell. Formula: "
       [:code "=(+ #cell A1 #cell B1)"] " · range: "
       [:code "=(reduce + #cells A1:A3)"] " · click a cell to edit its source."]
      [:table {:id "grid" :style "border-collapse:collapse;"}
       (grid-rows sh)]]])))

;; --- SSE ----------------------------------------------------------------

(defn- patch-cells-event
  "Patch ONLY the given cells, matched by id (mode outer). No whole-grid
   re-render — this is what scales to a 10000x10000 sheet (Step 3)."
  [sh addrs]
  (str "event: datastar-patch-elements\n"
       "data: mode outer\n"
       (->> addrs
            (map (fn [a] (str "data: elements " (str (h/html (cell-input sh a))) "\n")))
            (apply str))
       "\n"))

(defn- signals-event
  "SSE that patches frontend signals (e.g. the $err toast)."
  [m]
  (str "event: datastar-patch-signals\n"
       "data: signals " (json/write-value-as-string m) "\n\n"))

(defn- sse-response [body]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"}
   :body body})

;; --- handlers -----------------------------------------------------------

(defn- read-json [req]
  (when-let [b (:body req)]
    (json/read-value (slurp b) json/keyword-keys-object-mapper)))

(def ^:private edit-lock (Object.))

(defn- pretty-err [msg]
  (let [m (str msg)]
    (cond
      (re-find #"cannot be cast" m)        "type error (number expected)"
      (re-find #"Divide by zero" m)        "divide by zero"
      (re-find #"unknown cell" m)          "reference to empty cell"
      (re-find #"disallowed symbol" m)     "not allowed in a formula"
      :else m)))

(defn- handle-cell [req]
  (let [sh (the-sheet)
        {:keys [cell v]} (read-json req)]
    (if (addr/valid? cell)
      ;; One sheet = one execution context with mutable graph state. Serialize
      ;; edits so concurrent requests don't race set-cell!/settle/recompute.
      (locking edit-lock
        (try
          (sheet/set-cell! sh cell (str v))
          (sheet/settle! sh)
          (let [affected (cons cell (sort (sheet/dependents* sh cell)))
                errs (keep (fn [a]
                             (when-let [e (:error (sheet/value sh a))]
                               (str a ": " (pretty-err e))))
                           affected)]
            ;; cell #ERR comes from per-cell patch; toast surfaces the message
            (sse-response (str (patch-cells-event sh affected)
                               (signals-event {:err (if (seq errs)
                                                      (str/join "; " errs) "")}))))
          (catch Throwable e
            ;; set-time error (bad formula, disallowed symbol): no crash, toast
            (sse-response (signals-event {:err (str cell ": " (pretty-err (.getMessage e)))})))))
      (sse-response "\n"))))

(defn- app [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"]            {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body (page (the-sheet))}
    [:get "/datastar.js"] (if-let [r (clojure.java.io/resource "public/datastar.js")]
                            {:status 200
                             :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no datastar"})
    [:post "/cell"]       (handle-cell req)
    {:status 404 :body "not found"}))

(defonce ^:private server* (atom nil))

(defn start! [& [port]]
  (when @server* (@server*))
  (reset! server* (http/run-server #'app {:port (or port 8080)}))
  (println "calcloj on http://localhost:" (or port 8080)))

(defn -main [& _]
  (start!)
  @(promise))
