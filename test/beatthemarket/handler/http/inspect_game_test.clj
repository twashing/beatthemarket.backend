(ns beatthemarket.handler.http.inspect-game-test
  (:require [clojure.test :refer :all]
            [integrant.repl.state :as state]
            [com.rpl.specter :refer [transform ALL]]

            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :as util])
  #_(:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [clojure.core.async :as core.async
             :refer [<!!]]
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
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util]
            [clj-time.coerce :as c])
  #_(:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))


(def expected-user {:userEmail "twashing@gmail.com"
                    :userName "Timothy Washington"
                    :userAccounts
                    [{:accountName "Cash"
                      :accountBalance 100000.0
                      :accountAmount 0}
                     {:accountName "Equity"
                      :accountBalance 100000.0
                      :accountAmount 0}]})
(def expected-user-keys #{:userEmail :userName :userExternalUid :userAccounts})
(def expected-user-account-keys #{:accountId :accountName :accountBalance :accountAmount})

(deftest query-user-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"]


    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "query User($email: String!) {
                                     user(email: $email) {
                                       userEmail
                                       userName
                                       userExternalUid
                                       userAccounts
                                       { accountId
                                         accountName
                                         accountBalance
                                         accountAmount
                                       }
                                     }
                                   }"
                           :variables {:email email}}})


    (let [result-user (-> (test-util/<message!! 1000) :payload :data :user)]

      (->> (keys result-user)
           (into #{})
           (= expected-user-keys)
           is)

      (->> (:userAccounts result-user)
           (map keys)
           (map #(into #{} %))
           (map #(= expected-user-account-keys %))
           (every? true?)
           is)

      (->> (transform [identity] #(dissoc % :userExternalUid) result-user)
           (transform [:userAccounts ALL] #(dissoc % :accountId))
           (= expected-user)
           is))))

(deftest query-users-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        email "twashing@gmail.com"]


    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "query Users {
                                     users {
                                       userEmail
                                       userName
                                       userExternalUid
                                       userAccounts
                                       { accountId
                                         accountName
                                         accountBalance
                                         accountAmount
                                       }
                                     }
                                   }"}})

    (let [result-users (-> (test-util/<message!! 1000) :payload :data :users)]

      (->> (map keys result-users)
           (map #(into #{} %))
           (map #(= expected-user-keys %))
           is)

      (->> (map :userAccounts result-users)
           (map #(map keys %))
           (map #(map (fn [a] (into #{} a)) %))
           (map #(map (fn [a] (= expected-user-account-keys a)) %))
           (map #(every? true? %))
           (every? true?)
           is)

      (->> (first result-users)
           (transform [identity] #(dissoc % :userExternalUid))
           (transform [:userAccounts ALL] #(dissoc % :accountId))
           (= expected-user)
           is))))


#_{:userEmail "twashing@gmail.com"
 :userName "Timothy Washington"
 :userExternalUid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"
 :userAccounts
 [{:accountId "9ac6c44c-5fd0-4de0-9412-a4b923408031"
   :accountName "Cash"
   :accountBalance 100000.0
   :accountAmount 0}
  {:accountId "fdfcf6b1-eaba-4dcf-89ab-03fbfbcd35fa"
   :accountName "Equity"
   :accountBalance 100000.0
   :accountAmount 0}]}
