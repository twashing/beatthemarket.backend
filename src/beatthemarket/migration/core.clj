(ns beatthemarket.migration.core
  (:require [integrant.repl.state :as state]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.migration.schema-init :as schema-init]
            [beatthemarket.util :as util :refer [ppi]]))


(defn apply-norms!

  ([]
   (let [norms (schema-init/load-norm)]
     (apply-norms! norms)))

  ([norms]
   (-> state/system :persistence/datomic :opts :conn
       (apply-norms! norms)))

  ([conn norms]
   (ppi [:norm-count (count norms)])
   (ppi [:norms norms])
   (persistence.datomic/transact! conn norms)))

(def run-migrations apply-norms!)


(defn -main
  [& args]

  (state.core/set-prep)
  (state.core/init-components)

  (apply-norms!))
