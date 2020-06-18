(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async
             :refer [go go-loop chan timeout alts! <! >!!]]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [integrant.core :as ig]
            [integrant.repl.state]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.game :as game]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))

(defmethod ig/halt-key! :game/games [_ games]
  (run! #(>!! (:control-channel %) :exit)
        (-> games deref vals)))

;; C.
;; Calculate Profit / Loss

;; D.
;; Complete a Level

;; E.
;; Win a Game
;; Lose a Game

;; F.
;; Pause | Resume a Game

(defn stream-subscription [tick-sleep-ms
                           data-subscription-channel
                           control-channel
                           close-sink-fn
                           sink-fn]

  (go-loop []
    (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                    control-channel])]

      (if (= :exit v)
        (do
          (close-sink-fn)
          (core.async/close! data-subscription-channel))
        (do
          (let [vv (<! data-subscription-channel)]

            ;; TODO calculate game position
            ;; (trace (format "Sink value / %s" vv))
            (sink-fn vv))
          (recur))))))

(defn narrow-stocks-by-game-user-subscription [stocks subscription-id-set]
  (filter #(some subscription-id-set [(:game.stock/id %)])
          stocks))

(defn register-game-control! [game game-control]
  (swap! (:game/games integrant.repl.state/system)
         assoc (:game/id game) game-control))

(defn initialize-game [conn user-entity source-stream]

  (let [bind-data-sequence    (fn [a]
                                (->> (datasource/->combined-data-sequence
                                       datasource.core/beta-configurations :datasource.sine/generate-sine-sequence)
                                     (datasource/combined-data-sequence-with-datetime (t/now))
                                     (map #(conj % (UUID/randomUUID)))
                                     (assoc a :data-sequence)))
        stocks                (->> (name-generator/generate-names 4)
                                   (map (juxt :stock-name :stock-symbol))
                                   (map #(apply game/->stock %))
                                   (map game/bind-temporary-id))
        stocks-with-tick-data (map bind-data-sequence stocks)
        game                  (game/initialize-game conn user-entity stocks)

        data-subscription-channel (chan)
        game-control              {:game                      game
                                   :stocks-with-tick-data     stocks-with-tick-data
                                   :tick-sleep-ms             500
                                   :data-subscription-channel data-subscription-channel
                                   :control-channel           (chan)
                                   :close-sink-fn             (partial source-stream nil)
                                   :sink-fn                   #(source-stream {:message (json/write-str %)})}]

    (register-game-control! game game-control)
    game-control))


(comment


  (initialize-game)
  (def result *1)

  (require '[integrant.repl.state])
  (-> integrant.repl.state/system :game/games)



  (>!! control-channel :foo)
  (>!! control-channel :exit)

  (def tick-sleep-ms 500)
  (def control-channel (chan))
  (let [data-sequence-channel (chan)]

    (core.async/onto-chan data-sequence-channel (range))

    (go-loop []
      (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                      control-channel])]
        (if (= :exit v)
          (println v)
          (do
            (println (<! data-sequence-channel))
            (recur))))))

  (def result *1)

  )
