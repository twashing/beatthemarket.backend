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
            [integrant.repl.state :as repl.state]

            [beatthemarket.game.games :as game.games]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.handler.http.service :as http.service]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.persistence.core :as persistence.core]

            [beatthemarket.handler.http.integration.util :as integration.util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util]
            [clj-time.coerce :as c])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8080/ws"))

(deftest subscription-handler-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)]

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

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

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

    (testing "We are returned expected game information [stocks subscriptions id]"

      (let [{:keys [stocks id]} (-> (test-util/consume-until 987) :payload :data :createGame)]

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

                  :input-sequence
                  :stocks-with-tick-data
                  :cash-position-at-game-start
                  :profit-loss
                  :current-level
                  :tick-sleep-atom
                  :level-timer
                  :sink-fn
                  :close-sink-fn

                  :control-channel
                  :stock-tick-stream
                  :portfolio-update-stream
                  :game-event-stream

                  :process-transact!
                  :process-transact-level-update!
                  :process-transact-profit-loss!

                  :calculate-profit-loss
                  :check-level-complete

                  :stream-level-update!
                  :stream-portfolio-update!
                  :stream-stock-tick

                  :group-stock-tick-pairs
                  :client-id
                  :game-level
                  :short-circuit-game?
                  :accounts :user}]

            (->> repl.state/system :game/games deref (#(get % game-id)) keys
                 (into #{})
                 (= expected-component-game-keys)
                 is)))))))

(deftest one-game-per-user-per-device-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))

    (testing "Create a Game"

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

      (let [conn                                         (-> repl.state/system :persistence/datomic :opts :conn)
            {game-id :id}                                (-> (test-util/consume-until 987) :payload :data :createGame)

            {[{client-id-persisted :game.user/user-client}] :game/users}
            (ffirst (persistence.core/entity-by-domain-id conn :game/id (UUID/fromString game-id)))]

        (is (= client-id client-id-persisted))

        (testing "User / device pair can only have 1 running game"

          (test-util/send-data {:id   989
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
                                 :variables {:id game-id}}})

          (Thread/sleep 200)

          (testing "Creating another game should throw an error"

            (test-util/send-data {:id   988
                                  :type :start
                                  :payload
                                  {:query "mutation CreateGame($gameLevel: Int!) {
                                             createGame(gameLevel: $gameLevel) {
                                               id
                                               stocks { id name symbol }
                                             }
                                           }"
                                   :variables {:gameLevel gameLevel}}})

            (let [errors (-> (test-util/consume-until 988) :payload :errors)
                  expected-error-count 1]

              (is (= expected-error-count (count errors)))
              (is (clojure.string/starts-with? (-> errors first :message) "User device has a running game")))))))))

(deftest check-empty-client-id-start-or-resume-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))

    (testing "Create a Game"

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

      (test-util/<message!! 1000)

      (let [{game-id :id} (-> (test-util/<message!! 1000) :payload :data :createGame)]

        (testing "User / device pair can only have 1 running game"

          (testing "Starting a game should pass the :client-id, otherwise throw an error"

            (test-util/send-init)
            (test-util/<message!! 1000)

            (test-util/send-data {:id   989
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
                                   :variables {:id game-id}}})

            (test-util/<message!! 1000)

            (let [errors (-> (test-util/<message!! 1000) :payload :errors)
                  expected-error-count 1
                  expected-error-message "Missing :client-id in your connection_init"]

              (are [x y] (= x y)
                expected-error-count (count errors)
                expected-error-message (-> errors first :message))))

          (testing "Resuming a game should pass the :client-id, otherwise throw an error"

            (test-util/send-data {:id   990
                                  :type :start
                                  :payload
                                  {:query "mutation ResumeGame($gameId: String!) {
                                             resumeGame(gameId: $gameId) {
                                               event
                                               gameId
                                             }
                                           }"
                                   :variables {:gameId game-id}}})

            (test-util/<message!! 1000)

            (let [errors (-> (test-util/<message!! 1000) :payload :errors)
                  expected-error-count 1
                  expected-error-message "Missing :client-id in your connection_init"]

              (are [x y] (= x y)
                expected-error-count (count errors)
                expected-error-message (-> errors first :message)))))))))

(deftest start-game-resolver-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})


    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

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

    (testing "Start a Game"

      (test-util/<message!! 1000)

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

        (let [expected-result []
              result (-> (test-util/<message!! 1000) :payload :data :startGame)]

          (is (= expected-result result)))))))

