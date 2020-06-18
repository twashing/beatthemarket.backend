(ns beatthemarket.graphql
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :as util]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game :as game]
            [beatthemarket.game.games :as games]
            [clojure.data.json :as json]))


(defn resolve-hello
  [_context _args _value]
  "Hello, Clojurians!")

(defn resolve-login
  [context _ _]

  (let [{{checked-authentication :checked-authentication} :request} context
        conn (-> repl.state/system :persistence/datomic :conn)
        {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)]

    (if (util/truthy? (and db-before db-after tx-data tempids))
      "user-added"
      "user-exists")))


;; TODO
;; Buy Stock
;;   ? pause subscription stream
;;     custom onto-chan (pause, inject vals)
;;   db/q game stock
;;   verify tick id
;;   verify tick price
;;   verify buy price is most recent
;;   tentry verify balanced


(defn resolve-buy-stock [context a b]

  (println "resolve-buy-stock CALLED / " a b)
  {:message "Ack"})

(defn stream-new-game
  [context _ source-stream]

  (let [{{{email :email} :checked-authentication} :request} context
        conn (-> repl.state/system :persistence/datomic :conn)

        result-user-id (ffirst
                         (d/q '[:find ?e
                                :in $ ?email
                                :where [?e :user/email ?email]]
                              (d/db conn)
                              email))

        user-entity (hash-map :db/id result-user-id)

        ;; A
        {:keys                         [game stocks-with-tick-data tick-sleep-ms
                                        data-subscription-channel control-channel
                                        close-sink-fn sink-fn] :as game-control}
        (games/initialize-game conn user-entity source-stream)

        ;; B
        game-stocks (:game/stocks game)
        game-subscriptions (:game.user/subscriptions (game/game-user-by-user-id game result-user-id))
        message (-> (transform [MAP-VALS ALL :game.stock/id] str
                               {:stocks        game-stocks
                                :subscriptions game-subscriptions})
                    (assoc :game/id (str (:game/id game))))]

    ;; C
    (games/stream-subscription tick-sleep-ms
                               data-subscription-channel control-channel
                               close-sink-fn sink-fn)

    (>!! data-subscription-channel message)

    ;; D
    (let [data-subscription-stock
          (->> (game/game-user-by-user-id game (:db/id user-entity))
               :game.user/subscriptions
               (map :game.stock/id)
               (into #{})
               (games/narrow-stocks-by-game-user-subscription stocks-with-tick-data))

          ;; NOTE have a mechanism to stream multiple subscriptions
          conn (-> integrant.repl.state/system :persistence/datomic :conn)
          data-subscription-stock-sequence
          (->> data-subscription-stock first :data-sequence
               (map (fn [[m v t]]

                      (let [moment (str m)
                            value v
                            tick-id (str t)]

                        ;; i
                        (persistence.datomic/transact-entities!
                          conn
                          (hash-map
                            :game.stock.tick/trade-time m
                            :game.stock.tick/close value
                            :game.stock.tick/id t))

                        ;; ii
                        [moment value tick-id]))))]

      (core.async/onto-chan data-subscription-channel data-subscription-stock-sequence))

    ;; Return a cleanup fn
    (constantly nil)))

;; NOTE subscription resolver
(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))
(def *ping-context (atom nil))

(defn stream-ping
  [context args source-stream]
  (swap! *ping-subscribes inc)
  (reset! *ping-context context)
  (let [{:keys [message count]} args
        runnable ^Runnable (fn []
                             (dotimes [i count]

                               ;; (println "Sanity check / stream-ping / " [i count])
                               (source-stream {:message (str message " #" (inc i))
                                               :timestamp (System/currentTimeMillis)})
                               (Thread/sleep 50))

                             (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))
