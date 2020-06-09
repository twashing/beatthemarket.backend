(ns beatthemarket.persistence.datomic-test
  (:require [clojure.test :refer :all]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.test-util :refer [component-prep-fixture component-fixture]]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(use-fixtures :once (partial component-prep-fixture :test))
(use-fixtures :each component-fixture)

(defmethod persistence.datomic/->datomic-client :test [{:keys [db-name config]}]
  (hash-map
    :client (memdb/client config)
    :url    (format "datomic:mem://%s" db-name)))


(deftest foobar-test
  (testing "foobar"
    (is (= 1 1))))
