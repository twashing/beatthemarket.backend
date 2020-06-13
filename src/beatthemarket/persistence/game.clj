(ns beatthemarket.persistence.game
  (:require [datomic.client.api :as d]
            [beatthemarket.util :as util]))


(defn user-by-email [conn email]

  (let [db (d/db conn)
        user-q '[:find ?e
                 :in $ ?email
                 :where
                 [?e :user/email ?email]]]

    (d/q user-q db email)))

(defn user-exists? [result-entities]
  (let [set-and-subsets-note-empty?
        (every-pred util/exists? (partial every? util/exists?))]

    (set-and-subsets-note-empty? result-entities)))
