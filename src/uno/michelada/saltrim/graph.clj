(ns uno.michelada.saltrim.graph
  "Pure layout for the cell dependency-graph view. The engine already tracks each
   formula's deps (`sheet/deps`) and their reverse (`sheet/dependents*`); this
   turns a forward deps-map into a layered DAG ready to render. No engine, no db
   — just data, so it's easy to test.")

(defn build
  "From `deps-map` {addr #{addrs it references}} build a layered DAG.
   Edges are `[from to]` = `from` feeds `to` (to's formula reads from). Nodes =
   every addr in any edge. `layer` = longest-path depth from a source (a node
   with no incoming edge is layer 0), so each node sits to the RIGHT of
   everything it depends on. Returns {:nodes #{…} :edges [[from to]…]
   :layer {addr n}}. The graph is a DAG — value cycles are rejected by the engine
   before they can be stored."
  [deps-map]
  (let [edges (vec (for [[a ds] deps-map, d ds] [d a]))   ; d -> a
        nodes (set (mapcat identity edges))
        preds (reduce (fn [m [f t]] (update m t (fnil conj #{}) f)) {} edges)
        layer (let [seen (atom {})]
                (letfn [(d [n]
                          (or (@seen n)
                              (let [v (if-let [ps (seq (preds n))]
                                        (inc (long (apply max (map d ps))))
                                        0)]
                                (swap! seen assoc n v)
                                v)))]
                  (into {} (map (juxt identity d)) nodes)))]
    {:nodes nodes :edges edges :layer layer}))
