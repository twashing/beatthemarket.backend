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
            [beatthemarket.game.games :as game.games]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]))


(comment

  (halt)

  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))

  (util/pprint+identity integrant.repl.state/config)
  (util/pprint+identity integrant.repl.state/system))

