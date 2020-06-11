(ns beatthemarket.persistence.game
  (:require [datomic.client.api :as d]
            [beatthemarket.util :as util]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


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

(defn conditionally-add-new-user! [conn {:keys [email name uid] :as checked-authentication}]

  (when-not (user-exists? (user-by-email conn email))
    (->> [{:user/email email
           :user/name name
           :user/external-uid uid}]
         (persistence.datomic/transact! conn))))
