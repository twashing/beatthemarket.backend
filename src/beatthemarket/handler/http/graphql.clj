(ns beatthemarket.handler.http.graphql
  (:require [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [beatthemarket.util :as util]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game :as game]
            [clojure.data.json :as json]))


(defn resolve-hello
  [context args value]
  "Hello, Clojurians!")

(defn resolve-login
  [context _ _]

  (let [{{checked-authentication :checked-authentication} :request} context
        conn (-> integrant.repl.state/system :persistence/datomic :conn)]

    (let [{:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)]

      (if (-> (and db-before db-after tx-data tempids)
              util/truthy?)
        "user-added"
        "user-exists"))))

(defn stream-new-game
  [context args source-stream]

  (let [{{{email :email :as checked-authentication} :checked-authentication} :request} context
        conn (-> integrant.repl.state/system :persistence/datomic :conn)

        result-user-id (-> (d/q '[:find ?e
                                  :in $ ?email
                                  :where [?e :user/email ?email]]
                                (d/db conn)
                                email)
                           ffirst)

        user-entity (hash-map :db/id result-user-id)

        ;; Initialize Game
        message (as-> (game/initialize-game conn user-entity) game
                  (select-keys game [:game/subscriptions :game/stocks])
                  (transform [MAP-VALS ALL :game.stock/id] str game))

        runnable ^Runnable (fn []
                             (source-stream {:message (json/write-str message)})
                             (Thread/sleep 50)
                             (source-stream nil))]

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
