(ns beatthemarket.game.games.pipeline
  (:require [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.util :as util]))


(defn calculate-profitloss-and-checklevel-pipeline [op
                                                     user-db-id
                                                     {{game-id :game/id} :game

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
   (buy-stock-pipeline conn userId gameId stockId stockAmount tickId tickPrice true))

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice validate?]

   ;; (println [conn userId gameId stockId stockAmount tickId tickPrice validate?])
   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn userId))]

     (->> (games.trades/buy-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          list
          ;; (map #(bookkeeping/track-profit-loss+stream-portfolio-update! conn gameId game-db-id user-db-id %))
          (calculate-profitloss-and-checklevel-pipeline :buy user-db-id game-control)
          doall
          ;; util/pprint+identity
          ))))

(defn sell-stock-pipeline

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice]
   (sell-stock-pipeline conn userId gameId stockId stockAmount tickId tickPrice true))

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn userId))]

     (->> (games.trades/sell-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          list
          (calculate-profitloss-and-checklevel-pipeline :sell user-db-id game-control)
          doall))))

(defn replay-stock-pipeline [game-control user-db-id maybe-tentries]

  (map (fn [{op :op :as maybe-tentry}]

         ;; (util/pprint+identity "B /")
         ;; (util/pprint+identity op)
         ;; (util/pprint+identity maybe-tentry)

         (if op
           (->> (list maybe-tentry)
                (calculate-profitloss-and-checklevel-pipeline (:op maybe-tentry) user-db-id game-control)
                doall)
           maybe-tentry))
       maybe-tentries))
