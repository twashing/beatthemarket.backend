(ns beatthemarket.migration.core
  (:require [integrant.repl.state :as state]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.migration.schema-init :as schema-init]))


(defn apply-norms!

  ([]
   (let [norms (schema-init/load-norm)]
     (apply-norms! norms)))

  ([norms]
   (-> state/system :persistence/datomic :conn
       (apply-norms! norms)))

  ([conn norms]
   (persistence.datomic/transact! conn norms)))

(def run-migrations apply-norms!)


(defn -main
  [& args]

  (state.core/set-prep)
  (state.core/init-components)

  (apply-norms!))
