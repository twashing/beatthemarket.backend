(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async
             :refer [go go-loop chan close! timeout alts! >! <! >!!]]
            [clojure.core.async.impl.protocols]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.game :as game]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.bookkeeping :as bookkeeping])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))

(defmethod ig/halt-key! :game/games [_ games]
  (run! (fn [{:keys [data-subscription-channel control-channel]}]

          (println "Closing Game channels...")
          (close! data-subscription-channel)

          (go (>! control-channel :exit))
          (close! control-channel))
        (-> games deref vals)))


(defn onto-open-chan
  "Clone of clojure.core.async. But only puts to open channels.

   Puts the contents of coll into the supplied channel.

   By default the channel will be closed after the items are copied,
   but can be determined by the close? parameter.

   Returns a channel which will close after the items are copied."
  ([ch coll] (onto-open-chan ch coll true))
  ([ch coll close?]
   (go-loop [vs (seq coll)]

     (println "Channel open? " (not (clojure.core.async.impl.protocols/closed? ch)))
     (if (and vs
              (not (clojure.core.async.impl.protocols/closed? ch))
              (>! ch (first vs)))
       (recur (next vs))
       (when close?
         (close! ch))))))


;; C.
;; Calculate Profit / Loss

;; D.
;; Complete a Level

;; E.
;; Win a Game
;; Lose a Game

;; F.
;; Pause | Resume a Game

(defn stream-subscription! [tick-sleep-ms
                            data-subscription-channel
                            control-channel
                            close-sink-fn
                            sink-fn]

  (go-loop []
    (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                    control-channel])]

      ;; (println (format "go-loop / value / %s" v))
      (if (= :exit v)
        (close-sink-fn)
        (do
          (let [vv (<! data-subscription-channel)]

            ;; TODO calculate game position
            ;; (println (format "Sink value / %s" vv))
            (sink-fn vv))
          (recur))))))

(defn narrow-stocks-by-game-user-subscription [stocks subscription-id-set]
  (filter #(some subscription-id-set [(:game.stock/id %)])
          stocks))

(defn register-game-control! [game game-control]
  (swap! (:game/games repl.state/system)
         assoc (:game/id game) game-control))

(defn initialize-game! [conn user-entity sink-fn]

  (let [bind-data-sequence    (fn [a]
                                (->> (datasource/->combined-data-sequence
                                       datasource.core/beta-configurations :datasource.sine/generate-sine-sequence)
                                     (datasource/combined-data-sequence-with-datetime (t/now))
                                     (map #(conj % (UUID/randomUUID)))
                                     (assoc a :data-sequence)))
        stocks                (game/generate-stocks 4)
        stocks-with-tick-data (map bind-data-sequence stocks)
        game                  (game/initialize-game! conn user-entity stocks)

        game-control              {:game                      game
                                   :tick-sleep-ms             500
                                   :stocks-with-tick-data     stocks-with-tick-data
                                   :data-subscription-channel (chan)
                                   :control-channel           (chan)
                                   :close-sink-fn             (partial sink-fn nil)
                                   :sink-fn                   #(sink-fn {:message (json/write-str %)})}]

    (register-game-control! game game-control)
    game-control))


(defn create-game! [conn user-id sink-fn]
  (let [user-entity (hash-map :db/id user-id)]
    (initialize-game! conn user-entity sink-fn)))

(defn game->new-game-message [game user-id]

  (let [game-stocks        (:game/stocks game)
        game-subscriptions (:game.user/subscriptions (game/game-user-by-user-id game user-id))]

    (-> (transform [MAP-VALS ALL :game.stock/id] str
                   {:stocks        game-stocks
                    :subscriptions game-subscriptions})
        (assoc :game/id (str (:game/id game))))))

(defn data-subscription-stock-sequence [conn game user-id stocks-with-tick-data]

  (let [data-subscription-stock
        (->> (game/game-user-by-user-id game user-id)
             :game.user/subscriptions
             (map :game.stock/id)
             (into #{})
             (narrow-stocks-by-game-user-subscription stocks-with-tick-data))]

    (->> data-subscription-stock first :data-sequence
         (map (fn [[m v t]]

                (let [moment  (str m)
                      value   v
                      tick-id (str t)]

                  ;; i
                  (persistence.datomic/transact-entities!
                    conn
                    (hash-map
                      :game.stock.tick/trade-time m
                      :game.stock.tick/close value
                      :game.stock.tick/id t))

                  ;; ii
                  [moment value tick-id]))))))

(comment


  (initialize-game!)
  (def result *1)


  (-> repl.state/system :game/games)


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

  (def result *1))
