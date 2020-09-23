(ns beatthemarket.migration.core
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.migration.schema-init :as schema-init]
            [beatthemarket.util :as util :refer [ppi]]))


(defn create-database! [client db-name]
  (d/create-database client {:db-name db-name}))

(defn connect-to-database [client db-name]
  (d/connect client {:db-name db-name}))

(defn apply-norms!

  ([]
   (let [norms (schema-init/load-norm)]
     (apply-norms! norms)))

  ([norms]

   (let [conn (-> state/system :persistence/datomic :opts :conn)]
     (apply-norms! conn norms)))

  ([conn norms]
   (persistence.datomic/transact! conn norms)))

(def run-migrations apply-norms!)


(defn initialize-production []
  (run-migrations))

(comment

  (def client (-> integrant.repl.state/system :persistence/datomic :opts :client))
  (def db-name (-> integrant.repl.state/config :persistence/datomic :datomic :db-name))

  (def create-result (create-database! client db-name))
  (def conn (connect-to-database client db-name))

  (apply-norms!))
