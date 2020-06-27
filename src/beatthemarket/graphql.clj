(ns beatthemarket.graphql
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :as util]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]
            [clojure.data.json :as json])
  (:import [java.util UUID]))


(defn resolve-hello
  [_context _args _value]
  "Hello, Clojurians!")

(defn resolve-login
  [context _ _]

  (let [{{checked-authentication :checked-authentication}
         :request}                                   context
        conn                                         (-> repl.state/system :persistence/datomic :conn)
        {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)]

    (if (util/truthy? (and db-before db-after tx-data tempids))
      "user-added"
      "user-exists")))

(defn resolve-buy-stock [context args _]

  ;; (println "resolve-buy-stock CALLED /" args)
  (let [{{{userId :uid} :checked-authentication} :request} context
        conn                                               (-> repl.state/system :persistence/datomic :conn)
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        gameId (UUID/fromString gameId)]

    (try
      (game.games/buy-stock! conn userId gameId stockId stockAmount tickId tickPrice)
      (catch Throwable e
        {:message (ex-data e)}))

    {:message "Ack"}))

(defn stream-new-game
  [context _ source-stream]

  (let [conn                                                (-> repl.state/system :persistence/datomic :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))

        ;; A
        {:keys                         [game stocks-with-tick-data tick-sleep-ms
                                        stock-stream-channel control-channel
                                        close-sink-fn sink-fn] :as game-control} (game.games/create-game! conn user-db-id source-stream)]

    ;; B
    (let [message (game.games/game->new-game-message game user-db-id)]
      (>!! stock-stream-channel message))

    ;; C
    (game.games/start-game! conn user-db-id game-control)


    ;; D Return a cleanup fn
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
        runnable                ^Runnable (fn []
                                            (dotimes [i count]

                                              ;; (println "Sanity check / stream-ping / " [i count])
                                              (source-stream (util/pprint+identity {:message   (str message " #" (inc i))
                                                                                    :timestamp (System/currentTimeMillis)}))
                                              (Thread/sleep 50))

                                            (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))
