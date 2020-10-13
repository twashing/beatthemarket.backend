(ns beatthemarket.handler.http.integration.support-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [integrant.repl.state :as repl.state]
            [clojure.data.json :as json]

            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each test-util/component-fixture)

(deftest privacy-policy-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)]

    (let [expected-status 200
          {status :status} (response-for service :get "/privacy")]

      (is (= expected-status status)))))

(deftest terms-and-conditions-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)]

    (let [expected-status 200
          {status :status} (response-for service :get "/terms")]

      (is (= expected-status status)))))

(deftest support-url-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)]

    (let [expected-status 200
          {status :status} (response-for service :get "/support")]

      (is (= expected-status status)))))
