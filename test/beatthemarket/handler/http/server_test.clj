(ns beatthemarket.handler.http.server-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
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
            [beatthemarket.persistence.user :as persistence.user]
            [beatthemarket.util :as util]
            [clj-time.coerce :as c])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))


(deftest basic-handler-test

  (with-redefs [auth/auth-request-handler identity]

    (testing "Basic GraphQL call"
      (let [service (-> state/system :server/server :io.pedestal.http/service-fn)

            expected-status 200
            expected-body "{\"data\":{\"hello\":\"Hello, Clojurians!\"}}"
            expected-headers {"Content-Type" "application/json"}
            {:keys [status body headers]} (response-for service
                                                        :post "/api"
                                                        :body "{\"query\": \"{ hello }\"}"
                                                        :headers {"Content-Type" "application/json"})]

        (are [x y] (= x y)
          expected-status status
          expected-body body
          expected-headers headers)))

    (testing "Basic REST call"
      (let [service (-> state/system :server/server :io.pedestal.http/service-fn)

            expected-status 200
            expected-body "Hello World!"
            expected-headers {"Content-Type" "text/html;charset=UTF-8"}
            {:keys [status body headers]} (response-for service :get "/")]

        (are [x y] (= x y)
          expected-status status
          expected-body body
          expected-headers headers)))))

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

