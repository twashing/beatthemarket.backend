(ns beatthemarket.game.games
  (:require [clojure.core.async
             :as core.async :refer [go go-loop chan timeout alts!
                                    <! >!!]]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [integrant.core :as ig]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.game :as game]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))


(defn stream-subscription [tick-sleep-ms
                           data-subscription-channel
                           control-chanel
                           close-sink-fn
                           sink-fn]

  (go-loop []
    (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                    control-channel])]
      (if (= :exit v)
        (close-sink-fn)
        (do
          (sink-fn (<! data-subscription-channel))
          (recur))))))

(defn initialize-game [conn user-entity source-stream]

  (let [stocks (->> (name-generator/generate-names 4)
                    (map (juxt :stock-name :stock-symbol))
                    (map #(apply game/->stock %))
                    (map game/bind-temporary-id))

        bind-data-sequence #(->> (datasource/->combined-data-sequence datasource.core/beta-configurations :datasource.sine/generate-sine-sequence)
                                 (datasource/combined-data-sequence-with-datetime (t/now))
                                 (assoc % :data-sequence))

        stocks-with-tick-data (map bind-data-sequence stocks)

        game (game/initialize-game conn user-entity stocks-with-tick-data)]

    ;; TODO
    ;; get :data-subscription-channel
    ;; game/initialize-game AND stocks-with-tick-data without :data-sequence
    ;; register game in component

    {:game game
     :tick-sleep-ms 500
     :data-subscription-channel 1 ;; data-subscription-channel
     :control-chanel (chan)
     :close-sink-fn (partial source-stream nil)
     :sink-fn #(source-stream {:message (json/write-str %)})}))


(comment


  (initialize-game)
  (def result *1)

  (require '[integrant.repl.state])
  (-> integrant.repl.state/system :game/games pprint)


  ;; TODO
  ;; A. register game (with user) into beatthemarket.game.games
  ;; B. generate data sequences for each stock in game
  ;;    subscription pulls from the bound game

  ;; config for which data sequences to send
  ;; clock for each tick send

  (let [runnable ^Runnable (fn []

                             ;; game (or market)
                             ;; user
                             ;; stocks -> data-sequences

                             (loop [
                                    ;; new tick (on timer)
                                    ;; calculate profit | loss
                                    ;;   level completed
                                    ;;   game won (ended)
                                    ;;   game lost (ended)
                                    ;; signals
                                    ;;   game suspended
                                    ;;   game resumed
                                    ]

                               (recur))
                             )]

    (.start (Thread. runnable "stream-new-game-thread"))



    (def tick-sleep-ms 500)

    (>!! control-channel :foo)
    (>!! control-channel :exit)

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

    ))
