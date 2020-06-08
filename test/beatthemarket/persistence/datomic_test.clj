(ns beatthemarket.persistence.datomic-test
  (:require [clojure.test :refer :all]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.test-util :refer [component-prep-fixture component-fixture]]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(use-fixtures :once component-prep-fixture)
(use-fixtures :each component-fixture)

(defmethod persistence.datomic/->datomic-client :testing [{config :config}]
  (memdb/client config))


(deftest foobar-test
  (testing "foobar"
    (is (= 1 1))))
