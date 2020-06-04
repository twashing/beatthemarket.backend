(ns beatthemarket.server-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [aero.core :as aero]
            [io.pedestal.http :as server]
            [beatthemarket.test-util :refer [component-fixture]]
            [integrant.core :as ig]
            [integrant.repl.state :as state]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.server :as sut]))


(use-fixtures :each component-fixture)


#_(deftest inject-lacinia-configuration-test

  (let [profile :development
        config (-> "config.edn"
                   resource
                   (aero.core/read-config {:profile profile})
                   :integrant)

        {service :service/service} (ig/init config [:service/service])

        expected-paths
        '([""]
          ["api"]
          ["ide"]
          ["" "about"]
          ["assets" "graphiql" :path]
          ["assets" "graphiql" :path])]


    (->> service
         io.pedestal.http/default-interceptors
         auth/auth-interceptor
         sut/inject-lacinia-configuration
         :io.pedestal.http/routes
         (map :path-parts)
         sort
         (= expected-paths)
         is)))

;; subscription-handler-test
(deftest basic-handler-test

  ;; (-> state/system :server/server pprint)
  ;; (-> state/system :server/server :io.pedestal.http/routes pprint)

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
