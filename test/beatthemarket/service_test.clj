(ns beatthemarket.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [integrant.repl.state :as state]
            [beatthemarket.service :as service]
            [beatthemarket.test-util :refer [component-fixture]]))


(use-fixtures :once component-fixture)


(def service (-> state/system :server/server :io.pedestal.http/service-fn))

(deftest home-page-test
  (let [expected-body "Hello World!"
        expected-headers {"Content-Type" "text/html;charset=UTF-8"}
        {:keys [body headers]} (response-for service :get "/")]

    (are [x y] (= x y)
      expected-body body
      expected-headers headers)))

(deftest about-page-test

  (let [expected-body "Clojure 1.10.0 - served from /about"
        expected-headers {"Content-Type" "text/html;charset=UTF-8"}
        {:keys [body headers]} (response-for service :get "/about")]

    (are [x y] (= x y)
      expected-body body
      expected-headers headers)))
