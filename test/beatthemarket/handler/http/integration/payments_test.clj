(ns beatthemarket.handler.http.integration.payments-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [clojure.data.json :as json]
            [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]

            [beatthemarket.handler.graphql.core :as graphql.core]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [beatthemarket.integration.payments.core :as integration.payments.core]

            [beatthemarket.handler.http.integration.util :as integration.util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
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

    (testing "Returning an empty set of user payments"

      (let [empty-user-payments (-> (test-util/consume-until 987) :payload :data :userPayments)
            expected-user-payments []]

        (is (= expected-user-payments empty-user-payments))))))

(defn verify-payment-response [payment-response product-id provider]

  (let [expected-verify-keys #{:paymentId :productId :provider}
        expected-verify-payment-response [{:productId product-id
                                           :provider provider}]]

    (->> payment-response
         (map keys)
         (map #(into #{} %))
         (every? #(= expected-verify-keys %))
         is)

    (is (= expected-verify-payment-response
           (->> payment-response
                (filter #(= product-id (:productId %)))
                (map #(select-keys % [:productId :provider])))))))

(deftest verify-payment-apple-test-no-game

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

      (let [payment-response (-> (test-util/consume-until 987) :payload :data :verifyPayment)]

        (verify-payment-response payment-response product-id provider)


        (testing "Subsequent call to list payments, includes the most recent"

          (test-util/send-data {:id   988
                                :type :start
                                :payload
                                {:query "query UserPayments {
                                           userPayments {
                                             paymentId
                                             productId
                                             provider
                                           }
                                         }"}})

          (let [user-payments-response (-> (test-util/consume-until 988) :payload :data :userPayments)
                expected-user-payment-keys #{:paymentId :productId :provider}
                expected-user-payments-response [{:productId product-id
                                                  :provider provider}]]

            (->> user-payments-response
                 (map keys)
                 (map #(into #{} %))
                 (every? #(= expected-user-payment-keys %))
                 is)

            (is (= expected-user-payments-response
                   (->> user-payments-response
                        (filter #(= product-id (:productId %)))
                        (map #(select-keys % [:productId :provider])))))))


        (testing "With no game, charge is not applied"

          (let [[{payment-id :paymentId}] payment-response
                conn (-> repl.state/system :persistence/datomic :opts :conn)

                {payment-applied :payment.applied/applied}
                (ffirst (payments.persistence/payment-by-id conn (UUID/fromString payment-id)))]

            (is (nil? payment-applied))))))))

(deftest verify-payment-apple-test-with-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "additional_balance_100k"
        provider "apple"
        token (-> "example-hash-apple.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [game-id-uuid (-> (integration.util/start-game-workflow) :id UUID/fromString)
          verify-payment-id 990]

      (test-util/send-init {:client-id (str client-id)})
      (test-util/send-data {:id   verify-payment-id
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

      (let [payment-response (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)]

        (verify-payment-response payment-response product-id provider)

        (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
              expected-cash-account-balance (.floatValue 200000.0)
              email "twashing@gmail.com"

              {cash-account-balance :bookkeeping.account/balance}
              (ffirst (game.persistence/cash-account-for-user-game conn email game-id-uuid))]

          (is (= expected-cash-account-balance cash-account-balance)))


        (testing "With a running game, charge is applied"

          (let [[{payment-id :paymentId}] payment-response
                conn (-> repl.state/system :persistence/datomic :opts :conn)
                email "twashing@gmail.com"

                expected-cash-account-balance (.floatValue 200000.0)

                {payment-applied :payment.applied/applied}
                (ffirst (payments.persistence/payment-by-id conn (UUID/fromString payment-id)))

                {cash-account-balance :bookkeeping.account/balance}
                (game.persistence/cash-account-for-user-game conn email game-id-uuid)]

            (is payment-applied)
            (is expected-cash-account-balance cash-account-balance))))

      (games.control/update-short-circuit-game! game-id-uuid true))))

(deftest verify-payment-apple2-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "additional_balance_100k"
        provider "apple"
        token (-> "example-hash-apple.2.json" resource slurp)]


    (test-util/send-init {:client-id (str client-id)})


    (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
          {user-db-id :id} (test-util/login-assertion service id-token)]

      (is (empty? (beatthemarket.integration.payments.core/payments-for-user conn user-db-id))))


    (let [verify-payment-id 987]

      (test-util/send-data {:id   verify-payment-id
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

      (testing "Basic verify payment"

        (verify-payment-response (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)
                                 product-id provider)))

    (testing "Subsequent call to list payments, includes the most recent"

      (let [user-payments-id 988]

        (test-util/send-data {:id   user-payments-id
                              :type :start
                              :payload
                              {:query "query UserPayments {
                                         userPayments {
                                           paymentId
                                           productId
                                           provider
                                         }
                                       }"}})

        (let [user-payments-response (-> (test-util/consume-until user-payments-id) :payload :data :userPayments)
              original-user-payments-count (count user-payments-response)


              expected-user-payment-keys #{:paymentId :productId :provider}
              expected-user-payments-response [{:productId product-id
                                                :provider provider}]]

          (->> user-payments-response
               (map keys)
               (map #(into #{} %))
               (every? #(= expected-user-payment-keys %))
               is)

          (is (= expected-user-payments-response
                 (->> user-payments-response
                      (filter #(= product-id (:productId %)))
                      (map #(select-keys % [:productId :provider])))))


          (testing "Subsequent VerifyPayments are idempotent"

            (let [verify-payment-id2 990
                  user-payments-id2 991]

              (test-util/send-data {:id   verify-payment-id2
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

              (test-util/send-data {:id   user-payments-id2
                                    :type :start
                                    :payload
                                    {:query "query UserPayments {
                                               userPayments {
                                                 paymentId
                                                 productId
                                                 provider
                                               }
                                             }"}})

              (let [updated-user-payments-response (-> (test-util/consume-until user-payments-id2) :payload :data :userPayments)
                    expected-user-payment-keys #{:paymentId :productId :provider}
                    expected-user-payments-response [{:productId product-id
                                                      :provider provider}]]

                (is (= original-user-payments-count (count updated-user-payments-response)))
                (->> updated-user-payments-response
                     (map keys)
                     (map #(into #{} %))
                     (every? #(= expected-user-payment-keys %))
                     is)

                (is (= expected-user-payments-response
                       (->> updated-user-payments-response
                            (filter #(= product-id (:productId %)))
                            (map #(select-keys % [:productId :provider])))))))))))

    ;; NOTE Stop DB connection from being closed
    (Thread/sleep 2000)))

(deftest verify-payment-apple-subscription-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "margin_trading_1month.1"
        provider "apple"
        verify-payment-id 987
        apple-payload (-> "ios.margin_trading.txt" resource slurp (json/read-str :key-fn keyword))]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (integration.util/verify-payment client-id apple-payload verify-payment-id)

    (let [expected-verify-payment-result [{:productId "margin_trading_1month.1"
                                           :provider "apple"}]
          verify-payment-result (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)]

      (->> verify-payment-result
           (filter #(= product-id (:productId %)))
           (map #(dissoc % :paymentId))
           (= expected-verify-payment-result)
           is))))

(deftest verify-payment-google-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "additional_100k"
        provider "google"
        android-payload (-> "android.additional_100k.txt" resource slurp (json/read-str :key-fn keyword))

        verify-payment-id 987]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id verify-payment-id
                          :type :start
                          :payload
                          {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                       verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                         paymentId
                                         productId
                                         provider
                                       }
                                     }"
                           :variables android-payload}})

    (let [expected-verify-payment-keys #{:paymentId :productId :provider}
          expected-verify-payment-response [{:productId "additional_100k"
                                             :provider "google"}]
          verify-payment-response (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)]

      (->> verify-payment-response
           first
           keys
           (into #{})
           (= expected-verify-payment-keys)
           is)

      (is (= expected-verify-payment-response
             (update verify-payment-response 0 #(dissoc % :paymentId)))))))

(deftest verify-payment-google-subscription-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "margin_trading_1month"
        provider "google"

        product-id "margin_trading_1month"
        provider "google"
        android-token (-> "android.margintrading.token.json" resource slurp)]

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
                                       :token android-token}}})

    (ppi (test-util/<message!! 1000))
    (ppi (test-util/<message!! 1000))

    ;; TODO complete
    #_{:type "data",
       :id 987,
       :payload
       {:data
        {:verifyPayment
         [{:paymentId "07c135e7-f56f-452f-9e53-2c376f2043c4",
           :productId "additional_100k",
           :provider "google"}]}}}

    (Thread/sleep 2000)))

(deftest create-stripe-customer-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        email "foo@bar.com"]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Create customer if Stripe doesn't have it"

      (let [create-customer-id                    987
            _                                     (integration.util/create-test-customer! email create-customer-id)
            [{result-id :id result-email :email}] (-> (test-util/consume-until create-customer-id) :payload :data :createStripeCustomer)]

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
          (let [[{result-id2    :id
                  result-email2 :email}] (-> (test-util/<message!! 1000) :payload :data :createStripeCustomer)]

            (are [x y] (= x y)
              email     result-email2
              result-id result-id2)))

        (testing "DELETEING test customer"
          (integration.util/delete-test-customer! result-id))))))

(deftest verify-stripe-subscription-test-no-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RAoB8UK5GDab" ;; Margin Trading
        provider "stripe"
        token (-> "example-payload-stripe-subscription-expired-card-error.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Subscription error with an invalid card"

      (let [verify-payment-id 987]

        (test-util/send-data {:id   verify-payment-id
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

        (let [error-message (-> (test-util/consume-until verify-payment-id) :payload :errors first :message)
              expected-error-message "Your card has expired."]
          (is (= expected-error-message error-message)))))

    (let [token (-> "example-payload-stripe-subscription-valid.json" resource slurp (json/read-str :key-fn keyword))
          email "foo@bar.com"]

      (testing "Create a test customer"

        (let [create-id 988]

          (integration.util/create-test-customer! email create-id)

          (let [[{result-id :id
                  result-email :email}] (-> (test-util/consume-until create-id) :payload :data :createStripeCustomer)

                token-json (json/write-str
                             (assoc token
                                    :customerId result-id
                                    :paymentMethodId "pm_card_visa"))]

            (testing "Subsciption with a valid card"

              (let [verify-payment-id2 989]

                (test-util/send-data {:id   verify-payment-id2
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
                                                   :token token-json}}})

                (verify-payment-response (-> (test-util/consume-until verify-payment-id2) :payload :data :verifyPayment)
                                         product-id provider))

              (testing "DELETEING test customer"
                (integration.util/delete-test-customer! result-id)))))))))

(deftest verify-stripe-subscription-idempotency-test-no-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RAoB8UK5GDab" ;; Margin Trading
        provider "stripe"
        token (-> "example-payload-stripe-subscription-expired-card-error.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (let [token (-> "example-payload-stripe-subscription-valid.json" resource slurp (json/read-str :key-fn keyword))
          email "foo@bar.com"]


      (testing "Create a test customer"

        (let [create-id 988]

          (integration.util/create-test-customer! email create-id)

          (let [[{result-id :id
                  result-email :email}] (-> (test-util/consume-until create-id) :payload :data :createStripeCustomer)]


            (testing "Subsciption with a valid card"

              (let [verify-payment-id 989
                    verify-payment-id2 990

                    token-json (json/write-str
                                 (assoc token
                                        :customerId result-id
                                        :paymentMethodId "pm_card_visa"))

                    payload {:productId product-id
                             :provider provider
                             :token token-json}]

                (testing "First subscription"

                  (integration.util/verify-payment client-id payload verify-payment-id)

                  (let [subscriptions1 (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)]

                    (integration.util/verify-payment client-id payload verify-payment-id2)

                    (is (= subscriptions1
                           (-> (test-util/consume-until verify-payment-id2) :payload :data :verifyPayment))))))

              (testing "DELETEING test customer"
                (integration.util/delete-test-customer! result-id)))))))))

(deftest verify-stripe-subscription-test-with-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RAoB8UK5GDab" ;; Margin Trading
        provider "stripe"
        token (-> "example-payload-stripe-subscription-expired-card-error.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (testing "Create a test customer"

      (let [email              "foo@bar.com"
            create-customer-id 987

            _                                     (integration.util/create-test-customer! email create-customer-id)
            [{result-id :id result-email :email}] (-> (test-util/consume-until create-customer-id) :payload :data :createStripeCustomer)]

        (testing "Subsciption with a valid card"

          (let [conn          (-> repl.state/system :persistence/datomic :opts :conn)
                create-id     990
                start-id      991
                stock-tick-id 992

                game-id-uuid (-> (integration.util/start-game-workflow create-id start-id stock-tick-id) :id UUID/fromString)

                token      (-> "example-payload-stripe-subscription-valid.json"
                               resource
                               slurp
                               (json/read-str :key-fn keyword))
                token-json (json/write-str
                             (assoc token
                                    :customerId result-id
                                    :paymentMethodId "pm_card_visa"))

                user-email  "twashing@gmail.com"
                user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))

                verify-payment-id 990]

            (test-util/send-data {:id   verify-payment-id
                                  :type :start
                                  :payload
                                  {:query     "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                             verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                               paymentId
                                               productId
                                               provider
                                             }
                                           }"
                                   :variables {:productId product-id
                                               :provider  provider
                                               :token     token-json}}})

            (verify-payment-response (-> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)
                                     product-id provider)

            (is (integration.payments.core/margin-trading? conn (:db/id user-entity)))))

        (testing "DELETEING test customer"
          (integration.util/delete-test-customer! result-id))))))

(deftest verify-stripe-charge-test-no-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RCtpy369Bu4g" ;; Additional $100k
        provider "stripe"
        token (-> "example-payload-stripe-charge.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/<message!! 1000)


    (testing "Charge a valid card"

      (test-util/send-init {:client-id (str client-id)})
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

      (let [payment-response (-> (test-util/<message!! 3000) :payload :data :verifyPayment)]

        (verify-payment-response payment-response product-id provider)


        (testing "With no game, charge is not applied"

          (let [[{payment-id :paymentId}] payment-response
                conn (-> repl.state/system :persistence/datomic :opts :conn)

                {payment-applied :payment.applied/applied}
                (ffirst (payments.persistence/payment-by-id conn (UUID/fromString payment-id)))]

            (is (nil? payment-applied))))))))

(deftest verify-stripe-charge-test-with-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1RCtpy369Bu4g" ;; Additional $100k
        provider "stripe"
        token (-> "example-payload-stripe-charge.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [game-id-uuid (-> (integration.util/start-game-workflow) :id UUID/fromString)]


      (testing "Charge a valid card"

        (let [payload {:productId product-id
                       :provider provider
                       :token token}

              verify-payment-id 990]

          (integration.util/verify-payment client-id payload 990))


        (let [payment-response (-> (test-util/consume-until 990) :payload :data :verifyPayment)]

          (verify-payment-response payment-response product-id provider)


          (testing "With a running game, charge is applied"

            (let [[{payment-id :paymentId}] payment-response
                  conn (-> repl.state/system :persistence/datomic :opts :conn)
                  email "twashing@gmail.com"

                  expected-cash-account-balance (.floatValue 200000.0)

                  {payment-applied :payment.applied/applied}
                  (ffirst (payments.persistence/payment-by-id conn (UUID/fromString payment-id)))

                  {cash-account-balance :bookkeeping.account/balance}
                  (game.persistence/cash-account-for-user-game conn email game-id-uuid)]

              (is payment-applied)
              (is expected-cash-account-balance cash-account-balance)))))

      (games.control/update-short-circuit-game! game-id-uuid true))))

(deftest verify-stripe-charge-test-with-example-payload

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        payload (-> "example-payload-stripe-charge2.json" resource slurp (json/read-str :key-fn keyword))]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/<message!! 1000)


    (testing "Charge a valid card"

      (test-util/send-init {:client-id (str client-id)})
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
                             :variables payload}})

      (test-util/<message!! 1000)

      (let [{product-id :productId
             provider :provider} payload
            payment-response (-> (test-util/<message!! 3000) :payload :data :verifyPayment)]

        (verify-payment-response payment-response product-id provider)))))

(deftest apply-product-and-subscription-payments-on-game-start-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Purchase Product and Subscription before game start"

      (let [provider "stripe"

            product-id-subscription "prod_I1RAoB8UK5GDab" ;; Margin Trading
            product-id-product      "prod_I1RCtpy369Bu4g" ;; Additional $100k

            token-product (-> "example-payload-stripe-charge.json" resource slurp)

            token-subscription (-> "example-payload-stripe-subscription-valid.json"
                                   resource
                                   slurp
                                   (json/read-str :key-fn keyword))

            conn  (-> repl.state/system :persistence/datomic :opts :conn)
            email "foo@bar.com"

            user-email  "twashing@gmail.com"
            user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))

            create-customer-id      987
            _                       (integration.util/create-test-customer! email create-customer-id)
            [{result-id :id}]       (-> (test-util/consume-until create-customer-id) :payload :data :createStripeCustomer)
            token-subscription-json (json/write-str
                                      (assoc token-subscription
                                             :customerId result-id
                                             :paymentMethodId "pm_card_visa"))]

        (integration.util/verify-payment-workflow client-id product-id-product provider token-product)
        (integration.util/verify-payment-workflow client-id product-id-subscription provider token-subscription-json)


        (testing "Purchases are unapplied"

          (let [expected-unapplied-purchases #{product-id-subscription product-id-product}
                unapplied-payments-before-game-start
                (integration.payments.core/unapplied-payments-for-user conn (:db/id user-entity))]

            (->> (map :payment/product-id unapplied-payments-before-game-start)
                 (into #{})
                 (= expected-unapplied-purchases)
                 is)))

        (Thread/sleep 2000)

        (testing "Purchses are applied after game start"

          (let [game-id-uuid (-> (integration.util/start-game-workflow) :id UUID/fromString)]

            (is (empty? (integration.payments.core/unapplied-payments-for-user conn (:db/id user-entity))))

            (let [expected-applied-purchases #{product-id-subscription product-id-product}
                  applied-payments-after-game-start
                  (integration.payments.core/applied-payments-for-user conn (:db/id user-entity))]

              (->> (map :payment/product-id applied-payments-after-game-start)
                   (into #{})
                   (= expected-applied-purchases)
                   is))

            (testing "Cash account is increased and Margin Trading is enabled"

              (let [expected-cash-account-balance (.floatValue 200000.0)

                    email "twashing@gmail.com"
                    {cash-account-balance :bookkeeping.account/balance}
                    (game.persistence/cash-account-for-user-game conn email game-id-uuid)]

                (is (integration.payments.core/margin-trading? conn (:db/id user-entity)))
                (is expected-cash-account-balance cash-account-balance)))

            (games.control/update-short-circuit-game! game-id-uuid true)))

        (testing "DELETEING test customer"
          (integration.util/delete-test-customer! result-id))))))

(defn run-trades! [conn
                   {user-db-id :db/id
                    userId :user/external-uid} ops data-sequence]

  (let [;; A
        data-sequence-fn (constantly data-sequence)
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts       {:level-timer-sec 5
                    :stagger-wavelength? false
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game! conn game-control)


        ;; E Buy Stock
        {stock-id  :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops-count (count ops)]

    (test-util/run-trades! iterations stock-id opts ops ops-count)
    game-control))

(deftest apply-previous-games-unused-payments-for-user-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        verify-payment-args {:productId "additional_balance_100k"
                             :provider "apple"
                             :token (-> "example-hash-apple.json" resource slurp)}]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Adding payment"

      (let [verify-payment-id 987]

        (test-util/send-data {:id verify-payment-id
                              :type :start
                              :payload
                              {:query "mutation VerifyPayment($productId: String!, $provider: String!, $token: String!) {
                                         verifyPayment(productId: $productId, provider: $provider, token: $token) {
                                           paymentId
                                           productId
                                           provider
                                         }
                                       }"
                               :variables verify-payment-args}})

        (test-util/consume-until verify-payment-id)))


    (let [conn       (-> repl.state/system :persistence/datomic :opts :conn)
          user-email "twashing@gmail.com"

          {user-db-id :db/id
           userId     :user/external-uid
           :as        user} (ffirst (iam.persistence/user-by-email conn user-email '[:db/id :user/external-uid]))

          data-sequence [120.0 115.0 100.0]
          ops           [{:op :buy :stockAmount 200}
                         {:op :sell :stockAmount 100}
                         {:op :sell :stockAmount 100}]]

      (testing "Running Game Lose 1"

        (let [{{game-id :game/id} :game} (run-trades! conn user ops data-sequence)

              expected-profitloss1 '({:profit-loss-type :realized-profit-loss
                                      :profit-loss -2000.0})]

          (games.control/exit-game! conn game-id)
          (->> (game.calculation/collect-realized-profit-loss-pergame conn user-db-id game-id true)
               (map #(select-keys % [:profit-loss-type :profit-loss]))
               (= expected-profitloss1)
               is)))

      (testing "Running Game Lose 2"

        (let [{{game-id :game/id} :game} (run-trades! conn user ops data-sequence)

              expected-profitloss2 '({:profit-loss-type :realized-profit-loss
                                      :profit-loss -2000.0}
                                     {:profit-loss-type :realized-profit-loss
                                      :profit-loss -2000.0})]

          (games.control/exit-game! conn game-id)
          (->> (game.calculation/collect-realized-profit-loss-allgames conn user-db-id true)
               (map #(select-keys % [:profit-loss-type :profit-loss]))
               (= expected-profitloss2)
               is)))

      (testing "Running subsequent game should apply unused payment balance"

        (let [;; A
              data-sequence-fn (constantly data-sequence)
              tick-length      (count (data-sequence-fn))

              ;; C
              sink-fn    identity
              opts       {:level-timer-sec 5
                          :stagger-wavelength? false
                          :user            {:db/id user-db-id}
                          :accounts        (game.core/->game-user-accounts)
                          :game-level      :game-level/one}

              ;; D Launch Game
              {{game-id    :game/id
                game-db-id :db/id
                stocks     :game/stocks
                :as        game} :game
               :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
              [_ iterations] (game.games/start-game! conn game-control)]

          (games.control/update-short-circuit-game! game-id true)

          (let [expected-applied-balance 196000.0

                {applied-balance :bookkeeping.account/balance}
                (ffirst (game.persistence/cash-account-for-user-game conn user-email game-id))]

            (is (= expected-applied-balance applied-balance))))))))

(defn subscribe-to-game-events [subscription-id game-id]

  (test-util/send-data {:id   subscription-id
                        :type :start
                        :payload
                        {:query "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }"
                         :variables {:gameId game-id}}}))

;; TODO
;; GameEvents... on LevelTimer subscription is breaking on Type
#_(deftest additional-5-minutes-test-with-game

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1REIJ0bKeXG37" ;; Additional 5 minutes
        provider "stripe"
        token (-> "example-payload-stripe-charge-5-minutes.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [game-id (:id (integration.util/start-game-workflow))
          game-id-uuid (UUID/fromString game-id)

          game-events-id 992]

      ;;A
      (test-util/send-data {:id   game-events-id
                            :type :start
                            :payload
                            {:query "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }"
                             :variables {:gameId game-id}}})

      ;; B
      (testing "Charge a valid card"

        (let [verify-payment-id 1000]

          (integration.util/verify-payment client-id {:productId product-id
                                                      :provider provider
                                                      :token token} verify-payment-id)

          (let [payment-response (->> (test-util/consume-until verify-payment-id) :payload :data :verifyPayment)]

            (verify-payment-response payment-response product-id provider)

            (testing "With a running game, charge is applied"

              (let [[{payment-id :paymentId}] payment-response
                    conn (-> repl.state/system :persistence/datomic :opts :conn)
                    email "twashing@gmail.com"

                    expected-cash-account-balance (.floatValue 200000.0)

                    {payment-applied :payment.applied/applied}
                    (ffirst (payments.persistence/payment-by-id conn (UUID/fromString payment-id)))

                    {cash-account-balance :bookkeeping.account/balance}
                    (game.persistence/cash-account-for-user-game conn email game-id-uuid)]

                (is payment-applied)
                (is expected-cash-account-balance cash-account-balance))))))

      ;; C
      (testing "Game timer has been extended by 5 minutes"

        (let [payment-response (->> (test-util/consume-until game-events-id) ppi)

              expected-timer-response
              {:type "data"
               :id game-events-id
               :payload {:data
                         {:gameEvents
                          {:gameId game-id
                           :level 1
                           :minutesRemaining 9
                           :secondsRemaining 58}}}}]

          (is (= expected-timer-response payment-response))))

      (Thread/sleep 3000)
      (games.control/update-short-circuit-game! game-id-uuid true))))


;; in paused game
;; additional 5 minutes
;; resume, then consume timer events
;;
;; after game end
;; game restart and join
;; additional 5 minutes
;; then consume timer events

(deftest additional-5-minutes-test-after-game-end

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "prod_I1REIJ0bKeXG37" ;; Additional 5 minutes
        provider "stripe"
        token (-> "example-payload-stripe-charge-5-minutes.json" resource slurp)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (let [game-id (:id (integration.util/start-game-workflow))
          game-id-uuid (UUID/fromString game-id)
          verify-payment-id 990]


      ;;A
      ;; (subscribe-to-game-events 1000 game-id)

      ;; B
      (integration.util/verify-payment client-id {:productId product-id
                                                  :provider provider
                                                  :token token} verify-payment-id)
      (test-util/consume-until verify-payment-id)

      ;; C
      (integration.util/exit-game game-id)


      (Thread/sleep 4000)

      (testing "Restart the game"

        (let [restart-game-id 1000
              expected-restart-game {:type "data"
                                     :id restart-game-id
                                     :payload {:data {:restartGame
                                                      {:event "restart"
                                                       :gameId game-id}}}}]

          (integration.util/restart-game game-id restart-game-id)

          (->> (test-util/consume-subscriptions 1000)
               (filter #(= restart-game-id (:id %)))
               (filter #(= "data" (:type %)))
               last
               (= expected-restart-game)
               is)))


      (let [game-events-id 1001]

        (subscribe-to-game-events game-events-id game-id)

        (testing "i. Game timer has been extended by 5 minutes
                  ii. Running P/L is empty"

            (is (empty? (game.calculation/running-profit-loss-for-game game-id-uuid)))

            ;; D
            (let [payment-response (->> (test-util/consume-subscriptions 1000)
                                        (filter #(= game-events-id (:id %)))
                                        last)

                  expected-timer-response
                  {:type "data"
                   :id game-events-id
                   :payload {:data
                             {:gameEvents
                              {:gameId game-id
                               :level 1
                               :minutesRemaining 9
                               :secondsRemaining 58}}}}]

              (is (= expected-timer-response payment-response)))))


      (games.control/update-short-circuit-game! game-id-uuid true))))

(defn buy-sell-workflow

  ([game-id target-buy-value]

   (buy-sell-workflow game-id target-buy-value 989 990))

  ([game-id target-buy-value subscribe-stock-tick-id buy-id]

   (test-util/send-data {:id   subscribe-stock-tick-id
                         :type :start
                         :payload
                         {:query "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }"
                          :variables {:gameId game-id}}})

   (let [latest-tick (->> (test-util/consume-subscriptions)
                          (filter #(= subscribe-stock-tick-id (:id %)))
                          last)
         [{stockTickId :stockTickId
           stockTickTime :stockTickTime
           stockTickClose :stockTickClose
           stockId :stockId
           stockName :stockName}]
         (-> latest-tick :payload :data :stockTicks)

         amount-to-buy (->> (/ target-buy-value stockTickClose)
                            (format "%.0f")
                            (#(Integer/parseInt %)))]

     (test-util/send-data {:id   buy-id
                           :type :start
                           :payload
                           {:query "mutation BuyStock($input: BuyStock!) {
                                           buyStock(input: $input) {
                                             message
                                           }
                                         }"
                            :variables {:input {:gameId      game-id
                                                :stockId     stockId
                                                :stockAmount amount-to-buy
                                                :tickId      stockTickId
                                                :tickPrice   stockTickClose}}}}))))



;; TODO Fix subscription workflow - subscriptions stopped streaming in test
#_(deftest margin-trading-allows-upto-10x-cash-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Purchase Product and Subscription before game start"

      (let [provider "stripe"

            product-id-subscription "prod_I1RAoB8UK5GDab" ;; Margin Trading
            token-subscription      (-> "example-payload-stripe-subscription-valid.json"
                                        resource
                                        slurp
                                        (json/read-str :key-fn keyword))

            conn  (-> repl.state/system :persistence/datomic :opts :conn)
            email "foo@bar.com"

            user-email  "twashing@gmail.com"
            user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))

            create-customer-id      987
            verify-payment-id       990

            ;; A
            _                       (integration.util/create-test-customer! email create-customer-id)
            [{result-id :id}]       (-> (test-util/consume-until create-customer-id) :payload :data :createStripeCustomer)
            token-subscription-json (json/write-str
                                      (assoc token-subscription
                                             :customerId result-id
                                             :paymentMethodId "pm_card_visa"))]

        ;; B
        (integration.util/verify-payment-workflow client-id product-id-subscription provider token-subscription-json verify-payment-id)


        (testing "Purchses are applied after game start"

          (let [subscribe-stock-tick-id 1010
                buy-id                  1011

                ;; C
                game-id      (:id (integration.util/start-game-workflow 1000 1001 subscribe-stock-tick-id))
                game-id-uuid (UUID/fromString game-id)]


            (testing "Make purchase, greater than Cash, less than Margin threshold"

              (let [target-buy-value 250000.0
                    expected-buy-ack {:type "data" :id buy-id :payload {:data {:buyStock {:message "Ack"}}}}]

                (buy-sell-workflow game-id target-buy-value subscribe-stock-tick-id buy-id)

                (->> (test-util/consume-until buy-id)
                     (= expected-buy-ack)
                     is)

                #_(testing "Make purchase, greater than Margin threshold"

                  (let [target-buy-value 1850000.0
                        buy-id           1021]

                    (buy-sell-workflow game-id target-buy-value
                                       subscribe-stock-tick-id buy-id)

                    (let [expected-error-message-part "Insufficient Funds on Margin account (10x Cash)"
                          error-message               (-> (test-util/consume-until buy-id) :payload :errors second :message)]

                      (is (clojure.string/starts-with? error-message expected-error-message-part)))))))

            (Thread/sleep 2000)
            (games.control/update-short-circuit-game! game-id-uuid true)))

        (testing "DELETEING test customer"
          (integration.util/delete-test-customer! result-id))))))


;; TODO
;; Apply additional 5 minutes
;; Notify client of applied purchases
