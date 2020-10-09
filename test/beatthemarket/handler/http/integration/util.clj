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

(defn verify-payment

  ([client-id payload]

   (verify-payment client-id payload 989))

  ([client-id payload message-id]

   (test-util/send-init {:client-id (str client-id)})
   (test-util/send-data {:id   message-id
                         :type :start
                         :payload
                         {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                          :variables payload}})))

(defn delete-test-customer!

  ([id]

   (delete-test-customer! id 987))

  ([id delete-id]

   (test-util/send-data {:id   delete-id
                         :type :start
                         :payload
                         {:query "mutation DeleteStripeCustomer($id: String!) {
                                           deleteStripeCustomer(id: $id) {
                                             message
                                           }
                                         }"
                          :variables {:id id}}})))

(defn create-test-customer!

  ([email]

   (create-test-customer! email 987))

  ([email create-id]

   (test-util/send-data {:id   create-id
                         :type :start
                         :payload
                         {:query "mutation CreateStripeCustomer($email: String!) {
                                       createStripeCustomer(email: $email) {
                                         id
                                         email
                                       }
                                     }"
                          :variables {:email email}}})))
