(ns beatthemarket.persistence.core
  (:require [datomic.client.api :as d]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(defn bind-temporary-id [entity]
  (assoc entity :db/id (str (UUID/randomUUID))))

(defn pull-entity [conn entity-id]
  {:pre (util/exists? entity-id)}
  (d/pull (d/db conn) '[*] entity-id))

