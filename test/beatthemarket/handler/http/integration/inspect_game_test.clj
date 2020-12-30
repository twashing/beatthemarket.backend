(ns beatthemarket.handler.http.integration.inspect-game-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource]]
            [integrant.repl.state :as state]
            [com.rpl.specter :refer [transform ALL]]

            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.handler.http.integration.util :as integration.util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8080/ws"))


(def expected-user {:userEmail "twashing@gmail.com"
                    :userName "Timothy Washington"})
(def expected-user-keys #{:userEmail :userName :userExternalUid})

(defn create-exit-game! [message-id]

  (let [{game-id :id :as result} (integration.util/start-game-workflow)
        game-id-uuid (UUID/fromString game-id)]

    (integration.util/exit-game game-id message-id)

    (games.control/update-short-circuit-game! game-id-uuid true)

    (test-util/consume-until message-id)

    result))

(deftest query-user-without-game-test

  ;; TODO complete
  (testing "User without Game and P/L"

    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          id-token (test-util/->id-token)
          client-id (UUID/randomUUID)
          email "twashing@gmail.com"]


      (test-util/login-assertion service id-token)


      (let [product-id "prod_I1RAoB8UK5GDab"
            provider "stripe"

            create-customer-id 1001
            _ (integration.util/create-test-customer! email create-customer-id)

            [{result-id :id
              result-email :email}] (-> (test-util/consume-until create-customer-id) :payload :data :createStripeCustomer)

            token (-> "example-payload-stripe-subscription-valid.json"
                      resource
                      slurp
                      (json/read-str :key-fn keyword))
            token-json (json/write-str
                         (assoc token
                                :customerId result-id
                                :paymentMethodId "pm_card_visa"))

            payment-id 988]

        (integration.util/verify-payment client-id {:productId product-id
                                                    :provider provider
                                                    :token token-json} payment-id)

        (test-util/consume-until payment-id)

        (Thread/sleep 2000)
        (test-util/send-data {:id   989
                              :type :start
                              :payload
                              {:query "query User($email: String!) {
                                         user(email: $email) {
                                           userEmail
                                           userName
                                           userExternalUid
                                           subscriptions {
                                             paymentId
                                             productId
                                             provider
                                           }
                                           games {
                                             gameId status
                                             profitLoss {
                                               profitLoss
                                               stockId
                                               gameId
                                               profitLossType
                                             }
                                           }
                                         }
                                       }"
                               :variables {:email email}}})

        (let [expected-user {:type "data"
                             :id 989
                             :payload
                             {:data
                              {:user
                               {:userEmail email
                                :userName "Timothy Washington"
                                :userExternalUid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"
                                :subscriptions
                                [{;; :paymentId "5452ed64-bfa4-49b7-baa0-0922be5afc98"
                                  :productId product-id
                                  :provider "stripe"}]
                                :games
                                [{:gameId "88c3c8f9-5377-40ba-b278-6d3c79d011b2"
                                  :status "lost"
                                  :profitLoss
                                  [{:profitLoss 6.5
                                    :stockId nil
                                    :gameId "88c3c8f9-5377-40ba-b278-6d3c79d011b2"
                                    :profitLossType nil}]}]}}}}

              user-result (test-util/consume-until 989)]

          (ppi user-result)

          ;; TODO
          #_(is (= expected-user
                 (update-in user-result [:payload :data :user :subscriptions 0] #(dissoc % :paymentId)))))


        (testing "DELETEING test customer"

          (let [delete-customer-id 1010]
            (integration.util/delete-test-customer! result-id delete-customer-id)))))))

(deftest query-user-with-game-and-profit-loss-test

  (testing "User with Game and P/L"

    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          id-token (test-util/->id-token)
          client-id (UUID/randomUUID)

          email "twashing@gmail.com"
          query-user-id 987]

      (test-util/send-init {:client-id (str client-id)})
      (test-util/login-assertion service id-token)

      (let [{game-id1 :id} (create-exit-game! 1000)
            {game-id2 :id} (create-exit-game! 1001)]

        (test-util/send-data {:id   query-user-id
                              :type :start
                              :payload
                              {:query "query User($email: String!) {
                                         user(email: $email) {
                                           userEmail
                                           userExternalUid
                                           games {
                                             gameId status
                                             profitLoss {
                                               profitLoss
                                               stockId
                                               gameId
                                               profitLossType
                                             }
                                           }
                                         }
                                       }"
                               :variables {:email email}}})

        (let [expected-user-query-result
              {:type "data"
               :id query-user-id
               :payload
               {:data
                {:user
                 {:userEmail "twashing@gmail.com"
                  ;; :userName "Timothy Washington"
                  :userExternalUid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"
                  :games
                  #{{:gameId game-id1
                     :status "exited"
                     :profitLoss []}
                    {:gameId game-id2
                     :status "exited"
                     :profitLoss []}}}}}}]

          (is (= expected-user-query-result
                 (update-in (test-util/consume-until query-user-id)
                            [:payload :data :user :games]
                            set))))))))

(deftest query-users-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)
        email "twashing@gmail.com"]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (create-exit-game! 1000)
    (create-exit-game! 1001)

    (let [users-message-id 1002]

      (test-util/send-data {:id   users-message-id
                            :type :start
                            :payload
                            {:query "query Users {
                                     users {
                                       userEmail
                                       userName
                                       userExternalUid
                                       games {
                                         gameId
                                         status
                                         profitLoss {
                                           profitLoss
                                           stockId
                                           gameId
                                           profitLossType
                                         }
                                       }
                                     }
                                   }"}})

      (let [expected-user-emails #{"charles.mingus@foo.com"
                                   "john.coltrane@foo.com"
                                   "herbie.hancock@foo.com"
                                   "miles.davis@foo.com"
                                   "sun.ra@foo.com"
                                   "thelonious.monk@foo.com"
                                   "twashing@gmail.com"}]

        (->> (test-util/consume-until users-message-id)
             :payload :data :users
             (map :userEmail)
             set
             (= expected-user-emails)
             is)))))

(deftest query-account-balances-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"
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


    (let [{gameId :id} (-> (test-util/consume-until 987) :payload :data :createGame)]

      (test-util/send-data {:id   988
                            :type :start
                            :payload
                            {:query "query AccountBalances($gameId: String!, $email: String!) {
                                       accountBalances(gameId: $gameId, email: $email) {
                                         id
                                         name
                                         balance
                                         counterParty
                                         amount
                                       }
                                   }"
                             :variables {:gameId gameId
                                         :email  email}}})

      (test-util/<message!! 1000)

      (let [expected-user-account-balances #{{:name "Cash"
                                              :balance 100000.0
                                              :counterParty nil
                                              :amount 0}
                                             {:name "Equity"
                                              :balance 100000.0
                                              :counterParty nil
                                              :amount 0}}

            result-accounts (->> (test-util/<message!! 1000) :payload :data :accountBalances
                                 (map #(dissoc % :id))
                                 (into #{}))]

            (is (= expected-user-account-balances result-accounts))))))
