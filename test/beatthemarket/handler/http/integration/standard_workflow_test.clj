(ns beatthemarket.handler.http.integration.standard-workflow-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [clojure.core.async :as core.async
             :refer [<!!]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [aero.core :as aero]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [io.pedestal.http :as server]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.test-util :as test-util]
            [integrant.repl.state :as state]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.handler.http.service :as http.service]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util]
            [clj-time.coerce :as c])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))

(deftest subscription-handler-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)]

    (testing "Auth interceptor rejects GQL call"
      (let [expected-error-status 401
            {status :status} (response-for service
                                           :post "/api"
                                           :body "{\"query\": \"{ hello }\"}"
                                           :headers {"Content-Type" "application/json"})]

        (is (= expected-error-status status))))

    (testing "Exception handler format"

      (with-redefs [auth/auth-request-handler identity]

        (let [expected-status 400
              expected-body {:errors
                             [{:message "Cannot query field `foobar' on type `QueryRoot'."
                               :locations [{:line 1 :column 3}]
                               :extensions {:type "QueryRoot" :field "foobar"}}]}
              expected-headers {"Content-Type" "application/json"}

              {status :status
               body :body
               headers :headers}
              (response-for service
                            :post "/api"
                            :body "{\"query\": \"{ foobar }\"}"
                            :headers {"Content-Type" "application/json"})

              body-parsed (json/read-str body :key-fn keyword)]

          (are [x y] (= x y)
            expected-status status
            expected-body body-parsed
            expected-headers headers))))))

(deftest subscriptions-ws-request
  (testing "Basic WS connection"
    (test-util/send-init)
    (test-util/expect-message {:type "connection_ack"})))

(deftest create-game-resolver-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel "one"]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame($gameLevel: String!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                             :variables {:gameLevel gameLevel}}}))

    (testing "We are returned expected game information [stocks subscriptions id]"

      (let [result (test-util/<message!! 1000)
            {:keys [stocks id]} (-> result :payload :data :createGame)]

        (is (UUID/fromString id))
        (is (= 4 (count stocks)))
        (->> (map keys stocks)
             (map #(into #{} %))
             (every? #(= #{:id :name :symbol} %))
             is)

        (testing "Returned game is what's registered in the :game/games component"

          (let [game-id (UUID/fromString id)
                expected-component-game-keys
                #{:game
                  :control-channel
                  :stocks-with-tick-data
                  :profit-loss
                  :input-sequence
                  :current-level

                  :transact-profit-loss-mappingfn
                  :stream-portfolio-update-mappingfn
                  :calculate-profit-loss-mappingfn
                  :collect-profit-loss-mappingfn
                  :transact-tick-mappingfn
                  :stream-stock-tick-mappingfn
                  :check-level-complete-mappingfn

                  :close-sink-fn
                  :sink-fn

                  :paused?
                  :level-timer-atom
                  :tick-sleep-atom

                  :portfolio-update-stream
                  :stock-tick-stream
                  :game-event-stream}]

            (->> state/system :game/games deref (#(get % game-id)) keys
                 (into #{})
                 (= expected-component-game-keys)
                 is)))))))

(deftest start-game-resolver-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel "one"]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame($gameLevel: String!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                             :variables {:gameLevel gameLevel}}}))

    (testing "Start a Game"

      (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

        (test-util/send-data {:id   987
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

        (as-> (:game/games state/system) gs
          (deref gs)
          (get gs (UUID/fromString id))
          (:control-channel gs)
          (core.async/>!! gs {:type :ControlEvent
                              :event :exit
                              :game-id id}))


        (test-util/<message!! 1000)

        (let [expected-result []
              result (-> (test-util/<message!! 1000) :payload :data :startGame)]

          (is (= expected-result result)))))))

(deftest start-game-resolver-with-start-position-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel "one"]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame($gameLevel: String!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                             :variables {:gameLevel gameLevel}}}))

    (testing "Start a Game"

      (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)
            startPosition 10]

        (test-util/send-data {:id   988
                              :type :start
                              :payload
                              {:query "mutation StartGame($id: String!, $startPosition: Int) {
                                         startGame(id: $id, startPosition: $startPosition) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                               :variables {:id id
                                           :startPosition startPosition}}})

        (as-> (:game/games state/system) gs
          (deref gs)
          (get gs (UUID/fromString id))
          (:control-channel gs)
          (core.async/>!! gs {:type :ControlEvent
                              :event :exit
                              :game-id id}))


        (test-util/<message!! 1000)

        (let [expected-historical-data-length startPosition
              result (-> (test-util/<message!! 1000) :payload :data :startGame)]


          (is (= expected-historical-data-length (count result)))

          (->> result
               (map #(map keys %))
               (map #(map (fn [a] (into #{} a)) %))
               (map #(every? (fn [a]
                               (= #{:stockTickId :stockTickTime :stockTickClose :stockId :stockName}
                                  a)) %))
               (every? true?)
               is))))))

