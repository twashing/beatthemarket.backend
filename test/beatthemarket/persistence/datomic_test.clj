(ns beatthemarket.persistence.datomic-test
  (:require [clojure.test :refer :all]
            [beatthemarket.test-util :refer [component-prep-fixture component-fixture]]))


(use-fixtures :once (partial component-prep-fixture :test))
(use-fixtures :each component-fixture)


(deftest foobar-test
  (testing "foobar"
    (is (= 1 1))))
