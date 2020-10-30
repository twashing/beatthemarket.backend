(ns beatthemarket.game.games.state
  (:require [integrant.repl.state :as repl.state]
            [clj-time.core :as t]))


(defn register-game-control! [game game-control]
  (swap! (:game/games repl.state/system)
         assoc (:game/id game) game-control))

(defn level->source-and-destination [level]

  (->> repl.state/config :game/game :levels seq
       (sort-by (comp :order second))
       (partition 2 1)
       (filter (fn [[[level-name _] r]] (= level level-name)))
       first))

(defn inmemory-game-by-id [game-id]

  (-> repl.state/system :game/games
      deref
      (get game-id)))

(defn update-inmemory-game-level! [game-id level]

  (let [[[source-level-name _ :as source]
         [dest-level-name dest-level-config :as dest]] (level->source-and-destination level)]

    (println "Site B: Updating new level in memory / " dest-level-name)
    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :current-level] (-> dest-level-config
                                                        (assoc :level dest-level-name)
                                                        (dissoc :order)
                                                        atom
                                                        constantly))))))

(defn update-inmemory-game-timer! [game-id time-in-seconds]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :level-timer] (constantly (atom time-in-seconds))))))

(defn update-inmemory-tick-sleep-atom! [game-id tick-sleep]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :tick-sleep-atom] (constantly (atom tick-sleep))))))

(defn calculate-remaining-time [now end]

  (let [end (if (t/after? now end) now end)
        interval (t/interval now end)]
    {:interval interval
     :remaining-in-minutes (t/in-minutes interval)
     :remaining-in-seconds (rem (t/in-seconds interval) 60)}))
