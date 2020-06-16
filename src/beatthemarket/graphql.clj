(ns beatthemarket.graphql
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [com.rpl.specter :refer [select-one transform pred ALL MAP-VALS]]
            [beatthemarket.util :as util]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game :as game]
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

               ;; Initialize Game
        game (game/initialize-game conn user-entity)

        ;; TODO register game
        ;; TODO send initial game info
        ;; TODO stream tick data

        game-stocks (:game/stocks game)
        game-subscriptions (:game.user/subscriptions
                            (select-one [:game/users ALL (pred #(= result-user-id
                                                                   (-> % :game.user/user :db/id)))]
                                        game))

        message (transform [MAP-VALS ALL :game.stock/id] str
                           {:stocks game-stocks
                            :subscriptions game-subscriptions})
        runnable ^Runnable (fn []

                             ;; NOTE work in beatthemarket.game.games
                             (source-stream {:message (json/write-str message)})


                             #_(Thread/sleep 50)
                             #_(source-stream nil))]

    (.start (Thread. runnable "stream-new-game-thread"))

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
