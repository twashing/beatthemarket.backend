(ns beatthemarket.graphql-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [integrant.repl.state :as state]
            [clojure.data.json :as json]
            [datomic.client.api :as d]

            [beatthemarket.test-util :as test-util]
            [beatthemarket.graphql :as sut]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.persistence.user :as persistence.user]
            [beatthemarket.util :as util]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest login-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)]

    (testing "Basic login with a new user"

      (let [id-token (test-util/->id-token)]

        (test-util/login-assertion service id-token)

        (testing "A lookup of the added user"


          (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)
                email-initial "twashing@gmail.com"
                user-entity (persistence.user/user-by-email conn email-initial)

                expected-email email-initial
                expected-name "Timothy Washington"
                expected-account-names ["Cash" "Equity"]

                {:user/keys [email name accounts]} (d/pull (d/db conn) '[*] (ffirst user-entity))
                account-names (->> accounts
                                   (map :bookkeeping.account/name)
                                   sort)]

            (are [x y] (= x y)
              expected-email email
              expected-name name
              expected-account-names account-names)))

        (testing "Subsequent logins find an existing user"

          (let [expected-status 200
                expected-body {:data {:login "user-exists"}}
                expected-headers {"Content-Type" "application/json"}

                {status :status
                 body :body
                 headers :headers}
                (response-for service
                              :post "/api"
                              :body "{\"query\": \"{ login }\"}"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (format "Bearer %s" id-token)})

                body-parsed (json/read-str body :key-fn keyword)]

            (are [x y] (= x y)
              expected-status status
              expected-body body-parsed
              expected-headers headers)))))))
