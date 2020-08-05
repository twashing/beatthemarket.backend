(ns beatthemarket.game.calculation
  (:require [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.util :as util]))


(defn collect-profit-loss

  ([profit-loss-type game-id]
   (collect-profit-loss profit-loss-type game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([profit-loss-type game-id profit-loss]
   (->> (for [[k vs] profit-loss]
          {:game-id          game-id
           :stock-id         k
           :profit-loss-type profit-loss-type
           :profit-loss      (->> (filter profit-loss-type vs)
                                  (reduce (fn [ac {pl profit-loss-type}]
                                            (+ ac pl))
                                          0.0)
                                  (format "%.2f") (Float.))})
        flatten)))

(defn collect-realized-profit-loss

  ([game-id]
   (collect-realized-profit-loss game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([game-id profit-loss]
   (collect-profit-loss :realized-profit-loss game-id profit-loss)))

(defn collect-running-profit-loss

  ([game-id]
   (collect-running-profit-loss game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([game-id profit-loss]
   (collect-profit-loss :running-profit-loss game-id profit-loss)))

(defn collect-account-balances [conn game-id user-id]

  (->> (d/q '[:find (pull ?ua [:bookkeeping.account/id
                               :bookkeeping.account/name
                               :bookkeeping.account/balance
                               :bookkeeping.account/amount
                               {:bookkeeping.account/counter-party
                                [:game.stock/name]}])
              :in $ ?game-id ?user-id
              :where
              [?game-id]
              [?game-id :game/users ?gus]
              [?gus :game.user/user ?user-id]
              [?gus :game.user/accounts ?ua]]
            (d/db conn)
            game-id user-id)
       (map first)))
