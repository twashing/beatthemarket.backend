(ns beatthemarket.handler.http.integration.payments-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [clojure.data.json :as json]
            [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]

            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [beatthemarket.integration.payments.core :as integration.payments.core]
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

(defn start-game-workflow []

  (test-util/<message!! 1000)
  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        gameLevel 1]

    (testing "Create a Game"

      (test-util/send-data {:id   987
                            :type :start
                            :payload
                            {:query "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                             :variables {:gameLevel gameLevel}}}))

    (testing "Start a Game"

      (let [{:keys [stocks id] :as create-game} (-> (test-util/<message!! 1000) :payload :data :createGame)]

        (test-util/send-data {:id   988
                              :type :start
                              :payload
                              {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                               :variables {:id id}}})

        (test-util/<message!! 5000)
        (-> (test-util/<message!! 1000) :payload :data :startGame)

        create-game))))

(defn verify-payment-workflow [client-id product-id provider token]

  (test-util/<message!! 1000)
  (test-util/send-init {:client-id (str client-id)})

  (test-util/<message!! 1000)
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

  (let [verify-payment (-> (test-util/<message!! 3000) :payload :data :verifyPayment)]

    (test-util/<message!! 1000)
    verify-payment))

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
           (map #(select-keys % [:productId :provider]) payment-response)))))

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

      (let [payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)]

        (verify-payment-response payment-response product-id provider)


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

          (test-util/<message!! 3000)

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
                   (map #(select-keys % [:productId :provider]) user-payments-response)))))


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

    (let [game-id-uuid (-> (start-game-workflow) :id UUID/fromString)]

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

      (test-util/<message!! 10000)
      (test-util/<message!! 10000)

      (let [payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)]

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

      (verify-payment-response (-> (test-util/<message!! 1000) :payload :data :verifyPayment)
                               product-id provider))

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

(deftest verify-payment-apple-subscription-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "margin_trading_1month.1"
        provider "apple"
        apple-payload (-> "ios.margin_trading.txt" resource slurp (json/read-str :key-fn keyword))]

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
                           :variables apple-payload}})

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

    ))

(deftest verify-payment-google-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        product-id "margin_trading_1month"
        provider "google"
        android-payload (-> "android.additional_100k.txt" resource slurp (json/read-str :key-fn keyword))]

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
                           :variables android-payload}})

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

    ))

#_(deftest X-test

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

(defn create-test-customer! [email]

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
  (test-util/<message!! 1000))

(deftest create-stripe-customer-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        email "foo@bar.com"]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Create customer if Stripe doesn't have it"

      (let [[{result-id :id
              result-email :email}] (-> (create-test-customer! email) :payload :data :createStripeCustomer)]

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
                  result-email2 :email}] (-> (test-util/<message!! 1000) :payload :data :createStripeCustomer)]

            (are [x y] (= x y)
              email result-email2
              result-id result-id2)))

        (testing "DELETEING test customer"
          (delete-test-customer! result-id))))))

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

      (test-util/<message!! 5000)
      (let [error-message (-> (test-util/<message!! 5000) :payload :errors first :message)
            expected-error-message "Your card has expired."]
        (is (= expected-error-message error-message))))

    (let [token (-> "example-payload-stripe-subscription-valid.json" resource slurp (json/read-str :key-fn keyword))
          email "foo@bar.com"]

      (testing "Create a test customer"

        (let [[{result-id :id
                result-email :email}] (-> (create-test-customer! email) :payload :data :createStripeCustomer)

              token-json (json/write-str
                           (assoc token
                                  :customerId result-id
                                  :paymentMethodId "pm_card_visa"))]

          (testing "Subsciption with a valid card"

            (test-util/send-data {:id   988
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

            (test-util/<message!! 1000)

            (verify-payment-response (-> (test-util/<message!! 5000) :payload :data :verifyPayment)
                                     product-id provider))

          (testing "DELETEING test customer"
            (delete-test-customer! result-id)))))))

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

      (let [email "foo@bar.com"
            [{result-id :id
              result-email :email}] (-> (create-test-customer! email) :payload :data :createStripeCustomer)]

        (testing "Subsciption with a valid card"

          (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
                game-id-uuid (-> (start-game-workflow) :id UUID/fromString)

                token (-> "example-payload-stripe-subscription-valid.json"
                          resource
                          slurp
                          (json/read-str :key-fn keyword))
                token-json (json/write-str
                             (assoc token
                                    :customerId result-id
                                    :paymentMethodId "pm_card_visa"))

                user-email "twashing@gmail.com"
                user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))]

            (test-util/send-data {:id   988
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

            (test-util/<message!! 1000)

            (verify-payment-response (-> (test-util/<message!! 5000) :payload :data :verifyPayment)
                                     product-id provider)

            (is (integration.payments.core/margin-trading? conn (:db/id user-entity)))))

        (testing "DELETEING test customer"
          (delete-test-customer! result-id))))))

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

    (let [game-id-uuid (-> (start-game-workflow) :id UUID/fromString)]


      (testing "Charge a valid card"

        (test-util/send-init {:client-id (str client-id)})
        (test-util/send-data {:id   989
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
        (test-util/<message!! 1000)

        (let [payment-response (-> (test-util/<message!! 3000) :payload :data :verifyPayment)]

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

        payload (-> "example-payload-stripe-charge2.json" resource slurp (json/read-str :key-fn keyword) ppi)]

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
            payment-response (-> (test-util/<message!! 3000) ppi :payload :data :verifyPayment)]

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
            product-id-product "prod_I1RCtpy369Bu4g" ;; Additional $100k

            token-product (-> "example-payload-stripe-charge.json" resource slurp)

            token-subscription (-> "example-payload-stripe-subscription-valid.json"
                                   resource
                                   slurp
                                   (json/read-str :key-fn keyword))

            conn (-> repl.state/system :persistence/datomic :opts :conn)
            email "foo@bar.com"

            user-email "twashing@gmail.com"
            user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))

            [{result-id :id}] (-> (create-test-customer! email) :payload :data :createStripeCustomer)
            token-subscription-json (json/write-str
                                      (assoc token-subscription
                                             :customerId result-id
                                             :paymentMethodId "pm_card_visa"))]

        (verify-payment-workflow client-id product-id-product provider token-product)
        (verify-payment-workflow client-id product-id-subscription provider token-subscription-json)


        (testing "Purchases are unapplied"

          (let [expected-unapplied-purchases #{product-id-subscription product-id-product}
                unapplied-payments-before-game-start
                (integration.payments.core/unapplied-payments-for-user conn (:db/id user-entity))]

            (->> (map :payment/product-id unapplied-payments-before-game-start)
                 (into #{})
                 (= expected-unapplied-purchases)
                 is)))


        (testing "Purchses are applied after game start"

          (let [game-id-uuid (-> (start-game-workflow) :id UUID/fromString)]

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
          (delete-test-customer! result-id))))))

