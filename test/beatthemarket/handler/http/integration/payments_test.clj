(ns beatthemarket.handler.http.integration.payments-test
  (:require [clojure.test :refer :all]
            [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8081/ws"))


;; > User, subscriptions (incl. payment provider) (GET)
;; check DB
;;
;; > Verify (One time) Purchase (POST)
;; store product, provider, token
;;
;; > Verify Subscription (POST)
;; store product, provider, token
;;
;; Update subscription (Webhook - POST)

(deftest user-payments-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        ;; gameLevel 1
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "query UserPayments {
                                       userPayments {
                                         paymentId
productId
provider
                                       }
                                     }"}})

    (util/pprint+identity (test-util/<message!! 1000))
    (util/pprint+identity (test-util/<message!! 1000))

    (is true)
    ))
