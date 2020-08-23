(ns beatthemarket.game.games.core
  (:require [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]

            [beatthemarket.game.games.processing :as games.processing]
            ;; [clj-time.core :as t]
            ;; [io.pedestal.log :as log]
            ;; [beatthemarket.persistence.datomic :as persistence.datomic]
            ;; [beatthemarket.persistence.core :as persistence.core]
            ))

(defn register-game-control! [game game-control]
  (swap! (:game/games repl.state/system)
         assoc (:game/id game) game-control))

(defn level->source-and-destination [level]

  (->> repl.state/config :game/game :levels seq
       (sort-by (comp :order second))
       (partition 2 1)
       (filter (fn [[[level-name _] r]] (= level level-name)))
       first))

(defn update-inmemory-game-level! [game-id level]

  (let [[[source-level-name _ :as source]
         [dest-level-name dest-level-config :as dest]] (level->source-and-destination level)]

    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :current-level] (-> dest-level-config
                                                        (assoc :level dest-level-name)
                                                        (dissoc :order)
                                                        constantly))))))

(defn default-game-control [conn user-id game-id
                            {:keys [current-level
                                    control-channel

                                    stock-tick-stream
                                    portfolio-update-stream
                                    game-event-stream]}]

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
     :stream-stock-tick             (partial games.processing/stream-stock-tick game-id)
     :process-transact-profit-loss! (partial games.processing/process-transact-profit-loss! conn)
     :stream-portfolio-update!      (partial games.processing/stream-portfolio-update! portfolio-update-stream)

     :check-level-complete           (partial games.processing/check-level-complete game-id control-channel current-level)
     :process-transact-level-update! (partial games.processing/process-transact-level-update! conn)
     :stream-level-update!           (partial games.processing/stream-level-update! game-event-stream)}))
