(ns beatthemarket.handler.http.server-test
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
        id-token (test-util/->id-token)]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame {
                                       createGame {
                                         id
                                         stocks
                                       }
                                     }"}}))

    (testing "We are returned expected game information [stocks subscriptions id]"

      (let [result (test-util/<message!! 1000)
            {:keys [stocks id]} (-> result :payload :data :createGame)]

        (is (UUID/fromString id))
        (is (= 4 (count stocks)))
        (->> (map #(json/read-str % :key-fn keyword) stocks)
             (map keys)
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

                  :transact-profit-loss-xf
                  :stream-portfolio-update-xf
                  :calculate-profit-loss-xf
                  :collect-profit-loss-xf
                  :transact-tick-xf
                  :stream-stock-tick-xf

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
        id-token (test-util/->id-token)]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame {
                                       createGame {
                                         id
                                         stocks
                                       }
                                     }"}}))

    (testing "Start a Game"

      (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

        (test-util/send-data {:id   987
                              :type :start
                              :payload
                              {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           message
                                         }
                                       }"
                               :variables {:id id}}})

        (as-> (:game/games state/system) gs
          (deref gs)
          (get gs (UUID/fromString id))
          (:control-channel gs)
          (core.async/>!! gs :exit))

        (Thread/sleep 1000)

        (let [_ (test-util/<message!! 1000)

              expected-result {:message :gamestarted}
              result (-> (test-util/<message!! 1000) :payload :data :startGame)]

          (= expected-result result))))))

(deftest stream-stock-ticks-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame {
                                       createGame {
                                         id
                                         stocks
                                       }
                                     }"}}))

    (testing "Start a Game"

      (let [{:keys [stocks id]} (-> (test-util/<message!! 1000) :payload :data :createGame)]

        (test-util/send-data {:id   987
                              :type :start
                              :payload
                              {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           message
                                         }
                                       }"
                               :variables {:id id}}})

        (util/pprint+identity (test-util/<message!! 1000))
        (util/pprint+identity (test-util/<message!! 1000))

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
          (core.async/>!! gs :exit))
        (Thread/sleep 1000)

        (util/pprint+identity (test-util/<message!! 1000))

        (let [expected-keys #{:stockTickId :stockTickTime :stockTickClose :stockId :stockName}
              stockTicks (-> (test-util/<message!! 1000) :payload :data :stockTicks)]

          (->> (map #(into #{} (keys %)) stockTicks)
               (map #(= expected-keys %))
               (every? true?)
               is))))))


;; > Stream Stock Ticks
;; > Stream P/L, Account Balances
;; > Stream Game Events

#_(deftest buy-stock-test

  ;; A. REST Login (not WebSocket) ; creates a user
  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token))


  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"})

  (testing "First setting up a test harness"

    (let [conn                              (-> state/system :persistence/datomic :opts :conn)
          email                             "twashing@gmail.com"
          {user-id :db/id}                  (ffirst (iam.persistence/user-by-email conn email))
          sink-fn                           identity
          {{game-id :game/id :as game} :game
           control-channel             :control-channel
           :as                         game-control} (game.games/create-game! conn user-id sink-fn)
          game-user-subscription            (-> game
                                                :game/users first
                                                :game.user/subscriptions first)
          stock-id                          (:game.stock/id game-user-subscription)
          test-chan                         (core.async/chan)
          game-loop-fn                      (fn [a]
                                              (core.async/>!! test-chan a))
          {{:keys [mixer
                   pause-chan
                   input-chan
                   output-chan] :as channel-controls}
           :channel-controls}               (game.games/start-game! conn user-id game-control game-loop-fn)]

      (core.async/<!! (core.async/timeout 7000))
      #_(game.games/control-streams! control-channel channel-controls :exit)

      (let [{tick-price :game.stock.tick/close
             tick-time  :game.stock.tick/trade-time
             tick-id    :game.stock.tick/id}
            (-> (<!! test-chan)
                (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
                first
                second)]

        (test-util/send-data {:id   987
                              :type :start
                              :payload
                              {:query "mutation BuyStock($input: BuyStock!) {
                                       buyStock(input: $input) {
                                         message
                                       }
                                     }"

                               :variables {:input {:gameId      (str game-id)
                                                   :stockId     (str stock-id)
                                                   :stockAmount 100
                                                   :tickId      (str tick-id)
                                                   :tickTime    (.intValue (Long. tick-time))
                                                   :tickPrice   tick-price}}}}))))

  (let [ack (test-util/<message!! 1000)]

    (is (= {:type "data" :id 987 :payload {:data {:buyStock {:message "Ack"}}}}
           ack))))

#_(deftest sell-stock-test

  ;; A. REST Login (not WebSocket) ; creates a user
  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token))


  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"})

  (testing "First setting up a test harness"

    (let [conn                              (-> state/system :persistence/datomic :opts :conn)
          email                             "twashing@gmail.com"
          {user-id :db/id}                  (ffirst (iam.persistence/user-by-email conn email))
          sink-fn                           identity
          {{game-id :game/id :as game} :game
           control-channel             :control-channel
           :as                         game-control} (game.games/create-game! conn user-id sink-fn)
          game-user-subscription            (-> game
                                                :game/users first
                                                :game.user/subscriptions first)
          stock-id                          (:game.stock/id game-user-subscription)
          test-chan                         (core.async/chan)
          game-loop-fn                      (fn [a]
                                              (core.async/>!! test-chan a))
          {{:keys                                           [mixer
                                                             pause-chan
                                                             input-chan
                                                             output-chan] :as channel-controls}
           :channel-controls}               (game.games/start-game! conn user-id game-control game-loop-fn)]

      (core.async/<!! (core.async/timeout 7000))
      #_(game.games/control-streams! control-channel channel-controls :exit)

      (let [{tick-price :game.stock.tick/close
             tick-time  :game.stock.tick/trade-time
             tick-id    :game.stock.tick/id}
            (-> (<!! test-chan)
                (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
                first
                second)]

        (testing "Initial stock purchase"
          (test-util/send-data {:id   987
                                :type :start
                                :payload
                                {:query "mutation BuyStock($input: BuyStock!) {
                                           buyStock(input: $input) {
                                             message
                                           }
                                         }"

                                 :variables {:input {:gameId      (str game-id)
                                                     :stockId     (str stock-id)
                                                     :stockAmount 100
                                                     :tickId      (str tick-id)
                                                     :tickTime    (.intValue (Long. tick-time))
                                                     :tickPrice   tick-price}}}})
          (test-util/<message!! 1000)
          (test-util/<message!! 1000))

        (testing "Now selling the stock"
          (test-util/send-data {:id   987
                                :type :start
                                :payload
                                {:query "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }"

                                 :variables {:input {:gameId      (str game-id)
                                                     :stockId     (str stock-id)
                                                     :stockAmount 100
                                                     :tickId      (str tick-id)
                                                     :tickTime    (.intValue (Long. tick-time))
                                                     :tickPrice   tick-price}}}})

          (let [ack (test-util/<message!! 1000)]

            (is (= {:type "data" :id 987 :payload {:data {:sellStock {:message "Ack"}}}}
                   ack))))))))
