(ns beatthemarket.game.games.pipeline
  (:require [clojure.core.async :as core.async]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :as util]))


(defn conditionally-stream-account-balance-updates [conn
                                                    portfolio-update-stream
                                                    game-db-id
                                                    user-id
                                                    tentry]

  (core.async/go
    (core.async/>! portfolio-update-stream
                   (util/pprint+identity (game.calculation/collect-account-balances conn game-db-id user-id))))
  tentry)


(defn calculate-profitloss-and-checklevel-pipeline [op
                                                    user-db-id
                                                    {{game-id :game/id} :game

                                                     control-channel :control-channel
                                                     current-level :current-level
                                                     portfolio-update-stream :portfolio-update-stream
                                                     game-event-stream :game-event-stream

                                                     process-transact-profit-loss! :process-transact-profit-loss!
                                                     stream-portfolio-update! :stream-portfolio-update!

                                                     check-level-complete :check-level-complete
                                                     process-transact-level-update! :process-transact-level-update!
                                                     stream-level-update! :stream-level-update!}
                                                    input]

  (->> (map (partial games.processing/calculate-profit-loss op user-db-id game-id) input)
       (map process-transact-profit-loss!)
       (map stream-portfolio-update!)

       (map check-level-complete)
       (map process-transact-level-update!)
       (map stream-level-update!)))

(defn stock-tick-and-stream-pipeline [{:keys [stock-tick-stream
                                              process-transact!
                                              stream-stock-tick]}
                                      input]

  (->> (map process-transact! input)
       (map stream-stock-tick)))

(defn stock-tick-pipeline [user-db-id {input-sequence :input-sequence :as game-control}]
  (->> (stock-tick-and-stream-pipeline game-control input-sequence)
       (calculate-profitloss-and-checklevel-pipeline :tick user-db-id game-control)))

(defn buy-stock-pipeline

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice]
   (buy-stock-pipeline game-control conn userId gameId stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream :portfolio-update-stream :as game-control} conn userId gameId stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id (util/extract-id (iam.persistence/user-by-external-uid conn userId))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))]

     (util/pprint+identity [:buy-stock-pipeline userId gameId game-db-id])

     (->> (games.trades/buy-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          (conditionally-stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (calculate-profitloss-and-checklevel-pipeline :buy user-db-id game-control)
          doall))))

(defn sell-stock-pipeline

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice]
   (sell-stock-pipeline game-control conn userId gameId stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream :portfolio-update-stream :as game-control} conn userId gameId stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn userId))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))]

     (util/pprint+identity [:buy-stock-pipeline userId gameId game-db-id])

     (->> (games.trades/sell-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          (conditionally-stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (calculate-profitloss-and-checklevel-pipeline :sell user-db-id game-control)
          doall))))

(defn replay-stock-pipeline [game-control user-db-id maybe-tentries]

  (map (fn [{op :op :as maybe-tentry}]

         (if op
           (->> (list maybe-tentry)
                (calculate-profitloss-and-checklevel-pipeline (:op maybe-tentry) user-db-id game-control)
                doall)
           maybe-tentry))
       maybe-tentries))
