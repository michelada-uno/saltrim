(ns uno.michelada.saltrim.fmt-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.fmt :as fmt]))

(deftest masks
  (testing "decimals"
    (is (= "1234.50" (fmt/apply-mask "0.00" 1234.5)))
    (is (= "3.14" (fmt/apply-mask "0.00" 3.14159)))
    (is (= "6" (fmt/apply-mask "0" 5.7))))                    ; rounds
  (testing "thousands grouping"
    (is (= "1,234,567" (fmt/apply-mask "#,##0" 1234567)))
    (is (= "1,234.50" (fmt/apply-mask "#,##0.00" 1234.5))))
  (testing "percent scales by 100"
    (is (= "25.0%" (fmt/apply-mask "0.0%" 0.25)))
    (is (= "100%" (fmt/apply-mask "0%" 1))))
  (testing "literal prefix / suffix"
    (is (= "$1,234.50" (fmt/apply-mask "$#,##0.00" 1234.5)))
    (is (= "1234 USD" (fmt/apply-mask "0 USD" 1234))))
  (testing "negatives"
    (is (= "-3.50" (fmt/apply-mask "0.00" -3.5))))
  (testing "no-op cases"
    (is (= "hello" (fmt/apply-mask "0.00" "hello")) "text passes through")
    (is (= "" (fmt/apply-mask "0.00" nil)))
    (is (= "42" (fmt/apply-mask "" 42)) "blank mask -> plain str")
    (is (= "42" (fmt/apply-mask nil 42)))))
