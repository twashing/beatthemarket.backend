(ns beatthemarket.server-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [aero.core :as aero]
            [io.pedestal.http :as server]
            [beatthemarket.test-util :as test-util]
            [integrant.core :as ig]
            [integrant.repl.state :as state]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.server :as sut]))


(use-fixtures :once test-util/component-fixture)
(use-fixtures :each (test-util/subscriptions-fixture "ws://localhost:8888/ws"))

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

  (testing "Auth interceptor rejects GQL call"
    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)

          expected-error-status 401
          {status :status} (response-for service
                                         :post "/api"
                                         :body "{\"query\": \"{ hello }\"}"
                                         :headers {"Content-Type" "application/json"})]

      (is (= expected-error-status status)))))

(deftest subscriptions-ws-request
  (test-util/send-init)
  (test-util/expect-message {:type "connection_ack"}))
