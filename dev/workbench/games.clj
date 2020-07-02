(ns workbench.games
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.util :as util]))


(comment

  (halt)

  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))

  (util/pprint+identity integrant.repl.state/config)
  (util/pprint+identity integrant.repl.state/system)

  )

(comment ;; i. Create Game, ii. buy and sell stocks, iii. calculate profit/loss


  )
