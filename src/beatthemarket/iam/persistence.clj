(ns beatthemarket.iam.persistence
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :refer [ppi]]))


(defn user-by-email

  ([conn email] (user-by-email conn email '[*]))

  ([conn email pull-expr]

   (d/q '[:find (pull ?e pexpr)
          :in $ ?email pexpr
          :where
          [?e :user/email ?email]]
        (d/db conn)
        email pull-expr)))

(defn user-by-external-uid [conn external-uid]
  (d/q '[:find (pull ?e [*])
         :in $ ?external-uid
         :where
         [?e :user/external-uid ?external-uid]]
       (d/db conn)
       external-uid))

(defn user-by-id [conn id] (persistence.core/pull-entity conn id))

(defn game-user-by-user

  ([conn user-id game-id]
   (game-user-by-user conn user-id game-id '[*]))

  ([conn user-id game-id expr]

   #_(d/q '[:find (pull ?u pexpr)
            :in $ ?u pexpr
            :where
            [?u]]
          (d/db conn)
          user-id pexpr)

   (d/q '[:find (pull ?gus pexpr)
          :in $ ?gameId ?user-id pexpr
          :where
          [?g :game/id ?gameId]
          [?g :game/users ?gus]
          [?gus :game.user/user ?user-id]]
        (d/db conn)
        game-id user-id expr)))
