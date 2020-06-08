(ns beatthemarket.persistence.foobar-test
  (:require [clojure.test :refer :all]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.test-util :refer [component-prep-fixture component-fixture]]
            [beatthemarket.persistence.datomic :as sut]))


;; (use-fixtures :once component-prep-fixture)
;; (use-fixtures :each component-fixture)

;; (defmethod sut/->datomic-client :testing [{config :config}]
;;   (memdb/client config))


(deftest foobar-test
  (testing "foobar"
    (is (= 1 1))))

(deftest a-test
  (testing "foobar"
    (is (= 2 2))))

(deftest b-test
  (testing "foobar"
    (is (= 3 3))))
