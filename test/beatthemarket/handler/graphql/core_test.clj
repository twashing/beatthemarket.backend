(ns beatthemarket.handler.graphql.core-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [integrant.repl.state :as state]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [select transform ALL]]

            [beatthemarket.test-util :as test-util]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :refer [ppi] :as util]))


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


          (let [conn          (-> integrant.repl.state/system :persistence/datomic :opts :conn)
                email-initial "twashing@gmail.com"
                user-entity   (:db/id (ffirst (iam.persistence/user-by-email conn email-initial)))

                expected-email         email-initial
                ;; expected-name          "Timothy Washington"
                expected-account-names ["Cash" "Equity"]

                {:user/keys [email name]} (d/pull (d/db conn) '[*] user-entity)]

            (are [x y] (= x y)
              expected-email         email
              ;; expected-name          name
              )))

        (testing "Subsequent logins find an existing user"

          (let [expected-status 200
                expected-body-message "userexists"
                expected-headers {"Content-Type" "application/json"}

                expected-user-keys #{:id :userEmail #_:userName :userExternalUid :userAccounts}
                expected-user-account-keys #{:accountId :accountName :accountBalance :accountAmount}

                {status :status
                 body :body
                 headers :headers}
                (response-for service
                              :post "/api"
                              :body "{\"query\": \"mutation Login { login { message user }} \" }"
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (format "Bearer %s" id-token)})
                {{{user :user
                   message :message} :login} :data :as body-parsed} (json/read-str body :key-fn keyword)
                user-parsed (json/read-str user :key-fn keyword)]

            (->> (keys user-parsed)
                 (into #{})
                 (= expected-user-keys)
                 is)

            (->> user-parsed :userAccounts
                 (map keys)
                 (map #(into #{} %))
                 (map #(= expected-user-account-keys %))
                 (every? true?)
                 is)

            (are [x y] (= x y)
              expected-status status
              expected-headers headers
              expected-body-message message)))))))

(deftest user-query-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)]

    (test-util/login-assertion service id-token)

    (testing "Users query without limit"

      (let [{status :status
             body :body
             headers :headers}
            (response-for service
                          :post "/api"
                          :body "{\"operationName\":\"Users\",
                                      \"query\":\"query Users {
                                          users {
                                            userEmail
                                            userName
                                            userExternalUid
                                            subscriptions {
                                              paymentId
                                              productId
                                              provider
                                              __typename
                                            }
                                            games {
                                              gameId
                                              status
                                              profitLoss {
                                                profitLoss
                                                stockId
                                                gameId
                                                profitLossType
                                                __typename
                                              }
                                              __typename
                                            }
                                            __typename
                                          }
                                     }\" }"

                          :headers {"Content-Type" "application/json"
                                    "Authorization" (format "Bearer %s" id-token)})

            {{users :users} :data :as body-parsed} (json/read-str body :key-fn keyword)

            expected-user-games '({:userEmail "thelonious.monk@foo.com" :games "Infinity"}
                                  {:userEmail "john.coltrane@foo.com" :games 7.25}
                                  {:userEmail "charles.mingus@foo.com" :games 6.5}
                                  {:userEmail "sun.ra@foo.com" :games 0.6899999976158142}
                                  {:userEmail "herbie.hancock@foo.com" :games -1.25}
                                  {:userEmail "miles.davis@foo.com" :games -5.5})

            user-games (->> users
                            (map #(select-keys % [:userEmail :games]))
                            (transform [ALL :games ALL] #(get-in % [:profitLoss 0 :profitLoss]))
                            (transform [ALL :games] #(reduce + %)))]

        (is (= expected-user-games user-games))))

    (testing "Users query with limit"

      (let [expected-status 200

            {status :status
             body :body
             headers :headers}
            (response-for service
                          :post "/api"
                          :body "{\"operationName\":\"Users\",
                                      \"variables\":{\"limit\":5},
                                      \"query\":\"query Users($limit: Int!) {
                                          users(limit: $limit) {
                                            userEmail
                                            userName
                                            userExternalUid
                                            subscriptions {
                                              paymentId
                                              productId
                                              provider
                                              __typename
                                            }
                                            games {
                                              gameId
                                              status
                                              profitLoss {
                                                profitLoss
                                                stockId
                                                gameId
                                                profitLossType
                                                __typename
                                              }
                                              __typename
                                            }
                                            __typename
                                          }
                                     }\" }"

                          :headers {"Content-Type" "application/json"
                                    "Authorization" (format "Bearer %s" id-token)})

            {{users :users} :data :as body-parsed} (json/read-str body :key-fn keyword)

            expected-user-games '({:userEmail "thelonious.monk@foo.com" :games "Infinity"}
                                  {:userEmail "john.coltrane@foo.com" :games 7.25}
                                  {:userEmail "charles.mingus@foo.com" :games 6.5}
                                  {:userEmail "sun.ra@foo.com" :games 0.6899999976158142}
                                  {:userEmail "herbie.hancock@foo.com" :games -1.25})

            user-games (->> users
                            (map #(select-keys % [:userEmail :games]))
                            (transform [ALL :games ALL] #(get-in % [:profitLoss 0 :profitLoss]))
                            (transform [ALL :games] #(reduce + %)))]


        (is (= expected-user-games user-games))))))