(deftest start-game-resolver-with-start-position-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

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

    (testing "Start a Game"

      (test-util/<message!! 1000)

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

        (as-> (:game/games repl.state/system) gs
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

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        gameLevel 1

        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})

    (testing "REST Login (not WebSocket) ; creates a user"

      (test-util/login-assertion service id-token))


    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks  { id name symbol }
                                       }
                                     }"
                             :variables {:gameLevel gameLevel}}}))

    (testing "Start a Game"

      (test-util/<message!! 1000)

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

        (as-> (:game/games repl.state/system) gs
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

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [{:keys [stocks id]} (integration.util/start-game-workflow)
          stock-tick-id       989]

      (let [latest-tick (test-util/consume-until stock-tick-id)

            [{stockTickId :stockTickId
              stockTickTime :stockTickTime
              stockTickClose :stockTickClose
              stockId :stockId
              stockName :stockName}]
            (-> latest-tick :payload :data :stockTicks)

            buy-stock-id 990]

        (test-util/send-data {:id   buy-stock-id
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

        (let [ack (test-util/consume-until buy-stock-id)]

          (is (= {:type "data" :id 990 :payload {:data {:buyStock {:message "Ack"}}}}
                 ack)))))))

(deftest buy-stock-insufficient-funds-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [{:keys [stocks id]} (integration.util/start-game-workflow)
          stock-tick-id      989]

      (let [latest-tick (test-util/consume-until stock-tick-id)

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
                                                   :stockAmount 10000
                                                   :tickId      stockTickId
                                                   :tickPrice   stockTickClose}}}})

        (testing "We are recieving the correct InsufficientFunds error message"

          (let [expected-error-message {:message "InsufficientFunds"
                                        :locations [{:line 2 :column 44}]
                                        :path ["buyStock"]
                                        :extensions {:arguments {:input "$input"}}}]

            (->> (test-util/<message!! 1000)
                 :payload
                 :errors
                 (filter #(= "InsufficientFunds" (:message %)))
                 first
                 (= expected-error-message)
                 is)))))))

(deftest sell-stock-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [{:keys [stocks id]} (integration.util/start-game-workflow)
          stock-tick-id       989]

      (let [latest-tick (test-util/consume-until stock-tick-id)

            [{stockTickId :stockTickId
              stockTickTime :stockTickTime
              stockTickClose :stockTickClose
              stockId :stockId
              stockName :stockName}]
            (-> latest-tick :payload :data :stockTicks)

            buy-id 990]

        (test-util/send-data {:id   buy-id
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

        ;; NOTE If the client doesn't consume the GQL buy response, the corresponding stock account isn't generated
        ;; Ie, clients can get a corresponding error message: "Cannot find corresponding account for stockId [79164837200112]"
        (test-util/consume-until buy-id)

        (testing "Selling the stock"

          (let [sell-id 991]

            (test-util/send-data {:id   sell-id
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

            (let [ack (test-util/consume-until sell-id)]
              (is (= {:type "data" :id 991 :payload {:data {:sellStock {:message "Ack"}}}}
                     ack)))))))))

(deftest sell-stock-before-owning-errors-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [{:keys [stocks id]} (integration.util/start-game-workflow)
          stock-tick-id       989]

      (let [latest-tick (test-util/consume-until stock-tick-id)

            [{stock-tick-id :stockTickId
              stock-tick-time :stockTickTime
              stock-tick-close :stockTickClose
              stock-id :stockId
              stock-name :stockName}]
            (-> latest-tick :payload :data :stockTicks)

            stock-amount 100
            buy-id 990]

        (integration.util/buy-stock buy-id id stock-id stock-amount stock-tick-id stock-tick-close)
        (test-util/consume-until buy-id)

        (testing "Selling the stock"

          (let [sell-id 991
                oversell-id 992]

            (integration.util/sell-stock sell-id id stock-id stock-amount stock-tick-id stock-tick-close)
            (let [ack (test-util/consume-until sell-id)]
              (is (= {:type "data" :id 991 :payload {:data {:sellStock {:message "Ack"}}}}
                     ack)))

            (integration.util/sell-stock oversell-id id stock-id stock-amount stock-tick-id stock-tick-close)
            (let [expected-error-ack
                  {:type "data"
                   :id oversell-id
                   :payload
                   {:data {:sellStock nil}
                    :errors
                    [{:message "Insufficient amount of stock [0] to sell"
                      :locations [{:line 2 :column 44}]
                      :path ["sellStock"]
                      :extensions {:arguments {:input "$input"}}}]}}

                  ack (test-util/consume-until oversell-id)]

              (is (= expected-error-ack ack)))))))))

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

    (as-> (:game/games repl.state/system) gs
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

#_(deftest stream-game-events-test

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

    (as-> (:game/games repl.state/system) gs
      (deref gs)
      (get gs (UUID/fromString id))
      (:control-channel gs)
      (core.async/>!! gs {:type :ControlEvent
                          :event :exit
                          :game-id id}))))

