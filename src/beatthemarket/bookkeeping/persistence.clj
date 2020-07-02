(ns beatthemarket.bookkeeping.persistence
  (:require [beatthemarket.persistence.core :as persistence.core]
            [datomic.client.api :as d]))


(defn cash-account-by-user

  ([conn user-id]
   (cash-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Cash" (:bookkeeping.account/name %)))
        first)))

(defn equity-account-by-user

  ([conn user-id]
   (equity-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Equity" (:bookkeeping.account/name %)))
        first)))

(defn stock-accounts-by-user-external-id [conn external-uid]
  (d/q '[:find (pull ?e [{:user/accounts [*]}])
         :in $ ?external-uid
         :where
         [?e :user/external-uid ?external-uid]]
       (d/db conn)
       external-uid))

(defn stock-accounts-by-user-entity [conn user-db-id]
  (d/q '[:find (pull ?e [{:user/accounts [*]}])
         :in $ ?e
         :where
         [?e]]
       (d/db conn)
       user-db-id))

(defn stock-accounts-by-user-for-game [conn user-db-id game-id]
  (d/q '[:find (pull ?gs [{:bookkeeping.account/_counter-party [*]}])
         :in $ ?e ?game-id
         :where
         [?e]

         ;; Match Game to User
         [?g :game/id ?game-id]
         [?g :game/users ?gus]
         [?gus :game.user/user ?e]

         ;; Narrow accounts for the game
         [?g :game/stocks ?gs]
         [?e :user/accounts ?uas]
         [?uas :bookkeeping.account/counter-party ?gs]]
       (d/db conn)
       user-db-id
       game-id))
