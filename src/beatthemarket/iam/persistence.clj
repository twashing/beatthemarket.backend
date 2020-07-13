(ns beatthemarket.iam.persistence
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.persistence.core :as persistence.core]))


(defn user-by-email [conn email]
  (d/q '[:find (pull ?e [*])
         :in $ ?email
         :where
         [?e :user/email ?email]]
       (d/db conn)
       email))

(defn user-by-external-uid [conn external-uid]
  (d/q '[:find (pull ?e [*])
         :in $ ?external-uid
         :where
         [?e :user/external-uid ?external-uid]]
       (d/db conn)
       external-uid))

(defn user-by-id [conn id] (persistence.core/pull-entity conn id))
