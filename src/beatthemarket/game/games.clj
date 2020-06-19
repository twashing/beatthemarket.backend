(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async
             :refer [go go-loop chan timeout alts! <! >!!]]
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

(defn stream-subscription! [tick-sleep-ms
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
  (swap! (:game/games repl.state/system)
         assoc (:game/id game) game-control))

(defn initialize-game [conn user-entity sink-fn]

  (let [bind-data-sequence    (fn [a]
                                (->> (datasource/->combined-data-sequence
                                       datasource.core/beta-configurations :datasource.sine/generate-sine-sequence)
                                     (datasource/combined-data-sequence-with-datetime (t/now))
                                     (map #(conj % (UUID/randomUUID)))
                                     (assoc a :data-sequence)))
        stocks                (game/generate-stocks 4)
        stocks-with-tick-data (map bind-data-sequence stocks)
        game                  (game/initialize-game conn user-entity stocks)

        data-subscription-channel (chan)
        game-control              {:game                      game
                                   :stocks-with-tick-data     stocks-with-tick-data
                                   :tick-sleep-ms             500
                                   :data-subscription-channel data-subscription-channel
                                   :control-channel           (chan)
                                   :close-sink-fn             (partial sink-fn nil)
                                   :sink-fn                   #(sink-fn {:message (json/write-str %)})}]

    (register-game-control! game game-control)
    game-control))


(defn create-game! [conn user-id sink-fn]
  (let [user-entity (hash-map :db/id user-id)]
    (initialize-game conn user-entity sink-fn)))

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


  (require '[beatthemarket.test-util :as test-util]
           '[beatthemarket.iam.authentication :as iam.auth]
           '[beatthemarket.iam.user :as iam.user])


  ;; A
  (do

    (def conn                   (-> repl.state/system :persistence/datomic :conn))
    (def id-token               (test-util/->id-token))
    (def checked-authentication (iam.auth/check-authentication id-token))
    (def add-user-db-result     (iam.user/conditionally-add-new-user! conn checked-authentication))
    (def result-user-id         (ffirst
                                  (d/q '[:find ?e
                                         :in $ ?email
                                         :where [?e :user/email ?email]]
                                       (d/db conn)
                                       (-> checked-authentication
                                           :claims (get "email")))))
    (def sink-fn                util/pprint+identity)
    (def game-control           (create-game! conn result-user-id sink-fn)))


  ;; B
  (let [{:keys [game stocks-with-tick-data tick-sleep-ms
                data-subscription-channel control-channel
                close-sink-fn sink-fn]}
        game-control]

    (stream-subscription! tick-sleep-ms
                          data-subscription-channel control-channel
                          close-sink-fn sink-fn)

    ;; (def message                (game->new-game-message game result-user-id))
    ;; (>!! data-subscription-channel message)

    (core.async/onto-chan
      data-subscription-channel
      (data-subscription-stock-sequence conn game result-user-id stocks-with-tick-data)))

  ;; C
  (-> game-control :control-channel (>!! :exit)))

(comment


  (initialize-game)
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
