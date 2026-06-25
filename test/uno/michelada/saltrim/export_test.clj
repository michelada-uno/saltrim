(ns uno.michelada.saltrim.export-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.export :as export])
  (:import (org.apache.poi.xssf.usermodel XSSFWorkbook)
           (java.io ByteArrayInputStream)))

(defn- roundtrip
  "Build a sheet via `setup`, export to .xlsx, read it back, and return a map of
   helpers over the first worksheet."
  [setup]
  (let [s (sheet/create-sheet)]
    (setup s)
    (sheet/settle! s)
    (let [bytes (export/workbook-bytes s "the-sheet")
          wb    (XSSFWorkbook. (ByteArrayInputStream. bytes))
          ws    (.getSheetAt wb 0)
          cell  (fn [a] (let [{:keys [ci ri]} (addr/parse a)]
                          (some-> (.getRow ws ri) (.getCell ci))))]
      {:bytes bytes :wb wb :ws ws :cell cell})))

(deftest exports-valid-xlsx
  (let [{:keys [bytes ws]} (roundtrip (fn [s] (sheet/set-cell! s "A1" "hi")))]
    (is (= [0x50 0x4B] [(bit-and 0xff (aget bytes 0)) (bit-and 0xff (aget bytes 1))])
        "starts with the PK zip magic")
    (is (= "the-sheet" (.getSheetName ws)))))

(deftest formula-exports-as-computed-value
  ;; the whole point: a formula becomes its COMPUTED value, not Excel syntax,
  ;; and the original Clojure source is preserved as a cell comment.
  (let [{:keys [cell]} (roundtrip
                        (fn [s]
                          (sheet/set-cell! s "B1" "100")
                          (sheet/set-cell! s "B2" "=(+ #cell B1 242)")))]
    (is (= 100.0 (.getNumericCellValue (cell "B1"))))
    (is (= 342.0 (.getNumericCellValue (cell "B2"))) "formula resolved to its value")
    (is (= "Formula: =(+ #cell B1 242)"
           (some-> (cell "B2") .getCellComment .getString .getString))
        "the Clojure source rides along as a comment")
    (is (nil? (some-> (cell "B1") .getCellComment)) "literals get no formula comment")))

(deftest text-and-errors
  (let [{:keys [cell]} (roundtrip
                        (fn [s]
                          (sheet/set-cell! s "A1" "Сумма")          ; unicode text
                          (sheet/set-cell! s "A2" "=(/ 1 0)")))]    ; runtime error
    (is (= "Сумма" (.getStringCellValue (cell "A1"))))
    (is (clojure.string/starts-with? (.getStringCellValue (cell "A2")) "#ERR")
        "an erroring cell exports its #ERR text, never a broken Excel formula")))

(deftest styles-and-number-format-carry
  (let [{:keys [wb cell]} (roundtrip
                           (fn [s]
                             (sheet/set-cell! s "A1" "bold")
                             (sheet/set-style! s "A1" :weight "bold")
                             (sheet/set-cell! s "B1" "5")
                             (sheet/set-style! s "B1" :bg "#ffcc00")
                             (sheet/set-cell! s "C1" "1234.5")
                             (sheet/set-style! s "C1" :format "#,##0.00")))
        bold? (fn [c] (.getBold (.getFontAt wb (.getFontIndexAsInt (.getCellStyle c)))))]
    (is (true? (bold? (cell "A1"))) "weight=bold -> bold font")
    (is (= "FFFFCC00"
           (.getARGBHex (.getFillForegroundColorColor (.getCellStyle (cell "B1")))))
        "bg=#ffcc00 -> solid fill")
    (is (= "#,##0.00" (.getDataFormatString (.getCellStyle (cell "C1"))))
        "number-format mask passes through as an Excel format code")))

(deftest empty-sheet-exports
  (testing "a sheet with no cells still produces a valid workbook"
    (let [{:keys [bytes ws]} (roundtrip (fn [_] nil))]
      (is (pos? (count bytes)))
      (is (some? ws)))))
