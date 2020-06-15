(ns beatthemarket.handler.http.server-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [aero.core :as aero]
            [clojure.data.json :as json]
            [io.pedestal.http :as server]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [beatthemarket.test-util :as test-util]
            [integrant.repl.state :as state]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.handler.http.service :as http.service]
            [beatthemarket.util :as util]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8080/ws"))


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
  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
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

    (let [data (test-util/<message!!)
          message (-> data :payload :data :newGame :message (#(json/read-str % :key-fn keyword)))

          {:keys [stocks subscriptions]} (transform [MAP-VALS ALL] #(dissoc % :id) message)]

      (is (some (into #{} stocks) subscriptions)))))


;; Buy Stock
;; Sell Stock
;; Calculate Profit / Loss
;; Complete a Level
;; Win a Game
;; Lose a Game
