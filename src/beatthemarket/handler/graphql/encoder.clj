(ns beatthemarket.handler.graphql.encoder)


(defn stock-tick->graphql [data]
  (clojure.set/rename-keys
    data {:game.stock.tick/id :stockTickId
          :game.stock.tick/trade-time :stockTickTime
          :game.stock.tick/close :stockTickClose
          :game.stock/id :stockId
          :game.stock/name :stockName}))
