(ns uno.michelada.saltrim.export
  "Build a STATIC .xlsx snapshot of a loaded sheet (Apache POI). Formulas are NOT
   translated to Excel: each cell exports its current COMPUTED value (numbers stay
   numeric), carrying its presentation — fill / font colour / bold / italic /
   alignment / number-format — and, for a formula cell, the original Clojure
   source as a cell comment. So an exported workbook has no live formulas or
   reactivity; it is a point-in-time copy. Only the workbook WRITER is used here."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet])
  (:import (java.io ByteArrayOutputStream)
           (org.apache.poi.ss.usermodel FillPatternType HorizontalAlignment)
           (org.apache.poi.xssf.usermodel XSSFWorkbook XSSFColor)))

;; --- colour: CSS string -> XSSFColor (nil when unparseable, so we just skip) --

(def ^:private named-colors
  {"white" [255 255 255] "black" [0 0 0] "red" [255 0 0] "green" [0 128 0]
   "blue" [0 0 255] "yellow" [255 255 0] "orange" [255 165 0] "purple" [128 0 128]
   "gray" [128 128 128] "grey" [128 128 128] "silver" [192 192 192]
   "lime" [0 255 0] "navy" [0 0 128] "teal" [0 128 128] "maroon" [128 0 0]
   "olive" [128 128 0] "aqua" [0 255 255] "cyan" [0 255 255] "fuchsia" [255 0 255]
   "magenta" [255 0 255] "gold" [255 215 0] "pink" [255 192 203]
   "tomato" [255 99 71] "salmon" [250 128 114] "khaki" [240 230 140]
   "coral" [255 127 80] "tan" [210 180 140] "beige" [245 245 220]
   "ivory" [255 255 240] "wheat" [245 222 179]})

(defn- hex->rgb [s]
  (let [s (subs s 1)]
    (cond
      (= 3 (count s)) (mapv #(Integer/parseInt (str % %) 16) s)
      (= 6 (count s)) (mapv #(Integer/parseInt (subs s % (+ % 2)) 16) [0 2 4]))))

(defn- css->rgb
  "CSS colour string -> [r g b] (0-255), or nil if not understood."
  [s]
  (when-let [s (some-> s str/trim str/lower-case not-empty)]
    (try
      (cond
        (str/starts-with? s "#")   (hex->rgb s)
        (str/starts-with? s "rgb") (let [[r g b] (->> (re-seq #"\d+" s) (take 3)
                                                       (map #(Integer/parseInt %)))]
                                     (when (and r g b) [r g b]))
        :else                      (named-colors s))
      (catch Exception _ nil))))

(defn- xssf-color ^XSSFColor [rgb]
  (when rgb (XSSFColor. (byte-array (map unchecked-byte rgb)) nil)))

;; --- value + style mapping -------------------------------------------------

(defn- set-value! [cell v]
  (cond
    (nil? v)     nil
    (number? v)  (.setCellValue cell (double v))
    (boolean? v) (.setCellValue cell (boolean v))
    (map? v)     (.setCellValue cell (str "#ERR " (:error v)))   ; {:error msg}
    :else        (.setCellValue cell (str v))))

(def ^:private aligns
  {"left"   HorizontalAlignment/LEFT   "right"  HorizontalAlignment/RIGHT
   "center" HorizontalAlignment/CENTER "centre" HorizontalAlignment/CENTER
   "justify" HorizontalAlignment/JUSTIFY})

(defn- prop [sh a k]
  ;; a style prop's computed value, only when it's a usable string (skip blanks
  ;; and {:error …} from a broken style formula)
  (let [v (sheet/style-value sh a k)]
    (when (string? v) (not-empty (str/trim v)))))

(defn- style-spec [sh a]
  (let [weight (some-> (prop sh a :weight) str/lower-case)
        slant  (some-> (prop sh a :slant) str/lower-case)]
    {:bg     (prop sh a :bg)
     :fg     (prop sh a :fg)
     :bold   (boolean (or (= "bold" weight) (some-> weight parse-long (>= 600))))
     :italic (boolean (#{"italic" "oblique"} slant))
     :align  (some-> (prop sh a :align) str/lower-case aligns)
     :fmt    (prop sh a :format)}))

(defn- styled? [{:keys [bg fg bold italic align fmt]}]
  (or bg fg bold italic align fmt))

(defn- safe-sheet-name [s]
  (let [s (-> (str (or s "Sheet1")) (str/replace #"[:\\/?*\[\]]" " ") str/trim)
        s (if (str/blank? s) "Sheet1" s)]
    (subs s 0 (min 31 (count s)))))

(defn workbook-bytes
  "A static .xlsx (byte[]) of sheet engine `sh`, tab named `sheet-name`."
  ^bytes [sh sheet-name]
  (with-open [wb (XSSFWorkbook.)]
    (let [ws      (.createSheet wb (safe-sheet-name sheet-name))
          data-fmt (.createDataFormat wb)
          helper  (.getCreationHelper wb)
          drawing (.createDrawingPatriarch ws)
          scache  (atom {})
          style-for
          (fn [spec]
            (when (styled? spec)
              (or (@scache spec)
                  (let [cs (.createCellStyle wb) font (.createFont wb)]
                    (when (:bold spec)   (.setBold font true))
                    (when (:italic spec) (.setItalic font true))
                    (when-let [c (xssf-color (css->rgb (:fg spec)))] (.setColor font c))
                    (.setFont cs font)
                    (when-let [c (xssf-color (css->rgb (:bg spec)))]
                      (.setFillForegroundColor cs c)
                      (.setFillPattern cs FillPatternType/SOLID_FOREGROUND))
                    (when-let [al (:align spec)] (.setAlignment cs al))
                    (when-let [m (:fmt spec)]
                      (try (.setDataFormat cs (.getFormat data-fmt m)) (catch Exception _)))
                    (swap! scache assoc spec cs)
                    cs))))
          add-comment!
          (fn [cell text]
            (try
              (let [anchor (.createClientAnchor helper)
                    _      (doto anchor
                             (.setCol1 (.getColumnIndex cell))
                             (.setRow1 (.getRowIndex cell))
                             (.setCol2 (+ 3 (.getColumnIndex cell)))
                             (.setRow2 (+ 4 (.getRowIndex cell))))
                    c      (.createCellComment drawing anchor)]
                (.setString c (.createRichTextString helper text))
                (.setCellComment cell c))
              (catch Exception _ nil)))
          addrs (->> (concat (sheet/cells sh) (keys (sheet/document-styles sh)))
                     (filter addr/valid?) distinct)]
      (doseq [a addrs]
        (let [v    (sheet/value sh a)
              spec (style-spec sh a)
              cs   (style-for spec)]
          (when (or (some? v) cs)
            (let [{:keys [ci ri]} (addr/parse a)
                  row  (or (.getRow ws ri) (.createRow ws ri))
                  cell (.createCell row ci)
                  src  (sheet/raw sh a)
                  lbl  (prop sh a :label)
                  note (cond-> []
                         (and src (str/starts-with? (str/trim (str src)) "="))
                         (conj (str "Formula: " src))
                         lbl (conj (str "Label: " lbl)))]
              (set-value! cell v)
              (when cs (.setCellStyle cell cs))
              (when (seq note) (add-comment! cell (str/join "\n" note)))))))
      (let [baos (ByteArrayOutputStream.)]
        (.write wb baos)
        (.toByteArray baos)))))
