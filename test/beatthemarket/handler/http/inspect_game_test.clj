(ns beatthemarket.handler.http.inspect-game-test
  (:require [clojure.test :refer :all]
            [integrant.repl.state :as state]
            [com.rpl.specter :refer [transform ALL]]

            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :as util]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))


(def expected-user {:userEmail "twashing@gmail.com"
                    :userName "Timothy Washington"})
(def expected-user-keys #{:userEmail :userName :userExternalUid})


(deftest query-user-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"]


    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "query User($email: String!) {
                                     user(email: $email) {
                                       userEmail
                                       userName
                                       userExternalUid
                                     }
                                   }"
                           :variables {:email email}}})


    (let [result-user (-> (test-util/<message!! 1000) :payload :data :user)]

      (->> (keys result-user)
           (into #{})
           (= expected-user-keys)
           is)

      (->> (transform [identity] #(dissoc % :userExternalUid) result-user)
           (= expected-user)
           is))))

(deftest query-users-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"]


    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "query Users {
                                     users {
                                       userEmail
                                       userName
                                       userExternalUid
                                     }
                                   }"}})

    (let [result-users (-> (test-util/<message!! 1000) :payload :data :users)]

      (->> (map keys result-users)
           (map #(into #{} %))
           (map #(= expected-user-keys %))
           is)

      (->> (first result-users)
           (transform [identity] #(dissoc % :userExternalUid))
           (= expected-user)
           is))))

(deftest query-account-balances-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"
        gameLevel "one"]

    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation CreateGame($gameLevel: String!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }"
                           :variables {:gameLevel gameLevel}}})

    (let [{gameId :id} (-> (test-util/<message!! 1000) :payload :data :createGame)]

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

(deftest query-stock-time-series-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"
        gameLevel "one"]

    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation CreateGame($gameLevel: String!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }"
                           :variables {:gameLevel gameLevel}}})

    (let [{gameId :id} (-> (test-util/<message!! 1000) :payload :data :createGame)
          stockId "asdf"]

      (test-util/send-data {:id   988
                            :type :start
                            :payload
                            {:query "query StockTimeSeries($gameId: String!, $stockId: String!, $range: [Int]) {
                                       stockTimeSeries(gameId: $gameId, stockId: $stockId, range: $range) {
                                         stockTickId
                                         stockTickTime
                                         stockTickClose
                                         stockId
                                         stockName
                                       }
                                   }"
                             :variables {:gameId gameId
                                         :stockId stockId
                                         :range [0 10]}}})

      (test-util/<message!! 1000)
      (test-util/<message!! 1000)

      )))
