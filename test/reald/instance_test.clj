(ns reald.instance-test
  (:require [clojure.test :refer [is testing deftest]]))


(deftest simple
  (testing
    "simple demo"
    (is (= 3
           (+ 2 4)))))

(deftest simple2
  (testing
    "simple demo"
    (is (= 3
           (+ 2 4)))))