(deftest stream-stock-ticks-test

    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          id-token (test-util/->id-token)
          gameLevel "one"]


      (testing "REST Login (not WebSocket) ; creates a user"

        (test-util/login-assertion service id-token))


      (testing "Create a Game"

        (test-util/send-data {:id   987
                              :type :start
                              :payload
                              {:query "mutation CreateGame($gameLevel: String!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks  { id name symbol }
                                       }
                                     }"
                               :variables {:gameLevel gameLevel}}}))

      (testing "Start a Game"

        (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

          (test-util/send-data {:id   987
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

          (test-util/<message!! 1000)
          (test-util/<message!! 1000)

          (testing "Stream Stock Ticks

                  We should expect a structure that looks like this

                  {:type \"data\"
                   :id 987
                   :payload
                   {:data
                    {:stockTicks
                     [{:stockTickId \"32bd40bb-c4b3-4f07-9667-781c67d4e1f5\"
                       :stockTickTime \"1595692766979\"
                       :stockTickClose 149.02000427246094
                       :stockId \"d658021f-ca4e-4e34-a6ee-2a9fc8bb253d\"
                       :stockName \"Outside Church\"}
                      {:stockTickId \"df09933d-5879-45e5-b038-40498d7ca198\"
                       :stockTickTime \"1595692766979\"
                       :stockTickClose 149.02000427246094
                       :stockId \"942349f5-94ef-4ed3-8470-a9fb1123dbb8\"
                       :stockName \"Sick Dough\"}
                      {:stockTickId \"324e662b-24ac-4b0d-8f91-ebee01f029d9\"
                       :stockTickTime \"1595692766979\"
                       :stockTickClose 149.02000427246094
                       :stockId \"97fa4791-47f8-42ff-8683-a235284de178\"
                       :stockName \"Vigorous Grip\"}
                      {:stockTickId \"cce40bb5-279e-47d6-a3c1-0e587c8e097b\"
                       :stockTickTime \"1595692766979\"
                       :stockTickClose 149.02000427246094
                       :stockId \"e02e81a7-15c1-4c4e-996a-bc65c8de4a9a\"
                       :stockName \"Color-blind Maintenance\"}]}}}"

            (test-util/send-data {:id   987
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
                                   :variables {:gameId id}}}))

          (as-> (:game/games state/system) gs
            (deref gs)
            (get gs (UUID/fromString id))
            (:control-channel gs)
            (core.async/>!! gs {:type :ControlEvent
                                :event :exit
                                :game-id id}))

          (test-util/<message!! 1000)

          (let [expected-keys #{:stockTickId :stockTickTime :stockTickClose :stockId :stockName}
                stockTicks (-> (test-util/<message!! 1000) :payload :data :stockTicks)]

            (->> (map #(into #{} (keys %)) stockTicks)
                 (map #(= expected-keys %))
                 (every? true?)
                 is))))))

(defn- consume-latest-tick []

  (let [latest-tick (atom nil)]
    (loop [r (test-util/<message!! 1000)]
      (if (= :beatthemarket.test-util/timed-out r)
        @latest-tick
        (do
          (reset! latest-tick r)
          (recur (test-util/<message!! 1000)))))))

(deftest buy-stock-test

    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          id-token (test-util/->id-token)
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

      (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

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

        (test-util/<message!! 1000)
        (test-util/<message!! 1000)

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

        (as-> (:game/games state/system) gs
          (deref gs)
          (get gs (UUID/fromString id))
          (:control-channel gs)
          (core.async/>!! gs {:type :ControlEvent
                              :event :exit
                              :game-id id}))


        (let [latest-tick (->> (test-util/consume-subscriptions)
                               (filter #(= 989 (:id %)))
                               last)
              [{stockTickId :stockTickId
                stockTickTime :stockTickTime
                stockTickClose :stockTickClose
                stockId :stockId
                stockName :stockName}]
              (-> latest-tick :payload :data :stockTicks)]

          (test-util/send-data {:id   990
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


          (let [ack (test-util/<message!! 1000)]

            (is (= {:type "data" :id 990 :payload {:data {:buyStock {:message "Ack"}}}}
                   ack)))))))

(deftest sell-stock-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
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

    (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

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

      (test-util/<message!! 1000)
      (test-util/<message!! 1000)

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

      (as-> (:game/games state/system) gs
        (deref gs)
        (get gs (UUID/fromString id))
        (:control-channel gs)
        (core.async/>!! gs {:type :ControlEvent
                            :event :exit
                            :game-id id}))


      (let [latest-tick (->> (test-util/consume-subscriptions)
                             (filter #(= 989 (:id %)))
                             last)
            [{stockTickId :stockTickId
              stockTickTime :stockTickTime
              stockTickClose :stockTickClose
              stockId :stockId
              stockName :stockName}]
            (-> latest-tick :payload :data :stockTicks)]

        (test-util/send-data {:id   990
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

        (test-util/<message!! 1000) ;;{:type "data", :id 990, :payload {:data {:buyStock {:message "Ack"}}}}
        (test-util/<message!! 1000) ;;{:type "complete", :id 990}

        (testing "Selling the stock"
          (test-util/send-data {:id   991
                                :type :start
                                :payload
                                {:query "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }"
                                 :variables {:input {:gameId      id
                                                     :stockId     stockId
                                                     :stockAmount 100
                                                     :tickId      stockTickId
                                                     :tickPrice   stockTickClose}}}})

          (let [ack (test-util/<message!! 1000)]
            (is (= {:type "data" :id 991 :payload {:data {:sellStock {:message "Ack"}}}}
                   ack))))))))

(deftest stream-portfolio-updates-test

  (let [{id :id :as createGameAck} (test-util/stock-buy-happy-path)]

    (test-util/send-data {:id   991
                          :type :start
                          :payload

                          {:query "subscription PortfolioUpdates($gameId: String!) {
                                         portfolioUpdates(gameId: $gameId) {
                                           message
                                         }
                                       }"
                           :variables {:gameId id}}})

    (as-> (:game/games state/system) gs
      (deref gs)
      (get gs (UUID/fromString id))
      (:control-channel gs)
      (core.async/>!! gs {:type :ControlEvent
                          :event :exit
                          :game-id id}))

    (let [expected-subscription-keys #{:game-id :stock-id :profit-loss-type :profit-loss}
          result (as-> (test-util/consume-subscriptions) ss
                   (filter #(= 991 (:id %)) ss)
                   (-> ss first :payload :data :portfolioUpdates :message)
                   (map #(clojure.edn/read-string %) ss))]

      (->> (map #(into #{} (keys %)) result)
           (map #(= expected-subscription-keys %))
           (every? true?)
           is))))

(deftest stream-game-events-test

  (let [{id :id :as createGameAck} (test-util/stock-buy-happy-path)]

    (test-util/send-data {:id   991
                          :type :start
                          :payload
                          {:query "subscription PortfolioUpdates($gameId: String!) {
                                     portfolioUpdates(gameId: $gameId) {
                                       message
                                     }
                                   }"
                           :variables {:gameId id}}})

    (test-util/send-data {:id   992
                          :type :start
                          :payload
                          {:query "subscription GameEvents($gameId: String!) {
                                     gameEvents(gameId: $gameId) {
                                       ... on ControlEvent {
                                         event
                                         gameId
                                       }
                                       ... on LevelStatus {
                                         event
                                         gameId
                                         profitLoss
                                         level
                                       }
                                       ... on LevelTimer {
                                         gameId
                                         level
                                         minutesRemaining
                                         secondsRemaining
                                       }
                                     }
                                   }"
                           :variables {:gameId id}}})

    (as-> (:game/games state/system) gs
      (deref gs)
      (get gs (UUID/fromString id))
      (:control-channel gs)
      (core.async/>!! gs {:type :ControlEvent
                          :event :exit
                          :game-id id}))

    (let [expected-game-events {:type "data"
                                :id 992
                                :payload
                                {:data
                                 {:gameEvents
                                  {:event "exit" :gameId id}}}}]

      (as-> (test-util/consume-subscriptions) ss
        (filter #(and (= 992 (:id %))
                      (= "exit" (-> % :payload :data :gameEvents :event))) ss)
        (first ss)
        (= expected-game-events ss)
        (is ss)))))

(deftest user-market-profit-loss-test

  (let [{gameId :id :as createGameAck} (test-util/stock-buy-happy-path)
        email                          "twashing@gmail.com"]

    (testing "Create a Game"

      (test-util/send-data {:id   991
                            :type :start
                            :payload
                            {:query "query UserPersonalProfitLoss($email: String!, $gameId: String!) {
                                       userPersonalProfitLoss(email: $email, gameId: $gameId) {
                                         profitLoss
                                         stockId
                                         gameId
                                         profitLossType
                                       }
                                     }"
                             :variables {:email email
                                         :gameId gameId}}}))

    (testing "We are returned expected game information [stocks subscriptions id]"

      (let [profit-loss (-> (test-util/<message!! 1000) :payload :data :userPersonalProfitLoss)

            expected-profit-loss-keys #{:gameId :stockId :profitLoss :profitLossType}
            expected-profit-losses #{{:profitLoss (float 0.0)
                                      :profitLossType "realized"}
                                     {:profitLoss (float 0.0)
                                      :profitLossType "running"}}]

        (->> (map #(into #{} (keys %)) profit-loss)
             (map #(= expected-profit-loss-keys %))
             (every? true?)
             is)

        (->> profit-loss
             (map #(select-keys % [:profitLoss :profitLossType]))
             (into #{})
             (= expected-profit-losses)
             is)))

    (as-> (:game/games state/system) gs
      (deref gs)
      (get gs (UUID/fromString gameId))
      (:control-channel gs)
      (core.async/>!! gs {:type :ControlEvent
                          :event :exit
                          :gameId gameId}))))
