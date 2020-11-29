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

(defn user-by-external-uid

  ([conn external-uid]
   (user-by-external-uid conn external-uid '[:db/id]))

  ([conn external-uid pull-expr]

   (d/q '[:find (pull ?e pexpr)
          :in $ ?external-uid pexpr
          :where
          [?e :user/external-uid ?external-uid]]
        (d/db conn)
        external-uid pull-expr)))

(defn user-by-id

  ([conn id]
   (persistence.core/pull-entity conn id '[*]))

  ([conn id pull-expr]
   (persistence.core/pull-entity conn id pull-expr)))

(defn game-user-by-user

  ([conn user-id game-id]
   (game-user-by-user conn user-id game-id '[*]))

  ([conn user-id game-id expr]

   (d/q '[:find (pull ?gus pexpr)
          :in $ ?gameId ?user-id pexpr
          :where
          [?g :game/id ?gameId]
          [?g :game/users ?gus]
          [?gus :game.user/user ?user-id]]
        (d/db conn)
        game-id user-id expr)))
