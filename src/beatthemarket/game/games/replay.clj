(ns beatthemarket.game.games.replay)


(defn replay-to-index! [game-control user-db-id start-position game-play-index]

  ;; A Replay trades
  ;; game-id user-db-id

  ;; pull stock-ticks :game.stock/price-history
  ;; pull tentries


  ;; B Replay ticks
  (let [[historical-data inputs-at-position] (->> (stock-tick-pipeline user-db-id game-control)
                                                  (seek-to-position game-play-index))]

    [historical-data (run-iteration inputs-at-position)]))
