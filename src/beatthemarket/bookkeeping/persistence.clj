(ns beatthemarket.bookkeeping.persistence
  (:require [datomic.client.api :as d]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :as util :refer [ppi]]))


(defn account-by-game-user [conn user-db-id game-id account-name]

  (d/q '[:find (pull ?gua [*])
         :in $ ?uid ?game-id ?account-name
         :where
         [?uid]
         [?g :game/id ?game-id]
         [?g :game/users ?gu]
         [?gu :game.user/user ?uid]
         [?gu :game.user/accounts ?gua]
         [?gua :bookkeeping.account/name ?account-name]]
       (d/db conn)
       user-db-id game-id account-name))

(defn cash-account-by-game-user [conn user-db-id game-id]
  (ffirst (account-by-game-user conn user-db-id game-id "Cash")))

(defn equity-account-by-game-user [conn user-db-id game-id]
  (ffirst (account-by-game-user conn user-db-id game-id "Equity")))

(defn pull-game-user [conn user-db-id game-id]
  (ffirst
    (d/q '[:find (pull ?gus [*])
           :in $ ?game-id ?game-user
           :where
           [?g :game/id ?game-id]
           [?g :game/users ?gus]
           [?gus :game.user/user ?game-user]]

         (d/db conn) game-id user-db-id)))

(defn stock-accounts-by-game-user [conn user-db-id game-id]
  (d/q '[:find (pull ?gua [*])
         :in $ ?game-id ?game-user
         :where
         [?g :game/id ?game-id]
         [?g :game/users ?gus]
         [?gus :game.user/user ?game-user]
         [?gus :game.user/accounts ?gua]
         [?gua :bookkeeping.account/counter-party]]
       (d/db conn)
       game-id user-db-id))

(defn stock-accounts-with-inventory-by-game-user [conn user-db-id game-id]
  (d/q '[:find (pull ?gua [*])
         :in $ ?game-id ?game-user
         :where
         [?g :game/id ?game-id]
         [?g :game/users ?gus]
         [?gus :game.user/user ?game-user]
         [?gus :game.user/accounts ?gua]
         [?gua :bookkeeping.account/counter-party]
         [?gua :bookkeeping.account/amount ?amount]
         [(> ?amount 0)]]
       (d/db conn)
       game-id user-db-id))
