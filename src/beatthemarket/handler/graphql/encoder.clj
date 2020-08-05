(ns beatthemarket.handler.graphql.encoder)


(defn stock-tick->graphql [data]
  (clojure.set/rename-keys
    data {:game.stock.tick/id         :stockTickId
          :game.stock.tick/trade-time :stockTickTime
          :game.stock.tick/close      :stockTickClose
          :game.stock/id              :stockId
          :game.stock/name            :stockName}))

(defn profit-loss->graphql [data]

  (-> data
      (clojure.set/rename-keys {:game-id          :gameId
                                :stock-id         :stockId
                                :profit-loss      :profitLoss
                                :profit-loss-type :profitLossType})
      (update :profitLossType #(if (= :realized-profit-loss %)
                                 :realized
                                 :running))))
