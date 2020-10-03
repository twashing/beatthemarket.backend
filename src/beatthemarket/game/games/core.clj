(ns beatthemarket.game.games.core
  (:require [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]

            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.util :refer [ppi] :as util]))


(defn default-game-control [conn game-id
                            {{user-db-id :db/id} :user
                             current-level :current-level
                             control-channel :control-channel

                             stock-tick-stream :stock-tick-stream
                             portfolio-update-stream :portfolio-update-stream
                             game-event-stream :game-event-stream}]

  (let [stream-buffer 10

        control-channel         (or control-channel (core.async/chan (core.async/sliding-buffer stream-buffer)))
        stock-tick-stream       (or stock-tick-stream (core.async/chan (core.async/sliding-buffer stream-buffer)))
        portfolio-update-stream (or portfolio-update-stream (core.async/chan (core.async/sliding-buffer stream-buffer)))
        game-event-stream       (or game-event-stream (core.async/chan (core.async/sliding-buffer stream-buffer)))]

    {:profit-loss {}

     :control-channel         control-channel
     :stock-tick-stream       stock-tick-stream
     :portfolio-update-stream portfolio-update-stream
     :game-event-stream       game-event-stream

     :process-transact!             (partial games.processing/process-transact! conn)
     :group-stock-tick-pairs        games.processing/group-stock-tick-pairs
     :stream-stock-tick             (partial games.processing/stream-stock-tick stock-tick-stream)
     :process-transact-profit-loss! (partial games.processing/process-transact-profit-loss! conn)
     :stream-portfolio-update!      (partial games.processing/stream-portfolio-update! portfolio-update-stream)

     :check-level-complete           (partial games.processing/check-level-complete conn user-db-id game-id control-channel)
     :process-transact-level-update! (partial games.processing/process-transact-level-update! conn)
     :stream-level-update!           (partial games.processing/stream-level-update! game-event-stream)}))