(defn buy-sell-workflow [game-id target-buy-value]

  (test-util/send-data {:id   989
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

  (test-util/<message!! 1000)

  (let [latest-tick (->> (test-util/consume-subscriptions)
                         (filter #(= 989 (:id %)))
                         last)
        [{stockTickId :stockTickId
          stockTickTime :stockTickTime
          stockTickClose :stockTickClose
          stockId :stockId
          stockName :stockName}]
        (-> latest-tick :payload :data :stockTicks ppi)

        amount-to-buy (->> (/ target-buy-value stockTickClose)
                           (format "%.0f")
                           (#(Integer/parseInt %)))]

    (test-util/send-data {:id   990
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
                                               :tickPrice   stockTickClose}}}})))

;; in live game
;; additional 5 minutes
;; then consume timer events
;;
;; in paused game
;; additional 5 minutes
;; resume, then consume timer events
;;
;; after game end
;; game restart and join
;; additional 5 minutes
;; then consume timer events


;; TODO Fix subscription workflow
#_(deftest margin-trading-allows-upto-10x-cash-test

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)


    (testing "Purchase Product and Subscription before game start"

      (let [provider "stripe"

            product-id-subscription "prod_I1RAoB8UK5GDab" ;; Margin Trading
            token-subscription (-> "example-payload-stripe-subscription-valid.json"
                                   resource
                                   slurp
                                   (json/read-str :key-fn keyword))

            conn (-> repl.state/system :persistence/datomic :opts :conn)
            email "foo@bar.com"

            user-email "twashing@gmail.com"
            user-entity (ffirst (iam.persistence/user-by-email conn user-email '[:db/id]))

            [{result-id :id}] (-> (create-test-customer! email) :payload :data :createStripeCustomer)
            token-subscription-json (json/write-str
                                      (assoc token-subscription
                                             :customerId result-id
                                             :paymentMethodId "pm_card_visa"))]

        (verify-payment-workflow client-id product-id-subscription provider token-subscription-json)


        (testing "Purchses are applied after game start"

          (let [game-id (:id (ppi (start-game-workflow)))
                game-id-uuid (UUID/fromString game-id)]

            ;; (ppi (test-util/<message!! 1000))
            ;; (ppi (test-util/<message!! 1000))
            ;; (ppi (test-util/<message!! 1000))
            ;; (ppi (test-util/<message!! 1000))
            #_(testing "Make purchase, greater than Cash, less than Margin threshold"

              (let [target-buy-value 250000.0
                    expected-buy-ack {:type "data" :id 990 :payload {:data {:buyStock {:message "Ack"}}}}]

                (buy-sell-workflow game-id target-buy-value)

                (->> (test-util/<message!! 1000)
                     (= expected-buy-ack)
                     is)
                (test-util/<message!! 1000)))

            (testing "Make purchase, greater than Margin threshold"

              (let [target-buy-value 1850000.0
                    expected-error-message (-> (test-util/<message!! 1000) ppi :payload :errors :message)]

                (buy-sell-workflow game-id target-buy-value)

                (is (clojure.string/starts-with? expected-error-message "Margin account (10x Cash) is still Insufficient Funds [100000.0] for purchase value"))

                #_(ppi (test-util/<message!! 1000))
                #_(ppi (test-util/<message!! 1000))
                #_(ppi (test-util/<message!! 1000))))

            (games.control/update-short-circuit-game! game-id-uuid true)))

        (testing "DELETEING test customer"
          (delete-test-customer! result-id))))))


;; TODO
;; Apply additional 5 minutes
;; Notify client of applied purchases
