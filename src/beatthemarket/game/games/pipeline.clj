(ns beatthemarket.game.games.pipeline
  (:require [clojure.core.async :as core.async]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :refer [ppi] :as util]))


(defn stream-account-balance-updates [conn
                                      portfolio-update-stream
                                      game-db-id
                                      user-id
                                      tentry]

  (core.async/go
    (core.async/>! portfolio-update-stream
                   (game.calculation/collect-account-balances conn game-db-id user-id)))
  tentry)

(defn stock-tick-and-stream-pipeline [{{game-id :game/id} :game
                                       process-transact! :process-transact!
                                       group-stock-tick-pairs :group-stock-tick-pairs
                                       stream-stock-tick :stream-stock-tick
                                       calculate-profit-loss :calculate-profit-loss}
                                      input]

  (->> (map process-transact! input)
       (map group-stock-tick-pairs)
       (map stream-stock-tick)
       (map calculate-profit-loss)))

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

;; A
(defn stock-tick-pipeline [{input-sequence :input-sequence :as game-control}]

  (->> (stock-tick-and-stream-pipeline game-control input-sequence)
       (execution-pipeline game-control)))

;; B
(defn buy-stock-pipeline

  ([game-control conn user-external-id game-id stockId stockAmount tickId tickPrice]
   (buy-stock-pipeline game-control conn user-external-id game-id stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream
     :portfolio-update-stream
     :as game-control} conn user-external-id game-id stockId stockAmount tickId tickPrice validate?]

   (let [game-control-without-check-level (assoc game-control :check-level-complete identity)
         user-db-id (util/extract-id (iam.persistence/user-by-external-uid conn user-external-id '[:db/id]))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id game-id '[:db/id]))
         op :buy]

     (->> (games.trades/buy-stock! conn user-db-id user-external-id game-id stockId stockAmount tickId tickPrice validate?)
          (stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (map (partial games.processing/calculate-profit-loss op user-db-id game-id))
          (execution-pipeline game-control-without-check-level)
          doall))))

(defn sell-stock-pipeline

  ([game-control conn user-external-id game-id stockId stockAmount tickId tickPrice]
   (sell-stock-pipeline game-control conn user-external-id game-id stockId stockAmount tickId tickPrice true))

  ([{portfolio-update-stream :portfolio-update-stream :as game-control}
    conn user-external-id game-id stockId stockAmount tickId tickPrice validate?]

   (let [game-control-without-check-level (assoc game-control :check-level-complete identity)
         user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn user-external-id '[:db/id]))
         game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id game-id '[:db/id]))
         op :sell]

     (->> (games.trades/sell-stock! conn user-db-id user-external-id game-id stockId stockAmount tickId tickPrice validate?)
          (stream-account-balance-updates conn portfolio-update-stream game-db-id user-db-id)
          list
          (map (partial games.processing/calculate-profit-loss op user-db-id game-id))
          (execution-pipeline game-control-without-check-level)
          doall))))

;; D
(defn replay-stock-trades-pipeline [{{game-id :game/id} :game
                                     calculate-profit-loss :calculate-profit-loss
                                     :as game-control}
                                    maybe-tentries]

  (map (fn [{buy-or-sell :op :as maybe-tentry}]

         (if buy-or-sell
           (->> (list maybe-tentry)
                (map calculate-profit-loss)
                (execution-pipeline game-control))
           maybe-tentry))
       maybe-tentries))


;; E
(defn input->stock-tick-struct [data] {:stock-ticks data})

(defn market-stock-tick-pipeline [{input-sequence :input-sequence
                                   process-transact! :process-transact!
                                   group-stock-tick-pairs :group-stock-tick-pairs}]

  (->> (map process-transact! input-sequence)
       (map group-stock-tick-pairs)
       (map input->stock-tick-struct)))

(defn join-market-pipeline [conn user-db-id game-id {input-sequence :input-sequence
                                                     process-transact! :process-transact!
                                                     stream-stock-tick :stream-stock-tick
                                                     group-stock-tick-pairs :group-stock-tick-pairs
                                                     :as game-control}]

  (->> (map process-transact! input-sequence)
       (map group-stock-tick-pairs)
       (map stream-stock-tick)
       (map (partial games.processing/calculate-profit-loss :tick user-db-id game-id))
       (execution-pipeline game-control)
       doall))
