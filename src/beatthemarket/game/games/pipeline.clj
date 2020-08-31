(ns beatthemarket.game.games.pipeline
  (:require [clojure.core.async :as core.async]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :as util]))


(defn stream-account-balance-updates [conn
                                      portfolio-update-stream
                                      game-db-id
                                      user-id
                                      tentry]

  (core.async/go
    (core.async/>! portfolio-update-stream
                   (game.calculation/collect-account-balances conn game-db-id user-id)))
  tentry)


(defn execution-pipeline [{control-channel :control-channel
                           current-level :current-level
                           portfolio-update-stream :portfolio-update-stream
                           game-event-stream :game-event-stream

                           process-transact-profit-loss! :process-transact-profit-loss!
                           stream-portfolio-update! :stream-portfolio-update!

                           check-level-complete :check-level-complete
                           process-transact-level-update! :process-transact-level-update!
                           stream-level-update! :stream-level-update!}
                          input]

  (->> (map process-transact-profit-loss! input)
       (map stream-portfolio-update!)

       (map check-level-complete)
       (map process-transact-level-update!)
       (map stream-level-update!)))

(defn stock-tick-and-stream-pipeline [{{game-id :game/id} :game
                                       ;; stock-tick-stream :stock-tick-stream
                                       process-transact! :process-transact!
                                       stream-stock-tick :stream-stock-tick}
                                      input]

  (->> (map process-transact! input)
       (map stream-stock-tick)
       (map (partial games.processing/calculate-profit-loss :tick nil game-id))))

(defn stock-tick-pipeline [{input-sequence :input-sequence :as game-control}]
  (->> (stock-tick-and-stream-pipeline game-control input-sequence)
       (execution-pipeline game-control)))

(defn buy-stock-pipeline

  ([game-control conn user-external-id game-id stockId stockAmount tickId tickPrice]
   (buy-stock-pipeline game-control conn user-external-id game-id stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream
     :portfolio-update-stream :as game-control} conn user-external-id game-id stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id (util/extract-id (iam.persistence/user-by-external-uid conn user-external-id))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id game-id))
         op :buy]

     (->> (games.trades/buy-stock! conn user-db-id user-external-id game-id stockId stockAmount tickId tickPrice validate?)
          (stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (map (partial games.processing/calculate-profit-loss op user-db-id game-id))
          (execution-pipeline game-control)
          doall))))

(defn sell-stock-pipeline

  ([game-control conn user-external-id game-id stockId stockAmount tickId tickPrice]
   (sell-stock-pipeline game-control conn user-external-id game-id stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream :portfolio-update-stream :as game-control}
    conn user-external-id game-id stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn user-external-id))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id game-id))
         op :sell]

     (->> (games.trades/sell-stock! conn user-db-id user-external-id game-id stockId stockAmount tickId tickPrice validate?)
          (stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (map (partial games.processing/calculate-profit-loss op user-db-id game-id))
          (execution-pipeline game-control)
          doall))))

(defn replay-stock-pipeline [game-control maybe-tentries]

  (map (fn [{op :op :as maybe-tentry}]

         (if op
           (->> (list maybe-tentry)
                (execution-pipeline game-control (:op maybe-tentry))
                doall)
           maybe-tentry))
       maybe-tentries))
