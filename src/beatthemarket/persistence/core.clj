(ns beatthemarket.persistence.core
  (:require [datomic.client.api :as d]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(defn bind-temporary-id [entity]
  (assoc entity :db/id (str (UUID/randomUUID))))

(defn pull-entity [conn entity-id]
  {:pre (util/exists? entity-id)}
  (d/pull (d/db conn) '[*] entity-id))

(defn entity-by-domain-id [conn entity-attribute id]
  (d/q '[:find (pull ?e [*])
         :in $ ?entity-attribute ?id
         :where
         [?e ?entity-attribute ?id]]
       (d/db conn)
       entity-attribute
       id))

(defn valid-id? [conn id]
  ((comp not empty?) (d/q '[:find ?a
                         :in $ ?entid
                         :where [?entid ?a]]
                       (d/db conn)
                       id)))
