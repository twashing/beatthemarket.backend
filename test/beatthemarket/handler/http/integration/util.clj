(ns beatthemarket.handler.http.integration.util
  (:require [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(defn start-game-workflow []

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                           :variables {:gameLevel gameLevel}}})

    (let [{:keys [stocks id] :as create-game-result} (-> (test-util/consume-until 987) :payload :data :createGame)]

      (test-util/send-data {:id   988
                            :type :start
                            :payload
                            {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                             :variables {:id id}}})

      (test-util/send-data {:id   989
                            :type :start
                            :payload
                            {:query "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }"
                             :variables {:gameId id}}})

      (test-util/<message!! 1000)

      create-game-result)))

(defn exit-game

  ([game-id] (exit-game game-id 993))

  ([game-id message-id]

   (test-util/send-data {:id   message-id
                         :type :start
                         :payload
                         {:query "mutation exitGame($gameId: String!) {
                                       exitGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }"
                          :variables {:gameId game-id}}})))
(defn restart-game

  ([game-id] (restart-game game-id 990))

  ([game-id message-id]

   (test-util/send-data {:id   message-id
                         :type :start
                         :payload
                         {:query "mutation RestartGame($gameId: String!) {
                                           restartGame(gameId: $gameId) {
                                             event
                                             gameId
                                           }
                                         }"
                          :variables {:gameId game-id}}})))
