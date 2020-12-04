(ns beatthemarket.handler.http.integration.portfolio-updates-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))

(deftest portfolio-updates-test

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
                 :variables {:gameLevel gameLevel}}}))


  (let [{:keys [stocks id] :as createGameAck} (-> (test-util/consume-until 987) :payload :data :createGame)]

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

    (test-util/send-data {:id   990
                :type :start
                :payload
                {:query "subscription PortfolioUpdates($gameId: String!) {
                           portfolioUpdates(gameId: $gameId) {
                             ... on ProfitLoss {
                               profitLoss
                               stockId
                               gameId
                               profitLossType
                             }
                             ... on AccountBalance {
                               id
                               name
                               balance
                               counterParty
                               amount
                             }
                           }
                         }"
                 :variables {:gameId id}}})


    (let [latest-tick (->> (test-util/consume-subscriptions)
                           (filter #(= 989 (:id %)))
                           last)
          [{stockTickId :stockTickId
            stockTickTime :stockTickTime
            stockTickClose :stockTickClose
            stockId :stockId
            stockName :stockName}]
          (-> latest-tick :payload :data :stockTicks)]

      (test-util/send-data {:id   991
                            :type :start
                            :payload
                            {:query "mutation BuyStock($input: BuyStock!) {
                                         buyStock(input: $input) {
                                           message
                                         }
                                     }"
                             :variables {:input {:gameId      id
                                                 :stockId     stockId
                                                 :stockAmount 100
                                                 :tickId      stockTickId
                                                 :tickPrice   stockTickClose}}}})

      (test-util/<message!! 1000)
      (test-util/<message!! 1000)
      (test-util/<message!! 1000)
      (test-util/<message!! 1000)


      (let [expected-profit-loss-keys #{:profitLoss :stockId :gameId :profitLossType}
            expected-account-update-keys #{:id :name :balance :counterParty :amount}
            collect-events (comp :portfolioUpdates :data :payload)
            portfolio-updates (->> (test-util/consume-subscriptions)
                                   (filter #(= 990 (:id %)))
                                   (map collect-events))

            expected-portfolio-update-count 2]

        (is (= expected-portfolio-update-count (count portfolio-updates)))

        (->> (filter :profitLossType portfolio-updates)
             (map keys)
             (map #(into #{} %))
             (map #(= expected-profit-loss-keys %))
             is)

        (->> (filter :amount portfolio-updates)
             (map keys)
             (map #(into #{} %))
             (map #(= expected-account-update-keys %))
             is)))))
