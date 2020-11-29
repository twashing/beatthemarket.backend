(ns beatthemarket.bookkeeping.persistence
  (:require [datomic.client.api :as d]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :as util :refer [ppi]]))


(defn account-by-game-user

  ([conn user-db-id game-id account-name]
   (account-by-game-user conn user-db-id game-id account-name '[*]))

  ([conn user-db-id game-id account-name pull-expr]

   (d/q '[:find (pull ?gua pexpr)
          :in $ ?uid ?game-id ?account-name pexpr
          :where
          [?uid]
          [?g :game/id ?game-id]
          [?g :game/users ?gu]
          [?gu :game.user/user ?uid]
          [?gu :game.user/accounts ?gua]
          [?gua :bookkeeping.account/name ?account-name]]
        (d/db conn)
        user-db-id game-id account-name pull-expr)))

(defn cash-account-by-game-user

  ([conn user-db-id game-id]
   (cash-account-by-game-user conn user-db-id game-id '[*]))

  ([conn user-db-id game-id pull-expr]
   (ffirst (account-by-game-user conn user-db-id game-id "Cash" pull-expr))))

(defn equity-account-by-game-user [conn user-db-id game-id]
  (ffirst (account-by-game-user conn user-db-id game-id "Equity")))

(defn pull-game-user

  ([conn user-db-id game-id]
   (pull-game-user conn user-db-id game-id '[*]))

  ([conn user-db-id game-id pull-expr]

   (ffirst
     (d/q '[:find (pull ?gus pexpr)
            :in $ ?game-id ?game-user pexpr
            :where
            [?g :game/id ?game-id]
            [?g :game/users ?gus]
            [?gus :game.user/user ?game-user]]

          (d/db conn) game-id user-db-id pull-expr))))

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