(deftest user-market-profit-loss-test

  (let [{gameId :id :as createGameAck} (test-util/stock-buy-happy-path)
        email                          "twashing@gmail.com"]

    ;; (Thread/sleep 2000)

    (testing "Selling the stock (in 2 blocks)"

      ;; Block 1
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
                              {:query "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }"
                               :variables {:input {:gameId      gameId
                                                   :stockId     stockId
                                                   :stockAmount 50
                                                   :tickId      stockTickId
                                                   :tickPrice   stockTickClose}}}}))

      ;; Bloack 2
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
                              {:query "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }"
                               :variables {:input {:gameId      gameId
                                                   :stockId     stockId
                                                   :stockAmount 50
                                                   :tickId      stockTickId
                                                   :tickPrice   stockTickClose}}}})))

    (test-util/<message!! 1000)
    (test-util/<message!! 1000)

    (testing "Query a User's P/L (all)"

      (test-util/send-data {:id   991
                            :type :start
                            :payload
                            {:query "query UserPersonalProfitLoss($email: String!, $gameId: String, $groupByStock: Boolean) {
                                       userPersonalProfitLoss(email: $email, gameId: $gameId, groupByStock: $groupByStock) {
                                         profitLoss
                                         stockId
                                         gameId
                                         profitLossType
                                       }
                                     }"
                             :variables {:email email
                                         :gameId gameId
                                         :groupByStock false}}})

      (test-util/<message!! 1000)

      (testing "We are returned expected game information [stocks subscriptions id]"

        (let [profit-loss (-> (test-util/<message!! 1000) :payload :data :userPersonalProfitLoss)

              expected-profit-loss-count 2
              expected-profit-loss-keys #{:gameId :stockId :profitLoss :profitLossType}
              expected-profit-loss {;; :profitLoss 61.0
                                      ;; :stockId stockId
                                      :gameId gameId
                                      :profitLossType "realized"}]

          (is (= expected-profit-loss-count (count profit-loss)))

          (->> (map #(into #{} (keys %)) profit-loss)
               (map #(= expected-profit-loss-keys %))
               (every? true?)
               is)

          (->> profit-loss
               (map #(select-keys % [:gameId :profitLossType]))
               (every? #(= expected-profit-loss %))
               is))))

    (testing "Query a User's P/L (grouped by stock)"

      (test-util/send-data {:id   991
                            :type :start
                            :payload
                            {:query "query UserPersonalProfitLoss($email: String!, $gameId: String, $groupByStock: Boolean) {
                                       userPersonalProfitLoss(email: $email, gameId: $gameId, groupByStock: $groupByStock) {
                                         profitLoss
                                         stockId
                                         gameId
                                         profitLossType
                                       }
                                     }"
                             :variables {:email email
                                         :gameId gameId
                                         :groupByStock true}}})

      (test-util/<message!! 1000)

      (testing "We are returned expected game information [stocks subscriptions id]"

        (let [profit-loss (-> (test-util/<message!! 1000) :payload :data :userPersonalProfitLoss)

              expected-profit-loss-count 1
              expected-profit-loss-keys #{:gameId :stockId :profitLoss :profitLossType}
              expected-profit-losses #{{:gameId gameId
                                        :profitLossType "realized"}}]

          (is (= expected-profit-loss-count (count profit-loss)))

          (->> (map #(into #{} (keys %)) profit-loss)
               (map #(= expected-profit-loss-keys %))
               (every? true?)
               is)

          (->> profit-loss
               (map #(select-keys % [:gameId :profitLossType]))
               (into #{})
               (= expected-profit-losses)
               is))))

    (as-> (:game/games repl.state/system) gs
      (deref gs)
      (get gs (UUID/fromString gameId))
      (:control-channel gs)
      (core.async/>!! gs {:type :ControlEvent
                          :event :exit
                          :gameId gameId}))))

(deftest start-exit-start-game-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [{:keys [stocks id]} (integration.util/start-game-workflow)]

      (let [exit-message-id 993
              expected-exit-response {:type "data"
                                      :id exit-message-id
                                      :payload
                                      {:data
                                       {:exitGame
                                        {:event "exit" :gameId id}}}}]

          (integration.util/exit-game id exit-message-id)
          (is (= expected-exit-response
                 (test-util/consume-until exit-message-id)))

          (Thread/sleep 2000))

      (let [restart-message-id 994
              expected-restart-response {:type "data"
                                         :id restart-message-id
                                         :payload
                                         {:data
                                          {:restartGame
                                           {:event "restart" :gameId id}}}}]

          (integration.util/restart-game id restart-message-id)
          (is (= expected-restart-response
                 (test-util/consume-until restart-message-id)))

          (Thread/sleep 2000))

      (games.control/update-short-circuit-game! (UUID/fromString id) true))))
