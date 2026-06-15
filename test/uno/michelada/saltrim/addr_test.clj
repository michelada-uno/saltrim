(ns uno.michelada.saltrim.addr-test
  (:require [clojure.test :refer [deftest testing is are]]
            [uno.michelada.saltrim.addr :as a]))

(deftest col-idx-roundtrip
  (are [col idx] (and (= idx (a/col->idx col)) (= col (a/idx->col idx)))
    "A" 0  "Z" 25  "AA" 26  "AB" 27  "AAB" 703))

(deftest parse-make
  (is (= {:col "AAB" :row 1234 :ci 703 :ri 1233} (a/parse "AAB1234")))
  (is (= "A1" (a/make 0 0)))
  (is (= "AB1234" (a/make 27 1233))))

(deftest valid
  (are [s ok] (= ok (a/valid? s))
    "A1" true  "AAB1234" true  "A1:A3" false  "A" false  "1" false))

(deftest ranges
  (testing "vertical / horizontal / rectangle row-major"
    (is (= ["A1" "A2" "A3"]            (a/range-cells "A1" "A3")))
    (is (= ["A1" "B1" "C1"]            (a/range-cells "A1" "C1")))
    (is (= ["A1" "B1" "A2" "B2"]       (a/range-cells "A1" "B2")))
    (is (= ["A1" "A2" "A3"]            (a/range-cells "A3" "A1"))))) ; order-insensitive
