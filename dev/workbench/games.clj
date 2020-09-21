(ns workbench.games
  (:require [clojure.core.async :as core.async
             :refer [go-loop chan close! timeout alts! >! <! >!!]]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.iam.persistence :as iam.persistence]))


(comment

  ;; 1
  (do

    (halt)

    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))

  ;; (util/ppi integrant.repl.state/config)
  ;; (util/ppi integrant.repl.state/system)

  ;; 2
  (do

    ;; A
    (def conn (-> repl.state/system :persistence/datomic :opts :conn))
    (def user (test-util/generate-user! conn))
    (def user-db-id (:db/id user))
    (def userId         (:user/external-uid user))

    ;; B
    ;; (def data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0]))
    (def data-sequence-fn games.control/->data-sequence)
    (def tick-length      7)


    ;; C create-game!
    (def sink-fn                identity)
    (def test-stock-ticks       (atom []))
    (def test-portfolio-updates (atom []))))

#_(comment

    (do
      (def game (:game game-control))
      (def gameId     (:game/id game))
      (def game-db-id (:db/id game))

      (def stocks (:game/stocks game))
      (def control-channel              (:control-channel game-control))
      (def game-event-stream            (:game-event-stream game-control))


      ;; i.
      (def start-results (game.games/start-game!-workbench conn user-db-id game-control))
      (def iterations (second start-results))


      ;; ii.
      (def data-sequence-fn games.control/->data-sequence)
      (def input-sequence
        (-> (map #(game.games/bind-data-sequence (data-sequence-fn) %) stocks)
            game.games/stocks->stock-sequences))

      ;; game.games/run-iteration


      ;; start with history + iterations
      ;; replay processing to position x
      ;; :noop stream + transact
      ;; Just rebuild :running-profit-loss
      #_(let [calculate-and-check-replay-xf (game.games/calculate-profitloss-and-checklevel-xf
                                              game-control-without-transact)]

          (comp (map game.games/process-transact!) calculate-and-check-xf)
          (comp (map game.games/buy-stock!) calculate-and-check-xf)
          (comp (map game.games/sell-stock!) calculate-and-check-xf))

      ;; Process live from position x
      (let [calculate-and-check-live-xf (game.games/calculate-profitloss-and-checklevel-xf
                                          game-control)]

        (comp (map game.games/process-transact!) calculate-and-check-xf)
        (comp (map game.games/buy-stock!) calculate-and-check-xf)
        (comp (map game.games/sell-stock!) calculate-and-check-xf))

      ))


;; LIVE
(comment

  (do

    (def opts       {:level-timer-sec 5
                     :accounts        (game.core/->game-user-accounts)})
    (def game-level :game-level/one)

    (def game-control (game.games/create-game! conn user-db-id sink-fn game-level data-sequence-fn opts)))


  (do
    (def game              (:game game-control))
    (def gameId            (:game/id game))
    (def game-db-id        (:db/id game))
    (def stocks            (:game/stocks game))
    (def control-channel   (:control-channel game-control))
    (def game-event-stream (:game-event-stream game-control))


    ;; i.
    ;; (def start-results (game.games/start-game!-workbench conn user-db-id game-control))
    ;; (def iterations (second start-results))
    )


  ;; [ok] implement BUY
  ;; [ok] calculator for :running-profit-loss

  ;; [ok] implement SELL
  ;; [ok] save :realized-profit-loss

  ;; [ok] On BUY / SELL
  ;; [ok] bind tentry to the tick

  ;; - track counterBalance amount; will fluctuate as long orginal purchase amount is sold off
  ;; - track if trading on margin

  ;; save :running-profit-loss on #{:win :lose :exit}
  ;; buying without sufficient money
  ;; selling without sufficient stock


  ;; >
  (def stock-tick-pipeline (game.games/stock-tick-pipeline game-control))

  ;; >
  (game.games/buy-stock-pipeline game-control conn user-db-id gameId [stockId stockAmount tickId tickPrice])

  ;; >
  (game.games/sell-stock-pipeline game-control conn user-db-id gameId [stockId stockAmount tickId tickPrice validate?]))


;; REPLAY
(comment

  (do

    ;; :noop stream + transact
    (def opts       {:level-timer-sec          5
                     :accounts                 (game.core/->game-user-accounts)
                     :process-transact!        identity
                     :stream-stock-tick        identity
                     :stream-portfolio-update! identity
                     :stream-level-update!     identity})
    (def game-level :game-level/one)

    (def game-control (game.games/create-game! conn user-db-id sink-fn game-level data-sequence-fn opts)))


  (do
    (def game              (:game game-control))
    (def gameId            (:game/id game))
    (def game-db-id        (:db/id game))
    (def stocks            (:game/stocks game))
    (def control-channel   (:control-channel game-control))
    (def game-event-stream (:game-event-stream game-control))


    ;; i.
    ;; (def start-results (game.games/start-game!-workbench conn user-db-id game-control))
    ;; (def iterations (second start-results))
    )


  ;; >
  (def stock-tick-pipeline (game.games/stock-tick-pipeline game-control))


  ;; >
  (game.games/buy-stock-pipeline game-control conn user-db-id gameId [stockId stockAmount tickId tickPrice])


  ;; >
  (game.games/sell-stock-pipeline game-control conn user-db-id gameId [stockId stockAmount tickId tickPrice validate?])

  )


;; MARKET
(comment


  ;; WITH User
  (do

    (def opts       {:level-timer-sec 5
                     :user            {:db/id user-db-id}
                     :accounts        (game.core/->game-user-accounts)
                     :game-level      :game-level/one})

    (def game-control (game.games/create-game! conn sink-fn data-sequence-fn opts)))


  ;; WITHOUT User
  (do

    (def opts       {:level-timer-sec 5
                     :stocks-in-game  10
                     :game-level      :game-level/market})

    (def game-control (game.games/create-game! conn sink-fn data-sequence-fn opts)))


  (pprint (dissoc game-control :input-sequence :stocks-with-tick-data))


  (let [{{game-id :game/id} :game} game-control]

    (games.control/join-game conn game-id user-db-id game-control))

  (pprint (persistence.core/entity-by-domain-id conn :game/id (-> game-control :game :game/id)))

  #_(pprint

    (->> (iam.persistence/game-user-by-user conn user-db-id '[{:game.user/_user
                                                                   [:game/status]}])
         flatten
         (map (comp :db/ident :game/status :game.user/_user))
         (into #{})
         (some #{:game-status/running})))

  )

