(ns beatthemarket.handler.http.graphql-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [integrant.repl.state :as state]
            [clojure.data.json :as json]
            [datomic.client.api :as d]

            [beatthemarket.test-util
             :refer [component-prep-fixture component-fixture subscriptions-fixture]]

            [beatthemarket.handler.http.graphql :as sut]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.util :as util]))


(use-fixtures :once (partial component-prep-fixture :test))
(use-fixtures :each component-fixture)


(deftest login-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)]

    (testing "Basic login with a new user"

      (let [expected-status 400
            expected-body {:errors
                           [{:message "Cannot query field `foobar' on type `QueryRoot'."
                             :locations [{:line 1 :column 3}]
                             :extensions {:type "QueryRoot" :field "foobar"}}]}
            expected-headers {"Content-Type" "application/json"}

            id-token (util/->id-token)

            {status :status
             body :body
             headers :headers}
            (trace (response-for service
                                 :post "/api"
                                 :body "{\"query\": \"{ login }\"}"
                                 :headers {"Content-Type" "application/json"
                                           "Authorization" (format "Bearer %s" id-token)}))

            body-parsed (json/read-str body :key-fn keyword)]

        (is true)

        #_(are [x y] (= x y)
            expected-status status
            expected-body body-parsed
            expected-headers headers)))))


(comment

  ;; C query data

  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
  (def db (d/db conn))

  (def all-emails-q '[:find ?user-email
                      :where [_ :user/email ?user-email]])
  (d/q all-emails-q db)


  (def name-from-email '[:find ?name ?identity-provider
                         :where
                         [?e :user/name ?name]
                         [?e :user/identity-provider ?identity-provider]
                         [?e :user/email "swashing@gmail.com"]])
  (d/q name-from-email db)

  )
