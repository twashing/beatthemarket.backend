(ns beatthemarket.handler.http.integration.util
  (:require [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(defn start-game-workflow

  ([]
   (start-game-workflow 987))

  ([create-id]
   (start-game-workflow create-id 988))

  ([create-id start-id]
   (start-game-workflow create-id start-id 989))

  ([create-id start-id stock-tick-id]

   (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
         id-token (test-util/->id-token)
         gameLevel 1]

     (test-util/send-data {:id   create-id
                           :type :start
                           :payload
                           {:query "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                            :variables {:gameLevel gameLevel}}})

     (let [{:keys [stocks id] :as create-game-result} (-> (test-util/consume-until create-id) :payload :data :createGame)]

       (test-util/send-data {:id   start-id
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
       (test-util/consume-until start-id)

       (test-util/send-data {:id   stock-tick-id
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
       (test-util/consume-until stock-tick-id)

       create-game-result))))

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

(defn buy-stock [buy-id game-id stock-id stock-amount stock-tick-id stock-tick-close]

  (test-util/send-data {:id   buy-id
                        :type :start
                        :payload
                        {:query "mutation BuyStock($input: BuyStock!) {
                                           buyStock(input: $input) {
                                             message
                                           }
                                         }"
                         :variables {:input {:gameId      game-id
                                             :stockId     stock-id
                                             :stockAmount stock-amount
                                             :tickId      stock-tick-id
                                             :tickPrice   stock-tick-close}}}}))

(defn sell-stock [sell-id game-id stock-id stock-amount stock-tick-id stock-tick-close]

  (test-util/send-data {:id   sell-id
                        :type :start
                        :payload
                        {:query "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }"
                         :variables {:input {:gameId      game-id
                                             :stockId     stock-id
                                             :stockAmount stock-amount
                                             :tickId      stock-tick-id
                                             :tickPrice   stock-tick-close}}}}))

(defn verify-payment

  ([client-id payload]

   (verify-payment client-id payload 989))

  ([client-id payload verify-payment-id]

   (test-util/send-init {:client-id (str client-id)})
   (test-util/send-data {:id   verify-payment-id
                         :type :start
                         :payload
                         {:query     "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                          :variables payload}})))

(defn verify-payment-workflow

  ([client-id product-id provider token]

   (verify-payment-workflow client-id product-id provider token 987))

  ([client-id product-id provider token verify-payment-id]

   (test-util/send-init {:client-id (str client-id)})
   (test-util/send-data {:id   verify-payment-id
                         :type :start
                         :payload
                         {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                          :variables {:productId product-id
                                      :provider provider
                                      :token token}}})

   (Thread/sleep 2000)
   (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)))

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
