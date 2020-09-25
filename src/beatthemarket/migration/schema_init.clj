(ns beatthemarket.migration.schema-init
  (:require [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :refer [ppi] :as util]))


(defn load-norm

  ([] (load-norm "migration/schema.datomic.edn"))

  ([schema]
   (-> schema
       resource slurp
       read-string)))

(defn apply-norm!

  ([]
   (-> state/system :persistence/datomic :conn
       apply-norm!))

  ([conn]
   (persistence.datomic/transact! conn (load-norm))))
