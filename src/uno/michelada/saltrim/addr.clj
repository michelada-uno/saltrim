(ns uno.michelada.saltrim.addr
  "Spreadsheet addressing. Address = COL + ROW, e.g. \"A1\", \"AAB1234\".
   COL = bijective base-26 letters (A=1, Z=26, AA=27, ...). ROW = 1-based int.
   No colon in an address — colon is reserved for ranges (\"A1:C3\").

   Two id spaces:
   - address string \"AAB1234\"  (canonical: signal/spin id, user-facing)
   - 0-based [col-idx row-idx]   (grid geometry / viewport math)")

(defn col->idx
  "Column letters -> 0-based index. \"A\"->0, \"Z\"->25, \"AA\"->26."
  [^String col]
  (dec
   (reduce (fn [acc ch]
             (+ (* acc 26) (- (int (Character/toUpperCase ch)) (int \A) -1)))
           0 col)))

(defn idx->col
  "0-based index -> column letters. 0->\"A\", 25->\"Z\", 26->\"AA\"."
  [idx]
  (loop [n (inc idx) out ""]
    (if (pos? n)
      (let [r (mod (dec n) 26)]
        (recur (quot (dec n) 26) (str (char (+ (int \A) r)) out)))
      out)))

(defn parse
  "\"AAB1234\" -> {:col \"AAB\" :row 1234 :ci <0-based> :ri <0-based>}."
  [addr]
  (let [[_ col row] (re-matches #"([A-Za-z]+)([0-9]+)" addr)
        row-n (Long/parseLong row)]
    {:col col :row row-n :ci (col->idx col) :ri (dec row-n)}))

(defn make
  "0-based col/row indices -> canonical address string."
  [ci ri]
  (str (idx->col ci) (inc ri)))

(defn valid?
  [addr]
  (boolean (re-matches #"[A-Za-z]+[0-9]+" (str addr))))

(defn range-cells
  "Inclusive rectangle from address a to address b, ROW-MAJOR.
   \"A1\" \"A3\" -> [A1 A2 A3] ; \"A1\" \"C1\" -> [A1 B1 C1]
   \"A1\" \"B2\" -> [A1 B1 A2 B2]."
  [a b]
  (let [{ca :ci ra :ri} (parse a)
        {cb :ci rb :ri} (parse b)
        [c0 c1] (sort [ca cb])
        [r0 r1] (sort [ra rb])]
    (vec (for [r (range r0 (inc r1))
               c (range c0 (inc c1))]
           (make c r)))))
