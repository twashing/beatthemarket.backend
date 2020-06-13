(ns beatthemarket.handler.http.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [integrant.repl.state :as state]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.handler.http.service :as service]
            [beatthemarket.test-util :refer [component-prep-fixture component-fixture]]))


(use-fixtures :once (partial component-prep-fixture :test))
(use-fixtures :each component-fixture)

(deftest home-page-test

  (with-redefs [auth/auth-request-handler identity]
    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          expected-body "Hello World!"
          expected-headers {"Content-Type" "text/html;charset=UTF-8"}
          {:keys [body headers]} (response-for service :get "/")]

      (are [x y] (= x y)
        expected-body body
        expected-headers headers))))

(deftest about-page-test

  (with-redefs [auth/auth-request-handler identity]
    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          expected-body "Clojure 1.10.0 - served from /about"
          expected-headers {"Content-Type" "text/html;charset=UTF-8"}
          {:keys [body headers]} (response-for service :get "/about")]

      (are [x y] (= x y)
        expected-body body
        expected-headers headers))))