(deftest subscription-resolver-test

  ;; REST Login (not WebSocket) ; creates a user
  (let [service  (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token))

  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"})

  (test-util/send-data {:id 987
                        :type :start
                        :payload
                        {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

  (test-util/expect-message {:id 987
                             :payload {:data {:ping {:message "short #1"}}}
                             :type "data"}))

(deftest new-game-subscription-test

  ;; A. REST Login (not WebSocket) ; creates a user
  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token))


  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"})

  ;; B. NEW GAME
  (testing "Creating a new game returns subscriptions and all stocks"

    (test-util/send-data {:id 987
                          :type :start
                          :payload
                          {:query "subscription { newGame( message: \"Foobar\" ) { message } }"}})

    (let [parse-newGame-message (fn [a] (-> a :payload :data :newGame :message (#(json/read-str % :key-fn keyword))))

          data                              (test-util/<message!! 1000)
          message                           (parse-newGame-message data)
          {:keys [id stocks subscriptions]} message]

      (is (some (into #{} stocks) subscriptions))

      (testing "Returned game is what's registered in the :game/games component"

        (let [game-id (UUID/fromString id)
              expected-component-game-keys
              (sort '(:game :stocks-with-tick-data :tick-sleep-ms :data-subscription-channel :control-channel :close-sink-fn :sink-fn))]

          (-> state/system :game/games deref (get game-id) keys sort
              (= expected-component-game-keys)
              is)))

      (testing "Subscription is being streamed to client"

        (let [[t0-time _v0 id0] (parse-newGame-message (test-util/<message!! 1000))
              [t1-time _v1 id1] (parse-newGame-message (test-util/<message!! 1000))]

          (is (t/after?
                (c/from-long (Long/parseLong t1-time))
                (c/from-long (Long/parseLong t0-time))))

          (testing "Two ticks streamed to client, got saved to the DB"

            (let [conn (-> state/system :persistence/datomic :conn)

                  tick-id0 (UUID/fromString id0)
                  tick-id1 (UUID/fromString id1)]

              (->> (d/q '[:find ?e
                          :in $ [?tick-id ...]
                          :where
                          [?e :game.stock.tick/id ?tick-id]]
                        (d/db conn)
                        [tick-id0 tick-id1])
                   count
                   (= 2)
                   is))))))))

(deftest buy-stock-test

  ;; A. REST Login (not WebSocket) ; creates a user
  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token))


  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"})

  (testing "First setting up a test harness"

    (let [conn                       (-> state/system :persistence/datomic :conn)
          email                      "twashing@gmail.com"
          {user-id :db/id}           (ffirst (persistence.user/user-by-email conn email))
          sink-fn                    identity
          {{game-id :game/id} :game} (game.games/create-game! conn user-id sink-fn)
          {stock-id :game.stock/id}  (ffirst (test-util/generate-stocks! conn 1))]

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
                                                 :tickId      "asdf"
                                                 :tickTime    3456
                                                 :tickPrice   1234.45}}}})))

  (let [ack (test-util/<message!! 1000)]

    (is (= {:type "data" :id 987 :payload {:data {:buyStock {:message "Ack"}}}}
           ack))))

#_(deftest buy-stock!-test

    (let [conn         (-> repl.state/system :persistence/datomic :conn)
          stock-amount 100
          stock-price  50.47

          game-id  nil
          user-id  nil
          stock-id nil]

      (testing "Cannot buy stock with having created a game"

        (is (thrown? AssertionError (bookkeeping/buy-stock! conn game-id user-id stock-id stock-amount stock-price)))

        (testing "Cannot buy stock without having a user"

          (let [user-id                  (:db/id (test-util/generate-user! conn))
                sink-fn                  identity
                {{game-id :db/id} :game} (games/create-game! conn user-id sink-fn)
                stock-id                 (ffirst (test-util/generate-stocks! conn 1))]

            (testing "Buying a stsock creates a tentry"

              (let [{tentry-id :db/id} (bookkeeping/buy-stock! conn game-id user-id stock-id stock-amount stock-price)]

                (is (util/exists? tentry-id))

                (let [pulled-tentry               (persistence.core/pull-entity conn tentry-id)
                      pulled-stock                (persistence.core/pull-entity conn stock-id)
                      expected-stock-account-name (format "STOCK.%s" (:game.stock/name pulled-stock))
                      new-stock-account           (-> pulled-tentry
                                                      :bookkeeping.tentry/credits first
                                                      :bookkeeping.credit/account)]

                  (testing "debit is cash account"
                    (->> pulled-tentry
                         :bookkeeping.tentry/debits first
                         :bookkeeping.debit/account
                         :bookkeeping.account/name
                         (= "Cash")
                         is))

                  (testing "credit is stock account"
                    (->> new-stock-account
                         :bookkeeping.account/name
                         (= expected-stock-account-name)
                         is))

                  (testing "We have new stock account"
                    (-> (d/q '[:find ?e
                               :in $ ?stock-aacount-name
                               :where [?e :bookkeeping.account/name ?stock-aacount-name]]
                             (d/db conn)
                             expected-stock-account-name)
                        first
                        util/exists?
                        is))

                  (testing "Debits + Credits balance"

                    (is (bookkeeping/tentry-balanced? pulled-tentry))
                    (is (bookkeeping/value-equals-price-times-amount? pulled-tentry)))

                  (testing "Stock account is bound to the game.user's set of accounts"

                    (let [game-pulled (persistence.core/pull-entity conn game-id)]

                      (->> game-pulled
                           :game/users first
                           :game.user/user
                           :user/accounts
                           (map :bookkeeping.account/id)
                           (some #{(:bookkeeping.account/id new-stock-account)})
                           is)

                      (testing "Portfolio now has value of
                                +stock account
                                -cash account"

                        (let [cash-starting-balance (-> repl.state/system :game/game :starting-balance)
                              stock-starting-balance 0.0
                              value-change (Float. (format "%.2f" (* stock-amount stock-price)))

                              game-user-accounts (->> game-pulled
                                                      :game/users first
                                                      :game.user/user
                                                      :user/accounts)]

                          (->> game-user-accounts
                               (filter #(= "Cash" (:bookkeeping.account/name %)))
                               first
                               :bookkeeping.account/balance
                               (= (- cash-starting-balance value-change))
                               is)

                          (->> game-user-accounts
                               (filter #(clojure.string/starts-with? (:bookkeeping.account/name %) "STOCK."))
                               first
                               :bookkeeping.account/balance
                               (= (+ stock-starting-balance value-change))
                               is)))))))))))))
