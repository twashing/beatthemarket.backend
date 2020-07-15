(ns workbench.games
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]))


(comment

  (halt)

  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    #_(migration.core/run-migrations))

  (util/pprint+identity integrant.repl.state/config)
  (util/pprint+identity integrant.repl.state/system))

(comment ;; i. Create Game, ii. buy and sell stocks, iii. calculate profit/loss


  ;; A
  (def conn (-> repl.state/system :persistence/datomic :opts :conn))
  (def user (test-util/generate-user! conn))
  (def result-user-id (:db/id user))
  (def userId         (:user/external-uid user))

  ;; B
  (def data-sequence-B [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
  (def tick-length     (count data-sequence-B))

  ;; C create-game!
  (def sink-fn identity)

  (def game-control (game.games/create-game! conn result-user-id sink-fn data-sequence-B))

  (def game (:game game-control))
  (def gameId (:game/id game))
  (def game-db-id (:db/id game))


  (def control-channel (:control-channel game-control))
  (def stock-stream-channel (:stock-stream-channel game-control))
  (def stocks-with-tick-data (:stocks-with-tick-data game-control))

  (def test-chan (core.async/chan))
  (def game-loop-fn (fn [a]
                      (when a (core.async/>!! test-chan a))))

  ;; D start-game!
  (def start-game-result (game.games/start-game! conn result-user-id game-control game-loop-fn))
  {{:keys [mixer
           pause-chan
           input-chan
           output-chan]}
   :channel-controls}

  (def game-user-subscription (-> game
                                  :game/users first
                                  :game.user/subscriptions first))
  (def stockId (:game.stock/id game-user-subscription))


  ;; ;; E subscription price history
  ;; subscription-ticks (->> (repeatedly #(<!! test-chan))
  ;;                         (take tick-length)
  ;;                         doall
  ;;                         (map #(second (first (game.games/narrow-stock-tick-pairs-by-subscription % game-user-subscription)))))
  ;;
  ;; local-transact-stock! (fn [{tickId      :game.stock.tick/id
  ;;                            tickPrice   :game.stock.tick/close
  ;;                            op          :op
  ;;                            stockAmount :stockAmount}]
  ;;
  ;;                         (case op
  ;;                           :buy  (game.games/buy-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
  ;;                           :sell (game.games/sell-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
  ;;                           :noop))

  ;; TODO lookup game-id
  :game/id
  :game/users
  :game.user/user
  :user/accounts
  :bookkeeping.account/id

  (def conn (-> integrantrepl.state/system :persistence/datomic :opts :conn))

  (-> (persistence.core/pull-entity conn game-db-id)
      util/pprint+identity)

  (-> (d/q '[:find (pull ?e [{:user/_accounts
                              [{:game.user/_user
                                [{:game/_users [:game/id]}]}]}])
          :in $ ?account-id
          :where
          [?e :bookkeeping.account/id ?account-id]]
        (d/db conn)
        #uuid "6caf0e08-34fe-458f-8c71-20bb3074033c")

      util/pprint+identity flatten first
      :user/_accounts :game.user/_user :game/_users :game/id)

  )
