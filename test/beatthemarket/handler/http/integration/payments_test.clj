(ns beatthemarket.handler.http.integration.payments-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
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

    (test-util/<message!! 1000)

    (testing "Returning an empty set of user payments"

      (let [empty-user-payments (-> (test-util/<message!! 1000) :payload :data :userPayments)
            expected-user-payments []]

        (is (= expected-user-payments empty-user-payments))))))

(deftest verify-payment-apple-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "additional_balance_100k"
        provider "apple"
        token (-> "example-hash-apple.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                           :variables {:productId product-id
                                       :provider provider
                                       :token token}}})

    (test-util/<message!! 1000)

    (testing "Basic verify payment"

      (let [verify-payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)

            expected-verify-keys #{:paymentId :productId :provider}
            expected-verify-payment-response [{:productId product-id
                                               :provider provider}]]

        (->> verify-payment-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-verify-keys %))
             is)

        (is (= expected-verify-payment-response
               (map #(select-keys % [:productId :provider]) verify-payment-response)))))

    (testing "Subsequent call to list payments, includes the most recent"

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

      (test-util/<message!! 1000)

      (let [user-payments-response (-> (test-util/<message!! 1000) :payload :data :userPayments)
            expected-user-payment-keys #{:paymentId :productId :provider}
            expected-user-payments-response [{:productId product-id
                                              :provider provider}]]

        (->> user-payments-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-user-payment-keys %))
             is)

        (is (= expected-user-payments-response
               (map #(select-keys % [:productId :provider]) user-payments-response)))))))

(deftest verify-payment-apple2-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "additional_balance_100k"
        provider "apple"
        token (-> "example-hash-apple.2.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                           :variables {:productId product-id
                                       :provider provider
                                       :token token}}})

    (test-util/<message!! 1000)

    (testing "Basic verify payment"

      (let [verify-payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)

            expected-verify-keys #{:paymentId :productId :provider}
            expected-verify-payment-response [{:productId product-id
                                               :provider provider}]]

        (->> verify-payment-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-verify-keys %))
             is)

        (is (= expected-verify-payment-response
               (map #(select-keys % [:productId :provider]) verify-payment-response)))))

    (testing "Subsequent call to list payments, includes the most recent"

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

      (test-util/<message!! 1000)

      (let [user-payments-response (-> (test-util/<message!! 1000) :payload :data :userPayments)
            expected-user-payment-keys #{:paymentId :productId :provider}
            expected-user-payments-response [{:productId product-id
                                              :provider provider}]]

        (->> user-payments-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-user-payment-keys %))
             is)

        (is (= expected-user-payments-response
               (map #(select-keys % [:productId :provider]) user-payments-response)))))))

(deftest verify-payment-google-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "margin_trading_1month"
        provider "google"
        token (-> "example-hash-apple.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                           :variables {:productId product-id
                                       :provider provider
                                       :token token}}})

     (util/pprint+identity (test-util/<message!! 1000))
     (util/pprint+identity (test-util/<message!! 1000))

     ;; (test-util/<message!! 1000)
     ;; (test-util/<message!! 1000)

    ))

(deftest X-test

    #_(testing "Basic verify payment"

      (let [verify-payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)

            expected-verify-keys #{:paymentId :productId :provider}
            expected-verify-payment-response [{:productId product-id
                                               :provider provider}]]

        (->> verify-payment-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-verify-keys %))
             is)

        (is (= expected-verify-payment-response
               (map #(select-keys % [:productId :provider]) verify-payment-response)))))

    #_(testing "Subsequent call to list payments, includes the most recent"

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

      (test-util/<message!! 1000)

      (let [user-payments-response (-> (test-util/<message!! 1000) :payload :data :userPayments)
            expected-user-payment-keys #{:paymentId :productId :provider}
            expected-user-payments-response [{:productId product-id
                                              :provider provider}]]

        (->> user-payments-response
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-user-payment-keys %))
             is)

        (is (= expected-user-payments-response
               (map #(select-keys % [:productId :provider]) user-payments-response))))))

(defn delete-test-customer! [id]

  (test-util/send-data {:id   987
                        :type :start
                        :payload
                        {:query "mutation DeleteStripeCustomer($id: String!) {
                                           deleteStripeCustomer(id: $id) {
                                             message
                                           }
                                         }"
                         :variables {:id id}}})

  (test-util/<message!! 1000))

(deftest create-stripe-customer-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        email "foo@bar.com"]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Create customer if Stripe doesn't have it"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateStripeCustomer($email: String!) {
                                       createStripeCustomer(email: $email) {
                                         id
                                         email
                                       }
                                     }"
                             :variables {:email email}}})

      (test-util/<message!! 1000)
      (let [[{result-id :id
              result-email :email}] (-> (test-util/<message!! 1000) :payload :data :createStripeCustomer)]

        (is (= email result-email))
        (is (not (nil? result-id)))

        (testing "Calling createStripeCustomer when customer exists, returns that customer"

          (test-util/send-data {:id   988
                                :type :start
                                :payload
                                {:query "mutation CreateStripeCustomer($email: String!) {
                                           createStripeCustomer(email: $email) {
                                             id
                                             email
                                           }
                                         }"
                                 :variables {:email email}}})

          (test-util/<message!! 1000)
          (let [[{result-id2 :id
                  result-email2 :email}] (-> (test-util/<message!! 1000) util/pprint+identity :payload :data :createStripeCustomer)]

            (are [x y] (= x y)
              email result-email2
              result-id result-id2)))

        (testing "DELETEING test customer"
          (delete-test-customer! result-id))))))

(deftest verify-payment-stripe-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RCtpy369Bu4g" ;; Additional $100k
        provider "stripe"
        token (-> "example-payload-stripe.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                           :variables {:productId product-id
                                       :provider provider
                                       :token token}}})

     (util/pprint+identity (test-util/<message!! 1000))
     (util/pprint+identity (test-util/<message!! 1000))

     ;; (test-util/<message!! 1000)
     ;; (test-util/<message!! 1000)

    ))
